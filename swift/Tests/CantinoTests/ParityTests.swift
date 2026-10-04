import Foundation
import Testing
@testable import Cantino

// The Swift runner of the cross-platform parity corpus (tests/parity; the
// contract is tests/parity/README.md). It replays tests/parity/calls.json
// through the Swift API, renders every outcome in the canonical form
// (Canonical.swift) and compares the result file byte for byte with
// tests/parity/expected.json minus the `abi_only` calls, which a typed API
// cannot express (NULL pointers, invalid kind codes, malformed JSON for a
// typed argument, legacy import options). Skipping is decided by that flag
// alone.
//
// API used per op (README "Op → platform API"):
//   import, open, close, get, get_many, query, way_coordinates,
//   representative_point, get_from_other_thread
//                          public OsmStore (synchronous; the wrong-thread
//                          call uses the same store from a new Thread)
//   basemap_info           public PmtilesInfo.read
//   slice_*                internal SliceProtocol (as Kotlin's; no public
//                          equivalent until the area download lifecycle)
//   basemap_plan_new, basemap_bad_feed, basemap_extract
//                          internal BasemapPlan / BasemapAssembler (the
//                          public extract does its own HTTP and cannot be
//                          fed canned bytes). basemap_plan_new.malformed_bbox
//                          passes its raw bbox text through
//                          BasemapPlan(bboxJSON:...), which mirrors Kotlin's
//                          NativeBridge.basemapPlanNew(String, ...).
//
// The C ABI (expected.json, written by tests/parity.rs) is the reference: a
// mismatch is a Swift adapter bug. Never regenerate expected.json from here.
//
// Threading: a synchronous test function never changes threads, so every
// store, plan and assembler is used on the thread that created it, as the
// core's thread confinement requires.

@Suite struct ParityTests {
    static let corpus = Fixtures.repoRoot.appendingPathComponent("tests/parity")

    @Test func replayParityCorpus() throws {
        let callsText = try String(contentsOf: Self.corpus.appendingPathComponent("calls.json"), encoding: .utf8)
        guard case .array(let calls) = try JSONValue.parse(callsText) else {
            Issue.record("calls.json is not an array")
            return
        }
        let expectedText = try String(contentsOf: Self.corpus.appendingPathComponent("expected.json"), encoding: .utf8)
        let expectedLines = try parseResultFile(expectedText)

        let runner = try ParityRunner()
        var actualLines: [String] = []
        var keptExpected: [String] = []
        var ran: [String: Int] = [:]
        var skipped: [String: Int] = [:]
        for call in calls {
            let id = try call.required("id").requiredString()
            let op = try call.required("op").requiredString()
            guard let expected = expectedLines[id] else {
                Issue.record("\(id): no line in expected.json (regenerate it with the Rust runner)")
                continue
            }
            if call["abi_only"]?.boolValue == true {
                skipped[op, default: 0] += 1
                continue
            }
            ran[op, default: 0] += 1
            let result = runner.run(id: id, op: op, args: call["args"] ?? .object([:]))
            let line = JSONValue.object(["id": .string(id), "result": result]).canonicalString
            actualLines.append(line)
            keptExpected.append(expected)
            if line != expected {
                Issue.record("parity mismatch in \(id)\n  expected \(expected)\n    actual \(line)")
            }
        }
        #expect(runner.openAreas.isEmpty, "calls.json must close every area it opens: \(runner.openAreas)")

        // The whole file, byte for byte (README "Result file"), so layout
        // differences cannot hide behind the per-line comparison.
        let actualFile = "[\n" + actualLines.joined(separator: ",\n") + "\n]\n"
        let expectedFile = "[\n" + keptExpected.joined(separator: ",\n") + "\n]\n"
        #expect(Array(actualFile.utf8) == Array(expectedFile.utf8), "result file differs from expected.json minus abi_only")
        #expect(expectedLines.count == calls.count, "expected.json has \(expectedLines.count) lines for \(calls.count) calls")

        let summary = Set(ran.keys).union(skipped.keys).sorted()
            .map { "\($0): ran \(ran[$0] ?? 0), skipped \(skipped[$0] ?? 0)" }
            .joined(separator: "\n  ")
        print("parity corpus: ran \(ran.values.reduce(0, +)), skipped (abi_only) \(skipped.values.reduce(0, +))\n  \(summary)")
    }

    /// expected.json's lines by call id. Each line is one canonical
    /// `{"id":...,"result":...}` object (README "Result file"); ids are
    /// plain ASCII without escapes, so the prefix identifies the call.
    private func parseResultFile(_ text: String) throws -> [String: String] {
        let prefix = #"{"id":""#
        var lines: [String: String] = [:]
        for raw in text.split(separator: "\n") {
            var line = String(raw)
            guard line.hasPrefix(prefix) else { continue } // "[" and "]"
            if line.hasSuffix(",") { line.removeLast() }
            let rest = line.dropFirst(prefix.count)
            guard let end = rest.range(of: #"","result":"#) else {
                Issue.record("unexpected expected.json line: \(line)")
                continue
            }
            lines[String(rest[..<end.lowerBound])] = line
        }
        return lines
    }
}

/// Runs one call at a time; owns the scratch directory and the open areas.
private final class ParityRunner {
    let root = Fixtures.repoRoot
    let scratch: URL
    private var stores: [String: OsmStore] = [:]

    var openAreas: [String] { stores.keys.sorted() }

    init() throws {
        scratch = try Fixtures.scratchDirectory("osm-parity")
    }

    /// `scratch:<name>` inside this run's scratch directory, anything else
    /// relative to the repository root (README "Calls").
    func path(_ value: JSONValue?) throws -> String {
        let path = try value.required("path argument").requiredString()
        if path.hasPrefix("scratch:") {
            return scratch.appendingPathComponent(String(path.dropFirst("scratch:".count))).path
        }
        return root.appendingPathComponent(path).path
    }

    func run(id: String, op: String, args: JSONValue) -> JSONValue {
        do {
            return try dispatch(op: op, args: args)
        } catch let error as CorpusShapeError {
            Issue.record("\(id): cannot express this call through the Swift API: \(error.message)")
            return .string("unexpressible call")
        } catch {
            return outcome(of: error, id: id)
        }
    }

    /// The `{"error":{...}}` outcome of an adapter error; anything else
    /// (a closed store, a non-Cantino error) is a runner bug.
    private func outcome(of error: any Error, id: String) -> JSONValue {
        if let outcome = JSONValue.errorOutcome(error) { return outcome }
        Issue.record("\(id): unexpected error \(error)")
        return .string("unexpected error: \(error)")
    }

    /// Runs `body`, turning a thrown adapter error into its outcome (for the
    /// sub-steps of basemap_extract, which record several outcomes).
    private func step(_ body: () throws -> JSONValue) -> JSONValue {
        do {
            return try body()
        } catch {
            return outcome(of: error, id: "(basemap step)")
        }
    }

    private func store(_ args: JSONValue) throws -> OsmStore {
        let name = try args.required("area").requiredString()
        guard let store = stores[name] else { throw CorpusShapeError("area \(name) is not open") }
        return store
    }

    private func dispatch(op: String, args: JSONValue) throws -> JSONValue {
        switch op {
        case "import":
            let report = try OsmStore.importArea(
                input: path(args["input"]), destination: path(args["destination"]),
                options: importOptions(args["options"]))
            return .ok(report.canonical)

        case "open":
            let store = try OsmStore.open(path(args["path"]))
            let name = try args.required("area").requiredString()
            guard stores.updateValue(store, forKey: name) == nil else { throw CorpusShapeError("area \(name) opened twice") }
            return .ok(.null)

        case "close":
            let name = try args.required("area").requiredString()
            try store(args).close()
            stores[name] = nil
            return .ok(.null)

        case "get":
            return .okOrMissing(try store(args).get(osmId(kind: args.required("kind"), id: args.required("id"))))

        case "get_from_other_thread":
            // The synchronous store, deliberately used from a fresh thread
            // (README "WrongThread"). nonisolated(unsafe): smuggling the
            // non-Sendable store across is exactly what the core must refuse.
            nonisolated(unsafe) let store = try store(args)
            let id = try osmId(kind: args.required("kind"), id: args.required("id"))
            let result: Result<OsmObject?, any Error> = try onOtherThread { Result { try store.get(id) } }
            return .okOrMissing(try result.get())

        case "get_many":
            let ids: [OsmId]
            if let repeatArg = args["repeat"] {
                let one = try osmId(repeatArg.required("id"))
                ids = Array(repeating: one, count: Int(try repeatArg.required("count").requiredInt()))
            } else {
                ids = try args.required("request").requiredArray().map { try osmId($0) }
            }
            return .ok(try store(args).get(ids).canonical)

        case "query":
            return .ok(try store(args).query(query(args.required("request"))).canonical)

        case "way_coordinates":
            let coordinates = try store(args).wayCoordinates(args.required("id").requiredInt())
            return coordinates == nil ? .missing : .ok(canonicalWayCoordinates(coordinates))

        case "representative_point":
            return .okOrMissing(try store(args).representativePoint(osmId(kind: args.required("kind"), id: args.required("id"))))

        case "slice_job_request":
            let request = try SliceProtocol.jobRequest(
                base: args["base"]?.stringValue, bbox: bbox(args.required("bbox")),
                name: args.required("name").requiredString())
            return .ok(request.canonical)

        case "slice_job":
            return .ok(try SliceProtocol.job(base: args["base"]?.stringValue,
                                             response: args.required("response").requiredString()).canonical)

        case "slice_progress":
            return .ok(try SliceProtocol.progress(status: args.required("status").requiredString()).canonical)

        case "basemap_info":
            return .ok(try PmtilesInfo.read(path(args["path"])).canonical)

        case "basemap_plan_new":
            // The outcome is plan_new's; a created plan is freed right away.
            let plan = try newPlan(args)
            try plan.free()
            return .ok(.null)

        case "basemap_bad_feed":
            return try badFeed(args)

        case "basemap_extract":
            return try extract(args)

        default:
            throw CorpusShapeError("unknown op \(op)")
        }
    }

    // MARK: Basemap

    private func newPlan(_ args: JSONValue) throws -> BasemapPlan {
        let minZoom = Int32(try args.required("min_zoom").requiredInt())
        let maxZoom = Int32(try args.required("max_zoom").requiredInt())
        let overfetch = try args.required("overfetch").requiredDouble()
        let bboxArg = try args.required("bbox")
        if case .string(let raw) = bboxArg {
            return try BasemapPlan(bboxJSON: raw, minZoom: minZoom, maxZoom: maxZoom, overfetch: overfetch)
        }
        return try BasemapPlan(bbox: bbox(bboxArg), minZoom: minZoom, maxZoom: maxZoom, overfetch: overfetch)
    }

    /// What an HTTP server answers to a range request: the bytes that
    /// exist, so a range past the end comes back short.
    private func serve(_ source: [UInt8], offset: Int64, length: Int64) -> [UInt8] {
        let start = Int(min(offset, Int64(source.count)))
        let end = Int(min(offset + length, Int64(source.count)))
        return Array(source[start..<end])
    }

    private func source(_ args: JSONValue) throws -> [UInt8] {
        let path = try path(args["source"])
        guard let data = FileManager.default.contents(atPath: path) else {
            throw CorpusShapeError("cannot read source \(path)")
        }
        return [UInt8](data)
    }

    /// README `basemap_bad_feed`: plan_new, first_request, each listed feed,
    /// plan_free.
    private func badFeed(_ args: JSONValue) throws -> JSONValue {
        let source = try source(args)
        let plan = try newPlan(args) // must succeed; a failure fails the call
        let first = step { .ok(try plan.firstRequest().canonical) }
        let feeds = try args.required("feeds").requiredArray().map { feed -> JSONValue in
            let bytes = serve(source, offset: try feed.required("offset").requiredInt(),
                              length: try feed.required("length").requiredInt())
            let id = try feed.required("id").requiredInt()
            return step { .ok(try plan.feed(id: id, bytes: bytes).canonical) }
        }
        try plan.free()
        return .object(["first_request": first, "feeds": .array(feeds)])
    }

    /// README `basemap_extract`: the whole sans-IO extract against the local
    /// source file, recording every engine answer in the fixed order.
    private func extract(_ args: JSONValue) throws -> JSONValue {
        let source = try source(args)
        let plan = try newPlan(args) // must succeed
        let firstRange = try plan.firstRequest()
        let first = JSONValue.ok(firstRange.canonical)

        // 2. FIFO queue from the first request until tiles_ready or an error.
        var queue = [firstRange]
        var feeds: [JSONValue] = []
        var ready = false
        while !queue.isEmpty {
            let range = queue.removeFirst()
            let bytes = serve(source, offset: range.offset, length: range.length)
            let outcome: JSONValue
            var stop = false
            do {
                let next = try plan.feed(id: range.id, bytes: bytes)
                outcome = .ok(next.canonical)
                switch next {
                case .fetch(let more): queue.append(contentsOf: more)
                case .wait: break
                case .tilesReady:
                    ready = true
                    stop = true
                }
            } catch {
                outcome = self.outcome(of: error, id: "(basemap feed)")
                stop = true
            }
            feeds.append(.object(["id": .int(range.id), "step": outcome]))
            if stop { break }
        }

        // 3.
        let outstanding = step { .ok(try plan.outstanding().canonical) }

        // 4. The plan failed: free it; nothing to assemble.
        guard ready else {
            let free = step { try plan.free(); return .ok(.null) }
            return .object(["first_request": first, "feeds": .array(feeds), "outstanding": outstanding, "free": free])
        }

        // 5. Assembly. into_assembler consumes the plan.
        let staging = scratch.appendingPathComponent("basemap.part").path
        let output = scratch.appendingPathComponent("basemap.pmtiles").path
        let assembler = try plan.intoAssembler(staging: staging) // must succeed
        let into = JSONValue.ok(.null)
        let remainingRanges = try assembler.remaining()
        let remaining = JSONValue.ok(remainingRanges.canonical)
        let progressBefore = step { .ok(try assembler.progress().canonical) }
        let finishEarly = step { try assembler.finish(output: output); return .ok(.null) }
        let writes = remainingRanges.map { range in
            step {
                try assembler.writeRange(id: range.id, bytes: serve(source, offset: range.offset, length: range.length))
                return .ok(.null)
            }
        }
        let progressAfter = step { .ok(try assembler.progress().canonical) }
        let finish = step { try assembler.finish(output: output); return .ok(.null) }
        let free = step { try assembler.free(); return .ok(.null) }
        let info = step { .ok(try PmtilesInfo.read(output).canonical) }
        return .object([
            "first_request": first,
            "feeds": .array(feeds),
            "outstanding": outstanding,
            "into_assembler": into,
            "remaining": remaining,
            "progress_before": progressBefore,
            "finish_early": finishEarly,
            "writes": .array(writes),
            "progress_after": progressAfter,
            "finish": finish,
            "free": free,
            "info": info,
        ])
    }

    // MARK: Building Swift values from call arguments

    /// A typed argument the Swift API cannot express (should only occur in
    /// abi_only calls, which are skipped).
    private func osmId(kind: JSONValue, id: JSONValue) throws -> OsmId {
        let code = try kind.requiredInt()
        guard let kind = Int32(exactly: code).flatMap(OsmKind.init(rawValue:)) else {
            throw CorpusShapeError("kind code \(code)")
        }
        return OsmId(kind, try id.requiredInt())
    }

    /// `{"type":"node","id":1}`.
    private func osmId(_ value: JSONValue) throws -> OsmId {
        let type = try value.required("type").requiredString()
        guard let kind = OsmKind(wire: type) else { throw CorpusShapeError("object type \(type)") }
        return OsmId(kind, try value.required("id").requiredInt())
    }

    private func bbox(_ value: JSONValue) throws -> Bbox {
        guard case .object = value else { throw CorpusShapeError("bbox \(value.canonicalString)") }
        return Bbox(west: try value.required("west").requiredDouble(), south: try value.required("south").requiredDouble(),
                    east: try value.required("east").requiredDouble(), north: try value.required("north").requiredDouble())
    }

    /// A Query from the structured request; absent fields take Query's
    /// defaults (the core's defaults too).
    private func query(_ value: JSONValue) throws -> Query {
        guard case .object(let fields) = value else { throw CorpusShapeError("query \(value.canonicalString)") }
        let known: Set<String> = ["tags", "bbox", "after", "limit", "max_candidates"]
        if let unknown = fields.keys.first(where: { !known.contains($0) }) { throw CorpusShapeError("query field \(unknown)") }
        var query = Query()
        query.tags = try (value["tags"]?.requiredArray() ?? []).map(tagFilter)
        if let bbox = value["bbox"], bbox != .null { query.bbox = try self.bbox(bbox) }
        if let after = value["after"], after != .null { query.after = try osmId(after) }
        if let limit = value["limit"] { query.limit = Int(try limit.requiredInt()) }
        if let max = value["max_candidates"] { query.maxCandidates = Int(try max.requiredInt()) }
        return query
    }

    private func tagFilter(_ value: JSONValue) throws -> TagFilter {
        guard case .object(let fields) = value, fields.count == 1, case let (name, argument)? = fields.first.map({ ($0.key, $0.value) }) else {
            throw CorpusShapeError("tag filter \(value.canonicalString)")
        }
        switch name {
        case "Exists": return .exists(try argument.requiredString())
        case "NotExists": return .notExists(try argument.requiredString())
        case "Equals":
            let pair = try argument.requiredArray()
            guard pair.count == 2 else { throw CorpusShapeError("Equals needs [key, value]") }
            return .equals(try pair[0].requiredString(), try pair[1].requiredString())
        default: throw CorpusShapeError("tag filter \(name)")
        }
    }

    /// null / absent: the defaults (the core's defaults for a NULL options
    /// pointer are the same: no untagged metadata, 16 MiB cache).
    private func importOptions(_ value: JSONValue?) throws -> ImportOptions {
        guard let value, value != .null else { return ImportOptions() }
        guard case .object(let fields) = value else { throw CorpusShapeError("import options \(value.canonicalString)") }
        let known: Set<String> = ["preserve_untagged_metadata", "cache_mb"]
        if let unknown = fields.keys.first(where: { !known.contains($0) }) { throw CorpusShapeError("import option \(unknown)") }
        var options = ImportOptions()
        if let preserve = value["preserve_untagged_metadata"] {
            guard let flag = preserve.boolValue else { throw CorpusShapeError("preserve_untagged_metadata") }
            options.preserveUntaggedMetadata = flag
        }
        if let cache = value["cache_mb"] { options.cacheMiB = Int(try cache.requiredInt()) }
        return options
    }
}

/// A call whose arguments cannot be built into Swift types: a corpus or
/// runner problem, reported as a test issue, never as an outcome.
private struct CorpusShapeError: Error {
    let message: String
    init(_ message: String) { self.message = message }
}

// MARK: JSONValue access for call arguments

private extension JSONValue {
    subscript(key: String) -> JSONValue? {
        if case .object(let fields) = self { return fields[key] }
        return nil
    }

    func required(_ key: String) throws -> JSONValue {
        guard let value = self[key] else { throw CorpusShapeError("missing \(key)") }
        return value
    }

    var stringValue: String? {
        if case .string(let text) = self { return text }
        return nil
    }

    var boolValue: Bool? {
        if case .bool(let flag) = self { return flag }
        return nil
    }

    func requiredString() throws -> String {
        guard let text = stringValue else { throw CorpusShapeError("expected a string, got \(canonicalString)") }
        return text
    }

    func requiredInt() throws -> Int64 {
        switch self {
        case .int(let value): return value
        case .double(let value): if let exact = Int64(exactly: value) { return exact }
        default: break
        }
        throw CorpusShapeError("expected an integer, got \(canonicalString)")
    }

    /// JSONDecoder may hand an integral float ("-112.0") back as an integer.
    func requiredDouble() throws -> Double {
        switch self {
        case .double(let value): return value
        case .int(let value): return Double(value)
        default: throw CorpusShapeError("expected a number, got \(canonicalString)")
        }
    }

    func requiredArray() throws -> [JSONValue] {
        guard case .array(let items) = self else { throw CorpusShapeError("expected an array, got \(canonicalString)") }
        return items
    }
}

private extension Optional where Wrapped == JSONValue {
    func required(_ what: String) throws -> JSONValue {
        guard let value = self else { throw CorpusShapeError("missing \(what)") }
        return value
    }
}

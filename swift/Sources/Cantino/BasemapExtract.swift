// Drives one basemap extract: the Swift half of the sans-IO engine in
// src/basemap (see include/cantino.h), over ``BasemapPlan`` and
// ``BasemapAssembler``. The counterpart of the Kotlin `BasemapExtract`.
//
// Threads: the native plan and assembler are confined to the thread that
// created them, so every native call of one extract runs on a private
// ``OwnerThread``. HTTP runs in child tasks, up to
// ``DownloadTuning/basemapParallelism`` requests at a time; each response is
// then handed to the owner thread. Directory responses (small) go across as
// bytes; tile ranges are downloaded to a temporary file in `workDir` and the
// engine reads that file, so tile data never sits in memory.
//
// Failure: HTTP failures follow ``AreaHTTP``'s classes and are retried
// inline per range; a server ignoring Range is permanent. Engine errors are
// classified by the core (context "engine"). Cancellation aborts in-flight
// requests. On every exit the native handles are freed on their thread; an
// unfinished assembler deletes its staging file, so only a successful run
// leaves its output file behind.

import Foundation

/// What an extract did, for the sidecar and for measurements.
struct ExtractStats: Hashable, Sendable {
    let requests: Int64
    let transferredBytes: Int64
    let addressedTiles: Int64
    let archiveBytes: Int64
}

struct BasemapExtract: Sendable {
    let http: AreaHTTP
    /// Scratch directory for tile range files.
    let workDir: URL

    /// Extracts `bbox` from `url` into `output` (via `<output>.part` in the
    /// same directory, so the final rename is atomic). `onProgress` gets
    /// (phase, bytes, total) and may be called from several tasks.
    func run(url: String, bbox: Bbox, maxZoom: Int, overfetch: Double, output: URL,
             onProgress: @escaping @Sendable (BasemapPhase, Int64, Int64?) -> Void) async throws -> ExtractStats {
        let thread = OwnerThread(name: "cantino-basemap-native")
        let engine = Engine()
        let counters = Counters()
        defer { thread.shutdown() }
        do {
            let stats = try await extract(url: url, bbox: bbox, maxZoom: maxZoom, overfetch: overfetch, output: output,
                                          thread: thread, engine: engine, counters: counters, onProgress: onProgress)
            try await thread.run { engine.free() }
            return stats
        } catch {
            // Free on the owner thread, even when cancelled (OwnerThread work
            // is not cancellable). Freeing an unfinished assembler deletes
            // its staging file.
            _ = try? await thread.run { engine.free() }
            throw error
        }
    }

    private func extract(url: String, bbox: Bbox, maxZoom: Int, overfetch: Double, output: URL,
                         thread: OwnerThread, engine: Engine, counters: Counters,
                         onProgress: @escaping @Sendable (BasemapPhase, Int64, Int64?) -> Void) async throws -> ExtractStats {
        // --- Directory phase: header + root, then leaves (and metadata if
        // outside the first 16 KiB), one batch per round.
        onProgress(.directories, 0, nil)
        let first: ByteRange = try await native(thread) {
            engine.plan = try BasemapPlan(bbox: bbox, minZoom: -1, maxZoom: Int32(maxZoom), overfetch: overfetch)
            return try engine.plan!.firstRequest()
        }
        var batch = [first]
        var tiles: TilePlan?
        while tiles == nil {
            guard !batch.isEmpty else {
                throw DownloadFailure.permanent("extract plan stalled without outstanding requests", .unknown)
            }
            let steps = try await fetchAll(batch) { range in
                let data = try await retryingInline {
                    try await http.getRange(url, offset: range.offset, length: range.length, allowShort: range.id == 0)
                }
                let transferred = counters.add(requests: 1, bytes: Int64(data.count))
                onProgress(.directories, transferred, nil)
                return try await native(thread) { try engine.plan!.feed(id: range.id, bytes: [UInt8](data)) }
            }
            var next: [ByteRange] = []
            for step in steps {
                switch step {
                case .wait: break
                case .fetch(let ranges): next += ranges
                case .tilesReady(let plan): tiles = plan
                }
            }
            batch = next
        }
        let tilePlan = tiles!

        // --- Tile phase: the assembler owns `<output>.part` from here.
        let staging = output.deletingLastPathComponent().appendingPathComponent(output.lastPathComponent + ".part")
        let remaining = try await native(thread) {
            let plan = engine.plan!
            engine.plan = nil // into_assembler consumes the plan whatever the outcome
            engine.assembler = try plan.intoAssembler(staging: staging.path)
            return try engine.assembler!.remaining()
        }
        let total = tilePlan.transferBytes
        let tileBytes = Counters()
        onProgress(.tiles, 0, total)
        _ = try await fetchAll(remaining) { range in
            let part = workDir.appendingPathComponent("range-\(range.id).part")
            defer { try? FileManager.default.removeItem(at: part) }
            try await retryingInline {
                try await http.downloadRange(url, offset: range.offset, length: range.length, to: part)
            }
            try await native(thread) { try engine.assembler!.writeRangeFile(id: range.id, path: part.path) }
            _ = counters.add(requests: 1, bytes: range.length)
            onProgress(.tiles, tileBytes.add(requests: 0, bytes: range.length), total)
        }
        try await native(thread) { try engine.assembler!.finish(output: output.path) }
        let (requests, transferred) = counters.snapshot
        return ExtractStats(requests: requests, transferredBytes: transferred,
                            addressedTiles: tilePlan.addressedTiles, archiveBytes: tilePlan.archiveBytes)
    }

    /// Runs `body` for every range, ``DownloadTuning/basemapParallelism`` at
    /// a time, and returns the results in input order; the first failure
    /// cancels the rest.
    private func fetchAll<T: Sendable>(_ ranges: [ByteRange],
                                       _ body: @escaping @Sendable (ByteRange) async throws -> T) async throws -> [T] {
        let parallelism = max(1, DownloadTuning.current.basemapParallelism)
        return try await withThrowingTaskGroup(of: (Int, T).self) { group in
            var results = [T?](repeating: nil, count: ranges.count)
            var next = 0
            func add() {
                let index = next
                next += 1
                group.addTask { (index, try await body(ranges[index])) }
            }
            while next < min(parallelism, ranges.count) { add() }
            while let (index, result) = try await group.next() {
                results[index] = result
                if next < ranges.count { add() }
            }
            return results.map { $0! }
        }
    }

    /// An engine call on the owner thread; engine rejections are classified
    /// by the core (context "engine": a bad or unsupported archive is
    /// permanent INVALID_DATA, zooms the archive lacks INVALID_REQUEST, a
    /// staging write STORAGE).
    private func native<T: Sendable>(_ thread: OwnerThread, _ body: @escaping @Sendable () throws -> T) async throws -> T {
        do {
            return try await thread.run(body)
        } catch let error as DownloadFailure {
            throw error
        } catch {
            throw Failures.failure(error, context: .engine, "basemap extract")
        }
    }
}

/// The native handles of one extract. Touched only on its owner thread,
/// hence `@unchecked Sendable`.
private final class Engine: @unchecked Sendable {
    var plan: BasemapPlan?
    var assembler: BasemapAssembler?

    func free() {
        try? plan?.free()
        plan = nil
        try? assembler?.free()
        assembler = nil
    }
}

/// Request and byte counters shared by concurrent fetches.
private final class Counters: @unchecked Sendable {
    private let lock = NSLock()
    private var requests: Int64 = 0
    private var bytes: Int64 = 0

    /// Adds and returns the new byte total.
    func add(requests: Int64, bytes: Int64) -> Int64 {
        lock.withLock {
            self.requests += requests
            self.bytes += bytes
            return self.bytes
        }
    }

    var snapshot: (Int64, Int64) { lock.withLock { (requests, bytes) } }
}

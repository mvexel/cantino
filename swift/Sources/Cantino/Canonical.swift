// Canonical form: the entry point for the cross-adapter parity runner
// (tests/parity/calls.json + expected.json, built on another branch).
//
// The idea: every adapter (Python ctypes, Kotlin, Swift) replays the same
// calls against the same fixture area and renders each result into ONE
// canonical JSON shape, which is compared byte-for-byte with the corpus.
// The shape chosen here is the core's own wire format (src/model.rs serde
// layout, the JSON every `cantino_*` function returns), with object keys
// sorted (JSONValue.canonicalString). So a corpus generated straight from
// the C ABI is directly comparable, and a Swift-side difference means the
// adapter lost or altered information on the way into the Swift models.
//
// Rendering per result:
//   OsmObject           -> the core's object JSON
//   OsmObject? (nil)    -> null (get: not in area)
//   [OsmObject?]        -> array (get many, nulls kept)
//   Coordinate          -> {"lat_e7","lon_e7"}
//   [Coordinate?]       -> the flat array the core returns ([lat,lon,...],
//                          null,null for gaps), not one object per point
//   ImportReport        -> {"counts":{...},"database_bytes"}
//   CantinoError        -> {"error": "<category>"} (messages are not part of
//                          the contract: branch on the code, never the text)
//
// Internal on purpose: tests reach it with `@testable import Cantino`; it is
// not app API. If the corpus settles on a different canonical shape, change
// it here only; the runner itself just calls `canonical` on results.

/// Renders a result into the canonical parity JSON (see file comment).
protocol CanonicalJSON {
    var canonical: JSONValue { get }
}

extension Optional: CanonicalJSON where Wrapped: CanonicalJSON {
    var canonical: JSONValue { self?.canonical ?? .null }
}

extension Array: CanonicalJSON where Element: CanonicalJSON {
    var canonical: JSONValue { .array(map(\.canonical)) }
}

extension OsmId: CanonicalJSON {
    var canonical: JSONValue { json }
}

extension ObjectMetadata: CanonicalJSON {
    var canonical: JSONValue {
        .object([
            "version": .int(version), "timestamp": .int(timestampSeconds), "changeset": .int(changeset),
            "uid": .int(uid), "user": .string(user),
        ])
    }
}

extension Coordinate: CanonicalJSON {
    var canonical: JSONValue { .object(["lat_e7": .int(Int64(latE7)), "lon_e7": .int(Int64(lonE7))]) }
}

extension OsmObject: CanonicalJSON {
    var canonical: JSONValue {
        var fields: [String: JSONValue] = [
            "type": .string(id.kind.wire),
            "id": .int(id.id),
            "tags": .object(tags.mapValues { .string($0) }),
            "metadata": metadata.canonical,
        ]
        switch self {
        case .node(let n):
            fields["coordinate"] = Coordinate(latE7: n.latE7, lonE7: n.lonE7).canonical
            fields["location_version"] = .int(Int64(n.locationVersion))
        case .way(let w):
            fields["nodes"] = .array(w.nodeIds.map { .int($0) })
        case .relation(let r):
            fields["members"] = .array(r.members.map { .object(["id": $0.id.canonical, "role": .string($0.role)]) })
        }
        return .object(fields)
    }
}

extension ImportReport: CanonicalJSON {
    var canonical: JSONValue {
        .object([
            "counts": .object(["nodes": .int(counts.nodes), "ways": .int(counts.ways), "relations": .int(counts.relations)]),
            "database_bytes": .int(databaseBytes),
        ])
    }
}

extension CantinoError: CanonicalJSON {
    /// Category names follow the header's CANTINO_ERROR_* suffixes.
    var canonical: JSONValue {
        let category = switch self {
        case .invalidArgument: "INVALID_ARGUMENT"
        case .invalidFile: "INVALID_FILE"
        case .io: "IO"
        case .wrongThread: "WRONG_THREAD"
        }
        return .object(["error": .string(category)])
    }
}

/// Way coordinates in the core's flat shape (see file comment). A function,
/// not a conformance, because `[Coordinate?]` already renders as an array
/// of objects through the generic conformances above.
func canonicalWayCoordinates(_ coordinates: [Coordinate?]?) -> JSONValue {
    guard let coordinates else { return .null }
    return .array(coordinates.flatMap { point -> [JSONValue] in
        guard let point else { return [.null, .null] }
        return [.int(Int64(point.latE7)), .int(Int64(point.lonE7))]
    })
}

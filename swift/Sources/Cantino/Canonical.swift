// Canonical form of the cross-platform parity corpus (tests/parity). The
// corpus README (tests/parity/README.md, "Outcomes" and "Canonical form") is
// the contract; this file implements it for the Swift types, and
// JSONValue.canonicalString writes the result.
//
// Every platform runner replays tests/parity/calls.json through its public
// API, maps each result back into the C ABI's JSON shape, wraps it in an
// outcome envelope, and must reproduce expected.json (written by the Rust
// runner straight from the C ABI) byte for byte. A difference therefore
// means the Swift adapter lost or altered information on the way into its
// types (or behaves differently), never a formatting accident.
//
// Outcome envelope (README "Outcomes"):
//   success with a value              -> {"ok": <value>}
//   success without one (open, close,
//     plan_new, write_range, finish,
//     *_free)                          -> {"ok": null}
//   nil from get / wayCoordinates      -> {"missing": true}
//   CantinoError / internal error      -> {"error": {"code": N, "kind": "<name>"}}
//     (the CANTINO_ERROR_* code and its name; the message is not part of
//     the contract)
//
// Value shapes (the C ABI's JSON, src/model.rs serde layout):
//   OsmObject        {"type","id","coordinate","location_version","tags","metadata"} / "nodes" / "members"
//   Coordinate       {"lat_e7","lon_e7"}
//   [Coordinate?]    the flat [lat_e7, lon_e7, ...] array, null,null for a gap
//   ImportReport     {"counts":{...}} (database_bytes deliberately dropped:
//                    SQLite page allocation, not a behaviour contract)
//   PmtilesInfo      the full cantino_basemap_info object; bounds/center floats
//   slice / basemap  the C ABI objects; `fraction`, `bounds`, `center` floats
//
// Internal on purpose: tests reach it with `@testable import Cantino`; it is
// not app API.

import CCantino

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

// MARK: Outcome envelope

extension JSONValue {
    /// `{"ok": value}`; `.ok(.null)` for a call without a result.
    static func ok(_ value: JSONValue) -> JSONValue { .object(["ok": value]) }

    /// `{"missing": true}`: the object is not in the area.
    static let missing: JSONValue = .object(["missing": .bool(true)])

    /// `{"ok": value}` for a found value, `{"missing": true}` for nil.
    static func okOrMissing<T: CanonicalJSON>(_ value: T?) -> JSONValue {
        value.map { .ok($0.canonical) } ?? .missing
    }

    /// `{"error":{"code":N,"kind":"<name>"}}` for an error that carries a
    /// `CANTINO_ERROR_*` category, nil for any other error (including
    /// ``CantinoStateError/closed(_:)``, which is the adapter's own state
    /// check and never comes from the core).
    static func errorOutcome(_ error: any Error) -> JSONValue? {
        let code: Int32
        switch error {
        case let error as CantinoError: code = error.code
        // The adapter maps CANTINO_ERROR_INTERNAL (and undecodable core
        // output) to CantinoStateError.internalError; the corpus kind is
        // `internal` either way.
        case let error as CantinoStateError:
            guard case .internalError = error else { return nil }
            code = CANTINO_ERROR_INTERNAL
        default: return nil
        }
        return .object(["error": .object(["code": .int(Int64(code)), "kind": .string(errorKindName(code))])])
    }
}

extension CantinoError {
    /// The `CANTINO_ERROR_*` code of this category (the negated status of
    /// the failed call).
    var code: Int32 {
        switch self {
        case .invalidArgument: CANTINO_ERROR_INVALID_ARGUMENT
        case .invalidFile: CANTINO_ERROR_INVALID_FILE
        case .io: CANTINO_ERROR_IO
        case .wrongThread: CANTINO_ERROR_WRONG_THREAD
        }
    }
}

/// The corpus `kind` name of a `CANTINO_ERROR_*` code (README "Outcomes").
func errorKindName(_ code: Int32) -> String {
    switch code {
    case CANTINO_ERROR_INVALID_ARGUMENT: "invalid_argument"
    case CANTINO_ERROR_INVALID_FILE: "invalid_file"
    case CANTINO_ERROR_IO: "io"
    case CANTINO_ERROR_WRONG_THREAD: "wrong_thread"
    case CANTINO_ERROR_INTERNAL: "internal"
    default: "unknown_\(code)"
    }
}

// MARK: Store values

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

extension ObjectCounts: CanonicalJSON {
    var canonical: JSONValue {
        .object(["nodes": .int(nodes), "ways": .int(ways), "relations": .int(relations)])
    }
}

extension ImportReport: CanonicalJSON {
    /// Counts only: `database_bytes` is dropped by every runner (README).
    var canonical: JSONValue { .object(["counts": counts.canonical]) }
}

/// Way coordinates in the core's flat shape (see file comment). A function,
/// not a conformance, because `[Coordinate?]` already renders as an array
/// of objects through the generic conformances above. nil (way not in the
/// area) renders as null; the runner turns it into `{"missing":true}`.
func canonicalWayCoordinates(_ coordinates: [Coordinate?]?) -> JSONValue {
    guard let coordinates else { return .null }
    return .array(coordinates.flatMap { point -> [JSONValue] in
        guard let point else { return [.null, .null] }
        return [.int(Int64(point.latE7)), .int(Int64(point.lonE7))]
    })
}

// MARK: SliceOSM protocol

extension SliceProtocol.JobRequest: CanonicalJSON {
    /// `body` is a JSON document compared as a string, verbatim (README rule 7).
    var canonical: JSONValue { .object(["url": .string(url), "body": .string(body)]) }
}

extension SliceProtocol.Job: CanonicalJSON {
    var canonical: JSONValue {
        .object(["job_id": .string(id), "status_url": .string(statusUrl), "download_url": .string(downloadUrl)])
    }
}

extension SliceProtocol.Progress: CanonicalJSON {
    var canonical: JSONValue {
        .object([
            "complete": .bool(complete),
            "fraction": fraction.map { .double($0) } ?? .null,
            "size_bytes": sizeBytes.map { .int($0) } ?? .null,
            "timestamp": timestamp.map { .string($0) } ?? .null,
        ])
    }
}

// MARK: Basemap

extension PmtilesInfo: CanonicalJSON {
    var canonical: JSONValue {
        .object([
            "spec_version": .int(Int64(specVersion)),
            "bounds": .array([bounds.west, bounds.south, bounds.east, bounds.north].map { .double($0) }),
            // The center zoom is a float in the C ABI ([lon, lat, zoom] is
            // one f64 array): 12.0, never 12.
            "center": .array([.double(center.lon), .double(center.lat), .double(Double(center.zoom))]),
            "min_zoom": .int(Int64(minZoom)),
            "max_zoom": .int(Int64(maxZoom)),
            "addressed_tiles": .int(addressedTiles),
            "tile_entries": .int(tileEntries),
            "tile_contents": .int(tileContents),
            "tile_type": .int(Int64(tileType)),
            "tile_compression": .int(Int64(tileCompression)),
            "clustered": .bool(clustered),
            "file_bytes": .int(fileBytes),
        ])
    }
}

extension ByteRange: CanonicalJSON {
    var canonical: JSONValue { .object(["id": .int(id), "offset": .int(offset), "length": .int(length)]) }
}

extension TilePlan: CanonicalJSON {
    var canonical: JSONValue {
        .object([
            "requests": requests.canonical,
            "transfer_bytes": .int(transferBytes),
            "tile_data_bytes": .int(tileDataBytes),
            "archive_bytes": .int(archiveBytes),
            "tile_entries": .int(tileEntries),
            "addressed_tiles": .int(addressedTiles),
            "tile_contents": .int(tileContents),
            "cover_tiles": .int(coverTiles),
            "directory_requests": .int(directoryRequests),
            "directory_bytes": .int(directoryBytes),
            "min_zoom": .int(Int64(minZoom)),
            "max_zoom": .int(Int64(maxZoom)),
        ])
    }
}

extension BasemapStep: CanonicalJSON {
    var canonical: JSONValue {
        switch self {
        case .fetch(let ranges): .object(["fetch": ranges.canonical])
        case .wait: .string("wait")
        case .tilesReady(let plan): .object(["tiles_ready": plan.canonical])
        }
    }
}

extension BasemapProgress: CanonicalJSON {
    var canonical: JSONValue {
        .object([
            "ranges_done": .int(rangesDone), "ranges_total": .int(rangesTotal),
            "bytes_done": .int(bytesDone), "bytes_total": .int(bytesTotal),
        ])
    }
}

// MARK: Area store and failure classification

extension AreaLayout: CanonicalJSON {
    /// `cantino_area_layout`'s object: the staging fields are null without a work ID.
    var canonical: JSONValue {
        func text(_ value: String?) -> JSONValue { value.map(JSONValue.string) ?? .null }
        return .object([
            "root": .string(root), "data": .string(data), "basemap": .string(basemap),
            "sidecar": .string(sidecar), "journal": .string(journal), "lock": .string(lock),
            "staging_dir": text(stagingDir), "staged_data": text(stagedData),
            "staged_basemap": text(stagedBasemap), "staged_metadata": text(stagedMetadata),
        ])
    }
}

extension AreaInfo: CanonicalJSON {
    /// `cantino_area_published`'s object. The metadata is the sidecar as the
    /// adapter would write it (`database_bytes` included: unlike the import
    /// report, the sidecar is a stored format, and the published check
    /// compares it with the file).
    var canonical: JSONValue {
        .object([
            "data": .string(dataURL.path),
            "basemap": basemapURL.map { .string($0.path) } ?? .null,
            "metadata": metadata?.json ?? .null,
        ])
    }
}

extension Failures.Classified: CanonicalJSON {
    var canonical: JSONValue {
        .object([
            "class": .string(failureClass.rawValue), "reason": .string(reason.rawValue),
            "inline_retry": .bool(inlineRetry), "scheduler_retry": .bool(schedulerRetry),
        ])
    }
}

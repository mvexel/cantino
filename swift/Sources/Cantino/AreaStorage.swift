// The Swift counterpart of the Kotlin adapter's internal `AreaStorage`: the
// on-disk layout of a downloaded area and the commit protocol that publishes
// it. A thin wrapper, as in Kotlin: the layout, staging, the roll-forward
// commit journal, recovery after a kill, the per-area lock and the reading
// of the published area live in the Rust core (src/area_storage.rs, through
// `cantino_area_*`), so Android and iOS behave identically by construction.
// The caller chooses the root directory (Kotlin: `filesDir/cantino-areas`;
// iOS: a directory under Application Support, excluded from backup as the
// app sees fit); this type adds no policy of its own.
//
// ```
// <root>/<areaId>.sqlite, .pmtiles, .json   published data, basemap, sidecar
// <root>/<areaId>.commit                    commit journal, only while publishing
// <root>/<areaId>.lock                      area lock file (empty)
// <root>/.staging/<areaId>/<workId>/        the next version, built by run <workId>
// ```
//
// Not here (Kotlin keeps them in `AreaStorage` too, but they are
// Android-specific in how they are used): the download scratch directory and
// the WorkManager job checkpoint. The iOS area manager will own its own.
//
// Internal, like Kotlin's. Platform-neutral: no Apple-only API. Every call
// is safe from any thread (the core serialises on the area lock); there are
// no handles.

import CCantino
import Foundation

/// The areas root of an app and the operations on it. A value: it only
/// remembers the root path.
struct AreaStorage: Hashable, Sendable {
    /// The core's root directory for this app's areas (created by the core
    /// when it first needs it; its parent must exist).
    let root: String

    init(root: String) {
        self.root = root
    }

    init(root: URL) throws {
        guard root.isFileURL else {
            throw CantinoError.invalidArgument("not a file URL: \(root)")
        }
        self.root = root.path
    }

    // MARK: Area IDs

    /// Area IDs become file names, so they are restricted to a safe
    /// alphabet: 1 to 64 characters from `[A-Za-z0-9_-]`. The rule lives in
    /// the core (`cantino_area_validate_id`). Throws
    /// ``CantinoError/invalidArgument(_:)`` (Kotlin turns the same rejection
    /// into `IllegalArgumentException`, Swift keeps the typed domain error).
    static func validate(areaId: String) throws {
        var call = NativeCall()
        _ = try call.check(cantino_area_validate_id(areaId, &call.error))
    }

    // MARK: Layout

    /// Paths of `areaId` as the core lays them out, and of run `workId`'s
    /// staging when given. Touches no file. `workId` is raw text so that the
    /// core's rejection of a malformed one can be exercised (parity corpus);
    /// the typed overload is what the area manager uses.
    func layout(areaId: String, workId: String?) throws -> AreaLayout {
        var call = NativeCall()
        _ = try call.check(cantino_area_layout(root, areaId, workId, &call.result, &call.error))
        return try Wire.decode(WireAreaLayout.self, call.resultString()).model
    }

    func layout(areaId: String, workId: UUID) throws -> AreaLayout {
        try layout(areaId: areaId, workId: workId.uuidString)
    }

    /// The published data file of `areaId` (it may not exist).
    func dataFile(areaId: String) throws -> String {
        try layout(areaId: areaId, workId: nil as String?).data
    }

    /// The published basemap of `areaId` (it may not exist).
    func basemapFile(areaId: String) throws -> String {
        try layout(areaId: areaId, workId: nil as String?).basemap
    }

    // MARK: Staged next version

    /// Creates an empty staging directory for run `workId` and deletes those
    /// of other runs (left by killed processes or cancelled runs), after
    /// rolling a pending commit forward (so a committed version is never
    /// deleted). Returns the staging directory. Throws
    /// ``CantinoError/io(_:)`` if that roll-forward fails.
    func prepareStaging(areaId: String, workId: UUID) throws -> String {
        var call = NativeCall()
        _ = try call.check(cantino_area_prepare_staging(root, areaId, workId.uuidString, &call.result, &call.error))
        let layout = try Wire.decode(WireAreaLayout.self, call.resultString()).model
        guard let staging = layout.stagingDir else {
            throw Wire.malformed("prepare_staging without staging_dir")
        }
        return staging
    }

    /// Discards run `workId`'s staging directory unless its commit is
    /// pending (the journal owns it then). Best effort in the core.
    func discardStaging(areaId: String, workId: UUID) throws {
        var call = NativeCall()
        _ = try call.check(cantino_area_discard_staging(root, areaId, workId.uuidString, &call.error))
    }

    /// Writes the sidecar of the staged version; it is published with it.
    /// The core checks it is a valid sidecar and refuses an invalid one
    /// (``CantinoError/invalidArgument(_:)``). The text is written verbatim.
    ///
    /// Kotlin writes `JSONObject.toString()` (org.json key order); Swift
    /// writes ``JSONValue/canonicalString`` (keys sorted). Both are the same
    /// document to every reader of the sidecar, which parses it.
    func writeStagedMetadata(areaId: String, workId: UUID, metadata: AreaMetadata) throws {
        try writeStagedMetadata(areaId: areaId, workId: workId, json: metadata.json.canonicalString)
    }

    /// As above for sidecar text the caller already has.
    func writeStagedMetadata(areaId: String, workId: UUID, json: String) throws {
        var call = NativeCall()
        _ = try call.check(cantino_area_write_staged_metadata(root, areaId, workId.uuidString, json, &call.error))
    }

    // MARK: Commit

    /// Publishes run `workId`'s staged version: data, sidecar and, when
    /// `hasBasemap`, basemap together, through the roll-forward journal whose
    /// atomic write is the commit point. Before it nothing has changed (a
    /// failure, a throw from the hook or a kill leaves the old area); after
    /// it the new version is published whatever happens to the process (the
    /// next ``recover(areaId:)``, which every reader and every new run
    /// performs, finishes it). Without a basemap in the new version, a
    /// previously published basemap is removed.
    ///
    /// `beforeCommit` runs on the calling thread under the area lock
    /// immediately before the commit point; throwing from it aborts with
    /// nothing changed, and that error propagates. `afterCommitPoint` runs
    /// right after the commit point (Kotlin: `AreaTestHooks.afterCommitPoint`,
    /// for tests that stand in for a kill). Neither may call back into
    /// `AreaStorage` for the same area (the lock is not reentrant).
    ///
    /// Throws ``CantinoError/io(_:)`` for a file system failure (before the
    /// commit point nothing changed; after it, a failed rename, the version
    /// is committed and the next recover finishes it);
    /// ``CantinoError/invalidArgument(_:)`` for invalid IDs or an incomplete
    /// staged version.
    func commit(areaId: String, workId: UUID, hasBasemap: Bool,
                beforeCommit: () throws -> Void = {},
                afterCommitPoint: (() -> Void)? = nil) throws {
        try withoutActuallyEscaping(beforeCommit) { before in
            let hook = CommitHook(before: before, after: afterCommitPoint ?? {})
            var call = NativeCall()
            let context = Unmanaged.passUnretained(hook).toOpaque()
            let status = cantino_area_commit(
                root, areaId, workId.uuidString, hasBasemap ? 1 : 0, commitHookTrampoline, context, &call.error)
            // The core only calls the hook during the call above; keep the
            // box alive until here.
            withExtendedLifetime(hook) {}
            if status == 1 {
                // Aborted by the hook: it aborts only by throwing, and that
                // error is the call's outcome.
                if let error = hook.error { throw error }
                throw CantinoStateError.internalError(
                    "Cantino internal error: commit of \(areaId) aborted without an error")
            }
            _ = try call.check(status)
        }
    }

    // MARK: Recovery and reading

    /// Finishes a commit left half-done by a dead process (or a failed
    /// rename). Cheap when there is nothing to do; blocks while a commit of
    /// this area is running. Unlike Kotlin's `recover` this throws the I/O
    /// failure: ``published(areaId:)`` is the one that carries on regardless.
    func recover(areaId: String) throws {
        var call = NativeCall()
        _ = try call.check(cantino_area_recover(root, areaId, &call.error))
    }

    /// The published area, or nil when none exists. Completes a pending
    /// commit first (an I/O failure to do so leaves the journal for the next
    /// recover, and the core reads anyway, as Kotlin's `published` does). The
    /// core trusts the metadata only if it describes the files present
    /// (recorded database size = data file size; recorded basemap = basemap
    /// file); anything else reads as "metadata unknown" (nil) rather than as
    /// wrong metadata.
    func published(areaId: String) throws -> AreaInfo? {
        do {
            try recover(areaId: areaId)
        } catch CantinoError.io {
            // Leave the journal; the next recover retries. Readers meanwhile
            // see a mix of old and new parts, flagged by the sidecar checks.
        }
        var call = NativeCall()
        let status = try call.check(cantino_area_published(root, areaId, &call.result, &call.error))
        if status == 1 { return nil }
        let wire = try Wire.decode(WirePublishedArea.self, call.resultString())
        return AreaInfo(areaId: areaId, dataPath: wire.data, basemapPath: wire.basemap, metadata: wire.metadata)
    }
}

// MARK: - Commit hook

/// What the C hook gets as its context: the closures and the error the
/// before-commit closure threw (Swift errors cannot cross the C frame).
private final class CommitHook {
    let before: () throws -> Void
    let after: () -> Void
    var error: (any Error)?

    init(before: @escaping () throws -> Void, after: @escaping () -> Void) {
        self.before = before
        self.after = after
    }
}

/// `cantino_area_commit_hook`: no captures (a C function pointer), the
/// closures travel in `context`.
private let commitHookTrampoline: cantino_area_commit_hook = { context, stage in
    let hook = Unmanaged<CommitHook>.fromOpaque(context!).takeUnretainedValue()
    switch stage {
    case CANTINO_AREA_STAGE_BEFORE_COMMIT:
        do {
            try hook.before()
            return 0
        } catch {
            hook.error = error
            return 1 // non-zero aborts; nothing changed
        }
    case CANTINO_AREA_STAGE_AFTER_COMMIT_POINT:
        hook.after()
        return 0 // ignored by the core
    default:
        return 0
    }
}

// MARK: - Models

/// Paths of an area, as the core lays them out
/// (`cantino_area_layout`). The staging paths are nil without a work ID.
struct AreaLayout: Hashable, Sendable {
    let root: String
    let data: String
    let basemap: String
    let sidecar: String
    let journal: String
    let lock: String
    let stagingDir: String?
    let stagedData: String?
    let stagedBasemap: String?
    let stagedMetadata: String?
}

/// A published area (Kotlin `AreaInfo`, with paths instead of `File`s).
struct AreaInfo: Hashable, Sendable {
    let areaId: String
    let dataPath: String
    /// The basemap, or nil when the area has none.
    let basemapPath: String?
    /// The sidecar, or nil when unknown (area published by hand, or the
    /// sidecar does not describe the files present).
    let metadata: AreaMetadata?
}

/// How a published basemap was obtained.
enum BasemapKind: String, Hashable, Sendable {
    /// Downloaded as a ready-made file.
    case url
    /// Cut on the device from a remote archive.
    case extract
}

/// The published basemap of an area, as recorded when it was downloaded.
struct BasemapMetadata: Hashable, Sendable {
    let kind: BasemapKind
    let sourceUrl: String
    let fileBytes: Int64
    let addressedTiles: Int64
    let minZoom: Int
    let maxZoom: Int
    let requests: Int64
    let transferredBytes: Int64
}

/// What the sidecar records about an area (Kotlin `AreaMetadata`).
struct AreaMetadata: Hashable, Sendable {
    let bbox: Bbox
    let name: String
    /// The server's timestamp string as received, stored unchanged.
    let snapshotTimestamp: String?
    let importedAtMillis: Int64
    let report: ImportReport
    let basemap: BasemapMetadata?
    let workId: UUID?

    /// The sidecar JSON (keys are a stored format, shared with Kotlin and the
    /// core: renaming a Swift property must not change them).
    var json: JSONValue {
        var basemapJSON = JSONValue.null
        if let basemap {
            basemapJSON = .object([
                "kind": .string(basemap.kind.rawValue),
                "source_url": .string(basemap.sourceUrl),
                "bytes": .int(basemap.fileBytes),
                "addressed_tiles": .int(basemap.addressedTiles),
                "min_zoom": .int(Int64(basemap.minZoom)),
                "max_zoom": .int(Int64(basemap.maxZoom)),
                "requests": .int(basemap.requests),
                "transferred_bytes": .int(basemap.transferredBytes),
            ])
        }
        return .object([
            "bbox": .object([
                "west": .double(bbox.west), "south": .double(bbox.south),
                "east": .double(bbox.east), "north": .double(bbox.north),
            ]),
            "name": .string(name),
            "snapshot_timestamp": snapshotTimestamp.map(JSONValue.string) ?? .null,
            "imported_at_millis": .int(importedAtMillis),
            "report": .object([
                "counts": .object([
                    "nodes": .int(report.counts.nodes), "ways": .int(report.counts.ways),
                    "relations": .int(report.counts.relations),
                ]),
                "database_bytes": .int(report.databaseBytes),
            ]),
            "basemap": basemapJSON,
            "work_id": workId.map { .string($0.uuidString.lowercased()) } ?? .null,
        ])
    }
}

// MARK: - Wire shapes (include/cantino.h, "Area store")

private struct WireAreaLayout: Decodable {
    let root: String
    let data: String
    let basemap: String
    let sidecar: String
    let journal: String
    let lock: String
    let stagingDir: String?
    let stagedData: String?
    let stagedBasemap: String?
    let stagedMetadata: String?

    enum CodingKeys: String, CodingKey {
        case root, data, basemap, sidecar, journal, lock
        case stagingDir = "staging_dir"
        case stagedData = "staged_data"
        case stagedBasemap = "staged_basemap"
        case stagedMetadata = "staged_metadata"
    }

    var model: AreaLayout {
        AreaLayout(root: root, data: data, basemap: basemap, sidecar: sidecar, journal: journal, lock: lock,
                   stagingDir: stagingDir, stagedData: stagedData, stagedBasemap: stagedBasemap,
                   stagedMetadata: stagedMetadata)
    }
}

private struct WirePublishedArea: Decodable {
    let data: String
    let basemap: String?
    /// nil for `null`, and for a sidecar this adapter cannot read: the core
    /// validated it already, so that only guards against a decoder stricter
    /// than the core's (Kotlin does the same).
    let metadata: AreaMetadata?

    enum CodingKeys: String, CodingKey { case data, basemap, metadata }

    init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        data = try container.decode(String.self, forKey: .data)
        basemap = try container.decodeIfPresent(String.self, forKey: .basemap)
        metadata = (try? container.decodeIfPresent(WireAreaMetadata.self, forKey: .metadata))?.model
    }
}

private struct WireBbox: Decodable {
    let west: Double
    let south: Double
    let east: Double
    let north: Double
}

private struct WireBasemapMetadata: Decodable {
    let kind: String
    let sourceUrl: String
    let bytes: Int64
    let addressedTiles: Int64
    let minZoom: Int
    let maxZoom: Int
    let requests: Int64
    let transferredBytes: Int64

    enum CodingKeys: String, CodingKey {
        case kind, bytes, requests
        case sourceUrl = "source_url"
        case addressedTiles = "addressed_tiles"
        case minZoom = "min_zoom"
        case maxZoom = "max_zoom"
        case transferredBytes = "transferred_bytes"
    }

    var model: BasemapMetadata? {
        guard let kind = BasemapKind(rawValue: kind) else { return nil }
        return BasemapMetadata(kind: kind, sourceUrl: sourceUrl, fileBytes: bytes, addressedTiles: addressedTiles,
                               minZoom: minZoom, maxZoom: maxZoom, requests: requests,
                               transferredBytes: transferredBytes)
    }
}

private struct WireAreaMetadata: Decodable {
    let bbox: WireBbox
    let name: String
    let snapshotTimestamp: String?
    let importedAtMillis: Int64
    let report: WireReport
    let basemap: WireBasemapMetadata?
    let workId: String?

    enum CodingKeys: String, CodingKey {
        case bbox, name, report, basemap
        case snapshotTimestamp = "snapshot_timestamp"
        case importedAtMillis = "imported_at_millis"
        case workId = "work_id"
    }

    var model: AreaMetadata? {
        var basemapModel: BasemapMetadata?
        if let basemap {
            guard let converted = basemap.model else { return nil }
            basemapModel = converted
        }
        var work: UUID?
        if let workId {
            guard let parsed = UUID(uuidString: workId) else { return nil }
            work = parsed
        }
        return AreaMetadata(
            bbox: Bbox(west: bbox.west, south: bbox.south, east: bbox.east, north: bbox.north), name: name,
            snapshotTimestamp: snapshotTimestamp, importedAtMillis: importedAtMillis, report: report.model,
            basemap: basemapModel, workId: work)
    }
}

// Offline basemap support over the core's PMTiles engine
// (src/mobile_basemap.rs, `cantino_basemap_*` in include/cantino.h).
//
// Public: `PmtilesInfo.read`, validating a local PMTiles v3 file and
// reading its header (Kotlin `PmtilesInfo.read`; Swift exposes every field
// the C ABI reports, see swift/README.md).
//
// Internal: `BasemapPlan` / `BasemapAssembler`, a typed wrapper over the
// sans-IO extract engine, mirroring how the Kotlin `BasemapExtract` drives
// `NativeBridge.basemapPlan*` / `basemapAsm*`, but without any networking.
// The caller performs every range request and feeds the bytes back. The
// public Swift `BasemapExtract` (HTTP range requests, background transfer on
// iOS) belongs to the area download lifecycle of a later slice and will sit
// on top of these two types; the parity runner drives them directly with
// bytes sliced from a local file.

import CCantino
import Foundation

/// Header facts of a local PMTiles v3 file, from the core's validator
/// (`cantino_basemap_info`). Useful to check a basemap the app ships or
/// downloads itself, or to fit the map camera to ``bounds``.
///
/// Kotlin's `PmtilesInfo` exposes a subset (zooms, bounds, counts, file
/// size); Swift has every field the core reports.
public struct PmtilesInfo: Hashable, Sendable {
    /// The header's center: where and at which zoom a viewer should start.
    public struct Center: Hashable, Sendable {
        /// Longitude in degrees.
        public let lon: Double
        /// Latitude in degrees.
        public let lat: Double
        /// Zoom level.
        public let zoom: Int

        init(lon: Double, lat: Double, zoom: Int) {
            self.lon = lon
            self.lat = lat
            self.zoom = zoom
        }
    }

    /// PMTiles spec version (always 3; anything else is rejected).
    public let specVersion: Int
    /// Lowest zoom level.
    public let minZoom: Int
    /// Highest zoom level.
    public let maxZoom: Int
    /// Area covered according to the header, in degrees (for a world
    /// archive west > east is possible near the antimeridian).
    public let bounds: Bbox
    /// The header's center point and zoom.
    public let center: Center
    /// Tiles addressable (run lengths expanded).
    public let addressedTiles: Int64
    /// Directory entries.
    public let tileEntries: Int64
    /// Distinct tile blobs (deduplicated).
    public let tileContents: Int64
    /// PMTiles tile type code: 1 MVT, 2 PNG, 3 JPEG, 4 WebP, 5 AVIF, 0 unknown.
    public let tileType: Int
    /// PMTiles tile compression code: 1 none, 2 gzip, 3 brotli, 4 zstd,
    /// 0 unknown.
    public let tileCompression: Int
    /// Whether tile data is laid out in tile-ID order (required for
    /// extracts; every extract output is clustered).
    public let clustered: Bool
    /// Size of the file in bytes.
    public let fileBytes: Int64

    /// Validates the file at `path` as PMTiles v3 (magic, version, every
    /// section inside the file, so a truncated download is rejected) and
    /// reads its header. Directories are not walked. Synchronous file I/O
    /// (small reads); any thread.
    ///
    /// Throws ``CantinoError/io(_:)`` if the file is missing or unreadable,
    /// ``CantinoError/invalidFile(_:)`` if it is not a valid PMTiles v3
    /// archive.
    public static func read(_ path: String) throws -> PmtilesInfo {
        var call = NativeCall()
        _ = try call.check(cantino_basemap_info(path, &call.result, &call.error))
        return try Wire.decode(WirePmtilesInfo.self, call.resultString()).model()
    }

    /// ``read(_:)-(String)`` for a file URL. Throws
    /// ``CantinoError/invalidArgument(_:)`` for a non-file URL.
    public static func read(_ url: URL) throws -> PmtilesInfo {
        guard url.isFileURL else { throw CantinoError.invalidArgument("not a file URL: \(url)") }
        return try read(url.path)
    }
}

private struct WirePmtilesInfo: Decodable {
    let specVersion: Int
    let bounds: [Double]
    let center: [Double]
    let minZoom: Int
    let maxZoom: Int
    let addressedTiles: Int64
    let tileEntries: Int64
    let tileContents: Int64
    let tileType: Int
    let tileCompression: Int
    let clustered: Bool
    let fileBytes: Int64

    enum CodingKeys: String, CodingKey {
        case bounds, center, clustered
        case specVersion = "spec_version"
        case minZoom = "min_zoom"
        case maxZoom = "max_zoom"
        case addressedTiles = "addressed_tiles"
        case tileEntries = "tile_entries"
        case tileContents = "tile_contents"
        case tileType = "tile_type"
        case tileCompression = "tile_compression"
        case fileBytes = "file_bytes"
    }

    func model() throws -> PmtilesInfo {
        guard bounds.count == 4 else { throw Wire.malformed("PMTiles bounds need 4 numbers") }
        // The center zoom is a u8 in the header, written as a float by the
        // core (`[lon, lat, zoom]` is one f64 array).
        guard center.count == 3, let zoom = Int(exactly: center[2]) else {
            throw Wire.malformed("PMTiles center needs [lon, lat, integral zoom]")
        }
        return PmtilesInfo(
            specVersion: specVersion, minZoom: minZoom, maxZoom: maxZoom,
            bounds: Bbox(west: bounds[0], south: bounds[1], east: bounds[2], north: bounds[3]),
            center: .init(lon: center[0], lat: center[1], zoom: zoom),
            addressedTiles: addressedTiles, tileEntries: tileEntries, tileContents: tileContents,
            tileType: tileType, tileCompression: tileCompression, clustered: clustered, fileBytes: fileBytes)
    }
}

// MARK: - Extract engine (internal)

/// One HTTP range request of the extract engine: bytes
/// `offset ..< offset + length` of the source archive, answered under `id`
/// (unique within one plan). Kotlin: internal `ByteRange`.
struct ByteRange: Hashable, Sendable, Decodable {
    let id: Int64
    let offset: Int64
    let length: Int64
}

/// What the extract will transfer and write, once every directory is read
/// (the `tiles_ready` step). `requests` are the tile-data ranges to fetch.
struct TilePlan: Hashable, Sendable, Decodable {
    let requests: [ByteRange]
    let transferBytes: Int64
    let tileDataBytes: Int64
    let archiveBytes: Int64
    let tileEntries: Int64
    let addressedTiles: Int64
    let tileContents: Int64
    let coverTiles: Int64
    let directoryRequests: Int64
    let directoryBytes: Int64
    let minZoom: Int
    let maxZoom: Int

    enum CodingKeys: String, CodingKey {
        case requests
        case transferBytes = "transfer_bytes"
        case tileDataBytes = "tile_data_bytes"
        case archiveBytes = "archive_bytes"
        case tileEntries = "tile_entries"
        case addressedTiles = "addressed_tiles"
        case tileContents = "tile_contents"
        case coverTiles = "cover_tiles"
        case directoryRequests = "directory_requests"
        case directoryBytes = "directory_bytes"
        case minZoom = "min_zoom"
        case maxZoom = "max_zoom"
    }
}

/// The engine's answer to a fed response: fetch more ranges, wait for
/// responses still outstanding, or the tile plan once every directory is in.
/// Wire: `{"fetch":[ByteRange...]}` | `"wait"` | `{"tiles_ready":TilePlan}`.
enum BasemapStep: Hashable, Sendable, Decodable {
    case fetch([ByteRange])
    case wait
    case tilesReady(TilePlan)

    private enum CodingKeys: String, CodingKey {
        case fetch
        case tilesReady = "tiles_ready"
    }

    init(from decoder: any Decoder) throws {
        if let text = try? decoder.singleValueContainer().decode(String.self) {
            guard text == "wait" else {
                throw DecodingError.dataCorrupted(.init(codingPath: [], debugDescription: "unknown step \(text)"))
            }
            self = .wait
            return
        }
        let container = try decoder.container(keyedBy: CodingKeys.self)
        if let ranges = try container.decodeIfPresent([ByteRange].self, forKey: .fetch) {
            self = .fetch(ranges)
        } else {
            self = .tilesReady(try container.decode(TilePlan.self, forKey: .tilesReady))
        }
    }
}

/// Tile ranges written so far, of all the plan's tile ranges.
struct BasemapProgress: Hashable, Sendable, Decodable {
    let rangesDone: Int64
    let rangesTotal: Int64
    let bytesDone: Int64
    let bytesTotal: Int64

    enum CodingKeys: String, CodingKey {
        case rangesDone = "ranges_done"
        case rangesTotal = "ranges_total"
        case bytesDone = "bytes_done"
        case bytesTotal = "bytes_total"
    }
}

/// An extract plan (`CantinoBasemapPlan`): reads the source archive's header
/// and directories from the responses fed to it, then becomes a
/// ``BasemapAssembler``.
///
/// Flow (include/cantino.h): ``firstRequest()``, fetch it, ``feed(id:bytes:)``
/// each response (fetching the ranges of every ``BasemapStep/fetch(_:)``)
/// until ``BasemapStep/tilesReady(_:)``, then ``intoAssembler(staging:)``.
///
/// **Threading: confined to the creating thread**, like ``OsmStore``: every
/// call including ``free()`` must come from it (others throw
/// ``CantinoError/wrongThread(_:)``, handle untouched). Fetch on any thread,
/// hand the bytes to the owner thread. Not `Sendable` for the same reason.
///
/// A wrong `id` or length is rejected without changing state (re-fetch and
/// feed again); other feed errors are fatal for the plan.
final class BasemapPlan {
    /// The live handle, or nil once freed or consumed. Owner thread only.
    private var handle: OpaquePointer?

    /// Creates a plan for `bbox` (degrees; west > east crosses the
    /// antimeridian). Zooms -1 mean the source archive's own, others are
    /// clamped to it; `overfetch` is the extra bytes allowed per wanted
    /// byte to save requests (go-pmtiles' 0.05). The calling thread owns
    /// the plan. Throws ``CantinoError/invalidArgument(_:)`` for an invalid
    /// bbox, zooms outside -1...31 or min > max, a negative or non-finite
    /// overfetch.
    ///
    /// `bbox` is not validated by ``Bbox``'s order rules here: west > east
    /// is meaningful for the engine.
    convenience init(bbox: Bbox, minZoom: Int32 = -1, maxZoom: Int32 = -1, overfetch: Double = 0.05) throws {
        try self.init(bboxJSON: bbox.json().canonicalString, minZoom: minZoom, maxZoom: maxZoom, overfetch: overfetch)
    }

    /// As ``init(bbox:minZoom:maxZoom:overfetch:)`` with the bbox as raw JSON
    /// text, handed to the core verbatim (Kotlin's
    /// `NativeBridge.basemapPlanNew` takes the same string). The parity
    /// corpus feeds malformed text through it.
    init(bboxJSON: String, minZoom: Int32, maxZoom: Int32, overfetch: Double) throws {
        var call = NativeCall()
        var plan: OpaquePointer?
        _ = try call.check(cantino_basemap_plan_new(bboxJSON, minZoom, maxZoom, overfetch, &plan, &call.error))
        guard let plan else {
            throw CantinoStateError.internalError("Cantino internal error: plan_new returned no handle")
        }
        handle = plan
    }

    /// Best-effort free of a plan that was neither freed nor consumed (only
    /// effective on the owner thread; elsewhere the core refuses and the
    /// plan leaks, as for ``OsmStore``).
    deinit {
        if let handle {
            var call = NativeCall()
            _ = cantino_basemap_plan_free(handle, &call.error)
        }
    }

    /// The first range to fetch: the header and root directory
    /// (`{"id":0,"offset":0,"length":16384}`; a tiny archive may answer
    /// shorter).
    func firstRequest() throws -> ByteRange {
        let handle = try live()
        var call = NativeCall()
        _ = try call.check(cantino_basemap_plan_first_request(handle, &call.result, &call.error))
        return try Wire.decode(ByteRange.self, call.resultString())
    }

    /// Feeds the response to request `id` and returns the next step.
    func feed(id: Int64, bytes: [UInt8]) throws -> BasemapStep {
        let handle = try live()
        var call = NativeCall()
        let status = bytes.withUnsafeBufferPointer { buffer in
            // baseAddress is nil for an empty array: the header allows NULL
            // with len 0.
            cantino_basemap_plan_feed(handle, UInt64(bitPattern: id), buffer.baseAddress, buffer.count,
                                      &call.result, &call.error)
        }
        _ = try call.check(status)
        return try Wire.decode(BasemapStep.self, call.resultString())
    }

    /// The requests issued and not yet fed.
    func outstanding() throws -> [ByteRange] {
        let handle = try live()
        var call = NativeCall()
        _ = try call.check(cantino_basemap_plan_outstanding(handle, &call.result, &call.error))
        return try Wire.decode([ByteRange].self, call.resultString())
    }

    /// Turns the finished plan into an assembler writing to `staging`
    /// (created or truncated; must be on the output's file system so the
    /// final rename is atomic). The calling thread owns the assembler.
    ///
    /// **Consumes the plan** on success and on every failure except
    /// ``CantinoError/wrongThread(_:)`` (the core's rule): afterwards this
    /// plan is freed and further calls throw ``CantinoStateError/closed(_:)``.
    func intoAssembler(staging: String) throws -> BasemapAssembler {
        let handle = try live()
        var call = NativeCall()
        var assembler: OpaquePointer?
        let status = cantino_basemap_plan_into_assembler(handle, staging, &assembler, &call.error)
        do {
            _ = try call.check(status)
        } catch CantinoError.wrongThread(let message) {
            throw CantinoError.wrongThread(message) // the plan is untouched and still ours
        } catch {
            self.handle = nil
            throw error
        }
        self.handle = nil
        guard let assembler else {
            throw CantinoStateError.internalError("Cantino internal error: into_assembler returned no handle")
        }
        return BasemapAssembler(handle: assembler)
    }

    /// Frees the plan. Idempotent; owner thread only (otherwise
    /// ``CantinoError/wrongThread(_:)`` and the plan stays alive).
    func free() throws {
        guard let handle else { return }
        var call = NativeCall()
        _ = try call.check(cantino_basemap_plan_free(handle, &call.error))
        self.handle = nil
    }

    private func live() throws(CantinoStateError) -> OpaquePointer {
        guard let handle else { throw .closed("basemap plan is freed or consumed") }
        return handle
    }
}

/// Writes the tile ranges of a finished ``BasemapPlan`` into a staging file
/// and publishes the extract (`CantinoBasemapAssembler`).
///
/// Flow: write every ``remaining()`` range (``writeRange(id:bytes:)`` or
/// ``writeRangeFile(id:path:)``, idempotent, any order), then
/// ``finish(output:)`` (header last, fsync, atomic rename), then ``free()``
/// in every case. Freeing an unfinished assembler deletes its staging file.
///
/// Thread-confined to the thread that called
/// ``BasemapPlan/intoAssembler(staging:)``, like ``BasemapPlan``.
final class BasemapAssembler {
    private var handle: OpaquePointer?

    fileprivate init(handle: OpaquePointer) {
        self.handle = handle
    }

    /// Best-effort free (owner thread only), as ``BasemapPlan``'s deinit.
    deinit {
        if let handle {
            var call = NativeCall()
            _ = cantino_basemap_asm_free(handle, &call.error)
        }
    }

    /// Writes the response to tile request `id`. A response of the wrong
    /// length or an unknown id throws ``CantinoError/invalidArgument(_:)``.
    func writeRange(id: Int64, bytes: [UInt8]) throws {
        let handle = try live()
        var call = NativeCall()
        let status = bytes.withUnsafeBufferPointer { buffer in
            cantino_basemap_asm_write_range(handle, UInt64(bitPattern: id), buffer.baseAddress, buffer.count,
                                            &call.error)
        }
        _ = try call.check(status)
    }

    /// Writes the response to tile request `id` from a file holding exactly
    /// that response (streamed by the core; the caller deletes the file).
    func writeRangeFile(id: Int64, path: String) throws {
        let handle = try live()
        var call = NativeCall()
        _ = try call.check(cantino_basemap_asm_write_range_file(handle, UInt64(bitPattern: id), path, &call.error))
    }

    /// The tile requests not yet written.
    func remaining() throws -> [ByteRange] {
        let handle = try live()
        var call = NativeCall()
        _ = try call.check(cantino_basemap_asm_remaining(handle, &call.result, &call.error))
        return try Wire.decode([ByteRange].self, call.resultString())
    }

    /// Ranges and bytes written so far.
    func progress() throws -> BasemapProgress {
        let handle = try live()
        var call = NativeCall()
        _ = try call.check(cantino_basemap_asm_progress(handle, &call.result, &call.error))
        return try Wire.decode(BasemapProgress.self, call.resultString())
    }

    /// Writes the header, fsyncs and renames the staging file over `output`
    /// atomically. Refuses with ``CantinoError/invalidArgument(_:)`` (output
    /// untouched) while ranges are missing. Call ``free()`` afterwards in
    /// every case.
    func finish(output: String) throws {
        let handle = try live()
        var call = NativeCall()
        _ = try call.check(cantino_basemap_asm_finish(handle, output, &call.error))
    }

    /// Frees the assembler (deleting the staging file if unfinished).
    /// Idempotent; owner thread only.
    func free() throws {
        guard let handle else { return }
        var call = NativeCall()
        _ = try call.check(cantino_basemap_asm_free(handle, &call.error))
        self.handle = nil
    }

    private func live() throws(CantinoStateError) -> OpaquePointer {
        guard let handle else { throw .closed("basemap assembler is freed") }
        return handle
    }
}

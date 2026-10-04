// Public models of the area download lifecycle: the Swift counterparts of
// the Kotlin adapter's AreaModels.kt and the basemap source in Basemap.kt.
// Field names, defaults and semantics follow Kotlin; deviations are listed
// in swift/README.md.

import Foundation

/// Download endpoint, HTTP timeout and import options of an ``AreaManager``.
/// Transient failures retry automatically; polling and retry timing are
/// internal defaults. The config is stored with each download, so a run
/// resumed after the app was killed uses the config it started with.
public struct AreaConfig: Hashable, Sendable {
    /// The public SliceOSM service.
    public static let defaultSliceBaseUrl = "https://slice.openstreetmap.us/"

    /// SliceOSM service root, ending in `/`. A custom endpoint may use HTTP
    /// or HTTPS (iOS App Transport Security must allow plain HTTP).
    public var sliceBaseUrl: String
    /// Maximum silence during an HTTP request or response, in seconds
    /// (`URLRequest.timeoutInterval`). Kotlin has separate connect and read
    /// timeouts; URLSession has one idle timeout.
    public var timeout: TimeInterval
    /// On-device import settings; defaults to a full import.
    public var importOptions: ImportOptions

    public init(sliceBaseUrl: String = AreaConfig.defaultSliceBaseUrl, timeout: TimeInterval = 60,
                importOptions: ImportOptions = ImportOptions()) {
        self.sliceBaseUrl = sliceBaseUrl
        self.timeout = timeout
        self.importOptions = importOptions
    }
}

/// Where an area's offline basemap comes from. Opt-in per download
/// (``AreaManager/download(areaId:bbox:name:basemap:)``); the default
/// ``none`` downloads OSM data only.
///
/// The basemap is a PMTiles v3 archive published next to the area
/// (``AreaInfo/basemapURL``, ``AreaInfo/pmtilesURL`` for MapLibre). It is
/// part of the area: the area is ``AreaState/ready(runId:area:)`` only when
/// OSM data and the requested basemap are both published, and a refresh
/// replaces both (a refresh with ``none`` removes a previous basemap).
///
/// A struct with validating factories (Kotlin: a sealed interface whose
/// constructors throw), so an invalid source cannot be built.
public struct BasemapSource: Hashable, Sendable {
    enum Kind: Hashable, Sendable {
        case none
        case url(String)
        case extract(planetUrl: String, maxZoom: Int, overfetch: Double)
    }

    let kind: Kind

    /// No basemap.
    public static let none = BasemapSource(kind: .none)

    /// A ready-made PMTiles v3 file for this area, downloaded as is and
    /// validated (magic, version, sections within the file) before it is
    /// published; anything else fails the download (not retryable). Throws
    /// ``CantinoError/invalidArgument(_:)`` unless `url` is http(s).
    public static func url(_ url: String) throws -> BasemapSource {
        try requireHttp(url)
        return BasemapSource(kind: .url(url))
    }

    /// Cut the area's bbox out of a large remote PMTiles archive on the
    /// device with HTTP range requests (the Rust engine plans and assembles,
    /// Swift fetches with up to four parallel requests). The server must
    /// answer 206; one answering 200 fails the download. Zooms are the
    /// archive's minimum up to `maxZoom` (clamped to the archive's);
    /// `overfetch` is the extra bytes allowed per wanted byte to save
    /// requests (go-pmtiles' default 0.05). Throws
    /// ``CantinoError/invalidArgument(_:)`` unless `planetUrl` is http(s),
    /// `maxZoom` is 0...31 and `overfetch` is finite and >= 0.
    public static func extract(planetUrl: String, maxZoom: Int = 15, overfetch: Double = 0.05) throws -> BasemapSource {
        try requireHttp(planetUrl)
        guard (0...31).contains(maxZoom) else { throw CantinoError.invalidArgument("maxZoom must be 0...31: \(maxZoom)") }
        guard overfetch.isFinite, overfetch >= 0 else {
            throw CantinoError.invalidArgument("overfetch must be >= 0: \(overfetch)")
        }
        return BasemapSource(kind: .extract(planetUrl: planetUrl, maxZoom: maxZoom, overfetch: overfetch))
    }

    private static func requireHttp(_ url: String) throws(CantinoError) {
        guard url.hasPrefix("https://") || url.hasPrefix("http://") else {
            throw CantinoError.invalidArgument("basemap URL must be http(s): \(url)")
        }
    }
}

/// Progress phase of ``AreaState/basemap(runId:phase:bytes:totalBytes:)``.
public enum BasemapPhase: String, Hashable, Sendable {
    /// ``BasemapSource/url(_:)``: downloading the file.
    case download
    /// ``BasemapSource/extract(planetUrl:maxZoom:overfetch:)``: reading the
    /// archive's header and directories (no total yet).
    case directories
    /// Extract: fetching tile data; the total is the bytes to transfer.
    case tiles
}

/// A published area as found on disk: OSM data and/or a basemap, metadata.
public struct AreaInfo: Hashable, Sendable {
    /// The app-chosen area ID.
    public let areaId: String
    /// The OSM data (`<root>/<areaId>.sqlite`); open it with
    /// ``OsmStore/open(_:)-(URL)`` or ``AsyncOsmStore``. Nil for a
    /// basemap-only area (``AreaManager/downloadBasemap(areaId:bbox:basemap:)``).
    public let dataURL: URL?
    /// What the download recorded (bbox, snapshot age, import report,
    /// basemap). Nil when the sidecar is missing or does not describe these
    /// files; the files themselves are still complete and valid.
    public let metadata: AreaMetadata?
    /// The published PMTiles basemap, or nil without one.
    public let basemapURL: URL?

    public init(areaId: String, dataURL: URL?, metadata: AreaMetadata?, basemapURL: URL? = nil) {
        self.areaId = areaId
        self.dataURL = dataURL
        self.metadata = metadata
        self.basemapURL = basemapURL
    }

    /// The basemap as a MapLibre Native source URL
    /// (`pmtiles://file:///.../<areaId>.pmtiles`), or nil without a basemap.
    public var pmtilesURL: String? { basemapURL.map { "pmtiles://file://\($0.path)" } }
}

/// How a published basemap was obtained; see ``BasemapSource``.
public enum BasemapKind: String, Hashable, Sendable {
    /// Downloaded as a ready-made file.
    case url
    /// Cut on the device from a remote archive.
    case extract
}

/// The published basemap of an area, as recorded when it was downloaded.
public struct BasemapMetadata: Hashable, Sendable {
    /// How it was obtained.
    public let kind: BasemapKind
    /// The file (``BasemapKind/url``) or remote archive (``BasemapKind/extract``) it came from.
    public let sourceUrl: String
    /// Size of the published PMTiles file.
    public let fileBytes: Int64
    /// Tiles addressable in the file.
    public let addressedTiles: Int64
    /// Lowest zoom level in the file.
    public let minZoom: Int
    /// Highest zoom level in the file.
    public let maxZoom: Int
    /// HTTP requests the download made (1 for a URL download).
    public let requests: Int64
    /// Bytes transferred (the file size for a URL download).
    public let transferredBytes: Int64

    public init(kind: BasemapKind, sourceUrl: String, fileBytes: Int64, addressedTiles: Int64, minZoom: Int,
                maxZoom: Int, requests: Int64, transferredBytes: Int64) {
        self.kind = kind
        self.sourceUrl = sourceUrl
        self.fileBytes = fileBytes
        self.addressedTiles = addressedTiles
        self.minZoom = minZoom
        self.maxZoom = maxZoom
        self.requests = requests
        self.transferredBytes = transferredBytes
    }
}

/// What a download recorded about the area it published (the sidecar
/// `<areaId>.json`, a format shared with Android).
public struct AreaMetadata: Hashable, Sendable {
    /// The requested area.
    public let bbox: Bbox
    /// The job name given to the download.
    public let name: String
    /// SliceOSM's replication timestamp of the OSM data: show it to users as
    /// the age of the data. Nil if the server reported none or one that is
    /// not an RFC 3339 date-time.
    public let snapshotTimestamp: Date?
    /// Device clock (Unix milliseconds) when the area was published.
    public let importedAtMillis: Int64
    /// The import's object counts, database size and profile; nil for a
    /// basemap-only area.
    public let report: ImportReport?
    /// The published basemap, nil for ``BasemapSource/none``.
    public let basemap: BasemapMetadata?
    /// The download run that published the area (nil for areas published
    /// by hand). Changes with every refresh, so it also serves as a version
    /// key for caches of the area's content.
    public let workId: UUID?

    public init(bbox: Bbox, name: String, snapshotTimestamp: Date?, importedAtMillis: Int64, report: ImportReport?,
                basemap: BasemapMetadata? = nil, workId: UUID? = nil) {
        self.bbox = bbox
        self.name = name
        self.snapshotTimestamp = snapshotTimestamp
        self.importedAtMillis = importedAtMillis
        self.report = report
        self.basemap = basemap
        self.workId = workId
    }

    /// RFC 3339 (SliceOSM sends `2026-10-03T20:30:01Z`): `Z` or a numeric
    /// offset, optional fraction, `T`/`t` or a space between date and time.
    /// Anything else (or nil) is nil, never an error: a malformed server
    /// timestamp must not fail a download or hide an area.
    static func parseSnapshotTimestamp(_ value: String?) -> Date? {
        guard var text = value?.trimmingCharacters(in: .whitespaces).uppercased(), !text.isEmpty else { return nil }
        if text.count > 10, text[text.index(text.startIndex, offsetBy: 10)] == " " {
            text.replaceSubrange(text.index(text.startIndex, offsetBy: 10)...text.index(text.startIndex, offsetBy: 10),
                                 with: "T")
        }
        let plain = ISO8601DateFormatter()
        let fractional = ISO8601DateFormatter()
        fractional.formatOptions.insert(.withFractionalSeconds)
        return plain.date(from: text) ?? fractional.date(from: text)
    }

    /// The sidecar spelling of a timestamp, as Kotlin's `Instant.toString()`:
    /// UTC, `Z`, fractional seconds only when present.
    static func formatSnapshotTimestamp(_ date: Date) -> String {
        let formatter = ISO8601DateFormatter()
        if date.timeIntervalSince1970.rounded(.down) != date.timeIntervalSince1970 {
            formatter.formatOptions.insert(.withFractionalSeconds)
        }
        return formatter.string(from: date)
    }

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
        var reportJSON = JSONValue.null
        if let report {
            var object: [String: JSONValue] = [
                "counts": .object([
                    "nodes": .int(report.counts.nodes), "ways": .int(report.counts.ways),
                    "relations": .int(report.counts.relations),
                ]),
                "database_bytes": .int(report.databaseBytes),
            ]
            if let profile = report.profile { object["profile"] = profile.json }
            reportJSON = .object(object)
        }
        return .object([
            "bbox": .object([
                "west": .double(bbox.west), "south": .double(bbox.south),
                "east": .double(bbox.east), "north": .double(bbox.north),
            ]),
            "name": .string(name),
            "snapshot_timestamp": snapshotTimestamp.map { .string(Self.formatSnapshotTimestamp($0)) } ?? .null,
            "imported_at_millis": .int(importedAtMillis),
            "report": reportJSON,
            "basemap": basemapJSON,
            "work_id": workId.map { .string($0.uuidString.lowercased()) } ?? .null,
        ])
    }
}

/// Why a download ended in ``AreaState/failed(runId:message:retryable:reason:)``,
/// grouped by what the app (or its user) can do about it.
///
/// | Reason | Typical causes | Retryable |
/// | --- | --- | --- |
/// | ``network`` | No connection, timeouts, a dropped or truncated transfer | yes, after retries ran out |
/// | ``server`` | 5xx, 408, 429, a vanished job, an unparseable answer, a job that never finishes, no range support | mostly yes |
/// | ``invalidRequest`` | Invalid bbox or name, other HTTP 4xx, zooms the archive lacks | no |
/// | ``storage`` | Disk full, a staging file that cannot be written, publishing interrupted by an I/O error | yes |
/// | ``invalidData`` | A PBF that fails to import, a basemap that is not a valid PMTiles v3 archive | no |
/// | ``unknown`` | An unexpected error (a bug in Cantino) | no |
public enum FailureReason: String, Hashable, Sendable {
    case network
    case server
    case invalidRequest = "invalid_request"
    case storage
    case invalidData = "invalid_data"
    case unknown
}

/// Lifecycle of one area's download, as observed through
/// ``AreaManager/state(areaId:)``.
///
/// ```
/// idle ─download()→ submitting → slicing → downloading → importing ─┬──────────→ ready
///                       ↑                                            └→ basemap ┘
///                    queued ← transient failure (backoff, same SliceOSM job resumed)
/// any non-final state ─cancel()→ cancelled    permanent failure / retries exhausted → failed
/// ```
///
/// Invariant for every state: the previously published area stays intact
/// and openable until one short commit step publishes the replacement, all
/// parts together. Only a run that reaches ``ready(runId:area:)`` replaces
/// it.
///
/// Every state except ``idle(published:)`` carries ``runId``, the value
/// ``AreaManager/download(areaId:bbox:name:basemap:)`` returned. Unlike
/// Android (WorkManager keeps finished work for about a day), final states
/// live in memory only: after the app restarts, a finished run reads as
/// ``idle(published:)``.
public enum AreaState: Hashable, Sendable {
    /// No download is known; `published` is the area on disk, if any.
    case idle(published: AreaInfo?)
    /// Waiting before the next attempt after a transient failure.
    /// `previousRuns` attempts already happened.
    case queued(runId: UUID, previousRuns: Int)
    /// Submitting the job to SliceOSM, or re-attaching to the job of an
    /// interrupted run.
    case submitting(runId: UUID)
    /// SliceOSM is cutting the extract; `fraction` 0...1, nil before the
    /// server reports totals.
    case slicing(runId: UUID, fraction: Double?)
    /// Downloading the OSM data (PBF); `totalBytes` nil when the server
    /// sends no length.
    case downloading(runId: UUID, bytes: Int64, totalBytes: Int64?)
    /// Importing into a staging file: nothing is published yet. The import
    /// cannot be interrupted, but a cancel during it is honored when it
    /// returns.
    case importing(runId: UUID)
    /// Downloading the basemap after the OSM data was imported (staged).
    case basemap(runId: UUID, phase: BasemapPhase, bytes: Int64, totalBytes: Int64?)
    /// Published: OSM data and, if requested, the basemap. Already-open
    /// stores keep the old snapshot; reopen to see the new data.
    case ready(runId: UUID, area: AreaInfo)
    /// Gave up. `reason` says why; `message` is developer-facing.
    /// `retryable` is true for transient causes that exhausted their retries.
    case failed(runId: UUID, message: String, retryable: Bool, reason: FailureReason)
    /// Cancelled before publishing began: nothing of this run was published.
    case cancelled(runId: UUID)

    /// The run this state belongs to; nil only for ``idle(published:)``.
    public var runId: UUID? {
        switch self {
        case .idle: nil
        case .queued(let id, _), .submitting(let id), .slicing(let id, _), .downloading(let id, _, _),
             .importing(let id), .basemap(let id, _, _, _), .ready(let id, _), .failed(let id, _, _, _),
             .cancelled(let id):
            id
        }
    }

    /// True for the states a run ends in: ready, failed and cancelled.
    public var isTerminal: Bool {
        switch self {
        case .ready, .failed, .cancelled: true
        default: false
        }
    }
}

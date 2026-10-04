// Swift mirrors of the Rust model (src/model.rs, src/store.rs) and of the
// Kotlin models (android/cantino/.../Models.kt). JSON is the wire format
// across the C ABI only; nothing outside this module builds or parses it,
// and no JSON type appears in the public API. Field semantics follow the
// Rust docs: raw tags, integer 1e-7 coordinates, ordered (possibly repeated)
// references, metadata that may be absent.
//
// Every model is an immutable, Sendable value type: equal content means
// equal values, and they may be shared freely between threads and tasks.
//
// Types the library *returns* (OsmObject and its parts, ObjectMetadata,
// ImportReport, ObjectCounts, Coordinate) have internal initialisers, like
// Kotlin's internal constructors: apps read them, only the adapter (and
// `@testable` tests) make them, so fields can be added later. Types apps
// *construct* (OsmId, Bbox, TagFilter, Query, ImportOptions) have public
// initialisers with Kotlin's defaults.

import Foundation

/// The three OSM object namespaces. IDs are only unique within a kind:
/// node 42 and way 42 are different objects (see ``OsmId``).
///
/// Raw values are the C ABI's kind codes (node=0, way=1, relation=2).
public enum OsmKind: Int32, Sendable, CaseIterable, Hashable {
    /// A point with a coordinate.
    case node = 0
    /// An ordered list of node references (a line, or a closed ring).
    case way = 1
    /// An ordered list of members of any kind, each with a role.
    case relation = 2

    /// The core's JSON spelling ("node", "way", "relation").
    var wire: String {
        switch self {
        case .node: "node"
        case .way: "way"
        case .relation: "relation"
        }
    }

    init?(wire: String) {
        switch wire {
        case "node": self = .node
        case "way": self = .way
        case "relation": self = .relation
        default: return nil
        }
    }
}

/// Identity of an OSM object: its `kind` and its numeric `id` within that
/// kind. IDs are the raw OSM IDs of the extract (positive for data from the
/// OSM database).
public struct OsmId: Hashable, Sendable, CustomStringConvertible {
    /// Node, way or relation.
    public var kind: OsmKind
    /// The OSM ID within `kind`.
    public var id: Int64

    public init(_ kind: OsmKind, _ id: Int64) {
        self.kind = kind
        self.id = id
    }

    public var description: String { "\(kind.wire)/\(id)" }

    /// The core's ID shape: `{"type":"node","id":1}`.
    var json: JSONValue { .object(["type": .string(kind.wire), "id": .int(id)]) }
}

/// Editing metadata of one object version, as found in the source extract.
///
/// Absent source fields are stored as zero/empty, so a zero or empty value
/// may mean "unknown" rather than a real value (SliceOSM extracts usually
/// carry all fields). `timestampSeconds` is Unix seconds (UTC).
public struct ObjectMetadata: Hashable, Sendable {
    /// Object version.
    public let version: Int64
    /// Edit time, Unix seconds (UTC).
    public let timestampSeconds: Int64
    /// Changeset ID.
    public let changeset: Int64
    /// User ID of the editor.
    public let uid: Int64
    /// User name of the editor.
    public let user: String

    init(version: Int64, timestampSeconds: Int64, changeset: Int64, uid: Int64, user: String) {
        self.version = version
        self.timestampSeconds = timestampSeconds
        self.changeset = changeset
        self.uid = uid
        self.user = user
    }
}

/// A WGS84 point, as returned by ``OsmStore/wayCoordinates(_:)`` and
/// ``OsmStore/representativePoint(_:)``. Integers in 1e-7 degrees
/// (`latE7`, `lonE7`), the storage format, so they compare exactly; `lat`
/// and `lon` are the same values in degrees.
public struct Coordinate: Hashable, Sendable {
    /// Latitude in 1e-7 degrees.
    public let latE7: Int32
    /// Longitude in 1e-7 degrees.
    public let lonE7: Int32

    init(latE7: Int32, lonE7: Int32) {
        self.latE7 = latE7
        self.lonE7 = lonE7
    }

    /// Latitude in degrees (WGS84).
    public var lat: Double { Double(latE7) / 1e7 }
    /// Longitude in degrees (WGS84).
    public var lon: Double { Double(lonE7) / 1e7 }
}

/// One OSM object as stored in the area: a node, way or relation.
///
/// Kotlin models this as a sealed interface with `Node`/`Way`/`Relation`
/// classes; Swift uses an enum with one payload struct per kind, so a
/// `switch` is exhaustive. The common properties (`id`, `tags`, `metadata`)
/// are available on the enum directly, and ``node``/``way``/``relation``
/// give the payload when you expect one kind (Kotlin's `as? OsmObject.Node`).
///
/// Values own copies of their data: they stay valid after the ``OsmStore``
/// that returned them is closed, and may be passed to any thread.
public enum OsmObject: Hashable, Sendable {
    case node(Node)
    case way(Way)
    case relation(Relation)

    /// A node. Coordinates are integers in 1e-7 degrees (`latE7`, `lonE7`),
    /// the storage format, so they compare and round-trip without float
    /// drift; `lat` and `lon` are the same values in degrees.
    ///
    /// `locationVersion` is the object version at which the node got its
    /// current coordinate; it is kept even when `metadata` is not.
    ///
    /// Untagged nodes (the vertices of ways) are stored and returned by
    /// ``OsmStore/get(_:)-(OsmId)``, but they are not spatially indexed: a
    /// bbox ``Query`` never returns them. Reach them through their ways.
    public struct Node: Hashable, Sendable {
        public let id: OsmId
        /// Latitude in 1e-7 degrees.
        public let latE7: Int32
        /// Longitude in 1e-7 degrees.
        public let lonE7: Int32
        /// Object version at which the node got its current coordinate.
        public let locationVersion: Int32
        /// Raw tags (see ``OsmObject/tags``).
        public let tags: [String: String]
        /// Editing metadata (see ``OsmObject/metadata``).
        public let metadata: ObjectMetadata?

        init(id: OsmId, latE7: Int32, lonE7: Int32, locationVersion: Int32, tags: [String: String], metadata: ObjectMetadata?) {
            self.id = id
            self.latE7 = latE7
            self.lonE7 = lonE7
            self.locationVersion = locationVersion
            self.tags = tags
            self.metadata = metadata
        }

        /// Latitude in degrees (WGS84).
        public var lat: Double { Double(latE7) / 1e7 }
        /// Longitude in degrees (WGS84).
        public var lon: Double { Double(lonE7) / 1e7 }
    }

    /// A way. `nodeIds` are the node references in order; repeats are
    /// significant (a closed way repeats its first node at the end). A
    /// referenced node may be missing from the area when the way crosses
    /// the area's edge: ``OsmStore/get(_:)-(OsmId)`` then returns nil for it.
    public struct Way: Hashable, Sendable {
        public let id: OsmId
        /// Node references in order (repeats are significant).
        public let nodeIds: [Int64]
        public let tags: [String: String]
        public let metadata: ObjectMetadata?

        init(id: OsmId, nodeIds: [Int64], tags: [String: String], metadata: ObjectMetadata?) {
            self.id = id
            self.nodeIds = nodeIds
            self.tags = tags
            self.metadata = metadata
        }
    }

    /// One member of a ``Relation``: the referenced object and its `role`
    /// (raw string, may be empty). The member object may be missing from
    /// the area (outside it, or never part of the extract).
    public struct Member: Hashable, Sendable {
        /// The referenced object.
        public let id: OsmId
        /// The member's role, raw (may be empty).
        public let role: String

        init(id: OsmId, role: String) {
            self.id = id
            self.role = role
        }
    }

    /// A relation. `members` are in order; the same member may appear more
    /// than once.
    public struct Relation: Hashable, Sendable {
        public let id: OsmId
        /// Members in order.
        public let members: [Member]
        public let tags: [String: String]
        public let metadata: ObjectMetadata?

        init(id: OsmId, members: [Member], tags: [String: String], metadata: ObjectMetadata?) {
            self.id = id
            self.members = members
            self.tags = tags
            self.metadata = metadata
        }
    }

    /// Kind and ID; `id.kind` matches the case.
    public var id: OsmId {
        switch self {
        case .node(let n): n.id
        case .way(let w): w.id
        case .relation(let r): r.id
        }
    }

    /// Raw tags, exactly as in OSM: no normalization; an empty value is
    /// distinct from a missing key.
    public var tags: [String: String] {
        switch self {
        case .node(let n): n.tags
        case .way(let w): w.tags
        case .relation(let r): r.tags
        }
    }

    /// Editing metadata, or nil for untagged nodes imported without
    /// ``ImportOptions/preserveUntaggedMetadata`` (they keep only
    /// ``Node/locationVersion``). Tagged nodes, ways and relations always
    /// have it.
    public var metadata: ObjectMetadata? {
        switch self {
        case .node(let n): n.metadata
        case .way(let w): w.metadata
        case .relation(let r): r.metadata
        }
    }

    /// The node payload, or nil for a way or relation.
    public var node: Node? { if case .node(let n) = self { n } else { nil } }
    /// The way payload, or nil for a node or relation.
    public var way: Way? { if case .way(let w) = self { w } else { nil } }
    /// The relation payload, or nil for a node or way.
    public var relation: Relation? { if case .relation(let r) = self { r } else { nil } }
}

/// A WGS84 rectangle in degrees: `west`/`east` longitudes, `south`/`north`
/// latitudes. It must not wrap: `west <= east` and `south <= north`; split
/// an area that crosses the antimeridian into two boxes.
///
/// The initialiser does not validate. An invalid box (wrong order, outside
/// ±180/±90) is rejected where it is used: ``OsmStore/query(_:)`` throws
/// ``CantinoError/invalidArgument(_:)``. A non-finite edge is rejected the
/// same way by the adapter, before the call (JSON cannot carry NaN).
public struct Bbox: Hashable, Sendable {
    /// Western edge, longitude in degrees (-180..180).
    public var west: Double
    /// Southern edge, latitude in degrees (-90..90).
    public var south: Double
    /// Eastern edge, longitude in degrees, `>= west`.
    public var east: Double
    /// Northern edge, latitude in degrees, `>= south`.
    public var north: Double

    public init(west: Double, south: Double, east: Double, north: Double) {
        self.west = west
        self.south = south
        self.east = east
        self.north = north
    }

    private static let kmPerDegree = 111.32
    private static let maxLat = 85.0

    /// A `widthKm` (east-west) by `heightKm` (north-south) box centred on
    /// (`lat`, `lon`); `heightKm` defaults to a square.
    ///
    /// Uses a flat-earth (equirectangular) approximation: 111.32 km per
    /// degree of latitude, and that times cos(latitude) per degree of
    /// longitude. Fine at city scale, increasingly rough for boxes of
    /// hundreds of km or near the poles. The math is the Kotlin adapter's,
    /// operation for operation.
    ///
    /// Edges are clamped, never wrapped (a Bbox cannot wrap): latitudes to
    /// -85..85 (the Web Mercator range, so the box stays usable for map
    /// tiles) and longitudes to -180..180. A box that would cross the
    /// antimeridian is therefore cut at it and comes out narrower than
    /// asked; to cover both sides, build two boxes.
    ///
    /// Throws ``CantinoError/invalidArgument(_:)`` for a non-finite or
    /// out-of-range centre (`lat` outside -90..90, `lon` outside -180..180)
    /// or a non-positive or non-finite size. (Kotlin throws
    /// `IllegalArgumentException`; a Swift precondition would be untestable
    /// and crash on user input, so this throws instead.)
    public static func around(lat: Double, lon: Double, widthKm: Double, heightKm: Double? = nil) throws(CantinoError) -> Bbox {
        let heightKm = heightKm ?? widthKm
        guard lat.isFinite, (-90.0...90.0).contains(lat) else {
            throw .invalidArgument("lat must be within -90..90: \(lat)")
        }
        guard lon.isFinite, (-180.0...180.0).contains(lon) else {
            throw .invalidArgument("lon must be within -180..180: \(lon)")
        }
        guard widthKm.isFinite, widthKm > 0 else { throw .invalidArgument("widthKm must be positive: \(widthKm)") }
        guard heightKm.isFinite, heightKm > 0 else { throw .invalidArgument("heightKm must be positive: \(heightKm)") }
        let halfLat = heightKm / 2 / kmPerDegree
        // cos(lat) -> 0 at the poles; use the clamped latitude so the
        // longitude span stays finite (the result is clamped anyway).
        // Java's Math.toRadians multiplies by the constant pi/180, as here.
        let clampedLat = min(max(lat, -maxLat), maxLat)
        let cosLat = cos(clampedLat * (Double.pi / 180.0))
        let halfLon = widthKm / 2 / (kmPerDegree * cosLat)
        return Bbox(
            west: max(lon - halfLon, -180.0),
            south: max(lat - halfLat, -maxLat),
            east: min(lon + halfLon, 180.0),
            north: min(lat + halfLat, maxLat)
        )
    }

    /// `{"west","south","east","north"}`; throws for a non-finite edge.
    func json() throws(CantinoError) -> JSONValue {
        for edge in [west, south, east, north] where !edge.isFinite {
            throw .invalidArgument("bbox edges must be finite: \(self)")
        }
        return .object(["west": .double(west), "south": .double(south), "east": .double(east), "north": .double(north)])
    }
}

/// One tag predicate of a ``Query``. Matching is on raw strings:
/// case-sensitive, no trimming; a missing tag and a tag with an empty value
/// are distinct.
public enum TagFilter: Hashable, Sendable {
    /// The object has tag `key`, with any value (including empty).
    case exists(String)
    /// The object has tag `key` with exactly `value`.
    case equals(String, String)
    /// The object does **not** have tag `key` (e.g. amenities without
    /// `opening_hours`).
    ///
    /// Absence has no index, so this filter never drives a query: it is
    /// checked on the candidates another filter produces. A ``Query`` needs
    /// at least one ``exists(_:)`` or ``equals(_:_:)`` filter, or a
    /// ``Query/bbox``, next to it; a query whose only filters are
    /// `notExists` throws ``CantinoError/invalidArgument(_:)`` instead of
    /// scanning the whole area.
    case notExists(String)

    /// Wire form: `{"Exists":k}`, `{"Equals":[k,v]}`, `{"NotExists":k}`.
    var json: JSONValue {
        switch self {
        case .exists(let key): .object(["Exists": .string(key)])
        case .equals(let key, let value): .object(["Equals": .array([.string(key), .string(value)])])
        case .notExists(let key): .object(["NotExists": .string(key)])
        }
    }
}

/// A query against an ``OsmStore``: all `tags` filters must match (AND),
/// and when `bbox` is set the object must be a spatial candidate for it. No
/// filters at all returns every object, page by page.
///
/// **Spatial results are candidates, not exact intersections.** Tagged
/// nodes match exactly (point inside the box). Ways and relations match
/// when their bounding box intersects the box: a way crossing the box
/// without a node inside it is included, and so is one that merely bends
/// around the box. Bounds are computed from the members present in the
/// area, so objects clipped at the area's edge can be missed near that
/// edge. **Untagged nodes are never returned by a bbox query** (they are
/// not spatially indexed); reach them through their ways.
///
/// Results are ordered by kind (nodes, ways, relations), then ascending ID.
/// At most `limit` (1..10 000) objects come back per call; to fetch the
/// next page, pass the last result's id as `after` (Kotlin's
/// `copy(after = ...)` is `var next = query; next.after = ...` here). A
/// page shorter than `limit` is the last one.
///
/// `maxCandidates` bounds the spatial candidates a bbox-driven query
/// collects (all kinds together). Exceeding it throws
/// ``CantinoError/invalidArgument(_:)``; results are never silently
/// truncated. Narrow the box or add a tag filter.
public struct Query: Hashable, Sendable {
    /// Tag filters, all of which must match (AND). Empty: no tag condition.
    public var tags: [TagFilter]
    /// Spatial candidate filter, or nil for none.
    public var bbox: Bbox?
    /// Keyset cursor: only objects ordered after this ID (the last result
    /// of the previous page). Nil for the first page.
    public var after: OsmId?
    /// Maximum objects per call, 1..10 000. Default 100.
    public var limit: Int
    /// Maximum spatial candidates a bbox-driven query may collect before it
    /// fails with ``CantinoError/invalidArgument(_:)``. Default 100 000.
    public var maxCandidates: Int

    public init(tags: [TagFilter] = [], bbox: Bbox? = nil, after: OsmId? = nil, limit: Int = 100, maxCandidates: Int = 100_000) {
        self.tags = tags
        self.bbox = bbox
        self.after = after
        self.limit = limit
        self.maxCandidates = maxCandidates
    }

    /// The core's query JSON (src/store.rs `Query`), with explicit nulls
    /// like the Kotlin adapter. A negative limit or candidate bound is sent
    /// as is: the core reads them as unsigned and rejects them as malformed
    /// (InvalidArgument), the same outcome as in Kotlin.
    func json() throws(CantinoError) -> String {
        JSONValue.object([
            "tags": .array(tags.map(\.json)),
            "bbox": try bbox?.json() ?? .null,
            "after": after?.json ?? .null,
            "limit": .int(Int64(limit)),
            "max_candidates": .int(Int64(maxCandidates)),
        ]).canonicalString
    }
}

/// Options for ``OsmStore/importArea(input:destination:options:)``.
///
/// `preserveUntaggedMetadata` keeps version/timestamp/changeset/user for
/// untagged nodes too; by default they read back with nil
/// ``OsmObject/metadata`` and only ``OsmObject/Node/locationVersion``,
/// which makes the database noticeably smaller. `cacheMiB` is SQLite's page
/// cache during the import in MiB: resident memory, not a cap on the
/// import's total memory.
public struct ImportOptions: Hashable, Sendable {
    /// Keep full editing metadata for untagged nodes (larger file).
    public var preserveUntaggedMetadata: Bool
    /// SQLite page cache during the import, in MiB.
    public var cacheMiB: Int

    public init(preserveUntaggedMetadata: Bool = false, cacheMiB: Int = 16) {
        self.preserveUntaggedMetadata = preserveUntaggedMetadata
        self.cacheMiB = cacheMiB
    }

    func json() -> String {
        JSONValue.object([
            "preserve_untagged_metadata": .bool(preserveUntaggedMetadata),
            "cache_mb": .int(Int64(cacheMiB)),
        ]).canonicalString
    }
}

/// Number of objects per kind.
public struct ObjectCounts: Hashable, Sendable {
    /// Nodes (tagged and untagged).
    public let nodes: Int64
    /// Ways.
    public let ways: Int64
    /// Relations.
    public let relations: Int64

    init(nodes: Int64, ways: Int64, relations: Int64) {
        self.nodes = nodes
        self.ways = ways
        self.relations = relations
    }
}

/// Result of an import: objects stored per kind, and the size of the
/// published database file in bytes.
public struct ImportReport: Hashable, Sendable {
    /// Objects stored per kind.
    public let counts: ObjectCounts
    /// Size of the published database file in bytes.
    public let databaseBytes: Int64

    init(counts: ObjectCounts, databaseBytes: Int64) {
        self.counts = counts
        self.databaseBytes = databaseBytes
    }
}

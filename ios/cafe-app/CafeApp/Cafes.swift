import Cantino
import Foundation

/*
 * Café policy of the reference app: what counts as a café, where to put it on
 * the map, and how to read outdoor seating. All of this is app-level
 * interpretation on top of raw OSM objects from Cantino (port of the Android
 * café app's Cafes.kt).
 */

/// A WGS84 point in degrees.
struct LatLon: Hashable, Sendable, CustomStringConvertible {
    let lat: Double
    let lon: Double

    var description: String { String(format: "%.5f, %.5f", lat, lon) }
}

/// Outdoor seating as far as the data says. Missing or unrecognised values are unknown.
enum OutdoorSeating: Hashable, Sendable {
    case yes
    case no
    case unknown(raw: String?)

    /// `outdoor_seating=yes|no`. Values naming the kind of seating
    /// (`sidewalk`, `garden`, `terrace`, …; documented on the wiki) also mean
    /// yes. Anything else, including a missing tag, is unknown.
    static func of(_ tags: [String: String]) -> OutdoorSeating {
        guard let raw = tags["outdoor_seating"] else { return .unknown(raw: nil) }
        switch raw.trimmingCharacters(in: .whitespaces).lowercased() {
        case "yes": return .yes
        case "no": return .no
        case let value where seatingKinds.contains(value): return .yes
        default: return .unknown(raw: raw)
        }
    }

    private static let seatingKinds: Set<String> = [
        "sidewalk", "garden", "terrace", "patio", "pedestrian_zone", "street", "veranda", "parklet", "balcony", "roof",
    ]

    var isUnknown: Bool { if case .unknown = self { true } else { false } }
}

/// One café as the app sees it. `location` is nil when none of the object's
/// nodes are in the area (it is still listed, without a distance). For ways
/// and relations it is a simple mean of available vertices; not an exact
/// centroid or a point guaranteed to lie inside the polygon.
struct Cafe: Hashable, Sendable, Identifiable {
    let id: OsmId
    let tags: [String: String]
    let location: LatLon?

    var name: String { tags["name"] ?? "Unnamed café" }
    var outdoorSeating: OutdoorSeating { .of(tags) }
    func openState(at now: LocalTime) -> OpenState { OpeningHours.evaluate(tags["opening_hours"], at: now) }
}

/// What the app keeps of the downloaded OSM data: points of interest only
/// (port of `CafeProfile` in the Android café app's Cafes.kt; keep the rules
/// in step).
///
/// The café app reads nothing but `amenity=cafe` objects and what they
/// reference, and the basemap draws everything else (roads, buildings,
/// water), so storing the rest would only cost space: on the Salt Lake City
/// test area the POI profile is 8.8 MB instead of 128.5 MB
/// (docs/guide/downloading.md, "Keep only what you need").
///
/// Why this is enough for the app: the import keeps references whole (a kept
/// way keeps all its nodes, a kept relation all its members, recursively),
/// so café markers and the inspector's way-node and member rows behave as
/// with a full import. Objects that match none of the keys (a street, a
/// plain building) are not in the area file. The basemap is independent of
/// this profile; it is downloaded in full for the same bbox.
///
/// Changing the rules only affects new downloads: the published area keeps
/// the profile it was built with (``ImportReport/profile``).
enum CafeProfile {
    /// `amenity`, `shop`, `tourism`, `leisure`, `craft`, `office`, `healthcare`, `historic` on nodes, ways and relations.
    static let poi = ImportProfile(
        keep: ["amenity", "shop", "tourism", "leisure", "craft", "office", "healthcare", "historic"]
            .map { KeepRule(kinds: Set(OsmKind.allCases), key: $0) })

    static let importOptions = ImportOptions(profile: poi)

    /// The ``AreaManager`` config for the café app's downloads.
    static let areaConfig = AreaConfig(importOptions: importOptions)

    /// A short label for the profile an area records (``ImportReport/profile``),
    /// or nil when it records none. Nil is not shown as "all map data": the
    /// area metadata currently drops the recorded profile (core sidecar
    /// parser), so a POI area reads back without one as well.
    static func label(_ profile: ImportProfile?) -> String? {
        switch profile {
        case nil: nil
        case poi: "points of interest only"
        case let other?: "filtered: " + other.keep.map(\.key).joined(separator: ", ")
        }
    }

    /// The detail screen's text for an object the area does not contain. The
    /// app downloads with an import profile, so the object may be outside the
    /// area *or* filtered out, and the area cannot tell which: both causes
    /// are named, with the profile when the area records one.
    static func notFoundText(_ profile: ImportProfile?) -> String {
        "Not in this area or filtered out: the offline area does not contain this object. It lies outside the area "
            + "(or was clipped at its edge), or the import profile"
            + (label(profile).map { " (\($0))" } ?? "") + " did not keep it."
    }
}

enum CafeLoader {
    static let pageSize = 500

    /// All `amenity=cafe` objects in `bbox` (nodes, ways, relations), paged
    /// with Cantino's keyset pagination. Runs on the store's thread.
    static func load(_ store: OsmStore, bbox: Bbox) throws -> [Cafe] {
        var objects: [OsmObject] = []
        var query = Query(tags: [.equals("amenity", "cafe")], bbox: bbox, limit: pageSize)
        while true {
            let page = try store.query(query)
            objects += page
            guard page.count == pageSize, let last = page.last else { break }
            query.after = last.id
        }
        return try objects.map { Cafe(id: $0.id, tags: $0.tags, location: try representativePoint(store, $0)) }
    }

    /// Café marker policy: node coordinate, mean of a way's distinct available
    /// vertices, or the first usable node/way member of a relation. Nested
    /// relations are deliberately skipped. This is a marker, not geometry.
    static func representativePoint(_ store: OsmStore, _ object: OsmObject) throws -> LatLon? {
        switch object {
        case .node(let node):
            return LatLon(lat: node.lat, lon: node.lon)
        case .way(let way):
            guard let coordinates = try store.wayCoordinates(way.id.id) else { return nil }
            var seen = Set<Coordinate>()
            let points = coordinates.compactMap { $0 }.filter { seen.insert($0).inserted }
            guard !points.isEmpty else { return nil }
            let count = Double(points.count)
            return LatLon(lat: points.map(\.lat).reduce(0, +) / count, lon: points.map(\.lon).reduce(0, +) / count)
        case .relation(let relation):
            // One batch get for the node/way members, in member order.
            let ids = relation.members.map(\.id).filter { $0.kind != .relation }
            for member in try store.get(ids) {
                if let member, let point = try representativePoint(store, member) { return point }
            }
            return nil
        }
    }
}

enum Geo {
    private static let earthRadiusM = 6_371_000.0

    static func center(_ bbox: Bbox) -> LatLon {
        LatLon(lat: (bbox.south + bbox.north) / 2, lon: (bbox.west + bbox.east) / 2)
    }

    static func contains(_ bbox: Bbox, _ point: LatLon) -> Bool {
        (bbox.south...bbox.north).contains(point.lat) && (bbox.west...bbox.east).contains(point.lon)
    }

    /// Great-circle distance in metres (haversine).
    static func distanceMeters(_ a: LatLon, _ b: LatLon) -> Double {
        let rad = Double.pi / 180
        let dLat = (b.lat - a.lat) * rad
        let dLon = (b.lon - a.lon) * rad
        let h = pow(sin(dLat / 2), 2) + cos(a.lat * rad) * cos(b.lat * rad) * pow(sin(dLon / 2), 2)
        return 2 * earthRadiusM * asin(h.squareRoot())
    }

    static func formatDistance(_ meters: Double) -> String {
        meters < 1000 ? "\(Int(meters)) m" : String(format: "%.1f km", meters / 1000)
    }
}

/// One relation member as the detail screen shows it. Built on the store's thread.
struct RelationMemberRow: Hashable, Sendable {
    let member: OsmObject.Member
    let present: Bool
    let name: String?

    /// The row text; absent members say so instead of linking. With the
    /// app's import profile they are still "not in this area" (outside it),
    /// never filtered out: the import keeps every member of a kept relation.
    func label(index: Int) -> String {
        let role = member.role.isEmpty ? "(no role)" : member.role
        let target = "\(member.id.kind.label) \(member.id.id)"
        return "\(index + 1). \(role) · \(target)" + (name.map { " · \($0)" } ?? "") + (present ? "" : " · not in this area")
    }

    static func rows(_ store: OsmStore, _ relation: OsmObject.Relation) throws -> [RelationMemberRow] {
        let targets = try store.get(relation.members.map(\.id))
        return zip(relation.members, targets).map { member, target in
            RelationMemberRow(member: member, present: target != nil, name: target?.tags["name"])
        }
    }
}

extension OsmKind {
    /// "node", "way", "relation" (as the Android app's `kind.name.lowercase()`).
    var label: String {
        switch self {
        case .node: "node"
        case .way: "way"
        case .relation: "relation"
        }
    }
}

extension OpenState {
    /// Short user-facing label. Unknown is always spelled out, never shown as closed.
    var label: String {
        switch self {
        case .open: "Open now"
        case .closed: "Closed now"
        case .unknown: "Hours unknown"
        }
    }
}

extension OutdoorSeating {
    var label: String {
        switch self {
        case .yes: "Outdoor seating: yes"
        case .no: "Outdoor seating: no"
        case .unknown(let raw): raw.map { "Outdoor seating: unknown (\"\($0)\")" } ?? "Outdoor seating: unknown"
        }
    }
}

func formatBytes(_ bytes: Int64) -> String {
    if bytes >= 1_000_000 { return String(format: "%.1f MB", Double(bytes) / 1e6) }
    if bytes >= 1_000 { return String(format: "%.0f kB", Double(bytes) / 1e3) }
    return "\(bytes) B"
}

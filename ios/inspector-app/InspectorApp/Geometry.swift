import Cantino
import Foundation

/*
 * Tap-to-inspect geometry, all in the app (Cantino has no "what is here"
 * query): a bbox query around the tap gives *candidates*, and this file
 * decides which of them the tap actually hit. Port of android/inspector-app
 * Geometry.kt; same rules, same numbers.
 *
 * - Tagged nodes: distance to the node.
 * - Ways: distance to the nearest segment of the way's coordinates
 *   (OsmStore.wayCoordinates). Nil entries are vertices outside the area: no
 *   segment is drawn or measured across them.
 * - Relations: never a hit ("near (bbox)"); their geometry is not assembled.
 * - A closed way (a park) tapped in its middle is "near": there is
 *   deliberately no point-in-polygon test.
 *
 * Untagged way vertices are never returned by a bbox query, which is why the
 * way's line, not its vertices, decides a hit.
 */

/// A WGS84 point in degrees.
struct LatLon: Hashable, Sendable, CustomStringConvertible {
    let lat: Double
    let lon: Double

    var description: String { String(format: "%.5f, %.5f", lat, lon) }
}

enum Geo {
    /// Edge length of the default area, in km (a 5×5 km city centre: a full import stays small).
    static let areaSizeKm = 5.0

    private static let earthRadiusM = 6_371_000.0
    private static let metersPerDegree = earthRadiusM * Double.pi / 180
    private static let rad = Double.pi / 180

    static func center(_ bbox: Bbox) -> LatLon {
        LatLon(lat: (bbox.south + bbox.north) / 2, lon: (bbox.west + bbox.east) / 2)
    }

    static func contains(_ bbox: Bbox, _ point: LatLon) -> Bool {
        (bbox.south...bbox.north).contains(point.lat) && (bbox.west...bbox.east).contains(point.lon)
    }

    /// A square box reaching `meters` from `center` in each direction.
    static func around(_ center: LatLon, meters: Double) -> Bbox {
        let dLat = meters / metersPerDegree
        let dLon = meters / (metersPerDegree * cos(center.lat * rad))
        return Bbox(west: center.lon - dLon, south: center.lat - dLat, east: center.lon + dLon, north: center.lat + dLat)
    }

    /// Great-circle distance in metres (haversine).
    static func distanceMeters(_ a: LatLon, _ b: LatLon) -> Double {
        let dLat = (b.lat - a.lat) * rad
        let dLon = (b.lon - a.lon) * rad
        let h = pow(sin(dLat / 2), 2) + cos(a.lat * rad) * cos(b.lat * rad) * pow(sin(dLon / 2), 2)
        return 2 * earthRadiusM * asin(h.squareRoot())
    }

    static func formatDistance(_ meters: Double) -> String {
        if meters < 10 { return String(format: "%.1f m", meters) }
        if meters < 1000 { return "\(Int(meters)) m" }
        return String(format: "%.1f km", meters / 1000)
    }

    /// `point` in metres east/north of `origin` (equirectangular, fine at tap scale).
    private static func project(_ origin: LatLon, _ point: LatLon) -> (Double, Double) {
        ((point.lon - origin.lon) * metersPerDegree * cos(origin.lat * rad), (point.lat - origin.lat) * metersPerDegree)
    }

    /// Distance in metres from `p` to the segment `a`–`b`.
    static func pointToSegmentMeters(_ p: LatLon, _ a: LatLon, _ b: LatLon) -> Double {
        let (ax, ay) = project(p, a)
        let (bx, by) = project(p, b)
        let dx = bx - ax
        let dy = by - ay
        let lengthSquared = dx * dx + dy * dy
        // Parameter of the projection of p (the origin) onto the segment, clamped to it.
        let t = lengthSquared == 0 ? 0 : min(max(-(ax * dx + ay * dy) / lengthSquared, 0), 1)
        return hypot(ax + t * dx, ay + t * dy)
    }

    /// Distance in metres from `p` to a way's line, or nil when none of its
    /// vertices are in the area. Segments are measured only between two
    /// present vertices; a vertex next to a gap still counts as a point.
    static func distanceToLineMeters(_ p: LatLon, _ line: [LatLon?]) -> Double? {
        var best: Double?
        for (i, a) in line.enumerated() {
            guard let a else { continue }
            let b = i + 1 < line.count ? line[i + 1] : nil
            let d = pointToSegmentMeters(p, a, b ?? a)
            if best == nil || d < best! { best = d }
        }
        return best
    }

    /// A way's coordinates split into drawable runs at missing vertices
    /// (outside the area). A run of one vertex is kept: it is drawn as a point.
    static func segments(_ line: [LatLon?]) -> [[LatLon]] {
        var runs: [[LatLon]] = []
        var current: [LatLon] = []
        for point in line {
            if let point {
                current.append(point)
            } else {
                if !current.isEmpty { runs.append(current) }
                current = []
            }
        }
        if !current.isEmpty { runs.append(current) }
        return runs
    }
}

/// Whether a tap is on an object, or only inside its bounding box.
enum Match: Int, Hashable, Sendable {
    case hit = 0
    case near = 1

    var label: String { self == .hit ? "hit" : "near (bbox)" }
    var name: String { self == .hit ? "hit" : "near" }
}

/// One object under a tap, as the candidate sheet lists it.
struct Candidate: Hashable, Sendable, Identifiable {
    let id: OsmId
    let tags: [String: String]
    let match: Match
    /// Metres to the node or the nearest way segment; nil for relations and ways with no vertex in the area.
    let distanceMeters: Double?
}

enum HitTest {
    /// Tap radius when there is no screen to measure (debug taps, tests).
    static let defaultRadiusM = 15.0

    static func node(_ id: OsmId, _ tags: [String: String], at: LatLon, tap: LatLon, radiusMeters: Double) -> Candidate {
        let d = Geo.distanceMeters(tap, at)
        return Candidate(id: id, tags: tags, match: d <= radiusMeters ? .hit : .near, distanceMeters: d)
    }

    static func way(_ id: OsmId, _ tags: [String: String], line: [LatLon?], tap: LatLon, radiusMeters: Double) -> Candidate {
        let d = Geo.distanceToLineMeters(tap, line)
        return Candidate(id: id, tags: tags, match: d.map { $0 <= radiusMeters } == true ? .hit : .near, distanceMeters: d)
    }

    static func relation(_ id: OsmId, _ tags: [String: String]) -> Candidate {
        Candidate(id: id, tags: tags, match: .near, distanceMeters: nil)
    }

    /// Hits first, then near; nearest first (unknown distance last); then node < way < relation, then ID.
    static func order(_ candidates: [Candidate]) -> [Candidate] {
        candidates.sorted { a, b in
            if a.match != b.match { return a.match.rawValue < b.match.rawValue }
            switch (a.distanceMeters, b.distanceMeters) {
            case let (x?, y?) where x != y: return x < y
            case (.some, nil): return true
            case (nil, .some): return false
            default: break
            }
            if a.id.kind != b.id.kind { return a.id.kind.rawValue < b.id.kind.rawValue }
            return a.id.id < b.id.id
        }
    }
}

enum Labels {
    /// Keys that say what an object is, most telling first.
    private static let primary = [
        "amenity", "shop", "highway", "footway", "crossing", "barrier", "railway", "public_transport",
        "leisure", "tourism", "building", "landuse", "natural", "waterway", "boundary", "type",
    ]

    /// "highway=footway", the first telling tag, or the first tag, or "untagged".
    static func primaryTag(_ tags: [String: String]) -> String {
        guard let key = primary.first(where: { tags[$0] != nil }) ?? tags.keys.min() else { return "untagged" }
        return "\(key)=\(tags[key]!)"
    }

    static func kindId(_ id: OsmId) -> String { "\(id.kind.label) \(id.id)" }

    /// "node/123" for osm.org links and debug arguments.
    static func path(_ id: OsmId) -> String { "\(id.kind.label)/\(id.id)" }

    /// Parses "way/123" (debug launch arguments).
    static func parsePath(_ value: String) -> OsmId? {
        guard let slash = value.firstIndex(of: "/"),
              let kind = OsmKind.allCases.first(where: { $0.label == value[..<slash].lowercased() }),
              let id = Int64(value[value.index(after: slash)...]), id > 0 else { return nil }
        return OsmId(kind, id)
    }

    /// The object's page on openstreetmap.org (needs a network connection).
    static func osmOrgUrl(_ id: OsmId) -> String { "https://www.openstreetmap.org/\(path(id))" }
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

import Cantino
import Foundation
import Testing
@testable import InspectorApp

/// Hit testing, distances and candidate ordering. Same cases as android/inspector-app GeometryTest.
struct GeometryTests {
    let tap = LatLon(lat: 40.0, lon: -111.0)
    // At latitude 40, 0.0001° of latitude ≈ 11.12 m and 0.0001° of longitude ≈ 8.52 m.
    let east10m = LatLon(lat: 40.0, lon: -111.0 + 10 / 85_175.6)
    let north10m = LatLon(lat: 40.0 + 10 / 111_194.9, lon: -111.0)

    @Test func pointToSegmentDistances() {
        let a = LatLon(lat: 40.0 + 20 / 111_194.9, lon: -111.0 - 0.001)
        let b = LatLon(lat: 40.0 + 20 / 111_194.9, lon: -111.0 + 0.001)
        #expect(abs(Geo.pointToSegmentMeters(tap, a, b) - 20) < 0.05)
        #expect(abs(Geo.pointToSegmentMeters(tap, east10m, LatLon(lat: 40.0, lon: -110.99)) - 10) < 0.05)
        #expect(abs(Geo.pointToSegmentMeters(tap, north10m, north10m) - 10) < 0.05)
        #expect(abs(Geo.distanceMeters(tap, east10m) - Geo.pointToSegmentMeters(tap, east10m, east10m)) < 0.01)
    }

    @Test func noSegmentIsMeasuredAcrossAMissingVertex() throws {
        let west = LatLon(lat: 40.0, lon: -111.001)
        let east = LatLon(lat: 40.0, lon: -110.999)
        #expect(abs(try #require(Geo.distanceToLineMeters(tap, [west, east]))) < 1e-6)
        let gapped = try #require(Geo.distanceToLineMeters(tap, [west, nil, east]))
        #expect(abs(gapped - Geo.distanceMeters(tap, west)) < 0.05) // only the vertices count
        #expect(Geo.distanceToLineMeters(tap, [nil, nil]) == nil)
        #expect(Geo.distanceToLineMeters(tap, []) == nil)
    }

    @Test func segmentsSplitAtMissingVertices() {
        let p = (1...5).map { LatLon(lat: 40.0, lon: -111.0 + Double($0) * 0.001) }
        #expect(Geo.segments(p) == [p])
        #expect(Geo.segments([p[0], p[1], nil, p[3], p[4]]) == [[p[0], p[1]], [p[3], p[4]]])
        #expect(Geo.segments([nil, p[1], nil, nil, p[3], nil]) == [[p[1]], [p[3]]])
        #expect(Geo.segments([nil, nil]) == [])
    }

    @Test func hitOrNearByKind() throws {
        let node = OsmId(.node, 1)
        #expect(HitTest.node(node, [:], at: east10m, tap: tap, radiusMeters: 15).match == .hit)
        #expect(HitTest.node(node, [:], at: east10m, tap: tap, radiusMeters: 5).match == .near)

        let way = OsmId(.way, 2)
        // The middle of a long segment, far from both (untagged) vertices: a hit.
        let sidewalk = [LatLon(lat: 40.0, lon: -111.002), LatLon(lat: 40.0, lon: -110.998)]
        let mid = HitTest.way(way, [:], line: sidewalk, tap: tap, radiusMeters: 15)
        #expect(mid.match == .hit)
        #expect(abs(try #require(mid.distanceMeters)) < 1e-6)
        // Inside a large closed way (a park): near only, no point-in-polygon test.
        let park = [LatLon(lat: 39.99, lon: -111.01), LatLon(lat: 39.99, lon: -110.99), LatLon(lat: 40.01, lon: -110.99),
                    LatLon(lat: 40.01, lon: -111.01), LatLon(lat: 39.99, lon: -111.01)]
        let inside = HitTest.way(way, [:], line: park, tap: tap, radiusMeters: 15)
        #expect(inside.match == .near)
        #expect(try #require(inside.distanceMeters) > 800)
        // No vertex in the area: near, distance unknown.
        #expect(HitTest.way(way, [:], line: [nil], tap: tap, radiusMeters: 15) == Candidate(id: way, tags: [:], match: .near, distanceMeters: nil))

        #expect(HitTest.relation(OsmId(.relation, 3), [:]) == Candidate(id: OsmId(.relation, 3), tags: [:], match: .near, distanceMeters: nil))
    }

    @Test func candidatesOrderHitsFirstThenNearestThenKindAndId() {
        func c(_ kind: OsmKind, _ id: Int64, _ match: Match, _ d: Double?) -> Candidate {
            Candidate(id: OsmId(kind, id), tags: [:], match: match, distanceMeters: d)
        }
        let ordered = HitTest.order([
            c(.relation, 1, .near, nil),
            c(.way, 9, .near, 40),
            c(.way, 5, .hit, 0),
            c(.node, 7, .hit, 0),
            c(.node, 3, .hit, 0),
            c(.way, 4, .hit, 2.5),
            c(.way, 8, .near, nil),
        ])
        #expect(ordered.map { Labels.kindId($0.id) } == ["node 3", "node 7", "way 5", "way 4", "way 9", "way 8", "relation 1"])
    }

    @Test func aroundIsASquareOfTheRadius() {
        let box = Geo.around(tap, meters: 15)
        #expect(abs(Geo.distanceMeters(tap, LatLon(lat: box.north, lon: tap.lon)) - 15) < 0.01)
        #expect(abs(Geo.distanceMeters(tap, LatLon(lat: tap.lat, lon: box.east)) - 15) < 0.01)
        #expect(Geo.contains(box, tap))
    }

    @Test func labelsAndPaths() {
        #expect(Labels.primaryTag(["crossing": "marked", "highway": "crossing"]) == "highway=crossing")
        #expect(Labels.primaryTag(["name": "X", "amenity": "cafe", "building": "yes"]) == "amenity=cafe")
        #expect(Labels.primaryTag(["name": "X", "colour": "red"]) == "colour=red")
        #expect(Labels.primaryTag([:]) == "untagged")
        #expect(Labels.parsePath("way/123") == OsmId(.way, 123))
        #expect(Labels.parsePath("way/-1") == nil)
        #expect(Labels.parsePath("area/1") == nil)
        #expect(Labels.osmOrgUrl(OsmId(.node, 42)) == "https://www.openstreetmap.org/node/42")
        #expect(Geo.formatDistance(4.24) == "4.2 m")
        #expect(Geo.formatDistance(42.9) == "42 m")
        #expect(Geo.formatDistance(1500) == "1.5 km")
    }
}

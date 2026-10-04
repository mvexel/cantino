import Cantino
import Foundation
import Testing
@testable import InspectorApp

/// The app logic on a real store, imported from the fixture Android's
/// instrumented test uses (android/inspector-app/src/androidTest/assets/inspector.osm,
/// read in place: the simulator shares the host's file system).
/// Same cases as android/inspector-app InspectTest.
struct InspectTests {
    static let fixture = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent() // InspectorAppTests
        .deletingLastPathComponent() // inspector-app
        .deletingLastPathComponent() // ios
        .deletingLastPathComponent() // repository root
        .appendingPathComponent("android/inspector-app/src/androidTest/assets/inspector.osm")

    /// Imports the fixture and runs `body` on a store opened on this thread
    /// (a store belongs to the thread that opened it; the tests are synchronous).
    private func withFixture(_ body: (OsmStore) throws -> Void) throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("inspector-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let area = directory.appendingPathComponent("area.sqlite")
        try OsmStore.importArea(input: Self.fixture, destination: area)
        let store = try OsmStore.open(area)
        defer { try? store.close() }
        try body(store)
    }

    private func ids(_ candidates: [Candidate]) -> [String] {
        candidates.map { "\($0.match.name) \(Labels.kindId($0.id))" }
    }

    @Test func tapOnACrossingHitsTheNodeAndBothWays() throws {
        try withFixture { (store: OsmStore) throws in
            let result = try Inspect.tap(store, at: LatLon(lat: 40.0, lon: -111.0), radiusMeters: 15)
            #expect(ids(result.candidates) == ["hit node 1", "hit way 10", "hit way 12"])
            #expect(result.candidates.allSatisfy { $0.distanceMeters == 0 })
            #expect(!result.truncated)
        }
    }

    @Test func tapMidSidewalkFindsTheWayNotAVertex() throws {
        try withFixture { (store: OsmStore) throws in
            // No vertex within 150 m: untagged vertices are never candidates, the way's line is.
            let result = try Inspect.tap(store, at: LatLon(lat: 40.0020, lon: -111.0000), radiusMeters: 15)
            #expect(ids(result.candidates) == ["hit way 11", "near relation 30"])
            #expect(abs(try #require(result.candidates[0].distanceMeters)) < 0.01)
        }
    }

    @Test func tapInsideAParkIsNearOnly() throws {
        try withFixture { (store: OsmStore) throws in
            let result = try Inspect.tap(store, at: LatLon(lat: 40.0060, lon: -111.0000), radiusMeters: 15)
            #expect(ids(result.candidates) == ["near way 20", "near relation 30"])
            #expect(abs(try #require(result.candidates[0].distanceMeters) - 255.5) < 1) // to the park's east and west edges
        }
    }

    @Test func waysUsingANode() throws {
        try withFixture { (store: OsmStore) throws in
            let crossing = try #require(try store.get(OsmId(.node, 1))?.node)
            #expect(try Inspect.parentWays(store, crossing).map(\.id.id) == [10, 12])
            let kerb = try #require(try store.get(OsmId(.node, 5))?.node)
            #expect(try Inspect.parentWays(store, kerb).map(\.id.id) == [12])
            let cafe = try #require(try store.get(OsmId(.node, 3))?.node)
            #expect(try Inspect.parentWays(store, cafe).map(\.id.id) == [])
        }
    }

    @Test func missingReferencesAndBrokenGeometry() throws {
        try withFixture { (store: OsmStore) throws in
            let way = try Inspect.detail(store, OsmId(.way, 40))
            #expect(way.missingReferences == 2)
            #expect(way.references == 6)
            #expect(way.shape.lines.map(\.count) == [2, 2])
            #expect(way.shape.lines[0][1] == LatLon(lat: 40.01, lon: -111.003))
            #expect(way.nodeRefs.map { $0.location != nil } == [true, true, false, false, true, true])

            let sidewalk = try Inspect.detail(store, OsmId(.way, 10))
            #expect(sidewalk.nodeRefs.map(\.tagged) == [false, false, true, false])
            #expect(sidewalk.missingReferences == 0)

            let relation = try Inspect.detail(store, OsmId(.relation, 30))
            #expect(relation.missingReferences == 1)
            #expect(relation.references == 3)
            #expect(relation.members.enumerated().map { $1.text(index: $0) }
                == ["1. outer · way 20 · Test Park", "2. inner · way 998 · not in this area", "3. (no role) · node 4 · amenity=bench"])
            #expect(relation.shape.lines.count == 1)
            #expect(relation.shape.points.count == 1)

            let node = try Inspect.detail(store, OsmId(.node, 1))
            #expect(node.parentWays.map(\.id.id) == [10, 12])
            #expect(try Inspect.detail(store, OsmId(.node, 999)).object == nil)
        }
    }

    @Test func checksCountOnTheFixture() throws {
        try withFixture { (store: OsmStore) throws in
            func count(_ query: String, bbox: Bbox? = nil) throws -> KindCounts {
                try Queries.count(store, try QueryBar.parse(query), bbox: bbox)
            }
            #expect(try count("amenity=* !opening_hours") == KindCounts(nodes: 1))
            #expect(try count("highway=crossing !crossing") == KindCounts(nodes: 1))
            #expect(try count("highway=crossing crossing=*") == KindCounts(nodes: 1))
            #expect(try count("footway=sidewalk !surface") == KindCounts(ways: 1))
            #expect(try count("highway=footway footway=sidewalk") == KindCounts(ways: 2))
            #expect(try count("barrier=kerb !kerb") == KindCounts(nodes: 1))
            #expect(try count("highway=steps") == KindCounts(ways: 1))
            #expect(try count("amenity=Cafe") == KindCounts()) // exact, case-sensitive
            // A lone !key drives nothing: Cantino rejects it without a bbox and accepts it with one.
            #expect {
                _ = try count("!opening_hours")
            } throws: { error in
                if case .invalidArgument(let message) = error as? CantinoError { !message.isEmpty } else { false }
            }
            #expect(try count("!name", bbox: Geo.around(LatLon(lat: 40.0, lon: -111.0), meters: 15)) == KindCounts(nodes: 1, ways: 2))
        }
    }

    @Test func pagesWithAfter() throws {
        try withFixture { (store: OsmStore) throws in
            let filters = try QueryBar.parse("highway=*")
            var seen: [OsmId] = []
            var after: OsmId?
            while true {
                let page = try Queries.page(store, filters, bbox: nil, after: after, limit: 2)
                seen += page.map(\.id)
                guard page.count == 2, let last = page.last else { break }
                after = last.id
            }
            #expect(seen.map(Labels.kindId) == ["node 1", "node 2", "way 10", "way 11", "way 12", "way 40", "way 50"])
        }
    }
}

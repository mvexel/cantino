import Foundation
import Testing
@testable import Cantino

/// Port of the Kotlin instrumented OsmStoreTest
/// (android/cantino/src/androidTest/.../OsmStoreTest.kt): the fixture from
/// tests/fixtures goes through import, open, get, query, error recovery and
/// close via the C ABI.
///
/// Threading: Kotlin runs each test body on a single-thread executor. Here
/// each test body is synchronous, and a synchronous Swift function cannot
/// change threads mid-way, so the thread that runs the test is the store's
/// owner for the whole body. `storeRejectsCallsFromAnotherThread` uses a
/// second, explicit thread.
@Suite struct OsmStoreTests {
    @Test func importReportCountsTheFixture() throws {
        let (_, report) = try Fixtures.importFixture()
        #expect(report.counts == ObjectCounts(nodes: 4, ways: 2, relations: 1))
        #expect(report.databaseBytes > 0)
    }

    @Test func importGetQueryCloseOnOneThread() throws {
        let store = try OsmStore.open(Fixtures.importFixture().area)
        defer { try? store.close() }

        let node1 = try #require(try store.get(node(1))?.node)
        let metadata = try #require(node1.metadata)
        #expect(metadata == ObjectMetadata(version: 3, timestampSeconds: metadata.timestampSeconds, changeset: 123, uid: 7, user: "Mapper"))
        #expect(node1.latE7 == 400_000_000)
        #expect(node1.lon == -111.0)
        #expect(try store.get(node(99)) == nil)

        let cafes = try store.query(Query(tags: [.equals("amenity", "cafe")]))
        #expect(cafes.map(\.id) == [node(2)])
        // Non-ASCII survives the C strings (UTF-8) in both directions.
        #expect(cafes.first?.tags["name"] == "Café Test")
        #expect(try store.query(Query(tags: [.equals("name", "Café Test")])).count == 1)

        let way1 = try #require(try store.get(way(1))?.way)
        #expect(way1.nodeIds == [1, 2, 1]) // order and repeats preserved
        let relation1 = try #require(try store.get(relation(1))?.relation)
        #expect(relation1.members == [
            OsmObject.Member(id: way(1), role: "outer"),
            OsmObject.Member(id: node(99), role: "label"),
        ])
    }

    @Test func untaggedNodesHaveNoMetadataByDefault() throws {
        let directory = try Fixtures.scratchDirectory()
        let area = directory.appendingPathComponent("area.sqlite")
        // URL overloads (the other tests use paths), as Kotlin's File overloads.
        try OsmStore.importArea(input: Fixtures.snapshotOsm, destination: area)
        let store = try OsmStore.open(area)
        defer { try? store.close() }
        let vertex = try #require(try store.get(node(1))?.node)
        #expect(vertex.metadata == nil)
        #expect(vertex.locationVersion == 3)
        #expect(try store.get(node(2))?.metadata?.changeset == 124)
    }

    @Test func paginationAndBbox() throws {
        let store = try OsmStore.open(Fixtures.importFixture().area)
        defer { try? store.close() }
        var highways = Query(tags: [.exists("highway")], limit: 1)
        let first = try store.query(highways)
        highways.after = first.last?.id
        let second = try store.query(highways)
        #expect((first + second).map(\.id) == [way(1), way(2)])
        highways.after = second.last?.id
        #expect(try store.query(highways).isEmpty)

        let box = Bbox(west: -111.0015, south: 40.0005, east: -111.0005, north: 40.0015)
        let inBox = try store.query(Query(bbox: box)).map(\.id)
        #expect(inBox.contains(node(2)))
        #expect(!inBox.contains(node(1)))
        // Way 2 crosses this box with both of its nodes outside it.
        let crossing = Bbox(west: -111.002, south: 39.999, east: -110.998, north: 40.002)
        #expect(try store.query(Query(bbox: crossing)).map(\.id).contains(way(2)))
    }

    @Test func wayCoordinatesKeepOrderAndMissingNodes() throws {
        let store = try OsmStore.open(Fixtures.importFixture().area)
        defer { try? store.close() }
        // Way 1 is nodes 1, 2, 1: order and the closing repeat survive.
        let node1 = Coordinate(latE7: 400_000_000, lonE7: -1_110_000_000)
        let node2 = Coordinate(latE7: 400_010_000, lonE7: -1_110_010_000)
        #expect(try store.wayCoordinates(1) == [node1, node2, node1])
        let second = try #require(try store.wayCoordinates(1)?[1])
        #expect(abs(second.lat - 40.001) < 1e-9)
        #expect(try store.wayCoordinates(42) == nil) // not in the area

    }

    @Test func batchGetKeepsOrderAndReportsMissing() throws {
        let store = try OsmStore.open(Fixtures.importFixture().area)
        defer { try? store.close() }
        let ids = [way(1), node(99), node(2), way(1)]
        let objects = try store.get(ids)
        #expect(objects.count == ids.count)
        #expect(objects[0] == (try store.get(ids[0])))
        #expect(objects[1] == nil)
        #expect(objects[2]?.tags["name"] == "Café Test")
        #expect(objects[0] == objects[3])
        #expect(try store.get([OsmId]()).isEmpty)
        let tooMany = Array(repeating: node(1), count: OsmStore.maxBatch + 1)
        #expect(category(caught { try store.get(tooMany) }) == "INVALID_ARGUMENT")
        #expect(try store.get(Array(tooMany.dropFirst())).compactMap { $0 }.count == OsmStore.maxBatch)
    }

    @Test func notExistsFiltersButNeedsADriver() throws {
        let store = try OsmStore.open(Fixtures.importFixture().area)
        defer { try? store.close() }
        let unnamedHighways = Query(tags: [.exists("highway"), .notExists("name")])
        #expect(try store.query(unnamedHighways).map(\.id) == [way(1), way(2)])
        let cafesWithoutSeating = Query(tags: [.equals("amenity", "cafe"), .notExists("outdoor_seating")])
        #expect(try store.query(cafesWithoutSeating).map(\.id) == [node(2)])
        #expect(try store.query(Query(tags: [.exists("amenity"), .notExists("name")])).isEmpty)
        let error = caught { try store.query(Query(tags: [.notExists("name")])) }
        #expect(category(error) == "INVALID_ARGUMENT")
        #expect((error as? CantinoError)?.message.contains("NotExists") == true)
        // A bbox drives, so NotExists alongside one is fine.
        let box = Bbox(west: -111.002, south: 39.999, east: -110.998, north: 40.002)
        #expect(try store.query(Query(tags: [.notExists("name")], bbox: box)).map(\.id).contains(way(2)))
    }

    @Test func invalidQueryFailsAndStoreStaysUsable() throws {
        let store = try OsmStore.open(Fixtures.importFixture().area)
        defer { try? store.close() }
        #expect(category(caught { try store.query(Query(bbox: Bbox(west: 10, south: 0, east: -10, north: 1))) }) == "INVALID_ARGUMENT")
        #expect(category(caught { try store.query(Query(limit: 0)) }) == "INVALID_ARGUMENT")
        #expect(category(caught { try store.get(node(0)) }) == "INVALID_ARGUMENT")
        // Swift-only: JSON cannot carry NaN, the adapter rejects it up front.
        #expect(category(caught { try store.query(Query(bbox: Bbox(west: .nan, south: 0, east: 1, north: 1))) }) == "INVALID_ARGUMENT")
        #expect(try store.get(way(1))?.id == way(1))
    }

    @Test func storeRejectsCallsFromAnotherThread() throws {
        // `nonisolated(unsafe)`: deliberately smuggling the non-Sendable
        // store to another thread, which is what the runtime check is for.
        nonisolated(unsafe) let store = try OsmStore.open(Fixtures.importFixture().area)
        let error = try onOtherThread { caught { try store.get(node(1)) }.map { "\($0)" } }
        #expect(error?.contains("different thread") == true)
        let typed = try onOtherThread { category(caught { try store.get(node(1)) }) }
        #expect(typed == "WRONG_THREAD")
        // Closing from the wrong thread fails too and leaves the store open...
        #expect(try onOtherThread { category(caught { try store.close() }) } == "WRONG_THREAD")
        #expect(try store.get(node(1)) != nil)
        // ...and the owner thread can still close it.
        try store.close()
    }

    @Test func openFailuresAreTypedByCause() throws {
        let directory = try Fixtures.scratchDirectory("osm-open")
        // A path that does not exist: the environment (io).
        let missing = caught { try OsmStore.open(directory.appendingPathComponent("missing.sqlite")) }
        #expect(category(missing) == "IO")
        #expect((missing as? CantinoError)?.message.isEmpty == false)
        // A file that exists but is no area (the OSM XML input): invalidFile.
        #expect(category(caught { try OsmStore.open(Fixtures.snapshotOsm) }) == "INVALID_FILE")
        // An empty file: SQLite reads it as an empty database without the
        // Cantino application id, also invalidFile.
        let empty = directory.appendingPathComponent("empty.sqlite")
        try Data().write(to: empty)
        #expect(category(caught { try OsmStore.open(empty) }) == "INVALID_FILE")
        // A corrupt import input is invalidFile, a missing one io.
        let garbage = directory.appendingPathComponent("garbage.pbf")
        try Data(repeating: 0x5a, count: 64).write(to: garbage)
        let area = directory.appendingPathComponent("area.sqlite")
        #expect(category(caught { try OsmStore.importArea(input: garbage, destination: area) }) == "INVALID_FILE")
        #expect(category(caught { try OsmStore.importArea(input: directory.appendingPathComponent("nope.pbf"), destination: area) }) == "IO")
        // (Kotlin also checks PmtilesInfo.read here; the basemap API is not
        // part of this Swift slice.)
        // Every domain failure is a CantinoError (one catch for all).
        #expect(caught { try OsmStore.open(empty) } is CantinoError)
        // Swift-only: a non-file URL is rejected before the core.
        #expect(category(caught { try OsmStore.open(URL(string: "https://example.com/a.sqlite")!) }) == "INVALID_ARGUMENT")
    }

    @Test func closedStoreFailsFast() throws {
        let store = try OsmStore.open(Fixtures.importFixture().area)
        try store.close()
        try store.close() // idempotent
        #expect(throws: CantinoStateError.closed("store is closed")) { try store.get(node(1)) }
        #expect(category(caught { try store.get([node(1)]) }) == "CLOSED")
    }
}

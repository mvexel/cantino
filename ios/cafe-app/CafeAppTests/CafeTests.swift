import Cantino
import Foundation
import Testing
@testable import CafeApp

/// The café logic of the example on real stores: ports of the Android café
/// app's RelationCafeTest and CafeStoreTest (instrumented there), plus the
/// paging and outdoor-seating rules. Fixtures are the repository's
/// tests/fixtures, read in place (the simulator shares the host's file system).
enum Fixtures {
    static let directory = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent() // CafeAppTests
        .deletingLastPathComponent() // cafe-app
        .deletingLastPathComponent() // ios
        .deletingLastPathComponent() // repository root
        .appendingPathComponent("tests/fixtures")

    static func scratch(_ prefix: String) throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("\(prefix)-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }
}

struct CafeLoaderTests {
    @Test func relationCafeQueryDetailRowsAndPresentMemberResolve() throws {
        let directory = try Fixtures.scratch("cafe-relation")
        defer { try? FileManager.default.removeItem(at: directory) }
        let area = directory.appendingPathComponent("area.sqlite")
        // Imported as the app imports downloads (CafeProfile): the café
        // relation keeps its members by reference closure.
        let report = try OsmStore.importArea(input: Fixtures.directory.appendingPathComponent("cafe-relation.osm"),
                                             destination: area, options: CafeProfile.importOptions)
        #expect(report.profile == CafeProfile.poi)

        let store = try OsmStore.open(area)
        defer { try? store.close() }
        // The list path: the relation café is found and placed on the map.
        let cafes = try CafeLoader.load(store, bbox: Bbox(west: -111.01, south: 39.99, east: -110.99, north: 40.01))
        #expect(cafes.map(\.id) == [OsmId(.relation, 301)])
        #expect(cafes.first?.name == "Relation Café")
        // First usable member (way 201): mean of its distinct vertices.
        let location = try #require(cafes.first?.location)
        #expect(abs(location.lat - 40.0005) < 1e-9 && abs(location.lon - -111.0005) < 1e-9)

        // The detail path: member rows as DetailView renders them.
        let relation = try #require(try store.get(OsmId(.relation, 301))?.relation)
        let rows = try RelationMemberRow.rows(store, relation)
        #expect(rows.map(\.member.id) == [OsmId(.way, 201), OsmId(.way, 999)])
        #expect(rows.map(\.member.role) == ["outer", "inner"])
        #expect(rows[0].label(index: 0) == "1. outer · way 201")
        #expect(rows[1].label(index: 1) == "2. inner · way 999 · not in this area")

        // A present member resolves to its object (the row's link target).
        #expect(rows[0].present)
        #expect(!rows[1].present)
        #expect(try store.get(rows[0].member.id)?.way?.nodeIds == [101, 102, 103, 104, 101])

        // The way's node list as the detail screen resolves it (one batch get).
        let detail = try DetailModel.load(store, OsmId(.way, 201))
        #expect(detail.nodeRefs.map(\.id) == [101, 102, 103, 104, 101])
        #expect(detail.nodeRefs.allSatisfy { $0.location != nil })
    }

    /// The app's import profile is applied: POIs stay, everything else is gone.
    @Test func poiProfileKeepsCafesAndDropsStreets() throws {
        let directory = try Fixtures.scratch("cafe-profile")
        defer { try? FileManager.default.removeItem(at: directory) }
        let area = directory.appendingPathComponent("area.sqlite")
        // The options AreaManager gets from CafeProfile.areaConfig.
        #expect(CafeProfile.areaConfig.importOptions == CafeProfile.importOptions)
        let report = try OsmStore.importArea(input: Fixtures.directory.appendingPathComponent("snapshot.osm"),
                                             destination: area, options: CafeProfile.importOptions)

        // The report records the app's profile; the map's status line reads it.
        #expect(report.profile == CafeProfile.poi)
        #expect(CafeProfile.label(report.profile) == "points of interest only")
        // Only the café node is kept: no way or relation has a POI key.
        #expect(report.counts.nodes == 1 && report.counts.ways == 0 && report.counts.relations == 0)

        let store = try OsmStore.open(area)
        defer { try? store.close() }
        let cafes = try CafeLoader.load(store, bbox: Bbox(west: -111.1, south: 39.8, east: -110.9, north: 40.2))
        #expect(cafes.map(\.id) == [OsmId(.node, 2)])
        // highway=path and its untagged nodes: filtered out.
        #expect(try store.get(OsmId(.way, 2)) == nil)
        #expect(try store.get(OsmId(.node, 3)) == nil)
        // highway=service, although it uses the café node.
        #expect(try store.get(OsmId(.way, 1)) == nil)
        // The detail screen names both causes for a missing object.
        #expect(try DetailModel.load(store, OsmId(.way, 2), profile: report.profile).object == nil)
        #expect(CafeProfile.notFoundText(report.profile).hasSuffix("or the import profile (points of interest only) did not keep it."))
        #expect(CafeProfile.notFoundText(nil).hasPrefix("Not in this area or filtered out: "))
        #expect(CafeProfile.notFoundText(nil).hasSuffix("or the import profile did not keep it."))
    }

    @Test func poiProfileHasTheEightDocumentedKeysOnAllKinds() {
        #expect(CafeProfile.poi.keep.map(\.key)
            == ["amenity", "shop", "tourism", "leisure", "craft", "office", "healthcare", "historic"])
        #expect(CafeProfile.poi.keep.allSatisfy { $0.kinds == Set(OsmKind.allCases) && $0.values == nil })
        #expect(CafeProfile.label(nil) == nil)
        #expect(CafeProfile.label(ImportProfile(keep: [KeepRule(kinds: [.way], key: "highway")])) == "filtered: highway")
    }

    /// More cafés than one page: every one is found once, in ID order.
    @Test func pagesThroughMoreThanOnePage() throws {
        let directory = try Fixtures.scratch("cafe-paging")
        defer { try? FileManager.default.removeItem(at: directory) }
        let count = CafeLoader.pageSize * 2 + 1
        var xml = "<osm version=\"0.6\" generator=\"cantino-test\">\n"
        for i in 1...count {
            let lat = 40.0 + Double(i % 100) * 0.0001
            let lon = -111.0 + Double(i / 100) * 0.0001
            xml += "<node id=\"\(i)\" lat=\"\(lat)\" lon=\"\(lon)\"><tag k=\"amenity\" v=\"cafe\"/></node>\n"
        }
        xml += "<node id=\"\(count + 1)\" lat=\"40.0\" lon=\"-111.0\"><tag k=\"amenity\" v=\"bar\"/></node>\n</osm>\n"
        let input = directory.appendingPathComponent("many.osm")
        try xml.write(to: input, atomically: true, encoding: .utf8)
        let area = directory.appendingPathComponent("area.sqlite")
        try OsmStore.importArea(input: input, destination: area)

        let store = try OsmStore.open(area)
        defer { try? store.close() }
        let cafes = try CafeLoader.load(store, bbox: Bbox(west: -111.1, south: 39.9, east: -110.9, north: 40.1))
        #expect(cafes.map(\.id.id) == Array(1...Int64(count)))
        #expect(cafes.allSatisfy { $0.location != nil && $0.name == "Unnamed café" })
    }

    @Test func outdoorSeatingKeepsUnknownSeparate() {
        #expect(OutdoorSeating.of(["outdoor_seating": "yes"]) == .yes)
        #expect(OutdoorSeating.of(["outdoor_seating": " No "]) == .no)
        #expect(OutdoorSeating.of(["outdoor_seating": "sidewalk"]) == .yes)
        #expect(OutdoorSeating.of(["outdoor_seating": "maybe"]) == .unknown(raw: "maybe"))
        #expect(OutdoorSeating.of([:]) == .unknown(raw: nil))
    }

    @Test func filtersNeverFoldUnknownIntoOpenOrClosed() {
        #expect(MapModel.matches(.open, .unknown(reason: "x")) == false)
        #expect(MapModel.matches(.closed, .unknown(reason: "x")) == false)
        #expect(MapModel.matches(.unknown, .unknown(reason: "x")))
        #expect(MapModel.matches(.yes, .unknown(raw: nil)) == false)
        #expect(MapModel.matches(.unknown, .unknown(raw: "maybe")))
    }

    @Test func latLonInputParsing() {
        #expect(AppModel.parseLatLon("40.7608,-111.8910") == LatLon(lat: 40.7608, lon: -111.8910))
        #expect(AppModel.parseLatLon(" 40.7608 , -111.8910 ") == LatLon(lat: 40.7608, lon: -111.8910))
        #expect(AppModel.parseLatLon("40.7608") == nil)
        #expect(AppModel.parseLatLon("91,0") == nil)
        #expect(AppModel.parseLatLon("a,b") == nil)
    }

    @Test func debugLocationIsStickyAndClearable() throws {
        let defaults = try #require(UserDefaults(suiteName: "debug-location-\(UUID().uuidString)"))
        #expect(DebugLocation.read(arguments: [], defaults: defaults) == nil)
        #expect(DebugLocation.read(arguments: ["app", "-lat", "40.7608", "-lon", "-111.8910"], defaults: defaults)
            == LatLon(lat: 40.7608, lon: -111.8910))
        #expect(DebugLocation.read(arguments: ["app"], defaults: defaults) == LatLon(lat: 40.7608, lon: -111.8910))
        #expect(DebugLocation.read(arguments: ["app", "-clear_debug_location", "YES"], defaults: defaults) == nil)
    }
}

/// Port of CafeStoreTest: a refresh must not close a store that a caller has
/// selected but not yet read from; the next caller sees the new area.
struct CafeStoreTests {
    @Test func refreshCannotCloseASelectedStoreBeforeItsRead() async throws {
        let root = try Fixtures.scratch("cafe-refresh")
        defer { try? FileManager.default.removeItem(at: root) }
        let areas = root.appendingPathComponent("cantino-areas")
        try FileManager.default.createDirectory(at: areas, withIntermediateDirectories: true)

        @Sendable func publish(_ fixture: String) throws {
            let output = areas.appendingPathComponent("\(CafeStore.areaId).sqlite")
            let report = try OsmStore.importArea(input: Fixtures.directory.appendingPathComponent(fixture), destination: output)
            let sidecar: [String: Any] = [
                "bbox": ["west": -112, "south": 39, "east": -110, "north": 41],
                "name": "refresh test",
                "snapshot_timestamp": NSNull(),
                "imported_at_millis": Int64(Date().timeIntervalSince1970 * 1000),
                "work_id": UUID().uuidString.lowercased(),
                "basemap": NSNull(),
                "report": [
                    "database_bytes": report.databaseBytes,
                    "profile": NSNull(),
                    "counts": ["nodes": report.counts.nodes, "ways": report.counts.ways, "relations": report.counts.relations],
                ],
            ]
            try JSONSerialization.data(withJSONObject: sidecar)
                .write(to: areas.appendingPathComponent("\(CafeStore.areaId).json"))
        }

        try publish("snapshot.osm.pbf")
        let cafeStore = CafeStore(directory: root)
        let selected = Signal()
        let resumeRead = Signal()
        await cafeStore.setBeforeRead {
            if await selected.fire() { await resumeRead.wait() } // only the first reader pauses
        }

        let first = Task { try await cafeStore.withStore { store, _ in try store.get(OsmId(.node, 10)) != nil } }
        await selected.wait() // old store chosen, native read not yet submitted
        try publish("profile.osm.pbf")
        let secondDone = Signal()
        let second = Task {
            let found = try await cafeStore.withStore { store, _ in try store.get(OsmId(.node, 10)) != nil }
            await secondDone.fire()
            return found
        }
        // The refresh must wait for the reader that already selected the old store.
        try await Task.sleep(for: .milliseconds(250))
        #expect(await secondDone.hasFired == false)
        await resumeRead.fire()
        #expect(try await first.value == false)
        #expect(try await second.value == true)

        // A missing published area closes the cached store.
        try FileManager.default.removeItem(at: areas)
        await #expect(throws: CafeStore.NoAreaError.self) { try await cafeStore.withStore { _, _ in () } }
    }
}

/// A one-shot async event.
actor Signal {
    private var fired = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    /// Fires the event; returns false if it had already fired.
    @discardableResult
    func fire() -> Bool {
        guard !fired else { return false }
        fired = true
        waiters.forEach { $0.resume() }
        waiters = []
        return true
    }

    var hasFired: Bool { fired }

    func wait() async {
        if fired { return }
        await withCheckedContinuation { waiters.append($0) }
    }
}


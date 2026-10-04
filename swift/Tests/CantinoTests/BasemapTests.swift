import Foundation
import Testing
@testable import Cantino

/// PmtilesInfo and the internal extract wrapper beyond what the parity
/// corpus pins (which covers their outputs call by call).
@Suite struct BasemapTests {
    static let fixture = Fixtures.repoRoot.appendingPathComponent("tests/fixtures/basemap/slc-nw-z12-15.pmtiles")

    @Test func pmtilesInfoReadsEveryHeaderField() throws {
        let info = try PmtilesInfo.read(Self.fixture)
        #expect(info.specVersion == 3)
        #expect((info.minZoom, info.maxZoom) == (12, 15))
        #expect(info.bounds == Bbox(west: -112.09, south: 40.83, east: -112.06, north: 40.845))
        #expect(info.center == PmtilesInfo.Center(lon: -112.075, lat: 40.8375, zoom: 12))
        #expect(info.tileType == 1 && info.tileCompression == 2 && info.clustered)
        #expect(info.fileBytes == 49752)
        #expect(category(caught { try PmtilesInfo.read(URL(string: "https://example.com/a.pmtiles")!) }) == "INVALID_ARGUMENT")
        #expect(category(caught { try PmtilesInfo.read(Fixtures.snapshotOsm) }) == "INVALID_FILE")
    }

    @Test func failedIntoAssemblerConsumesThePlan() throws {
        let plan = try BasemapPlan(bbox: Bbox(west: -112.09, south: 40.83, east: -112.06, north: 40.845))
        // Not ready (nothing fed): into_assembler fails and consumes the plan.
        let staging = try Fixtures.scratchDirectory("osm-basemap").appendingPathComponent("x.part").path
        #expect(caught { try plan.intoAssembler(staging: staging) } is CantinoError)
        #expect(category(caught { try plan.firstRequest() }) == "CLOSED")
        try plan.free() // idempotent on a consumed plan
    }

    @Test func planIsConfinedToItsThread() throws {
        nonisolated(unsafe) let plan = try BasemapPlan(bbox: Bbox(west: -112.09, south: 40.83, east: -112.06, north: 40.845))
        #expect(try onOtherThread { category(caught { try plan.firstRequest() }) } == "WRONG_THREAD")
        #expect(try onOtherThread { category(caught { try plan.free() }) } == "WRONG_THREAD")
        #expect(try plan.firstRequest() == ByteRange(id: 0, offset: 0, length: 16384))
        try plan.free()
    }
}

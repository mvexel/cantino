import Foundation
import Testing
@testable import Cantino

// AreaStorage is a thin wrapper over the core's area store (src/area_storage.rs
// has the exhaustive tests, and the parity corpus pins the ABI outcomes). These
// check what the wrapper adds: typed models, the commit hook as a Swift
// closure through the C callback and context pointer, error mapping.

@Suite struct AreaStorageTests {
    let workId = UUID(uuidString: "7C9E6679-7425-40DE-944B-E07FC1F90AE7")!

    /// A fresh areas root (not yet created: the core creates it).
    private func storage() throws -> AreaStorage {
        AreaStorage(root: try Fixtures.scratchDirectory("osm-area").appendingPathComponent("areas").path)
    }

    private func metadata(databaseBytes: Int64, workId: UUID?) -> AreaMetadata {
        AreaMetadata(
            bbox: Bbox(west: -111.895, south: 40.0, east: -111.885, north: 40.771), name: "Café / test",
            snapshotTimestamp: AreaMetadata.parseSnapshotTimestamp("2026-10-03T20:30:01Z"), importedAtMillis: 1_759_523_401_000,
            report: ImportReport(counts: ObjectCounts(nodes: 3, ways: 2, relations: 1), databaseBytes: databaseBytes),
            basemap: nil, workId: workId)
    }

    /// Stages a complete version (data + sidecar) the way a run does.
    @discardableResult
    private func stage(_ storage: AreaStorage, areaId: String = "city", data: String = "0123456789") throws -> AreaMetadata {
        let staging = try storage.prepareStaging(areaId: areaId, workId: workId)
        try data.write(toFile: staging + "/area.sqlite", atomically: false, encoding: .utf8)
        let metadata = metadata(databaseBytes: Int64(data.utf8.count), workId: workId)
        try storage.writeStagedMetadata(areaId: areaId, workId: workId, metadata: metadata)
        return metadata
    }

    @Test func validatesAreaIds() throws {
        try AreaStorage.validate(areaId: "City_1-b")
        for bad in ["", "a b", "../x", "café", String(repeating: "x", count: 65)] {
            #expect(category(caught { try AreaStorage.validate(areaId: bad) }) == "INVALID_ARGUMENT", "\(bad)")
        }
    }

    @Test func layoutNamesTheFiles() throws {
        let storage = try storage()
        let plain = try storage.layout(areaId: "city", workId: nil as String?)
        #expect(plain.data == storage.root + "/city.sqlite")
        #expect(plain.basemap == storage.root + "/city.pmtiles")
        #expect(plain.stagingDir == nil)
        let run = try storage.layout(areaId: "city", workId: workId)
        // The core lower-cases the run ID in paths.
        #expect(run.stagingDir == storage.root + "/.staging/city/7c9e6679-7425-40de-944b-e07fc1f90ae7")
        #expect(run.stagedMetadata == run.stagingDir.map { $0 + "/area.json" })
        #expect(category(caught { try storage.layout(areaId: "city", workId: "not-a-uuid") }) == "INVALID_ARGUMENT")
        #expect(try storage.dataFile(areaId: "city") == plain.data)
    }

    @Test func nothingPublishedYet() throws {
        #expect(try storage().published(areaId: "city") == nil)
    }

    @Test func commitPublishesAndTheHooksRunInOrder() throws {
        let storage = try storage()
        let expected = try stage(storage)
        let data = try storage.dataFile(areaId: "city")
        // Before the commit point nothing is published yet; after it the
        // journal exists (the roll-forward is under way).
        nonisolated(unsafe) var events: [String] = []
        try storage.commit(
            areaId: "city", workId: workId, hasBasemap: false,
            beforeCommit: {
                events.append("before")
                #expect(!FileManager.default.fileExists(atPath: data))
            },
            afterCommitPoint: { events.append("after") })
        #expect(events == ["before", "after"])
        let info = try #require(try storage.published(areaId: "city"))
        #expect(info.dataURL?.path == data)
        #expect(info.basemapURL == nil)
        #expect(info.metadata == expected)
        #expect(try String(contentsOfFile: data, encoding: .utf8) == "0123456789")
    }

    @Test func aThrowingBeforeHookAbortsWithNothingChanged() throws {
        struct Stop: Error {}
        let storage = try storage()
        try stage(storage)
        let error = caught {
            try storage.commit(areaId: "city", workId: workId, hasBasemap: false, beforeCommit: { throw Stop() })
        }
        #expect(error is Stop)
        #expect(try storage.published(areaId: "city") == nil)
        // The staged version is intact: a retry publishes it.
        try storage.commit(areaId: "city", workId: workId, hasBasemap: false)
        #expect(try storage.published(areaId: "city") != nil)
    }

    @Test func commitOfAnIncompleteStagedVersionIsInvalid() throws {
        let storage = try storage()
        _ = try storage.prepareStaging(areaId: "city", workId: workId)
        let error = caught { try storage.commit(areaId: "city", workId: workId, hasBasemap: false) }
        #expect(category(error) == "INVALID_ARGUMENT")
    }

    @Test func aSecondCommitReplacesTheArea() throws {
        let storage = try storage()
        try stage(storage, data: "old")
        try storage.commit(areaId: "city", workId: workId, hasBasemap: false)
        let again = UUID()
        let staging = try storage.prepareStaging(areaId: "city", workId: again)
        try "newer data".write(toFile: staging + "/area.sqlite", atomically: false, encoding: .utf8)
        let next = metadata(databaseBytes: 10, workId: again)
        try storage.writeStagedMetadata(areaId: "city", workId: again, metadata: next)
        try storage.commit(areaId: "city", workId: again, hasBasemap: false)
        #expect(try storage.published(areaId: "city")?.metadata == next)
        // The first run's staging directory is gone (the commit consumed it).
        #expect(!FileManager.default.fileExists(atPath: staging + "/area.sqlite"))
    }

    @Test func discardRemovesTheStagingDirectory() throws {
        let storage = try storage()
        let staging = try storage.prepareStaging(areaId: "city", workId: workId)
        #expect(FileManager.default.fileExists(atPath: staging))
        try storage.discardStaging(areaId: "city", workId: workId)
        #expect(!FileManager.default.fileExists(atPath: staging))
    }

    @Test func aSidecarThatDoesNotDescribeTheFilesReadsAsUnknown() throws {
        let storage = try storage()
        let staging = try storage.prepareStaging(areaId: "city", workId: workId)
        try "0123456789".write(toFile: staging + "/area.sqlite", atomically: false, encoding: .utf8)
        // The sidecar claims 99 bytes; the data file has 10.
        try storage.writeStagedMetadata(areaId: "city", workId: workId, metadata: metadata(databaseBytes: 99, workId: workId))
        try storage.commit(areaId: "city", workId: workId, hasBasemap: false)
        let info = try #require(try storage.published(areaId: "city"))
        #expect(info.metadata == nil)
    }

    @Test func sidecarJsonUsesTheStoredKeys() {
        let json = metadata(databaseBytes: 10, workId: workId).json.canonicalString
        #expect(json == #"{"basemap":null,"bbox":{"east":-111.885,"north":40.771,"south":40.0,"west":-111.895},"# +
            #""imported_at_millis":1759523401000,"name":"Café / test","report":{"counts":{"nodes":3,"relations":1,"ways":2},"# +
            #""database_bytes":10},"snapshot_timestamp":"2026-10-03T20:30:01Z","work_id":"7c9e6679-7425-40de-944b-e07fc1f90ae7"}"#)
    }
}

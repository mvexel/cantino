import Foundation
import Testing
@testable import Cantino

/// The download lifecycle end to end: real URLSession (routed to an
/// in-process fake SliceOSM), real import, real area store. Port of the
/// Kotlin `AreaManagerTest` (minus foreground mode, which iOS does not have),
/// plus resume-after-kill tests: they lay out on disk what a killed process
/// leaves behind and check that a new manager finishes the job.
///
/// Serialized: the download tuning is process-wide.
@Suite(.serialized) final class AreaManagerTests {
    static let pbf = try! Data(contentsOf: Fixtures.repoRoot.appendingPathComponent("tests/fixtures/snapshot.osm.pbf"))
    static let newPbf = try! Data(contentsOf: Fixtures.repoRoot.appendingPathComponent("tests/fixtures/profile.osm.pbf"))
    /// 22 tiles z12–15 of NW Salt Lake City (tests/fixtures/basemap).
    static let pmtiles = try! Data(contentsOf: Fixtures.repoRoot
        .appendingPathComponent("tests/fixtures/basemap/slc-nw-z12-15.pmtiles"))

    let directory: URL
    let slice = FakeSlice(pbf: AreaManagerTests.pbf, planet: AreaManagerTests.pmtiles)
    let areaId = "test-\(UUID().uuidString.prefix(8))"
    let bbox = Bbox(west: -111.01, south: 39.89, east: -110.99, north: 40.11)
    var areas: URL { directory.appendingPathComponent("cantino-areas") }
    var downloads: URL { directory.appendingPathComponent("cantino-area-downloads/\(areaId)") }

    init() throws {
        directory = try Fixtures.scratchDirectory("area-manager")
        var tuning = DownloadTuning()
        tuning.pollInterval = 0.1
        tuning.inlineRetryDelay = 0.05
        tuning.backoffDelay = 1
        DownloadTuning.current = tuning
    }

    deinit {
        AreaTestHooks.afterImport = nil
        AreaTestHooks.afterCommitPoint = nil
        DownloadTuning.reset()
        slice.close()
    }

    private func manager() -> AreaManager {
        AreaManager(directory: directory, config: AreaConfig(sliceBaseUrl: slice.base),
                    sessionConfiguration: FakeSlice.sessionConfiguration)
    }

    private var storage: AreaStorage { AreaStorage(root: areas.path) }

    // MARK: Tests

    @Test func recoveryFailureRemainsStorageFailureAndRetainsJournal() async throws {
        DownloadTuning.current.maxRunAttempts = 1
        try FileManager.default.createDirectory(at: areas, withIntermediateDirectories: true)
        let journal = areas.appendingPathComponent("\(areaId).commit")
        try "corrupt journal".write(to: journal, atomically: true, encoding: .utf8)
        let manager = manager()
        let states = try Recorder(manager, areaId)
        let runId = try manager.download(areaId: areaId, bbox: bbox)
        let failed = try await states.await { $0.runId == runId && $0.isTerminal }
        guard case .failed(_, _, let retryable, let reason) = failed else { Issue.record("\(failed)"); return }
        #expect(reason == .storage)
        #expect(retryable)
        #expect(try String(contentsOf: journal, encoding: .utf8) == "corrupt journal")
        #expect(slice.submits == 0)
        // Readers fail closed while the journal cannot be recovered.
        #expect(throws: CantinoError.self) { try manager.publishedArea(areaId: areaId) }
    }

    @Test func happyPathPublishesAreaWithSnapshotTimestamp() async throws {
        let manager = manager()
        var first = try manager.state(areaId: areaId).makeAsyncIterator()
        #expect(await first.next() == .idle(published: nil))
        let states = try Recorder(manager, areaId)
        let runId = try manager.download(areaId: areaId, bbox: bbox, name: "café test")
        let ready = try await states.await { if case .ready = $0 { true } else { false } }
        guard case .ready(_, let area) = ready else { Issue.record("\(ready)"); return }
        // Every state of the run carries the ID download() returned.
        #expect(ready.runId == runId)
        #expect(ready.isTerminal)
        #expect(AreaState.idle(published: nil).runId == nil)
        #expect(!AreaState.idle(published: nil).isTerminal)
        let progress = states.seen.filter {
            switch $0 { case .slicing, .downloading, .importing: true; default: false }
        }
        #expect(!progress.isEmpty)
        for state in progress {
            #expect(state.runId == runId)
            #expect(!state.isTerminal)
        }

        let metadata = try #require(area.metadata)
        #expect(metadata.report.counts == ObjectCounts(nodes: 4, ways: 2, relations: 1))
        #expect(metadata.snapshotTimestamp == AreaMetadata.parseSnapshotTimestamp(FakeSlice.timestamp))
        #expect(metadata.workId == runId)
        // The sidecar keeps the server's spelling.
        let sidecar = try JSONSerialization.jsonObject(
            with: Data(contentsOf: areas.appendingPathComponent("\(areaId).json"))) as! [String: Any]
        #expect(sidecar["snapshot_timestamp"] as? String == FakeSlice.timestamp)
        // SliceOSM order is south, west, north, east; the name is passed through.
        let body = try JSONSerialization.jsonObject(with: Data(slice.submitBodies.first!.utf8)) as! [String: Any]
        #expect(body["Name"] as? String == "café test")
        #expect(body["RegionData"] as? [Double] == [39.89, -111.01, 40.11, -110.99])
        #expect(states.seen.contains { if case .importing = $0 { true } else { false } })
        #expect(states.seen.contains { if case .slicing(_, 0.5) = $0 { true } else { false } })

        #expect(try cafeNames(area.dataURL) == ["Café Test"])
        let published = try #require(try manager.publishedArea(areaId: areaId))
        #expect(published == area)
        #expect(published.metadata?.bbox == bbox)
        // BasemapSource.none (the default): no basemap, no basemap traffic.
        #expect(published.basemapURL == nil)
        #expect(published.pmtilesURL == nil)
        #expect(published.metadata?.basemap == nil)
        #expect(slice.basemapRequests == 0)
        try assertNoStagingLeft()
        // The finished run left no request to resume.
        #expect(!FileManager.default.fileExists(atPath: downloads.appendingPathComponent("request.json").path))
        states.close()
    }

    @Test func serverErrorsWhilePollingAreRetriedAndResumeTheSameJob() async throws {
        // 5 consecutive 500s: the first attempt retries inline 3 times (4
        // failures) and gives up; the next attempt, after the backoff,
        // resumes the checkpointed job (no second submit).
        slice.statusFailures = 5
        let manager = manager()
        let states = try Recorder(manager, areaId)
        try manager.download(areaId: areaId, bbox: bbox)
        let ready = try await states.await(seconds: 60) { if case .ready = $0 { true } else { false } }
        guard case .ready(_, let area) = ready else { Issue.record("\(ready)"); return }
        #expect(slice.submits == 1, "job must be resumed, not resubmitted")
        #expect(states.seen.contains { if case .queued(_, 1) = $0 { true } else { false } })
        #expect(try cafeNames(area.dataURL) == ["Café Test"])
        states.close()
    }

    @Test func cancelDuringDownloadKeepsPreviousArea() async throws {
        let before = try publishOldArea()
        slice.throttle = true
        let manager = manager()
        let states = try Recorder(manager, areaId)
        let runId = try manager.download(areaId: areaId, bbox: bbox)
        // The fixture is tiny (URLSession reports its progress in one go):
        // cancel while the throttled transfer (~4 s) is in flight.
        _ = try await states.await { if case .downloading = $0 { true } else { false } }
        try await Task.sleep(nanoseconds: 500_000_000)
        try manager.cancel(areaId: areaId)
        let cancelled = try await states.await { if case .cancelled = $0 { true } else { false } }
        #expect(cancelled.runId == runId)
        #expect(cancelled.isTerminal)
        try assertOldAreaIntact(manager, before)
        #expect(!states.seen.contains { if case .importing = $0 { true } else { false } })
        try await waitUntil { try self.stagingDirs().isEmpty }
        try assertNoStagingLeft()
        #expect(!FileManager.default.fileExists(atPath: downloads.appendingPathComponent("request.json").path))
        states.close()
    }

    @Test func corruptPbfFailsWithoutRetryAndKeepsPreviousArea() async throws {
        let before = try publishOldArea()
        slice.file = Data(repeating: 0x5a, count: 1024)
        let manager = manager()
        let states = try Recorder(manager, areaId)
        let runId = try manager.download(areaId: areaId, bbox: bbox)
        let failed = try await states.await { if case .failed = $0 { true } else { false } }
        guard case .failed(let id, let message, let retryable, let reason) = failed else { return }
        #expect(id == runId)
        #expect(!retryable)
        #expect(reason == .invalidData)
        #expect(message.hasPrefix("import failed"), "\(message)")
        #expect(slice.downloads == 1)
        try assertOldAreaIntact(manager, before)
        try assertNoStagingLeft()
        states.close()
    }

    /// state() follows the area: after a second download the stream can
    /// still show the first run's final state; runId tells them apart.
    @Test func secondDownloadIsDistinguishableFromThePreviousRunsFinalState() async throws {
        let manager = manager()
        let first = try manager.download(areaId: areaId, bbox: bbox)
        let firstEnd = try await terminal(manager, first)
        guard case .ready = firstEnd else { Issue.record("\(firstEnd)"); return }

        let second = try manager.download(areaId: areaId, bbox: bbox)
        #expect(first != second)
        let states = try Recorder(manager, areaId)
        let secondEnd = try await states.await { $0.runId == second && $0.isTerminal }
        guard case .ready = secondEnd else { Issue.record("\(secondEnd)"); return }
        #expect(!states.seen.contains { $0.runId == first && $0 == secondEnd })
        #expect(states.seen.allSatisfy { $0.runId == nil || $0.runId == first || $0.runId == second })
        try assertNoStagingLeft()
        states.close()
    }

    @Test func clientErrorOnSubmitFailsWithoutRetry() async throws {
        slice.submitCode = 400
        let manager = manager()
        let runId = try manager.download(areaId: areaId, bbox: bbox)
        let failed = try await terminal(manager, runId)
        guard case .failed(_, let message, let retryable, let reason) = failed else { Issue.record("\(failed)"); return }
        #expect(!retryable)
        #expect(reason == .invalidRequest)
        #expect(message.contains("HTTP 400"), "\(message)")
        #expect(slice.submits == 1)
        #expect(slice.polls == 0)
        #expect(try manager.publishedArea(areaId: areaId) == nil)
    }

    @Test func urlBasemapIsValidatedAndPublishedWithTheData() async throws {
        let manager = manager()
        let url = slice.url("/basemap.pmtiles")
        let runId = try manager.download(areaId: areaId, bbox: bbox, basemap: try .url(url))
        let ready = try await terminal(manager, runId)
        guard case .ready(_, let area) = ready else { Issue.record("\(ready)"); return }
        #expect(try cafeNames(area.dataURL) == ["Café Test"])
        let basemapURL = try #require(area.basemapURL)
        #expect(basemapURL.path == areas.appendingPathComponent("\(areaId).pmtiles").path)
        #expect(try Data(contentsOf: basemapURL) == Self.pmtiles)
        #expect(area.pmtilesURL == "pmtiles://file://\(basemapURL.path)")
        #expect(area.pmtilesURL!.hasPrefix("pmtiles://file:///"))
        let basemap = try #require(area.metadata?.basemap)
        #expect(basemap.kind == .url)
        #expect(basemap.sourceUrl == url)
        #expect(basemap.fileBytes == Int64(Self.pmtiles.count))
        #expect(basemap.addressedTiles == 22)
        #expect(basemap.minZoom == 12 && basemap.maxZoom == 15)
        #expect(try manager.publishedArea(areaId: areaId) == area)
        try assertNoStagingLeft()
    }

    @Test func urlBasemapThatIsNotPmtilesFailsAndKeepsPreviousArea() async throws {
        let before = try publishOldArea()
        slice.basemapBody = Data("<html>moved</html>".utf8)
        let manager = manager()
        let runId = try manager.download(areaId: areaId, bbox: bbox, basemap: try .url(slice.url("/basemap.pmtiles")))
        let failed = try await terminal(manager, runId)
        guard case .failed(_, let message, let retryable, let reason) = failed else { Issue.record("\(failed)"); return }
        #expect(!retryable)
        #expect(reason == .invalidData)
        #expect(message.contains("not a valid PMTiles"), "\(message)")
        try assertOldAreaIntact(manager, before)
        try assertNoStagingLeft()
    }

    /// On-device extract against a range-capable fake planet: the published
    /// file must be byte-identical to the engine run in-process over the same
    /// bytes.
    @Test func extractedBasemapMatchesTheEngineAndIsPublishedWithTheData() async throws {
        let sub = Bbox(west: -112.085, south: 40.835, east: -112.07, north: 40.842)
        let manager = manager()
        let url = slice.url("/planet.pmtiles")
        let runId = try manager.download(areaId: areaId, bbox: sub, basemap: try .extract(planetUrl: url, maxZoom: 15))
        let ready = try await terminal(manager, runId)
        guard case .ready(_, let area) = ready else { Issue.record("\(ready)"); return }
        let file = try #require(area.basemapURL)
        #expect(try Data(contentsOf: file) == referenceExtract(Self.pmtiles, sub, maxZoom: 15))
        let info = try PmtilesInfo.read(file)
        #expect(info.bounds == sub)
        #expect(info.minZoom == 12 && info.maxZoom == 15)
        let basemap = try #require(area.metadata?.basemap)
        #expect(basemap.kind == .extract)
        #expect(basemap.addressedTiles == info.addressedTiles)
        #expect((1..<22).contains(info.addressedTiles))
        // Every request was a range request, and the sidecar counts them.
        #expect(basemap.requests == Int64(slice.basemapRequests))
        #expect(slice.rangeHeaders.allSatisfy { $0.hasPrefix("bytes=") })
        #expect(try cafeNames(area.dataURL) == ["Café Test"])
        try assertNoStagingLeft()
    }

    @Test func serverIgnoringRangeFailsAndKeepsPreviousArea() async throws {
        let before = try publishOldArea()
        slice.ignoreRange = true
        let manager = manager()
        let runId = try manager.download(areaId: areaId, bbox: bbox,
                                         basemap: try .extract(planetUrl: slice.url("/planet.pmtiles")))
        let failed = try await terminal(manager, runId)
        guard case .failed(_, let message, let retryable, let reason) = failed else { Issue.record("\(failed)"); return }
        #expect(!retryable)
        #expect(reason == .server)
        #expect(message.contains("ignored the Range header"), "\(message)")
        // One request, not retried: a 200 is not transient.
        #expect(slice.basemapRequests == 1)
        try assertOldAreaIntact(manager, before)
        try assertNoStagingLeft()
    }

    @Test func basemapFailureAfterDataDoesNotPublishTheNewData() async throws {
        let before = try publishOldArea()
        slice.basemapCode = 404
        let manager = manager()
        let states = try Recorder(manager, areaId)
        try manager.download(areaId: areaId, bbox: bbox, basemap: try .url(slice.url("/basemap.pmtiles")))
        let failed = try await states.await { if case .failed = $0 { true } else { false } }
        guard case .failed(_, let message, let retryable, let reason) = failed else { return }
        #expect(!retryable)
        // The app asked for a basemap URL that does not exist.
        #expect(reason == .invalidRequest)
        #expect(message.contains("HTTP 404"), "\(message)")
        // The data part succeeded (downloaded and imported) ...
        #expect(slice.downloads == 1)
        #expect(states.seen.contains { if case .importing = $0 { true } else { false } })
        // ... and still nothing new is published.
        try assertOldAreaIntact(manager, before)
        try assertNoStagingLeft()
        states.close()
    }

    /// A cancel during the (uninterruptible) import must discard the staged
    /// import, never publish while the state says cancelled.
    @Test func cancelDuringImportPublishesNothing() async throws {
        let before = try publishOldArea()
        let inImport = DispatchSemaphore(value: 0)
        let release = DispatchSemaphore(value: 0)
        AreaTestHooks.afterImport = {
            inImport.signal()
            _ = release.wait(timeout: .now() + 30)
        }
        let manager = manager()
        let states = try Recorder(manager, areaId)
        try manager.download(areaId: areaId, bbox: bbox, basemap: try .url(slice.url("/basemap.pmtiles")))
        #expect(await wait(inImport))
        try manager.cancel(areaId: areaId)
        release.signal()
        _ = try await states.await { if case .cancelled = $0 { true } else { false } }
        try await waitUntil { try self.stagingDirs().isEmpty }
        var current = try manager.state(areaId: areaId).makeAsyncIterator()
        let now = await current.next()
        guard case .cancelled = now else { Issue.record("\(String(describing: now))"); return }
        try assertOldAreaIntact(manager, before)
        #expect(slice.basemapRequests == 0, "no basemap download after the cancel")
        try assertNoStagingLeft()
        states.close()
    }

    /// A cancel that arrives after the commit point is ignored: the run
    /// published, so the state is ready.
    @Test func cancelAfterCommitPointReportsReady() async throws {
        _ = try publishOldArea()
        let committed = DispatchSemaphore(value: 0)
        let release = DispatchSemaphore(value: 0)
        AreaTestHooks.afterCommitPoint = {
            committed.signal()
            _ = release.wait(timeout: .now() + 30)
        }
        let manager = manager()
        let states = try Recorder(manager, areaId)
        let runId = try manager.download(areaId: areaId, bbox: bbox)
        #expect(await wait(committed))
        try manager.cancel(areaId: areaId)
        release.signal()
        let end = try await states.await { $0.runId == runId && $0.isTerminal }
        guard case .ready(_, let area) = end else { Issue.record("\(end)"); return }
        #expect(area.metadata?.workId == runId)
        #expect(try cafeNames(area.dataURL) == ["Café Test"])
        #expect(!states.seen.contains { if case .cancelled = $0 { true } else { false } })
        try await waitUntil { try self.stagingDirs().isEmpty }
        states.close()
    }

    @Test func invalidAreaIdsAndBasemapSourcesAreRejected() throws {
        let manager = manager()
        #expect(throws: CantinoError.self) { try manager.download(areaId: "../x", bbox: self.bbox) }
        #expect(throws: CantinoError.self) { try manager.cancel(areaId: "") }
        #expect(throws: CantinoError.self) { try manager.state(areaId: "a b") }
        #expect(throws: CantinoError.self) { try BasemapSource.url("file:///x.pmtiles") }
        #expect(throws: CantinoError.self) { try BasemapSource.extract(planetUrl: "https://x/p.pmtiles", maxZoom: 32) }
        #expect(throws: CantinoError.self) { try BasemapSource.extract(planetUrl: "https://x/p.pmtiles", overfetch: -1) }
    }

    // MARK: Resume after the app was killed

    /// Killed while downloading: the request and the job checkpoint are on
    /// disk, a partial PBF and a staging directory are left over. A new
    /// manager (next launch) resumes the same run with the same job,
    /// replaces the old area and cleans up.
    @Test func aRunKilledWhileDownloadingIsResumedWithItsJob() async throws {
        _ = try publishOldArea()
        slice.file = Self.newPbf
        let runId = UUID()
        let request = DownloadRequest(workId: runId, areaId: areaId, bbox: bbox, name: "kill test", basemap: .none,
                                      config: AreaConfig(sliceBaseUrl: slice.base))
        let files = DownloadFiles(root: directory.appendingPathComponent("cantino-area-downloads"))
        try files.writeRequest(request)
        try files.writeCheckpoint(areaId, workId: runId, base: slice.base, jobId: FakeSlice.job)
        try Data(repeating: 1, count: 100).write(to: files.pbf(areaId, runId))
        _ = try storage.prepareStaging(areaId: areaId, workId: runId)

        Registry.resetForTesting() // a new process
        let manager = manager()
        let end = try await terminal(manager, runId)
        guard case .ready(_, let area) = end else { Issue.record("\(end)"); return }
        #expect(area.metadata?.report.counts == ObjectCounts(nodes: 10, ways: 3, relations: 4))
        #expect(slice.submits == 0, "the checkpointed job is resumed")
        #expect(slice.downloads == 1)
        try assertNoStagingLeft()
        #expect(!FileManager.default.fileExists(atPath: files.request(areaId).path))
        #expect(!FileManager.default.fileExists(atPath: files.checkpoint(areaId).path))
    }

    /// Killed after the commit point, before the renames: the journal is on
    /// disk. The resumed run's recovery finishes the publication and the run
    /// succeeds without downloading anything.
    @Test func aRunKilledAfterItsCommitPointSucceedsWithoutDownloading() async throws {
        _ = try publishOldArea()
        let runId = UUID()
        // Stage the new version and stop "dead" right after the commit point.
        let staging = try storage.prepareStaging(areaId: areaId, workId: runId)
        let input = Fixtures.repoRoot.appendingPathComponent("tests/fixtures/profile.osm").path
        let report = try OsmStore.importArea(input: input, destination: staging + "/area.sqlite")
        try storage.writeStagedMetadata(areaId: areaId, workId: runId, metadata: AreaMetadata(
            bbox: bbox, name: "kill test", snapshotTimestamp: nil, importedAtMillis: 1, report: report, workId: runId))
        struct Killed: Error {}
        let kill = DispatchSemaphore(value: 0)
        let killer = Thread {
            // The commit's after-commit-point hook parks the thread for good,
            // as a kill would leave it: the journal is written, nothing renamed.
            try? self.storage.commit(areaId: self.areaId, workId: runId, hasBasemap: false,
                                     afterCommitPoint: { kill.signal(); Thread.sleep(forTimeInterval: 3600) })
        }
        killer.start()
        #expect(await wait(kill))
        // The parked thread holds the in-process area lock; a new process
        // would not. Move the journal state to a fresh root, as the next
        // launch sees it.
        let fresh = try Fixtures.scratchDirectory("area-manager-restart")
        try FileManager.default.copyItem(at: areas, to: fresh.appendingPathComponent("cantino-areas"))
        let files = DownloadFiles(root: fresh.appendingPathComponent("cantino-area-downloads"))
        try files.writeRequest(DownloadRequest(workId: runId, areaId: areaId, bbox: bbox, name: "kill test",
                                               basemap: .none, config: AreaConfig(sliceBaseUrl: slice.base)))
        #expect(FileManager.default.fileExists(atPath: fresh.appendingPathComponent("cantino-areas/\(areaId).commit").path))

        let manager = AreaManager(directory: fresh, config: AreaConfig(sliceBaseUrl: slice.base),
                                  sessionConfiguration: FakeSlice.sessionConfiguration)
        let end = try await terminal(manager, runId)
        guard case .ready(_, let area) = end else { Issue.record("\(end)"); return }
        #expect(area.metadata?.workId == runId)
        #expect(area.metadata?.report.counts == ObjectCounts(nodes: 10, ways: 3, relations: 4))
        #expect(slice.submits == 0 && slice.downloads == 0)
        #expect(!FileManager.default.fileExists(atPath: fresh.appendingPathComponent("cantino-areas/\(areaId).commit").path))
    }

    /// A cancelled run leaves no request behind: nothing is resumed.
    @Test func aCancelledRunIsNotResumed() async throws {
        slice.throttle = true
        let manager = manager()
        let runId = try manager.download(areaId: areaId, bbox: bbox)
        let states = try Recorder(manager, areaId)
        _ = try await states.await { if case .downloading = $0 { true } else { false } }
        try manager.cancel(areaId: areaId)
        _ = try await states.await { $0 == .cancelled(runId: runId) }
        #expect(DownloadFiles(root: directory.appendingPathComponent("cantino-area-downloads")).storedRequests().isEmpty)
        states.close()
    }

    // MARK: Helpers

    /// Publishes a distinguishable "previous" area (data with an "Old Café",
    /// a basemap, a sidecar) through the same commit as a download.
    private func publishOldArea() throws -> (AreaInfo, Data) {
        let xml = try Fixtures.scratchDirectory("old").appendingPathComponent("old.osm")
        try #"<osm version="0.6"><node id="7" lat="40.0" lon="-111.0" version="1"><tag k="amenity" v="cafe"/><tag k="name" v="Old Café"/></node></osm>"#
            .write(to: xml, atomically: true, encoding: .utf8)
        let run = UUID()
        let staging = try storage.prepareStaging(areaId: areaId, workId: run)
        let report = try OsmStore.importArea(input: xml.path, destination: staging + "/area.sqlite")
        let oldBasemap = Self.pmtiles
        try oldBasemap.write(to: URL(fileURLWithPath: staging + "/basemap.pmtiles"))
        let metadata = AreaMetadata(
            bbox: Bbox(west: -111.1, south: 39.9, east: -110.9, north: 40.1), name: "old",
            snapshotTimestamp: AreaMetadata.parseSnapshotTimestamp("2026-01-01T00:00:00Z"), importedAtMillis: 1,
            report: report,
            basemap: BasemapMetadata(kind: .url, sourceUrl: "http://old.example/x.pmtiles",
                                     fileBytes: Int64(oldBasemap.count), addressedTiles: 22, minZoom: 12, maxZoom: 15,
                                     requests: 1, transferredBytes: Int64(oldBasemap.count)),
            workId: run)
        try storage.writeStagedMetadata(areaId: areaId, workId: run, metadata: metadata)
        try storage.commit(areaId: areaId, workId: run, hasBasemap: true)
        let published = try #require(try storage.published(areaId: areaId))
        #expect(published.metadata == metadata)
        return (published, oldBasemap)
    }

    private func assertOldAreaIntact(_ manager: AreaManager, _ before: (AreaInfo, Data)) throws {
        let now = try #require(try manager.publishedArea(areaId: areaId))
        #expect(now == before.0)
        #expect(try cafeNames(now.dataURL) == ["Old Café"])
        #expect(try Data(contentsOf: #require(now.basemapURL)) == before.1)
    }

    private func stagingDirs() throws -> [URL] {
        let staging = areas.appendingPathComponent(".staging/\(areaId)")
        return (try? FileManager.default.contentsOfDirectory(at: staging, includingPropertiesForKeys: nil)) ?? []
    }

    private func assertNoStagingLeft() throws {
        let parts = ((try? FileManager.default.contentsOfDirectory(at: downloads, includingPropertiesForKeys: nil)) ?? [])
            .filter { $0.pathExtension == "part" }
        #expect(parts.isEmpty, "\(parts)")
        #expect(try stagingDirs().isEmpty)
        #expect(!FileManager.default.fileExists(atPath: areas.appendingPathComponent("\(areaId).commit").path))
    }

    /// Café names in an area (the store is used on this thread only, with no
    /// suspension in between).
    private func cafeNames(_ url: URL) throws -> [String] {
        let store = try OsmStore.open(url)
        defer { try? store.close() }
        return try store.query(Query(tags: [.equals("amenity", "cafe")], limit: 1000)).map { $0.tags["name"] ?? "?" }
    }

    /// The final state of `runId`.
    private func terminal(_ manager: AreaManager, _ runId: UUID, seconds: Double = 30) async throws -> AreaState {
        let states = try Recorder(manager, areaId)
        defer { states.close() }
        return try await states.await(seconds: seconds) { $0.runId == runId && $0.isTerminal }
    }

    /// The same extract run in-process by the engine over the fixture bytes
    /// (no HTTP): the expected output. Synchronous: the native handles stay
    /// on this thread.
    private func referenceExtract(_ source: Data, _ bbox: Bbox, maxZoom: Int) throws -> Data {
        let bytes = [UInt8](source)
        func serve(_ range: ByteRange) -> [UInt8] {
            Array(bytes[Int(range.offset)..<min(bytes.count, Int(range.offset + range.length))])
        }
        let plan = try BasemapPlan(bbox: bbox, minZoom: -1, maxZoom: Int32(maxZoom), overfetch: 0.05)
        var queue = [try plan.firstRequest()]
        while !queue.isEmpty {
            let range = queue.removeFirst()
            if case .fetch(let more) = try plan.feed(id: range.id, bytes: serve(range)) { queue += more }
        }
        let dir = try Fixtures.scratchDirectory("reference")
        let assembler = try plan.intoAssembler(staging: dir.appendingPathComponent("ref.part").path)
        for range in try assembler.remaining() { try assembler.writeRange(id: range.id, bytes: serve(range)) }
        let output = dir.appendingPathComponent("ref.pmtiles")
        try assembler.finish(output: output.path)
        try assembler.free()
        return try Data(contentsOf: output)
    }

    private func wait(_ semaphore: DispatchSemaphore, seconds: Double = 30) async -> Bool {
        await withCheckedContinuation { continuation in
            DispatchQueue.global().async { continuation.resume(returning: semaphore.wait(timeout: .now() + seconds) == .success) }
        }
    }

    private func waitUntil(seconds: Double = 15, _ condition: @escaping () throws -> Bool) async throws {
        let deadline = Date().addingTimeInterval(seconds)
        while try !condition() {
            guard Date() < deadline else { Issue.record("condition not met in \(seconds) s"); return }
            try await Task.sleep(nanoseconds: 50_000_000)
        }
    }
}

/// Collects every state yielded for an area, from subscription on.
final class Recorder: @unchecked Sendable {
    private let states = Locked<[AreaState]>([])
    private var task: Task<Void, Never>?

    init(_ manager: AreaManager, _ areaId: String) throws {
        let stream = try manager.state(areaId: areaId)
        task = Task { [states] in
            for await state in stream { states.withLock { $0.append(state) } }
        }
    }

    var seen: [AreaState] { states.withLock { $0 } }

    /// The first state seen (so far or later) that matches.
    func await(seconds: Double = 30, _ predicate: @escaping (AreaState) -> Bool) async throws -> AreaState {
        let deadline = Date().addingTimeInterval(seconds)
        while true {
            if let match = seen.first(where: predicate) { return match }
            guard Date() < deadline else {
                Issue.record("no matching state in \(seconds) s; seen \(seen)")
                throw CancellationError()
            }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
    }

    func close() { task?.cancel() }
}

#if canImport(Darwin) // progress needs KVO
/// Progress arrives during a large transfer, not only at its end.
@Test func downloadsReportProgressWhileTransferring() async throws {
    let slice = FakeSlice(pbf: Data((0..<2_000_000).map { UInt8(truncatingIfNeeded: $0 &* 31) }), planet: Data())
    defer { slice.close() }
    slice.throttle = true
    let http = AreaHTTP(configuration: FakeSlice.sessionConfiguration, timeout: 30)
    let target = try Fixtures.scratchDirectory("progress").appendingPathComponent("file.part")
    let seen = Locked<[Int64]>([])
    let bytes = try await http.download(slice.url("/files/\(FakeSlice.job).osm.pbf"), to: target) { bytes, _ in
        seen.withLock { $0.append(bytes) }
    }
    #expect(bytes == 2_000_000)
    #expect(seen.withLock { $0 }.contains { $0 > 0 && $0 < 2_000_000 })
}
#endif

@Test func userAgentVersionMatchesTheCrate() throws {
    let cargo = try String(contentsOf: Fixtures.repoRoot.appendingPathComponent("Cargo.toml"), encoding: .utf8)
    #expect(cargo.contains("version = \"\(cantinoVersion)\""))
}

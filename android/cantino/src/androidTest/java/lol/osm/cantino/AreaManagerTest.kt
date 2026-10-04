package lol.osm.cantino

import android.app.ActivityManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The download lifecycle end to end on a device: real WorkManager (the test
 * APK's auto-initialized instance), real HttpURLConnection, real import,
 * against a fake SliceOSM server on 127.0.0.1 that serves the shared fixture
 * PBF. Every test uses its own area ID, so leftovers cannot interfere.
 *
 * [liveDownloadThroughSliceOsm] talks to the real service and runs only with
 * `-Pandroid.testInstrumentationRunnerArguments.live=true`.
 */
@RunWith(AndroidJUnit4::class)
class AreaManagerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext
    private val pbf = instrumentation.context.assets.open("snapshot.osm.pbf").use { it.readBytes() }
    /** 22 tiles z12–15 of NW Salt Lake City, cut from a Protomaps build (tests/fixtures/basemap). */
    private val pmtiles = instrumentation.context.assets.open("basemap/slc-nw-z12-15.pmtiles").use { it.readBytes() }
    private lateinit var server: MockWebServer
    private lateinit var slice: FakeSlice
    private val areaId = "test-${System.nanoTime()}"
    private val bbox = Bbox(-111.01, 39.89, -110.99, 40.11)

    @Before
    fun startServer() {
        slice = FakeSlice(pbf, pmtiles)
        server = MockWebServer().apply {
            dispatcher = slice
            start(0)
        }
    }

    @After
    fun stopServer() {
        AreaTestHooks.afterImport = null
        AreaTestHooks.afterCommitPoint = null
        AreaTestHooks.onForeground = null
        AreaTestHooks.foregroundManifestGaps = null
        AreaTestHooks.downloadTuning = null
        server.close()
    }

    private fun config(): AreaConfig {
        AreaTestHooks.downloadTuning = DownloadTuning(pollIntervalMillis = 100, inlineRetryDelayMillis = 50, backoffDelayMillis = 10_000)
        return AreaConfig(sliceBaseUrl = server.url("/").toString())
    }

    private fun manager() = AreaManager(target, config())

    @Test
    fun recoveryFailureRemainsStorageFailureAndRetainsJournal() {
        val manager = manager()
        AreaTestHooks.downloadTuning = DownloadTuning.current.copy(maxRunAttempts = 1)
        val journal = File(target.filesDir, "cantino-areas/$areaId.commit")
        journal.parentFile!!.mkdirs()
        journal.writeText("corrupt journal")
        try {
            val runId = manager.download(areaId, bbox)
            // A reader deliberately fails closed while this journal cannot
            // be recovered; observe WorkManager directly for this failure.
            val work = runBlocking {
                withTimeout(30_000) {
                    androidx.work.WorkManager.getInstance(target).getWorkInfoByIdFlow(runId)
                        .first { it?.state?.isFinished == true }!!
                }
            }
            assertEquals(androidx.work.WorkInfo.State.FAILED, work.state)
            assertEquals(FailureReason.STORAGE.name, work.outputData.getString(AreaDownloadWorker.KEY_REASON))
            assertTrue(work.outputData.getBoolean(AreaDownloadWorker.KEY_RETRYABLE, false))
            assertEquals("corrupt journal", journal.readText())
            assertEquals(0, slice.submits.get())
        } finally {
            journal.delete()
        }
    }

    @Test
    fun happyPathPublishesAreaWithSnapshotTimestamp() {
        val foregroundCalls = AtomicInteger()
        AreaTestHooks.onForeground = { foregroundCalls.incrementAndGet() }
        val manager = manager()
        assertEquals(AreaState.Idle(null), runBlocking { manager.state(areaId).first() })
        val states = Recorder(manager, areaId)
        val runId = manager.download(areaId, bbox, "café test")
        val ready = states.await { it is AreaState.Ready } as AreaState.Ready
        // Every state of the run carries the ID download() returned (Idle, seen before it, has none).
        assertEquals(runId, ready.runId)
        assertTrue(ready.isTerminal)
        assertEquals(null, AreaState.Idle(null).runId)
        assertFalse(AreaState.Idle(null).isTerminal)
        val progress = states.seen.filter { it is AreaState.Slicing || it is AreaState.Downloading || it is AreaState.Importing }
        assertTrue(progress.isNotEmpty())
        progress.forEach { assertEquals("$it", runId, it.runId); assertFalse("$it", it.isTerminal) }

        val metadata = ready.area.metadata!!
        assertEquals(ObjectCounts(4, 2, 1), metadata.report!!.counts)
        assertEquals(Instant.parse(FakeSlice.TIMESTAMP), metadata.snapshotTimestamp)
        // The sidecar keeps the server's string as received.
        val sidecar = JSONObject(File(target.filesDir, "cantino-areas/$areaId.json").readText())
        assertEquals(FakeSlice.TIMESTAMP, sidecar.getString("snapshot_timestamp"))
        // SliceOSM order is south, west, north, east; the name is passed through.
        val body = JSONObject(slice.submitBodies.single())
        assertEquals("café test", body.getString("Name"))
        assertEquals("[39.89,-111.01,40.11,-110.99]", body.getJSONArray("RegionData").toString())
        assertTrue(states.seen.any { it is AreaState.Importing })
        assertTrue(states.seen.any { it is AreaState.Slicing && it.fraction == 0.5 })

        assertEquals(listOf("Café Test"), cafeNames(manager.publishedArea(areaId)?.dataFile!!))
        val published = manager.publishedArea(areaId)!!
        assertEquals(ready.area, published)
        assertEquals(bbox, published.metadata!!.bbox)
        // BasemapSource.None (the default): no basemap, no basemap traffic.
        assertEquals(null, published.basemapFile)
        assertEquals(null, published.pmtilesUrl)
        assertEquals(null, published.metadata!!.basemap)
        assertFalse(File(target.filesDir, "cantino-areas/$areaId.pmtiles").exists())
        assertEquals(0, slice.basemapRequests.get())
        assertEquals("foreground mode is opt-in", 0, foregroundCalls.get())
        assertNoStagingLeft()
        states.close()
    }

    /**
     * The import profile is recorded in the published area's report: in the
     * Ready state and when the area is read back from disk (the core parses
     * the sidecar, so this is where a dropped profile would show).
     */
    @Test
    fun importProfileIsRecordedInThePublishedArea() {
        val profile = ImportProfile(listOf(KeepRule(setOf(OsmKind.NODE), "amenity", listOf("cafe"))))
        val manager = AreaManager(target, config().copy(importOptions = ImportOptions(profile = profile)))
        val states = Recorder(manager, areaId)
        manager.download(areaId, bbox)
        val ready = states.await { it is AreaState.Ready } as AreaState.Ready
        val report = ready.area.metadata!!.report!!
        assertEquals(profile, report.profile)
        assertEquals(ObjectCounts(1, 0, 0), report.counts)
        assertEquals(profile, manager.publishedArea(areaId)!!.metadata!!.report!!.profile)
        assertEquals(profile, runBlocking { manager.loadPublishedArea(areaId) }!!.metadata!!.report!!.profile)
        // A manager without a profile still reads the recorded one.
        assertEquals(profile, manager().publishedArea(areaId)!!.metadata!!.report!!.profile)
        states.close()
    }

    /**
     * Foreground mode: the run promotes itself to WorkManager's
     * SystemForegroundService. Checked twice: the worker's setForeground
     * calls succeeded (hook), and while the run is inside it (blocked right
     * after the import) the system lists the service as a running foreground
     * service of this app. After the run the service is gone.
     */
    @Test
    fun foregroundModeRunsTheDownloadAsAForegroundService() {
        val notificationIds = Collections.synchronizedList(mutableListOf<Int>())
        AreaTestHooks.onForeground = { notificationIds += it }
        var foregroundDuringRun: Boolean? = null
        AreaTestHooks.afterImport = { foregroundDuringRun = foregroundServiceRunning() }
        val manager = AreaManager(target, config().copy(foreground = ForegroundConfig(title = "Test download")))
        val states = Recorder(manager, areaId)
        manager.download(areaId, bbox)
        val ready = states.await { it is AreaState.Ready || it is AreaState.Failed }
        assertTrue("$ready", ready is AreaState.Ready)

        assertEquals(true, foregroundDuringRun)
        assertTrue("setForeground was never called", notificationIds.isNotEmpty())
        assertEquals(setOf("cantino-area-download:$areaId".hashCode()), notificationIds.toSet())
        assertEquals(listOf("Café Test"), cafeNames(manager.publishedArea(areaId)?.dataFile!!))
        waitUntil { !foregroundServiceRunning() }
        assertNoStagingLeft()
        states.close()
    }

    /**
     * Foreground mode requested, but the app manifest lacks the opt-in
     * entries (simulated: this test APK declares them for the test above).
     * The run must never ask for the foreground (a missing service type
     * would crash the process inside WorkManager's service) and must finish
     * as ordinary background work.
     */
    @Test
    fun foregroundModeWithoutManifestEntriesFallsBackToBackground() {
        AreaTestHooks.foregroundManifestGaps = { listOf("<uses-permission android.permission.FOREGROUND_SERVICE_DATA_SYNC>") }
        val foregroundCalls = AtomicInteger()
        AreaTestHooks.onForeground = { foregroundCalls.incrementAndGet() }
        var foregroundDuringRun: Boolean? = null
        AreaTestHooks.afterImport = { foregroundDuringRun = foregroundServiceRunning() }
        val manager = AreaManager(target, config().copy(foreground = ForegroundConfig(title = "Test download")))
        val states = Recorder(manager, areaId)
        manager.download(areaId, bbox)
        val ready = states.await { it is AreaState.Ready || it is AreaState.Failed }
        assertTrue("$ready", ready is AreaState.Ready)

        assertEquals("setForeground must not be called", 0, foregroundCalls.get())
        assertEquals(false, foregroundDuringRun)
        assertEquals(listOf("Café Test"), cafeNames(manager.publishedArea(areaId)?.dataFile!!))
        assertNoStagingLeft()
        states.close()
    }

    /** The real manifest check against this test APK, which declares the opt-in entries. */
    @Test
    fun manifestCheckFindsTheOptInForegroundEntries() {
        assertEquals(emptyList<String>(), AreaDownloadWorker.foregroundManifestGaps(target))
    }

    @Test
    fun snapshotTimestampParsesRfc3339AndToleratesGarbage() {
        val parse = { value: String? -> AreaMetadata.parseSnapshotTimestamp(value) }
        val expected = Instant.parse("2026-10-03T20:30:01Z")
        assertEquals(expected, parse("2026-10-03T20:30:01Z"))
        assertEquals(expected, parse("2026-10-03t20:30:01z"))
        assertEquals(expected, parse("2026-10-03 20:30:01Z"))
        assertEquals(expected, parse("2026-10-03T22:30:01+02:00"))
        assertEquals(Instant.parse("2026-10-03T20:30:01.250Z"), parse("2026-10-03T20:30:01.25Z"))
        assertEquals(null, parse(null))
        assertEquals(null, parse(""))
        assertEquals(null, parse("yesterday"))
        assertEquals(null, parse("2026-10-03T20:30:01")) // no offset: not RFC 3339
    }

    @Test
    fun serverErrorsWhilePollingAreRetriedAndResumeTheSameJob() {
        // 5 consecutive 500s: the first run retries inline 3 times (4 failures),
        // gives up with Result.retry(); WorkManager reruns it after its 10 s
        // backoff and that run resumes the checkpointed job (no second submit).
        slice.statusFailures.set(5)
        val manager = manager()
        val states = Recorder(manager, areaId)
        manager.download(areaId, bbox)
        val ready = states.await(60) { it is AreaState.Ready }
        assertTrue(ready is AreaState.Ready)
        assertEquals("job must be resumed, not resubmitted", 1, slice.submits.get())
        assertTrue(states.seen.any { it is AreaState.Queued && it.previousRuns == 1 })
        assertEquals(listOf("Café Test"), cafeNames(manager.publishedArea(areaId)?.dataFile!!))
        states.close()
    }

    @Test
    fun cancelDuringDownloadKeepsPreviousArea() {
        val before = publishOldArea()
        slice.throttle = true // ~14 s for the fixture
        val manager = manager()
        val states = Recorder(manager, areaId)
        val runId = manager.download(areaId, bbox)
        states.await { it is AreaState.Downloading && it.bytes > 0 }
        manager.cancel(areaId)
        val cancelled = states.await { it is AreaState.Cancelled }
        assertEquals(runId, cancelled.runId)
        assertTrue(cancelled.isTerminal)

        assertOldAreaIntact(manager, before)
        assertFalse(states.seen.any { it is AreaState.Importing })
        assertNoStagingLeft()
        states.close()
    }

    @Test
    fun corruptPbfFailsWithoutRetryAndKeepsPreviousArea() {
        val before = publishOldArea()
        slice.file = ByteArray(1024) { 0x5a }
        val manager = manager()
        val states = Recorder(manager, areaId)
        val runId = manager.download(areaId, bbox)
        val failed = states.await { it is AreaState.Failed } as AreaState.Failed
        assertEquals(runId, failed.runId)
        assertTrue(failed.isTerminal)
        assertFalse(failed.retryable)
        assertEquals(FailureReason.INVALID_DATA, failed.reason)
        assertTrue(failed.message, failed.message.startsWith("import failed"))
        assertEquals(1, slice.downloads.get())
        assertEquals(listOf("Old Café"), cafeNames(manager.publishedArea(areaId)?.dataFile!!))
        // The old area's files and metadata are still what was published before.
        assertOldAreaIntact(manager, before)
        assertNoStagingLeft()
        states.close()
    }

    /**
     * state() follows the area: after a second download() the flow can still
     * show the first run's final state. runId tells them apart, and waiting
     * for `runId == mine && isTerminal` skips the stale one.
     */
    @Test
    fun secondDownloadIsDistinguishableFromThePreviousRunsFinalState() {
        val manager = manager()
        val first = manager.download(areaId, bbox)
        val firstEnd = runBlocking {
            withTimeout(30_000) { manager.state(areaId).first { it.runId == first && it.isTerminal } }
        }
        assertTrue("$firstEnd", firstEnd is AreaState.Ready)

        val second = manager.download(areaId, bbox)
        assertTrue(first != second)
        val seen = Collections.synchronizedList(mutableListOf<AreaState>())
        val secondEnd = runBlocking {
            withTimeout(30_000) {
                manager.state(areaId).onEach { seen += it }.first { it.runId == second && it.isTerminal }
            }
        }
        assertTrue("$secondEnd", secondEnd is AreaState.Ready)
        assertEquals(second, secondEnd.runId)
        // The previous run's Ready (if the flow showed it) is a different value from this run's.
        assertTrue(seen.none { it.runId == first && it == secondEnd })
        assertTrue(seen.all { it.runId == null || it.runId == first || it.runId == second })
        assertNoStagingLeft()
    }

    @Test
    fun clientErrorOnSubmitFailsWithoutRetry() {
        slice.submitCode = 400
        val manager = manager()
        val states = Recorder(manager, areaId)
        val runId = manager.download(areaId, bbox)
        val failed = states.await { it is AreaState.Failed } as AreaState.Failed
        assertEquals(runId, failed.runId)
        assertFalse(failed.retryable)
        assertEquals(FailureReason.INVALID_REQUEST, failed.reason)
        assertTrue(failed.message, failed.message.contains("HTTP 400"))
        assertEquals(1, slice.submits.get())
        assertEquals(0, slice.polls.get())
        assertEquals(null, manager.publishedArea(areaId)?.dataFile)
        states.close()
    }

    /** Real SliceOSM, small downtown Salt Lake City box. Logs per-state timings under tag AreaLive. */
    @Test
    fun liveDownloadThroughSliceOsm() {
        assumeTrue("pass -e live true to run", InstrumentationRegistry.getArguments().getString("live") == "true")
        val manager = AreaManager(target)
        val id = "live-slc"
        val states = Recorder(manager, id)
        val started = System.nanoTime()
        manager.download(id, Bbox(-111.895, 40.765, -111.885, 40.771), "Cantino live test")
        val ready = states.await(300) { it is AreaState.Ready || it is AreaState.Failed }
        val total = (System.nanoTime() - started) / 1e6
        // First time each state kind was observed, relative to download().
        val firsts = LinkedHashMap<String, Double>()
        states.timed.forEach { (nanos, state) ->
            firsts.putIfAbsent(state.javaClass.simpleName, (nanos - started) / 1e6)
        }
        firsts.forEach { (state, ms) -> Log.i("AreaLive", "first $state at %.0f ms".format(ms)) }
        Log.i("AreaLive", "total %.0f ms, final $ready".format(total))
        assertTrue("$ready", ready is AreaState.Ready)
        ready as AreaState.Ready
        val cafes = cafeNames(ready.area.dataFile!!)
        val metadata = ready.area.metadata!!
        Log.i("AreaLive", "snapshot ${metadata.snapshotTimestamp}, ${metadata.report}, ${cafes.size} cafés: $cafes")
        assertNotNull(metadata.snapshotTimestamp)
        assertTrue(metadata.report!!.counts.nodes > 0)
        states.close()
    }

    // --- Basemap ----------------------------------------------------------------

    @Test
    fun urlBasemapIsValidatedAndPublishedWithTheData() {
        val manager = manager()
        val states = Recorder(manager, areaId)
        val url = server.url("/basemap.pmtiles").toString()
        manager.download(areaId, bbox, basemap = BasemapSource.Url(url))
        val ready = states.await { it is AreaState.Ready } as AreaState.Ready

        val area = ready.area
        assertEquals(listOf("Café Test"), cafeNames(area.dataFile!!))
        assertEquals(File(target.filesDir, "cantino-areas/$areaId.pmtiles"), area.basemapFile)
        assertArrayEquals(pmtiles, area.basemapFile!!.readBytes())
        assertEquals("pmtiles://file://${area.basemapFile!!.absolutePath}", area.pmtilesUrl)
        assertTrue(area.pmtilesUrl!!.startsWith("pmtiles://file:///"))
        val basemap = area.metadata!!.basemap!!
        assertEquals(BasemapKind.URL, basemap.kind)
        assertEquals(url, basemap.sourceUrl)
        assertEquals(pmtiles.size.toLong(), basemap.fileBytes)
        assertEquals(22L, basemap.addressedTiles)
        assertEquals(12 to 15, basemap.minZoom to basemap.maxZoom)
        assertEquals(area, manager.publishedArea(areaId))
        assertTrue(states.seen.any { it is AreaState.Importing })
        assertNoStagingLeft()
        states.close()
    }

    @Test
    fun urlBasemapThatIsNotPmtilesFailsAndKeepsPreviousArea() {
        val before = publishOldArea()
        slice.basemapBody = "<html>moved</html>".toByteArray()
        val manager = manager()
        val states = Recorder(manager, areaId)
        manager.download(areaId, bbox, basemap = BasemapSource.Url(server.url("/basemap.pmtiles").toString()))
        val failed = states.await { it is AreaState.Failed } as AreaState.Failed
        assertFalse(failed.retryable)
        assertEquals(FailureReason.INVALID_DATA, failed.reason)
        assertTrue(failed.message, failed.message.contains("not a valid PMTiles"))
        assertOldAreaIntact(manager, before)
        assertNoStagingLeft()
        states.close()
    }

    @Test
    fun basemapOnlyPublishesNoDataAndMakesNoSliceRequests() {
        val manager = manager()
        val states = Recorder(manager, areaId)
        val url = server.url("/basemap.pmtiles").toString()
        val runId = manager.downloadBasemap(areaId, bbox, BasemapSource.Url(url))
        val ready = states.await { it.runId == runId && it.isTerminal }
        assertTrue("$ready", ready is AreaState.Ready)
        ready as AreaState.Ready
        assertEquals(null, ready.area.dataFile)
        assertArrayEquals(pmtiles, ready.area.basemapFile!!.readBytes())
        val metadata = ready.area.metadata!!
        assertEquals(null, metadata.report)
        assertEquals(null, metadata.snapshotTimestamp)
        assertEquals(runId, metadata.workId)
        assertEquals(BasemapKind.URL, metadata.basemap!!.kind)
        assertEquals(0, slice.submits.get())
        assertEquals(0, slice.downloads.get())
        assertFalse(states.seen.any { it is AreaState.Slicing || it is AreaState.Downloading || it is AreaState.Importing })
        assertTrue(states.seen.any { it is AreaState.Basemap && it.runId == runId })
        assertEquals(ready.area, manager.publishedArea(areaId))
        assertNoStagingLeft()
        states.close()
    }

    /** Refresh = full replace: basemap-only removes the old data; a later download brings it back. */
    @Test
    fun basemapOnlyReplacesDataAndADownloadBringsItBack() {
        publishOldArea()
        val manager = manager()
        val url = server.url("/basemap.pmtiles").toString()
        val basemapOnly = manager.downloadBasemap(areaId, bbox, BasemapSource.Url(url))
        val first = runBlocking { withTimeout(30_000) { manager.state(areaId).first { it.runId == basemapOnly && it.isTerminal } } }
        assertTrue("$first", first is AreaState.Ready)
        assertEquals(null, manager.publishedArea(areaId)!!.dataFile)
        assertFalse(File(target.filesDir, "cantino-areas/$areaId.sqlite").exists())

        val full = manager.download(areaId, bbox)
        val second = runBlocking { withTimeout(30_000) { manager.state(areaId).first { it.runId == full && it.isTerminal } } }
        assertTrue("$second", second is AreaState.Ready)
        val area = manager.publishedArea(areaId)!!
        assertEquals(listOf("Café Test"), cafeNames(area.dataFile!!))
        assertEquals(null, area.basemapFile) // the full download asked for no basemap
        assertNoStagingLeft()
    }

    @Test
    fun downloadBasemapNeedsASource() {
        assertThrows(IllegalArgumentException::class.java) { manager().downloadBasemap(areaId, bbox, BasemapSource.None) }
    }

    /**
     * On-device extract against a range-capable fake planet (the fixture
     * archive). The published file must be byte-identical to the Rust engine
     * driven in-process over the same bytes, i.e. to what the desktop engine
     * (golden-tested against go-pmtiles) produces for this bbox.
     */
    @Test
    fun extractedBasemapMatchesTheEngineAndIsPublishedWithTheData() {
        val sub = Bbox(-112.085, 40.835, -112.07, 40.842)
        val manager = manager()
        val states = Recorder(manager, areaId)
        val url = server.url("/planet.pmtiles").toString()
        manager.download(areaId, sub, basemap = BasemapSource.Extract(url, maxZoom = 15))
        val ready = states.await { it is AreaState.Ready || it is AreaState.Failed }
        assertTrue("$ready", ready is AreaState.Ready)
        ready as AreaState.Ready

        val file = ready.area.basemapFile!!
        val expected = referenceExtract(pmtiles, sub, maxZoom = 15)
        assertArrayEquals(expected, file.readBytes())
        val info = PmtilesInfo.read(file)
        assertEquals(Bbox(-112.085, 40.835, -112.07, 40.842), info.bounds)
        assertEquals(12 to 15, info.minZoom to info.maxZoom)
        val basemap = ready.area.metadata!!.basemap!!
        assertEquals(BasemapKind.EXTRACT, basemap.kind)
        assertEquals(info.addressedTiles, basemap.addressedTiles)
        assertTrue(info.addressedTiles in 1 until 22)
        // Every request was a range request, and the sidecar counts them.
        assertEquals(basemap.requests, slice.basemapRequests.get().toLong())
        assertTrue(slice.rangeHeaders.all { it.startsWith("bytes=") })
        assertEquals(listOf("Café Test"), cafeNames(ready.area.dataFile!!))
        assertNoStagingLeft()
        states.close()
    }

    @Test
    fun serverIgnoringRangeFailsAndKeepsPreviousArea() {
        val before = publishOldArea()
        slice.ignoreRange = true
        val manager = manager()
        val states = Recorder(manager, areaId)
        manager.download(areaId, bbox, basemap = BasemapSource.Extract(server.url("/planet.pmtiles").toString()))
        val failed = states.await { it is AreaState.Failed } as AreaState.Failed
        assertFalse(failed.retryable)
        assertEquals(FailureReason.SERVER, failed.reason)
        assertTrue(failed.message, failed.message.contains("ignored the Range header"))
        // One request, not retried: a 200 is not transient.
        assertEquals(1, slice.basemapRequests.get())
        assertOldAreaIntact(manager, before)
        assertNoStagingLeft()
        states.close()
    }

    @Test
    fun basemapFailureAfterDataDoesNotPublishTheNewData() {
        val before = publishOldArea()
        slice.basemapCode = 404
        val manager = manager()
        val states = Recorder(manager, areaId)
        manager.download(areaId, bbox, basemap = BasemapSource.Url(server.url("/basemap.pmtiles").toString()))
        val failed = states.await { it is AreaState.Failed } as AreaState.Failed
        assertFalse(failed.retryable)
        // The app asked for a basemap URL that does not exist.
        assertEquals(FailureReason.INVALID_REQUEST, failed.reason)
        assertTrue(failed.message, failed.message.contains("HTTP 404"))
        // The data part succeeded (downloaded and imported) ...
        assertEquals(1, slice.downloads.get())
        assertTrue(states.seen.any { it is AreaState.Importing })
        // ... and still nothing new is published: data, basemap and sidecar are the old ones.
        assertOldAreaIntact(manager, before)
        assertNoStagingLeft()
        states.close()
    }

    /**
     * The honesty bug of the first version: a cancel during the native
     * import used to publish while the state said Cancelled. The hook blocks
     * right after the import (like a long import: not interruptible), the
     * test cancels, and the run must discard the staged import.
     */
    @Test
    fun cancelDuringImportPublishesNothing() {
        val before = publishOldArea()
        val inImport = CountDownLatch(1)
        val release = CountDownLatch(1)
        AreaTestHooks.afterImport = {
            inImport.countDown()
            release.await(30, TimeUnit.SECONDS)
        }
        val manager = manager()
        val states = Recorder(manager, areaId)
        manager.download(areaId, bbox, basemap = BasemapSource.Url(server.url("/basemap.pmtiles").toString()))
        assertTrue(inImport.await(30, TimeUnit.SECONDS))
        manager.cancel(areaId)
        states.await { it is AreaState.Cancelled }
        release.countDown()
        // Let the worker unwind, then check nothing was published and the state stayed Cancelled.
        waitUntil { stagingDirs().isEmpty() }
        assertTrue(runBlocking { manager.state(areaId).first() } is AreaState.Cancelled)
        assertOldAreaIntact(manager, before)
        assertEquals("no basemap download after the cancel", 0, slice.basemapRequests.get())
        assertNoStagingLeft()
        states.close()
    }

    /** A cancel that arrives after the commit point is ignored: the run published, so the state is Ready. */
    @Test
    fun cancelAfterCommitPointReportsReady() {
        publishOldArea()
        val committed = CountDownLatch(1)
        val release = CountDownLatch(1)
        AreaTestHooks.afterCommitPoint = {
            committed.countDown()
            release.await(30, TimeUnit.SECONDS)
        }
        val manager = manager()
        val states = Recorder(manager, areaId)
        val workId = manager.download(areaId, bbox)
        assertTrue(committed.await(30, TimeUnit.SECONDS))
        manager.cancel(areaId)
        // The state reader waits on the area lock until the commit is done.
        Thread { Thread.sleep(500); release.countDown() }.start()
        val ready = states.await { it is AreaState.Ready || it is AreaState.Cancelled }
        assertTrue("$ready", ready is AreaState.Ready)
        ready as AreaState.Ready
        assertEquals(workId, ready.area.metadata!!.workId)
        assertEquals(listOf("Café Test"), cafeNames(manager.publishedArea(areaId)?.dataFile!!))
        assertFalse(states.seen.any { it is AreaState.Cancelled })
        waitUntil { stagingDirs().isEmpty() }
        states.close()
    }

    /**
     * Real SliceOSM and a real Protomaps daily build: downtown Salt Lake City
     * with an on-device extract to z15. Logs per-state timings and the
     * extract's requests, bytes and tile count under tag AreaLive.
     */
    @Test
    fun liveDownloadWithExtractedBasemap() {
        assumeTrue("pass -e live true to run", InstrumentationRegistry.getArguments().getString("live") == "true")
        val manager = AreaManager(target)
        val id = "live-slc-basemap"
        val planet = requireNotNull(InstrumentationRegistry.getArguments().getString("planetUrl")) { "pass -e planetUrl <PMTiles URL>" }
        val states = Recorder(manager, id)
        val started = System.nanoTime()
        manager.download(id, Bbox(-111.895, 40.765, -111.885, 40.771), "Cantino live test", BasemapSource.Extract(planet, 15))
        val ready = states.await(300) { it is AreaState.Ready || it is AreaState.Failed }
        val total = (System.nanoTime() - started) / 1e6
        val firsts = LinkedHashMap<String, Double>()
        states.timed.forEach { (nanos, state) ->
            val key = if (state is AreaState.Basemap) "Basemap.${state.phase}" else state.javaClass.simpleName
            firsts.putIfAbsent(key, (nanos - started) / 1e6)
        }
        firsts.forEach { (state, ms) -> Log.i("AreaLive", "first $state at %.0f ms".format(ms)) }
        Log.i("AreaLive", "total %.0f ms, final $ready".format(total))
        assertTrue("$ready", ready is AreaState.Ready)
        ready as AreaState.Ready
        val basemap = ready.area.metadata!!.basemap!!
        val info = PmtilesInfo.read(ready.area.basemapFile!!)
        Log.i("AreaLive", "basemap $basemap; file ${info.fileBytes} B, z${info.minZoom}-${info.maxZoom}, ${info.addressedTiles} tiles")
        Log.i("AreaLive", "data ${ready.area.metadata!!.report}; ${cafeNames(ready.area.dataFile!!).size} cafés")
        assertTrue(info.addressedTiles > 0)
        states.close()
    }

    // --- helpers --------------------------------------------------------------

    /**
     * Publishes a distinguishable "previous" area (data with an "Old Café",
     * a basemap, a sidecar) through the same commit as a download, and
     * returns it with the bytes of its basemap.
     */
    private fun publishOldArea(): Pair<AreaInfo, ByteArray> {
        val xml = File(target.cacheDir, "$areaId-old.osm")
        xml.writeText(
            """<osm version="0.6"><node id="7" lat="40.0" lon="-111.0" version="1">""" +
                """<tag k="amenity" v="cafe"/><tag k="name" v="Old Café"/></node></osm>""",
        )
        val storage = AreaStorage(target).apply { prepare(areaId) }
        val oldRun = UUID.randomUUID()
        storage.prepareStaging(areaId, oldRun)
        val report = OsmStore.importArea(xml.path, storage.stagedArea(areaId, oldRun).path)
        xml.delete()
        val oldBasemap = pmtiles.copyOf() // same format, distinguishable by the sidecar
        storage.stagedBasemap(areaId, oldRun).writeBytes(oldBasemap)
        val metadata = AreaMetadata(
            Bbox(-111.1, 39.9, -110.9, 40.1), "old", Instant.parse("2026-01-01T00:00:00Z"), 1L, report,
            BasemapMetadata(BasemapKind.URL, "http://old.example/x.pmtiles", oldBasemap.size.toLong(), 22, 12, 15, 1, oldBasemap.size.toLong()),
            oldRun,
        )
        storage.writeStagedMetadata(areaId, oldRun, metadata)
        storage.commit(areaId, oldRun, hasData = true, hasBasemap = true) {}
        val published = storage.published(areaId)!!
        assertEquals(metadata, published.metadata)
        return published to oldBasemap
    }

    /** Data, basemap and sidecar are exactly the ones [publishOldArea] published. */
    private fun assertOldAreaIntact(manager: AreaManager, before: Pair<AreaInfo, ByteArray>) {
        assertEquals(before.first, manager.publishedArea(areaId))
        assertEquals(listOf("Old Café"), cafeNames(manager.publishedArea(areaId)?.dataFile!!))
        assertArrayEquals(before.second, manager.publishedArea(areaId)?.basemapFile!!.readBytes())
    }

    /** WorkManager's foreground service is running in the foreground (own services are always visible). */
    private fun foregroundServiceRunning(): Boolean {
        val activities = target.getSystemService(ActivityManager::class.java)
        @Suppress("DEPRECATION")
        return activities.getRunningServices(100).any {
            it.service.className == "androidx.work.impl.foreground.SystemForegroundService" && it.foreground
        }
    }

    private fun stagingDirs(): List<File> =
        File(target.filesDir, "cantino-areas/.staging/$areaId").listFiles()?.toList().orEmpty()

    private fun waitUntil(seconds: Long = 15, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (!condition()) {
            assertTrue("condition not met in $seconds s", System.nanoTime() < deadline)
            Thread.sleep(50)
        }
    }

    /**
     * The same extract run in-process by the native engine over the fixture
     * bytes (no HTTP): the expected output. Native handles live on one thread.
     */
    private fun referenceExtract(source: ByteArray, bbox: Bbox, maxZoom: Int): ByteArray {
        val worker = Executors.newSingleThreadExecutor()
        val dir = File(target.cacheDir, "$areaId-reference").apply { mkdirs() }
        try {
            return worker.submit<ByteArray> {
                val serve = { r: ByteRange ->
                    source.copyOfRange(r.offset.toInt(), minOf(source.size.toLong(), r.offset + r.length).toInt())
                }
                val plan = NativeBridge.basemapPlanNew(bbox.toJson().toString(), -1, maxZoom, 0.05)
                val queue = ArrayDeque(listOf(ByteRange.fromJson(JSONObject(NativeBridge.basemapPlanFirstRequest(plan)))))
                while (queue.isNotEmpty()) {
                    val r = queue.removeFirst()
                    val step = NativeBridge.basemapPlanFeed(plan, r.id, serve(r))
                    if (step.trim() != "\"wait\"") {
                        JSONObject(step).optJSONArray("fetch")?.let { queue += ByteRange.listFromJson(it.toString()) }
                    }
                }
                val asm = NativeBridge.basemapPlanIntoAssembler(plan, File(dir, "ref.part").path)
                try {
                    for (r in ByteRange.listFromJson(NativeBridge.basemapAsmRemaining(asm))) {
                        NativeBridge.basemapAsmWriteRange(asm, r.id, serve(r))
                    }
                    NativeBridge.basemapAsmFinish(asm, File(dir, "ref.pmtiles").path)
                } finally {
                    NativeBridge.basemapAsmFree(asm)
                }
                File(dir, "ref.pmtiles").readBytes()
            }.get()
        } finally {
            worker.shutdown()
            dir.deleteRecursively()
        }
    }

    private fun assertNoStagingLeft() {
        val parts = File(target.noBackupFilesDir, "cantino-area-downloads/$areaId").listFiles { f -> f.name.endsWith(".part") }
        assertEquals(emptyList<File>(), parts?.toList().orEmpty())
        assertEquals(emptyList<File>(), stagingDirs())
        assertFalse(File(target.filesDir, "cantino-areas/$areaId.commit").exists())
    }

    /** Opens the area on one worker thread (stores are thread-confined) and lists café names. */
    private fun cafeNames(file: File): List<String> {
        val worker = Executors.newSingleThreadExecutor()
        try {
            return worker.submit<List<String>> {
                OsmStore.open(file.path).use { store ->
                    store.query(Query(tags = listOf(TagFilter.Equals("amenity", "cafe")), limit = 1000))
                        .map { it.tags["name"] ?: "?" }
                }
            }.get()
        } finally {
            worker.shutdown()
        }
    }

    /** Collects every state emitted for an area, with timestamps, from creation on. */
    private class Recorder(manager: AreaManager, areaId: String) {
        val timed: MutableList<Pair<Long, AreaState>> = Collections.synchronizedList(mutableListOf())
        val seen get() = synchronized(timed) { timed.map { it.second } }
        private val scope = CoroutineScope(Dispatchers.Default)
        private val flow = manager.state(areaId)

        init {
            scope.launch { flow.collect { timed += System.nanoTime() to it } }
        }

        fun await(seconds: Long = 30, predicate: (AreaState) -> Boolean): AreaState = runBlocking {
            withTimeout(TimeUnit.SECONDS.toMillis(seconds)) { flow.first(predicate) }
        }

        fun close() = scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
    }

    /**
     * Minimal SliceOSM: one job ID; the status reports half done on the first
     * poll and complete on the second; the file is the fixture PBF. Also a
     * basemap host: `/basemap.pmtiles` (plain download) and
     * `/planet.pmtiles` (HTTP range requests, sliced from the fixture
     * archive, 206 + Content-Range like a CDN). Knobs inject test failures.
     */
    private class FakeSlice(pbf: ByteArray, private val planet: ByteArray) : Dispatcher() {
        @Volatile var basemapBody: ByteArray = planet
        @Volatile var basemapCode = 200
        @Volatile var ignoreRange = false
        val basemapRequests = AtomicInteger()
        val rangeHeaders: MutableList<String> = Collections.synchronizedList(mutableListOf())
        @Volatile var submitCode = 201
        @Volatile var file: ByteArray = pbf
        @Volatile var throttle = false
        val statusFailures = AtomicInteger(0)
        val submits = AtomicInteger()
        val polls = AtomicInteger()
        val downloads = AtomicInteger()
        val submitBodies: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.url.encodedPath
            return when {
                request.method == "POST" && path == "/api/" -> {
                    submits.incrementAndGet()
                    submitBodies += request.body?.utf8().orEmpty()
                    if (submitCode != 201) {
                        MockResponse.Builder().code(submitCode).body("bad request").build()
                    } else {
                        MockResponse.Builder().code(201).body("$JOB\n").build()
                    }
                }
                path == "/api/$JOB" -> {
                    if (statusFailures.getAndDecrement() > 0) {
                        return MockResponse.Builder().code(500).body("busy").build()
                    }
                    val complete = polls.incrementAndGet() >= 2
                    val status = JSONObject()
                        .put("Timestamp", TIMESTAMP)
                        .put("NodesTotal", 4).put("NodesProg", if (complete) 4 else 2)
                        .put("ElemsTotal", 8).put("ElemsProg", if (complete) 8 else 4)
                        .put("SizeBytes", file.size)
                        .put("Complete", complete)
                    MockResponse.Builder().body(status.toString()).build()
                }
                path == "/files/$JOB.osm.pbf" -> {
                    downloads.incrementAndGet()
                    MockResponse.Builder().body(Buffer().write(file)).apply {
                        if (throttle) throttleBody(8, 250, TimeUnit.MILLISECONDS)
                    }.build()
                }
                path == "/basemap.pmtiles" -> {
                    basemapRequests.incrementAndGet()
                    if (basemapCode != 200) {
                        MockResponse.Builder().code(basemapCode).body("no such file").build()
                    } else {
                        MockResponse.Builder().body(Buffer().write(basemapBody)).build()
                    }
                }
                path == "/planet.pmtiles" -> {
                    basemapRequests.incrementAndGet()
                    val range = request.headers["Range"]
                    if (range != null) rangeHeaders += range
                    val match = range?.let { Regex("""bytes=(\d+)-(\d+)""").matchEntire(it) }
                    if (ignoreRange || match == null) {
                        MockResponse.Builder().body(Buffer().write(planet)).build()
                    } else {
                        val first = match.groupValues[1].toInt()
                        val last = minOf(match.groupValues[2].toInt(), planet.size - 1)
                        MockResponse.Builder()
                            .code(206)
                            .addHeader("Content-Range", "bytes $first-$last/${planet.size}")
                            .body(Buffer().write(planet, first, last - first + 1))
                            .build()
                    }
                }
                else -> MockResponse.Builder().code(404).build()
            }
        }

        companion object {
            const val JOB = "2637da98-20a1-428f-b6db-18ac2861b763"
            const val TIMESTAMP = "2026-10-03T20:30:01Z"
        }
    }
}

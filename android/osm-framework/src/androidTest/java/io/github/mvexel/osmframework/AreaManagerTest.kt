package io.github.mvexel.osmframework

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
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
    private lateinit var server: MockWebServer
    private lateinit var slice: FakeSlice
    private val areaId = "test-${System.nanoTime()}"
    private val bbox = Bbox(-111.01, 39.89, -110.99, 40.11)

    @Before
    fun startServer() {
        slice = FakeSlice(pbf)
        server = MockWebServer().apply {
            dispatcher = slice
            start(0)
        }
    }

    @After
    fun stopServer() = server.close()

    private fun manager() = AreaManager(
        target,
        AreaConfig(
            sliceBaseUrl = server.url("/").toString(),
            pollIntervalMillis = 100,
            inlineRetries = 3,
            inlineRetryDelayMillis = 50,
            backoffDelayMillis = 10_000, // WorkManager's minimum
        ),
    )

    @Test
    fun happyPathPublishesAreaWithSnapshotTimestamp() {
        val manager = manager()
        assertEquals(AreaState.Idle(null), runBlocking { manager.state(areaId).first() })
        val states = Recorder(manager, areaId)
        manager.download(areaId, bbox, "café test")
        val ready = states.await { it is AreaState.Ready } as AreaState.Ready

        assertEquals(Counts(4, 2, 1), ready.report.counts)
        assertEquals(FakeSlice.TIMESTAMP, ready.snapshotTimestamp)
        // SliceOSM order is south, west, north, east; the name is passed through.
        val body = JSONObject(slice.submitBodies.single())
        assertEquals("café test", body.getString("Name"))
        assertEquals("[39.89,-111.01,40.11,-110.99]", body.getJSONArray("RegionData").toString())
        assertTrue(AreaState.Importing in states.seen)
        assertTrue(states.seen.any { it is AreaState.Slicing && it.fraction == 0.5 })

        assertEquals(listOf("Café Test"), cafeNames(manager.areaFile(areaId)!!))
        val published = manager.publishedArea(areaId)!!
        assertEquals(ready.area, published)
        assertEquals(bbox, published.metadata!!.bbox)
        assertNoStagingLeft()
        states.close()
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
        assertTrue(states.seen.any { it is AreaState.Queued && it.attempt == 1 })
        assertEquals(listOf("Café Test"), cafeNames(manager.areaFile(areaId)!!))
        states.close()
    }

    @Test
    fun cancelDuringDownloadKeepsPreviousArea() {
        publishOldArea()
        slice.throttle = true // ~14 s for the fixture
        val manager = manager()
        val states = Recorder(manager, areaId)
        manager.download(areaId, bbox)
        states.await { it is AreaState.Downloading && it.bytes > 0 }
        manager.cancel(areaId)
        states.await { it == AreaState.Cancelled }

        assertEquals(listOf("Old Café"), cafeNames(manager.areaFile(areaId)!!))
        assertFalse(AreaState.Importing in states.seen)
        assertNoStagingLeft()
        states.close()
    }

    @Test
    fun corruptPbfFailsWithoutRetryAndKeepsPreviousArea() {
        publishOldArea()
        slice.file = ByteArray(1024) { 0x5a }
        val manager = manager()
        val states = Recorder(manager, areaId)
        manager.download(areaId, bbox)
        val failed = states.await { it is AreaState.Failed } as AreaState.Failed
        assertFalse(failed.retryable)
        assertTrue(failed.message, failed.message.startsWith("import failed"))
        assertEquals(1, slice.downloads.get())
        assertEquals(listOf("Old Café"), cafeNames(manager.areaFile(areaId)!!))
        // The old area's file and metadata are still what was published before.
        assertEquals(null, manager.publishedArea(areaId)!!.metadata)
        assertNoStagingLeft()
        states.close()
    }

    @Test
    fun clientErrorOnSubmitFailsWithoutRetry() {
        slice.submitCode = 400
        val manager = manager()
        val states = Recorder(manager, areaId)
        manager.download(areaId, bbox)
        val failed = states.await { it is AreaState.Failed } as AreaState.Failed
        assertFalse(failed.retryable)
        assertTrue(failed.message, failed.message.contains("HTTP 400"))
        assertEquals(1, slice.submits.get())
        assertEquals(0, slice.polls.get())
        assertEquals(null, manager.areaFile(areaId))
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
        manager.download(id, Bbox(-111.895, 40.765, -111.885, 40.771), "osm-framework live test")
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
        val cafes = cafeNames(ready.area.file)
        Log.i("AreaLive", "snapshot ${ready.snapshotTimestamp}, ${ready.report}, ${cafes.size} cafés: $cafes")
        assertNotNull(ready.snapshotTimestamp)
        assertTrue(ready.report.counts.nodes > 0)
        states.close()
    }

    // --- helpers --------------------------------------------------------------

    /** Publishes a distinguishable "previous" area directly, as an earlier download would have. */
    private fun publishOldArea() {
        val xml = File(target.cacheDir, "$areaId-old.osm")
        xml.writeText(
            """<osm version="0.6"><node id="7" lat="40.0" lon="-111.0" version="1">""" +
                """<tag k="amenity" v="cafe"/><tag k="name" v="Old Café"/></node></osm>""",
        )
        val area = AreaStorage(target).apply { prepare(areaId) }.areaFile(areaId)
        OsmStore.importArea(xml.path, area.path)
        xml.delete()
    }

    private fun assertNoStagingLeft() {
        val parts = File(target.noBackupFilesDir, "osm-area-downloads/$areaId").listFiles { f -> f.name.endsWith(".part") }
        assertEquals(emptyList<File>(), parts?.toList().orEmpty())
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
     * poll and complete on the second; the file is the fixture PBF. Knobs
     * inject the failures each test needs.
     */
    private class FakeSlice(pbf: ByteArray) : Dispatcher() {
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
                else -> MockResponse.Builder().code(404).build()
            }
        }

        companion object {
            const val JOB = "2637da98-20a1-428f-b6db-18ac2861b763"
            const val TIMESTAMP = "2026-10-03T20:30:01Z"
        }
    }
}

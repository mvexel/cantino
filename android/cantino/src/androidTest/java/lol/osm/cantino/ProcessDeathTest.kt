package lol.osm.cantino

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A real process death during a download, in phases run by
 * `scripts/kill-test-android.sh`: one phase starts a run and parks it at the
 * point under test, the script kills the process with `kill -9`, and the
 * next phase (a new process) checks what survived and that WorkManager's
 * rerun of the same work finishes the job. The fake SliceOSM runs on the host
 * (`scripts/fake-sliceosm.py` via `adb reverse`) so it outlives the kill.
 *
 * Old area: `snapshot.osm.pbf` (4/2/1 objects). New area: `profile.osm.pbf`
 * (10/3/4), so which one is published is plain from the counts.
 *
 * Skipped unless run with `-e killPhase <phase> -e port <port> -e area <id>`.
 */
@RunWith(AndroidJUnit4::class)
class ProcessDeathTest {
    private val arguments = InstrumentationRegistry.getArguments()
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val phase: String? = arguments.getString("killPhase")
    private val port = arguments.getString("port")?.toInt() ?: 0
    private val areaId = arguments.getString("area") ?: "kill-test"
    private val base get() = "http://127.0.0.1:$port/"

    /** Survives the kill: what a phase hands to the next one. */
    private val notes get() = File(target.filesDir, "process-death-$areaId.json")

    private fun manager() = AreaManager(
        target,
        AreaConfig(
            sliceBaseUrl = base,
            pollIntervalMillis = 100,
            inlineRetries = 3,
            inlineRetryDelayMillis = 50,
            backoffDelayMillis = 10_000,
        ),
    )

    private fun control(json: String): JSONObject = http("control", json)
    private fun serverState(): JSONObject = http("state", null)

    private fun http(path: String, post: String?): JSONObject {
        val connection = URL(base + path).openConnection() as HttpURLConnection
        try {
            if (post != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.outputStream.use { it.write(post.toByteArray()) }
            }
            return JSONObject(connection.inputStream.bufferedReader().readText())
        } finally {
            connection.disconnect()
        }
    }

    /** Which fixture the data file holds: node 10 exists only in the new one. */
    private fun isNewArea(file: File): Boolean {
        val worker = Executors.newSingleThreadExecutor()
        try {
            return worker.submit<Boolean> { OsmStore.open(file).use { it.get(OsmId(OsmKind.NODE, 10)) != null } }.get()
        } finally {
            worker.shutdown()
        }
    }

    private fun await(manager: AreaManager, seconds: Long, predicate: (AreaState) -> Boolean): AreaState = runBlocking {
        withTimeout(TimeUnit.SECONDS.toMillis(seconds)) { manager.state(areaId).first(predicate) }
    }

    /** Tells the script to kill now, then waits to be killed. */
    private fun parkForKill(): Nothing {
        Log.i(TAG, "$KILL_MARKER $areaId")
        Thread.sleep(TimeUnit.SECONDS.toMillis(120))
        fail("process was not killed")
        throw AssertionError()
    }

    @Test
    fun run() {
        assumeTrue("run through scripts/kill-test-android.sh", phase != null)
        when (phase) {
            "seed" -> seed()
            "download" -> killDuringDownload()
            "verifyDownload" -> verifyAfterDownloadKill()
            "commit" -> killAfterCommitPoint()
            "verifyCommit" -> verifyAfterCommitKill()
            else -> fail("unknown phase $phase")
        }
    }

    /** Publishes the old area the later phases must keep or replace. */
    private fun seed() {
        // The server outlives scenarios: count submits from here on.
        val submitsBefore = control("""{"pbf":"snapshot.osm.pbf","slow":false}""").getInt("submits")
        val manager = manager()
        val runId = manager.download(areaId, Bbox(-111.01, 39.89, -110.99, 40.11), "kill test")
        val ready = await(manager, 60) { it is AreaState.Ready && it.runId == runId } as AreaState.Ready
        assertEquals(ObjectCounts(4, 2, 1), ready.area.metadata!!.report.counts)
        notes.writeText(JSONObject().put("old", runId.toString()).put("submits_before", submitsBefore).toString())
    }

    private fun startReplacement(slow: Boolean): UUID {
        control("""{"pbf":"profile.osm.pbf","slow":$slow}""")
        val runId = manager().download(areaId, Bbox(-111.01, 39.89, -110.99, 40.11), "kill test")
        notes.writeText(JSONObject(notes.readText()).put("run", runId.toString()).toString())
        return runId
    }

    private fun note(key: String): UUID = UUID.fromString(JSONObject(notes.readText()).getString(key))

    /** Submits since this scenario's seed phase began. */
    private fun submits(): Int = serverState().getInt("submits") - JSONObject(notes.readText()).getInt("submits_before")

    /** Phase 1 of the download scenario: killed while the PBF is arriving. */
    private fun killDuringDownload() {
        val manager = manager()
        val runId = startReplacement(slow = true)
        await(manager, 60) { it is AreaState.Downloading && it.runId == runId }
        parkForKill()
    }

    /**
     * Phase 2: the old area is still the published one, whole; the rerun of
     * the same work resumes the checkpointed SliceOSM job (no new submit),
     * downloads again and publishes the new area.
     */
    private fun verifyAfterDownloadKill() {
        val manager = manager()
        val old = note("old")
        val runId = note("run")
        // The server is still slow, so the rerun cannot have published yet.
        val published = manager.publishedArea(areaId)
        assertNotNull(published)
        assertEquals(old, published!!.metadata!!.workId)
        assertEquals(ObjectCounts(4, 2, 1), published.metadata!!.report.counts)
        assertFalse(isNewArea(published.dataFile))

        control("""{"slow":false}""")
        // WorkManager reruns the killed work by itself (JobScheduler), no
        // download() call here; backoff and job scheduling can take a while.
        val ready = await(manager, 300) { it is AreaState.Ready && it.runId == runId } as AreaState.Ready
        assertEquals(ObjectCounts(10, 3, 4), ready.area.metadata!!.report.counts)
        assertTrue(isNewArea(ready.area.dataFile))
        assertEquals(runId, ready.area.metadata!!.workId)
        val server = serverState()
        assertEquals("seed + one job for the killed run, resumed after the kill", 2, submits())
        assertTrue("the killed download was retried: $server", server.getInt("downloads") >= 3)
        Log.i(TAG, "download scenario: $server")
    }

    /** Phase 1 of the commit scenario: killed right after the commit point. */
    private fun killAfterCommitPoint() {
        AreaTestHooks.afterCommitPoint = { parkForKill() }
        startReplacement(slow = false)
        Thread.sleep(TimeUnit.SECONDS.toMillis(120))
        fail("the run never reached its commit point")
    }

    /**
     * Phase 2: the journal written before the kill rolls forward on the first
     * read, so the new area is published whole; the rerun of the same work
     * finds its own area published and reports Ready without downloading.
     */
    private fun verifyAfterCommitKill() {
        val manager = manager()
        val runId = note("run")
        val downloadsBefore = serverState().getInt("downloads")
        val published = manager.publishedArea(areaId)
        assertNotNull(published)
        assertEquals(runId, published!!.metadata!!.workId)
        assertEquals(ObjectCounts(10, 3, 4), published.metadata!!.report.counts)
        assertTrue(isNewArea(published.dataFile))
        assertFalse(File(target.filesDir, "cantino-areas/$areaId.commit").exists())

        val ready = await(manager, 300) { it is AreaState.Ready && it.runId == runId } as AreaState.Ready
        assertEquals(runId, ready.area.metadata!!.workId)
        val server = serverState()
        assertEquals("seed + the killed run; no new submit after the commit point", 2, submits())
        assertEquals("no new download after the commit point", downloadsBefore, server.getInt("downloads"))
        Log.i(TAG, "commit scenario: $server")
    }

    private companion object {
        const val TAG = "CANTINO_KILL"
        const val KILL_MARKER = "KILL_ME"
    }
}

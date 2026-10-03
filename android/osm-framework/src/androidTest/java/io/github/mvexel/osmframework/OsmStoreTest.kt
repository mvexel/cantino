package io.github.mvexel.osmframework

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.Executors

/**
 * Device-side counterpart of scripts/mobile-api-smoke.py: the fixture from
 * tests/fixtures goes through import, open, get, query, error recovery and close
 * via JNI on one worker thread.
 */
@RunWith(AndroidJUnit4::class)
class OsmStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().context
    private val target = InstrumentationRegistry.getInstrumentation().targetContext

    private fun importFixture(): File {
        val directory = File(target.cacheDir, "osm-test-${System.nanoTime()}").apply { mkdirs() }
        val input = File(directory, "snapshot.osm")
        context.assets.open("snapshot.osm").use { source -> input.outputStream().use { source.copyTo(it) } }
        val area = File(directory, "area.osmx")
        val report = JSONObject(OsmStore.importArea(input.path, area.path))
        val counts = report.getJSONObject("counts")
        assertEquals(listOf(4, 2, 1), listOf("nodes", "ways", "relations").map(counts::getInt))
        return area
    }

    @Test
    fun importGetQueryCloseOnOneWorkerThread() {
        val worker = Executors.newSingleThreadExecutor()
        try {
            worker.submit {
                val area = importFixture()
                OsmStore.open(area.path).use { store ->
                    val node = JSONObject(store.get(OsmKind.NODE, 1)!!)
                    assertEquals("Mapper", node.getJSONObject("metadata").getString("user"))
                    assertNull(store.get(OsmKind.NODE, 99))

                    val cafes = JSONArray(store.query("""{"tags":[{"Equals":["amenity","cafe"]}]}"""))
                    assertEquals(1, cafes.length())
                    // Non-ASCII survives Java's modified UTF-8 in both directions.
                    assertEquals("Café Test", cafes.getJSONObject(0).getJSONObject("tags").getString("name"))
                    val named = JSONArray(store.query("""{"tags":[{"Equals":["name","Café Test"]}]}"""))
                    assertEquals(1, named.length())

                    assertThrows(OsmFrameworkException::class.java) { store.query("{malformed") }
                    // A failed query leaves the store usable.
                    val way = JSONObject(store.get(OsmKind.WAY, 1)!!)
                    assertEquals(listOf(1L, 2L, 1L), (0 until 3).map { way.getJSONArray("nodes").getLong(it) })
                }
            }.get()
        } finally {
            worker.shutdown()
        }
    }

    @Test
    fun storeRejectsCallsFromAnotherThread() {
        val owner = Executors.newSingleThreadExecutor()
        val other = Executors.newSingleThreadExecutor()
        try {
            val store = owner.submit<OsmStore> { OsmStore.open(importFixture().path) }.get()
            val error = other.submit<Throwable?> {
                runCatching { store.get(OsmKind.NODE, 1) }.exceptionOrNull()
            }.get()
            assertTrue(error is OsmFrameworkException)
            assertTrue(error!!.message!!.contains("different thread"))
            owner.submit { store.close() }.get()
        } finally {
            owner.shutdown()
            other.shutdown()
        }
    }

    @Test
    fun closedStoreFailsFast() {
        val worker = Executors.newSingleThreadExecutor()
        try {
            worker.submit {
                val store = OsmStore.open(importFixture().path)
                store.close()
                store.close() // idempotent
                assertThrows(IllegalStateException::class.java) { store.get(OsmKind.NODE, 1) }
            }.get()
        } finally {
            worker.shutdown()
        }
    }
}

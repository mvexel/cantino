package io.github.mvexel.osmframework

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
        // Three pairs per run exercises the external merge sorter on device too.
        val report = OsmStore.importArea(input.path, area.path, ImportOptions(sortPairs = 3))
        assertEquals(Counts(4, 2, 1), report.counts)
        assertTrue(report.databaseBytes > 0)
        return area
    }

    private fun onWorker(block: () -> Unit) {
        val worker = Executors.newSingleThreadExecutor()
        try {
            worker.submit(block).get()
        } finally {
            worker.shutdown()
        }
    }

    @Test
    fun importGetQueryCloseOnOneWorkerThread() = onWorker {
        OsmStore.open(importFixture().path).use { store ->
            val node = store.get(OsmId(OsmKind.NODE, 1)) as OsmObject.Node
            assertEquals(Metadata(3, node.metadata!!.timestamp, 123, 7, "Mapper"), node.metadata)
            assertEquals(400_000_000, node.latE7)
            assertEquals(-111.0, node.lon, 0.0)
            assertNull(store.get(OsmId(OsmKind.NODE, 99)))

            val cafes = store.query(Query(tags = listOf(TagFilter.Equals("amenity", "cafe"))))
            assertEquals(listOf(OsmId(OsmKind.NODE, 2)), cafes.map { it.id })
            // Non-ASCII survives Java's modified UTF-8 in both directions.
            assertEquals("Café Test", cafes.single().tags["name"])
            assertEquals(1, store.query(Query(tags = listOf(TagFilter.Equals("name", "Café Test")))).size)

            val way = store.get(OsmId(OsmKind.WAY, 1)) as OsmObject.Way
            assertEquals(listOf(1L, 2L, 1L), way.nodes) // order and repeats preserved
            val relation = store.get(OsmId(OsmKind.RELATION, 1)) as OsmObject.Relation
            assertEquals(
                listOf(OsmObject.Member(OsmId(OsmKind.WAY, 1), "outer"), OsmObject.Member(OsmId(OsmKind.NODE, 99), "label")),
                relation.members,
            )
        }
    }

    @Test
    fun paginationAndBbox() = onWorker {
        OsmStore.open(importFixture().path).use { store ->
            val highways = Query(tags = listOf(TagFilter.Exists("highway")), limit = 1)
            val first = store.query(highways)
            val second = store.query(highways.copy(after = first.last().id))
            assertEquals(listOf(OsmId(OsmKind.WAY, 1), OsmId(OsmKind.WAY, 2)), (first + second).map { it.id })
            assertTrue(store.query(highways.copy(after = second.last().id)).isEmpty())

            val box = Bbox(west = -111.0015, south = 40.0005, east = -111.0005, north = 40.0015)
            val inBox = store.query(Query(bbox = box)).map { it.id }
            assertTrue(OsmId(OsmKind.NODE, 2) in inBox)
            assertTrue(OsmId(OsmKind.NODE, 1) !in inBox)
        }
    }

    @Test
    fun invalidQueryFailsAndStoreStaysUsable() = onWorker {
        OsmStore.open(importFixture().path).use { store ->
            assertThrows(OsmFrameworkException::class.java) {
                store.query(Query(bbox = Bbox(west = 10.0, south = 0.0, east = -10.0, north = 1.0)))
            }
            assertEquals(OsmId(OsmKind.WAY, 1), store.get(OsmId(OsmKind.WAY, 1))!!.id)
        }
    }

    @Test
    fun storeRejectsCallsFromAnotherThread() {
        val owner = Executors.newSingleThreadExecutor()
        val other = Executors.newSingleThreadExecutor()
        try {
            val store = owner.submit<OsmStore> { OsmStore.open(importFixture().path) }.get()
            val error = other.submit<Throwable?> {
                runCatching { store.get(OsmId(OsmKind.NODE, 1)) }.exceptionOrNull()
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
    fun closedStoreFailsFast() = onWorker {
        val store = OsmStore.open(importFixture().path)
        store.close()
        store.close() // idempotent
        assertThrows(IllegalStateException::class.java) { store.get(OsmId(OsmKind.NODE, 1)) }
    }
}

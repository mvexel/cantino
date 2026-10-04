package io.github.mvexel.cantino

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
        val area = File(directory, "area.sqlite")
        // Keep untagged-node metadata so node 1's metadata can be checked.
        val report = OsmStore.importArea(input.path, area.path, ImportOptions(preserveUntaggedMetadata = true))
        assertEquals(ObjectCounts(4, 2, 1), report.counts)
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
            assertEquals(ObjectMetadata(3, node.metadata!!.timestampSeconds, 123, 7, "Mapper"), node.metadata)
            assertEquals(400_000_000, node.latE7)
            assertEquals(-111.0, node.lon, 0.0)
            assertNull(store.get(OsmId(OsmKind.NODE, 99)))

            val cafes = store.query(Query(tags = listOf(TagFilter.Equals("amenity", "cafe"))))
            assertEquals(listOf(OsmId(OsmKind.NODE, 2)), cafes.map { it.id })
            // Non-ASCII survives Java's modified UTF-8 in both directions.
            assertEquals("Café Test", cafes.single().tags["name"])
            assertEquals(1, store.query(Query(tags = listOf(TagFilter.Equals("name", "Café Test")))).size)

            val way = store.get(OsmId(OsmKind.WAY, 1)) as OsmObject.Way
            assertEquals(listOf(1L, 2L, 1L), way.nodeIds) // order and repeats preserved
            val relation = store.get(OsmId(OsmKind.RELATION, 1)) as OsmObject.Relation
            assertEquals(
                listOf(OsmObject.Member(OsmId(OsmKind.WAY, 1), "outer"), OsmObject.Member(OsmId(OsmKind.NODE, 99), "label")),
                relation.members,
            )
        }
    }

    @Test
    fun untaggedNodesHaveNoMetadataByDefault() = onWorker {
        val directory = File(target.cacheDir, "osm-test-${System.nanoTime()}").apply { mkdirs() }
        val input = File(directory, "snapshot.osm")
        context.assets.open("snapshot.osm").use { source -> input.outputStream().use { source.copyTo(it) } }
        val area = File(directory, "area.sqlite")
        // File overloads (the other tests use paths).
        OsmStore.importArea(input, area)
        OsmStore.open(area).use { store ->
            val vertex = store.get(OsmId(OsmKind.NODE, 1)) as OsmObject.Node
            assertNull(vertex.metadata)
            assertEquals(3, vertex.locationVersion)
            assertEquals(124L, store.get(OsmId(OsmKind.NODE, 2))!!.metadata!!.changeset)
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
            // Way 2 crosses this box with both of its nodes outside it.
            val crossing = Bbox(west = -111.002, south = 39.999, east = -110.998, north = 40.002)
            assertTrue(OsmId(OsmKind.WAY, 2) in store.query(Query(bbox = crossing)).map { it.id })
        }
    }

    @Test
    fun wayCoordinatesAndRepresentativePoints() = onWorker {
        OsmStore.open(importFixture().path).use { store ->
            // Way 1 is nodes 1, 2, 1: order and the closing repeat survive.
            val node1 = Coordinate(400_000_000, -1_110_000_000)
            val node2 = Coordinate(400_010_000, -1_110_010_000)
            assertEquals(listOf(node1, node2, node1), store.wayCoordinates(1))
            assertEquals(40.001, store.wayCoordinates(1)!![1]!!.lat, 1e-9)
            assertNull(store.wayCoordinates(42)) // not in the area

            assertEquals(node2, store.representativePoint(OsmId(OsmKind.NODE, 2)))
            // Closed way: mean of its distinct vertices (nodes 1 and 2).
            val ring = Coordinate(400_005_000, -1_110_005_000)
            assertEquals(ring, store.representativePoint(OsmId(OsmKind.WAY, 1)))
            // Open way 2 runs from 39.9 to 40.1 along -111: halfway is 40.0.
            assertEquals(Coordinate(400_000_000, -1_110_000_000), store.representativePoint(OsmId(OsmKind.WAY, 2)))
            // Relation 1: way 1 counts, the missing node 99 does not.
            assertEquals(ring, store.representativePoint(OsmId(OsmKind.RELATION, 1)))
            assertNull(store.representativePoint(OsmId(OsmKind.NODE, 99)))
        }
    }

    @Test
    fun batchGetKeepsOrderAndReportsMissing() = onWorker {
        OsmStore.open(importFixture().path).use { store ->
            val ids = listOf(OsmId(OsmKind.WAY, 1), OsmId(OsmKind.NODE, 99), OsmId(OsmKind.NODE, 2), OsmId(OsmKind.WAY, 1))
            val objects = store.get(ids)
            assertEquals(ids.size, objects.size)
            assertEquals(store.get(ids[0]), objects[0])
            assertNull(objects[1])
            assertEquals("Café Test", objects[2]!!.tags["name"])
            assertEquals(objects[0], objects[3])
            assertTrue(store.get(emptyList()).isEmpty())
            val tooMany = List(OsmStore.MAX_BATCH + 1) { OsmId(OsmKind.NODE, 1) }
            assertThrows(CantinoException::class.java) { store.get(tooMany) }
            assertEquals(OsmStore.MAX_BATCH, store.get(tooMany.drop(1)).count { it != null })
        }
    }

    @Test
    fun notExistsFiltersButNeedsADriver() = onWorker {
        OsmStore.open(importFixture().path).use { store ->
            val unnamedHighways = Query(tags = listOf(TagFilter.Exists("highway"), TagFilter.NotExists("name")))
            assertEquals(listOf(OsmId(OsmKind.WAY, 1), OsmId(OsmKind.WAY, 2)), store.query(unnamedHighways).map { it.id })
            val cafesWithoutSeating = Query(
                tags = listOf(TagFilter.Equals("amenity", "cafe"), TagFilter.NotExists("outdoor_seating")),
            )
            assertEquals(listOf(OsmId(OsmKind.NODE, 2)), store.query(cafesWithoutSeating).map { it.id })
            assertTrue(
                store.query(Query(tags = listOf(TagFilter.Exists("amenity"), TagFilter.NotExists("name")))).isEmpty(),
            )
            val error = assertThrows(CantinoException::class.java) {
                store.query(Query(tags = listOf(TagFilter.NotExists("name"))))
            }
            assertTrue(error.message!!.contains("NotExists"))
            // A bbox drives, so NotExists alongside one is fine.
            val box = Bbox(west = -111.002, south = 39.999, east = -110.998, north = 40.002)
            assertTrue(OsmId(OsmKind.WAY, 2) in store.query(Query(tags = listOf(TagFilter.NotExists("name")), bbox = box)).map { it.id })
        }
    }

    @Test
    fun invalidQueryFailsAndStoreStaysUsable() = onWorker {
        OsmStore.open(importFixture().path).use { store ->
            assertThrows(CantinoException::class.java) {
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
            assertTrue(error is CantinoException)
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

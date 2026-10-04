package lol.osm.cantino.inspector

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import lol.osm.cantino.Bbox
import lol.osm.cantino.CantinoException
import lol.osm.cantino.OsmId
import lol.osm.cantino.OsmKind
import lol.osm.cantino.OsmObject
import lol.osm.cantino.OsmStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The app logic on a real store, imported from the shared fixture
 * (src/androidTest/assets/inspector.osm; iOS reads the same file).
 * Same cases as ios/inspector-app InspectTests.
 */
@RunWith(AndroidJUnit4::class)
class InspectTest {
    private val context = InstrumentationRegistry.getInstrumentation().context
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directory: File
    private lateinit var store: OsmStore

    @Before
    fun open() {
        directory = File(target.cacheDir, "inspector-${System.nanoTime()}").apply { mkdirs() }
        val input = File(directory, "inspector.osm")
        context.assets.open("inspector.osm").use { source -> input.outputStream().use { source.copyTo(it) } }
        val area = File(directory, "area.sqlite")
        OsmStore.importArea(input, area)
        store = OsmStore.open(area)
    }

    @After
    fun close() {
        store.close()
        directory.deleteRecursively()
    }

    private fun ids(candidates: List<Candidate>) = candidates.map { "${it.match.name.lowercase()} ${Labels.kindId(it.id)}" }

    @Test
    fun tapOnACrossingHitsTheNodeAndBothWays() {
        val result = Inspect.tap(store, LatLon(40.0, -111.0), 15.0)
        assertEquals(listOf("hit node 1", "hit way 10", "hit way 12"), ids(result.candidates))
        assertTrue(result.candidates.all { it.distanceMeters == 0.0 })
        assertEquals(false, result.truncated)
    }

    @Test
    fun tapMidSidewalkFindsTheWayNotAVertex() {
        // No vertex within 150 m: untagged vertices are never candidates, the way's line is.
        val result = Inspect.tap(store, LatLon(40.0020, -111.0000), 15.0)
        assertEquals(listOf("hit way 11", "near relation 30"), ids(result.candidates))
        assertEquals(0.0, result.candidates[0].distanceMeters!!, 0.01)
    }

    @Test
    fun tapInsideAParkIsNearOnly() {
        val result = Inspect.tap(store, LatLon(40.0060, -111.0000), 15.0)
        assertEquals(listOf("near way 20", "near relation 30"), ids(result.candidates))
        assertEquals(255.5, result.candidates[0].distanceMeters!!, 1.0) // to the park's east and west edges
    }

    @Test
    fun waysUsingANode() {
        val crossing = store.get(OsmId(OsmKind.NODE, 1)) as OsmObject.Node
        assertEquals(listOf(10L, 12L), Inspect.parentWays(store, crossing).map { it.id.id })
        val kerb = store.get(OsmId(OsmKind.NODE, 5)) as OsmObject.Node
        assertEquals(listOf(12L), Inspect.parentWays(store, kerb).map { it.id.id })
        val cafe = store.get(OsmId(OsmKind.NODE, 3)) as OsmObject.Node
        assertEquals(emptyList<Long>(), Inspect.parentWays(store, cafe).map { it.id.id })
    }

    @Test
    fun missingReferencesAndBrokenGeometry() {
        val way = Inspect.detail(store, OsmId(OsmKind.WAY, 40))
        assertEquals(2, way.missingReferences)
        assertEquals(6, way.references)
        assertEquals(listOf(listOf(41L, 42L), listOf(43L, 44L)).map { it.size }, way.shape.lines.map { it.size })
        assertEquals(LatLon(40.01, -111.003), way.shape.lines[0][1])
        assertEquals(listOf(true, true, false, false, true, true), way.nodeRefs.map { it.location != null })

        val sidewalk = Inspect.detail(store, OsmId(OsmKind.WAY, 10))
        assertEquals(listOf(false, false, true, false), sidewalk.nodeRefs.map { it.tagged })
        assertEquals(0, sidewalk.missingReferences)

        val relation = Inspect.detail(store, OsmId(OsmKind.RELATION, 30))
        assertEquals(1, relation.missingReferences)
        assertEquals(3, relation.references)
        assertEquals(
            listOf("1. outer · way 20 · Test Park", "2. inner · way 998 · not in this area", "3. (no role) · node 4 · amenity=bench"),
            relation.members.mapIndexed { i, row -> row.text(i) },
        )
        assertEquals(1, relation.shape.lines.size)
        assertEquals(1, relation.shape.points.size)

        val node = Inspect.detail(store, OsmId(OsmKind.NODE, 1))
        assertEquals(listOf(10L, 12L), node.parentWays.map { it.id.id })
        assertEquals(Inspect.detail(store, OsmId(OsmKind.NODE, 999)).obj, null)
    }

    @Test
    fun checksCountOnTheFixture() {
        fun count(query: String, bbox: Bbox? = null) = Queries.count(store, QueryBar.parse(query), bbox)
        assertEquals(KindCounts(nodes = 1), count("amenity=* !opening_hours"))
        assertEquals(KindCounts(nodes = 1), count("highway=crossing !crossing"))
        assertEquals(KindCounts(nodes = 1), count("highway=crossing crossing=*"))
        assertEquals(KindCounts(ways = 1), count("footway=sidewalk !surface"))
        assertEquals(KindCounts(ways = 2), count("highway=footway footway=sidewalk"))
        assertEquals(KindCounts(nodes = 1), count("barrier=kerb !kerb"))
        assertEquals(KindCounts(ways = 1), count("highway=steps"))
        assertEquals(KindCounts(), count("amenity=Cafe")) // exact, case-sensitive
        // A lone !key drives nothing: Cantino rejects it without a bbox and accepts it with one.
        try {
            count("!opening_hours")
            fail("a lone NotExists without a bbox must be rejected")
        } catch (e: CantinoException.InvalidArgument) {
            assertTrue(e.message!!.isNotEmpty())
        }
        assertEquals(KindCounts(nodes = 1, ways = 2), count("!name", Geo.around(LatLon(40.0, -111.0), 15.0)))
    }

    @Test
    fun pagesWithAfter() {
        val filters = QueryBar.parse("highway=*")
        val seen = mutableListOf<OsmId>()
        var after: OsmId? = null
        while (true) {
            val page = Queries.page(store, filters, null, after, limit = 2)
            seen += page.map { it.id }
            if (page.size < 2) break
            after = page.last().id
        }
        assertEquals(
            listOf("node 1", "node 2", "way 10", "way 11", "way 12", "way 40", "way 50"),
            seen.map(Labels::kindId),
        )
    }
}

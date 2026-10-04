package lol.osm.cantino.cafe

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import lol.osm.cantino.Bbox
import lol.osm.cantino.OsmId
import lol.osm.cantino.OsmKind
import lol.osm.cantino.OsmObject
import lol.osm.cantino.OsmStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RelationCafeTest {
    private val context = InstrumentationRegistry.getInstrumentation().context
    private val target = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun relationCafeQueryDetailRowsAndPresentMemberResolve() {
        val directory = File(target.cacheDir, "cafe-relation-${System.nanoTime()}").apply { mkdirs() }
        val input = File(directory, "cafe-relation.osm")
        context.assets.open("cafe-relation.osm").use { source -> input.outputStream().use { source.copyTo(it) } }
        val area = File(directory, "area.sqlite")
        OsmStore.importArea(input, area)

        OsmStore.open(area).use { store ->
            // The list path: the relation café is found and placed on the map.
            val cafes = CafeLoader.load(store, Bbox(-111.01, 39.99, -110.99, 40.01))
            assertEquals(listOf(OsmId(OsmKind.RELATION, 301)), cafes.map { it.id })
            assertEquals("Relation Café", cafes.single().name)
            assertNotNull(cafes.single().location)

            // The detail path: member rows as DetailActivity renders them.
            val relation = store.get(cafes.single().id) as OsmObject.Relation
            val rows = relationMemberRows(store, relation)
            assertEquals(listOf(OsmId(OsmKind.WAY, 201) to "outer", OsmId(OsmKind.WAY, 999) to "inner"), rows.map { it.member.id to it.member.role })
            assertEquals("1. outer · way 201", rows[0].label(0))
            assertEquals("2. inner · way 999 · not in this area", rows[1].label(1))

            // A present member resolves to its object (the row's link target).
            assertTrue(rows[0].present)
            assertFalse(rows[1].present)
            assertEquals(listOf(101L, 102L, 103L, 104L, 101L), (store.get(rows[0].member.id) as OsmObject.Way).nodeIds)
        }
    }
}

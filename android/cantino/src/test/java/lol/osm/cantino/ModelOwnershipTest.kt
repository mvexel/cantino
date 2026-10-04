package lol.osm.cantino

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelOwnershipTest {
    @Test
    fun objectsOwnCollectionsAndKeepStableValueSemantics() {
        val tags = mutableMapOf("name" to "Café")
        val nodes = mutableListOf(1L, 2L, 1L)
        val members = mutableListOf(OsmObject.Member(OsmId(OsmKind.WAY, 10), "outer"))
        val node = OsmObject.Node(OsmId(OsmKind.NODE, 1), 1, 2, 1, tags, null)
        val way = OsmObject.Way(OsmId(OsmKind.WAY, 10), nodes, tags, null)
        val relation = OsmObject.Relation(OsmId(OsmKind.RELATION, 20), members, tags, null)
        val hashes = listOf(node.hashCode(), way.hashCode(), relation.hashCode())

        tags.clear()
        nodes.clear()
        members.clear()
        assertEquals("Café", node.tags["name"])
        assertEquals("Café", way.tags["name"])
        assertEquals("Café", relation.tags["name"])
        assertEquals(listOf(1L, 2L, 1L), way.nodeIds)
        assertEquals(listOf(OsmObject.Member(way.id, "outer")), relation.members)
        assertEquals(hashes, listOf(node.hashCode(), way.hashCode(), relation.hashCode()))
        assertEquals(way, OsmObject.Way(way.id, way.nodeIds, way.tags, null))
        assertTrue(runCatching { (way.nodeIds as MutableList<Long>).clear() }.exceptionOrNull() is UnsupportedOperationException)
        assertTrue(runCatching { (node.tags as MutableMap<String, String>).clear() }.exceptionOrNull() is UnsupportedOperationException)
    }
}

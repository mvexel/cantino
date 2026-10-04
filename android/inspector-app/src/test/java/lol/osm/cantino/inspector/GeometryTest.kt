package lol.osm.cantino.inspector

import lol.osm.cantino.OsmId
import lol.osm.cantino.OsmKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hit testing, distances and candidate ordering. Same cases as ios/inspector-app GeometryTests. */
class GeometryTest {
    private val tap = LatLon(40.0, -111.0)
    // At latitude 40, 0.0001° of latitude ≈ 11.12 m and 0.0001° of longitude ≈ 8.52 m.
    private val east10m = LatLon(40.0, -111.0 + 10 / 85_175.6)
    private val north10m = LatLon(40.0 + 10 / 111_194.9, -111.0)

    @Test
    fun pointToSegmentDistances() {
        // Perpendicular foot inside the segment: the distance to the line.
        val a = LatLon(40.0 + 20 / 111_194.9, -111.0 - 0.001)
        val b = LatLon(40.0 + 20 / 111_194.9, -111.0 + 0.001)
        assertEquals(20.0, Geo.pointToSegmentMeters(tap, a, b), 0.05)
        // Foot outside the segment: the distance to the nearer end.
        assertEquals(10.0, Geo.pointToSegmentMeters(tap, east10m, LatLon(40.0, -110.99)), 0.05)
        // A degenerate segment is a point.
        assertEquals(10.0, Geo.pointToSegmentMeters(tap, north10m, north10m), 0.05)
        // Haversine and the flat projection agree at tap scale.
        assertEquals(Geo.distanceMeters(tap, east10m), Geo.pointToSegmentMeters(tap, east10m, east10m), 0.01)
    }

    @Test
    fun noSegmentIsMeasuredAcrossAMissingVertex() {
        // A line passing right through the tap, but its middle vertex is outside the area.
        val west = LatLon(40.0, -111.001)
        val east = LatLon(40.0, -110.999)
        assertEquals(0.0, Geo.distanceToLineMeters(tap, listOf(west, east))!!, 1e-6)
        val gapped = Geo.distanceToLineMeters(tap, listOf(west, null, east))!!
        assertEquals(Geo.distanceMeters(tap, west), gapped, 0.05) // only the vertices count
        assertNull(Geo.distanceToLineMeters(tap, listOf(null, null)))
        assertNull(Geo.distanceToLineMeters(tap, emptyList()))
    }

    @Test
    fun segmentsSplitAtMissingVertices() {
        val p = (1..5).map { LatLon(40.0, -111.0 + it * 0.001) }
        assertEquals(listOf(p), Geo.segments(p))
        assertEquals(listOf(listOf(p[0], p[1]), listOf(p[3], p[4])), Geo.segments(listOf(p[0], p[1], null, p[3], p[4])))
        assertEquals(listOf(listOf(p[1]), listOf(p[3])), Geo.segments(listOf(null, p[1], null, null, p[3], null)))
        assertEquals(emptyList<List<LatLon>>(), Geo.segments(listOf(null, null)))
    }

    @Test
    fun hitOrNearByKind() {
        val node = OsmId(OsmKind.NODE, 1)
        assertEquals(Match.HIT, HitTest.node(node, emptyMap(), east10m, tap, 15.0).match)
        assertEquals(Match.NEAR, HitTest.node(node, emptyMap(), east10m, tap, 5.0).match)

        val way = OsmId(OsmKind.WAY, 2)
        // The middle of a long segment, far from both (untagged) vertices: a hit.
        val sidewalk = listOf(LatLon(40.0, -111.002), LatLon(40.0, -110.998))
        val mid = HitTest.way(way, emptyMap(), sidewalk, tap, 15.0)
        assertEquals(Match.HIT, mid.match)
        assertEquals(0.0, mid.distanceMeters!!, 1e-6)
        // Inside a large closed way (a park): near only, no point-in-polygon test.
        val park = listOf(LatLon(39.99, -111.01), LatLon(39.99, -110.99), LatLon(40.01, -110.99), LatLon(40.01, -111.01), LatLon(39.99, -111.01))
        val inside = HitTest.way(way, emptyMap(), park, tap, 15.0)
        assertEquals(Match.NEAR, inside.match)
        assertTrue(inside.distanceMeters!! > 800)
        // No vertex in the area: near, distance unknown.
        assertEquals(Candidate(way, emptyMap(), Match.NEAR, null), HitTest.way(way, emptyMap(), listOf(null), tap, 15.0))

        assertEquals(Candidate(OsmId(OsmKind.RELATION, 3), emptyMap(), Match.NEAR, null), HitTest.relation(OsmId(OsmKind.RELATION, 3), emptyMap()))
    }

    @Test
    fun candidatesOrderHitsFirstThenNearestThenKindAndId() {
        fun c(kind: OsmKind, id: Long, match: Match, d: Double?) = Candidate(OsmId(kind, id), emptyMap(), match, d)
        val ordered = HitTest.order(
            listOf(
                c(OsmKind.RELATION, 1, Match.NEAR, null),
                c(OsmKind.WAY, 9, Match.NEAR, 40.0),
                c(OsmKind.WAY, 5, Match.HIT, 0.0),
                c(OsmKind.NODE, 7, Match.HIT, 0.0),
                c(OsmKind.NODE, 3, Match.HIT, 0.0),
                c(OsmKind.WAY, 4, Match.HIT, 2.5),
                c(OsmKind.WAY, 8, Match.NEAR, null),
            ),
        )
        assertEquals(
            listOf("node 3", "node 7", "way 5", "way 4", "way 9", "way 8", "relation 1"),
            ordered.map { Labels.kindId(it.id) },
        )
    }

    @Test
    fun aroundIsASquareOfTheRadius() {
        val box = Geo.around(tap, 15.0)
        assertEquals(15.0, Geo.distanceMeters(tap, LatLon(box.north, tap.lon)), 0.01)
        assertEquals(15.0, Geo.distanceMeters(tap, LatLon(tap.lat, box.east)), 0.01)
        assertTrue(Geo.contains(box, tap))
    }

    @Test
    fun labelsAndPaths() {
        assertEquals("highway=crossing", Labels.primaryTag(mapOf("crossing" to "marked", "highway" to "crossing")))
        assertEquals("amenity=cafe", Labels.primaryTag(mapOf("name" to "X", "amenity" to "cafe", "building" to "yes")))
        assertEquals("colour=red", Labels.primaryTag(mapOf("name" to "X", "colour" to "red")))
        assertEquals("untagged", Labels.primaryTag(emptyMap()))
        assertEquals(OsmId(OsmKind.WAY, 123), Labels.parsePath("way/123"))
        assertNull(Labels.parsePath("way/-1"))
        assertNull(Labels.parsePath("area/1"))
        assertEquals("https://www.openstreetmap.org/node/42", Labels.osmOrgUrl(OsmId(OsmKind.NODE, 42)))
        assertEquals("4.2 m", Geo.formatDistance(4.24))
        assertEquals("42 m", Geo.formatDistance(42.9))
        assertEquals("1.5 km", Geo.formatDistance(1500.0))
    }
}

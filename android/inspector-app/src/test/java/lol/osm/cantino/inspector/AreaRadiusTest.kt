package lol.osm.cantino.inspector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Same cases as ios/inspector-app InspectorAppTests/AreaRadiusTests.swift and the café app's AreaRadiusTest. */
class AreaRadiusTest {
    private val slc = LatLon(40.7608, -111.8910)

    @Test
    fun radiusIsHalfTheSideOfTheSquare() {
        val box = AreaRadius.bbox(slc, 2.5)
        // 5 km north-south at 111.32 km per degree of latitude.
        assertEquals(5.0 / 111.32, box.north - box.south, 1e-9)
        assertEquals(slc.lat, (box.north + box.south) / 2, 1e-9)
        assertEquals(slc.lon, (box.west + box.east) / 2, 1e-9)
        // The edges are 2.5 km from the centre (flat-earth approximation, well within 1 %).
        assertEquals(2500.0, Geo.distanceMeters(slc, LatLon(box.north, slc.lon)), 25.0)
        assertEquals(2500.0, Geo.distanceMeters(slc, LatLon(slc.lat, box.east)), 25.0)
    }

    @Test
    fun defaultIsAChoice() {
        assertEquals(listOf(1.0, 2.5, 5.0, 10.0), AreaRadius.CHOICES_KM)
        assertEquals(2.5, AreaRadius.DEFAULT_KM, 0.0)
        assertTrue(AreaRadius.DEFAULT_KM in AreaRadius.CHOICES_KM)
    }

    @Test
    fun launchOptionMustBeAChoice() {
        assertEquals(2.5, AreaRadius.choice(2.5)!!, 0.0)
        assertEquals(10.0, AreaRadius.choice(10.0)!!, 0.0)
        assertNull(AreaRadius.choice(3.0))
        assertNull(AreaRadius.choice(0.0))
        assertNull(AreaRadius.choice(Double.NaN))
        assertNull(AreaRadius.choice(null))
    }

    @Test
    fun labels() {
        assertEquals(listOf("1 km", "2.5 km", "5 km", "10 km"), AreaRadius.CHOICES_KM.map(AreaRadius::label))
        assertEquals("5 × 5 km", AreaRadius.sideLabel(2.5))
        assertEquals("20 × 20 km", AreaRadius.sideLabel(10.0))
    }

    @Test
    fun chooserParsesLatLon() {
        assertEquals(slc, parseLatLon("40.7608,-111.8910"))
        assertEquals(slc, parseLatLon(" 40.7608 , -111.8910 "))
        assertNull(parseLatLon("40.7608"))
        assertNull(parseLatLon("91,0"))
        assertNull(parseLatLon("a,b"))
    }
}

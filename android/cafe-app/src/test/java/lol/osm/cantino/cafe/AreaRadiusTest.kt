package lol.osm.cantino.cafe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Same cases as ios/cafe-app CafeAppTests/AreaRadiusTests.swift and the Inspector's AreaRadiusTest. */
class AreaRadiusTest {
    private val slc = LatLon(40.7608, -111.8910)

    @Test
    fun radiusIsHalfTheSideOfTheSquare() {
        val box = AreaRadius.bbox(slc, 5.0)
        // 10 km north-south at 111.32 km per degree of latitude.
        assertEquals(10.0 / 111.32, box.north - box.south, 1e-9)
        assertEquals(slc.lat, (box.north + box.south) / 2, 1e-9)
        assertEquals(slc.lon, (box.west + box.east) / 2, 1e-9)
        // The edges are 5 km from the centre (flat-earth approximation, well within 1 %).
        assertEquals(5000.0, Geo.distanceMeters(slc, LatLon(box.north, slc.lon)), 50.0)
        assertEquals(5000.0, Geo.distanceMeters(slc, LatLon(slc.lat, box.east)), 50.0)
    }

    @Test
    fun radiusRoundTripsThroughTheBbox() {
        AreaRadius.CHOICES_KM.forEach { assertEquals(it, AreaRadius.of(AreaRadius.bbox(slc, it)), 1e-9) }
    }

    @Test
    fun defaultIsAChoice() {
        assertEquals(listOf(1.0, 2.5, 5.0, 10.0), AreaRadius.CHOICES_KM)
        assertEquals(5.0, AreaRadius.DEFAULT_KM, 0.0)
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
        assertEquals("10 × 10 km", AreaRadius.sideLabel(5.0))
        assertEquals("5 × 5 km", AreaRadius.sideLabel(2.5))
        assertEquals("2 × 2 km", AreaRadius.sideLabel(1.0))
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

package lol.osm.cantino

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BboxAroundTest {
    @Test
    fun squareAtEquatorIsSymmetric() {
        val box = Bbox.around(0.0, 10.0, 11.132)
        assertEquals(10.0 - 0.05, box.west, 1e-9)
        assertEquals(10.0 + 0.05, box.east, 1e-9)
        assertEquals(-0.05, box.south, 1e-9)
        assertEquals(0.05, box.north, 1e-9)
    }

    @Test
    fun longitudeSpanWidensWithLatitude() {
        val box = Bbox.around(60.0, 5.0, 10.0)
        // cos(60) = 0.5: a degree of longitude is half as long, so twice the degrees.
        assertEquals(2 * (box.north - box.south), box.east - box.west, 1e-9)
    }

    @Test
    fun widthAndHeightAreIndependent() {
        val box = Bbox.around(0.0, 0.0, widthKm = 22.264, heightKm = 11.132)
        assertEquals(0.2, box.east - box.west, 1e-9)
        assertEquals(0.1, box.north - box.south, 1e-9)
    }

    @Test
    fun latitudeIsClamped() {
        val box = Bbox.around(84.9, 0.0, 100.0)
        assertEquals(85.0, box.north, 0.0)
        val south = Bbox.around(-90.0, 0.0, 10.0)
        assertEquals(-85.0, south.south, 0.0)
        assertTrue(south.west.isFinite() && south.east.isFinite())
    }

    @Test
    fun antimeridianIsClampedNotWrapped() {
        val box = Bbox.around(0.0, 179.99, 100.0)
        assertEquals(180.0, box.east, 0.0)
        assertTrue(box.west < box.east)
        val west = Bbox.around(0.0, -179.99, 100.0)
        assertEquals(-180.0, west.west, 0.0)
        assertTrue(west.west < west.east)
    }

    @Test
    fun invalidArgumentsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { Bbox.around(91.0, 0.0, 1.0) }
        assertThrows(IllegalArgumentException::class.java) { Bbox.around(0.0, 181.0, 1.0) }
        assertThrows(IllegalArgumentException::class.java) { Bbox.around(Double.NaN, 0.0, 1.0) }
        assertThrows(IllegalArgumentException::class.java) { Bbox.around(0.0, 0.0, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { Bbox.around(0.0, 0.0, 1.0, -1.0) }
    }
}

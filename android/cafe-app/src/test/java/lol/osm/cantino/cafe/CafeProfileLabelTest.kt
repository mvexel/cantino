package lol.osm.cantino.cafe

import lol.osm.cantino.ImportProfile
import lol.osm.cantino.KeepRule
import lol.osm.cantino.OsmKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CafeProfileLabelTest {
    @Test
    fun poiProfileHasTheEightDocumentedKeysOnAllKinds() {
        assertEquals(
            listOf("amenity", "shop", "tourism", "leisure", "craft", "office", "healthcare", "historic"),
            CafeProfile.POI.keep.map { it.key },
        )
        assertTrue(CafeProfile.POI.keep.all { it.kinds == OsmKind.entries.toSet() && it.values == null })
    }

    @Test
    fun labelsNameTheAreasActualProfile() {
        assertEquals(null, CafeProfile.label(null))
        assertEquals("points of interest only", CafeProfile.label(CafeProfile.POI))
        assertEquals("filtered: highway", CafeProfile.label(ImportProfile(listOf(KeepRule(setOf(OsmKind.WAY), "highway")))))
    }

    @Test
    fun notFoundTextNamesBothCausesAndTheProfileWhenKnown() {
        assertTrue(notFoundText(null).startsWith("Not in this area or filtered out: "))
        assertTrue(notFoundText(null).endsWith("or the import profile did not keep it."))
        assertTrue(notFoundText(CafeProfile.POI).endsWith("or the import profile (points of interest only) did not keep it."))
    }
}

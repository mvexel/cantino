package lol.osm.cantino.cafe

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import lol.osm.cantino.Bbox
import lol.osm.cantino.OsmId
import lol.osm.cantino.OsmKind
import lol.osm.cantino.OsmStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The app's import profile is applied: POIs stay, everything else is gone. */
@RunWith(AndroidJUnit4::class)
class CafeProfileTest {
    private val context = InstrumentationRegistry.getInstrumentation().context
    private val target = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun poiProfileKeepsCafesAndDropsStreets() {
        val directory = File(target.cacheDir, "cafe-profile-${System.nanoTime()}").apply { mkdirs() }
        try {
            val input = File(directory, "snapshot.osm")
            context.assets.open("snapshot.osm").use { source -> input.outputStream().use { source.copyTo(it) } }
            val area = File(directory, "area.sqlite")
            // The options AreaManager gets from CafeProfile.AREA_CONFIG.
            assertEquals(CafeProfile.IMPORT_OPTIONS, CafeProfile.AREA_CONFIG.importOptions)
            val report = OsmStore.importArea(input, area, CafeProfile.IMPORT_OPTIONS)

            // The report records the app's profile; the map's status line reads it.
            assertEquals(CafeProfile.POI, report.profile)
            assertEquals("points of interest only", CafeProfile.label(report.profile))
            // Only the café node is kept: no way or relation has a POI key.
            assertEquals(1L, report.counts.nodes)
            assertEquals(0L, report.counts.ways)
            assertEquals(0L, report.counts.relations)

            OsmStore.open(area).use { store ->
                assertEquals(listOf(OsmId(OsmKind.NODE, 2)), CafeLoader.load(store, Bbox(-111.1, 39.8, -110.9, 40.2)).map { it.id })
                assertNotNull(store.get(OsmId(OsmKind.NODE, 2)))
                // highway=path and its untagged nodes: filtered out.
                assertNull(store.get(OsmId(OsmKind.WAY, 2)))
                assertNull(store.get(OsmId(OsmKind.NODE, 3)))
                // highway=service, although it uses the café node.
                assertNull(store.get(OsmId(OsmKind.WAY, 1)))
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}

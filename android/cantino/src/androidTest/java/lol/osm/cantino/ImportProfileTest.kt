package lol.osm.cantino

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Import profiles through JNI, on tests/fixtures/profile.osm (see
 * tests/profile.rs for what each object is there for).
 */
@RunWith(AndroidJUnit4::class)
class ImportProfileTest {
    private val context = InstrumentationRegistry.getInstrumentation().context
    private val target = InstrumentationRegistry.getInstrumentation().targetContext

    private val profile = ImportProfile(
        listOf(
            KeepRule(setOf(OsmKind.RELATION, OsmKind.NODE, OsmKind.WAY), "amenity", listOf("cafe")),
            KeepRule(setOf(OsmKind.WAY), "shop"),
        ),
    )

    private fun fixture(): Pair<File, File> {
        val directory = File(target.cacheDir, "profile-${System.nanoTime()}").apply { mkdirs() }
        val input = File(directory, "profile.osm")
        context.assets.open("profile.osm").use { source -> input.outputStream().use { source.copyTo(it) } }
        return input to File(directory, "area.sqlite")
    }

    @Test
    fun profileFiltersWithReferenceClosureAndIsReported() {
        val (input, area) = fixture()
        val report = OsmStore.importArea(input, area, ImportOptions(profile = profile))
        assertEquals(ObjectCounts(7, 2, 3), report.counts)
        assertEquals(profile, report.profile)
        OsmStore.open(area).use { store ->
            assertNotNull(store.get(OsmId(OsmKind.WAY, 12))) // kept as a member of relation 20
            assertNull(store.get(OsmId(OsmKind.WAY, 11))) // only in an unkept relation
            assertNull(store.get(OsmId(OsmKind.NODE, 10))) // shop rule is for ways only
            val cafes = store.query(Query(tags = listOf(TagFilter.Equals("amenity", "cafe"))))
            assertEquals(listOf(OsmId(OsmKind.NODE, 1), OsmId(OsmKind.RELATION, 20)), cafes.map { it.id })
        }
        // The report round-trips through the area sidecar's JSON.
        assertEquals(report, ImportReport.fromJson(report.toJson()))
    }

    @Test
    fun noProfileImportsEverything() {
        val (input, area) = fixture()
        val report = OsmStore.importArea(input, area)
        assertEquals(ObjectCounts(10, 3, 4), report.counts)
        assertNull(report.profile)
    }

    @Test
    fun invalidProfileIsInvalidArgument() {
        val (input, area) = fixture()
        assertThrows(CantinoException.InvalidArgument::class.java) {
            OsmStore.importArea(input, area, ImportOptions(profile = ImportProfile(emptyList())))
        }
        assertThrows(CantinoException.InvalidArgument::class.java) {
            OsmStore.importArea(input, area, ImportOptions(profile = ImportProfile(listOf(KeepRule(emptySet(), "amenity")))))
        }
    }

    @Test
    fun profileSurvivesTheWorkRequest() {
        val request = AreaDownloadWorker.DownloadRequest(
            "area",
            Bbox(-111.0, 40.0, -110.99, 40.01),
            "test",
            AreaConfig(importOptions = ImportOptions(profile = profile)),
        )
        assertEquals(profile, AreaDownloadWorker.DownloadRequest.fromData(request.toData()).config.importOptions.profile)
        assertNull(
            AreaDownloadWorker.DownloadRequest.fromData(request.copy(config = AreaConfig()).toData()).config.importOptions.profile,
        )
        // Wire form matches the Rust core's (kinds in n, w, r order).
        assertEquals("nwr", JSONObject(ImportOptions(profile = profile).toJson()).getJSONObject("profile").getJSONArray("keep").getJSONObject(0).getString("kinds"))
    }
}

package lol.osm.cantino

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Publication consistency through the core's area store, without downloads
 * or the importer. Reader/commit serialization is tested in the core
 * (`src/area_storage/tests.rs`), which reads under its own lock.
 */
@RunWith(AndroidJUnit4::class)
class AreaStorageTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val areaId = "storage-${UUID.randomUUID()}"
    private val storage = AreaStorage(context).apply { prepare(areaId) }
    private val areas = File(context.filesDir, "cantino-areas")

    @After fun cleanup() {
        AreaTestHooks.afterCommitPoint = null
        listOf("sqlite", "pmtiles", "json", "commit", "lock").forEach { File(areas, "$areaId.$it").deleteRecursively() }
        File(areas, ".staging/$areaId").deleteRecursively()
        File(context.noBackupFilesDir, "cantino-area-downloads/$areaId").deleteRecursively()
    }

    private fun stage(name: String): UUID {
        val run = UUID.randomUUID()
        storage.prepareStaging(areaId, run)
        val data = storage.stagedArea(areaId, run).apply { writeText(name) }
        val basemap = storage.stagedBasemap(areaId, run).apply { writeText("map-$name") }
        storage.writeStagedMetadata(areaId, run, AreaMetadata(
            Bbox(-1.0, -1.0, 1.0, 1.0), name, null, 1L,
            ImportReport(ObjectCounts(0, 0, 0), data.length()),
            BasemapMetadata(BasemapKind.URL, "https://example.com/map.pmtiles", basemap.length(), 1, 0, 1, 1, basemap.length()),
            run,
        ))
        return run
    }

    @Test fun failedRecoveryDoesNotExposeMixedFilesAndKeepsJournalForRetry() {
        val old = stage("old")
        storage.commit(areaId, old, true) {}
        val next = stage("new-version")
        // After the durable journal, force the data rename to fail. The
        // basemap rename succeeds first, leaving a genuinely partial commit.
        AreaTestHooks.afterCommitPoint = {
            check(storage.dataFile(areaId).delete())
            check(storage.dataFile(areaId).mkdir())
            File(storage.dataFile(areaId), "obstruction").writeText("cannot replace directory")
        }
        expectIo { storage.commit(areaId, next, true) {} }
        AreaTestHooks.afterCommitPoint = null
        assertEquals("map-new-version", storage.basemapFile(areaId).readText())
        expectIo { storage.recover(areaId) }
        expectIo { storage.published(areaId) }
        assertTrue(File(areas, "$areaId.commit").isFile)
        assertTrue(storage.stagedArea(areaId, next).isFile)
        storage.dataFile(areaId).deleteRecursively()
        val recovered = storage.published(areaId)!!
        assertEquals(next, recovered.metadata!!.workId)
        assertEquals("new-version", recovered.dataFile.readText())
        assertFalse(File(areas, "$areaId.commit").exists())
    }

    @Test fun malformedJournalFailsClosedButManualImportNeedsNoSidecar() {
        storage.dataFile(areaId).writeText("manual import")
        assertNull(storage.published(areaId)!!.metadata)
        val journal = File(areas, "$areaId.commit")
        journal.writeText("broken journal")
        expectIo { storage.published(areaId) }
        assertTrue(journal.isFile)
    }

    private fun expectIo(action: () -> Unit) {
        try {
            action()
            fail("expected storage failure")
        } catch (_: IOException) {
        }
    }
}

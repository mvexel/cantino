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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Publication consistency, without involving downloads or the native importer. */
@RunWith(AndroidJUnit4::class)
class AreaStorageTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val areaId = "storage-${UUID.randomUUID()}"
    private val storage = AreaStorage(context).apply { prepare(areaId) }
    private val areas = File(context.filesDir, "cantino-areas")
    private val executor = Executors.newFixedThreadPool(2)

    @After fun cleanup() {
        AreaTestHooks.afterRecovery = null
        AreaTestHooks.afterCommitPoint = null
        executor.shutdownNow()
        executor.awaitTermination(5, TimeUnit.SECONDS)
        listOf("sqlite", "pmtiles", "json", "commit").forEach { File(areas, "$areaId.$it").deleteRecursively() }
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

    @Test fun publicationWaitsUntilReaderHasReadTheWholeSnapshot() {
        val old = stage("old")
        storage.commit(areaId, old, true) {}
        val next = stage("new-version")
        val reading = CountDownLatch(1)
        val releaseReader = CountDownLatch(1)
        val writerStarted = CountDownLatch(1)
        val committing = CountDownLatch(1)
        AreaTestHooks.afterRecovery = {
            reading.countDown()
            check(releaseReader.await(5, TimeUnit.SECONDS))
        }
        val read = executor.submit<AreaInfo?> { storage.published(areaId) }
        assertTrue(reading.await(5, TimeUnit.SECONDS))
        val write = executor.submit {
            writerStarted.countDown()
            storage.commit(areaId, next, true) { committing.countDown() }
        }
        try {
            assertTrue(writerStarted.await(5, TimeUnit.SECONDS))
            assertFalse("commit entered while reader still reads metadata", committing.await(150, TimeUnit.MILLISECONDS))
        } finally {
            releaseReader.countDown()
        }
        assertEquals(old, read.get(5, TimeUnit.SECONDS)!!.metadata!!.workId)
        write.get(5, TimeUnit.SECONDS)
        AreaTestHooks.afterRecovery = null
        assertEquals(next, storage.published(areaId)!!.metadata!!.workId)
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

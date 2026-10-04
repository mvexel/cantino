package lol.osm.cantino.cafe

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import lol.osm.cantino.OsmId
import lol.osm.cantino.OsmKind
import lol.osm.cantino.OsmStore
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CafeStoreTest {
    @Test
    fun refreshCannotCloseASelectedStoreBeforeItsRead() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = File(instrumentation.targetContext.cacheDir, "refresh-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
            override fun getNoBackupFilesDir(): File = File(root, "scratch")
        }
        val areas = File(root, "cantino-areas").apply { mkdirs() }
        val selected = CompletableDeferred<Unit>()
        val resumeRead = CompletableDeferred<Unit>()
        fun publish(fixture: String) {
            val input = File(root, fixture)
            instrumentation.context.assets.open(fixture).use { source -> input.outputStream().use { source.copyTo(it) } }
            val output = File(areas, "${CafeStore.AREA_ID}.sqlite")
            val report = OsmStore.importArea(input, output)
            File(areas, "${CafeStore.AREA_ID}.json").writeText(JSONObject()
                .put("bbox", JSONObject().put("west", -112).put("south", 39).put("east", -110).put("north", 41))
                .put("name", "refresh test")
                .put("snapshot_timestamp", JSONObject.NULL)
                .put("imported_at_millis", System.currentTimeMillis())
                .put("work_id", UUID.randomUUID().toString())
                .put("basemap", JSONObject.NULL)
                .put("report", JSONObject().put("database_bytes", report.databaseBytes)
                    .put("profile", JSONObject.NULL)
                    .put("counts", JSONObject().put("nodes", report.counts.nodes)
                        .put("ways", report.counts.ways).put("relations", report.counts.relations)))
                .toString())
        }
        try {
            withContext(Dispatchers.IO) { publish("snapshot.osm.pbf") }
            CafeStore.beforeRead = {
                if (!selected.isCompleted) {
                    selected.complete(Unit)
                    resumeRead.await()
                }
            }
            withTimeout(10_000) {
                coroutineScope {
                    val first = async(Dispatchers.IO) {
                        CafeStore.withStore(context) { store, _ -> store.get(OsmId(OsmKind.NODE, 10)) != null }
                    }
                    selected.await() // old store chosen, native read not yet submitted
                    withContext(Dispatchers.IO) { publish("profile.osm.pbf") }
                    val second = async(start = CoroutineStart.UNDISPATCHED) {
                        CafeStore.withStore(context) { store, _ -> store.get(OsmId(OsmKind.NODE, 10)) != null }
                    }
                    try {
                        // Refresh must wait for the reader that already selected the old store.
                        assertNull(withTimeoutOrNull(250) { second.await() })
                    } finally {
                        resumeRead.complete(Unit)
                    }
                    assertFalse(first.await())
                    assertTrue(second.await())
                }
            }
        } finally {
            CafeStore.beforeRead = null
            resumeRead.complete(Unit)
            withContext(NonCancellable + Dispatchers.IO) {
                areas.deleteRecursively()
                // A missing published area closes the singleton's cached store.
                try { CafeStore.withStore(context) { _, _ -> Unit } } catch (_: CafeStore.NoAreaException) { }
                root.deleteRecursively()
            }
        }
    }
}

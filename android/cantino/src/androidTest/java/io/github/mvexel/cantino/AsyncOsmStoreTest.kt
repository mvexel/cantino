package io.github.mvexel.cantino

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/** [AsyncOsmStore] from arbitrary coroutine threads: the store's thread confinement must hold. */
@RunWith(AndroidJUnit4::class)
class AsyncOsmStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().context
    private val target = InstrumentationRegistry.getInstrumentation().targetContext

    private fun importFixture(): File {
        val directory = File(target.cacheDir, "async-test-${System.nanoTime()}").apply { mkdirs() }
        val input = File(directory, "snapshot.osm")
        context.assets.open("snapshot.osm").use { source -> input.outputStream().use { source.copyTo(it) } }
        val area = File(directory, "area.sqlite")
        OsmStore.importArea(input, area)
        return area
    }

    @Test
    fun usableFromAnyDispatcherAndCloseIsIdempotent() = runBlocking {
        val store = AsyncOsmStore.open(importFixture())
        // Many concurrent calls from the IO pool all land on the owner thread.
        val ids = (1..20).map { async(Dispatchers.IO) { store.get(OsmId(OsmKind.NODE, 2))?.id } }.awaitAll()
        assertEquals(List(20) { OsmId(OsmKind.NODE, 2) }, ids)
        assertNull(store.get(OsmId(OsmKind.NODE, 99)))
        assertEquals(1, store.query(Query(tags = listOf(TagFilter.Equals("amenity", "cafe")))).size)
        val way = store.withStore { it.get(OsmId(OsmKind.WAY, 1)) }
        assertEquals(OsmId(OsmKind.WAY, 1), way?.id)

        store.close()
        store.close()
        assertThrows(IllegalStateException::class.java) { runBlocking { store.get(OsmId(OsmKind.NODE, 2)) } }
        Unit
    }

    @Test
    fun openFailureLeavesNoStore() = runBlocking {
        assertThrows(CantinoException.Io::class.java) {
            runBlocking { withContext(Dispatchers.IO) { AsyncOsmStore.open(File(target.cacheDir, "missing.sqlite")) } }
        }
        Unit
    }
}

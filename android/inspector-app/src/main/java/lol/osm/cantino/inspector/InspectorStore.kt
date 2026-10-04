package lol.osm.cantino.inspector

import android.content.Context
import android.util.Log
import lol.osm.cantino.AreaInfo
import lol.osm.cantino.AreaManager
import lol.osm.cantino.AsyncOsmStore
import lol.osm.cantino.OsmStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The app's single holder of the offline area's store. Copied from
 * android/cafe-app CafeStore.kt (area ID and names changed, test hook dropped).
 *
 * Threading is the library's job: [AsyncOsmStore] owns the one thread an
 * [OsmStore] may be used on. This object only decides *which* store is open.
 *
 * Refresh: an open store keeps reading the snapshot it opened, even after
 * [AreaManager] publishes a replacement. [withStore] compares the published
 * area's identity (the publishing work ID, else file size + mtime) with the
 * one it opened, and closes and reopens when they differ.
 *
 * Process-wide singleton: MainActivity and ObjectActivity share one store.
 */
object InspectorStore {
    const val AREA_ID = "inspector-area"
    private const val TAG = "InspectorStore"

    // Held through the read: a refresh must not close a store selected by a
    // caller that has not yet submitted its work to the owner thread.
    private val lock = Mutex()
    private var store: AsyncOsmStore? = null
    private var openedIdentity: String? = null

    /**
     * Runs [block] on the store thread with a store opened on the current
     * published area. Throws [NoAreaException] when nothing is published.
     */
    suspend fun <T> withStore(context: Context, block: (OsmStore, AreaInfo) -> T): T {
        val appContext = context.applicationContext
        return lock.withLock {
            val published = withContext(Dispatchers.IO) { AreaManager(appContext).publishedArea(AREA_ID) } ?: run {
                closeStore()
                throw NoAreaException()
            }
            val identity = identity(published)
            if (store == null || identity != openedIdentity) {
                closeStore()
                // Inspector always downloads OSM data (a full import, never downloadBasemap).
                val dataFile = checkNotNull(published.dataFile) { "area ${published.areaId} has no OSM data" }
                Log.i(TAG, "opening $dataFile ($identity)")
                store = AsyncOsmStore.open(dataFile)
                openedIdentity = identity
            }
            store!!.withStore { block(it, published) }
        }
    }

    private suspend fun closeStore() {
        store?.close()
        store = null
        openedIdentity = null
    }

    private fun identity(area: AreaInfo): String =
        area.metadata?.workId?.toString() ?: area.dataFile?.let { f: File -> "${f.length()}:${f.lastModified()}" } ?: "no-data"

    class NoAreaException : IllegalStateException("no offline area has been downloaded")
}

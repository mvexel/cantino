package lol.osm.cantino.cafe

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
 * The app's single holder of the offline area's store.
 *
 * Threading is the library's job: [AsyncOsmStore] owns the one thread an
 * [OsmStore] may be used on. This object only decides *which* store is open.
 *
 * Refresh: an open store keeps reading the snapshot it opened, even after
 * [AreaManager] publishes a replacement. [withStore] therefore compares the
 * published area's identity (the publishing work ID, else file size + mtime)
 * with the one it opened, and closes and reopens when they differ. So every
 * screen sees the new data on its next query after a refresh, without being
 * told.
 *
 * Process-wide singleton: MainActivity and DetailActivity share one store.
 */
object CafeStore {
    const val AREA_ID = "cafe-area"
    private const val TAG = "CafeStore"

    // Serialises open/reopen/close decisions; the store calls themselves run
    // on AsyncOsmStore's thread, outside the lock.
    private val lock = Mutex()
    private var store: AsyncOsmStore? = null
    private var openedIdentity: String? = null

    /**
     * Runs [block] on the store thread with a store opened on the current
     * published area. Throws [NoAreaException] when nothing is published.
     */
    suspend fun <T> withStore(context: Context, block: (OsmStore, AreaInfo) -> T): T {
        val appContext = context.applicationContext
        val (current, published) = lock.withLock {
            val published = withContext(Dispatchers.IO) { AreaManager(appContext).publishedArea(AREA_ID) } ?: run {
                closeStore()
                throw NoAreaException()
            }
            val identity = identity(published)
            if (store == null || identity != openedIdentity) {
                closeStore()
                Log.i(TAG, "opening ${published.dataFile} ($identity)")
                store = AsyncOsmStore.open(published.dataFile)
                openedIdentity = identity
            }
            store!! to published
        }
        return current.withStore { block(it, published) }
    }

    private suspend fun closeStore() {
        store?.close()
        store = null
        openedIdentity = null
    }

    private fun identity(area: AreaInfo): String =
        area.metadata?.workId?.toString() ?: area.dataFile.let { f: File -> "${f.length()}:${f.lastModified()}" }

    class NoAreaException : IllegalStateException("no offline area has been downloaded")
}

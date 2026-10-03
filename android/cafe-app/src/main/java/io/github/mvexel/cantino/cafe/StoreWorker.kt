package io.github.mvexel.cantino.cafe

import android.content.Context
import android.util.Log
import io.github.mvexel.cantino.AreaInfo
import io.github.mvexel.cantino.AreaManager
import io.github.mvexel.cantino.OsmStore
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * The app's single owner of the [OsmStore].
 *
 * Threading: an [OsmStore] is confined to the thread that opened it (the core
 * rejects calls from any other thread). This object runs every store call —
 * open, get, query, close — on one dedicated thread ("osm-store"), exposed as
 * a coroutine dispatcher. Screens call [withStore] from any coroutine and get
 * plain Kotlin objects back (they own copies of their data and outlive the
 * store).
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
object StoreWorker {
    const val AREA_ID = "cafe-area"
    private const val TAG = "CafeStore"

    private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "osm-store").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    // Touched only on the osm-store thread.
    private var store: OsmStore? = null
    private var openedIdentity: String? = null

    /** The open store's area, for callers that need its bbox. Read on the store thread. */
    var area: AreaInfo? = null
        private set

    /**
     * Runs [block] on the store thread with a store opened on the current
     * published area. Throws [NoAreaException] when nothing is published.
     */
    suspend fun <T> withStore(context: Context, block: (OsmStore, AreaInfo) -> T): T = withContext(dispatcher) {
        val published = AreaManager(context).publishedArea(AREA_ID) ?: run {
            closeStore()
            throw NoAreaException()
        }
        val identity = identity(published)
        if (store == null || identity != openedIdentity) {
            closeStore()
            Log.i(TAG, "opening ${published.dataFile} ($identity)")
            store = OsmStore.open(published.dataFile)
            openedIdentity = identity
        }
        area = published
        block(store!!, published)
    }

    private fun closeStore() {
        store?.close()
        store = null
        openedIdentity = null
        area = null
    }

    private fun identity(area: AreaInfo): String =
        area.metadata?.workId?.toString() ?: area.dataFile.let { f: File -> "${f.length()}:${f.lastModified()}" }

    class NoAreaException : IllegalStateException("no offline area has been downloaded")
}

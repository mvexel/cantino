package io.github.mvexel.cantino

import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * A coroutine-friendly [OsmStore]: it owns one dedicated thread, opens the
 * store on it, and runs every call there, so callers can use it from any
 * coroutine without breaking the store's thread confinement.
 *
 * Why: an [OsmStore] must be used on the thread that opened it. Calling it
 * from `withContext(Dispatchers.IO)` works by luck until a suspension resumes
 * on another pool thread, then fails. This class makes the right thing the
 * only thing: all store access is funnelled onto the one owner thread.
 *
 * Calls are serialised (one at a time, in submission order). Returned
 * objects own copies of their data and are safe to use anywhere.
 *
 * Always [close] it (it holds a thread and a database connection); closing
 * is idempotent.
 *
 * ```
 * val store = AsyncOsmStore.open(area.dataFile)
 * try {
 *     val cafes = store.query(Query(tags = listOf(TagFilter.Equals("amenity", "cafe"))))
 *     val first = store.get(cafes.first().id)
 *     // Anything OsmStore offers, in one hop on the owner thread:
 *     val both = store.withStore { s -> s.get(idA) to s.get(idB) }
 * } finally {
 *     store.close()
 * }
 * ```
 */
public class AsyncOsmStore private constructor(
    private val executor: ExecutorService,
    private val store: OsmStore,
) {
    private val dispatcher = executor.asCoroutineDispatcher()
    private val closed = AtomicBoolean(false)

    /**
     * Runs [block] on the owner thread with the underlying [OsmStore] and
     * returns its result. Use it for any [OsmStore] method this class does
     * not mirror, and to group several reads into one thread hop.
     *
     * [block] runs to completion once started, even if the calling coroutine
     * is cancelled meanwhile. Do not let the [OsmStore] escape [block]: it
     * is only valid on the owner thread, and not after [close]. Throws
     * [IllegalStateException] if this store is closed, and whatever [block]
     * throws (the [CantinoException] subtypes each [OsmStore] method
     * documents; never [CantinoException.WrongThread], since [block] runs on
     * the owner thread).
     */
    public suspend fun <T> withStore(block: (OsmStore) -> T): T {
        check(!closed.get()) { "AsyncOsmStore is closed" }
        return withContext(dispatcher) { block(store) }
    }

    /**
     * Suspending [OsmStore.get]: the object with [id], or null if it is not
     * in this area. Throws [CantinoException.InvalidArgument] for a
     * non-positive ID, [IllegalStateException] if closed.
     */
    public suspend fun get(id: OsmId): OsmObject? = withStore { it.get(id) }

    /**
     * Suspending [OsmStore.query]; see [Query] for ordering, pagination and
     * candidate semantics. Throws [CantinoException.InvalidArgument] for an
     * invalid query (see [OsmStore.query]), [IllegalStateException] if closed.
     */
    public suspend fun query(query: Query): List<OsmObject> = withStore { it.query(query) }

    /**
     * Closes the store on its owner thread, then shuts the thread down.
     * Idempotent and safe to call concurrently; calls after the first return
     * immediately. Work already queued runs first. Further use throws
     * [IllegalStateException].
     */
    public suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            // Not cancellable: skipping it would leak the connection and the thread.
            withContext(dispatcher + NonCancellable) { store.close() }
        } finally {
            executor.shutdown()
        }
    }

    /** Opens area databases. */
    public companion object {
        /**
         * Starts the owner thread and opens the area database at [file] on it
         * (see [OsmStore.open]). Throws [CantinoException.Io] if the file is
         * missing or unreadable, [CantinoException.InvalidFile] if it is not
         * an area database or of an incompatible format version; no thread is
         * left behind in that case.
         */
        public suspend fun open(file: File): AsyncOsmStore {
            val executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "cantino-store").apply { isDaemon = true }
            }
            val store = try {
                // Not cancellable: a store opened and then dropped would leak.
                withContext(executor.asCoroutineDispatcher() + NonCancellable) { OsmStore.open(file) }
            } catch (e: Throwable) {
                executor.shutdown()
                throw e
            }
            val async = AsyncOsmStore(executor, store)
            try {
                // Honour a cancellation that arrived while opening.
                coroutineContext.ensureActive()
            } catch (e: Throwable) {
                async.close()
                throw e
            }
            return async
        }

        /** [open] for a path string. */
        public suspend fun open(path: String): AsyncOsmStore = open(File(path))
    }
}

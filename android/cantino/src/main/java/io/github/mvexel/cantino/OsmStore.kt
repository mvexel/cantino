package io.github.mvexel.cantino

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A failure reported by Cantino: the native core (bad input file,
 * invalid query or bbox, I/O error, a call from the wrong thread) or a
 * malformed PMTiles file. [message] says what went wrong; [cause] is the
 * underlying exception when there is one. An [OsmStore] remains usable after
 * a failed query.
 */
public class CantinoException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * An open, read-only offline area database.
 *
 * Get one from [open], with the file from [AreaManager.dataFile] /
 * [AreaInfo.dataFile] or from your own [importArea]. Every entry point
 * takes either a [File] or a path string.
 *
 * **Threading: confined to one thread.** The native handle (one read-only
 * SQLite connection) belongs to the thread that called [open]. Every call,
 * including [close], must run on that same thread; calls from another thread
 * throw [CantinoException] instead of corrupting state. Use one
 * dedicated worker thread per store (for example a single-thread executor),
 * never the main thread for large queries. Calls are synchronous.
 *
 * Several stores may open the same area, but [open] loads dictionaries, so
 * reuse an open store instead of opening one per query.
 *
 * **Snapshots.** A store reads the file as it was when opened. When
 * [AreaManager] publishes a refreshed area, an already-open store keeps
 * reading the old snapshot (the old file stays alive while open); close it
 * and open the new one to see the new data.
 *
 * Returned objects own copies of their data and outlive the store.
 */
public class OsmStore private constructor(private var handle: Long) : AutoCloseable {
    public companion object {
        /**
         * Opens a published area database (created by [importArea] or
         * [AreaManager]) read-only. The calling thread becomes the store's
         * owner thread. Throws [CantinoException] if the file is
         * missing, not an area database, or of an incompatible format version.
         */
        @JvmStatic
        public fun open(path: String): OsmStore = OsmStore(native { NativeBridge.open(path) })

        /** [open] for a [File], e.g. [AreaInfo.dataFile]. */
        @JvmStatic
        public fun open(file: File): OsmStore = open(file.path)

        /**
         * Imports an OSM PBF or OSM XML file ([input]) into an area database
         * at [destination], publishing it atomically: the new file replaces
         * [destination] in one rename once complete, and a failed import
         * leaves any existing file at [destination] intact.
         *
         * Synchronous disk and CPU work (seconds for a city): never call it
         * from the main thread. Any thread otherwise; no store is involved.
         * An open store on [destination] keeps its old snapshot; close and
         * reopen it to see the replacement. Throws [CantinoException]
         * for unreadable or invalid input and I/O errors (including a full
         * disk).
         *
         * [AreaManager] calls this for downloaded areas; use it directly for
         * extracts you obtain yourself.
         */
        @JvmStatic
        @JvmOverloads
        public fun importArea(input: String, destination: String, options: ImportOptions = ImportOptions()): ImportReport =
            ImportReport.fromJson(JSONObject(native { NativeBridge.importArea(input, destination, options.toJson()) }))

        /** [importArea] for [File]s. */
        @JvmStatic
        @JvmOverloads
        public fun importArea(input: File, destination: File, options: ImportOptions = ImportOptions()): ImportReport =
            importArea(input.path, destination.path, options)
    }

    /**
     * The object with [id], or null when it is not in this area (outside the
     * extract, or referenced by a way or relation crossing the area's edge).
     * Throws [CantinoException] on a native error, [IllegalStateException]
     * if the store is closed.
     */
    public fun get(id: OsmId): OsmObject? =
        native { NativeBridge.get(live(), id.kind.code, id.id) }?.let { osmObjectFromJson(JSONObject(it)) }

    /**
     * Runs [query] and returns at most [Query.limit] objects; see [Query] for
     * ordering, pagination and the candidate semantics of a bbox. Throws
     * [CantinoException] for an invalid query (limit outside 1..10 000,
     * invalid bbox, too many spatial candidates), [IllegalStateException] if
     * the store is closed.
     */
    public fun query(query: Query): List<OsmObject> {
        val results = JSONArray(native { NativeBridge.query(live(), query.toJson()) })
        return List(results.length()) { osmObjectFromJson(results.getJSONObject(it)) }
    }

    /** Closes the store and its connection. Idempotent; must run on the owner thread. */
    override fun close() {
        if (handle == 0L) return
        native { NativeBridge.close(handle) }
        handle = 0L
    }

    private fun live(): Long {
        check(handle != 0L) { "store is closed" }
        return handle
    }
}

/** Runs a JNI call and turns the Rust side's RuntimeException into [CantinoException]. */
internal inline fun <T> native(block: () -> T): T =
    try {
        block()
    } catch (error: RuntimeException) {
        if (error is IllegalStateException) throw error
        throw CantinoException(error.message?.removePrefix("Rust error: ") ?: "native failure", error)
    }

package io.github.mvexel.osmframework

import org.json.JSONArray
import org.json.JSONObject

/** A framework or native failure. The store remains usable after query errors. */
class OsmFrameworkException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * An open offline area database.
 *
 * Thread confinement: the Rust handle (one read-only SQLite connection) belongs
 * to the thread that called [open]. Every call, including [close], must run on
 * that same thread; the core rejects calls from other threads with an error
 * rather than corrupting state. Use one dedicated worker thread per store.
 *
 * Several stores may open the same area, but [open] loads dictionaries, so
 * reuse an open store instead of opening one per query.
 *
 * Returned objects own copies of their data and outlive the store.
 */
class OsmStore private constructor(private var handle: Long) : AutoCloseable {
    companion object {
        /** Opens a published area database created by [importArea]. */
        @JvmStatic
        fun open(path: String): OsmStore = OsmStore(native { NativeBridge.open(path) })

        /**
         * Imports an OSM PBF/XML file into [destination], publishing it atomically:
         * a failed import leaves any existing area at [destination] intact.
         * Synchronous disk and CPU work: never call from the main thread.
         * An open store keeps its old snapshot; close and reopen to see a replacement.
         */
        @JvmStatic
        @JvmOverloads
        fun importArea(input: String, destination: String, options: ImportOptions = ImportOptions()): ImportReport =
            ImportReport.fromJson(JSONObject(native { NativeBridge.importArea(input, destination, options.toJson()) }))
    }

    /** The object, or null when it is not in this area. */
    fun get(id: OsmId): OsmObject? =
        native { NativeBridge.get(live(), id.kind.code, id.id) }?.let { OsmObject.fromJson(JSONObject(it)) }

    /** Runs [query]; see [Query] for ordering, pagination and spatial semantics. */
    fun query(query: Query): List<OsmObject> {
        val results = JSONArray(native { NativeBridge.query(live(), query.toJson()) })
        return List(results.length()) { OsmObject.fromJson(results.getJSONObject(it)) }
    }

    /** Closes the store. Idempotent; must run on the owner thread. */
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

private inline fun <T> native(block: () -> T): T =
    try {
        block()
    } catch (error: RuntimeException) {
        if (error is IllegalStateException) throw error
        throw OsmFrameworkException(error.message?.removePrefix("Rust error: ") ?: "native failure", error)
    }

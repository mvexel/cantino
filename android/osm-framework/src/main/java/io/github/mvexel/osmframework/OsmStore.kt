package io.github.mvexel.osmframework

/** A framework or native failure. The store remains usable after query errors. */
class OsmFrameworkException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/** OSM object namespaces, numbered as in the C ABI. */
enum class OsmKind(internal val code: Int) { NODE(0), WAY(1), RELATION(2) }

/**
 * An open offline area database.
 *
 * Thread confinement: LMDB read transactions and the Rust handle belong to the
 * thread that called [open]. Every call, including [close], must run on that
 * same thread; the core rejects calls from other threads with an error rather
 * than corrupting state. Use one dedicated worker thread per store.
 *
 * Only one store may be open per database path in a process (an LMDB rule);
 * reuse the open store instead of opening one per query.
 *
 * Results are JSON strings that own copies of all object data.
 */
class OsmStore private constructor(private var handle: Long) : AutoCloseable {
    companion object {
        /** Opens a published area database created by [importArea]. */
        @JvmStatic
        fun open(path: String): OsmStore = OsmStore(native { NativeBridge.open(path) })

        /**
         * Imports an OSM PBF/XML file into [destination], publishing it atomically.
         * Synchronous disk and CPU work: never call from the main thread.
         * [optionsJson] may be null for defaults. Returns the import report JSON.
         */
        @JvmStatic
        fun importArea(input: String, destination: String, optionsJson: String? = null): String =
            native { NativeBridge.importArea(input, destination, optionsJson) }
    }

    /** Object JSON, or null when the object is not in this area. */
    fun get(kind: OsmKind, id: Long): String? = native { NativeBridge.get(live(), kind.code, id) }

    /** Runs a JSON query (see include/osm_framework.h); returns a JSON array. */
    fun query(requestJson: String): String = native { NativeBridge.query(live(), requestJson) }

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

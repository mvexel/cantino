package io.github.mvexel.cantino

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A failure reported by Cantino's native core, by category. Sealed: catch
 * the subtype you can act on, or [CantinoException] for all of them; a
 * `when` over it is exhaustive.
 *
 * | Subtype | Meaning | Typical reaction |
 * | --- | --- | --- |
 * | [InvalidArgument] | The call was wrong: bad query, bbox, ID or batch size | Fix the call (a bug, or bad user input) |
 * | [InvalidFile] | A file is not what it should be: not an area, old format, corrupt input, bad PMTiles | Re-download / re-import |
 * | [Io] | The environment failed: missing file, permission, disk full | Free space, check the path, retry |
 * | [WrongThread] | A thread-confined handle was used from another thread | Fix the threading ([AsyncOsmStore]) |
 *
 * [message] is a developer-facing description from the core (not
 * localized); [cause] is the underlying exception when there is one. An
 * [OsmStore] remains usable after any of these.
 *
 * Not covered: a closed store throws [IllegalStateException], as do internal
 * errors of the core (a bug or a caught native panic, message starting
 * "Cantino internal error"), which no app can handle meaningfully.
 *
 * The native layer throws the subtypes directly (`src/android.rs`, from the
 * category in `cantino_last_error_code`); the mapping is never inferred from
 * the message text. Constructors are public so tests and fakes can throw
 * them.
 */
public sealed class CantinoException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause) {

    /**
     * The caller passed something invalid: a query with a limit outside
     * 1..10 000, only [TagFilter.NotExists] filters and no bbox, an invalid
     * bbox, more spatial candidates than [Query.maxCandidates], a batch
     * [OsmStore.get] over [OsmStore.MAX_BATCH] IDs, a non-positive ID.
     */
    public class InvalidArgument @JvmOverloads public constructor(message: String, cause: Throwable? = null) :
        CantinoException(message, cause)

    /**
     * A file is not what it should be: not an area database, an area of an
     * incompatible format version (re-import it), a corrupt, truncated or
     * unreadable OSM PBF/XML input (or one with unsorted or duplicate IDs),
     * a malformed or unsupported PMTiles archive ([PmtilesInfo.read],
     * basemap validation).
     */
    public class InvalidFile @JvmOverloads public constructor(message: String, cause: Throwable? = null) :
        CantinoException(message, cause)

    /**
     * An I/O failure of the environment: a missing file, a permission
     * problem, a full disk, a read/write error. Opening a path that does not
     * exist is [Io]; opening a file that exists but is no area is
     * [InvalidFile].
     */
    public class Io @JvmOverloads public constructor(message: String, cause: Throwable? = null) :
        CantinoException(message, cause)

    /**
     * A thread-confined native handle (an [OsmStore], or the basemap
     * engine's internal handles) was used from a thread other than the one
     * that created it. The handle is unaffected; make the call on its owner
     * thread (or use [AsyncOsmStore]).
     */
    public class WrongThread @JvmOverloads public constructor(message: String, cause: Throwable? = null) :
        CantinoException(message, cause)
}

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
 * throw [CantinoException.WrongThread] instead of corrupting state. Use one
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
 *
 * **Errors.** Native failures are [CantinoException] subtypes (each method
 * names the ones it throws; every method can also throw
 * [CantinoException.WrongThread] and, rarely, [CantinoException.Io]). A
 * closed store throws [IllegalStateException]. The store stays usable after
 * a failed call.
 */
public class OsmStore private constructor(private var handle: Long) : AutoCloseable {
    /** Opening areas and importing extracts. */
    public companion object {
        /**
         * Opens a published area database (created by [importArea] or
         * [AreaManager]) read-only. The calling thread becomes the store's
         * owner thread.
         *
         * Throws [CantinoException.Io] if the file is missing or unreadable,
         * [CantinoException.InvalidFile] if it is not an area database or of
         * an incompatible format version (re-import or re-download it).
         */
        @JvmStatic
        public fun open(path: String): OsmStore = OsmStore(NativeBridge.open(path))

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
         * reopen it to see the replacement.
         *
         * Throws [CantinoException.InvalidFile] for corrupt, truncated or
         * invalid input (not OSM PBF/XML, a history file, unsorted or
         * duplicate IDs), [CantinoException.Io] for I/O errors (a missing
         * [input], an unwritable [destination] directory, a full disk).
         *
         * [AreaManager] calls this for downloaded areas; use it directly for
         * extracts you obtain yourself.
         */
        @JvmStatic
        @JvmOverloads
        public fun importArea(input: String, destination: String, options: ImportOptions = ImportOptions()): ImportReport =
            ImportReport.fromJson(JSONObject(NativeBridge.importArea(input, destination, options.toJson())))

        /** [importArea] for [File]s. */
        @JvmStatic
        @JvmOverloads
        public fun importArea(input: File, destination: File, options: ImportOptions = ImportOptions()): ImportReport =
            importArea(input.path, destination.path, options)

        /** Most IDs one batch [get] accepts (the same bound as [Query.limit]). */
        public const val MAX_BATCH: Int = 10_000
    }

    /**
     * The object with [id], or null when it is not in this area (outside the
     * extract, or referenced by a way or relation crossing the area's edge).
     * Throws [CantinoException.InvalidArgument] for a non-positive ID,
     * [CantinoException.WrongThread] off the owner thread,
     * [IllegalStateException] if the store is closed.
     */
    public fun get(id: OsmId): OsmObject? =
        NativeBridge.get(live(), id.kind.code, id.id)?.let { osmObjectFromJson(JSONObject(it)) }

    /**
     * Looks up several objects in one native call: one entry per element of
     * [ids], in the same order, null where the object is not in this area.
     * Duplicate IDs are returned once per occurrence.
     *
     * Use it instead of calling [get] in a loop (for example for a way's
     * [OsmObject.Way.nodeIds] or a relation's members): each [get] crosses
     * JNI and decodes JSON once, this crosses once for the whole list.
     *
     * At most [MAX_BATCH] (10 000) IDs per call; more throws
     * [CantinoException.InvalidArgument] (never a silent truncation), as does
     * an ID that is not positive. Throws [CantinoException.WrongThread] off
     * the owner thread, [IllegalStateException] if the store is closed.
     */
    public fun get(ids: List<OsmId>): List<OsmObject?> {
        val handle = live() // closed-store check even for an empty list
        if (ids.isEmpty()) return emptyList()
        val request = JSONArray(ids.map { it.toJson() }).toString()
        val results = JSONArray(NativeBridge.getMany(handle, request))
        return List(results.length()) { index ->
            results.optJSONObject(index)?.let(::osmObjectFromJson)
        }
    }

    /**
     * The coordinates of way [wayId]'s nodes, in the way's order with
     * repeats kept (a closed way ends with its first coordinate again), in
     * one native call.
     *
     * Returns null when the way is not in this area. An entry is null when
     * that node is outside the area (the way crosses the area's edge): do not
     * draw a line across such a gap.
     *
     * Throws [CantinoException.InvalidArgument] for a non-positive ID,
     * [CantinoException.WrongThread] off the owner thread,
     * [IllegalStateException] if the store is closed.
     */
    public fun wayCoordinates(wayId: Long): List<Coordinate?>? {
        val json = NativeBridge.wayCoordinates(live(), wayId) ?: return null
        // Flat [lat_e7, lon_e7, lat_e7, lon_e7, ...]; null, null for a node
        // outside the area. See cantino_way_coordinates in include/cantino.h.
        val flat = JSONArray(json)
        return List(flat.length() / 2) { index ->
            if (flat.isNull(2 * index)) null else Coordinate(flat.getInt(2 * index), flat.getInt(2 * index + 1))
        }
    }

    /**
     * A single point to put a marker or label for the object with [id], or
     * to measure a distance from. Null when the object is not in this area
     * or none of its geometry is.
     *
     * **This is an anchor point, not a guaranteed point-on-surface or a true
     * centroid**: for a concave building or a multipolygon it can lie outside
     * the shape. Computed in the native core:
     * - **Node**: its coordinate.
     * - **Closed way** (first node = last node): the mean of its distinct
     *   vertices that are in the area.
     * - **Open way**: the point at half the length of the line through its
     *   in-area nodes (nodes outside the area are skipped).
     * - **Relation**: the mean of the representative points of its distinct
     *   members that are in the area, each member weighted equally. Member
     *   relations are followed up to 8 levels deep; cycles are skipped.
     *
     * Throws [CantinoException.InvalidArgument] for a non-positive ID,
     * [CantinoException.WrongThread] off the owner thread,
     * [IllegalStateException] if the store is closed.
     */
    public fun representativePoint(id: OsmId): Coordinate? =
        NativeBridge.representativePoint(live(), id.kind.code, id.id)
            ?.let { Coordinate.fromJson(JSONObject(it)) }

    /**
     * Runs [query] and returns at most [Query.limit] objects; see [Query] for
     * ordering, pagination and the candidate semantics of a bbox.
     *
     * Throws [CantinoException.InvalidArgument] for an invalid query (limit
     * outside 1..10 000, invalid bbox, more spatial candidates than
     * [Query.maxCandidates], only [TagFilter.NotExists] filters and no
     * bbox), [CantinoException.WrongThread] off the owner thread,
     * [IllegalStateException] if the store is closed.
     */
    public fun query(query: Query): List<OsmObject> {
        val results = JSONArray(NativeBridge.query(live(), query.toJson()))
        return List(results.length()) { osmObjectFromJson(results.getJSONObject(it)) }
    }

    /**
     * Closes the store and its connection. Idempotent; must run on the owner
     * thread (otherwise [CantinoException.WrongThread], and the store stays
     * open).
     */
    override fun close() {
        if (handle == 0L) return
        NativeBridge.close(handle)
        handle = 0L
    }

    private fun live(): Long {
        check(handle != 0L) { "store is closed" }
        return handle
    }
}

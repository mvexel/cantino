package io.github.mvexel.osmframework

import org.json.JSONArray
import org.json.JSONObject

// Kotlin mirrors of the Rust model (src/model.rs, src/store.rs). JSON is the
// wire format across JNI only; nothing outside this file builds or parses it.
// Field semantics follow the Rust docs: raw tags, integer 1e-7 coordinates,
// ordered (possibly repeated) references, metadata that may be absent.

/** OSM object namespaces, numbered as in the C ABI. Node 42 and way 42 are distinct. */
enum class OsmKind(internal val code: Int, internal val wire: String) {
    NODE(0, "node"),
    WAY(1, "way"),
    RELATION(2, "relation");

    internal companion object {
        fun fromWire(value: String) = entries.first { it.wire == value }
    }
}

data class OsmId(val kind: OsmKind, val id: Long) {
    internal fun toJson() = JSONObject().put("type", kind.wire).put("id", id)

    internal companion object {
        fun fromJson(json: JSONObject) = OsmId(OsmKind.fromWire(json.getString("type")), json.getLong("id"))
    }
}

/**
 * OSMExpress stores absent source fields as zero/empty, so a zero here may mean
 * "unknown" rather than a real value. Timestamp is Unix seconds.
 */
data class Metadata(
    val version: Long,
    val timestamp: Long,
    val changeset: Long,
    val uid: Long,
    val user: String,
)

sealed interface OsmObject {
    val id: OsmId
    val tags: Map<String, String>
    /** Null when the database has no metadata for this object (legacy untagged nodes). */
    val metadata: Metadata?

    /** Coordinates in 1e-7 degrees, matching storage without float drift. */
    data class Node(
        override val id: OsmId,
        val latE7: Int,
        val lonE7: Int,
        val locationVersion: Int,
        override val tags: Map<String, String>,
        override val metadata: Metadata?,
    ) : OsmObject {
        val lat get() = latE7 / 1e7
        val lon get() = lonE7 / 1e7
    }

    /** Node references in order; repeats are significant (closed ways repeat the first node). */
    data class Way(
        override val id: OsmId,
        val nodes: List<Long>,
        override val tags: Map<String, String>,
        override val metadata: Metadata?,
    ) : OsmObject

    data class Member(val id: OsmId, val role: String)

    data class Relation(
        override val id: OsmId,
        val members: List<Member>,
        override val tags: Map<String, String>,
        override val metadata: Metadata?,
    ) : OsmObject

    companion object {
        internal fun fromJson(json: JSONObject): OsmObject {
            val kind = OsmKind.fromWire(json.getString("type"))
            val id = OsmId(kind, json.getLong("id"))
            val tags = json.getJSONObject("tags").let { t -> t.keys().asSequence().associateWith(t::getString) }
            val metadata = json.optJSONObject("metadata")?.let {
                Metadata(it.getLong("version"), it.getLong("timestamp"), it.getLong("changeset"), it.getLong("uid"), it.getString("user"))
            }
            return when (kind) {
                OsmKind.NODE -> json.getJSONObject("coordinate").let { c ->
                    Node(id, c.getInt("lat_e7"), c.getInt("lon_e7"), json.getInt("location_version"), tags, metadata)
                }
                OsmKind.WAY -> json.getJSONArray("nodes").let { n ->
                    Way(id, List(n.length()) { n.getLong(it) }, tags, metadata)
                }
                OsmKind.RELATION -> json.getJSONArray("members").let { m ->
                    Relation(id, List(m.length()) { i ->
                        m.getJSONObject(i).let { Member(OsmId.fromJson(it.getJSONObject("id")), it.getString("role")) }
                    }, tags, metadata)
                }
            }
        }
    }
}

/** A non-wrapping WGS84 rectangle in degrees. Split dateline-crossing areas into two. */
data class Bbox(val west: Double, val south: Double, val east: Double, val north: Double) {
    internal fun toJson() = JSONObject().put("west", west).put("south", south).put("east", east).put("north", north)
}

/** Tag predicates match raw strings; a missing tag and an empty value are distinct. */
sealed interface TagFilter {
    data class Exists(val key: String) : TagFilter
    data class Equals(val key: String, val value: String) : TagFilter

    fun toJson(): JSONObject = when (this) {
        is Exists -> JSONObject().put("Exists", key)
        is Equals -> JSONObject().put("Equals", JSONArray().put(key).put(value))
    }
}

/**
 * All [tags] filters must match. A [bbox] selects spatial *candidates*: ways or
 * relations crossing the box without a member node inside it can be omitted.
 * Results are ordered by kind then ID; pass the last result's id as [after] to
 * fetch the next page. [maxCandidates] bounds spatial work per namespace.
 */
data class Query(
    val tags: List<TagFilter> = emptyList(),
    val bbox: Bbox? = null,
    val after: OsmId? = null,
    val limit: Int = 100,
    val maxCandidates: Int = 100_000,
) {
    internal fun toJson(): String = JSONObject()
        .put("tags", JSONArray(tags.map { it.toJson() }))
        .put("bbox", bbox?.toJson() ?: JSONObject.NULL)
        .put("after", after?.toJson() ?: JSONObject.NULL)
        .put("limit", limit)
        .put("max_candidates", maxCandidates)
        .toString()
}

/**
 * [mapSize] reserves virtual address space, not RAM; a full map fails the
 * import cleanly. [sortPairs] bounds each of five external sorters (16 bytes per pair).
 */
data class ImportOptions(
    val mapSize: Long = 1L shl 30,
    val sortPairs: Int = 65_536,
    val preserveUntaggedMetadata: Boolean = true,
) {
    internal fun toJson(): String = JSONObject()
        .put("map_size", mapSize)
        .put("sort_pairs", sortPairs)
        .put("preserve_untagged_metadata", preserveUntaggedMetadata)
        .toString()
}

data class Counts(val nodes: Long, val ways: Long, val relations: Long)

data class ImportReport(val counts: Counts, val databaseBytes: Long) {
    internal companion object {
        fun fromJson(json: JSONObject) = ImportReport(
            json.getJSONObject("counts").let { Counts(it.getLong("nodes"), it.getLong("ways"), it.getLong("relations")) },
            json.getLong("database_bytes"),
        )
    }
}

package io.github.mvexel.osmframework

import org.json.JSONArray
import org.json.JSONObject

// Kotlin mirrors of the Rust model (src/model.rs, src/store.rs). JSON is the
// wire format across JNI only; nothing outside this module builds or parses
// it, and no org.json type appears in the public API. Field semantics follow
// the Rust docs: raw tags, integer 1e-7 coordinates, ordered (possibly
// repeated) references, metadata that may be absent.
//
// Every model here is an immutable value (data class or enum): equal content
// means equal objects, and instances may be shared freely between threads.

/**
 * The three OSM object namespaces. IDs are only unique within a kind:
 * node 42 and way 42 are different objects (see [OsmId]).
 */
public enum class OsmKind(internal val code: Int, internal val wire: String) {
    /** A point with a coordinate. */
    NODE(0, "node"),

    /** An ordered list of node references (a line, or a closed ring). */
    WAY(1, "way"),

    /** An ordered list of members of any kind, each with a role. */
    RELATION(2, "relation");

    internal companion object {
        fun fromWire(value: String) = entries.first { it.wire == value }
    }
}

/**
 * Identity of an OSM object: its [kind] and its numeric [id] within that kind.
 * IDs are the raw OSM IDs of the extract (positive for data from the OSM
 * database).
 */
public data class OsmId(val kind: OsmKind, val id: Long) {
    internal fun toJson() = JSONObject().put("type", kind.wire).put("id", id)

    internal companion object {
        fun fromJson(json: JSONObject) = OsmId(OsmKind.fromWire(json.getString("type")), json.getLong("id"))
    }
}

/**
 * Editing metadata of one object version, as found in the source extract.
 *
 * Absent source fields are stored as zero/empty, so a zero or empty value may
 * mean "unknown" rather than a real value (SliceOSM extracts usually carry
 * all fields). [timestampSeconds] is Unix seconds (UTC).
 */
public data class ObjectMetadata(
    val version: Long,
    val timestampSeconds: Long,
    val changeset: Long,
    val uid: Long,
    val user: String,
)

/**
 * One OSM object as stored in the area: [Node], [Way] or [Relation] (the
 * hierarchy is sealed, so a `when` over it is exhaustive).
 *
 * Objects are plain values that own copies of their data: they stay valid
 * after the [OsmStore] that returned them is closed, and may be passed to
 * any thread.
 */
public sealed interface OsmObject {
    /** Kind and ID; [OsmId.kind] matches the subtype. */
    public val id: OsmId

    /** Raw tags, exactly as in OSM: no normalization; an empty value is distinct from a missing key. */
    public val tags: Map<String, String>

    /**
     * Editing metadata, or null for untagged nodes imported without
     * [ImportOptions.preserveUntaggedMetadata] (they keep only
     * [Node.locationVersion]). Tagged nodes, ways and relations always have it.
     */
    public val metadata: ObjectMetadata?

    /**
     * A node. Coordinates are integers in 1e-7 degrees ([latE7], [lonE7]),
     * the storage format, so they compare and round-trip without float drift;
     * [lat] and [lon] are the same values in degrees.
     *
     * [locationVersion] is the object version at which the node got its
     * current coordinate; it is kept even when [metadata] is not.
     *
     * Untagged nodes (the vertices of ways) are stored and returned by
     * [OsmStore.get], but they are not spatially indexed: a bbox [Query]
     * never returns them. Reach them through their ways.
     */
    public data class Node(
        override val id: OsmId,
        val latE7: Int,
        val lonE7: Int,
        val locationVersion: Int,
        override val tags: Map<String, String>,
        override val metadata: ObjectMetadata?,
    ) : OsmObject {
        /** Latitude in degrees (WGS84). */
        public val lat: Double get() = latE7 / 1e7

        /** Longitude in degrees (WGS84). */
        public val lon: Double get() = lonE7 / 1e7
    }

    /**
     * A way. [nodeIds] are the node references in order; repeats are
     * significant (a closed way repeats its first node at the end). A
     * referenced node may be missing from the area when the way crosses the
     * area's edge: [OsmStore.get] then returns null for it.
     */
    public data class Way(
        override val id: OsmId,
        val nodeIds: List<Long>,
        override val tags: Map<String, String>,
        override val metadata: ObjectMetadata?,
    ) : OsmObject

    /**
     * One member of a [Relation]: the referenced object and its [role]
     * (raw string, may be empty). The member object may be missing from the
     * area (outside it, or never part of the extract).
     */
    public data class Member(val id: OsmId, val role: String)

    /** A relation. [members] are in order; the same member may appear more than once. */
    public data class Relation(
        override val id: OsmId,
        val members: List<Member>,
        override val tags: Map<String, String>,
        override val metadata: ObjectMetadata?,
    ) : OsmObject
}

// Interfaces cannot have an internal companion, so the parser is top-level.
internal fun osmObjectFromJson(json: JSONObject): OsmObject {
    val kind = OsmKind.fromWire(json.getString("type"))
    val id = OsmId(kind, json.getLong("id"))
    val tags = json.getJSONObject("tags").let { t -> t.keys().asSequence().associateWith(t::getString) }
    val metadata = json.optJSONObject("metadata")?.let {
        ObjectMetadata(it.getLong("version"), it.getLong("timestamp"), it.getLong("changeset"), it.getLong("uid"), it.getString("user"))
    }
    return when (kind) {
        OsmKind.NODE -> json.getJSONObject("coordinate").let { c ->
            OsmObject.Node(id, c.getInt("lat_e7"), c.getInt("lon_e7"), json.getInt("location_version"), tags, metadata)
        }
        OsmKind.WAY -> json.getJSONArray("nodes").let { n ->
            OsmObject.Way(id, List(n.length()) { n.getLong(it) }, tags, metadata)
        }
        OsmKind.RELATION -> json.getJSONArray("members").let { m ->
            OsmObject.Relation(id, List(m.length()) { i ->
                m.getJSONObject(i).let { OsmObject.Member(OsmId.fromJson(it.getJSONObject("id")), it.getString("role")) }
            }, tags, metadata)
        }
    }
}

/**
 * A WGS84 rectangle in degrees: [west]/[east] longitudes, [south]/[north]
 * latitudes. It must not wrap: `west <= east` and `south <= north`; split an
 * area that crosses the antimeridian into two boxes.
 *
 * The constructor does not validate. An invalid box (wrong order, outside
 * ±180/±90, not finite) is rejected where it is used: [OsmStore.query]
 * throws [OsmFrameworkException], [AreaManager.download] ends in a
 * non-retryable [AreaState.Failed].
 */
public data class Bbox(val west: Double, val south: Double, val east: Double, val north: Double) {
    internal fun toJson() = JSONObject().put("west", west).put("south", south).put("east", east).put("north", north)

    internal companion object {
        fun fromJson(json: JSONObject) =
            Bbox(json.getDouble("west"), json.getDouble("south"), json.getDouble("east"), json.getDouble("north"))
    }
}

/**
 * One tag predicate of a [Query]. Matching is on raw strings: case-sensitive,
 * no trimming; a missing tag and a tag with an empty value are distinct.
 */
public sealed interface TagFilter {
    /** The object has tag [key], with any value (including empty). */
    public data class Exists(val key: String) : TagFilter

    /** The object has tag [key] with exactly [value]. */
    public data class Equals(val key: String, val value: String) : TagFilter
}

internal fun TagFilter.toJson(): JSONObject = when (this) {
    is TagFilter.Exists -> JSONObject().put("Exists", key)
    is TagFilter.Equals -> JSONObject().put("Equals", JSONArray().put(key).put(value))
}

/**
 * A query against an [OsmStore]: all [tags] filters must match (AND), and
 * when [bbox] is set the object must be a spatial candidate for it. No
 * filters at all returns every object, page by page.
 *
 * **Spatial results are candidates, not exact intersections.** Tagged nodes
 * match exactly (point inside the box). Ways and relations match when their
 * bounding box intersects the box: a way crossing the box without a node
 * inside it is included, and so is one that merely bends around the box.
 * Bounds are computed from the members present in the area, so objects
 * clipped at the area's edge can be missed near that edge. **Untagged nodes
 * are never returned by a bbox query** (they are not spatially indexed);
 * reach them through their ways with [OsmStore.get].
 *
 * Results are ordered by kind (nodes, ways, relations), then ascending ID.
 * At most [limit] (1..10 000) objects come back per call; to fetch the next
 * page, pass the last result's [OsmObject.id] as [after]. A page shorter than
 * [limit] is the last one.
 *
 * [maxCandidates] bounds the spatial candidates a bbox-driven query collects
 * (all kinds together). Exceeding it throws [OsmFrameworkException]; results
 * are never silently truncated. Narrow the box or add a tag filter.
 */
public data class Query(
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
 * Options for [OsmStore.importArea] (and [AreaConfig.importOptions]).
 *
 * [preserveUntaggedMetadata] keeps version/timestamp/changeset/user for
 * untagged nodes too; by default they read back with null
 * [OsmObject.metadata] and only [OsmObject.Node.locationVersion], which makes
 * the database noticeably smaller. [cacheMiB] is SQLite's page cache during
 * the import in MiB: resident memory, not a cap on the import's total memory.
 */
public data class ImportOptions(
    val preserveUntaggedMetadata: Boolean = false,
    val cacheMiB: Int = 16,
) {
    internal fun toJson(): String = JSONObject()
        .put("preserve_untagged_metadata", preserveUntaggedMetadata)
        .put("cache_mb", cacheMiB)
        .toString()
}

/** Number of objects per kind. */
public data class ObjectCounts(val nodes: Long, val ways: Long, val relations: Long)

/** Result of an import: objects stored per kind, and the size of the published database file in bytes. */
public data class ImportReport(val counts: ObjectCounts, val databaseBytes: Long) {
    /** Same shape as the Rust report, so it round-trips through [fromJson]. */
    internal fun toJson(): JSONObject = JSONObject()
        .put("counts", JSONObject().put("nodes", counts.nodes).put("ways", counts.ways).put("relations", counts.relations))
        .put("database_bytes", databaseBytes)

    internal companion object {
        fun fromJson(json: JSONObject) = ImportReport(
            json.getJSONObject("counts").let { ObjectCounts(it.getLong("nodes"), it.getLong("ways"), it.getLong("relations")) },
            json.getLong("database_bytes"),
        )
    }
}

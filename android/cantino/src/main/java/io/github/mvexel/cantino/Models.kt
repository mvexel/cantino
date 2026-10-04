package io.github.mvexel.cantino

import org.json.JSONArray
import org.json.JSONObject

// Kotlin mirrors of the Rust model (src/model.rs, src/store.rs). JSON is the
// wire format across JNI only; nothing outside this module builds or parses
// it, and no org.json type appears in the public API. Field semantics follow
// the Rust docs: raw tags, integer 1e-7 coordinates, ordered (possibly
// repeated) references, metadata that may be absent.
//
// Every model here is an immutable value: equal content means equal objects,
// and instances may be shared freely between threads.
//
// Binary compatibility: types the library *returns* (OsmObject and its parts,
// ObjectMetadata, ImportReport, ObjectCounts, Coordinate) are plain classes with
// hand-written equals/hashCode/toString and internal constructors, not data
// classes, so a field can be added later without breaking compiled apps
// (a data class's copy() and componentN() would change signature). Types
// apps *construct* (OsmId, Bbox, TagFilter, Query, ImportOptions) stay data
// classes; see CHANGELOG.md for what that means for compatibility.

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
 *
 * @property kind Node, way or relation.
 * @property id The OSM ID within [kind].
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
public class ObjectMetadata internal constructor(
    /** Object version. */
    public val version: Long,
    /** Edit time, Unix seconds (UTC). */
    public val timestampSeconds: Long,
    /** Changeset ID. */
    public val changeset: Long,
    /** User ID of the editor. */
    public val uid: Long,
    /** User name of the editor. */
    public val user: String,
) {
    override fun equals(other: Any?): Boolean = this === other || other is ObjectMetadata &&
        version == other.version && timestampSeconds == other.timestampSeconds &&
        changeset == other.changeset && uid == other.uid && user == other.user

    override fun hashCode(): Int = hash(version, timestampSeconds, changeset, uid, user)

    override fun toString(): String =
        "ObjectMetadata(version=$version, timestampSeconds=$timestampSeconds, changeset=$changeset, uid=$uid, user=$user)"
}

/** hashCode over [values] in order, for the hand-written value classes. */
internal fun hash(vararg values: Any?): Int = values.contentHashCode()

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
    public class Node internal constructor(
        override val id: OsmId,
        /** Latitude in 1e-7 degrees. */
        public val latE7: Int,
        /** Longitude in 1e-7 degrees. */
        public val lonE7: Int,
        /** Object version at which the node got its current coordinate. */
        public val locationVersion: Int,
        override val tags: Map<String, String>,
        override val metadata: ObjectMetadata?,
    ) : OsmObject {
        /** Latitude in degrees (WGS84). */
        public val lat: Double get() = latE7 / 1e7

        /** Longitude in degrees (WGS84). */
        public val lon: Double get() = lonE7 / 1e7

        override fun equals(other: Any?): Boolean = this === other || other is Node &&
            id == other.id && latE7 == other.latE7 && lonE7 == other.lonE7 &&
            locationVersion == other.locationVersion && tags == other.tags && metadata == other.metadata

        override fun hashCode(): Int = hash(id, latE7, lonE7, locationVersion, tags, metadata)

        override fun toString(): String =
            "Node(id=$id, latE7=$latE7, lonE7=$lonE7, locationVersion=$locationVersion, tags=$tags, metadata=$metadata)"
    }

    /**
     * A way. [nodeIds] are the node references in order; repeats are
     * significant (a closed way repeats its first node at the end). A
     * referenced node may be missing from the area when the way crosses the
     * area's edge: [OsmStore.get] then returns null for it.
     */
    public class Way internal constructor(
        override val id: OsmId,
        /** Node references in order (repeats are significant). */
        public val nodeIds: List<Long>,
        override val tags: Map<String, String>,
        override val metadata: ObjectMetadata?,
    ) : OsmObject {
        override fun equals(other: Any?): Boolean = this === other || other is Way &&
            id == other.id && nodeIds == other.nodeIds && tags == other.tags && metadata == other.metadata

        override fun hashCode(): Int = hash(id, nodeIds, tags, metadata)

        override fun toString(): String = "Way(id=$id, nodeIds=$nodeIds, tags=$tags, metadata=$metadata)"
    }

    /**
     * One member of a [Relation]: the referenced object and its [role]
     * (raw string, may be empty). The member object may be missing from the
     * area (outside it, or never part of the extract).
     */
    public class Member internal constructor(
        /** The referenced object. */
        public val id: OsmId,
        /** The member's role, raw (may be empty). */
        public val role: String,
    ) {
        override fun equals(other: Any?): Boolean = this === other || other is Member && id == other.id && role == other.role

        override fun hashCode(): Int = hash(id, role)

        override fun toString(): String = "Member(id=$id, role=$role)"
    }

    /** A relation. [members] are in order; the same member may appear more than once. */
    public class Relation internal constructor(
        override val id: OsmId,
        /** Members in order. */
        public val members: List<Member>,
        override val tags: Map<String, String>,
        override val metadata: ObjectMetadata?,
    ) : OsmObject {
        override fun equals(other: Any?): Boolean = this === other || other is Relation &&
            id == other.id && members == other.members && tags == other.tags && metadata == other.metadata

        override fun hashCode(): Int = hash(id, members, tags, metadata)

        override fun toString(): String = "Relation(id=$id, members=$members, tags=$tags, metadata=$metadata)"
    }
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
 * throws [CantinoException.InvalidArgument], [AreaManager.download] ends in a
 * non-retryable [AreaState.Failed] with [FailureReason.INVALID_REQUEST].
 *
 * @property west Western edge, longitude in degrees (-180..180).
 * @property south Southern edge, latitude in degrees (-90..90).
 * @property east Eastern edge, longitude in degrees, `>= west`.
 * @property north Northern edge, latitude in degrees, `>= south`.
 */
public data class Bbox(val west: Double, val south: Double, val east: Double, val north: Double) {
    internal fun toJson() = JSONObject().put("west", west).put("south", south).put("east", east).put("north", north)

    /** Construction helpers. */
    public companion object {
        internal fun fromJson(json: JSONObject) =
            Bbox(json.getDouble("west"), json.getDouble("south"), json.getDouble("east"), json.getDouble("north"))

        /**
         * A [widthKm] (east-west) by [heightKm] (north-south) box centred on
         * ([lat], [lon]); [heightKm] defaults to a square.
         *
         * Uses a flat-earth (equirectangular) approximation: 111.32 km per
         * degree of latitude, and that times cos(latitude) per degree of
         * longitude. Fine at city scale, increasingly rough for boxes of
         * hundreds of km or near the poles.
         *
         * Edges are clamped, never wrapped (a [Bbox] cannot wrap): latitudes to
         * -85..85 (the Web Mercator range, so the box stays usable for map
         * tiles) and longitudes to -180..180. A box that would cross the
         * antimeridian is therefore cut at it and comes out narrower than
         * asked; to cover both sides, build two boxes. Throws
         * [IllegalArgumentException] for a non-finite or out-of-range centre
         * ([lat] outside -90..90, [lon] outside -180..180) or a non-positive
         * or non-finite size.
         */
        public fun around(lat: Double, lon: Double, widthKm: Double, heightKm: Double = widthKm): Bbox {
            require(lat.isFinite() && lat in -90.0..90.0) { "lat must be within -90..90: $lat" }
            require(lon.isFinite() && lon in -180.0..180.0) { "lon must be within -180..180: $lon" }
            require(widthKm.isFinite() && widthKm > 0) { "widthKm must be positive: $widthKm" }
            require(heightKm.isFinite() && heightKm > 0) { "heightKm must be positive: $heightKm" }
            val halfLat = heightKm / 2 / KM_PER_DEGREE
            // cos(lat) -> 0 at the poles; use the clamped latitude so the
            // longitude span stays finite (the result is clamped anyway).
            val cosLat = kotlin.math.cos(Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT)))
            val halfLon = widthKm / 2 / (KM_PER_DEGREE * cosLat)
            return Bbox(
                west = (lon - halfLon).coerceAtLeast(-180.0),
                south = (lat - halfLat).coerceAtLeast(-MAX_LAT),
                east = (lon + halfLon).coerceAtMost(180.0),
                north = (lat + halfLat).coerceAtMost(MAX_LAT),
            )
        }

        private const val KM_PER_DEGREE = 111.32
        private const val MAX_LAT = 85.0
    }
}

/**
 * One tag predicate of a [Query]. Matching is on raw strings: case-sensitive,
 * no trimming; a missing tag and a tag with an empty value are distinct.
 */
public sealed interface TagFilter {
    /**
     * The object has tag [key], with any value (including empty).
     *
     * @property key Tag key, raw (case-sensitive).
     */
    public data class Exists(val key: String) : TagFilter

    /**
     * The object has tag [key] with exactly [value].
     *
     * @property key Tag key, raw (case-sensitive).
     * @property value Tag value, compared exactly (case-sensitive, no trimming).
     */
    public data class Equals(val key: String, val value: String) : TagFilter

    /**
     * The object does **not** have tag [key] (e.g. amenities without
     * `opening_hours`).
     *
     * Absence has no index, so this filter never drives a query: it is
     * checked on the candidates another filter produces. A [Query] needs at
     * least one [Exists] or [Equals] filter, or a [Query.bbox], next to it; a
     * query whose only filters are `NotExists` throws
     * [CantinoException.InvalidArgument] instead of scanning the whole area.
     *
     * @property key Tag key, raw (case-sensitive).
     */
    public data class NotExists(val key: String) : TagFilter
}

internal fun TagFilter.toJson(): JSONObject = when (this) {
    is TagFilter.Exists -> JSONObject().put("Exists", key)
    is TagFilter.Equals -> JSONObject().put("Equals", JSONArray().put(key).put(value))
    is TagFilter.NotExists -> JSONObject().put("NotExists", key)
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
 * (all kinds together). Exceeding it throws
 * [CantinoException.InvalidArgument]; results
 * are never silently truncated. Narrow the box or add a tag filter.
 *
 * @property tags Tag filters, all of which must match (AND). Empty: no tag condition.
 * @property bbox Spatial candidate filter, or null for none.
 * @property after Keyset cursor: return only objects ordered after this ID
 *   (the last [OsmObject.id] of the previous page). Null for the first page.
 * @property limit Maximum objects per call, 1..10 000. Default 100.
 * @property maxCandidates Maximum spatial candidates a bbox-driven query may
 *   collect before it fails with [CantinoException.InvalidArgument]. Default 100000.
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
 *
 * @property preserveUntaggedMetadata Keep full editing metadata for untagged nodes (larger file).
 * @property cacheMiB SQLite page cache during the import, in MiB.
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
public class ObjectCounts internal constructor(
    /** Nodes (tagged and untagged). */
    public val nodes: Long,
    /** Ways. */
    public val ways: Long,
    /** Relations. */
    public val relations: Long,
) {
    override fun equals(other: Any?): Boolean = this === other || other is ObjectCounts &&
        nodes == other.nodes && ways == other.ways && relations == other.relations

    override fun hashCode(): Int = hash(nodes, ways, relations)

    override fun toString(): String = "ObjectCounts(nodes=$nodes, ways=$ways, relations=$relations)"
}

/** Result of an import: objects stored per kind, and the size of the published database file in bytes. */
public class ImportReport internal constructor(
    /** Objects stored per kind. */
    public val counts: ObjectCounts,
    /** Size of the published database file in bytes. */
    public val databaseBytes: Long,
) {
    override fun equals(other: Any?): Boolean = this === other || other is ImportReport &&
        counts == other.counts && databaseBytes == other.databaseBytes

    override fun hashCode(): Int = hash(counts, databaseBytes)

    override fun toString(): String = "ImportReport(counts=$counts, databaseBytes=$databaseBytes)"

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

/**
 * A WGS84 point, as returned by [OsmStore.wayCoordinates] and
 * [OsmStore.representativePoint]. Like [OsmObject.Node], the values are
 * integers in 1e-7 degrees ([latE7], [lonE7]), the storage format, so they
 * compare exactly; [lat] and [lon] are the same values in degrees.
 */
public class Coordinate internal constructor(
    /** Latitude in 1e-7 degrees. */
    public val latE7: Int,
    /** Longitude in 1e-7 degrees. */
    public val lonE7: Int,
) {
    /** Latitude in degrees (WGS84). */
    public val lat: Double get() = latE7 / 1e7

    /** Longitude in degrees (WGS84). */
    public val lon: Double get() = lonE7 / 1e7

    override fun equals(other: Any?): Boolean = this === other || other is Coordinate &&
        latE7 == other.latE7 && lonE7 == other.lonE7

    override fun hashCode(): Int = hash(latE7, lonE7)

    override fun toString(): String = "Coordinate(latE7=$latE7, lonE7=$lonE7)"

    internal companion object {
        fun fromJson(json: JSONObject) = Coordinate(json.getInt("lat_e7"), json.getInt("lon_e7"))
    }
}

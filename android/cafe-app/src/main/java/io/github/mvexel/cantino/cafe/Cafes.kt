package io.github.mvexel.cantino.cafe

import io.github.mvexel.cantino.Bbox
import io.github.mvexel.cantino.OsmId
import io.github.mvexel.cantino.OsmKind
import io.github.mvexel.cantino.OsmObject
import io.github.mvexel.cantino.OsmStore
import io.github.mvexel.cantino.Query
import io.github.mvexel.cantino.TagFilter
import java.time.LocalDateTime
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Café policy of the reference app: what counts as a café, where to put it on
 * the map, and how to read outdoor seating. All of this is app-level
 * interpretation on top of raw OSM objects from the framework.
 */

/** A WGS84 point in degrees. */
data class LatLon(val lat: Double, val lon: Double) {
    override fun toString() = "%.5f, %.5f".format(lat, lon)
}

/** Outdoor seating as far as the data says. Missing or unrecognised values are [Unknown]. */
sealed interface OutdoorSeating {
    data object Yes : OutdoorSeating
    data object No : OutdoorSeating
    data class Unknown(val raw: String?) : OutdoorSeating

    companion object {
        /**
         * `outdoor_seating=yes|no`. Values naming the kind of seating
         * (`sidewalk`, `garden`, `terrace`, …; documented on the wiki) also
         * mean yes. Anything else, including a missing tag, is Unknown.
         */
        fun of(tags: Map<String, String>): OutdoorSeating {
            val raw = tags["outdoor_seating"] ?: return Unknown(null)
            return when (raw.trim().lowercase()) {
                "yes" -> Yes
                "no" -> No
                in SEATING_KINDS -> Yes
                else -> Unknown(raw)
            }
        }

        private val SEATING_KINDS = setOf(
            "sidewalk", "garden", "terrace", "patio", "pedestrian_zone", "street", "veranda", "parklet", "balcony", "roof",
        )
    }
}

/**
 * One café as the app sees it. [location] is null when none of the object's
 * nodes are in the area (it is still listed, without a distance). For ways and
 * relations it is a representative point: the mean of the distinct node
 * coordinates we could resolve (good enough for a café building; not an
 * exact centroid or a point guaranteed to lie inside the polygon).
 */
data class Cafe(
    val id: OsmId,
    val tags: Map<String, String>,
    val location: LatLon?,
) {
    val name: String get() = tags["name"] ?: "Unnamed café"
    val outdoorSeating: OutdoorSeating get() = OutdoorSeating.of(tags)
    fun openState(now: LocalDateTime): OpenState = OpeningHours.evaluate(tags["opening_hours"], now)
}

object CafeLoader {
    /**
     * All `amenity=cafe` objects in [bbox] (nodes, ways, relations), paged
     * with the framework's keyset pagination. Runs on the store's thread.
     */
    fun load(store: OsmStore, bbox: Bbox): List<Cafe> {
        val objects = mutableListOf<OsmObject>()
        var after: OsmId? = null
        while (true) {
            val page = store.query(Query(tags = listOf(TagFilter.Equals("amenity", "cafe")), bbox = bbox, after = after, limit = PAGE))
            objects += page
            if (page.size < PAGE) break
            after = page.last().id
        }
        return objects.map { Cafe(it.id, it.tags, representativePoint(store, it)) }
    }

    /** Node: its coordinate. Way: mean of its nodes. Relation: mean over node members and way members' nodes (one level). */
    fun representativePoint(store: OsmStore, obj: OsmObject): LatLon? = when (obj) {
        is OsmObject.Node -> LatLon(obj.lat, obj.lon)
        is OsmObject.Way -> mean(obj.nodeIds.distinct().mapNotNull { nodeLocation(store, it) })
        is OsmObject.Relation -> mean(
            obj.members.flatMap { member ->
                when (member.id.kind) {
                    OsmKind.NODE -> listOfNotNull(nodeLocation(store, member.id.id))
                    OsmKind.WAY -> (store.get(member.id) as? OsmObject.Way)?.nodeIds.orEmpty().distinct()
                        .mapNotNull { nodeLocation(store, it) }
                    // Nested relations are not followed: a café is rarely one.
                    OsmKind.RELATION -> emptyList()
                }
            },
        )
    }

    private fun nodeLocation(store: OsmStore, id: Long): LatLon? =
        (store.get(OsmId(OsmKind.NODE, id)) as? OsmObject.Node)?.let { LatLon(it.lat, it.lon) }

    private fun mean(points: List<LatLon>): LatLon? =
        if (points.isEmpty()) null else LatLon(points.sumOf { it.lat } / points.size, points.sumOf { it.lon } / points.size)

    private const val PAGE = 500
}

object Geo {
    /** Edge length of the default area, in km. A default, not a cap: change it freely. */
    const val AREA_SIZE_KM = 10.0

    private const val EARTH_RADIUS_M = 6_371_000.0
    private const val KM_PER_DEGREE_LAT = 111.32

    /** A [sizeKm] × [sizeKm] box centred on [center] (flat-earth approximation, fine at city scale). */
    fun squareAround(center: LatLon, sizeKm: Double = AREA_SIZE_KM): Bbox {
        val halfLat = sizeKm / 2 / KM_PER_DEGREE_LAT
        val halfLon = sizeKm / 2 / (KM_PER_DEGREE_LAT * cos(Math.toRadians(center.lat)))
        return Bbox(
            west = (center.lon - halfLon).coerceAtLeast(-180.0),
            south = (center.lat - halfLat).coerceAtLeast(-85.0),
            east = (center.lon + halfLon).coerceAtMost(180.0),
            north = (center.lat + halfLat).coerceAtMost(85.0),
        )
    }

    fun center(bbox: Bbox) = LatLon((bbox.south + bbox.north) / 2, (bbox.west + bbox.east) / 2)

    fun contains(bbox: Bbox, point: LatLon) =
        point.lat in bbox.south..bbox.north && point.lon in bbox.west..bbox.east

    /** Great-circle distance in metres (haversine). */
    fun distanceMeters(a: LatLon, b: LatLon): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(sqrt(h))
    }

    fun formatDistance(meters: Double): String =
        if (meters < 1000) "${meters.toInt()} m" else "%.1f km".format(meters / 1000)
}

package lol.osm.cantino.cafe

import lol.osm.cantino.AreaConfig
import lol.osm.cantino.Bbox
import lol.osm.cantino.ImportOptions
import lol.osm.cantino.ImportProfile
import lol.osm.cantino.KeepRule
import lol.osm.cantino.OsmKind
import lol.osm.cantino.OsmId
import lol.osm.cantino.OsmObject
import lol.osm.cantino.OsmStore
import lol.osm.cantino.Query
import lol.osm.cantino.TagFilter
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
 * relations it is a simple mean of available vertices; not an exact centroid
 * or a point guaranteed to lie inside the polygon.
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

/**
 * What the app keeps of the downloaded OSM data: points of interest only.
 *
 * The café app reads nothing but `amenity=cafe` objects and what they
 * reference, and the basemap draws everything else (roads, buildings,
 * water), so storing the rest would only cost space: on the Salt Lake City
 * test area the POI profile is 8.8 MB instead of 128.5 MB
 * (docs/guide/downloading.md, "Keep only what you need"). The same eight keys
 * are kept on iOS (`CafeProfile` in Cafes.swift); keep them in step.
 *
 * Why this is enough for the app: the import keeps references whole (a kept
 * way keeps all its nodes, a kept relation all its members, recursively), so
 * café markers and the inspector's way-node and member rows behave as with a
 * full import. Objects that match none of the keys (a street, a plain
 * building) are not in the area file. The basemap is independent of this
 * profile; it is downloaded in full for the same bbox.
 *
 * Changing the rules only affects new downloads: the published area keeps
 * the profile it was built with ([lol.osm.cantino.ImportReport.profile]).
 */
object CafeProfile {
    /** `amenity`, `shop`, `tourism`, `leisure`, `craft`, `office`, `healthcare`, `historic` on nodes, ways and relations. */
    val POI = ImportProfile(
        listOf("amenity", "shop", "tourism", "leisure", "craft", "office", "healthcare", "historic")
            .map { KeepRule(OsmKind.entries.toSet(), it) },
    )

    val IMPORT_OPTIONS = ImportOptions(profile = POI)

    /** The [lol.osm.cantino.AreaManager] config for the café app's downloads. */
    val AREA_CONFIG = AreaConfig(importOptions = IMPORT_OPTIONS)

    /**
     * A short label for the profile an area records ([lol.osm.cantino.ImportReport.profile]),
     * or null when it records none. Null is not shown as "all map data": the
     * area metadata currently drops the recorded profile (core sidecar
     * parser), so a POI area reads back without one as well.
     */
    fun label(profile: ImportProfile?): String? = when (profile) {
        null -> null
        POI -> "points of interest only"
        else -> "filtered: " + profile.keep.joinToString(", ") { it.key }
    }
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

    /**
     * Café marker policy: node coordinate, mean of a way's distinct available
     * vertices, or the first usable node/way member of a relation. Nested
     * relations are deliberately skipped. This is a marker, not geometry.
     */
    fun representativePoint(store: OsmStore, obj: OsmObject): LatLon? = when (obj) {
        is OsmObject.Node -> LatLon(obj.lat, obj.lon)
        is OsmObject.Way -> store.wayCoordinates(obj.id.id)?.filterNotNull()?.distinct()
            ?.takeIf { it.isNotEmpty() }?.let { points ->
                LatLon(points.map { it.lat }.average(), points.map { it.lon }.average())
            }
        is OsmObject.Relation -> obj.members.asSequence()
            .filter { it.id.kind != OsmKind.RELATION }
            .mapNotNull { store.get(it.id) }
            .firstNotNullOfOrNull { representativePoint(store, it) }
    }

    private const val PAGE = 500
}

object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

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

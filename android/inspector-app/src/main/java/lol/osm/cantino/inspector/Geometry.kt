package lol.osm.cantino.inspector

import lol.osm.cantino.Bbox
import lol.osm.cantino.OsmId
import lol.osm.cantino.OsmKind
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Tap-to-inspect geometry, all in the app (Cantino has no "what is here"
 * query): a bbox query around the tap gives *candidates*, and this file
 * decides which of them the tap actually hit.
 *
 * - Tagged nodes: distance to the node.
 * - Ways: distance to the nearest segment of the way's coordinates
 *   (OsmStore.wayCoordinates). Null entries are vertices outside the area:
 *   no segment is drawn or measured across them.
 * - Relations: never a hit. Their bbox intersects the tap box, nothing more
 *   is known without assembling geometry (out of scope), so they are "near".
 * - A closed way (a park) tapped in its middle is "near", not "hit": there is
 *   deliberately no point-in-polygon test.
 *
 * Untagged way vertices are never returned by a bbox query (they are not
 * spatially indexed), which is why the way's geometry, not its vertices,
 * decides a hit. Tapping the middle of a long sidewalk segment finds the way.
 *
 * Distances use a local flat projection around the tap: good to centimetres
 * over the tens of metres a tap covers. Mirrors ios/inspector-app Geometry.swift.
 */

/** A WGS84 point in degrees. */
data class LatLon(val lat: Double, val lon: Double) {
    override fun toString() = String.format(Locale.ROOT, "%.5f, %.5f", lat, lon)
}

object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0
    private const val METERS_PER_DEGREE = EARTH_RADIUS_M * Math.PI / 180

    fun center(bbox: Bbox) = LatLon((bbox.south + bbox.north) / 2, (bbox.west + bbox.east) / 2)

    fun contains(bbox: Bbox, point: LatLon) =
        point.lat in bbox.south..bbox.north && point.lon in bbox.west..bbox.east

    /** A square box reaching [meters] from [center] in each direction. */
    fun around(center: LatLon, meters: Double): Bbox {
        val dLat = meters / METERS_PER_DEGREE
        val dLon = meters / (METERS_PER_DEGREE * cos(Math.toRadians(center.lat)))
        return Bbox(center.lon - dLon, center.lat - dLat, center.lon + dLon, center.lat + dLat)
    }

    /** Great-circle distance in metres (haversine). */
    fun distanceMeters(a: LatLon, b: LatLon): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(sqrt(h))
    }

    fun formatDistance(meters: Double): String = when {
        meters < 10 -> String.format(Locale.ROOT, "%.1f m", meters)
        meters < 1000 -> "${meters.toInt()} m"
        else -> String.format(Locale.ROOT, "%.1f km", meters / 1000)
    }

    /** [point] in metres east/north of [origin] (equirectangular, fine at tap scale). */
    private fun project(origin: LatLon, point: LatLon): Pair<Double, Double> =
        (point.lon - origin.lon) * METERS_PER_DEGREE * cos(Math.toRadians(origin.lat)) to
            (point.lat - origin.lat) * METERS_PER_DEGREE

    /** Distance in metres from [p] to the segment [a]–[b]. */
    fun pointToSegmentMeters(p: LatLon, a: LatLon, b: LatLon): Double {
        val (ax, ay) = project(p, a)
        val (bx, by) = project(p, b)
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        // Parameter of the projection of p (the origin) onto the segment, clamped to it.
        val t = if (lengthSquared == 0.0) 0.0 else (-(ax * dx + ay * dy) / lengthSquared).coerceIn(0.0, 1.0)
        return hypot(ax + t * dx, ay + t * dy)
    }

    /**
     * Distance in metres from [p] to a way's line, or null when none of its
     * vertices are in the area. Segments are measured only between two
     * present vertices; a vertex next to a gap still counts as a point.
     */
    fun distanceToLineMeters(p: LatLon, line: List<LatLon?>): Double? {
        var best: Double? = null
        line.forEachIndexed { i, a ->
            if (a == null) return@forEachIndexed
            val b = line.getOrNull(i + 1)
            val d = if (b != null) pointToSegmentMeters(p, a, b) else pointToSegmentMeters(p, a, a)
            if (best == null || d < best!!) best = d
        }
        return best
    }

    /**
     * A way's coordinates split into drawable runs at missing vertices
     * (outside the area). A run of one vertex is kept: it is drawn as a point.
     */
    fun segments(line: List<LatLon?>): List<List<LatLon>> {
        val runs = mutableListOf<List<LatLon>>()
        var current = mutableListOf<LatLon>()
        for (point in line) {
            if (point == null) {
                if (current.isNotEmpty()) runs += current
                current = mutableListOf()
            } else {
                current += point
            }
        }
        if (current.isNotEmpty()) runs += current
        return runs
    }
}

/** Whether a tap is on an object, or only inside its bounding box. */
enum class Match(val label: String) { HIT("hit"), NEAR("near (bbox)") }

/** One object under a tap, as the candidate sheet lists it. */
data class Candidate(
    val id: OsmId,
    val tags: Map<String, String>,
    val match: Match,
    /** Metres to the node or the nearest way segment; null for relations and ways with no vertex in the area. */
    val distanceMeters: Double?,
)

object HitTest {
    /** Tap radius when there is no screen to measure (debug taps, tests). */
    const val DEFAULT_RADIUS_M = 15.0

    fun node(id: OsmId, tags: Map<String, String>, at: LatLon, tap: LatLon, radiusMeters: Double): Candidate {
        val d = Geo.distanceMeters(tap, at)
        return Candidate(id, tags, if (d <= radiusMeters) Match.HIT else Match.NEAR, d)
    }

    fun way(id: OsmId, tags: Map<String, String>, line: List<LatLon?>, tap: LatLon, radiusMeters: Double): Candidate {
        val d = Geo.distanceToLineMeters(tap, line)
        return Candidate(id, tags, if (d != null && d <= radiusMeters) Match.HIT else Match.NEAR, d)
    }

    fun relation(id: OsmId, tags: Map<String, String>) = Candidate(id, tags, Match.NEAR, null)

    /** Hits first, then near; nearest first (unknown distance last); then node < way < relation, then ID. */
    val ORDER: Comparator<Candidate> = compareBy<Candidate> { it.match.ordinal }
        .thenBy(nullsLast()) { it.distanceMeters }
        .thenBy { it.id.kind.ordinal }
        .thenBy { it.id.id }

    fun order(candidates: List<Candidate>): List<Candidate> = candidates.sortedWith(ORDER)
}

object Labels {
    /** Keys that say what an object is, most telling first. */
    private val PRIMARY = listOf(
        "amenity", "shop", "highway", "footway", "crossing", "barrier", "railway", "public_transport",
        "leisure", "tourism", "building", "landuse", "natural", "waterway", "boundary", "type",
    )

    /** "highway=footway", the first telling tag, or the first tag, or "untagged". */
    fun primaryTag(tags: Map<String, String>): String {
        val key = PRIMARY.firstOrNull { it in tags } ?: tags.keys.minOrNull() ?: return "untagged"
        return "$key=${tags[key]}"
    }

    fun kindId(id: OsmId) = "${id.kind.name.lowercase()} ${id.id}"

    /** "node/123" for osm.org links and debug arguments. */
    fun path(id: OsmId) = "${id.kind.name.lowercase()}/${id.id}"

    /** Parses "way/123" (debug launch arguments). */
    fun parsePath(value: String): OsmId? {
        val slash = value.indexOf('/')
        if (slash < 0) return null
        val kind = OsmKind.entries.firstOrNull { it.name.lowercase() == value.substring(0, slash).lowercase() } ?: return null
        val id = value.substring(slash + 1).toLongOrNull()?.takeIf { it > 0 } ?: return null
        return OsmId(kind, id)
    }

    /** The object's page on openstreetmap.org (needs a network connection). */
    fun osmOrgUrl(id: OsmId) = "https://www.openstreetmap.org/${path(id)}"
}

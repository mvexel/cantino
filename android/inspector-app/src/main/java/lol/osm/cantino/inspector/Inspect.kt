package lol.osm.cantino.inspector

import lol.osm.cantino.Coordinate
import lol.osm.cantino.OsmId
import lol.osm.cantino.OsmKind
import lol.osm.cantino.OsmObject
import lol.osm.cantino.OsmStore
import lol.osm.cantino.Query

/*
 * Everything Inspector reads from the store, as plain values. Every function
 * here runs on the store's thread (InspectorStore.withStore) and hands the UI
 * values it can keep. Mirrors ios/inspector-app Inspect.swift.
 */

/** What to draw for an object: lines (broken at the area edge) and points. */
data class Shape(val lines: List<List<LatLon>> = emptyList(), val points: List<LatLon> = emptyList()) {
    val isEmpty get() = lines.isEmpty() && points.isEmpty()
    operator fun plus(other: Shape) = Shape(lines + other.lines, points + other.points)
    fun allPoints(): List<LatLon> = lines.flatten() + points
}

/** The candidates under a tap. [truncated]: more objects than [Inspect.TAP_LIMIT] were in the box. */
data class TapResult(val at: LatLon, val radiusMeters: Double, val candidates: List<Candidate>, val truncated: Boolean)

/** One way node row of the navigator. [tagged]: the node has tags (it is also a POI, crossing, …). */
data class NodeRef(val id: Long, val location: LatLon?, val tagged: Boolean)

/** One relation member row of the navigator. */
data class MemberRow(val member: OsmObject.Member, val present: Boolean, val label: String?) {
    fun text(index: Int): String {
        val role = member.role.ifEmpty { "(no role)" }
        return "${index + 1}. $role · ${Labels.kindId(member.id)}" +
            (label?.let { " · $it" } ?: "") + if (present) "" else " · not in this area"
    }
}

/** Everything the object navigator shows. */
data class ObjectDetail(
    val id: OsmId,
    val obj: OsmObject?,
    val nodeRefs: List<NodeRef> = emptyList(),
    val members: List<MemberRow> = emptyList(),
    /** Way nodes or relation members that are not in this area (each distinct reference once). */
    val missingReferences: Int = 0,
    /** Distinct references (way nodes or relation members). */
    val references: Int = 0,
    /** For nodes: the ways that use it (see [Inspect.parentWays]). */
    val parentWays: List<OsmObject.Way> = emptyList(),
    val shape: Shape = Shape(),
)

object Inspect {
    /** At most this many candidates per tap (the sheet says when there were more). */
    const val TAP_LIMIT = 200
    /** Member ways drawn for a selected relation; more would be one native call each. */
    const val RELATION_WAY_LIMIT = 300

    fun latLon(c: Coordinate?) = c?.let { LatLon(it.lat, it.lon) }

    /** A way's coordinates, null entries for vertices outside the area; null if the way is not in the area. */
    fun line(store: OsmStore, wayId: Long): List<LatLon?>? = store.wayCoordinates(wayId)?.map(::latLon)

    /**
     * Tap-to-inspect: every object whose bbox meets a square of
     * [radiusMeters] around [at] (one bbox query, no tag filter), classified
     * by [HitTest] and ordered hits first, nearest first.
     */
    fun tap(store: OsmStore, at: LatLon, radiusMeters: Double): TapResult {
        val found = store.query(Query(bbox = Geo.around(at, radiusMeters), limit = TAP_LIMIT + 1))
        val candidates = found.take(TAP_LIMIT).map { obj ->
            when (obj) {
                is OsmObject.Node -> HitTest.node(obj.id, obj.tags, LatLon(obj.lat, obj.lon), at, radiusMeters)
                is OsmObject.Way -> HitTest.way(obj.id, obj.tags, line(store, obj.id.id).orEmpty(), at, radiusMeters)
                is OsmObject.Relation -> HitTest.relation(obj.id, obj.tags)
            }
        }
        return TapResult(at, radiusMeters, HitTest.order(candidates), found.size > TAP_LIMIT)
    }

    /** What to highlight for [obj]: a node's point, a way's runs, a relation's member ways and nodes (best effort). */
    fun shape(store: OsmStore, obj: OsmObject): Shape = when (obj) {
        is OsmObject.Node -> Shape(points = listOf(LatLon(obj.lat, obj.lon)))
        is OsmObject.Way -> Shape(lines = Geo.segments(line(store, obj.id.id).orEmpty()))
        is OsmObject.Relation -> {
            val ways = obj.members.filter { it.id.kind == OsmKind.WAY }.map { it.id.id }.distinct().take(RELATION_WAY_LIMIT)
            val nodeIds = obj.members.filter { it.id.kind == OsmKind.NODE }.map { it.id }.distinct().take(OsmStore.MAX_BATCH)
            val points = store.get(nodeIds).mapNotNull { (it as? OsmObject.Node)?.let { n -> LatLon(n.lat, n.lon) } }
            Shape(lines = ways.flatMap { Geo.segments(line(store, it).orEmpty()) }, points = points)
        }
    }

    /** Shapes for a page of query results (relations are listed, not drawn). */
    fun shapes(store: OsmStore, objects: List<OsmObject>): Shape =
        objects.filter { it !is OsmObject.Relation }.fold(Shape()) { acc, obj -> acc + shape(store, obj) }

    /**
     * "Ways using this node", best effort. Cantino has no node→way index, so:
     * the ways whose bbox contains the node (a bbox query on a box of about
     * a metre), keeping those whose node list has it. Complete for ways in the
     * area, because a way's bbox covers each of its vertices that is in the
     * area; it cannot know ways that are not in the extract at all.
     */
    fun parentWays(store: OsmStore, node: OsmObject.Node): List<OsmObject.Way> {
        val box = Geo.around(LatLon(node.lat, node.lon), 1.0)
        val ways = mutableListOf<OsmObject.Way>()
        var after: OsmId? = null
        while (true) {
            val page = store.query(Query(bbox = box, after = after, limit = 1000))
            page.filterIsInstance<OsmObject.Way>().filterTo(ways) { node.id.id in it.nodeIds }
            if (page.size < 1000) return ways
            after = page.last().id
        }
    }

    /** The navigator's model for [id]: the object, its references resolved (batch get), and its shape. */
    fun detail(store: OsmStore, id: OsmId): ObjectDetail {
        val obj = store.get(id) ?: return ObjectDetail(id, null)
        return when (obj) {
            is OsmObject.Node -> ObjectDetail(id, obj, parentWays = parentWays(store, obj), shape = shape(store, obj))
            is OsmObject.Way -> {
                val distinct = obj.nodeIds.distinct()
                val nodes = distinct.chunked(OsmStore.MAX_BATCH)
                    .flatMap { chunk -> store.get(chunk.map { OsmId(OsmKind.NODE, it) }) }
                    .mapIndexed { i, n -> distinct[i] to (n as? OsmObject.Node) }.toMap()
                val refs = obj.nodeIds.map { ref ->
                    val node = nodes[ref]
                    NodeRef(ref, node?.let { LatLon(it.lat, it.lon) }, node?.tags?.isNotEmpty() == true)
                }
                ObjectDetail(
                    id, obj, nodeRefs = refs, missingReferences = distinct.count { nodes[it] == null },
                    references = distinct.size, shape = shape(store, obj),
                )
            }
            is OsmObject.Relation -> {
                val distinct = obj.members.map { it.id }.distinct()
                val targets = distinct.chunked(OsmStore.MAX_BATCH).flatMap { store.get(it) }
                    .mapIndexed { i, t -> distinct[i] to t }.toMap()
                val rows = obj.members.map { member ->
                    val target = targets[member.id]
                    MemberRow(member, target != null, target?.let { it.tags["name"] ?: Labels.primaryTag(it.tags) })
                }
                ObjectDetail(
                    id, obj, members = rows, missingReferences = distinct.count { targets[it] == null },
                    references = distinct.size, shape = shape(store, obj),
                )
            }
        }
    }
}

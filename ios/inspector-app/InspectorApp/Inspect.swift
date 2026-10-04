import Cantino
import Foundation

/*
 * Everything Inspector reads from the store, as plain values. Every function
 * here runs on the store's thread (InspectorStore.withStore) and hands the UI
 * values it can keep. Port of android/inspector-app Inspect.kt.
 */

/// What to draw for an object: lines (broken at the area edge) and points.
struct Shape: Hashable, Sendable {
    var lines: [[LatLon]] = []
    var points: [LatLon] = []

    var isEmpty: Bool { lines.isEmpty && points.isEmpty }
    static func + (a: Shape, b: Shape) -> Shape { Shape(lines: a.lines + b.lines, points: a.points + b.points) }
    var allPoints: [LatLon] { lines.flatMap { $0 } + points }
}

/// The candidates under a tap. `truncated`: more objects than ``Inspect/tapLimit`` were in the box.
struct TapResult: Hashable, Sendable {
    let at: LatLon
    let radiusMeters: Double
    let candidates: [Candidate]
    let truncated: Bool
}

/// One way node row of the navigator. `tagged`: the node has tags (it is also a POI, crossing, …).
struct NodeRef: Hashable, Sendable {
    let id: Int64
    let location: LatLon?
    let tagged: Bool
}

/// One relation member row of the navigator.
struct MemberRow: Hashable, Sendable {
    let member: OsmObject.Member
    let present: Bool
    let label: String?

    func text(index: Int) -> String {
        let role = member.role.isEmpty ? "(no role)" : member.role
        return "\(index + 1). \(role) · \(Labels.kindId(member.id))" + (label.map { " · \($0)" } ?? "")
            + (present ? "" : " · not in this area")
    }
}

/// Everything the object navigator shows.
struct ObjectDetail: Sendable {
    let id: OsmId
    let object: OsmObject?
    var nodeRefs: [NodeRef] = []
    var members: [MemberRow] = []
    /// Way nodes or relation members that are not in this area (each distinct reference once).
    var missingReferences = 0
    /// Distinct references (way nodes or relation members).
    var references = 0
    /// For nodes: the ways that use it (see ``Inspect/parentWays(_:_:)``).
    var parentWays: [OsmObject.Way] = []
    var shape = Shape()
}

enum Inspect {
    /// At most this many candidates per tap (the sheet says when there were more).
    static let tapLimit = 200
    /// Member ways drawn for a selected relation; more would be one native call each.
    static let relationWayLimit = 300

    /// A way's coordinates, nil entries for vertices outside the area; nil if the way is not in the area.
    static func line(_ store: OsmStore, _ wayId: Int64) throws -> [LatLon?]? {
        try store.wayCoordinates(wayId)?.map { $0.map { LatLon(lat: $0.lat, lon: $0.lon) } }
    }

    /// Tap-to-inspect: every object whose bbox meets a square of
    /// `radiusMeters` around `at` (one bbox query, no tag filter), classified
    /// by ``HitTest`` and ordered hits first, nearest first.
    static func tap(_ store: OsmStore, at: LatLon, radiusMeters: Double) throws -> TapResult {
        let found = try store.query(Query(bbox: Geo.around(at, meters: radiusMeters), limit: tapLimit + 1))
        let candidates = try found.prefix(tapLimit).map { object -> Candidate in
            switch object {
            case .node(let n):
                HitTest.node(n.id, n.tags, at: LatLon(lat: n.lat, lon: n.lon), tap: at, radiusMeters: radiusMeters)
            case .way(let w):
                HitTest.way(w.id, w.tags, line: try line(store, w.id.id) ?? [], tap: at, radiusMeters: radiusMeters)
            case .relation(let r):
                HitTest.relation(r.id, r.tags)
            }
        }
        return TapResult(at: at, radiusMeters: radiusMeters, candidates: HitTest.order(candidates), truncated: found.count > tapLimit)
    }

    /// What to highlight: a node's point, a way's runs, a relation's member ways and nodes (best effort).
    static func shape(_ store: OsmStore, _ object: OsmObject) throws -> Shape {
        switch object {
        case .node(let n):
            return Shape(points: [LatLon(lat: n.lat, lon: n.lon)])
        case .way(let w):
            return Shape(lines: Geo.segments(try line(store, w.id.id) ?? []))
        case .relation(let r):
            let ways = Array(unique(r.members.filter { $0.id.kind == .way }.map(\.id.id)).prefix(relationWayLimit))
            let nodeIds = Array(unique(r.members.filter { $0.id.kind == .node }.map(\.id)).prefix(OsmStore.maxBatch))
            let points = try store.get(nodeIds).compactMap { $0?.node.map { LatLon(lat: $0.lat, lon: $0.lon) } }
            var lines: [[LatLon]] = []
            for way in ways { lines += Geo.segments(try line(store, way) ?? []) }
            return Shape(lines: lines, points: points)
        }
    }

    /// Shapes for a page of query results (relations are listed, not drawn).
    static func shapes(_ store: OsmStore, _ objects: [OsmObject]) throws -> Shape {
        try objects.filter { $0.relation == nil }.reduce(Shape()) { $0 + (try shape(store, $1)) }
    }

    /// "Ways using this node", best effort. Cantino has no node→way index,
    /// so: the ways whose bbox contains the node (a bbox query on a box of
    /// about a metre), keeping those whose node list has it. Complete for
    /// ways in the area (a way's bbox covers each of its vertices that is in
    /// the area); it cannot know ways that are not in the extract at all.
    static func parentWays(_ store: OsmStore, _ node: OsmObject.Node) throws -> [OsmObject.Way] {
        let box = Geo.around(LatLon(lat: node.lat, lon: node.lon), meters: 1)
        var ways: [OsmObject.Way] = []
        var after: OsmId?
        while true {
            let page = try store.query(Query(bbox: box, after: after, limit: 1000))
            ways += page.compactMap(\.way).filter { $0.nodeIds.contains(node.id.id) }
            guard page.count == 1000, let last = page.last else { return ways }
            after = last.id
        }
    }

    /// The navigator's model for `id`: the object, its references resolved (batch get), and its shape.
    static func detail(_ store: OsmStore, _ id: OsmId) throws -> ObjectDetail {
        guard let object = try store.get(id) else { return ObjectDetail(id: id, object: nil) }
        var detail = ObjectDetail(id: id, object: object)
        detail.shape = try shape(store, object)
        switch object {
        case .node(let node):
            detail.parentWays = try parentWays(store, node)
        case .way(let way):
            let distinct = unique(way.nodeIds)
            var nodes: [Int64: OsmObject.Node] = [:]
            for chunk in stride(from: 0, to: distinct.count, by: OsmStore.maxBatch).map({ Array(distinct[$0..<min($0 + OsmStore.maxBatch, distinct.count)]) }) {
                for (ref, found) in zip(chunk, try store.get(chunk.map { OsmId(.node, $0) })) { nodes[ref] = found?.node }
            }
            detail.nodeRefs = way.nodeIds.map { ref in
                let node = nodes[ref]
                return NodeRef(id: ref, location: node.map { LatLon(lat: $0.lat, lon: $0.lon) }, tagged: node.map { !$0.tags.isEmpty } ?? false)
            }
            detail.missingReferences = distinct.count { nodes[$0] == nil }
            detail.references = distinct.count
        case .relation(let relation):
            let distinct = unique(relation.members.map(\.id))
            var targets: [OsmId: OsmObject] = [:]
            for start in stride(from: 0, to: distinct.count, by: OsmStore.maxBatch) {
                let chunk = Array(distinct[start..<min(start + OsmStore.maxBatch, distinct.count)])
                for (ref, found) in zip(chunk, try store.get(chunk)) { targets[ref] = found }
            }
            detail.members = relation.members.map { member in
                let target = targets[member.id]
                return MemberRow(member: member, present: target != nil, label: target.map { $0.tags["name"] ?? Labels.primaryTag($0.tags) })
            }
            detail.missingReferences = distinct.count { targets[$0] == nil }
            detail.references = distinct.count
        }
        return detail
    }

    /// Distinct values in first-seen order (Kotlin's `distinct()`).
    static func unique<T: Hashable>(_ values: [T]) -> [T] {
        var seen = Set<T>()
        return values.filter { seen.insert($0).inserted }
    }
}

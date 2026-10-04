import Cantino
import SwiftUI

/// Inspector for one OSM object of the offline area: any kind, not only
/// cafés (port of the Android café app's DetailActivity).
///
/// Shows, as stored by Cantino (nothing interpreted except the clearly
/// labelled "App interpretation" block for cafés):
/// - every raw tag, sorted by key;
/// - metadata (version, timestamp, changeset, user), or "not stored" for
///   untagged nodes imported without metadata (only their location version);
/// - the underlying object: a node's coordinate; a way's node list in order,
///   repeats included (a closed way repeats its first node), each tappable;
///   a relation's members (role, kind, id), each tappable, and marked "not in
///   this area" when ``OsmStore/get(_:)`` returns nil (extracts are clipped
///   at the area edge).
///
/// All store access goes through ``CafeStore`` (its one thread).
struct DetailView: View {
    let id: OsmId
    @State private var model: DetailModel?
    @State private var error: String?

    // Text(verbatim:) for anything with an ID: a string literal with an
    // interpolated integer is localized, and IDs would get digit grouping.
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 4) {
                if let error {
                    Text("Could not read the offline area: \(error)").foregroundStyle(Palette.closed)
                } else if let model {
                    content(model)
                } else {
                    Text(verbatim: "Loading \(id.kind.label) \(id.id)…")
                }
            }
            .padding()
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .navigationTitle(Text(verbatim: "\(id.kind.label) \(id.id)"))
        .navigationBarTitleDisplayMode(.inline)
        .task(id: id) {
            let id = id
            do {
                model = try await CafeStore.shared.withStore { store, area in
                    try DetailModel.load(store, id, profile: area.metadata?.report?.profile)
                }
            } catch {
                self.error = "\(error)"
            }
        }
    }

    @ViewBuilder
    private func content(_ model: DetailModel) -> some View {
        let kind = id.kind.label
        if let object = model.object {
            let name = object.tags["name"]
            Text(name ?? "\(kind) \(id.id)").font(.title2.bold())
            if name != nil { Text(verbatim: "\(kind) \(id.id)").font(.footnote).foregroundStyle(Palette.muted) }

            // App interpretation (cafés only), clearly separated from the raw data.
            if object.tags["amenity"] == "cafe" {
                section("App interpretation")
                let open = OpeningHours.evaluate(object.tags["opening_hours"], at: .now)
                coloredStatus(open)
                if case .unknown(let reason) = open {
                    Text("Why unknown: \(reason)").font(.footnote).foregroundStyle(Palette.muted)
                }
                mono("opening_hours = \(object.tags["opening_hours"] ?? "(missing)")")
                Text(OutdoorSeating.of(object.tags).label)
                if let point = model.point {
                    Text("Map point: \(point.description)" + (object.node == nil ? " (mean of its nodes)" : ""))
                        .font(.footnote).foregroundStyle(Palette.muted)
                }
            }

            section("Tags (\(object.tags.count))")
            if object.tags.isEmpty { Text("No tags.").foregroundStyle(Palette.muted) }
            ForEach(object.tags.sorted { $0.key < $1.key }, id: \.key) { tag in
                mono("\(tag.key) = \(tag.value)")
            }

            section("Metadata")
            if let metadata = object.metadata {
                mono(Self.describe(metadata))
            } else {
                let version = object.node.map { "; location version \($0.locationVersion)" } ?? ""
                Text("Not stored (untagged node imported without metadata)\(version).")
            }

            switch object {
            case .node(let node):
                section("Node")
                mono(String(format: "lat %.7f\nlon %.7f", node.lat, node.lon))
            case .way(let way):
                let distinct = Set(way.nodeIds).count
                let closed = way.nodeIds.count > 1 && way.nodeIds.first == way.nodeIds.last
                section("Way nodes (\(way.nodeIds.count) refs, \(distinct) distinct\(closed ? ", closed" : ""))")
                ForEach(Array(model.nodeRefs.enumerated()), id: \.offset) { index, ref in
                    let label = "\(index + 1). node \(ref.id)  \(ref.location?.description ?? "not in this area")"
                    if ref.location != nil {
                        link(label, OsmId(.node, ref.id))
                    } else {
                        mono(label, color: Palette.muted)
                    }
                }
            case .relation(let relation):
                section("Relation members (\(relation.members.count))")
                ForEach(Array(model.members.enumerated()), id: \.offset) { index, row in
                    if row.present {
                        link(row.label(index: index), row.member.id)
                    } else {
                        mono(row.label(index: index), color: Palette.muted)
                    }
                }
            }
        } else {
            Text(verbatim: "\(kind) \(id.id)").font(.title2.bold())
            Text(CafeProfile.notFoundText(model.profile))
        }
    }

    static func describe(_ m: ObjectMetadata) -> String {
        // Absent source fields are stored as 0/"" by the core: show them as unknown, not as real values.
        let timestamp = m.timestampSeconds > 0
            ? ISO8601DateFormatter().string(from: Date(timeIntervalSince1970: TimeInterval(m.timestampSeconds)))
            : "unknown"
        return """
        version   \(m.version > 0 ? "\(m.version)" : "unknown")
        timestamp \(timestamp)
        changeset \(m.changeset > 0 ? "\(m.changeset)" : "unknown")
        user      \(m.user.isEmpty ? "unknown" : m.user)\(m.uid > 0 ? " (uid \(m.uid))" : "")
        """
    }

    private func section(_ title: String) -> some View {
        Text(title).font(.headline).padding(.top, 16).padding(.bottom, 2)
    }

    private func mono(_ value: String, color: Color = .primary) -> some View {
        Text(value).font(.footnote.monospaced()).foregroundStyle(color).textSelection(.enabled)
    }

    /// A row that opens the inspector for `target`.
    private func link(_ label: String, _ target: OsmId) -> some View {
        NavigationLink(value: target) {
            Text(label).font(.footnote.monospaced()).multilineTextAlignment(.leading)
                .padding(.vertical, 6) // a finger-sized target
        }
    }
}

/// What the store thread hands to the UI: plain values only.
struct DetailModel: Sendable {
    struct NodeRef: Sendable {
        let id: Int64
        let location: LatLon?
    }

    let object: OsmObject?
    let nodeRefs: [NodeRef]
    let members: [RelationMemberRow]
    let point: LatLon?
    /// The profile the area was imported with (nil: everything).
    var profile: ImportProfile? = nil

    /// Resolves node coordinates and member presence on the store thread
    /// (one batch get each), so the UI only renders.
    ///
    /// Way nodes and relation members of a kept object are never filtered
    /// out by the app's import profile (the import keeps references whole),
    /// so a missing one is outside the area. An object opened directly may
    /// also be filtered out; `profile` lets the "not found" text say so.
    static func load(_ store: OsmStore, _ id: OsmId, profile: ImportProfile? = nil) throws -> DetailModel {
        let object = try store.get(id)
        var refs: [NodeRef] = []
        if let way = object?.way {
            let nodes = try store.get(way.nodeIds.map { OsmId(.node, $0) })
            refs = zip(way.nodeIds, nodes).map { ref, node in
                NodeRef(id: ref, location: node?.node.map { LatLon(lat: $0.lat, lon: $0.lon) })
            }
        }
        let members = try object?.relation.map { try RelationMemberRow.rows(store, $0) } ?? []
        let point = try object.flatMap { try CafeLoader.representativePoint(store, $0) }
        return DetailModel(object: object, nodeRefs: refs, members: members, point: point, profile: profile)
    }
}

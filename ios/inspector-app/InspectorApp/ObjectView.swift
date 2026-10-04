import Cantino
import SwiftUI

/// The object navigator: one OSM object of the offline area, raw, with its
/// references followed. Port of android/inspector-app ObjectActivity (grown
/// from ios/cafe-app DetailView.swift).
///
/// - Tags, metadata (or "not stored" for untagged nodes and their location version).
/// - Node: coordinate, and "ways using this node" (``Inspect/parentWays(_:_:)``, best effort).
/// - Way: its nodes in order (repeats kept), tagged vertices marked, each a link.
/// - Relation: its members with roles, each a link.
/// - Missing references: way nodes or members not in this area (batch `get` nils).
/// - "Show on map" returns to the map with the object highlighted; the
///   openstreetmap.org link is the only thing here that needs the network.
struct ObjectView: View {
    let id: OsmId
    let onShowOnMap: (OsmId) -> Void
    @State private var detail: ObjectDetail?
    @State private var error: String?

    // Text(verbatim:) for anything with an ID: a localized literal would group digits.
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 4) {
                if let error {
                    Text("Could not read the offline area: \(error)").foregroundStyle(Palette.error)
                } else if let detail {
                    content(detail)
                } else {
                    Text(verbatim: "Loading \(Labels.kindId(id))…")
                }
            }
            .padding()
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .navigationTitle(Text(verbatim: Labels.kindId(id)))
        .navigationBarTitleDisplayMode(.inline)
        .task(id: id) {
            let id = id
            do {
                detail = try await InspectorStore.shared.withStore { store, _ in try Inspect.detail(store, id) }
            } catch {
                self.error = "\(error)"
            }
        }
    }

    @ViewBuilder
    private func content(_ detail: ObjectDetail) -> some View {
        let name = detail.object?.tags["name"]
        Text(verbatim: name ?? Labels.kindId(id)).font(.title2.bold())
        if name != nil { Text(verbatim: Labels.kindId(id)).font(.footnote).foregroundStyle(Palette.muted) }
        HStack {
            Button("Show on map") { onShowOnMap(id) }
                .buttonStyle(.borderedProminent)
                .disabled(detail.object == nil || detail.shape.isEmpty)
            if let url = URL(string: Labels.osmOrgUrl(id)) {
                Link("openstreetmap.org ↗", destination: url).buttonStyle(.bordered)
            }
        }
        Text("The openstreetmap.org page needs a network connection; everything else here is offline.")
            .font(.caption).foregroundStyle(Palette.muted)

        if let object = detail.object {
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
                section("Ways using this node (\(detail.parentWays.count))")
                Text("Best effort: the ways in this area whose bbox contains the node and whose node list has it. Ways outside the extract are unknown.")
                    .font(.caption).foregroundStyle(Palette.muted)
                ForEach(detail.parentWays, id: \.id) { way in
                    link("way \(way.id.id) · \(way.tags["name"] ?? Labels.primaryTag(way.tags))", way.id)
                }
            case .way(let way):
                let closed = way.nodeIds.count > 1 && way.nodeIds.first == way.nodeIds.last
                section("Way nodes (\(way.nodeIds.count) refs, \(detail.references) distinct\(closed ? ", closed" : ""))")
                missing(detail, "nodes")
                ForEach(Array(detail.nodeRefs.enumerated()), id: \.offset) { index, ref in
                    let label = "\(index + 1). node \(ref.id)  \(ref.location?.description ?? "not in this area")\(ref.tagged ? " · tagged" : "")"
                    if ref.location != nil {
                        link(label, OsmId(.node, ref.id))
                    } else {
                        mono(label, color: Palette.muted)
                    }
                }
            case .relation(let relation):
                section("Relation members (\(relation.members.count))")
                missing(detail, "members")
                ForEach(Array(detail.members.enumerated()), id: \.offset) { index, row in
                    if row.present {
                        link(row.text(index: index), row.member.id)
                    } else {
                        mono(row.text(index: index), color: Palette.muted)
                    }
                }
            }
        } else {
            Text("Not in this area: the offline extract does not contain this object (it lies outside the area, or was clipped at its edge).")
        }
    }

    private func missing(_ detail: ObjectDetail, _ what: String) -> some View {
        Text(detail.missingReferences == 0
             ? "All \(detail.references) \(what) are in this area."
             : "\(detail.missingReferences) of \(detail.references) \(what) are not in this area (outside the extract).")
            .font(.footnote)
            .foregroundStyle(detail.missingReferences == 0 ? Palette.muted : Palette.error)
    }

    static func describe(_ m: ObjectMetadata) -> String {
        // Absent source fields are stored as 0/"" by the core: show them as unknown.
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
        Text(verbatim: title).font(.headline).padding(.top, 16).padding(.bottom, 2)
    }

    private func mono(_ value: String, color: Color = .primary) -> some View {
        Text(verbatim: value).font(.footnote.monospaced()).foregroundStyle(color).textSelection(.enabled)
    }

    /// A row that opens the navigator for `target`.
    private func link(_ label: String, _ target: OsmId) -> some View {
        NavigationLink(value: target) {
            Text(verbatim: label).font(.footnote.monospaced()).multilineTextAlignment(.leading)
                .padding(.vertical, 6) // a finger-sized target
        }
    }
}

import Cantino
import MapLibre
import SwiftUI

/// The map screen's data: the offline area, its cafés, the "nearby" origin
/// and the filters (port of the Android café app's MapScreen).
///
/// Data flow: ``CafeStore/withStore(_:)`` (store thread) → ``CafeLoader`` →
/// plain ``Cafe`` values on the main actor → filtered here → GeoJSON-like
/// point features for MapLibre and rows for the list. Opening hours are
/// evaluated at the moment of filtering (and again when the app becomes
/// active), so "open now" means now.
///
/// Filters keep unknown separate: "Open" never includes unknown and "Closed"
/// never includes unknown; the Unknown choice shows exactly the cafés we
/// cannot decide about.
@MainActor
@Observable
final class MapModel {
    enum OutdoorFilter: String, CaseIterable, Identifiable {
        case any = "Any", yes = "Yes", no = "No", unknown = "Unknown"
        var id: Self { self }
    }

    enum OpenFilter: String, CaseIterable, Identifiable {
        case any = "Any", open = "Open", closed = "Closed", unknown = "Unknown"
        var id: Self { self }
    }

    /// A café with everything evaluated for display at one instant.
    struct Row: Identifiable, Hashable {
        let cafe: Cafe
        let open: OpenState
        let outdoor: OutdoorSeating
        let distance: Double?
        var id: OsmId { cafe.id }
    }

    private(set) var area: AreaInfo?
    private(set) var cafes: [Cafe] = []
    private(set) var reference: ReferenceLocation?
    private(set) var loadError: String?
    private(set) var loaded = false
    var outdoorFilter = OutdoorFilter.any
    var openFilter = OpenFilter.any
    /// The instant "open now" is evaluated at; ``touch()`` moves it to now.
    private(set) var now = LocalTime.now

    func touch() { now = .now }

    func load(debugLocation: LatLon?, device: DeviceLocation) async {
        do {
            let (area, cafes) = try await CafeStore.shared.withStore { store, area in
                (area, try area.metadata.map { try CafeLoader.load(store, bbox: $0.bbox) })
            }
            self.area = area
            self.cafes = cafes ?? []
            if cafes == nil { loadError = "The area has no metadata (bbox unknown); cannot search it." }
            reference = Self.referenceLocation(area, debugLocation: debugLocation, device: device)
            Log.map.info("loaded \(self.cafes.count) cafés")
        } catch {
            Log.map.error("loading cafés failed: \(error, privacy: .public)")
            loadError = "Could not open the offline area: \(error)"
        }
        loaded = true
    }

    /// "Nearby" origin: debug override, else a recent device fix inside the area, else the area centre.
    private static func referenceLocation(_ area: AreaInfo, debugLocation: LatLon?, device: DeviceLocation) -> ReferenceLocation? {
        if let debugLocation { return ReferenceLocation(point: debugLocation, source: .debugOverride) }
        guard let bbox = area.metadata?.bbox else { return nil }
        if let here = device.lastKnown(), Geo.contains(bbox, here) { return ReferenceLocation(point: here, source: .device) }
        return ReferenceLocation(point: Geo.center(bbox), source: .areaCentre)
    }

    /// Every café evaluated at ``now``.
    var all: [Row] {
        let origin = reference?.point
        return cafes.map { cafe in
            let distance: Double? = if let origin, let location = cafe.location { Geo.distanceMeters(origin, location) } else { nil }
            return Row(cafe: cafe, open: cafe.openState(at: now), outdoor: cafe.outdoorSeating, distance: distance)
        }
    }

    /// The filtered rows, nearest first (cafés without a location last).
    func rows(from all: [Row]) -> [Row] {
        all.filter { Self.matches(outdoorFilter, $0.outdoor) && Self.matches(openFilter, $0.open) }
            .sorted { ($0.distance ?? .infinity) < ($1.distance ?? .infinity) }
    }

    /// Counts per choice, given the other filter, so Unknown is visible as a number.
    func outdoorCounts(_ all: [Row]) -> [OutdoorFilter: Int] {
        let base = all.filter { Self.matches(openFilter, $0.open) }
        return Dictionary(uniqueKeysWithValues: OutdoorFilter.allCases.map { f in (f, base.count { Self.matches(f, $0.outdoor) }) })
    }

    func openCounts(_ all: [Row]) -> [OpenFilter: Int] {
        let base = all.filter { Self.matches(outdoorFilter, $0.outdoor) }
        return Dictionary(uniqueKeysWithValues: OpenFilter.allCases.map { f in (f, base.count { Self.matches(f, $0.open) }) })
    }

    nonisolated static func matches(_ filter: OutdoorFilter, _ value: OutdoorSeating) -> Bool {
        switch filter {
        case .any: true
        case .yes: value == .yes
        case .no: value == .no
        case .unknown: value.isUnknown
        }
    }

    nonisolated static func matches(_ filter: OpenFilter, _ value: OpenState) -> Bool {
        switch filter {
        case .any: true
        case .open: value == .open
        case .closed: value == .closed
        case .unknown: value.isUnknown
        }
    }

    /// Snapshot age as shown in the status line ("2026-10-03 20:30", UTC).
    var statusLine: String {
        if let loadError { return loadError }
        guard let area else { return "Loading cafés from the offline area…" }
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd HH:mm"
        formatter.timeZone = TimeZone(identifier: "UTC")
        formatter.locale = Locale(identifier: "en_US_POSIX")
        let snapshot = area.metadata?.snapshotTimestamp.map(formatter.string(from:)) ?? "unknown"
        let ref = reference.map { "distances from \($0.source.label)" } ?? "no reference location"
        return "Offline area · OSM data as of \(snapshot) UTC · \(ref)"
    }
}

struct MapScreen: View {
    let app: AppModel
    @State private var model = MapModel()
    @State private var showList = false
    @State private var confirmRefresh = false
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.openCafe) private var openCafe

    var body: some View {
        let all = model.all
        let rows = model.rows(from: all)
        let outdoorCounts = model.outdoorCounts(all)
        let openCounts = model.openCounts(all)
        VStack(alignment: .leading, spacing: 6) {
            Group {
                HStack {
                    Text(verbatim: "Cafés · \(rows.count) of \(all.count)").font(.title3.bold())
                    Spacer()
                    Button(showList ? "Map" : "List") { showList.toggle() }
                    Button("Refresh area") { confirmRefresh = true }.disabled(model.area == nil)
                }
                Text(model.statusLine).font(.caption).foregroundStyle(Palette.muted)
                filterRow("Outdoor", selection: $model.outdoorFilter, counts: outdoorCounts)
                filterRow("Now", selection: $model.openFilter, counts: openCounts)
                legend
            }
            .padding(.horizontal, 8)
            ZStack {
                if let area = model.area {
                    CafeMapView(area: area, rows: rows, reference: model.reference)
                } else {
                    Color(white: 0.95)
                }
                if showList {
                    CafeList(rows: rows).background(Color(uiColor: .systemBackground))
                }
            }
        }
        .toolbar(.hidden, for: .navigationBar)
        .task {
            let launch = app.debugLaunch
            showList = launch.showList
            model.outdoorFilter = launch.outdoor ?? .any
            model.openFilter = launch.now ?? .any
            await model.load(debugLocation: app.debugLocation, device: app.device)
            if let object = launch.object { openCafe(object) }
            if launch.refresh, let area = model.area { app.refresh(area, fromDebugLaunch: true) }
        }
        .onChange(of: scenePhase) { _, phase in if phase == .active { model.touch() } }
        .alert("Refresh the offline area?", isPresented: $confirmRefresh) {
            Button("Refresh") { if let area = model.area { app.refresh(area) } }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Downloads fresh map data and basemap for the same area. The current area stays usable until the new one is complete.\n\n"
                + "Privacy: the area's bounds are sent to SliceOSM and the Protomaps tile host.")
        }
    }

    /// One filter as a row of chips, each with its count (segmented
    /// controls truncate "Unknown 30" on a phone).
    private func filterRow<F: RawRepresentable & CaseIterable & Identifiable & Hashable>(
        _ label: String, selection: Binding<F>, counts: [F: Int]
    ) -> some View where F.RawValue == String, F.AllCases: RandomAccessCollection {
        HStack(spacing: 4) {
            Text("\(label):").font(.footnote.bold()).frame(width: 58, alignment: .leading)
            ForEach(F.allCases) { choice in
                let selected = selection.wrappedValue == choice
                Button {
                    selection.wrappedValue = choice
                } label: {
                    Text(choice.rawValue == "Any" ? "Any" : "\(choice.rawValue) \(counts[choice] ?? 0)")
                        .font(.footnote)
                        .lineLimit(1)
                        .fixedSize()
                        .padding(.horizontal, 8)
                        .padding(.vertical, 5)
                        .background(selected ? Color.accentColor : Color(white: 0.92), in: Capsule())
                        .foregroundStyle(selected ? Color.white : Color.primary)
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(selected ? .isSelected : [])
            }
        }
    }

    private var legend: some View {
        HStack(spacing: 14) {
            coloredStatus(.open)
            coloredStatus(.closed)
            coloredStatus(.unknown(reason: ""))
            Text("\(Text("●").foregroundStyle(Palette.me)) you")
        }
        .font(.caption)
    }
}

/// The nearby list: name, distance, status, outdoor seating and the raw tag.
struct CafeList: View {
    let rows: [MapModel.Row]

    var body: some View {
        List(rows) { row in
            NavigationLink(value: row.cafe.id) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(row.cafe.name).font(.headline)
                    Text("\(row.distance.map(Geo.formatDistance) ?? "no location in area") · \(coloredStatus(row.open)) · \(row.outdoor.label)")
                        .font(.subheadline)
                    Text(hoursLine(row)).font(.caption.monospaced()).foregroundStyle(Palette.muted)
                }
            }
        }
        .listStyle(.plain)
    }

    private func hoursLine(_ row: MapModel.Row) -> String {
        guard let hours = row.cafe.tags["opening_hours"] else { return "no opening_hours" }
        if case .unknown(let reason) = row.open { return "opening_hours=\(hours) (\(reason))" }
        return "opening_hours=\(hours)"
    }
}

/// MapLibre Native: the bundled Protomaps style pointed at the area's
/// PMTiles, cafés and the reference point as circle layers, tap → detail.
struct CafeMapView: UIViewRepresentable {
    let area: AreaInfo
    let rows: [MapModel.Row]
    let reference: ReferenceLocation?
    @Environment(\.openCafe) private var openCafe

    static let cafeSource = "cafes"
    static let cafeLayer = "cafe-points"
    static let labelLayer = "cafe-labels"
    static let meSource = "me"
    static let meLayer = "me-point"

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> MLNMapView {
        let map = MLNMapView(frame: .zero)
        let bbox = area.metadata?.bbox
        let center = reference?.point ?? bbox.map(Geo.center) ?? LatLon(lat: 0, lon: 0)
        context.coordinator.bbox = bbox
        context.coordinator.map = map
        context.coordinator.initialCenter = CLLocationCoordinate2D(latitude: center.lat, longitude: center.lon)
        if bbox != nil { map.minimumZoomLevel = 10 }
        // Delegate first: a local style loads synchronously, so
        // didFinishLoadingStyle can fire while styleJSON is being set.
        map.delegate = context.coordinator
        map.styleJSON = Self.styleJSON(area)
        let tap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.tapped(_:)))
        for recognizer in map.gestureRecognizers ?? [] where (recognizer as? UITapGestureRecognizer)?.numberOfTapsRequired == 2 {
            tap.require(toFail: recognizer)
        }
        map.addGestureRecognizer(tap)
        return map
    }

    func updateUIView(_ map: MLNMapView, context: Context) {
        context.coordinator.onSelect = { id in openCafe(id) }
        context.coordinator.show(rows: rows, reference: reference)
    }

    /// The bundled Protomaps style, pointed at this area's PMTiles (or a
    /// blank style without a basemap).
    static func styleJSON(_ area: AreaInfo) -> String {
        let blank = ##"{"version":8,"glyphs":"asset://glyphs/{fontstack}/{range}.pbf","sources":{},"layers":[{"id":"bg","type":"background","paint":{"background-color":"#f2efe9"}}]}"##
        guard let url = area.pmtilesURL,
              let file = Bundle.main.url(forResource: "style", withExtension: "json"),
              let style = try? String(contentsOf: file, encoding: .utf8) else { return blank }
        return style.replacingOccurrences(of: "__PMTILES_URL__", with: url)
    }

    @MainActor
    final class Coordinator: NSObject, @preconcurrency MLNMapViewDelegate {
        weak var map: MLNMapView?
        var bbox: Bbox?
        var onSelect: ((OsmId) -> Void)?
        var initialCenter: CLLocationCoordinate2D?
        private var style: MLNStyle?
        private var rows: [MapModel.Row] = []
        private var reference: ReferenceLocation?

        func show(rows: [MapModel.Row], reference: ReferenceLocation?) {
            self.rows = rows
            self.reference = reference
            apply()
        }

        // Explicit selectors: the Swift spellings of these optional delegate
        // methods are easy to get subtly wrong, and a mismatch fails silently.
        @objc(mapView:didFinishLoadingStyle:)
        func mapView(_ mapView: MLNMapView, didFinishLoading style: MLNStyle) {
            Log.map.debug("style loaded; adding the café overlay")
            self.style = style
            if let initialCenter {
                // Set once the style is in, so nothing in the style resets it.
                mapView.setCenter(initialCenter, zoomLevel: 14.5, animated: false)
                self.initialCenter = nil
            }
            addOverlay(style)
            apply()
        }

        @objc(mapViewDidFailLoadingMap:withError:)
        func mapViewDidFailLoadingMap(_ mapView: MLNMapView, withError error: Error) {
            Log.map.error("map failed to load: \(error, privacy: .public)")
        }

        /// The basemap covers only the area: keep the camera's centre over it.
        @objc(mapView:shouldChangeFromCamera:toCamera:)
        func mapView(_ mapView: MLNMapView, shouldChangeFrom oldCamera: MLNMapCamera, to newCamera: MLNMapCamera) -> Bool {
            guard let bbox else { return true }
            let c = newCamera.centerCoordinate
            return Geo.contains(bbox, LatLon(lat: c.latitude, lon: c.longitude))
        }

        @objc func tapped(_ gesture: UITapGestureRecognizer) {
            guard let map, gesture.state == .ended else { return }
            let point = gesture.location(in: map)
            let slop: CGFloat = 14
            let rect = CGRect(x: point.x - slop, y: point.y - slop, width: 2 * slop, height: 2 * slop)
            guard let hit = map.visibleFeatures(in: rect, styleLayerIdentifiers: [CafeMapView.cafeLayer]).first,
                  let key = hit.attribute(forKey: "key") as? String,
                  let row = rows.first(where: { Self.key($0.cafe.id) == key }) else { return }
            onSelect?(row.cafe.id)
        }

        private func addOverlay(_ style: MLNStyle) {
            let cafes = MLNShapeSource(identifier: CafeMapView.cafeSource, shape: nil, options: nil)
            let me = MLNShapeSource(identifier: CafeMapView.meSource, shape: nil, options: nil)
            style.addSource(cafes)
            style.addSource(me)

            let meLayer = MLNCircleStyleLayer(identifier: CafeMapView.meLayer, source: me)
            meLayer.circleRadius = NSExpression(forConstantValue: 7)
            meLayer.circleColor = NSExpression(forConstantValue: UIColor(Palette.me))
            meLayer.circleStrokeColor = NSExpression(forConstantValue: UIColor.white)
            meLayer.circleStrokeWidth = NSExpression(forConstantValue: 3)
            style.addLayer(meLayer)

            let dots = MLNCircleStyleLayer(identifier: CafeMapView.cafeLayer, source: cafes)
            dots.circleRadius = NSExpression(forConstantValue: 6.5)
            dots.circleColor = NSExpression(
                forMLNMatchingKey: NSExpression(forKeyPath: "open"),
                in: [
                    NSExpression(forConstantValue: "open"): NSExpression(forConstantValue: UIColor(Palette.open)),
                    NSExpression(forConstantValue: "closed"): NSExpression(forConstantValue: UIColor(Palette.closed)),
                ],
                default: NSExpression(forConstantValue: UIColor(Palette.unknown)))
            dots.circleStrokeColor = NSExpression(forConstantValue: UIColor.white)
            dots.circleStrokeWidth = NSExpression(forConstantValue: 2)
            style.addLayer(dots)

            let labels = MLNSymbolStyleLayer(identifier: CafeMapView.labelLayer, source: cafes)
            labels.text = NSExpression(forKeyPath: "name")
            labels.textFontNames = NSExpression(forConstantValue: ["Noto Sans Regular"])
            labels.textFontSize = NSExpression(forConstantValue: 11)
            labels.textOffset = NSExpression(forConstantValue: NSValue(cgVector: CGVector(dx: 0, dy: 1.1)))
            labels.textAnchor = NSExpression(forConstantValue: "top")
            labels.textOptional = NSExpression(forConstantValue: true)
            labels.textHaloColor = NSExpression(forConstantValue: UIColor.white)
            labels.textHaloWidth = NSExpression(forConstantValue: 1.5)
            labels.minimumZoomLevel = 14
            style.addLayer(labels)
        }

        private func apply() {
            guard let style else { return }
            let features: [MLNPointFeature] = rows.compactMap { row in
                guard let location = row.cafe.location else { return nil }
                let feature = MLNPointFeature()
                feature.coordinate = CLLocationCoordinate2D(latitude: location.lat, longitude: location.lon)
                feature.attributes = ["key": Self.key(row.cafe.id), "name": row.cafe.name, "open": Self.openKey(row.open)]
                return feature
            }
            Log.map.debug("showing \(features.count) café points")
            (style.source(withIdentifier: CafeMapView.cafeSource) as? MLNShapeSource)?.shape =
                MLNShapeCollectionFeature(shapes: features)
            if let reference {
                let me = MLNPointFeature()
                me.coordinate = CLLocationCoordinate2D(latitude: reference.point.lat, longitude: reference.point.lon)
                (style.source(withIdentifier: CafeMapView.meSource) as? MLNShapeSource)?.shape = me
            }
        }

        static func key(_ id: OsmId) -> String { "\(id.kind.label)/\(id.id)" }

        static func openKey(_ state: OpenState) -> String {
            switch state {
            case .open: "open"
            case .closed: "closed"
            case .unknown: "unknown"
            }
        }
    }
}

/// Opens the detail screen for an object (set by ``MapScreen`` through the
/// navigation path), so the UIKit map can navigate.
struct OpenCafeKey: EnvironmentKey {
    static let defaultValue: @MainActor @Sendable (OsmId) -> Void = { _ in }
}

extension EnvironmentValues {
    var openCafe: @MainActor @Sendable (OsmId) -> Void {
        get { self[OpenCafeKey.self] }
        set { self[OpenCafeKey.self] = newValue }
    }
}

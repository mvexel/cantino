import Cantino
import MapLibre
import SwiftUI

/// The map screen's state: the query, its results, the tap candidates and
/// the selection. Port of android/inspector-app MapScreen.kt.
///
/// - Query bar (``QueryBar``): terms ANDed, optional "this view only" (the
///   visible bbox), pages of ``Queries/page`` with "Load more" (keyset
///   `after`), "Count all". Results are drawn (nodes as dots, ways as lines;
///   relations are listed only) and listed. Errors are shown verbatim.
/// - Tap: ``Inspect/tap(_:at:radiusMeters:)`` lists every bbox candidate, hit or near.
/// - Selecting a row highlights its geometry, broken where it leaves the
///   area; "Inspect" opens the object navigator.
///
/// All store work runs in ``InspectorStore/withStore(_:)``; this class only
/// holds plain values.
@MainActor
@Observable
final class MapModel {
    enum Mode { case none, results, tap }

    /// A list row: what it shows and what it selects.
    struct Row: Identifiable, Hashable {
        let id: OsmId
        let title: String
        let detail: String
        let hit: Bool
    }

    private(set) var area: AreaInfo?
    var input = ""
    var viewOnly = false
    private(set) var status = "Loading the offline area…"
    private(set) var statusIsError = false
    private(set) var mode = Mode.none
    private(set) var results: [OsmObject] = []
    private(set) var resultsShape = Shape()
    private(set) var hasMore = false
    private(set) var tap: TapResult?
    private(set) var selected: OsmId?
    private(set) var highlightShape = Shape()
    /// Text sheets (about, counts): title and body.
    var sheet: TextSheet?

    @ObservationIgnored private var filters: [TagFilter] = []
    @ObservationIgnored private var queryBbox: Bbox?
    @ObservationIgnored private var after: OsmId?
    /// The UIKit map, for the visible bbox and camera moves.
    @ObservationIgnored let handle = MapHandle()

    struct TextSheet: Identifiable {
        let title: String
        let body: String
        var id: String { title }
    }

    func load() async {
        do {
            area = try await InspectorStore.shared.withStore { _, area in area }
            status = idleStatus
        } catch {
            showError("Could not open the offline area: \(error)")
        }
    }

    private var idleStatus: String {
        let snapshot = area?.metadata?.snapshotTimestamp.map(formatInstant) ?? "unknown"
        return "Offline · OSM data as of \(snapshot) · tap the map to inspect"
    }

    // MARK: - query

    func runQuery() {
        let parsed: [TagFilter]
        do {
            parsed = try QueryBar.parse(input)
        } catch {
            return showError("Query: \(error.message)")
        }
        filters = parsed
        queryBbox = viewOnly ? handle.visibleBbox() : nil
        results = []
        resultsShape = Shape()
        after = nil
        hasMore = false
        tap = nil
        mode = .results
        Task { await loadPage() }
    }

    func loadPage() async {
        let filters = filters, bbox = queryBbox, after = after
        status = "Querying…"
        statusIsError = false
        do {
            let (page, shape) = try await InspectorStore.shared.withStore { store, _ in
                let page = try Queries.page(store, filters, bbox: bbox, after: after)
                return (page, try Inspect.shapes(store, page))
            }
            results += page
            resultsShape = resultsShape + shape
            hasMore = page.count == Queries.page
            self.after = page.last?.id ?? after
            let text = QueryBar.format(filters)
            status = "\(text.isEmpty ? "(no filter)" : text)\(bbox != nil ? " · this view" : " · whole area")"
            mode = .results
        } catch {
            showError(Self.verbatim(error))
        }
    }

    func countAll() async {
        let filters = filters, bbox = queryBbox
        do {
            let counts = try await InspectorStore.shared.withStore { store, _ in try Queries.count(store, filters, bbox: bbox) }
            let text = QueryBar.format(filters)
            status = "\(text.isEmpty ? "(no filter)" : text): \(counts)"
            statusIsError = false
        } catch {
            showError(Self.verbatim(error))
        }
    }

    /// Counts every acceptance query over the whole area (debug `-counts`), logged for the record.
    func runCounts() async {
        status = "Counting the acceptance queries…"
        do {
            let lines = try await InspectorStore.shared.withStore { store, area in
                try ["snapshot \(area.metadata?.snapshotTimestamp.map { ISO8601DateFormatter().string(from: $0) } ?? "nil")"]
                    + Checks.acceptance.map { q in "\(q)\t\(try Queries.count(store, try QueryBar.parse(q), bbox: nil))" }
            }
            for line in lines { Log.counts.info("\(line, privacy: .public)") }
            status = idleStatus
            sheet = TextSheet(title: "Acceptance counts", body: lines.joined(separator: "\n"))
        } catch {
            showError(Self.verbatim(error))
        }
    }

    var rows: [Row] {
        switch mode {
        case .none: []
        case .results:
            results.map { Row(id: $0.id, title: Self.title($0.tags, $0.id), detail: "\(Labels.kindId($0.id)) · \(Labels.primaryTag($0.tags))", hit: false) }
        case .tap:
            (tap?.candidates ?? []).map { c in
                let distance = c.distanceMeters.map(Geo.formatDistance) ?? "distance unknown"
                return Row(id: c.id, title: Self.title(c.tags, c.id),
                           detail: "\(c.match.label) · \(distance) · \(Labels.kindId(c.id)) · \(Labels.primaryTag(c.tags))", hit: c.match == .hit)
            }
        }
    }

    var panelTitle: String {
        let selection = selected.map { " · selected \(Labels.kindId($0))" } ?? ""
        switch mode {
        case .none: return selected.map { "Selected \(Labels.kindId($0))" } ?? ""
        case .results: return "\(results.count)\(hasMore ? "+" : "") results\(selection)"
        case .tap:
            guard let tap else { return "" }
            let hits = tap.candidates.count { $0.match == .hit }
            return "\(tap.candidates.count)\(tap.truncated ? "+" : "") here: \(hits) hit, \(tap.candidates.count - hits) near (r \(Int(tap.radiusMeters)) m)\(selection)"
        }
    }

    var panelVisible: Bool { mode != .none || selected != nil }

    // MARK: - tap and selection

    func runTap(_ at: LatLon, radiusMeters: Double, selectIndex: Int? = nil) async {
        do {
            let result = try await InspectorStore.shared.withStore { store, _ in try Inspect.tap(store, at: at, radiusMeters: radiusMeters) }
            tap = result
            mode = .tap
            selected = nil
            highlightShape = Shape()
            for c in result.candidates {
                Log.map.info("tap \(result.at.description, privacy: .public) r=\(result.radiusMeters): \(c.match.name, privacy: .public) \(Labels.kindId(c.id), privacy: .public) \(c.distanceMeters ?? -1) \(Labels.primaryTag(c.tags), privacy: .public)")
            }
            status = "Tap at \(result.at.description): candidates from a bbox query; \"hit\" = on the node or way line"
            statusIsError = false
            if let index = selectIndex, index < result.candidates.count {
                await select(result.candidates[index].id, moveCamera: false)
            }
        } catch {
            showError(Self.verbatim(error))
        }
    }

    /// Highlights `id`'s geometry (gaps where vertices are outside the area); optionally moves the camera to it.
    func select(_ id: OsmId, moveCamera: Bool) async {
        do {
            let shape = try await InspectorStore.shared.withStore { store, _ in try store.get(id).map { try Inspect.shape(store, $0) } }
            selected = id
            highlightShape = shape ?? Shape()
            if shape == nil { status = "\(Labels.kindId(id)) is not in this area" }
            if moveCamera, let shape { handle.move(to: shape) }
        } catch {
            showError(Self.verbatim(error))
        }
    }

    func closePanel() {
        mode = .none
        selected = nil
        tap = nil
        highlightShape = Shape()
        status = idleStatus
        statusIsError = false
    }

    func showAbout() {
        guard let area else { return }
        Task.detached {
            let info = area.basemapURL.flatMap { try? PmtilesInfo.read($0) }
            let text = About.text(area, info: info, now: Date())
            await MainActor.run { self.sheet = TextSheet(title: "About this area", body: text) }
        }
    }

    private func showError(_ message: String) {
        status = message
        statusIsError = true
        Log.map.warning("\(message, privacy: .public)")
    }

    /// Errors as they come: Cantino's category and message, unedited.
    nonisolated static func verbatim(_ error: Error) -> String {
        if let error = error as? CantinoError {
            switch error {
            case .invalidArgument(let m): return "Cantino InvalidArgument: \(m)"
            case .invalidFile(let m): return "Cantino InvalidFile: \(m)"
            case .io(let m): return "Cantino Io: \(m)"
            case .wrongThread(let m): return "Cantino WrongThread: \(m)"
            }
        }
        return "\(type(of: error)): \(error)"
    }

    nonisolated static func title(_ tags: [String: String], _ id: OsmId) -> String {
        tags["name"] ?? (tags.isEmpty ? Labels.kindId(id) : Labels.primaryTag(tags))
    }
}

/// The map view behind the model: visible bbox and camera moves.
@MainActor
final class MapHandle {
    weak var map: MLNMapView?

    func visibleBbox() -> Bbox? {
        guard let bounds = map?.visibleCoordinateBounds else { return nil }
        return Bbox(west: bounds.sw.longitude, south: bounds.sw.latitude, east: bounds.ne.longitude, north: bounds.ne.latitude)
    }

    func move(to shape: Shape) {
        guard let map else { return }
        let points = shape.allPoints
        guard let first = points.first else { return }
        if Set(points).count == 1 {
            map.setCenter(CLLocationCoordinate2D(latitude: first.lat, longitude: first.lon), zoomLevel: 18, animated: true)
        } else {
            let sw = CLLocationCoordinate2D(latitude: points.map(\.lat).min()!, longitude: points.map(\.lon).min()!)
            let ne = CLLocationCoordinate2D(latitude: points.map(\.lat).max()!, longitude: points.map(\.lon).max()!)
            map.setVisibleCoordinateBounds(MLNCoordinateBounds(sw: sw, ne: ne), edgePadding: UIEdgeInsets(top: 48, left: 48, bottom: 48, right: 48), animated: true, completionHandler: nil)
        }
    }
}

struct MapScreen: View {
    static let panelHeight: CGFloat = 330
    let app: AppModel
    @State private var model = MapModel()
    @State private var confirmRefresh = false
    @Environment(\.openObject) private var openObject

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    TextField("amenity=cafe  shop=*  !opening_hours", text: $model.input)
                        .font(.body.monospaced())
                        .textFieldStyle(.roundedBorder)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .submitLabel(.search)
                        .onSubmit { model.runQuery() }
                    Button("Run") { model.runQuery() }.buttonStyle(.borderedProminent)
                }
                HStack {
                    Toggle("This view only", isOn: $model.viewOnly).toggleStyle(.switch).fixedSize().font(.footnote)
                    Spacer()
                    Menu("Checks") {
                        ForEach(Checks.all) { check in
                            Button("\(check.title)\n\(check.query)") {
                                model.input = check.query
                                model.runQuery()
                            }
                        }
                    }
                    Menu("Area") {
                        Button("About this area") { model.showAbout() }
                        Button("Refresh area") { confirmRefresh = true }
                        Button("Download another area") { app.showChooser("") }
                    }
                }
                Text(model.status).font(.caption).foregroundStyle(model.statusIsError ? Palette.error : Palette.muted)
            }
            .padding(.horizontal, 8)
            ZStack(alignment: .bottom) {
                if let area = model.area {
                    InspectorMapView(area: area, model: model, launch: runner)
                } else {
                    Color(white: 0.95)
                }
                if model.panelVisible {
                    panel.frame(height: Self.panelHeight)
                }
            }
        }
        .toolbar(.hidden, for: .navigationBar)
        .task {
            await model.load()
        }
        .onChange(of: app.showOnMap) { _, id in
            guard let id else { return }
            app.showOnMap = nil
            Task { await model.select(id, moveCamera: true) }
        }
        .sheet(item: $model.sheet) { sheet in
            NavigationStack {
                ScrollView {
                    Text(sheet.body).font(.footnote.monospaced()).textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading).padding()
                }
                .navigationTitle(sheet.title)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar { Button("Close") { model.sheet = nil } }
            }
        }
        .alert("Refresh the offline area?", isPresented: $confirmRefresh) {
            Button("Refresh") { if let area = model.area { app.refresh(area) } }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Downloads fresh data and basemap for the same area. The current area stays usable until the new one is complete.\n\n"
                + "Privacy: the area's bounds are sent to SliceOSM and the Protomaps tile host.")
        }
    }

    /// The debug launch options; the map runs them once its style is in.
    private var runner: DebugLaunchRunner {
        DebugLaunchRunner(app: app, model: model, openObject: openObject)
    }

    private var panel: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(model.panelTitle).font(.subheadline.bold()).lineLimit(2)
                Spacer()
                if let selected = model.selected {
                    Button("Inspect") { openObject(selected) }.buttonStyle(.bordered)
                }
                Button { model.closePanel() } label: { Image(systemName: "xmark") }.buttonStyle(.bordered)
            }
            List(model.rows) { row in
                Button {
                    Task { await model.select(row.id, moveCamera: false) }
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(row.title).font(.subheadline.bold())
                            .foregroundStyle(row.id == model.selected ? Color(uiColor: Palette.highlight) : .primary)
                        Text(row.detail).font(.caption).foregroundStyle(row.hit ? Palette.hit : Palette.muted)
                    }
                }
            }
            .listStyle(.plain)
            if model.mode == .results {
                HStack {
                    Button("Load more") { Task { await model.loadPage() } }.disabled(!model.hasMore)
                    Button("Count all") { Task { await model.countAll() } }
                }
                .buttonStyle(.bordered)
            }
        }
        .padding(8)
        .background(Color(uiColor: .systemBackground))
        .shadow(radius: 4)
    }
}

/// Runs the debug launch options (``DebugLaunch``) once the map is ready.
@MainActor
struct DebugLaunchRunner {
    let app: AppModel
    let model: MapModel
    let openObject: @MainActor @Sendable (OsmId) -> Void

    func run() {
        let launch = app.takeDebugLaunch()
        Task {
            if let query = launch.query {
                model.input = query
                model.viewOnly = launch.viewOnly
                model.runQuery()
            }
            if let tap = launch.tap {
                await model.runTap(tap, radiusMeters: launch.tapRadius ?? HitTest.defaultRadiusM, selectIndex: launch.select)
            }
            if let object = launch.object { openObject(object) }
            if launch.about { model.showAbout() }
            if launch.counts { await model.runCounts() }
        }
    }
}

/// MapLibre Native: the bundled Protomaps style pointed at the area's
/// PMTiles; results, the selection and the tap point as overlay layers.
struct InspectorMapView: UIViewRepresentable {
    let area: AreaInfo
    let model: MapModel
    let launch: DebugLaunchRunner

    static let results = "results"
    static let highlight = "highlight"
    static let tapSource = "tap"

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> MLNMapView {
        let map = MLNMapView(frame: .zero)
        let bbox = area.metadata?.bbox
        let pending = launch.app.peekDebugTap
        let center = pending.tap ?? bbox.map(Geo.center) ?? LatLon(lat: 0, lon: 0)
        let coordinator = context.coordinator
        coordinator.bbox = bbox
        coordinator.model = model
        coordinator.launch = launch
        coordinator.initialCenter = CLLocationCoordinate2D(latitude: center.lat, longitude: center.lon)
        coordinator.initialZoom = pending.zoom ?? (pending.tap != nil ? 18 : 15)
        model.handle.map = map
        if bbox != nil { map.minimumZoomLevel = 11 }
        // The bottom panel (330 pt) covers part of the map: centre the camera in the part above it.
        map.automaticallyAdjustsContentInset = false
        map.contentInset = UIEdgeInsets(top: 0, left: 0, bottom: MapScreen.panelHeight, right: 0)
        // Delegate first: a local style loads synchronously.
        map.delegate = coordinator
        map.styleJSON = Self.styleJSON(area)
        let tap = UITapGestureRecognizer(target: coordinator, action: #selector(Coordinator.tapped(_:)))
        for recognizer in map.gestureRecognizers ?? [] where (recognizer as? UITapGestureRecognizer)?.numberOfTapsRequired == 2 {
            tap.require(toFail: recognizer)
        }
        map.addGestureRecognizer(tap)
        return map
    }

    func updateUIView(_ map: MLNMapView, context: Context) {
        let tapShape = model.mode == .tap ? (model.tap.map { Shape(points: [$0.at]) } ?? Shape()) : Shape()
        context.coordinator.show(results: model.mode == .results ? model.resultsShape : Shape(),
                                 highlight: model.highlightShape, tap: tapShape)
    }

    /// The bundled Protomaps style, pointed at this area's PMTiles (blank without a basemap).
    static func styleJSON(_ area: AreaInfo) -> String {
        let blank = ##"{"version":8,"glyphs":"asset://glyphs/{fontstack}/{range}.pbf","sources":{},"layers":[{"id":"bg","type":"background","paint":{"background-color":"#f2efe9"}}]}"##
        guard let url = area.pmtilesURL,
              let file = Bundle.main.url(forResource: "style", withExtension: "json"),
              let style = try? String(contentsOf: file, encoding: .utf8) else { return blank }
        return style.replacingOccurrences(of: "__PMTILES_URL__", with: url)
    }

    @MainActor
    final class Coordinator: NSObject, @preconcurrency MLNMapViewDelegate {
        var bbox: Bbox?
        var model: MapModel?
        var launch: DebugLaunchRunner?
        var initialCenter: CLLocationCoordinate2D?
        var initialZoom = 15.0
        private var style: MLNStyle?
        private var shown: [String: Shape] = [:]
        private var pending: [String: Shape] = [:]

        func show(results: Shape, highlight: Shape, tap: Shape) {
            pending = [InspectorMapView.results: results, InspectorMapView.highlight: highlight, InspectorMapView.tapSource: tap]
            apply()
        }

        @objc(mapView:didFinishLoadingStyle:)
        func mapView(_ mapView: MLNMapView, didFinishLoading style: MLNStyle) {
            self.style = style
            if let initialCenter {
                mapView.setCenter(initialCenter, zoomLevel: initialZoom, animated: false)
                self.initialCenter = nil
            }
            addOverlay(style)
            apply()
            launch?.run()
            launch = nil
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
            guard let map = gesture.view as? MLNMapView, gesture.state == .ended, let model else { return }
            let coordinate = map.convert(gesture.location(in: map), toCoordinateFrom: map)
            // About 20 points around the finger, in metres at this zoom (bounded for very low and high zooms).
            let radius = min(max(map.metersPerPoint(atLatitude: coordinate.latitude) * 20, 3), 60)
            Task { await model.runTap(LatLon(lat: coordinate.latitude, lon: coordinate.longitude), radiusMeters: radius) }
        }

        private func addOverlay(_ style: MLNStyle) {
            for id in [InspectorMapView.results, InspectorMapView.highlight, InspectorMapView.tapSource] {
                style.addSource(MLNShapeSource(identifier: id, shape: nil, options: nil))
            }
            let isLine = NSPredicate(format: "$geometryType == 'LineString'")
            let isPoint = NSPredicate(format: "$geometryType == 'Point'")
            func source(_ id: String) -> MLNSource { style.source(withIdentifier: id)! }

            let resultLines = MLNLineStyleLayer(identifier: "results-lines", source: source(InspectorMapView.results))
            resultLines.lineColor = NSExpression(forConstantValue: Palette.result)
            resultLines.lineWidth = NSExpression(forConstantValue: 3)
            resultLines.lineCap = NSExpression(forConstantValue: "round")
            resultLines.predicate = isLine
            style.addLayer(resultLines)

            let resultPoints = MLNCircleStyleLayer(identifier: "results-points", source: source(InspectorMapView.results))
            resultPoints.circleRadius = NSExpression(forConstantValue: 5)
            resultPoints.circleColor = NSExpression(forConstantValue: Palette.result)
            resultPoints.circleStrokeColor = NSExpression(forConstantValue: UIColor.white)
            resultPoints.circleStrokeWidth = NSExpression(forConstantValue: 1.5)
            resultPoints.predicate = isPoint
            style.addLayer(resultPoints)

            let highlightLines = MLNLineStyleLayer(identifier: "highlight-lines", source: source(InspectorMapView.highlight))
            highlightLines.lineColor = NSExpression(forConstantValue: Palette.highlight)
            highlightLines.lineWidth = NSExpression(forConstantValue: 6)
            highlightLines.lineOpacity = NSExpression(forConstantValue: 0.85)
            highlightLines.lineCap = NSExpression(forConstantValue: "round")
            highlightLines.lineJoin = NSExpression(forConstantValue: "round")
            highlightLines.predicate = isLine
            style.addLayer(highlightLines)

            let highlightPoints = MLNCircleStyleLayer(identifier: "highlight-points", source: source(InspectorMapView.highlight))
            highlightPoints.circleRadius = NSExpression(forConstantValue: 8)
            highlightPoints.circleColor = NSExpression(forConstantValue: Palette.highlight)
            highlightPoints.circleStrokeColor = NSExpression(forConstantValue: UIColor.white)
            highlightPoints.circleStrokeWidth = NSExpression(forConstantValue: 2)
            highlightPoints.predicate = isPoint
            style.addLayer(highlightPoints)

            let tap = MLNCircleStyleLayer(identifier: "tap", source: source(InspectorMapView.tapSource))
            tap.circleRadius = NSExpression(forConstantValue: 4)
            tap.circleColor = NSExpression(forConstantValue: UIColor(white: 0.13, alpha: 1))
            tap.circleStrokeColor = NSExpression(forConstantValue: UIColor.white)
            tap.circleStrokeWidth = NSExpression(forConstantValue: 2)
            style.addLayer(tap)
        }

        private func apply() {
            guard let style else { return }
            for (id, shape) in pending where shown[id] != shape {
                (style.source(withIdentifier: id) as? MLNShapeSource)?.shape = Self.collection(shape)
                shown[id] = shape
            }
        }

        static func collection(_ shape: Shape) -> MLNShapeCollectionFeature {
            var features: [MLNShape & MLNFeature] = []
            func point(_ p: LatLon) -> MLNPointFeature {
                let f = MLNPointFeature()
                f.coordinate = CLLocationCoordinate2D(latitude: p.lat, longitude: p.lon)
                return f
            }
            for line in shape.lines {
                // A run of one vertex (its neighbours are outside the area) is drawn as a point.
                if line.count == 1 {
                    features.append(point(line[0]))
                } else {
                    var coordinates = line.map { CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon) }
                    features.append(MLNPolylineFeature(coordinates: &coordinates, count: UInt(coordinates.count)))
                }
            }
            features += shape.points.map(point)
            return MLNShapeCollectionFeature(shapes: features)
        }
    }
}

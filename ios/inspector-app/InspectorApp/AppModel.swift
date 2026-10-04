import Cantino
import Foundation
import Observation

/// Entry point state and the area flow, trimmed from ios/cafe-app
/// AppModel.swift (the café app demonstrates the download lifecycle in
/// detail; Inspector shows one progress line). Port of android/inspector-app
/// MainActivity:
///
///   location (or debug override, or typed "lat,lon")
///     → offer, with a radius choice (``AreaRadius``: 1, 2.5 (default), 5 or 10 km)
///     → AreaManager.download(…, .extract(latest Protomaps build, z15)),
///       a full import (no import profile: Inspector exists to show everything)
///     → ready → map (``MapScreen``).
///
/// A published area goes straight to the map, fully offline. "Refresh" and
/// "Download another area" re-run the download (full replace; one area per app).
@MainActor
@Observable
final class AppModel {
    enum Screen: Equatable {
        case opening
        case message(title: String, body: String)
        case chooser(reason: String)
        case offer(center: LatLon, source: LocationSource)
        case progress
        case failure(message: String, retryable: Bool)
        case map
    }

    static let basemapMaxZoom = 15
    nonisolated static let slcDowntown = LatLon(lat: 40.7608, lon: -111.8910)
    nonisolated static let zurichCentre = LatLon(lat: 47.3744, lon: 8.5410)

    private(set) var screen: Screen = .opening
    /// The progress line's text and fraction (nil: indeterminate).
    private(set) var progressLine = ""
    private(set) var progressFraction: Double?
    /// Bumped whenever the map must be rebuilt (a new area was published).
    private(set) var mapGeneration = 0
    private(set) var hasPublishedArea = false
    /// An object the navigator asked to show on the map (consumed by ``MapScreen``).
    var showOnMap: OsmId?

    let debugLocation = DebugLocation.read()
    /// Consumed by the first map (``takeDebugLaunch()``).
    private var debugLaunch = DebugLaunch.read()
    /// The radius selected on the offer screen, in km (kept while the app runs).
    var radiusKm = DebugLaunch.read().radiusKm ?? AreaRadius.defaultKm
    @ObservationIgnored let device = DeviceLocation()
    @ObservationIgnored private let areas = AreaManager()
    @ObservationIgnored private var currentRun: UUID?
    @ObservationIgnored private var lookup: Task<Void, Never>?
    @ObservationIgnored private var started = false

    var autoDownload: Bool { debugLaunch.autoDownload }

    /// Where the first map starts (the debug tap and zoom), without consuming the launch options.
    var peekDebugTap: (tap: LatLon?, zoom: Double?) { (debugLaunch.tap, debugLaunch.zoom) }

    func takeDebugLaunch() -> DebugLaunch {
        let launch = debugLaunch
        debugLaunch = DebugLaunch()
        return launch
    }

    /// Routes on the first observed state, then follows the area's state. Call once.
    func start() async {
        guard !started else { return }
        started = true
        let stream: AsyncStream<AreaState>
        do {
            stream = try areas.state(areaId: InspectorStore.areaId)
        } catch {
            screen = .failure(message: "Cannot observe the area: \(error)", retryable: false)
            return
        }
        let published = await loadPublished()
        var states = stream.makeAsyncIterator()
        guard let first = await states.next() else { return }
        if first.isRunning {
            screen = .progress
            onAreaState(first)
        } else if published != nil {
            showMap()
        } else if case .failed(_, let message, let retryable, _) = first {
            screen = .failure(message: message, retryable: retryable)
        } else {
            startFirstRun()
        }
        while let state = await states.next() {
            onAreaState(state)
        }
    }

    // MARK: - first run: location → offer

    func startFirstRun() {
        if let debugLocation { return offerDownload(debugLocation, source: .debugOverride) }
        if device.isDenied { return showChooser("Location permission was not granted.") }
        screen = .message(title: "Inspector", body: "To download an offline area around you, the app needs your location once.")
        Task {
            if await device.requestPermission() {
                screen = .message(title: "Finding your location…", body: "Using the device's location service (up to 30 s).")
                if let here = await device.current() {
                    offerDownload(here, source: .device)
                } else {
                    showChooser("No location fix was available.")
                }
            } else {
                showChooser("Location permission was not granted.")
            }
        }
    }

    func showChooser(_ reason: String) {
        screen = .chooser(reason: reason)
    }

    func offerDownload(_ center: LatLon, source: LocationSource) {
        screen = .offer(center: center, source: source)
        if autoDownload {
            Task {
                try? await Task.sleep(for: .seconds(1)) // time to see (and screenshot) the offer
                if screen == .offer(center: center, source: source) { startDownload(center: center) }
            }
        }
    }

    /// "lat,lon" in degrees, or nil.
    nonisolated static func parseLatLon(_ input: String) -> LatLon? {
        let parts = input.split(separator: ",", omittingEmptySubsequences: false)
            .map { Double($0.trimmingCharacters(in: .whitespaces)) }
        guard parts.count == 2, let lat = parts[0], let lon = parts[1],
              (-85.0...85.0).contains(lat), (-180.0...180.0).contains(lon) else { return nil }
        return LatLon(lat: lat, lon: lon)
    }

    // MARK: - download

    /// Downloads the square of the selected ``radiusKm`` around `center`, or
    /// exactly `bbox` (refresh: the published area's bbox, full replace).
    func startDownload(center: LatLon, bbox: Bbox? = nil) {
        screen = .progress
        setProgress("Finding the newest basemap build", nil)
        currentRun = nil
        lookup?.cancel()
        lookup = Task {
            let planet: String
            do {
                planet = try await ProtomapsBuilds.latestUrl() // network lookup (HEAD requests); demo only
            } catch is CancellationError {
                return
            } catch {
                guard !Task.isCancelled else { return }
                return showFailure("Could not reach the basemap host: \(error)", retryable: true)
            }
            guard !Task.isCancelled else { return }
            do {
                let area = try bbox ?? AreaRadius.bbox(center, radiusKm: radiusKm)
                Log.app.info("area \(bbox == nil ? "radius \(self.radiusKm) km" : "refresh", privacy: .public), bbox \(String(describing: area), privacy: .public)")
                currentRun = try areas.download(
                    areaId: InspectorStore.areaId, bbox: area,
                    name: String(format: "inspector %.4f,%.4f", center.lat, center.lon),
                    basemap: try .extract(planetUrl: planet, maxZoom: Self.basemapMaxZoom))
                lookup = nil
            } catch {
                showFailure("Could not start the download: \(error)", retryable: false)
            }
        }
    }

    func cancelDownload() {
        if currentRun == nil, let lookup {
            lookup.cancel()
            self.lookup = nil
            Task { await backAfterCancel() }
            return
        }
        try? areas.cancel(areaId: InspectorStore.areaId)
    }

    private func onAreaState(_ state: AreaState) {
        guard screen == .progress else {
            if state.isRunning { // a download we did not start here (resumed after a relaunch)
                currentRun = state.runId
                screen = .progress
                update(state)
            }
            return
        }
        // The stream follows the area, not one run: ignore another run's states.
        if let run = state.runId, run != currentRun {
            guard currentRun == nil, lookup == nil, state.isRunning else { return }
            currentRun = run
        }
        guard state.runId != nil else { return } // idle
        update(state)
        switch state {
        case .ready(_, let area):
            let counts = area.metadata?.report.map { "\($0.counts)" } ?? "nil"
            Log.app.info("ready: counts \(counts, privacy: .public), snapshot \(String(describing: area.metadata?.snapshotTimestamp), privacy: .public)")
            showMap()
        case .failed(_, let message, let retryable, _):
            showFailure(message, retryable: retryable)
        case .cancelled:
            Task { await backAfterCancel() }
        default:
            break
        }
    }

    /// One progress line: the phase, and bytes or a fraction when known.
    private func update(_ state: AreaState) {
        func bytes(_ b: Int64, _ total: Int64?) -> String {
            if let total, total > 0 { return "\(formatBytes(b)) of \(formatBytes(total))" }
            return formatBytes(b)
        }
        func fraction(_ b: Int64, _ total: Int64?) -> Double? { total.flatMap { $0 > 0 ? Double(b) / Double($0) : nil } }
        switch state {
        case .queued(_, let previousRuns): setProgress("Waiting to retry (attempt \(previousRuns + 1))", nil)
        case .submitting: setProgress("Requesting the extract", nil)
        case .slicing(_, let f): setProgress("SliceOSM is cutting the extract" + (f.map { String(format: " · %.0f %%", $0 * 100) } ?? ""), f)
        case .downloading(_, let b, let total): setProgress("OSM data · \(bytes(b, total))", fraction(b, total))
        case .importing: setProgress("Importing (full import, building indexes)", nil)
        case .basemap(_, let phase, let b, let total): setProgress("Basemap \(phase.rawValue) · \(bytes(b, total))", fraction(b, total))
        case .ready: setProgress("Ready", 1)
        case .failed, .cancelled, .idle: break
        }
    }

    private func setProgress(_ line: String, _ fraction: Double?) {
        progressLine = line
        progressFraction = fraction.map { min(max($0, 0), 1) }
    }

    private func backAfterCancel() async {
        if await loadPublished() != nil { showMap() } else { showChooser("Download cancelled.") }
    }

    private func showFailure(_ message: String, retryable: Bool) {
        currentRun = nil
        lookup = nil
        Task {
            _ = await loadPublished()
            screen = .failure(message: message, retryable: retryable)
        }
    }

    // MARK: - map

    func showMap() {
        lookup = nil
        currentRun = nil
        hasPublishedArea = true
        mapGeneration += 1
        screen = .map
    }

    /// Refresh = full re-download of the same bbox.
    func refresh(_ area: AreaInfo) {
        guard let bbox = area.metadata?.bbox else { return }
        startDownload(center: Geo.center(bbox), bbox: bbox)
    }

    private func loadPublished() async -> AreaInfo? {
        let published = try? await areas.loadPublishedArea(areaId: InspectorStore.areaId)
        hasPublishedArea = published != nil
        return published
    }
}

extension AreaState {
    var isRunning: Bool {
        switch self {
        case .queued, .submitting, .slicing, .downloading, .importing, .basemap: true
        default: false
        }
    }
}

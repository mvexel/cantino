import Cantino
import Foundation
import Observation

/// Entry point state and the first-run flow (port of the Android café app's
/// MainActivity):
///
///   location permission → one CoreLocation fix
///     → offer "download ~10×10 km around you (map data + basemap)?"
///     → AreaManager.download(…, .extract(latest Protomaps build, z15)),
///       importing points of interest only (``CafeProfile``)
///       with honest progress (phase, bytes, fraction when known)
///     → ready → map (``MapScreen``).
///
/// Fallbacks: permission denied or no fix → type "lat,lon" or pick the Salt
/// Lake City downtown preset. Download failure → retry (the previous area, if
/// any, is kept by Cantino). "Refresh area" on the map re-runs the download
/// for the same bbox (full replace).
///
/// Every later launch with a published area goes straight to the map, which
/// works fully offline (airplane mode). The download state is observed for
/// the app's lifetime, so a relaunch during a download (which ``AreaManager``
/// resumes in-process) shows its progress.
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

    private(set) var screen: Screen = .opening
    /// Non-nil while the progress screen is showing (our download, or one found running).
    private(set) var progress: ProgressModel?
    /// Bumped whenever the map must be rebuilt (a new area was published).
    private(set) var mapGeneration = 0
    /// A published area exists (decides the failure screen's "back to the map").
    private(set) var hasPublishedArea = false

    let debugLocation = DebugLocation.read()
    let debugLaunch = DebugLaunch.read()
    @ObservationIgnored let device = DeviceLocation()
    /// Downloads started here import points of interest only (``CafeProfile``).
    @ObservationIgnored private let areas = AreaManager(config: CafeProfile.areaConfig)
    /// The run the progress screen follows; nil before ``AreaManager/download`` returned.
    @ObservationIgnored private var currentRun: UUID?
    @ObservationIgnored private var lookup: Task<Void, Never>?
    @ObservationIgnored private var started = false
    /// Debug launch arguments act once per process (a refresh must not loop).
    @ObservationIgnored private var debugRefreshDone = false

    /// Routes on the first observed state, then follows the area's state for
    /// the app's lifetime. Call once.
    func start() async {
        guard !started else { return }
        started = true
        let stream: AsyncStream<AreaState>
        do {
            stream = try areas.state(areaId: CafeStore.areaId)
        } catch {
            screen = .failure(message: "Cannot observe the area: \(error)", retryable: false)
            return
        }
        let published = await loadPublished()
        var states = stream.makeAsyncIterator()
        guard let first = await states.next() else { return }
        if first.isRunning {
            showProgress(requested: Settings.requested)
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
        screen = .message(title: "Offline cafés",
                          body: "To download an offline area around you, the app needs your location once.")
        Task {
            if await device.requestPermission() {
                screen = .message(title: "Finding your location…",
                                  body: "Using the device's location service (up to 30 s).")
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
        progress = nil
        screen = .chooser(reason: reason)
    }

    /// The offer, with the one-line privacy note (``OfferView``).
    func offerDownload(_ center: LatLon, source: LocationSource) {
        screen = .offer(center: center, source: source)
        if debugLaunch.autoDownload {
            Task {
                try? await Task.sleep(for: .seconds(1)) // long enough to see (and screenshot) the offer
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

    /// Downloads the default-size area around `center`, or exactly `bbox`
    /// (refresh: the published area's bbox, full replace).
    func startDownload(center: LatLon, bbox: Bbox? = nil) {
        Settings.requested = center
        let screen = showProgress(requested: center)
        screen.phase("Finding the newest basemap build")
        if let delay = debugLaunch.cancelAfter {
            Task {
                try? await Task.sleep(for: .seconds(delay))
                if progress === screen { cancelDownload() }
            }
        }
        currentRun = nil
        lookup?.cancel()
        lookup = Task {
            let planet: String
            do {
                // Network lookup (HEAD requests); demo only, see ProtomapsBuilds.
                planet = try await ProtomapsBuilds.latestUrl()
            } catch is CancellationError {
                return
            } catch {
                guard !Task.isCancelled else { return }
                return showFailure("Could not reach the basemap host: \(error)", retryable: true)
            }
            guard !Task.isCancelled else { return }
            do {
                let area = try bbox ?? Bbox.around(lat: center.lat, lon: center.lon, widthKm: Geo.areaSizeKm)
                currentRun = try areas.download(
                    areaId: CafeStore.areaId, bbox: area,
                    name: String(format: "cafes %.4f,%.4f", center.lat, center.lon),
                    basemap: try .extract(planetUrl: planet, maxZoom: Self.basemapMaxZoom))
            } catch {
                showFailure("Could not start the download: \(error)", retryable: false)
            }
        }
    }

    /// Cancel on the progress screen: the build lookup if it is still
    /// running, else the download (the published area stays).
    func cancelDownload() {
        if currentRun == nil, let lookup {
            lookup.cancel()
            self.lookup = nil
            Task { await backAfterCancel() }
            return
        }
        try? areas.cancel(areaId: CafeStore.areaId)
    }

    private func onAreaState(_ state: AreaState) {
        guard let screen = progress else {
            // A download we did not start from this screen (e.g. resumed
            // after a relaunch): show it.
            if state.isRunning {
                currentRun = state.runId
                showProgress(requested: Settings.requested).update(state)
            }
            return
        }
        // The stream follows the area, not one run: ignore what belongs to
        // another run (e.g. the previous run's final state right after a
        // new download starts), but adopt a running one we did not know.
        // (Our own run's states cannot arrive before `currentRun` is set:
        // download() returns its ID in the same main-actor turn.)
        if let run = state.runId, run != currentRun {
            guard currentRun == nil, lookup == nil, state.isRunning else { return }
            currentRun = run
        }
        guard state.runId != nil else { return } // idle
        screen.update(state)
        switch state {
        case .ready(_, let area):
            screen.finish(area)
            progress = nil
            showMap()
        case .failed(_, let message, let retryable, _):
            showFailure(message, retryable: retryable)
        case .cancelled:
            progress = nil
            Task { await backAfterCancel() }
        default:
            break
        }
    }

    private func backAfterCancel() async {
        progress = nil
        if await loadPublished() != nil { showMap() } else { showChooser("Download cancelled.") }
    }

    @discardableResult
    private func showProgress(requested: LatLon?) -> ProgressModel {
        let model = ProgressModel(requested: requested)
        progress = model
        screen = .progress
        return model
    }

    private func showFailure(_ message: String, retryable: Bool) {
        progress = nil
        currentRun = nil
        lookup = nil
        Task {
            _ = await loadPublished()
            screen = .failure(message: message, retryable: retryable)
        }
    }

    func retry() {
        guard let requested = Settings.requested else { return showChooser("") }
        startDownload(center: requested)
    }

    // MARK: - map

    func showMap() {
        progress = nil
        lookup = nil
        currentRun = nil
        hasPublishedArea = true
        mapGeneration += 1
        screen = .map
    }

    /// Refresh = full re-download of the same area (same bbox).
    func refresh(_ area: AreaInfo, fromDebugLaunch: Bool = false) {
        if fromDebugLaunch {
            guard !debugRefreshDone else { return }
            debugRefreshDone = true
        }
        guard let bbox = area.metadata?.bbox else {
            if let requested = Settings.requested { startDownload(center: requested) }
            return
        }
        startDownload(center: Geo.center(bbox), bbox: bbox)
    }

    private func loadPublished() async -> AreaInfo? {
        let published = try? await areas.loadPublishedArea(areaId: CafeStore.areaId)
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

/// The centre of the last requested download, for Retry and for a relaunch mid-download.
enum Settings {
    static var requested: LatLon? {
        get {
            let defaults = UserDefaults.standard
            guard defaults.object(forKey: "requested_lat") != nil else { return nil }
            return LatLon(lat: defaults.double(forKey: "requested_lat"), lon: defaults.double(forKey: "requested_lon"))
        }
        set {
            UserDefaults.standard.set(newValue?.lat, forKey: "requested_lat")
            UserDefaults.standard.set(newValue?.lon, forKey: "requested_lon")
        }
    }
}

/// The download progress screen's model. Shows the current phase with what
/// is actually known (a fraction only when the server reports one, bytes
/// with a total only when there is a total) and how long each finished phase
/// took. Phase timings are also logged (`os_log`, category CafeDownload).
@MainActor
@Observable
final class ProgressModel {
    let requested: LatLon?
    private(set) var phaseName = ""
    private(set) var detail = ""
    /// 0...1, or nil for an indeterminate bar.
    private(set) var fraction: Double?
    private(set) var finished: [String] = []
    let startedAt = Date()
    private(set) var currentStart = Date()

    init(requested: LatLon?) { self.requested = requested }

    /// Starts a new named phase, closing the previous one.
    func phase(_ name: String) {
        guard name != phaseName else { return }
        let now = Date()
        if !phaseName.isEmpty {
            let took = now.timeIntervalSince(currentStart)
            finished.append(String(format: "✓ %@ — %.1f s", phaseName, took))
            Log.download.info("phase \"\(self.phaseName, privacy: .public)\" took \(Int(took * 1000)) ms")
        }
        phaseName = name
        currentStart = now
    }

    func update(_ state: AreaState) {
        switch state {
        case .queued(_, let previousRuns):
            phase(previousRuns == 0 ? "Waiting to start" : "Waiting to retry (attempt \(previousRuns + 1))")
            indeterminate("Retrying in the app's process after a transient failure.")
        case .submitting:
            phase("Requesting the extract (SliceOSM)")
            indeterminate("")
        case .slicing(_, let fraction):
            phase("SliceOSM is cutting the extract")
            if let fraction {
                determinate(fraction, String(format: "%.0f %% sliced", fraction * 100))
            } else {
                indeterminate("Waiting for the server to report progress.")
            }
        case .downloading(_, let bytes, let total):
            phase("Downloading OSM data")
            self.bytes(bytes, total)
        case .importing:
            phase("Importing into the offline database")
            indeterminate("Building indexes on the device.")
        case .basemap(_, let basemapPhase, let bytes, let total):
            switch basemapPhase {
            case .download: phase("Downloading the basemap")
            case .directories: phase("Basemap: reading tile directories")
            case .tiles: phase("Basemap: downloading tiles")
            }
            self.bytes(bytes, total)
        case .ready, .failed, .cancelled, .idle:
            break
        }
    }

    /// Ready: close the last phase and log the totals (sizes for the acceptance record).
    func finish(_ area: AreaInfo) {
        phase("Ready")
        let total = Int(Date().timeIntervalSince(startedAt) * 1000)
        let size = { (url: URL?) in
            url.flatMap { try? FileManager.default.attributesOfItem(atPath: $0.path)[.size] as? NSNumber }?.int64Value ?? -1
        }
        let counts = area.metadata?.report.map { "\($0.counts)" } ?? "nil"
        let profile = area.metadata?.report?.profile.map { "\($0)" } ?? "nil"
        Log.download.info(
            "ready in \(total) ms; data db \(size(area.dataURL)) B; pmtiles \(size(area.basemapURL)) B; counts \(counts, privacy: .public); profile \(profile, privacy: .public)")
    }

    private func bytes(_ bytes: Int64, _ total: Int64?) {
        if let total, total > 0 {
            determinate(Double(bytes) / Double(total), "\(formatBytes(bytes)) of \(formatBytes(total))")
        } else {
            indeterminate("\(formatBytes(bytes)) (total not known yet)")
        }
    }

    private func determinate(_ value: Double, _ message: String) {
        fraction = min(max(value, 0), 1)
        detail = message
    }

    private func indeterminate(_ message: String) {
        fraction = nil
        detail = message
    }
}

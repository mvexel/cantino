import Cantino
import CoreLocation
import Foundation

/*
 * Where the user is (one CoreLocation fix) and the debug launch options for
 * automated runs. DebugLocation and DeviceLocation are copied from
 * ios/cafe-app Location.swift; DebugLaunch mirrors android/inspector-app
 * Location.kt's DebugLaunch.
 */

/// Where the area centre came from (shown on the offer screen).
enum LocationSource: String, Sendable {
    case debugOverride = "debug location"
    case device = "your location"
    case manual = "chosen location"

    var label: String { rawValue }
}

private func argument(_ arguments: [String], _ flag: String) -> String? {
    guard let index = arguments.firstIndex(of: flag), index + 1 < arguments.count else { return nil }
    return arguments[index + 1]
}

private func flag(_ arguments: [String], _ name: String) -> Bool {
    ["yes", "true", "1"].contains(argument(arguments, name)?.lowercased() ?? "")
}

/// Debug location override, Debug builds only:
///
///     xcrun simctl launch <device> lol.osm.cantino.inspector -lat 40.7608 -lon -111.8910
///
/// Sticky (saved in `UserDefaults`); clear it with `-clear_debug_location YES`.
/// It replaces the device location as the first-run area centre.
enum DebugLocation {
    private static let latKey = "debug_location_lat"
    private static let lonKey = "debug_location_lon"

    static func read(arguments: [String] = ProcessInfo.processInfo.arguments,
                     defaults: UserDefaults = .standard) -> LatLon? {
        #if DEBUG
        if flag(arguments, "-clear_debug_location") {
            defaults.removeObject(forKey: latKey)
            defaults.removeObject(forKey: lonKey)
        }
        if let lat = argument(arguments, "-lat").flatMap(Double.init), let lon = argument(arguments, "-lon").flatMap(Double.init),
           (-85.0...85.0).contains(lat), (-180.0...180.0).contains(lon) {
            defaults.set(lat, forKey: latKey)
            defaults.set(lon, forKey: lonKey)
        }
        guard defaults.object(forKey: latKey) != nil, defaults.object(forKey: lonKey) != nil else { return nil }
        return LatLon(lat: defaults.double(forKey: latKey), lon: defaults.double(forKey: lonKey))
        #else
        return nil
        #endif
    }
}

/// Launch arguments that stand in for taps in automated runs (Debug builds
/// only, not sticky). The same options exist on Android as intent extras.
///
///     -radius 5                          preselect this area radius on the offer, km
///                                        (one of AreaRadius.choicesKm; same as the café app)
///     -auto_download YES                 accept the download offer
///     -query 'amenity=* !opening_hours' [-view_only YES]   run a query
///     -tap 40.76080,-111.89100 [-tap_radius 15]   tap there (candidates sheet; hit radius in m)
///     -select 0                          then select the candidate at that index
///     -object way/123                    open the object navigator
///     -about YES                         open "About this area"
///     -counts YES                        count the acceptance queries (os_log category InspectorCounts)
///     -zoom 17                           camera zoom (centre: the tap, else the area centre)
struct DebugLaunch: Sendable {
    /// The download area's radius in km (``AreaRadius``); not the tap's hit radius.
    var radiusKm: Double?
    var autoDownload = false
    var query: String?
    var viewOnly = false
    var tap: LatLon?
    /// The debug tap's hit radius, in metres.
    var tapRadius: Double?
    var select: Int?
    var object: OsmId?
    var about = false
    var counts = false
    var zoom: Double?

    static func read(arguments: [String] = ProcessInfo.processInfo.arguments) -> DebugLaunch {
        var launch = DebugLaunch()
        #if DEBUG
        launch.radiusKm = AreaRadius.choice(argument(arguments, "-radius").flatMap(Double.init))
        launch.autoDownload = flag(arguments, "-auto_download")
        launch.query = argument(arguments, "-query")
        launch.viewOnly = flag(arguments, "-view_only")
        launch.tap = argument(arguments, "-tap").flatMap(AppModel.parseLatLon)
        launch.tapRadius = argument(arguments, "-tap_radius").flatMap(Double.init).flatMap { $0 > 0 ? $0 : nil }
        launch.select = argument(arguments, "-select").flatMap(Int.init).flatMap { $0 >= 0 ? $0 : nil }
        launch.object = argument(arguments, "-object").flatMap(Labels.parsePath)
        launch.about = flag(arguments, "-about")
        launch.counts = flag(arguments, "-counts")
        launch.zoom = argument(arguments, "-zoom").flatMap(Double.init)
        #endif
        return launch
    }
}

/// One fix from CoreLocation, with a timeout. Main-actor bound because
/// `CLLocationManager` delivers to the thread that created it (here: main).
@MainActor
final class DeviceLocation: NSObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private var authorization: CheckedContinuation<Void, Never>?
    private var fix: CheckedContinuation<LatLon?, Never>?

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
    }

    var isAuthorized: Bool { [.authorizedWhenInUse, .authorizedAlways].contains(manager.authorizationStatus) }
    var isDenied: Bool { [.denied, .restricted].contains(manager.authorizationStatus) }

    /// Asks for when-in-use permission if it was never asked; returns
    /// whether the app may now read the location.
    func requestPermission() async -> Bool {
        if manager.authorizationStatus == .notDetermined {
            await withCheckedContinuation { continuation in
                authorization = continuation
                manager.requestWhenInUseAuthorization()
            }
        }
        return isAuthorized
    }

    /// A current fix: a fresh cached one, otherwise one new fix, waiting at
    /// most `timeout` seconds. Nil when location is off, denied, or no fix arrives.
    func current(timeout: TimeInterval = 30) async -> LatLon? {
        guard isAuthorized else { return nil }
        if let location = manager.location, -location.timestamp.timeIntervalSinceNow <= 120 {
            return LatLon(lat: location.coordinate.latitude, lon: location.coordinate.longitude)
        }
        let timer = Task { [weak self] in
            try? await Task.sleep(for: .seconds(timeout))
            self?.deliver(nil)
        }
        defer { timer.cancel() }
        return await withCheckedContinuation { continuation in
            fix = continuation
            manager.requestLocation()
        }
    }

    private func deliver(_ point: LatLon?) {
        guard let continuation = fix else { return }
        fix = nil
        manager.stopUpdatingLocation()
        continuation.resume(returning: point)
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        MainActor.assumeIsolated {
            guard status != .notDetermined, let continuation = authorization else { return }
            authorization = nil
            continuation.resume()
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let last = locations.last else { return }
        let point = LatLon(lat: last.coordinate.latitude, lon: last.coordinate.longitude)
        MainActor.assumeIsolated { deliver(point) }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        MainActor.assumeIsolated { deliver(nil) }
    }
}

import CoreLocation
import Foundation

/*
 * Where the user is: one CoreLocation fix, and the DEBUG location override
 * used for every automated run, so tests never send the tester's real
 * position to SliceOSM or the PMTiles host (port of the Android café app's
 * Location.kt).
 */

/// Where a reference location came from (shown in the UI next to distances).
enum LocationSource: String, Sendable {
    case debugOverride = "debug location"
    case device = "your location"
    case manual = "chosen location"
    case areaCentre = "area centre"

    var label: String { rawValue }
}

struct ReferenceLocation: Hashable, Sendable {
    let point: LatLon
    let source: LocationSource
}

/// Debug override, Debug builds only:
///
///     xcrun simctl launch booted lol.osm.cantino.cafe -lat 40.7608 -lon -111.8910
///
/// The override is sticky (saved in `UserDefaults`) so that a relaunch
/// without arguments still never asks the device for its real location;
/// clear it with `-clear_debug_location YES`. When set, it replaces the
/// device location everywhere: the first-run area centre and the "nearby"
/// origin. Release builds ignore all of it.
enum DebugLocation {
    private static let latKey = "debug_location_lat"
    private static let lonKey = "debug_location_lon"

    static func read(arguments: [String] = ProcessInfo.processInfo.arguments,
                     defaults: UserDefaults = .standard) -> LatLon? {
        #if DEBUG
        func value(_ flag: String) -> String? {
            guard let index = arguments.firstIndex(of: flag), index + 1 < arguments.count else { return nil }
            return arguments[index + 1]
        }
        if let clear = value("-clear_debug_location"), ["yes", "true", "1"].contains(clear.lowercased()) {
            defaults.removeObject(forKey: latKey)
            defaults.removeObject(forKey: lonKey)
        }
        if let lat = value("-lat").flatMap(Double.init), let lon = value("-lon").flatMap(Double.init),
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

    var isAuthorized: Bool {
        [.authorizedWhenInUse, .authorizedAlways].contains(manager.authorizationStatus)
    }

    var isDenied: Bool {
        [.denied, .restricted].contains(manager.authorizationStatus)
    }

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

    /// A recent cached fix (no radio use), or nil. Works in airplane mode.
    func lastKnown(maxAge: TimeInterval = 30 * 60) -> LatLon? {
        guard isAuthorized, let location = manager.location,
              -location.timestamp.timeIntervalSinceNow <= maxAge else { return nil }
        return LatLon(lat: location.coordinate.latitude, lon: location.coordinate.longitude)
    }

    /// A current fix: a fresh cached one if there is one, otherwise one new
    /// fix, waiting at most `timeout` seconds. Nil when location is off,
    /// denied, or no fix arrives in time.
    func current(timeout: TimeInterval = 30) async -> LatLon? {
        guard isAuthorized else { return nil }
        if let recent = lastKnown(maxAge: 2 * 60) { return recent }
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

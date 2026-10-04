import Cantino
import Foundation

/// The size of the download area, copied from ios/cafe-app AreaRadius.swift
/// (only the default differs); port of android/inspector-app AreaRadius.kt.
/// The area is a square centred on the user's location (or the place they
/// chose); the user picks its radius on the offer screen.
///
/// "Radius" means half the side of the square: radius 2.5 km is a 5 × 5 km box
/// reaching 2.5 km north, south, east and west of the centre (its corners are
/// about 3.5 km away). The same in both example apps, on Android and iOS.
///
/// The values are defaults, not limits: change ``choicesKm`` and
/// ``defaultKm`` freely. Inspector does a full import, so the file grows with
/// the area (about 24 MB for 5 × 5 km of downtown Salt Lake City, 45 MB in Zürich).
enum AreaRadius {
    /// The radius choices on the offer screen, in km.
    static let choicesKm: [Double] = [1, 2.5, 5, 10]

    /// Preselected radius, in km: a 5 × 5 km city centre, so a full import stays small.
    static let defaultKm = 2.5

    /// The square of `radiusKm` around `center`, via Cantino's `Bbox.around` (which takes the side).
    static func bbox(_ center: LatLon, radiusKm: Double) throws -> Bbox {
        try Bbox.around(lat: center.lat, lon: center.lon, widthKm: 2 * radiusKm)
    }

    /// `km` if it is one of ``choicesKm`` (the `-radius` launch argument), else nil.
    static func choice(_ km: Double?) -> Double? {
        guard let km else { return nil }
        return choicesKm.first { abs($0 - km) < 1e-9 }
    }

    /// "2.5 km".
    static func label(_ radiusKm: Double) -> String { "\(format(radiusKm)) km" }

    /// "5 × 5 km": the box a radius makes.
    static func sideLabel(_ radiusKm: Double) -> String {
        let side = format(2 * radiusKm)
        return "\(side) × \(side) km"
    }

    private static func format(_ value: Double) -> String {
        value.truncatingRemainder(dividingBy: 1) == 0 ? "\(Int(value))" : String(format: "%.1f", value)
    }
}

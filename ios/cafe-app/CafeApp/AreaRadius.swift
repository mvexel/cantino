import Cantino
import Foundation

/// The size of the download area. The area is a square centred on the user's
/// location (or the place they chose); the user picks its radius on the offer
/// screen. Port of android/cafe-app AreaRadius.kt.
///
/// "Radius" means half the side of the square: radius 5 km is a 10 × 10 km box
/// reaching 5 km north, south, east and west of the centre (its corners are
/// about 7 km away). The same in both example apps, on Android and iOS.
///
/// The values are defaults, not limits: change ``choicesKm`` and
/// ``defaultKm`` freely. A `Bbox` has no size cap; see
/// docs/guide/downloading.md#area-size.
enum AreaRadius {
    /// The radius choices on the offer screen, in km.
    static let choicesKm: [Double] = [1, 2.5, 5, 10]

    /// Preselected radius, in km: a 10 × 10 km box, a city centre's cafés.
    static let defaultKm = 5.0

    /// The square of `radiusKm` around `center`, via Cantino's `Bbox.around` (which takes the side).
    static func bbox(_ center: LatLon, radiusKm: Double) throws -> Bbox {
        try Bbox.around(lat: center.lat, lon: center.lon, widthKm: 2 * radiusKm)
    }

    /// The radius of a box made by ``bbox(_:radiusKm:)``, from its north-south
    /// extent (`Bbox.around` uses 111.32 km per degree of latitude). Used to
    /// label a refresh, which re-downloads the published bbox as it is.
    static func of(_ bbox: Bbox) -> Double { (bbox.north - bbox.south) * kmPerDegreeLat / 2 }

    /// `km` if it is one of ``choicesKm`` (the `-radius` launch argument), else nil.
    static func choice(_ km: Double?) -> Double? {
        guard let km else { return nil }
        return choicesKm.first { abs($0 - km) < 1e-9 }
    }

    /// "2.5 km".
    static func label(_ radiusKm: Double) -> String { "\(format(radiusKm)) km" }

    /// "10 × 10 km": the box a radius makes.
    static func sideLabel(_ radiusKm: Double) -> String {
        let side = format(2 * radiusKm)
        return "\(side) × \(side) km"
    }

    private static func format(_ value: Double) -> String {
        value.truncatingRemainder(dividingBy: 1) == 0 ? "\(Int(value))" : String(format: "%.1f", value)
    }

    private static let kmPerDegreeLat = 111.32
}

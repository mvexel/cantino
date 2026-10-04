import Cantino
import Testing
@testable import InspectorApp

/// Same cases as android/inspector-app AreaRadiusTest and the café app's AreaRadiusTests.
struct AreaRadiusTests {
    private let slc = LatLon(lat: 40.7608, lon: -111.8910)

    @Test func radiusIsHalfTheSideOfTheSquare() throws {
        let box = try AreaRadius.bbox(slc, radiusKm: 2.5)
        // 5 km north-south at 111.32 km per degree of latitude.
        #expect(abs((box.north - box.south) - 5 / 111.32) < 1e-9)
        #expect(abs((box.north + box.south) / 2 - slc.lat) < 1e-9)
        #expect(abs((box.west + box.east) / 2 - slc.lon) < 1e-9)
        // The edges are 2.5 km from the centre (flat-earth approximation, well within 1 %).
        #expect(abs(Geo.distanceMeters(slc, LatLon(lat: box.north, lon: slc.lon)) - 2500) < 25)
        #expect(abs(Geo.distanceMeters(slc, LatLon(lat: slc.lat, lon: box.east)) - 2500) < 25)
    }

    @Test func defaultIsAChoice() {
        #expect(AreaRadius.choicesKm == [1, 2.5, 5, 10])
        #expect(AreaRadius.defaultKm == 2.5)
        #expect(AreaRadius.choicesKm.contains(AreaRadius.defaultKm))
    }

    @Test func launchOptionMustBeAChoice() {
        #expect(AreaRadius.choice(2.5) == 2.5)
        #expect(AreaRadius.choice(10) == 10)
        #expect(AreaRadius.choice(3) == nil)
        #expect(AreaRadius.choice(0) == nil)
        #expect(AreaRadius.choice(.nan) == nil)
        #expect(AreaRadius.choice(nil) == nil)
        // -radius is the area (km); the debug tap's hit radius is -tap_radius (m).
        let launch = DebugLaunch.read(arguments: ["app", "-radius", "5", "-tap_radius", "15"])
        #expect(launch.radiusKm == 5)
        #expect(launch.tapRadius == 15)
    }

    @Test func labels() {
        #expect(AreaRadius.choicesKm.map(AreaRadius.label) == ["1 km", "2.5 km", "5 km", "10 km"])
        #expect(AreaRadius.sideLabel(2.5) == "5 × 5 km")
        #expect(AreaRadius.sideLabel(10) == "20 × 20 km")
    }

    @Test func chooserParsesLatLon() {
        #expect(AppModel.parseLatLon("40.7608,-111.8910") == slc)
        #expect(AppModel.parseLatLon(" 40.7608 , -111.8910 ") == slc)
        #expect(AppModel.parseLatLon("40.7608") == nil)
        #expect(AppModel.parseLatLon("91,0") == nil)
        #expect(AppModel.parseLatLon("a,b") == nil)
    }
}

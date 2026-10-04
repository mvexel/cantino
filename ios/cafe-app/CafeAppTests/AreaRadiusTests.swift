import Cantino
import Testing
@testable import CafeApp

/// Same cases as android/cafe-app AreaRadiusTest and the Inspector's AreaRadiusTests.
struct AreaRadiusTests {
    private let slc = LatLon(lat: 40.7608, lon: -111.8910)

    @Test func radiusIsHalfTheSideOfTheSquare() throws {
        let box = try AreaRadius.bbox(slc, radiusKm: 5)
        // 10 km north-south at 111.32 km per degree of latitude.
        #expect(abs((box.north - box.south) - 10 / 111.32) < 1e-9)
        #expect(abs((box.north + box.south) / 2 - slc.lat) < 1e-9)
        #expect(abs((box.west + box.east) / 2 - slc.lon) < 1e-9)
        // The edges are 5 km from the centre (flat-earth approximation, well within 1 %).
        #expect(abs(Geo.distanceMeters(slc, LatLon(lat: box.north, lon: slc.lon)) - 5000) < 50)
        #expect(abs(Geo.distanceMeters(slc, LatLon(lat: slc.lat, lon: box.east)) - 5000) < 50)
    }

    @Test func radiusRoundTripsThroughTheBbox() throws {
        for km in AreaRadius.choicesKm {
            #expect(abs(AreaRadius.of(try AreaRadius.bbox(slc, radiusKm: km)) - km) < 1e-9)
        }
    }

    @Test func defaultIsAChoice() {
        #expect(AreaRadius.choicesKm == [1, 2.5, 5, 10])
        #expect(AreaRadius.defaultKm == 5)
        #expect(AreaRadius.choicesKm.contains(AreaRadius.defaultKm))
    }

    @Test func launchOptionMustBeAChoice() {
        #expect(AreaRadius.choice(2.5) == 2.5)
        #expect(AreaRadius.choice(10) == 10)
        #expect(AreaRadius.choice(3) == nil)
        #expect(AreaRadius.choice(0) == nil)
        #expect(AreaRadius.choice(.nan) == nil)
        #expect(AreaRadius.choice(nil) == nil)
        #expect(DebugLaunch.read(arguments: ["app", "-radius", "2.5"]).radiusKm == 2.5)
        #expect(DebugLaunch.read(arguments: ["app", "-radius", "3"]).radiusKm == nil)
    }

    @Test func labels() {
        #expect(AreaRadius.choicesKm.map(AreaRadius.label) == ["1 km", "2.5 km", "5 km", "10 km"])
        #expect(AreaRadius.sideLabel(5) == "10 × 10 km")
        #expect(AreaRadius.sideLabel(2.5) == "5 × 5 km")
        #expect(AreaRadius.sideLabel(1) == "2 × 2 km")
    }
}

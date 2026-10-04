import Foundation
import Testing
@testable import Cantino

/// Port of the Kotlin BboxAroundTest (android/cantino/src/test/.../BboxAroundTest.kt),
/// same inputs and tolerances.
@Suite struct BboxAroundTests {
    private func close(_ a: Double, _ b: Double, _ tolerance: Double = 1e-9) -> Bool { abs(a - b) <= tolerance }

    @Test func squareAtEquatorIsSymmetric() throws {
        let box = try Bbox.around(lat: 0, lon: 10, widthKm: 11.132)
        #expect(close(box.west, 10 - 0.05))
        #expect(close(box.east, 10 + 0.05))
        #expect(close(box.south, -0.05))
        #expect(close(box.north, 0.05))
    }

    @Test func longitudeSpanWidensWithLatitude() throws {
        let box = try Bbox.around(lat: 60, lon: 5, widthKm: 10)
        // cos(60) = 0.5: a degree of longitude is half as long, so twice the degrees.
        #expect(close(box.east - box.west, 2 * (box.north - box.south)))
    }

    @Test func widthAndHeightAreIndependent() throws {
        let box = try Bbox.around(lat: 0, lon: 0, widthKm: 22.264, heightKm: 11.132)
        #expect(close(box.east - box.west, 0.2))
        #expect(close(box.north - box.south, 0.1))
    }

    @Test func latitudeIsClamped() throws {
        let box = try Bbox.around(lat: 84.9, lon: 0, widthKm: 100)
        #expect(box.north == 85.0)
        let south = try Bbox.around(lat: -90, lon: 0, widthKm: 10)
        #expect(south.south == -85.0)
        #expect(south.west.isFinite && south.east.isFinite)
    }

    @Test func antimeridianIsClampedNotWrapped() throws {
        let box = try Bbox.around(lat: 0, lon: 179.99, widthKm: 100)
        #expect(box.east == 180.0)
        #expect(box.west < box.east)
        let west = try Bbox.around(lat: 0, lon: -179.99, widthKm: 100)
        #expect(west.west == -180.0)
        #expect(west.west < west.east)
    }

    @Test func invalidArgumentsAreRejected() {
        // Kotlin: IllegalArgumentException. Swift: CantinoError.invalidArgument.
        #expect(category(caught { try Bbox.around(lat: 91, lon: 0, widthKm: 1) }) == "INVALID_ARGUMENT")
        #expect(category(caught { try Bbox.around(lat: 0, lon: 181, widthKm: 1) }) == "INVALID_ARGUMENT")
        #expect(category(caught { try Bbox.around(lat: .nan, lon: 0, widthKm: 1) }) == "INVALID_ARGUMENT")
        #expect(category(caught { try Bbox.around(lat: 0, lon: 0, widthKm: 0) }) == "INVALID_ARGUMENT")
        #expect(category(caught { try Bbox.around(lat: 0, lon: 0, widthKm: 1, heightKm: -1) }) == "INVALID_ARGUMENT")
    }
}

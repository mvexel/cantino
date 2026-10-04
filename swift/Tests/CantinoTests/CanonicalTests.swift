import Foundation
import Testing
@testable import Cantino

/// The canonical form (Canonical.swift) must reproduce the core's wire JSON
/// exactly, or a parity runner would report adapter differences that are
/// not there. Expected strings are the core's JSON for the fixture, with
/// keys sorted (src/model.rs serde layout).
@Suite struct CanonicalTests {
    @Test func objectsRenderInTheCoreWireShape() throws {
        let store = try OsmStore.open(Fixtures.importFixture().area)
        defer { try? store.close() }
        let cafe = try #require(try store.get(node(2)))
        let timestamp = try #require(cafe.metadata?.timestampSeconds)
        #expect(cafe.canonical.canonicalString ==
            #"{"coordinate":{"lat_e7":400010000,"lon_e7":-1110010000},"id":2,"location_version":1,"# +
            #""metadata":{"changeset":124,"timestamp":\#(timestamp),"uid":7,"user":"Mapper","version":1},"# +
            #""tags":{"amenity":"cafe","name":"Café Test"},"type":"node"}"#)
        #expect(try store.get(way(1)).canonical.canonicalString.contains(#""nodes":[1,2,1]"#))
        #expect(try store.get(relation(1)).canonical.canonicalString.contains(
            #""members":[{"id":{"id":1,"type":"way"},"role":"outer"},{"id":{"id":99,"type":"node"},"role":"label"}]"#))
        #expect(try store.get([node(99), node(3)]).canonical.canonicalString.hasPrefix("[null,{"))
        #expect(canonicalWayCoordinates(try store.wayCoordinates(1)).canonicalString ==
            "[400000000,-1110000000,400010000,-1110010000,400000000,-1110000000]")
        #expect(canonicalWayCoordinates(nil) == .null)
    }

    @Test func requestJsonIsDeterministic() throws {
        let query = Query(tags: [.equals("amenity", "café"), .notExists("a\"b")],
                          bbox: Bbox(west: -111.5, south: 40, east: -111.25, north: 40.000_01))
        #expect(try query.json() ==
            #"{"after":null,"bbox":{"east":-111.25,"north":40.00001,"south":40.0,"west":-111.5},"limit":100,"# +
            #""max_candidates":100000,"tags":[{"Equals":["amenity","café"]},{"NotExists":"a\"b"}]}"#)
    }

    @Test func parsedJsonRoundTrips() throws {
        let text = #"{"b":[1,2.5,null,true,"x"],"a":{"z":-3}}"#
        #expect(try JSONValue.parse(text).canonicalString == #"{"a":{"z":-3},"b":[1,2.5,null,true,"x"]}"#)
    }
}

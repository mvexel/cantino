import Foundation
import Testing
@testable import Cantino

/// The canonical form (Canonical.swift, JSONValue.swift) must follow
/// tests/parity/README.md exactly, or the parity runner would report
/// adapter differences that are not there (or hide real ones). Expected
/// strings are the core's JSON for the fixture, canonicalised as the Rust
/// runner does.
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

    @Test func outcomesFollowTheCorpusEnvelope() throws {
        #expect(JSONValue.ok(.null).canonicalString == #"{"ok":null}"#)
        #expect(JSONValue.okOrMissing(Optional<Coordinate>.none).canonicalString == #"{"missing":true}"#)
        let cases: [(any Error, String)] = [
            (CantinoError.invalidArgument("m"), #"{"error":{"code":1,"kind":"invalid_argument"}}"#),
            (CantinoError.invalidFile("m"), #"{"error":{"code":2,"kind":"invalid_file"}}"#),
            (CantinoError.io("m"), #"{"error":{"code":3,"kind":"io"}}"#),
            (CantinoError.wrongThread("m"), #"{"error":{"code":4,"kind":"wrong_thread"}}"#),
            (CantinoStateError.internalError("m"), #"{"error":{"code":5,"kind":"internal"}}"#),
        ]
        for (error, expected) in cases {
            #expect(JSONValue.errorOutcome(error)?.canonicalString == expected)
        }
        // The adapter's own closed-store check is not an ABI outcome.
        #expect(JSONValue.errorOutcome(CantinoStateError.closed("m")) == nil)
        // The import report drops database_bytes.
        let report = try Fixtures.importFixture().report
        #expect(report.canonical.canonicalString == #"{"counts":{"nodes":4,"relations":1,"ways":2}}"#)
    }

    @Test func stringsAndKeysFollowTheCorpusRules() {
        // Code point order: "Z" < "a" < "é" < "ｚ" (U+FF5A) < "😀" (U+1F600).
        // UTF-16 order would put the emoji (D83D) before the fullwidth z.
        let value = JSONValue.object([
            "😀": .string("y"), "ｚ": .string("x"), "é": .null, "a": .int(1), "Z": .bool(true),
        ])
        #expect(value.canonicalString == #"{"Z":true,"a":1,"é":null,"ｚ":"x","😀":"y"}"#)
        // Only quote, backslash and controls are escaped; "/" and non-ASCII stay raw.
        let text = JSONValue.string("q\"\\\n\t\r\u{8}\u{C}\u{1}\u{1F}/é😀")
        #expect(text.canonicalString == #""q\"\\\n\t\r\b\f\u0001\u001f/é😀""#)
    }

    @Test func floatsMatchSerdeJson() {
        // Expected strings are serde_json 1.0.151's output (the corpus writer)
        // for the same doubles.
        let cases: [(Double, String)] = [
            (1.0, "1.0"), (0.25, "0.25"), (-112.09, "-112.09"), (12.0, "12.0"),
            (0.3333333333333333, "0.3333333333333333"), (40.00001, "40.00001"), (100.0, "100.0"),
            (1e15, "1000000000000000.0"), (1234567890123456.7, "1234567890123456.8"),
            (1e16, "1e+16"), (1.5e16, "1.5e+16"), (123456789012345680.0, "1.2345678901234568e+17"),
            (1e21, "1e+21"), (1.7976931348623157e308, "1.7976931348623157e+308"),
            (9007199254740993.0, "9007199254740992.0"),
            (1e-5, "0.00001"), (1e-4, "0.0001"), (0.0001234, "0.0001234"), (0.00001234, "0.00001234"),
            (1e-7, "1e-7"), (1.5e-7, "1.5e-7"), (Double.leastNonzeroMagnitude, "5e-324"),
            (0.0, "0.0"), (-0.0, "-0.0"),
        ]
        for (value, expected) in cases {
            #expect(JSONValue.double(value).canonicalString == expected, "\(value)")
        }
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

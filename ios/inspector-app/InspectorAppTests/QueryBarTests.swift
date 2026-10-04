import Cantino
import Testing
@testable import InspectorApp

/// The query bar's syntax. Same cases as android/inspector-app QueryBarTest.
struct QueryBarTests {
    private func error(_ input: String) -> String {
        do {
            _ = try QueryBar.parse(input)
            Issue.record("expected a syntax error for \(input)")
            return ""
        } catch {
            return error.message
        }
    }

    @Test func equalsExistsAndNotExists() throws {
        #expect(try QueryBar.parse("amenity=cafe") == [.equals("amenity", "cafe")])
        #expect(try QueryBar.parse("shop=*") == [.exists("shop")])
        #expect(try QueryBar.parse("!opening_hours") == [.notExists("opening_hours")])
    }

    @Test func termsAreAndedInOrder() throws {
        #expect(try QueryBar.parse("  highway=crossing   !crossing\tkerb=* ")
            == [.equals("highway", "crossing"), .notExists("crossing"), .exists("kerb")])
    }

    @Test func emptyInputIsNoFilter() throws {
        #expect(try QueryBar.parse("") == [])
        #expect(try QueryBar.parse("   ") == [])
    }

    @Test func quotedValuesKeepSpacesAndLiteralStar() throws {
        #expect(try QueryBar.parse("name=\"Caffe Ibis\"") == [.equals("name", "Caffe Ibis")])
        #expect(try QueryBar.parse("note=\"*\"") == [.equals("note", "*")])
        #expect(try QueryBar.parse("name=\"\"") == [.equals("name", "")])
    }

    @Test func valuesAreExactAndCaseSensitive() throws {
        #expect(try QueryBar.parse("Amenity=Cafe") == [.equals("Amenity", "Cafe")])
        // Only the first "=" separates key and value.
        #expect(try QueryBar.parse("description=a=b") == [.equals("description", "a=b")])
    }

    @Test func syntaxErrorsExplainTheFix() {
        #expect(error("amenity") == "\"amenity\" needs a value: use amenity=value, amenity=* for any value, or !amenity for missing.")
        #expect(error("=cafe") == "\"=cafe\" has no key before \"=\".")
        #expect(error("amenity=") == "\"amenity=\" has no value: use amenity=* for any value, or amenity=\"\" for an empty one.")
        #expect(error("!") == "\"!\" needs a key, as in !opening_hours.")
        #expect(error("!crossing=no") == "\"!crossing=no\": a missing-tag term takes no value; write !crossing.")
        #expect(error("name=\"Caffe") == "Unclosed quote.")
    }

    @Test func formatRoundTrips() throws {
        for query in ["amenity=cafe !opening_hours shop=*", "name=\"Caffe Ibis\"", "note=\"*\"", "name=\"\""] {
            #expect(QueryBar.format(try QueryBar.parse(query)) == query)
        }
    }

    @Test func checksAndAcceptanceQueriesParse() throws {
        for query in Checks.all.map(\.query) + Checks.acceptance {
            let filters = try QueryBar.parse(query)
            // Every preset has a filter that can drive the query over the whole area.
            #expect(filters.contains { if case .notExists = $0 { false } else { true } }, "\(query)")
        }
        #expect(Checks.acceptance.count == 14)
    }
}

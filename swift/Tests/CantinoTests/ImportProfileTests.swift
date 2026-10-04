import Foundation
import Testing
@testable import Cantino

/// Port of the Kotlin `ImportProfileTest`, on tests/fixtures/profile.osm
/// (see tests/profile.rs for what each object is there for).
struct ImportProfileTests {
    let profile = ImportProfile(keep: [
        KeepRule(kinds: [.relation, .node, .way], key: "amenity", values: ["cafe"]),
        KeepRule(kinds: [.way], key: "shop"),
    ])

    private func fixture() throws -> (input: String, area: String) {
        let input = Fixtures.repoRoot.appendingPathComponent("tests/fixtures/profile.osm").path
        return (input, try Fixtures.scratchDirectory("profile").appendingPathComponent("area.sqlite").path)
    }

    @Test func profileFiltersWithReferenceClosureAndIsReported() throws {
        let (input, area) = try fixture()
        let report = try OsmStore.importArea(input: input, destination: area, options: ImportOptions(profile: profile))
        #expect(report.counts == ObjectCounts(nodes: 7, ways: 2, relations: 3))
        #expect(report.profile == profile)
        let store = try OsmStore.open(area)
        defer { try? store.close() }
        #expect(try store.get(OsmId(.way, 12)) != nil) // kept as a member of relation 20
        #expect(try store.get(OsmId(.way, 11)) == nil) // only in an unkept relation
        #expect(try store.get(OsmId(.node, 10)) == nil) // shop rule is for ways only
        let cafes = try store.query(Query(tags: [.equals("amenity", "cafe")]))
        #expect(cafes.map(\.id) == [OsmId(.node, 1), OsmId(.relation, 20)])
    }

    @Test func noProfileImportsEverything() throws {
        let (input, area) = try fixture()
        let report = try OsmStore.importArea(input: input, destination: area)
        #expect(report.counts == ObjectCounts(nodes: 10, ways: 3, relations: 4))
        #expect(report.profile == nil)
    }

    @Test func invalidProfileIsInvalidArgument() throws {
        let (input, area) = try fixture()
        for invalid in [ImportProfile(keep: []), ImportProfile(keep: [KeepRule(kinds: [], key: "amenity")])] {
            #expect {
                try OsmStore.importArea(input: input, destination: area, options: ImportOptions(profile: invalid))
            } throws: { error in
                if case CantinoError.invalidArgument = error { true } else { false }
            }
        }
    }

    @Test func wireFormMatchesTheCore() {
        // Kinds in n, w, r order, whatever the set's order.
        #expect(ImportOptions(profile: profile).json()
            == #"{"cache_mb":16,"preserve_untagged_metadata":false,"profile":{"keep":[{"key":"amenity","kinds":"nwr","values":["cafe"]},{"key":"shop","kinds":"w"}]}}"#)
    }
}

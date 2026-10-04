import Foundation
import Testing
@testable import Cantino

/// Port of the Kotlin AsyncOsmStoreTest: ``AsyncOsmStore`` used from
/// arbitrary tasks (and so arbitrary pool threads); the store's thread
/// confinement must hold.
@Suite struct AsyncOsmStoreTests {
    @Test func usableFromAnyTaskAndCloseIsIdempotent() async throws {
        let store = try await AsyncOsmStore.open(Fixtures.importFixture(preserveUntaggedMetadata: false).area)
        // Many concurrent calls from detached tasks all land on the owner thread.
        let ids = try await withThrowingTaskGroup(of: OsmId?.self) { group in
            for _ in 1...20 {
                group.addTask { try await store.get(node(2))?.id }
            }
            return try await group.reduce(into: [OsmId?]()) { $0.append($1) }
        }
        #expect(ids == Array(repeating: node(2), count: 20))
        #expect(try await store.get(node(99)) == nil)
        #expect(try await store.query(Query(tags: [.equals("amenity", "cafe")])).count == 1)
        let way1 = try await store.withStore { try $0.get(way(1)) }
        #expect(way1?.id == way(1))
        // withStore never sees WrongThread, even for several calls in one hop.
        let both = try await store.withStore { s in (try s.get(node(2))?.id, try s.wayCoordinates(1)?.count) }
        #expect(both.0 == node(2) && both.1 == 3)

        try await store.close()
        try await store.close()
        #expect(category(await caught { try await store.get(node(2)) }) == "CLOSED")
    }

    @Test func concurrentCloseIsSafe() async throws {
        let store = try await AsyncOsmStore.open(Fixtures.importFixture().area)
        try await withThrowingTaskGroup(of: Void.self) { group in
            for _ in 1...5 { group.addTask { try await store.close() } }
            try await group.waitForAll()
        }
        #expect(category(await caught { try await store.query(Query()) }) == "CLOSED")
    }

    @Test func openFailureLeavesNoStore() async throws {
        let missing = try Fixtures.scratchDirectory("async-test").appendingPathComponent("missing.sqlite")
        let error = await caught { try await Task.detached { try await AsyncOsmStore.open(missing) }.value }
        #expect(category(error) == "IO")
    }
}

import Cantino
import Foundation

/// The app's single holder of the offline area's store (port of the Android
/// café app's CafeStore.kt).
///
/// Threading is the SDK's job: ``AsyncOsmStore`` owns the one thread an
/// ``OsmStore`` may be used on. This actor only decides *which* store is open.
///
/// Refresh: an open store keeps reading the snapshot it opened, even after
/// ``AreaManager`` publishes a replacement. ``withStore(_:)`` therefore
/// compares the published area's identity (the publishing run's
/// ``AreaMetadata/workId``, else file size + modification date) with the one
/// it opened, and closes and reopens when they differ. So every screen sees
/// the new data on its next query after a refresh, without being told.
///
/// Serialisation: an actor is reentrant at every `await`, so on its own it
/// would let a refresh close the store that another caller has just selected
/// but not yet used. ``SerialGate`` holds the whole select–open–read sequence
/// (Android holds a `Mutex` for the same reason).
actor CafeStore {
    static let areaId = "cafe-area"

    /// The process-wide instance: the map and the detail screens share it.
    static let shared = CafeStore(directory: AreaManager.defaultDirectory)

    private let areas: AreaManager
    private let gate = SerialGate()
    private var store: AsyncOsmStore?
    private var openedIdentity: String?

    /// Test barrier at the selection/use boundary where a refresh could race.
    private var beforeRead: (@Sendable () async -> Void)?

    init(directory: URL) {
        areas = AreaManager(directory: directory)
    }

    func setBeforeRead(_ hook: (@Sendable () async -> Void)?) { beforeRead = hook }

    struct NoAreaError: Error, CustomStringConvertible {
        var description: String { "no offline area has been downloaded" }
    }

    /// Runs `block` on the store thread with a store opened on the current
    /// published area. Throws ``NoAreaError`` when nothing is published.
    func withStore<T: Sendable>(_ block: @escaping @Sendable (OsmStore, AreaInfo) throws -> T) async throws -> T {
        await gate.enter()
        do {
            let result = try await selectAndRun(block)
            await gate.leave()
            return result
        } catch {
            await gate.leave()
            throw error
        }
    }

    private func selectAndRun<T: Sendable>(_ block: @escaping @Sendable (OsmStore, AreaInfo) throws -> T) async throws -> T {
        // The café app always downloads OSM data (never downloadBasemap).
        guard let published = try await areas.loadPublishedArea(areaId: Self.areaId),
              let dataURL = published.dataURL else {
            try await closeStore()
            throw NoAreaError()
        }
        let identity = Self.identity(published)
        if store == nil || identity != openedIdentity {
            try await closeStore()
            store = try await AsyncOsmStore.open(dataURL)
            openedIdentity = identity
        }
        await beforeRead?()
        guard let store else { throw NoAreaError() }
        return try await store.withStore { try block($0, published) }
    }

    private func closeStore() async throws {
        let old = store
        store = nil
        openedIdentity = nil
        try await old?.close()
    }

    private static func identity(_ area: AreaInfo) -> String {
        if let workId = area.metadata?.workId { return workId.uuidString }
        let attributes = area.dataURL.flatMap { try? FileManager.default.attributesOfItem(atPath: $0.path) }
        let size = (attributes?[.size] as? NSNumber)?.int64Value ?? -1
        let modified = (attributes?[.modificationDate] as? Date)?.timeIntervalSince1970 ?? -1
        return "\(size):\(modified)"
    }
}

/// A first-in, first-out async lock: `enter()` waits until every earlier
/// caller has called `leave()`.
actor SerialGate {
    private var busy = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    func enter() async {
        if !busy {
            busy = true
            return
        }
        await withCheckedContinuation { waiters.append($0) }
        // Ownership was handed over by leave(); busy stays true.
    }

    func leave() {
        if waiters.isEmpty {
            busy = false
        } else {
            waiters.removeFirst().resume()
        }
    }
}

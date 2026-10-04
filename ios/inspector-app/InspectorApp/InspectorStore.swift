import Cantino
import Foundation

/// The app's single holder of the offline area's store. Copied from
/// ios/cafe-app CafeStore.swift (area ID and names changed, test hook dropped).
///
/// Threading is the SDK's job: ``AsyncOsmStore`` owns the one thread an
/// ``OsmStore`` may be used on. This actor only decides *which* store is open.
///
/// Refresh: an open store keeps reading the snapshot it opened, even after
/// ``AreaManager`` publishes a replacement. ``withStore(_:)`` compares the
/// published area's identity (``AreaMetadata/workId``, else file size +
/// modification date) with the one it opened, and reopens when they differ.
///
/// Serialisation: an actor is reentrant at every `await`, so ``SerialGate``
/// holds the whole select–open–read sequence (Android holds a `Mutex`).
actor InspectorStore {
    static let areaId = "inspector-area"

    /// The process-wide instance: the map and the navigator share it.
    static let shared = InspectorStore(directory: AreaManager.defaultDirectory)

    private let areas: AreaManager
    private let gate = SerialGate()
    private var store: AsyncOsmStore?
    private var openedIdentity: String?

    init(directory: URL) {
        areas = AreaManager(directory: directory)
    }

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
        // Inspector always downloads OSM data (a full import, never downloadBasemap).
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

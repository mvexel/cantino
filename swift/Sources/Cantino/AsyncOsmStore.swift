import Foundation

/// A concurrency-friendly ``OsmStore``: it owns one dedicated thread, opens
/// the store on it, and runs every call there, so callers can use it from
/// any task without breaking the store's thread confinement. The Swift
/// counterpart of the Kotlin `AsyncOsmStore` (suspend functions there,
/// `async` methods here).
///
/// Why: an ``OsmStore`` must be used on the OS thread that opened it, and a
/// Swift task may resume on a different pool thread after any `await`, so
/// using a plain store from async code works by luck and then fails with
/// ``CantinoError/wrongThread(_:)``. This type makes the right thing the
/// only thing: all store access is funnelled onto the one owner thread.
///
/// Calls are serialised (one at a time, in submission order). Returned
/// objects are `Sendable` values that own copies of their data.
///
/// It is an actor so its own state (the closed flag) is race-free; the
/// store itself lives on the owner thread, not in actor isolation (an
/// actor's executor is not tied to one thread).
///
/// Always ``close()`` it (it holds a thread and a database connection);
/// closing is idempotent. An instance dropped without `close()` closes its
/// store on the owner thread and stops the thread (Kotlin leaks both).
///
/// ```swift
/// let store = try await AsyncOsmStore.open(areaURL)
/// let cafes = try await store.query(Query(tags: [.equals("amenity", "cafe")]))
/// let first = try await store.get(cafes[0].id)
/// // Anything OsmStore offers, in one hop on the owner thread:
/// let both = try await store.withStore { s in (try s.get(idA), try s.get(idB)) }
/// try await store.close()
/// ```
public actor AsyncOsmStore {
    private let thread: OwnerThread
    private let box: StoreBox
    private var closed = false

    private init(thread: OwnerThread, box: StoreBox) {
        self.thread = thread
        self.box = box
    }

    deinit {
        // Nothing can be awaited here; queue the close behind any remaining
        // work and let the thread exit afterwards.
        if !closed {
            let box = self.box
            thread.submit { try? box.store?.close(); box.store = nil }
            thread.shutdown()
        }
    }

    /// Runs `block` on the owner thread with the underlying ``OsmStore`` and
    /// returns its result. Use it for any ``OsmStore`` method this type does
    /// not mirror, and to group several reads into one thread hop.
    ///
    /// `block` runs to completion once started, even if the calling task is
    /// cancelled meanwhile. Do not let the ``OsmStore`` escape `block`: it
    /// is only valid on the owner thread, and not after ``close()`` (it is
    /// not `Sendable`, so the compiler rejects most attempts). Throws
    /// ``CantinoStateError/closed(_:)`` if this store is closed, and whatever
    /// `block` throws (the errors each ``OsmStore`` method documents; never
    /// ``CantinoError/wrongThread(_:)``, since `block` runs on the owner
    /// thread).
    ///
    /// Deviation from Kotlin: `block` is `@Sendable` and its result
    /// `Sendable`, because both cross from the caller's task to the owner
    /// thread and back.
    public func withStore<T: Sendable>(_ block: @escaping @Sendable (OsmStore) throws -> T) async throws -> T {
        guard !closed else { throw CantinoStateError.closed("AsyncOsmStore is closed") }
        let box = self.box
        return try await thread.run {
            // On the owner thread: the only place `box.store` is touched.
            guard let store = box.store else { throw CantinoStateError.closed("AsyncOsmStore is closed") }
            return try block(store)
        }
    }

    /// Async ``OsmStore/get(_:)-(OsmId)``: the object with `id`, or nil if
    /// it is not in this area. Throws ``CantinoError/invalidArgument(_:)``
    /// for a non-positive ID, ``CantinoStateError/closed(_:)`` if closed.
    public func get(_ id: OsmId) async throws -> OsmObject? {
        try await withStore { try $0.get(id) }
    }

    /// Async ``OsmStore/query(_:)``; see ``Query`` for ordering, pagination
    /// and candidate semantics. Throws ``CantinoError/invalidArgument(_:)``
    /// for an invalid query (see ``OsmStore/query(_:)``),
    /// ``CantinoStateError/closed(_:)`` if closed.
    public func query(_ query: Query) async throws -> [OsmObject] {
        try await withStore { try $0.query(query) }
    }

    /// Closes the store on its owner thread, then shuts the thread down.
    /// Idempotent and safe to call concurrently; calls after the first
    /// return immediately. Work already queued runs first. Further use
    /// throws ``CantinoStateError/closed(_:)``.
    ///
    /// Not cancellable: skipping it would leak the connection and the
    /// thread.
    public func close() async throws {
        if closed { return }
        closed = true
        defer { thread.shutdown() }
        let box = self.box
        try await thread.run {
            try box.store?.close()
            box.store = nil
        }
    }

    // MARK: Opening

    /// Starts the owner thread and opens the area database at `path` on it
    /// (see ``OsmStore/open(_:)-(String)``). Throws ``CantinoError/io(_:)``
    /// if the file is missing or unreadable, ``CantinoError/invalidFile(_:)``
    /// if it is not an area database or of an incompatible format version;
    /// no thread is left behind in that case. If the calling task is
    /// cancelled while opening, the store is closed again and this throws
    /// `CancellationError` (the Kotlin wrapper's `ensureActive()`).
    public static func open(_ path: String) async throws -> AsyncOsmStore {
        let thread = OwnerThread(name: "cantino-store")
        let box: StoreBox
        do {
            // Not cancellable: a store opened and then dropped would leak.
            box = try await thread.run { StoreBox(try OsmStore.open(path)) }
        } catch {
            thread.shutdown()
            throw error
        }
        let store = AsyncOsmStore(thread: thread, box: box)
        if Task.isCancelled {
            try? await store.close()
            throw CancellationError()
        }
        return store
    }

    /// ``open(_:)-(String)`` for a file URL.
    public static func open(_ url: URL) async throws -> AsyncOsmStore {
        guard url.isFileURL else { throw CantinoError.invalidArgument("not a file URL: \(url)") }
        return try await open(url.path)
    }
}

/// Holder for the store that lives on the owner thread.
///
/// `@unchecked Sendable` is sound because of a usage rule, not a lock:
/// `store` is read and written only inside blocks run by the owning
/// ``OwnerThread`` (serially, on one OS thread). The box itself is passed
/// around freely; its content never is.
final class StoreBox: @unchecked Sendable {
    var store: OsmStore?

    init(_ store: OsmStore) {
        self.store = store
    }
}

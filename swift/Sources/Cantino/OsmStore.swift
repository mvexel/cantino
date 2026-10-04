import CCantino
import Foundation

/// An open, read-only offline area database.
///
/// Get one from ``open(_:)-(String)``, with a file from your own
/// ``importArea(input:destination:options:)`` (the iOS area manager comes in
/// a later slice).
///
/// **Threading: confined to one thread.** The native handle (one read-only
/// SQLite connection) belongs to the OS thread that called `open`. Every
/// call, including ``close()``, must run on that same thread; calls from
/// another thread throw ``CantinoError/wrongThread(_:)`` instead of
/// corrupting state. The check is the core's (`cantino_*` compares the
/// calling thread's Rust `ThreadId` with the opener's), so it holds for any
/// caller. Use one dedicated thread per store, or ``AsyncOsmStore``, which
/// owns such a thread. Note that Swift tasks are *not* threads: a task may
/// resume on a different pool thread after any `await`, so never hold an
/// `OsmStore` across an `await` in async code; use ``AsyncOsmStore``.
///
/// The class is deliberately not `Sendable`: Swift 6 concurrency checking
/// then flags most attempts to hand a store to another isolation domain at
/// compile time, and the core's runtime check catches the rest.
///
/// Several stores may open the same area, but `open` loads dictionaries, so
/// reuse an open store instead of opening one per query.
///
/// **Snapshots.** A store reads the file as it was when opened. When a
/// refreshed area is published (an import over the same destination), an
/// already-open store keeps reading the old snapshot; close it and open the
/// new one to see the new data.
///
/// Returned objects own copies of their data and outlive the store.
///
/// **Errors.** Native failures are ``CantinoError`` cases (each method
/// names the ones it throws; every method can also throw
/// ``CantinoError/wrongThread(_:)`` and, rarely, ``CantinoError/io(_:)``).
/// A closed store throws ``CantinoStateError/closed(_:)``. The store stays
/// usable after a failed call.
public final class OsmStore {
    /// Most IDs one batch ``get(_:)-(([OsmId]))`` accepts (the same bound as
    /// ``Query/limit``). Kotlin: `OsmStore.MAX_BATCH`.
    public static let maxBatch = 10_000

    /// The live `CantinoStore*`, or nil once closed. Only ever read or
    /// written on the owner thread (every method that touches it is
    /// confined; the core rejects other threads before using the handle).
    private var handle: OpaquePointer?

    private init(handle: OpaquePointer) {
        self.handle = handle
    }

    /// Best-effort release of a store that was never closed. On the owner
    /// thread this frees the connection. From any other thread the core
    /// refuses (WrongThread, handle untouched) and the connection leaks,
    /// because freeing it there is exactly what the confinement forbids.
    /// Kotlin has no finalizer at all; always call ``close()``.
    deinit {
        if let handle {
            var call = NativeCall()
            _ = cantino_close(handle, &call.error)
        }
    }

    // MARK: Opening and importing

    /// Opens a published area database (created by
    /// ``importArea(input:destination:options:)``) read-only. The calling
    /// thread becomes the store's owner thread.
    ///
    /// Throws ``CantinoError/io(_:)`` if the file is missing or unreadable,
    /// ``CantinoError/invalidFile(_:)`` if it is not an area database or of
    /// an incompatible format version (re-import or re-download it).
    public static func open(_ path: String) throws -> OsmStore {
        var call = NativeCall()
        var store: OpaquePointer?
        _ = try call.check(cantino_open(path, &store, &call.error))
        guard let store else {
            throw CantinoStateError.internalError("Cantino internal error: open returned no handle")
        }
        return OsmStore(handle: store)
    }

    /// ``open(_:)-(String)`` for a file URL. Throws
    /// ``CantinoError/invalidArgument(_:)`` for a non-file URL.
    public static func open(_ url: URL) throws -> OsmStore {
        try open(filePath(url))
    }

    /// Imports an OSM PBF or OSM XML file (`input`) into an area database at
    /// `destination`, publishing it atomically: the new file replaces
    /// `destination` in one rename once complete, and a failed import leaves
    /// any existing file at `destination` intact.
    ///
    /// Synchronous disk and CPU work (seconds for a city): never call it
    /// from the main thread. Any thread otherwise; no store is involved. An
    /// open store on `destination` keeps its old snapshot; close and reopen
    /// it to see the replacement.
    ///
    /// Throws ``CantinoError/invalidFile(_:)`` for corrupt, truncated or
    /// invalid input (not OSM PBF/XML, a history file, unsorted or duplicate
    /// IDs), ``CantinoError/io(_:)`` for I/O errors (a missing `input`, an
    /// unwritable `destination` directory, a full disk).
    @discardableResult
    public static func importArea(input: String, destination: String, options: ImportOptions = ImportOptions()) throws -> ImportReport {
        var call = NativeCall()
        _ = try call.check(cantino_import(input, destination, options.json(), &call.result, &call.error))
        return try Wire.decode(WireReport.self, call.resultString()).model
    }

    /// ``importArea(input:destination:options:)`` for file URLs.
    @discardableResult
    public static func importArea(input: URL, destination: URL, options: ImportOptions = ImportOptions()) throws -> ImportReport {
        try importArea(input: filePath(input), destination: filePath(destination), options: options)
    }

    private static func filePath(_ url: URL) throws(CantinoError) -> String {
        guard url.isFileURL else { throw .invalidArgument("not a file URL: \(url)") }
        return url.path
    }

    // MARK: Lookups

    /// The object with `id`, or nil when it is not in this area (outside
    /// the extract, or referenced by a way or relation crossing the area's
    /// edge). Throws ``CantinoError/invalidArgument(_:)`` for a non-positive
    /// ID, ``CantinoError/wrongThread(_:)`` off the owner thread,
    /// ``CantinoStateError/closed(_:)`` if the store is closed.
    public func get(_ id: OsmId) throws -> OsmObject? {
        let handle = try live()
        var call = NativeCall()
        let status = try call.check(cantino_get(handle, id.kind.rawValue, id.id, &call.result, &call.error))
        if status == 1 { return nil }
        return try Wire.decode(WireObject.self, call.resultString()).model()
    }

    /// Looks up several objects in one native call: one entry per element
    /// of `ids`, in the same order, nil where the object is not in this
    /// area. Duplicate IDs are returned once per occurrence.
    ///
    /// Use it instead of calling ``get(_:)-(OsmId)`` in a loop (for example
    /// for a way's ``OsmObject/Way/nodeIds`` or a relation's members): each
    /// single get crosses the ABI and decodes JSON once, this crosses once
    /// for the whole list.
    ///
    /// At most ``maxBatch`` (10 000) IDs per call; more throws
    /// ``CantinoError/invalidArgument(_:)`` (never a silent truncation), as
    /// does an ID that is not positive. Throws
    /// ``CantinoError/wrongThread(_:)`` off the owner thread,
    /// ``CantinoStateError/closed(_:)`` if the store is closed.
    public func get(_ ids: [OsmId]) throws -> [OsmObject?] {
        let handle = try live() // closed-store check even for an empty list
        if ids.isEmpty { return [] }
        let request = JSONValue.array(ids.map(\.json)).canonicalString
        var call = NativeCall()
        _ = try call.check(cantino_get_many(handle, request, &call.result, &call.error))
        return try Wire.decode([WireObject?].self, call.resultString()).map { try $0?.model() }
    }

    /// The coordinates of way `wayId`'s nodes, in the way's order with
    /// repeats kept (a closed way ends with its first coordinate again), in
    /// one native call.
    ///
    /// Returns nil when the way is not in this area. An entry is nil when
    /// that node is outside the area (the way crosses the area's edge): do
    /// not draw a line across such a gap.
    ///
    /// Throws ``CantinoError/invalidArgument(_:)`` for a non-positive ID,
    /// ``CantinoError/wrongThread(_:)`` off the owner thread,
    /// ``CantinoStateError/closed(_:)`` if the store is closed.
    public func wayCoordinates(_ wayId: Int64) throws -> [Coordinate?]? {
        let handle = try live()
        var call = NativeCall()
        let status = try call.check(cantino_way_coordinates(handle, wayId, &call.result, &call.error))
        if status == 1 { return nil }
        // Flat [lat_e7, lon_e7, lat_e7, lon_e7, ...]; null, null for a node
        // outside the area. See cantino_way_coordinates in include/cantino.h.
        let flat = try Wire.decode([Int32?].self, call.resultString())
        guard flat.count % 2 == 0 else { throw Wire.malformed("odd way coordinate count") }
        return stride(from: 0, to: flat.count, by: 2).map { index in
            guard let lat = flat[index], let lon = flat[index + 1] else { return nil }
            return Coordinate(latE7: lat, lonE7: lon)
        }
    }

    /// Runs `query` and returns at most ``Query/limit`` objects; see
    /// ``Query`` for ordering, pagination and the candidate semantics of a
    /// bbox.
    ///
    /// Throws ``CantinoError/invalidArgument(_:)`` for an invalid query
    /// (limit outside 1..10 000, invalid or non-finite bbox, more spatial
    /// candidates than ``Query/maxCandidates``, only
    /// ``TagFilter/notExists(_:)`` filters and no bbox),
    /// ``CantinoError/wrongThread(_:)`` off the owner thread,
    /// ``CantinoStateError/closed(_:)`` if the store is closed.
    public func query(_ query: Query) throws -> [OsmObject] {
        let handle = try live()
        let request = try query.json()
        var call = NativeCall()
        _ = try call.check(cantino_query(handle, request, &call.result, &call.error))
        return try Wire.decode([WireObject].self, call.resultString()).map { try $0.model() }
    }

    // MARK: Closing

    /// Closes the store and its connection. Idempotent; must run on the
    /// owner thread (otherwise ``CantinoError/wrongThread(_:)``, and the
    /// store stays open).
    public func close() throws {
        guard let handle else { return }
        var call = NativeCall()
        _ = try call.check(cantino_close(handle, &call.error))
        // Only forget the handle once the core has freed it: on WrongThread
        // it is untouched and still ours to close from the right thread.
        self.handle = nil
    }

    private func live() throws(CantinoStateError) -> OpaquePointer {
        guard let handle else { throw .closed("store is closed") }
        return handle
    }
}

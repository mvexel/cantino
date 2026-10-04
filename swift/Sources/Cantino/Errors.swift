import CCantino

/// A failure reported by Cantino's native core, by category. Mirrors the
/// Kotlin sealed `CantinoException`: switch over it exhaustively, or catch
/// `CantinoError` for all of them.
///
/// | Case | Meaning | Typical reaction |
/// | --- | --- | --- |
/// | ``invalidArgument(_:)`` | The call was wrong: bad query, bbox, ID or batch size | Fix the call (a bug, or bad user input) |
/// | ``invalidFile(_:)`` | A file is not what it should be: not an area, old format, corrupt input | Re-download / re-import |
/// | ``io(_:)`` | The environment failed: missing file, permission, disk full | Free space, check the path, retry |
/// | ``wrongThread(_:)`` | A thread-confined handle was used from another thread | Fix the threading (``AsyncOsmStore``) |
///
/// The associated string is a developer-facing message from the core (not
/// localized). An ``OsmStore`` remains usable after any of these.
///
/// The category comes from `cantino_last_error_code()` (read on the failing
/// thread right after the call), never from the message text.
///
/// Not covered: a closed store and internal errors of the core throw
/// ``CantinoStateError`` (the Kotlin adapter's `IllegalStateException`).
public enum CantinoError: Error, Sendable, Equatable {
    /// The caller passed something invalid: a query with a limit outside
    /// 1..10 000, only ``TagFilter/notExists(_:)`` filters and no bbox, an
    /// invalid bbox, more spatial candidates than ``Query/maxCandidates``,
    /// a batch ``OsmStore/get(_:)-(([OsmId]))`` over ``OsmStore/maxBatch``
    /// IDs, a non-positive ID; also an invalid ``Bbox/around(lat:lon:widthKm:heightKm:)``
    /// argument (Kotlin: `IllegalArgumentException`, see README).
    case invalidArgument(String)
    /// Not an area database, an area of an incompatible format version
    /// (re-import it), a corrupt/truncated/unreadable OSM PBF or XML input
    /// (or one with unsorted or duplicate IDs).
    case invalidFile(String)
    /// The environment failed: a missing file, a permission problem, a full
    /// disk, an I/O error. Opening a path that does not exist is ``io(_:)``;
    /// a file that exists but is no area is ``invalidFile(_:)``.
    case io(String)
    /// A thread-confined handle (an ``OsmStore``) was used from a thread
    /// other than the one that opened it. The handle is unaffected.
    case wrongThread(String)

    /// The developer-facing message of any case.
    public var message: String {
        switch self {
        case .invalidArgument(let m), .invalidFile(let m), .io(let m), .wrongThread(let m): m
        }
    }
}

extension CantinoError: CustomStringConvertible {
    public var description: String {
        switch self {
        case .invalidArgument(let m): "Cantino invalid argument: \(m)"
        case .invalidFile(let m): "Cantino invalid file: \(m)"
        case .io(let m): "Cantino I/O error: \(m)"
        case .wrongThread(let m): "Cantino wrong thread: \(m)"
        }
    }
}

/// Programming or core errors that no app can handle meaningfully: the
/// Swift counterpart of the Kotlin adapter's `IllegalStateException`.
///
/// A Swift `Error` rather than a `preconditionFailure`, so that a stray call
/// on a closed store, or a bug in the core, does not crash the app (the
/// Kotlin exception is catchable too) and tests can assert on it. It is a
/// separate type from ``CantinoError`` so that a `switch` over the domain
/// errors stays about the domain.
public enum CantinoStateError: Error, Sendable, Equatable {
    /// The ``OsmStore`` or ``AsyncOsmStore`` was used after `close()`.
    case closed(String)
    /// A bug in the core (`CANTINO_ERROR_INTERNAL`, including a caught Rust
    /// panic), an unknown error category (version skew between adapter and
    /// core), or core output this adapter could not decode. Report it. The
    /// message starts with "Cantino internal error", as in Kotlin.
    case internalError(String)
}

extension CantinoStateError: CustomStringConvertible {
    public var description: String {
        switch self {
        case .closed(let m): m
        case .internalError(let m): m
        }
    }
}

// MARK: - C ABI call plumbing

/// Owns the two out-buffers every fallible ABI call may fill (`*json` /
/// `*report` and `*error`) and frees them with `cantino_free` on every path:
/// the header requires freeing both, including an error string returned
/// alongside a failure.
struct NativeCall: ~Copyable {
    var result: UnsafeMutablePointer<CChar>? = nil
    var error: UnsafeMutablePointer<CChar>? = nil

    deinit {
        cantino_free(result)
        cantino_free(error)
    }

    /// Checks a status code: 0 or 1 are returned as-is, -1 becomes the
    /// typed error. Must be called right after the ABI call on the same
    /// thread, because the category is thread-local and overwritten by the
    /// next ABI call (errno-style).
    func check(_ status: Int32) throws -> Int32 {
        if status >= 0 { return status }
        let code = cantino_last_error_code()
        let message = error.map { String(cString: $0) } ?? "unknown error"
        throw mapError(code: code, message: message)
    }

    /// The result buffer as a Swift string (a copy; the buffer is freed in
    /// deinit). Only valid after a successful call that produced JSON.
    func resultString() throws -> String {
        guard let result else {
            throw CantinoStateError.internalError("Cantino internal error: core returned no result buffer")
        }
        return String(cString: result)
    }
}

/// `CANTINO_ERROR_*` code to the Swift error. The C macros are imported as
/// Swift constants by the Clang module; matching on them keeps the mapping
/// tied to the header.
func mapError(code: Int32, message: String) -> any Error {
    switch code {
    case CANTINO_ERROR_INVALID_ARGUMENT: return CantinoError.invalidArgument(message)
    case CANTINO_ERROR_INVALID_FILE: return CantinoError.invalidFile(message)
    case CANTINO_ERROR_IO: return CantinoError.io(message)
    case CANTINO_ERROR_WRONG_THREAD: return CantinoError.wrongThread(message)
    case CANTINO_ERROR_INTERNAL: return CantinoStateError.internalError("Cantino internal error: \(message)")
    default:
        // A code this adapter does not know (the core appended a category,
        // or NONE after a -1): version skew, so a bug, not a domain error.
        return CantinoStateError.internalError("Cantino internal error (category \(code)): \(message)")
    }
}

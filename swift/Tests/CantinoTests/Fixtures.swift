import Foundation
@testable import Cantino

/// The same fixture the Kotlin instrumented tests and the Rust tests use
/// (tests/fixtures/snapshot.osm), read in place from the repository rather
/// than copied as a SwiftPM resource (resources must live inside the
/// target's directory). Resolved relative to this source file.
enum Fixtures {
    static let repoRoot = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent() // CantinoTests
        .deletingLastPathComponent() // Tests
        .deletingLastPathComponent() // swift
        .deletingLastPathComponent() // repository root

    static let snapshotOsm = repoRoot.appendingPathComponent("tests/fixtures/snapshot.osm")

    /// A fresh, unique scratch directory per call (tests run in parallel).
    static func scratchDirectory(_ prefix: String = "osm-test") throws -> URL {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(prefix)-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }

    /// Imports the fixture into a fresh area file and returns its path.
    /// Keeps untagged-node metadata (as the Kotlin `importFixture`) so node
    /// 1's metadata can be checked.
    static func importFixture(preserveUntaggedMetadata: Bool = true) throws -> (area: String, report: ImportReport) {
        let area = try scratchDirectory().appendingPathComponent("area.sqlite").path
        let report = try OsmStore.importArea(
            input: snapshotOsm.path, destination: area,
            options: ImportOptions(preserveUntaggedMetadata: preserveUntaggedMetadata))
        return (area, report)
    }
}

/// Runs `body` on a new OS thread and returns what it returns (or throws
/// what it throws), blocking the caller until it is done. For the
/// wrong-thread tests: it gives a second thread to call from.
func onOtherThread<T: Sendable>(_ body: @escaping @Sendable () throws -> T) throws -> T {
    let done = DispatchSemaphore(value: 0)
    let result = ResultBox<T>()
    let thread = Thread {
        result.value = Result { try body() }
        done.signal()
    }
    thread.start()
    done.wait()
    return try result.value!.get()
}

/// Hand-off cell for `onOtherThread`; the semaphore orders the write
/// before the read.
private final class ResultBox<T>: @unchecked Sendable {
    var value: Result<T, any Error>?
}

/// Shorthand IDs, as `OsmId(OsmKind.NODE, 1)` in the Kotlin tests.
func node(_ id: Int64) -> OsmId { OsmId(.node, id) }
func way(_ id: Int64) -> OsmId { OsmId(.way, id) }
func relation(_ id: Int64) -> OsmId { OsmId(.relation, id) }

/// The error `body` throws, or nil if it returns. Lets a test assert on the
/// exact category (`CantinoError` cases carry messages, so `#expect(throws:)`
/// with a value would also pin the message text, which is not contract).
func caught<T>(_ body: () throws -> T) -> (any Error)? {
    do {
        _ = try body()
        return nil
    } catch {
        return error
    }
}

/// Async `caught`.
func caught<T>(_ body: () async throws -> T) async -> (any Error)? {
    do {
        _ = try await body()
        return nil
    } catch {
        return error
    }
}

/// "INVALID_ARGUMENT", "INVALID_FILE", "IO", "WRONG_THREAD" for a
/// `CantinoError` (the header's names, via the parity canonical form),
/// "CLOSED"/"INTERNAL" for a `CantinoStateError`, nil otherwise.
func category(_ error: (any Error)?) -> String? {
    switch error {
    case let error as CantinoError:
        if case .object(let fields) = error.canonical, case .string(let name)? = fields["error"] { return name }
        return nil
    case let error as CantinoStateError:
        if case .closed = error { return "CLOSED" }
        return "INTERNAL"
    default:
        return nil
    }
}

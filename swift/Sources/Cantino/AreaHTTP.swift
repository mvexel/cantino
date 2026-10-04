// HTTP for area downloads over URLSession: the SliceOSM protocol, plain
// file downloads and the HTTP range requests of a basemap extract. The
// Swift counterpart of the Kotlin adapter's SliceHttp.kt.
//
// Every failure is classified by the core's table (Failures, i.e.
// `cantino_classify_failure`), so iOS retries exactly what Android retries;
// this file only chooses the messages. Requests run in the calling task and
// are cancelled with it (URLSession aborts the transfer at once).
//
// Downloads go through download tasks: URLSession streams the body to a
// temporary file, which is then moved to the target, so large PBFs and tile
// ranges never sit in memory.

import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Why a download step failed, classified by what the run does next (the
/// kind) and by what the app can do about it (the reason, which ends up in
/// ``AreaState/failed(runId:message:retryable:reason:)``). Kotlin's sealed
/// `DownloadFailure`.
struct DownloadFailure: Error, CustomStringConvertible {
    enum Kind: Sendable {
        /// May succeed later without changing the request: retried inline,
        /// then by the run's backoff.
        case transient
        /// Retrying the same request cannot help.
        case permanent
        /// The device's storage failed (typically a full disk): retried by
        /// the run's backoff, never inline.
        case storage
        /// The server no longer knows the SliceOSM job: submit a new one.
        case jobGone
    }

    let kind: Kind
    let reason: FailureReason
    let message: String

    var description: String { message }

    static func transient(_ message: String, _ reason: FailureReason) -> DownloadFailure {
        DownloadFailure(kind: .transient, reason: reason, message: message)
    }

    static func permanent(_ message: String, _ reason: FailureReason) -> DownloadFailure {
        DownloadFailure(kind: .permanent, reason: reason, message: message)
    }
}

extension Failures.Classified {
    /// The ``DownloadFailure`` to throw, carrying `message`.
    func failure(_ message: String) -> DownloadFailure {
        let kind: DownloadFailure.Kind = switch failureClass {
        case .transient: .transient
        case .permanent: .permanent
        case .storage: .storage
        case .jobGone: .jobGone
        }
        return DownloadFailure(kind: kind, reason: FailureReason(rawValue: reason.rawValue) ?? .unknown, message: message)
    }
}

extension Failures {
    /// A native error from a `context` call as a ``DownloadFailure``;
    /// errors that are not the core's (a bug) classify as internal.
    static func failure(_ error: any Error, context: NativeContext, _ message: String) -> DownloadFailure {
        do {
            return try native(error, context: context).failure("\(message): \(error)")
        } catch {
            return .permanent("\(message): \(error)", .unknown)
        }
    }

    /// An I/O error on the network or in local storage as a ``DownloadFailure``.
    static func failure(io context: IOContext, _ message: String) -> DownloadFailure {
        (try? io(context))?.failure(message) ?? .permanent(message, .unknown)
    }
}

/// The crate version (Cargo.toml), for the User-Agent. A test keeps it in step.
let cantinoVersion = "0.4.0"

/// A local file operation failed: storage, not network.
private struct LocalStorageError: Error {
    let message: String
}

final class AreaHTTP: Sendable {
    private let session: URLSession
    private let timeout: TimeInterval

    /// `configuration` lets tests route requests to an in-process server
    /// (`protocolClasses`); production uses `.ephemeral` (no cookies, no
    /// cache: every answer is fresh).
    init(configuration: URLSessionConfiguration, timeout: TimeInterval) {
        configuration.timeoutIntervalForRequest = timeout
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.urlCache = nil
        session = URLSession(configuration: configuration)
        self.timeout = timeout
    }

    deinit {
        session.finishTasksAndInvalidate()
    }

    /// POSTs `body` as JSON and returns the answer text (SliceOSM submit).
    func postJSON(_ url: String, body: String) async throws -> String {
        var request = try self.request(url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = Data(body.utf8)
        let (data, response) = try await data(request)
        try checkStatus(response, data: data, url: url, context: .request)
        return String(decoding: data, as: UTF8.self)
    }

    /// GETs a small text document (SliceOSM status). A 404 means the job is gone.
    func getText(_ url: String) async throws -> String {
        let (data, response) = try await data(try request(url))
        try checkStatus(response, data: data, url: url, context: .job)
        return String(decoding: data, as: UTF8.self)
    }

    /// Downloads `url` into `target` (replacing it; no byte-range resume:
    /// an interrupted download restarts from byte 0). `onProgress` gets
    /// (bytes so far, total or nil). Returns the number of bytes. A 404 is a
    /// vanished job with `goneOn404`, else a permanent request error.
    func download(_ url: String, to target: URL, goneOn404: Bool = true,
                  onProgress: @escaping @Sendable (Int64, Int64?) -> Void) async throws -> Int64 {
        var request = try self.request(url)
        // No transparent compression: byte counts must be the file's own size.
        request.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
        let response = try await transfer(request, to: target, onProgress: onProgress)
        try checkStatus(response, data: errorBody(target), url: url, context: goneOn404 ? .job : .request)
        let bytes = try fileSize(target)
        let total = response.expectedContentLength
        if total >= 0, bytes != total {
            throw DownloadFailure.transient("download truncated: \(bytes) of \(total) bytes", .network)
        }
        return bytes
    }

    /// Bytes `offset ..< offset + length` of `url` in memory (directory
    /// ranges of a basemap extract: small). With `allowShort` the server may
    /// answer with fewer bytes when the file ends earlier (the 16 KiB first
    /// request of a tiny archive).
    func getRange(_ url: String, offset: Int64, length: Int64, allowShort: Bool = false) async throws -> Data {
        let (data, response) = try await data(try rangeRequest(url, offset: offset, length: length))
        let expected = try checkRange(response, data: data, url: url, offset: offset, length: length,
                                      allowShort: allowShort)
        guard Int64(data.count) == expected else {
            throw DownloadFailure.transient(
                "range \(offset)+\(length) truncated at \(data.count) of \(expected) bytes", .network)
        }
        return data
    }

    /// Bytes `offset ..< offset + length` of `url` into `target` (tile
    /// ranges, which can be megabytes: they go to disk and from there into
    /// the native assembler).
    func downloadRange(_ url: String, offset: Int64, length: Int64, to target: URL) async throws {
        let response = try await transfer(try rangeRequest(url, offset: offset, length: length), to: target,
                                          onProgress: nil)
        _ = try checkRange(response, data: errorBody(target), url: url, offset: offset, length: length,
                           allowShort: false)
        let bytes = try fileSize(target)
        // Fewer bytes than asked for is a dropped connection: re-fetch.
        if bytes != length {
            throw DownloadFailure.transient("range \(offset)+\(length) truncated: \(bytes) bytes", .network)
        }
    }

    // MARK: Requests

    private func request(_ url: String) throws -> URLRequest {
        guard let parsed = URL(string: url), parsed.scheme == "http" || parsed.scheme == "https" else {
            throw DownloadFailure.permanent("not an http(s) URL: \(url)", .invalidRequest)
        }
        var request = URLRequest(url: parsed, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: timeout)
        request.setValue("cantino/\(cantinoVersion)", forHTTPHeaderField: "User-Agent")
        return request
    }

    private func rangeRequest(_ url: String, offset: Int64, length: Int64) throws -> URLRequest {
        precondition(length > 0, "empty range")
        var request = try self.request(url)
        request.setValue("bytes=\(offset)-\(offset + length - 1)", forHTTPHeaderField: "Range")
        // Byte ranges address the stored representation; no transparent gzip.
        request.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
        return request
    }

    /// A small request, body in memory.
    private func data(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let (data, response) = try await network { try await self.session.data(for: request) }
        guard let http = response as? HTTPURLResponse else {
            throw DownloadFailure.transient("not an HTTP response from \(request.url!)", .server)
        }
        return (data, http)
    }

    /// A download task into `target`, cancelled with the calling task.
    /// Progress is observed on `countOfBytesReceived`.
    private func transfer(_ request: URLRequest, to target: URL,
                          onProgress: (@Sendable (Int64, Int64?) -> Void)?) async throws -> HTTPURLResponse {
        try await network {
            let holder = TaskHolder()
            return try await withTaskCancellationHandler {
                try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<HTTPURLResponse, any Error>) in
                    let task = self.session.downloadTask(with: request) { location, response, error in
                        holder.finished()
                        if let error { return continuation.resume(throwing: error) }
                        guard let location, let http = response as? HTTPURLResponse else {
                            return continuation.resume(
                                throwing: DownloadFailure.transient("not an HTTP response from \(request.url!)", .server))
                        }
                        // The temporary file is deleted when this handler returns.
                        do {
                            let files = FileManager.default
                            try? files.removeItem(at: target)
                            try files.moveItem(at: location, to: target)
                            continuation.resume(returning: http)
                        } catch {
                            continuation.resume(throwing: LocalStorageError(
                                message: "cannot write \(target.lastPathComponent): \(error.localizedDescription)"))
                        }
                    }
                    #if canImport(Darwin)
                    // KVO is Darwin-only; on Linux only the final size is reported.
                    if let onProgress {
                        holder.observe(task.observe(\.countOfBytesReceived) { task, _ in
                            let total = task.countOfBytesExpectedToReceive
                            onProgress(task.countOfBytesReceived, total > 0 ? total : nil)
                        })
                    }
                    #endif
                    holder.start(task)
                }
            } onCancel: {
                holder.cancel()
            }
        }
    }

    /// Runs a request: URLError is the network (classified by the core),
    /// unless the calling task was cancelled, in which case the cancellation
    /// wins; local file errors are storage.
    private func network<T>(_ body: () async throws -> T) async throws -> T {
        do {
            return try await body()
        } catch let error as DownloadFailure {
            throw error
        } catch let error as LocalStorageError {
            throw Failures.failure(io: .storage, error.message)
        } catch {
            try Task.checkCancellation()
            throw Failures.failure(io: .network, "network error: \(error.localizedDescription)")
        }
    }

    // MARK: Status checks

    /// Throws for a status the request does not accept, per the core's table:
    /// 2xx passes; 404 is a vanished job only for `.job`; 408, 429 and 5xx
    /// are transient; any other status is a permanent invalid request.
    private func checkStatus(_ response: HTTPURLResponse, data: Data, url: String,
                             context: Failures.HTTPContext) throws {
        let code = response.statusCode
        guard let classified = try Failures.http(code, context: context) else { return }
        throw classified.failure(statusMessage(url: url, code: code, body: data))
    }

    /// Checks a range answer before its body is used; returns the number of
    /// body bytes to expect. 206 with a `Content-Range` starting at `offset`
    /// and exactly `length` bytes (fewer with `allowShort`) is the only
    /// success; a 200 means the server ignored Range (and sent the whole,
    /// possibly 100+ GB, file): permanent.
    private func checkRange(_ response: HTTPURLResponse, data: Data, url: String, offset: Int64, length: Int64,
                            allowShort: Bool) throws -> Int64 {
        let code = response.statusCode
        if let classified = try Failures.http(code, context: .range) {
            let message = switch code {
            case 200: "server ignored the Range header (HTTP 200 instead of 206) for \(url); "
                + "basemap extracts need a server or CDN with HTTP range support"
            case 416: "HTTP 416 range \(offset)+\(length) not satisfiable at \(url)"
            case 200..<300: "HTTP \(code) for a range request to \(url) (need 206)"
            default: statusMessage(url: url, code: code, body: data)
            }
            throw classified.failure(message)
        }
        guard let header = response.value(forHTTPHeaderField: "Content-Range"),
              let (first, last) = Self.parseContentRange(header) else {
            throw DownloadFailure.permanent("206 without a valid Content-Range from \(url)", .server)
        }
        let got = last - first + 1
        let lengthOK = allowShort ? (1...length).contains(got) : got == length
        guard first == offset, lengthOK else {
            throw DownloadFailure.permanent(
                "server sent bytes \(first)-\(last) for requested \(offset)+\(length) from \(url)", .server)
        }
        let contentLength = response.expectedContentLength
        if contentLength >= 0, contentLength != got {
            throw DownloadFailure.permanent(
                "Content-Length \(contentLength) disagrees with Content-Range \(first)-\(last) from \(url)", .server)
        }
        return got
    }

    /// "bytes first-last/total" (total may be `*`).
    static func parseContentRange(_ header: String) -> (Int64, Int64)? {
        let text = header.trimmingCharacters(in: .whitespaces)
        guard text.hasPrefix("bytes ") else { return nil }
        let parts = text.dropFirst(6).split(separator: "/", maxSplits: 1)
        guard parts.count == 2 else { return nil }
        let bounds = parts[0].split(separator: "-", maxSplits: 1)
        guard bounds.count == 2, let first = Int64(bounds[0]), let last = Int64(bounds[1]), last >= first,
              parts[1] == "*" || Int64(parts[1]) != nil else { return nil }
        return (first, last)
    }

    /// "HTTP <code> from <url> <start of the error body>".
    private func statusMessage(url: String, code: Int, body: Data) -> String {
        let detail = String(decoding: body.prefix(200), as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)
        return "HTTP \(code) from \(url) \(detail)".trimmingCharacters(in: .whitespaces)
    }

    /// The start of a downloaded error body, for messages.
    private func errorBody(_ file: URL) -> Data {
        guard let handle = try? FileHandle(forReadingFrom: file) else { return Data() }
        defer { try? handle.close() }
        return (try? handle.read(upToCount: 200)) ?? Data()
    }

    private func fileSize(_ file: URL) throws -> Int64 {
        do {
            return (try FileManager.default.attributesOfItem(atPath: file.path)[.size] as? NSNumber)?.int64Value ?? 0
        } catch {
            throw Failures.failure(io: .storage, "cannot read \(file.lastPathComponent): \(error.localizedDescription)")
        }
    }
}

/// The download task of one transfer, for cancellation from another thread
/// (the cancellation handler can run before the task exists), plus its
/// progress observation (kept alive until the task finishes).
private final class TaskHolder: @unchecked Sendable {
    private let lock = NSLock()
    private var task: URLSessionTask?
    #if canImport(Darwin)
    private var observation: NSKeyValueObservation?
    #endif
    private var cancelled = false

    func start(_ task: URLSessionTask) {
        lock.lock()
        self.task = task
        let cancelled = self.cancelled
        lock.unlock()
        task.resume()
        if cancelled { task.cancel() }
    }

    #if canImport(Darwin)
    func observe(_ observation: NSKeyValueObservation) {
        lock.lock()
        self.observation = observation
        lock.unlock()
    }
    #endif

    func cancel() {
        lock.lock()
        cancelled = true
        let task = self.task
        lock.unlock()
        task?.cancel()
    }

    func finished() {
        #if canImport(Darwin)
        lock.lock()
        let observation = self.observation
        self.observation = nil
        lock.unlock()
        observation?.invalidate()
        #endif
    }
}

/// Retries transient failures within one attempt with doubling delays
/// (``DownloadTuning/inlineRetries`` retries after the first try). Other
/// failures and cancellation pass straight through.
func retryingInline<T>(_ body: () async throws -> T) async throws -> T {
    let tuning = DownloadTuning.current
    var wait = tuning.inlineRetryDelay
    for _ in 0..<tuning.inlineRetries {
        do {
            return try await body()
        } catch let failure as DownloadFailure where failure.kind == .transient {
            try await Task.sleep(nanoseconds: UInt64(wait * 1e9))
            wait *= 2
        }
    }
    return try await body()
}

/// Internal scheduling defaults; tests shorten them through ``current``.
struct DownloadTuning: Sendable {
    var pollInterval: TimeInterval = 2
    var maxSliceWait: TimeInterval = 8 * 60
    var inlineRetries = 3
    var inlineRetryDelay: TimeInterval = 1
    var maxRunAttempts = 5
    var backoffDelay: TimeInterval = 30
    var basemapParallelism = 4

    private static let lock = NSLock()
    nonisolated(unsafe) private static var override: DownloadTuning?

    /// The tuning in effect: the defaults, or a test's override.
    static var current: DownloadTuning {
        get { lock.withLock { override ?? DownloadTuning() } }
        set { lock.withLock { override = newValue } }
    }

    /// Back to the defaults (tests).
    static func reset() {
        lock.withLock { override = nil }
    }
}

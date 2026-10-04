// Classification of download failures, the Swift counterpart of the Kotlin
// adapter's internal `Failures` object (SliceHttp.kt). The table lives in
// the Rust core (src/failure.rs, `cantino_classify_failure`) so Android and
// iOS retry the same failures the same way; this file only builds the
// request and reads the answer. Pure, any thread. Internal, like Kotlin's:
// the area manager turns a ``Classified`` into its download-failure type.

import CCantino
import Foundation

enum Failures {
    /// What was being asked when an HTTP status came back.
    enum HTTPContext: String, Sendable {
        /// SliceOSM job submission and status polling.
        case job
        /// A plain file download.
        case request
        /// A basemap range request (206 is the only success).
        case range
    }

    /// Where an I/O error happened.
    enum IOContext: String, Sendable {
        case network
        case storage
    }

    /// The call a native error came out of.
    enum NativeContext: String, Sendable {
        /// Import, PMTiles validation.
        case `default`
        /// Basemap extract.
        case engine
        case protocolRequest = "protocol_request"
        case protocolResponse = "protocol_response"
    }

    /// A failure to classify.
    enum Input: Hashable, Sendable {
        case http(Int, HTTPContext)
        case io(IOContext)
        /// A `CANTINO_ERROR_*` code (1...5) from a native `context` call.
        case native(Int32, NativeContext)

        var json: JSONValue {
            switch self {
            case .http(let status, let context):
                .object(["http": .int(Int64(status)), "context": .string(context.rawValue)])
            case .io(let context):
                .object(["io": .string(context.rawValue)])
            case .native(let code, let context):
                .object(["native": .int(Int64(code)), "context": .string(context.rawValue)])
            }
        }
    }

    /// The core's class name for a failure.
    enum FailureClass: String, Sendable {
        /// Worth retrying: inline, then by the scheduler.
        case transient
        /// Repeating will not help.
        case permanent
        /// The device's storage: retried by the scheduler once storage is no longer low, never inline.
        case storage
        /// The SliceOSM job no longer exists on the server: submit a new one.
        case jobGone = "job_gone"
    }

    /// Why it failed, for the app to show.
    enum Reason: String, Sendable {
        case network
        case server
        case invalidRequest = "invalid_request"
        case storage
        case invalidData = "invalid_data"
        case unknown
    }

    /// A classification: the class and reason, and what the core says about
    /// retrying (Kotlin reads the two flags from the class).
    struct Classified: Hashable, Sendable {
        let failureClass: FailureClass
        let reason: Reason
        let inlineRetry: Bool
        let schedulerRetry: Bool
    }

    /// Classifies `input`, or returns nil when it is not a failure (a 2xx
    /// the request accepts; 206 for `.range`). Throws
    /// ``CantinoError/invalidArgument(_:)`` for a native code outside 1...5
    /// (the typed input cannot be malformed otherwise).
    static func classify(_ input: Input) throws -> Classified? {
        var call = NativeCall()
        let status = try call.check(cantino_classify_failure(input.json.canonicalString, &call.result, &call.error))
        if status == 1 { return nil }
        let wire = try Wire.decode(WireClassified.self, call.resultString())
        guard let failureClass = FailureClass(rawValue: wire.failureClass), let reason = Reason(rawValue: wire.reason) else {
            // Version skew: a class or reason this adapter does not know.
            throw Wire.malformed("failure class \(wire.failureClass) / reason \(wire.reason)")
        }
        return Classified(failureClass: failureClass, reason: reason, inlineRetry: wire.inlineRetry,
                          schedulerRetry: wire.schedulerRetry)
    }

    /// HTTP `status` answering a `context` request, or nil if accepted.
    static func http(_ status: Int, context: HTTPContext) throws -> Classified? {
        try classify(.http(status, context))
    }

    /// An I/O error on the network or in local storage.
    static func io(_ context: IOContext) throws -> Classified {
        try require(classify(.io(context)), "io \(context.rawValue)")
    }

    /// A native `error` from a `context` call.
    static func native(_ error: any Error, context: NativeContext) throws -> Classified {
        try native(code: code(of: error), context: context)
    }

    /// As ``native(_:context:)`` for the raw `CANTINO_ERROR_*` code.
    static func native(code: Int32, context: NativeContext) throws -> Classified {
        try require(classify(.native(code, context)), "native \(context.rawValue)")
    }

    private static func require(_ classified: Classified?, _ what: String) throws -> Classified {
        guard let classified else {
            throw CantinoStateError.internalError("Cantino internal error: \(what) was not classified as a failure")
        }
        return classified
    }

    /// The `CANTINO_ERROR_*` code of a native error (the category the
    /// adapter mapped it from).
    private static func code(of error: any Error) -> Int32 {
        switch error {
        case let error as CantinoError: error.code
        default: CANTINO_ERROR_INTERNAL
        }
    }
}

private struct WireClassified: Decodable {
    let failureClass: String
    let reason: String
    let inlineRetry: Bool
    let schedulerRetry: Bool

    enum CodingKeys: String, CodingKey {
        case failureClass = "class"
        case reason
        case inlineRetry = "inline_retry"
        case schedulerRetry = "scheduler_retry"
    }
}

// Typed Swift view of the SliceOSM protocol helpers in the Rust core
// (src/slice.rs, exposed through `cantino_slice_*`), mirroring the Kotlin
// adapter's internal `SliceProtocol` object. The core decides what to send
// and how to read the answers; this file only converts JSON. The HTTP (and
// on iOS the background URLSession) belongs to the area download lifecycle
// of a later slice, which builds on these helpers; keeping the protocol in
// Rust means both adapters send the same request bodies, use the same URL
// layout and read progress by the same rules.
//
// Internal, like Kotlin's: not app API. Platform-neutral (no networking).

import CCantino
import Foundation

enum SliceProtocol {
    /// POST `body` (JSON, verbatim, `application/json`) to `url`; the answer
    /// body is the job ID text.
    struct JobRequest: Hashable, Sendable {
        let url: String
        let body: String
    }

    /// A validated job: its ID and where to poll and download.
    struct Job: Hashable, Sendable {
        let id: String
        let statusUrl: String
        let downloadUrl: String
    }

    /// `fraction` (0...1) is nil until the server reports totals;
    /// `sizeBytes` nil while unknown; `timestamp` is the OSM replication
    /// timestamp of the snapshot (the data's age), ISO 8601, or nil.
    struct Progress: Hashable, Sendable {
        let complete: Bool
        let fraction: Double?
        let sizeBytes: Int64?
        let timestamp: String?
    }

    /// The job submission for `bbox`, named `name`. `base` nil is the public
    /// service (https://slice.openstreetmap.us/), else an http(s) base URL.
    /// Pure, any thread. Throws ``CantinoError/invalidArgument(_:)`` for an
    /// invalid bbox (wrong order, out of range, non-finite) or base URL.
    static func jobRequest(base: String?, bbox: Bbox, name: String) throws -> JobRequest {
        let bboxJSON = try bbox.json().canonicalString
        var call = NativeCall()
        _ = try call.check(cantino_slice_job_request(base, bboxJSON, name, &call.result, &call.error))
        let wire = try Wire.decode(WireJobRequest.self, call.resultString())
        return JobRequest(url: wire.url, body: wire.body)
    }

    /// Parses a submit response, or re-validates a persisted job ID. Pure,
    /// any thread. Throws ``CantinoError/invalidArgument(_:)`` unless
    /// `response` is a UUID (surrounding whitespace and quotes allowed), or
    /// for an invalid base URL.
    static func job(base: String?, response: String) throws -> Job {
        var call = NativeCall()
        _ = try call.check(cantino_slice_job(base, response, &call.result, &call.error))
        let wire = try Wire.decode(WireJob.self, call.resultString())
        return Job(id: wire.jobId, statusUrl: wire.statusUrl, downloadUrl: wire.downloadUrl)
    }

    /// Reads a job status document (the server's text, verbatim). Pure, any
    /// thread. Throws ``CantinoError/invalidArgument(_:)`` if it is not a
    /// status document (not JSON, no `Complete`).
    static func progress(status: String) throws -> Progress {
        var call = NativeCall()
        _ = try call.check(cantino_slice_progress(status, &call.result, &call.error))
        let wire = try Wire.decode(WireProgress.self, call.resultString())
        return Progress(complete: wire.complete, fraction: wire.fraction, sizeBytes: wire.sizeBytes,
                        timestamp: wire.timestamp)
    }
}

// Wire shapes (include/cantino.h, "SliceOSM protocol helpers").

private struct WireJobRequest: Decodable {
    let url: String
    let body: String
}

private struct WireJob: Decodable {
    let jobId: String
    let statusUrl: String
    let downloadUrl: String

    enum CodingKeys: String, CodingKey {
        case jobId = "job_id"
        case statusUrl = "status_url"
        case downloadUrl = "download_url"
    }
}

private struct WireProgress: Decodable {
    let complete: Bool
    // Optionals: the core writes null for unknown values.
    let fraction: Double?
    let sizeBytes: Int64?
    let timestamp: String?

    enum CodingKeys: String, CodingKey {
        case complete, fraction, timestamp
        case sizeBytes = "size_bytes"
    }
}

import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
@testable import Cantino

/// Minimal SliceOSM inside the test process, as a URLProtocol (the Kotlin
/// tests use MockWebServer): one job ID; the status reports half done on the
/// first poll and complete on the second; the file is the fixture PBF. Also
/// a basemap host: `/basemap.pmtiles` (plain download) and `/planet.pmtiles`
/// (HTTP range requests, 206 + Content-Range like a CDN). Knobs inject
/// failures. Each instance answers for its own host name, so tests running
/// in parallel do not share a server.
final class FakeSlice: @unchecked Sendable {
    static let job = "2637da98-20a1-428f-b6db-18ac2861b763"
    static let timestamp = "2026-10-03T20:30:01Z"

    let host = "slice-\(UUID().uuidString.lowercased()).test"
    var base: String { "http://\(host)/" }
    func url(_ path: String) -> String { "http://\(host)\(path)" }

    private let lock = NSLock()
    private var _file: Data
    private let planet: Data
    private var _basemapBody: Data
    private var _basemapCode = 200
    private var _ignoreRange = false
    private var _submitCode = 201
    private var _throttle = false
    private var _statusFailures = 0
    private var _submits = 0
    private var _polls = 0
    private var _downloads = 0
    private var _basemapRequests = 0
    private var _rangeHeaders: [String] = []
    private var _submitBodies: [String] = []

    init(pbf: Data, planet: Data) {
        _file = pbf
        self.planet = planet
        _basemapBody = planet
        Self.servers.withLock { $0[host] = self }
    }

    func close() {
        _ = Self.servers.withLock { $0.removeValue(forKey: host) }
    }

    var file: Data { get { lock.withLock { _file } } set { lock.withLock { _file = newValue } } }
    var basemapBody: Data { get { lock.withLock { _basemapBody } } set { lock.withLock { _basemapBody = newValue } } }
    var basemapCode: Int { get { lock.withLock { _basemapCode } } set { lock.withLock { _basemapCode = newValue } } }
    var ignoreRange: Bool { get { lock.withLock { _ignoreRange } } set { lock.withLock { _ignoreRange = newValue } } }
    var submitCode: Int { get { lock.withLock { _submitCode } } set { lock.withLock { _submitCode = newValue } } }
    /// Sends the PBF in small chunks with pauses (~4 s for the fixture).
    var throttle: Bool { get { lock.withLock { _throttle } } set { lock.withLock { _throttle = newValue } } }
    var statusFailures: Int { get { lock.withLock { _statusFailures } } set { lock.withLock { _statusFailures = newValue } } }
    var submits: Int { lock.withLock { _submits } }
    var polls: Int { lock.withLock { _polls } }
    var downloads: Int { lock.withLock { _downloads } }
    var basemapRequests: Int { lock.withLock { _basemapRequests } }
    var rangeHeaders: [String] { lock.withLock { _rangeHeaders } }
    var submitBodies: [String] { lock.withLock { _submitBodies } }

    /// A session configuration that routes this process's fake hosts here.
    static var sessionConfiguration: URLSessionConfiguration {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [FakeSliceProtocol.self]
        return configuration
    }

    struct Response {
        var code: Int
        var headers: [String: String] = [:]
        var body: Data
        var throttled = false
    }

    func respond(_ request: URLRequest) -> Response {
        let path = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)!.path // keeps a trailing slash
        let method = request.httpMethod ?? "GET"
        return lock.withLock { () -> Response in
            switch path {
            case "/api/" where method == "POST":
                _submits += 1
                _submitBodies.append(String(decoding: Self.body(of: request), as: UTF8.self))
                if _submitCode != 201 { return Response(code: _submitCode, body: Data("bad request".utf8)) }
                return Response(code: 201, body: Data("\(Self.job)\n".utf8))
            case "/api/\(Self.job)":
                if _statusFailures > 0 {
                    _statusFailures -= 1
                    return Response(code: 500, body: Data("busy".utf8))
                }
                _polls += 1
                let complete = _polls >= 2
                let status: [String: Any] = [
                    "Timestamp": Self.timestamp, "NodesTotal": 4, "NodesProg": complete ? 4 : 2,
                    "ElemsTotal": 8, "ElemsProg": complete ? 8 : 4, "SizeBytes": _file.count, "Complete": complete,
                ]
                return Response(code: 200, body: try! JSONSerialization.data(withJSONObject: status))
            case "/files/\(Self.job).osm.pbf":
                _downloads += 1
                return Response(code: 200, headers: ["Content-Length": "\(_file.count)"], body: _file,
                                throttled: _throttle)
            case "/basemap.pmtiles":
                _basemapRequests += 1
                if _basemapCode != 200 { return Response(code: _basemapCode, body: Data("no such file".utf8)) }
                return Response(code: 200, headers: ["Content-Length": "\(_basemapBody.count)"], body: _basemapBody)
            case "/planet.pmtiles":
                _basemapRequests += 1
                let range = request.value(forHTTPHeaderField: "Range")
                if let range { _rangeHeaders.append(range) }
                guard !_ignoreRange, let range, let (first, last) = Self.parseRange(range) else {
                    return Response(code: 200, headers: ["Content-Length": "\(planet.count)"], body: planet)
                }
                let end = min(last, planet.count - 1)
                return Response(code: 206, headers: ["Content-Range": "bytes \(first)-\(end)/\(planet.count)",
                                                     "Content-Length": "\(end - first + 1)"],
                                body: planet.subdata(in: first..<(end + 1)))
            default:
                return Response(code: 404, body: Data())
            }
        }
    }

    private static func parseRange(_ header: String) -> (Int, Int)? {
        guard header.hasPrefix("bytes=") else { return nil }
        let parts = header.dropFirst(6).split(separator: "-")
        guard parts.count == 2, let first = Int(parts[0]), let last = Int(parts[1]) else { return nil }
        return (first, last)
    }

    /// URLProtocol sees a POST body as a stream.
    private static func body(of request: URLRequest) -> Data {
        if let body = request.httpBody { return body }
        guard let stream = request.httpBodyStream else { return Data() }
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 4096)
        while stream.hasBytesAvailable {
            let read = stream.read(&buffer, maxLength: buffer.count)
            if read <= 0 { break }
            data.append(buffer, count: read)
        }
        return data
    }

    static let servers = Locked<[String: FakeSlice]>([:])
}

/// A value behind a lock.
final class Locked<Value>: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Value

    init(_ value: Value) { self.value = value }

    func withLock<T>(_ body: (inout Value) throws -> T) rethrows -> T {
        try lock.withLock { try body(&value) }
    }
}

final class FakeSliceProtocol: URLProtocol, @unchecked Sendable {
    private let stopped = Locked(false)

    override class func canInit(with request: URLRequest) -> Bool {
        guard let host = request.url?.host else { return false }
        return FakeSlice.servers.withLock { $0[host] != nil }
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let host = request.url?.host, let server = FakeSlice.servers.withLock({ $0[host] }) else {
            client?.urlProtocol(self, didFailWithError: URLError(.cannotConnectToHost))
            return
        }
        let answer = server.respond(request)
        let response = HTTPURLResponse(url: request.url!, statusCode: answer.code, httpVersion: "HTTP/1.1",
                                       headerFields: answer.headers)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        guard answer.throttled else {
            client?.urlProtocol(self, didLoad: answer.body)
            client?.urlProtocolDidFinishLoading(self)
            return
        }
        // Throttled: 16 chunks, 250 ms apart, stoppable (cancellation).
        DispatchQueue.global().async { [self] in
            let size = max(1, answer.body.count / 16)
            var offset = 0
            while offset < answer.body.count {
                if stopped.withLock({ $0 }) { return }
                let end = min(answer.body.count, offset + size)
                client?.urlProtocol(self, didLoad: answer.body.subdata(in: offset..<end))
                offset = end
                Thread.sleep(forTimeInterval: 0.25)
            }
            if !stopped.withLock({ $0 }) { client?.urlProtocolDidFinishLoading(self) }
        }
    }

    override func stopLoading() {
        stopped.withLock { $0 = true }
    }
}

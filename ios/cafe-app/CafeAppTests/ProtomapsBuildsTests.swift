import Foundation
import Testing
@testable import CafeApp

/// Port of the Android café app's ProtomapsBuildsTest.kt. A URLProtocol
/// stands in for MockWebServer: it answers with queued status codes and
/// records the requests.
@Suite(.serialized)
struct ProtomapsBuildsTests {
    private static let base = "https://builds.test/"

    private func session(_ statuses: [Int]) -> URLSession {
        StubProtocol.reset(statuses)
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [StubProtocol.self]
        return URLSession(configuration: configuration)
    }

    private func day(_ year: Int, _ month: Int, _ day: Int) throws -> Date {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = try #require(TimeZone(identifier: "UTC"))
        return try #require(calendar.date(from: DateComponents(year: year, month: month, day: day, hour: 12)))
    }

    @Test func newestExistingBuildWins() async throws {
        let url = try await ProtomapsBuilds.latestUrl(today: try day(2026, 10, 3), baseUrl: Self.base,
                                                       session: session([404, 404, 200]))
        #expect(url == Self.base + "20261001.pmtiles")
        let requests = StubProtocol.requests
        #expect(requests.map(\.method) == ["HEAD", "HEAD", "HEAD"])
        #expect(requests.map(\.path) == ["/20261003.pmtiles", "/20261002.pmtiles", "/20261001.pmtiles"])
    }

    @Test(arguments: [404, 503])
    func missingBuildsAndServerFailuresAreReported(status: Int) async throws {
        await #expect(throws: ProtomapsBuilds.LookupError.self) {
            _ = try await ProtomapsBuilds.latestUrl(maxAgeDays: 0, baseUrl: Self.base, session: session([status]))
        }
        #expect(StubProtocol.requests.count == 1)
    }
}

/// Answers each request with the next queued status code (empty body).
final class StubProtocol: URLProtocol, @unchecked Sendable {
    struct Request: Sendable { let method: String; let path: String }

    private static let lock = NSLock()
    nonisolated(unsafe) private static var statuses: [Int] = []
    nonisolated(unsafe) private static var recorded: [Request] = []

    static func reset(_ queue: [Int]) {
        lock.withLock {
            statuses = queue
            recorded = []
        }
    }

    static var requests: [Request] { lock.withLock { recorded } }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let status: Int = Self.lock.withLock {
            Self.recorded.append(Request(method: request.httpMethod ?? "GET", path: request.url?.path ?? ""))
            return Self.statuses.isEmpty ? 500 : Self.statuses.removeFirst()
        }
        let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: [:])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data())
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

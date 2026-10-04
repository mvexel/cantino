import Foundation

/// Demo-only discovery of a Protomaps daily planet build (port of the Android
/// café app's ProtomapsBuilds.kt). Production apps should host their own
/// PMTiles archive. Example code, deliberately not part of the SDK.
enum ProtomapsBuilds {
    struct LookupError: Error, CustomStringConvertible {
        let description: String
    }

    /// The newest `https://build.protomaps.com/YYYYMMDD.pmtiles` that answers
    /// a HEAD request with 2xx, trying today (UTC) and up to `maxAgeDays`
    /// days back. A 404 moves on to the previous day; any other status or a
    /// transport error stops with an error.
    static func latestUrl(
        today: Date = Date(),
        maxAgeDays: Int = 7,
        baseUrl: String = "https://build.protomaps.com/",
        session: URLSession = .shared
    ) async throws -> String {
        precondition(maxAgeDays >= 0)
        var base = baseUrl
        while base.hasSuffix("/") { base.removeLast() }
        base += "/"
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        for age in 0...maxAgeDays {
            try Task.checkCancellation()
            let day = calendar.date(byAdding: .day, value: -age, to: today)!
            let parts = calendar.dateComponents([.year, .month, .day], from: day)
            let url = base + String(format: "%04d%02d%02d.pmtiles", parts.year!, parts.month!, parts.day!)
            var request = URLRequest(url: URL(string: url)!, timeoutInterval: 15)
            request.httpMethod = "HEAD"
            let (_, response) = try await session.data(for: request)
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            switch status {
            case 200...299: return url
            case 404: continue
            default: throw LookupError(description: "cannot check \(url): HTTP \(status)")
            }
        }
        throw LookupError(description: "no Protomaps build found in the last \(maxAgeDays) days at \(base)")
    }
}

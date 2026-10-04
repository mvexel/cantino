import Cantino
import Foundation

/*
 * The query bar: a tiny text syntax over Cantino's TagFilter, and the paging
 * that runs it. Port of android/inspector-app QueryBar.kt (same syntax, same
 * error texts).
 *
 *   amenity=cafe        .equals   exact, case-sensitive value
 *   shop=*              .exists   any value
 *   !opening_hours      .notExists
 *   name="Caffe Ibis"   a quoted value may contain spaces; "*" quoted is the literal value *
 *
 * Terms are separated by spaces and ANDed. There is no OR and no pattern
 * matching (Cantino has neither). A query of only `!k` terms needs a bbox
 * ("this view only"); without one Cantino rejects it, and the app shows
 * Cantino's message verbatim.
 */

struct QuerySyntaxError: Error, CustomStringConvertible, Equatable {
    let message: String
    var description: String { message }
}

enum QueryBar {
    /// Parses the bar's text into ANDed filters. Empty text is no filter at all.
    static func parse(_ input: String) throws(QuerySyntaxError) -> [TagFilter] {
        var filters: [TagFilter] = []
        for token in try tokenize(input) { filters.append(try term(token)) }
        return filters
    }

    /// The canonical text of `filters` (what the bar shows after a preset).
    static func format(_ filters: [TagFilter]) -> String {
        filters.map { filter in
            switch filter {
            case .equals(let key, let value): "\(key)=\(quoteIfNeeded(value))"
            case .exists(let key): "\(key)=*"
            case .notExists(let key): "!\(key)"
            }
        }.joined(separator: " ")
    }

    private struct Token {
        let text: String
        let quoted: Bool
    }

    private static func tokenize(_ input: String) throws(QuerySyntaxError) -> [Token] {
        var tokens: [Token] = []
        var current = ""
        var quoted = false
        var inQuotes = false
        var started = false
        for c in input {
            if c == "\"" {
                inQuotes.toggle(); quoted = true; started = true
            } else if c.isWhitespace && !inQuotes {
                if started { tokens.append(Token(text: current, quoted: quoted)) }
                current = ""; quoted = false; started = false
            } else {
                current.append(c); started = true
            }
        }
        if inQuotes { throw QuerySyntaxError(message: "Unclosed quote.") }
        if started { tokens.append(Token(text: current, quoted: quoted)) }
        return tokens
    }

    private static func term(_ token: Token) throws(QuerySyntaxError) -> TagFilter {
        let text = token.text
        if text.hasPrefix("!") {
            let key = String(text.dropFirst())
            if key.isEmpty { throw QuerySyntaxError(message: "\"!\" needs a key, as in !opening_hours.") }
            if let eq = key.firstIndex(of: "=") {
                throw QuerySyntaxError(message: "\"\(text)\": a missing-tag term takes no value; write !\(key[..<eq]).")
            }
            return .notExists(key)
        }
        guard let eq = text.firstIndex(of: "=") else {
            throw QuerySyntaxError(message: "\"\(text)\" needs a value: use \(text)=value, \(text)=* for any value, or !\(text) for missing.")
        }
        let key = String(text[..<eq])
        let value = String(text[text.index(after: eq)...])
        if key.isEmpty { throw QuerySyntaxError(message: "\"\(text)\" has no key before \"=\".") }
        if value.isEmpty && !token.quoted {
            throw QuerySyntaxError(message: "\"\(text)\" has no value: use \(key)=* for any value, or \(key)=\"\" for an empty one.")
        }
        return value == "*" && !token.quoted ? .exists(key) : .equals(key, value)
    }

    private static func quoteIfNeeded(_ value: String) -> String {
        value.isEmpty || value == "*" || value.contains(where: \.isWhitespace) ? "\"\(value)\"" : value
    }
}

/// A validator-style preset: a query with a reason.
struct Check: Hashable, Sendable, Identifiable {
    let title: String
    let query: String
    let why: String
    var id: String { query }
}

enum Checks {
    /// The query bar's presets (the "Checks" menu). Each is a plain query the user can edit.
    static let all = [
        Check(title: "Amenities without opening_hours", query: "amenity=* !opening_hours", why: "Shops, cafés and services whose hours are not mapped."),
        Check(title: "Crossings without crossing=*", query: "highway=crossing !crossing", why: "Crossing nodes that do not say whether they are marked, signalled or unmarked."),
        Check(title: "Sidewalks without surface", query: "footway=sidewalk !surface", why: "Sidewalks mapped as separate ways, without a surface."),
        Check(title: "Kerbs without kerb=*", query: "barrier=kerb !kerb", why: "Kerbs that do not say whether they are lowered, flush or raised."),
        Check(title: "Steps without handrail", query: "highway=steps !handrail", why: "Steps without handrail information."),
        Check(title: "Benches without backrest", query: "amenity=bench !backrest", why: "Benches without backrest information."),
    ]

    /// The acceptance queries (docs/guide/inspector-app.md): counted over the
    /// whole area by the debug `-counts` launch option, on both platforms.
    static let acceptance = [
        "amenity=cafe", "amenity=restaurant", "amenity=bench", "amenity=drinking_water", "amenity=toilets",
        "amenity=bicycle_parking", "amenity=* !opening_hours",
        "highway=footway footway=sidewalk", "footway=crossing", "highway=crossing crossing=*",
        "highway=crossing !crossing", "barrier=kerb kerb=*", "highway=steps", "highway=pedestrian",
    ]
}

/// Objects per kind, for counts.
struct KindCounts: Hashable, Sendable, CustomStringConvertible {
    var nodes: Int64 = 0
    var ways: Int64 = 0
    var relations: Int64 = 0

    var total: Int64 { nodes + ways + relations }

    mutating func add(_ object: OsmObject) {
        switch object.id.kind {
        case .node: nodes += 1
        case .way: ways += 1
        case .relation: relations += 1
        }
    }

    var description: String { "\(total) (\(nodes) n, \(ways) w, \(relations) r)" }
}

/// Runs query-bar filters on the store's thread.
enum Queries {
    /// Results per page in the list and on the map ("load more" fetches the next).
    static let page = 500
    private static let countPage = 10_000

    /// One page after `after` (keyset pagination: nodes, ways, relations, each by ID).
    static func page(_ store: OsmStore, _ filters: [TagFilter], bbox: Bbox?, after: OsmId?, limit: Int = page) throws -> [OsmObject] {
        try store.query(Query(tags: filters, bbox: bbox, after: after, limit: limit))
    }

    /// Every match, counted per kind (pages of 10 000; no geometry is read).
    static func count(_ store: OsmStore, _ filters: [TagFilter], bbox: Bbox?) throws -> KindCounts {
        var counts = KindCounts()
        var after: OsmId?
        while true {
            let results = try page(store, filters, bbox: bbox, after: after, limit: countPage)
            results.forEach { counts.add($0) }
            guard results.count == countPage, let last = results.last else { return counts }
            after = last.id
        }
    }
}

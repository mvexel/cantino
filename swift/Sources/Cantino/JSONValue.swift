// A tiny JSON tree with a deterministic serialiser. Two jobs:
//
// 1. Requests to the core (queries, ID batches, import options) are built
//    as JSONValue and serialised here rather than with Foundation's
//    JSONEncoder, so the bytes do not depend on Foundation's platform
//    differences (Darwin vs swift-corelibs/swift-foundation on Linux:
//    escaping of "/", float formatting, key order).
// 2. Canonical form for cross-adapter parity (tests/parity, a later slice):
//    every value the API returns can be rendered back into the core's wire
//    shape (src/model.rs serde layout) with sorted keys, and compared
//    byte-for-byte against a corpus produced by the core or by the Kotlin
//    adapter. See Canonical.swift.
//
// Responses from the core are decoded with Codable (Wire.swift); JSONValue
// is Decodable too so a parity runner can load expected.json into it.

import Foundation

/// A JSON value. Integers and doubles are kept apart so OSM IDs (Int64) and
/// e7 coordinates never pass through a Double.
enum JSONValue: Equatable, Sendable {
    case null
    case bool(Bool)
    case int(Int64)
    case double(Double)
    case string(String)
    case array([JSONValue])
    case object([String: JSONValue])

    /// Compact JSON with object keys sorted by their UTF-8 bytes, so equal
    /// values always serialise to equal strings (the canonical form).
    var canonicalString: String {
        var out = ""
        write(into: &out)
        return out
    }

    private func write(into out: inout String) {
        switch self {
        case .null: out += "null"
        case .bool(let b): out += b ? "true" : "false"
        case .int(let i): out += String(i)
        case .double(let d):
            // Callers validate finiteness first (JSON has no NaN/Infinity);
            // this is a last line of defence that the core then rejects as
            // malformed input (InvalidArgument) instead of misreading it.
            guard d.isFinite else { out += "null"; return }
            // Integral doubles print as "1.0"-style so they stay floats for
            // serde; otherwise Swift's shortest round-trip representation.
            // Swift may print an exponent ("1e-05"), which is valid JSON.
            out += d == d.rounded() && abs(d) < 1e15 ? String(format: "%.1f", d) : "\(d)"
        case .string(let s): JSONValue.writeString(s, into: &out)
        case .array(let items):
            out += "["
            for (index, item) in items.enumerated() {
                if index > 0 { out += "," }
                item.write(into: &out)
            }
            out += "]"
        case .object(let members):
            out += "{"
            // Byte order, not locale order: identical on every platform.
            let keys = members.keys.sorted { Array($0.utf8).lexicographicallyPrecedes(Array($1.utf8)) }
            for (index, key) in keys.enumerated() {
                if index > 0 { out += "," }
                JSONValue.writeString(key, into: &out)
                out += ":"
                members[key]!.write(into: &out)
            }
            out += "}"
        }
    }

    /// RFC 8259 string escaping: quote, backslash and control characters;
    /// everything else (including non-ASCII such as "é") goes out as UTF-8,
    /// which is what the core expects.
    private static func writeString(_ s: String, into out: inout String) {
        out += "\""
        for scalar in s.unicodeScalars {
            switch scalar {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            case _ where scalar.value < 0x20:
                out += String(format: "\\u%04x", scalar.value)
            default: out.unicodeScalars.append(scalar)
            }
        }
        out += "\""
    }
}

extension JSONValue: Decodable {
    init(from decoder: any Decoder) throws {
        let container = try decoder.singleValueContainer()
        // Order matters: Bool before numbers, Int64 before Double, so that
        // integers in the corpus stay integers.
        if container.decodeNil() {
            self = .null
        } else if let b = try? container.decode(Bool.self) {
            self = .bool(b)
        } else if let i = try? container.decode(Int64.self) {
            self = .int(i)
        } else if let d = try? container.decode(Double.self) {
            self = .double(d)
        } else if let s = try? container.decode(String.self) {
            self = .string(s)
        } else if let a = try? container.decode([JSONValue].self) {
            self = .array(a)
        } else {
            self = .object(try container.decode([String: JSONValue].self))
        }
    }

    /// Parses JSON text (for example a parity corpus file) into a tree.
    static func parse(_ text: String) throws -> JSONValue {
        try JSONDecoder().decode(JSONValue.self, from: Data(text.utf8))
    }
}

// A tiny JSON tree with a deterministic serialiser. Two jobs:
//
// 1. Requests to the core (queries, ID batches, import options, bboxes) are
//    built as JSONValue and serialised here rather than with Foundation's
//    JSONEncoder, so the bytes do not depend on Foundation's platform
//    differences (Darwin vs swift-foundation on Linux: escaping of "/",
//    float formatting, key order).
// 2. The canonical form of the cross-platform parity corpus
//    (tests/parity/README.md, "Canonical form"; the README is the
//    contract): every value the API returns is rendered back into the C
//    ABI's JSON shape (Canonical.swift) and written here, so the Swift
//    runner's output can be compared byte for byte with expected.json,
//    which the Rust runner writes with serde_json.
//
// The writer follows the README rules exactly: no whitespace; object keys
// sorted by Unicode code point (= UTF-8 byte order; NOT UTF-16 code units,
// which would put U+1F600 before U+FF5A); strings escape only `"`, `\` and
// U+0000..U+001F (`\b \t \n \f \r` short forms, otherwise `\u00xx`
// lowercase), so `/` and non-ASCII stay raw; integers plain; floats as the
// shortest round-trip decimal, always with a fraction or exponent, laid out
// like serde_json (see `formatDouble`).
//
// Responses from the core are decoded with Codable (Wire.swift); JSONValue
// is Decodable too so the parity runner can load calls.json into it.

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

    /// Compact JSON with object keys sorted by Unicode code point (their
    /// UTF-8 bytes), so equal values always serialise to equal strings: the
    /// parity corpus's canonical form (see the file comment).
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
            out += JSONValue.formatDouble(d)
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

    /// Canonical string escaping (README rule 3): quote, backslash and
    /// U+0000..U+001F only, with the short forms serde_json uses; everything
    /// else (including `/` and non-ASCII such as "é") goes out raw as UTF-8,
    /// which is also what the core expects in requests.
    private static func writeString(_ s: String, into out: inout String) {
        out += "\""
        for scalar in s.unicodeScalars {
            switch scalar.value {
            case 0x22: out += "\\\""
            case 0x5C: out += "\\\\"
            case 0x08: out += "\\b"
            case 0x09: out += "\\t"
            case 0x0A: out += "\\n"
            case 0x0C: out += "\\f"
            case 0x0D: out += "\\r"
            case 0x00..<0x20:
                let hex = "0123456789abcdef".unicodeScalars.map { $0 }
                out += "\\u00"
                out.unicodeScalars.append(hex[Int(scalar.value >> 4)])
                out.unicodeScalars.append(hex[Int(scalar.value & 0xF)])
            default: out.unicodeScalars.append(scalar)
            }
        }
        out += "\""
    }

    /// A finite double as the shortest decimal that round-trips to the same
    /// value, laid out exactly like serde_json 1.0.151 (which writes the
    /// corpus; its float formatter is zmij, laid out like ryu but with an
    /// explicit `+` on positive exponents): `1.0`, `0.25`, `-112.09`,
    /// `12.0`, `0.00001`, `1e+16`, `1.5e-7`. Plain decimal while the decimal
    /// point falls within 16 digits left / 4 zeros right (1e-5 <= |v| <
    /// 1e16), exponent form otherwise. Integral values keep `.0`, so a float
    /// field stays a float. Pinned by CanonicalTests against serde_json's
    /// actual output.
    ///
    /// The digits come from Swift's `description`, which is the shortest
    /// round-trip representation on every platform (Swift's own
    /// SwiftDtoa, not the C library); only its layout differs ("1e-05",
    /// other exponent thresholds), so the digits and the decimal exponent
    /// are extracted and laid out again here.
    static func formatDouble(_ value: Double) -> String {
        var text = Substring(value.description) // e.g. "-1.5e-07", "12.0", "0.25"
        var sign = ""
        if text.hasPrefix("-") {
            sign = "-"
            text = text.dropFirst()
        }
        var exponent = 0
        if let e = text.firstIndex(where: { $0 == "e" || $0 == "E" }) {
            exponent = Int(text[text.index(after: e)...])! // "+16" and "-07" parse
            text = text[..<e]
        }
        let parts = text.split(separator: ".", omittingEmptySubsequences: false)
        let fraction = parts.count > 1 ? parts[1] : ""
        // value = digits * 10^k
        var digits = Array((parts[0] + fraction).drop(while: { $0 == "0" }))
        var k = exponent - fraction.count
        while digits.last == "0" {
            digits.removeLast()
            k += 1
        }
        if digits.isEmpty { return sign + "0.0" }
        let length = digits.count
        let point = length + k // position of the decimal point in `digits`
        let body: String
        if k >= 0, point <= 16 {
            body = String(digits) + String(repeating: "0", count: k) + ".0"
        } else if point > 0, point <= 16 {
            body = String(digits[..<point]) + "." + String(digits[point...])
        } else if point > -5, point <= 0 {
            body = "0." + String(repeating: "0", count: -point) + String(digits)
        } else if length == 1 {
            body = String(digits) + "e" + exponentText(point - 1)
        } else {
            body = String(digits[0]) + "." + String(digits[1...]) + "e" + exponentText(point - 1)
        }
        return sign + body
    }

    private static func exponentText(_ exponent: Int) -> String {
        exponent < 0 ? String(exponent) : "+" + String(exponent)
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

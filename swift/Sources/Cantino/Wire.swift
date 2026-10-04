// Decoding the core's JSON responses (src/model.rs serde layout) into the
// public models with Codable. These wire structs are private to the module
// and mirror the Rust structs field for field:
//
//   Object  {"type":"node","id":1,"coordinate":{"lat_e7","lon_e7"},
//            "location_version":3,"tags":{..},"metadata":{..}|null}
//           {"type":"way","id":1,"nodes":[1,2,1],"tags":{..},"metadata":..}
//           {"type":"relation","id":1,"members":[{"id":{"type","id"},"role"}],..}
//   Report  {"counts":{"nodes","ways","relations"},"database_bytes"}
//
// Foundation note: JSONDecoder behaves the same on Darwin and on Linux
// (swift-foundation) for this subset: integers, strings, nested objects,
// nulls. No dates, no floats are decoded (coordinates are e7 integers), and
// key decoding strategies are not used (explicit CodingKeys instead), so the
// platform differences in those areas do not matter.
//
// A response that fails to decode is a bug (core and adapter disagree), so
// it surfaces as CantinoStateError.internalError, never as a domain error.

import Foundation

private struct WireMetadata: Decodable {
    let version: Int64
    let timestamp: Int64
    let changeset: Int64
    let uid: Int64
    let user: String

    var model: ObjectMetadata {
        ObjectMetadata(version: version, timestampSeconds: timestamp, changeset: changeset, uid: uid, user: user)
    }
}

struct WireCoordinate: Decodable {
    let latE7: Int32
    let lonE7: Int32

    enum CodingKeys: String, CodingKey {
        case latE7 = "lat_e7"
        case lonE7 = "lon_e7"
    }

    var model: Coordinate { Coordinate(latE7: latE7, lonE7: lonE7) }
}

private struct WireId: Decodable {
    let type: String
    let id: Int64

    func model() throws -> OsmId {
        guard let kind = OsmKind(wire: type) else { throw Wire.malformed("unknown object type \(type)") }
        return OsmId(kind, id)
    }
}

private struct WireMember: Decodable {
    let id: WireId
    let role: String
}

/// One object of any kind; kind-specific fields are optional here and
/// required per kind in `model()`.
struct WireObject: Decodable {
    private let type: String
    private let id: Int64
    private let tags: [String: String]
    private let metadata: WireMetadata?
    private let coordinate: WireCoordinate?
    private let locationVersion: Int32?
    private let nodes: [Int64]?
    private let members: [WireMember]?

    private enum CodingKeys: String, CodingKey {
        case type, id, tags, metadata, coordinate, nodes, members
        case locationVersion = "location_version"
    }

    func model() throws -> OsmObject {
        guard let kind = OsmKind(wire: type) else { throw Wire.malformed("unknown object type \(type)") }
        let osmId = OsmId(kind, id)
        switch kind {
        case .node:
            guard let coordinate, let locationVersion else { throw Wire.malformed("node without coordinate") }
            return .node(.init(id: osmId, latE7: coordinate.latE7, lonE7: coordinate.lonE7,
                               locationVersion: locationVersion, tags: tags, metadata: metadata?.model))
        case .way:
            guard let nodes else { throw Wire.malformed("way without nodes") }
            return .way(.init(id: osmId, nodeIds: nodes, tags: tags, metadata: metadata?.model))
        case .relation:
            guard let members else { throw Wire.malformed("relation without members") }
            return .relation(.init(id: osmId, members: try members.map { .init(id: try $0.id.model(), role: $0.role) },
                                   tags: tags, metadata: metadata?.model))
        }
    }
}

private struct WireCounts: Decodable {
    let nodes: Int64
    let ways: Int64
    let relations: Int64
}

struct WireReport: Decodable {
    private let counts: WireCounts
    private let databaseBytes: Int64
    private let profile: WireProfile?

    private enum CodingKeys: String, CodingKey {
        case counts, profile
        case databaseBytes = "database_bytes"
    }

    var model: ImportReport {
        ImportReport(counts: ObjectCounts(nodes: counts.nodes, ways: counts.ways, relations: counts.relations),
                     databaseBytes: databaseBytes, profile: profile?.model)
    }
}

/// `{"keep":[{"kinds":"nwr","key":"amenity","values":["cafe"]}]}`.
struct WireProfile: Decodable {
    struct Rule: Decodable {
        let kinds: String
        let key: String
        let values: [String]?
    }

    let keep: [Rule]

    var model: ImportProfile {
        ImportProfile(keep: keep.map { rule in
            KeepRule(kinds: Set(OsmKind.allCases.filter { rule.kinds.contains($0.wire.prefix(1)) }),
                     key: rule.key, values: rule.values)
        })
    }
}

enum Wire {
    static func malformed(_ what: String) -> CantinoStateError {
        .internalError("Cantino internal error: malformed core response: \(what)")
    }

    /// Decodes `T` from a core response, mapping any decoding failure to
    /// an internal error (see the file comment).
    static func decode<T: Decodable>(_ type: T.Type, _ json: String) throws -> T {
        do {
            return try JSONDecoder().decode(type, from: Data(json.utf8))
        } catch {
            throw malformed("\(error)")
        }
    }
}

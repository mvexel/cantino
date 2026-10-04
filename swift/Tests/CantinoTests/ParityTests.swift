import Foundation
import Testing
@testable import Cantino

// TODO(parity runner, separate slice): replay tests/parity/calls.json
// against the fixture area and compare with tests/parity/expected.json.
// The corpus is being built on branch ios/parity-corpus; its format is not
// fixed yet, so this is only the hook.
//
// Intended shape of the runner (fill in once the corpus lands):
//   1. Import the corpus' input (probably tests/fixtures/snapshot.osm) with
//      `Fixtures.importFixture(...)` and open an `OsmStore`.
//   2. For each call in calls.json, dispatch on its operation name to the
//      matching OsmStore method (get, get many, query, wayCoordinates,
//      representativePoint, importArea), decoding the arguments into the
//      public models (OsmId, Query, Bbox, TagFilter, ImportOptions).
//   3. Render the result with `.canonical` (Canonical.swift;
//      `canonicalWayCoordinates` for way coordinates; a thrown CantinoError
//      renders as {"error":"<CATEGORY>"}), and compare
//      `result.canonicalString` with `JSONValue.parse(expected).canonicalString`.
// The canonical shape is the core's wire JSON with sorted keys, so a corpus
// produced through the C ABI (Python ctypes) compares directly.

@Suite struct ParityTests {
    static let corpus = Fixtures.repoRoot.appendingPathComponent("tests/parity")

    @Test(.enabled(if: FileManager.default.fileExists(atPath: corpus.appendingPathComponent("calls.json").path),
                   "tests/parity/calls.json not present (corpus lands on ios/parity-corpus)"))
    func replayParityCorpus() throws {
        // Deliberately fails once the corpus exists, so the missing runner
        // cannot go unnoticed.
        Issue.record("tests/parity exists: implement the Swift parity runner (see TODO in ParityTests.swift)")
    }
}

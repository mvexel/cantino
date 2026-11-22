# Maintenance and roadmap

Cantino is maintained by [Martijn van Exel](https://github.com/mvexel).
The code is Apache-2.0; [builds are documented](building.md). Bugs and requests
belong in [GitHub issues](https://github.com/mvexel/cantino/issues).

## Mission and position

Cantino is the raw-data layer for offline, domain-specific mobile apps. It
provides a bounded, immutable OSM snapshot, semantic queries, managed
acquisition and an optional basemap. It complements a renderer such as
MapLibre; it does not compete with complete navigation apps or established
OSM editors.

The product thesis is narrow: a specialized app should not have to build PBF
import, mobile indexes, retries and crash-safe publication merely to inspect
source OSM objects offline. The thesis is technically demonstrated by the
café and Inspector examples, but not yet validated by an external consumer.

There are no compatibility promises before that validation. API, ABI and file
formats may change without shims; re-import development data after a format
change.

## 0.4.0 parity bar — passed

The 2026-10-03 feature freeze required:

1. Android and iOS to pass the shared parity corpus and their platform suites
   on the intended device/simulator matrix.
2. The iOS café demo to complete the same first-run download, map/query flow,
   cold airplane-mode launch and interrupted-download recovery as Android.
3. The public examples and guide to describe the behavior verified on both
   platforms.

Version 0.4.0 passed that bar. The recorded run covers Android, the iOS
simulator, native macOS tests and the café acceptance flow on a physical
iPhone; see [Android and iOS parity](platform-parity.md). The feature freeze
is lifted. SDK capabilities remain parity-first; product-validation apps may
iterate on one platform without changing the shared contract.

## Product validation pilot — app ready

The [Android field app](field-app.md) now implements the first validation
workflow from the Inspector mechanics:

- one district downloaded for fully offline use;
- accessibility missing-data checks plus raw object and version inspection;
- observations, proposed tags, notes, evidence and review state in a separate
  app-owned findings file;
- reviewed GeoJSON and CSV export for MapRoulette, iD or JOSM;
- no direct or unattended upload from the Cantino snapshot.

The implementation proves the local workflow, not the product thesis.
External validation still requires a real organization or mapping group, at
least 100 inspected entrances, crossings, kerbs, steps, ramps, lifts, toilets
or parking features, a full airplane-mode session and a reviewed contribution
back to OSM. Findings that remain in a private export still serve an agency
audit, but do not validate an OSM contribution workflow.

See [Field findings and editing apps](editing-apps.md) for the integration
boundary and provenance rules.

## Development rules

- Add a capability only for a demonstrated consumer problem. Do not infer a
  module roadmap from internal seams.
- Keep one artifact per platform until measured size or adoption justifies
  packaging variants.
- Prefer app-owned domain logic over tag interpretation in the core.
- Treat the public SliceOSM instance as best-effort. A production consumer
  must arrange capacity with OpenStreetMap US or self-host, and must control
  its basemap source.
- Keep examples executable and supported; they are contract consumers, not
  screenshots.

## Product boundaries

Cantino does not provide rendering, routing, geocoding, exact geometry,
overlapping-area reconciliation, incremental refresh or country-scale
storage. Editing, OAuth, upload, conflict presentation and automated-edit
policy remain app/editor responsibilities. Upload is not planned as a Cantino
capability.

Dated research notes are evidence and design history, not commitments or a
backlog.

## Path to a stable release

A stable release needs more than green internal tests:

1. Keep the cross-platform parity bar green as the API changes.
2. One external app completing a real offline workflow.
3. A production plan for extract and basemap capacity.
4. Feedback that the query and object model cover the consumer without
   application-specific core features.
5. An explicit compatibility policy based on that consumer experience.

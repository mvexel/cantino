# Cantino

Cantino — raw OpenStreetMap snapshots for offline, domain-specific mobile
apps. In 1502 Alberto Cantino smuggled a copy of Portugal's secret master map
out of Lisbon: a copy of the master map you carry away. "OpenStreetMap" is
only used descriptively, never as part of
the product name (OSMF trademark policy). Formerly `osm-framework`.

Headless native data SDK that gives Android and iOS apps offline access to raw
OpenStreetMap objects for one bounded area. A shared Rust core stores immutable
snapshots in SQLite; thin Kotlin and Swift adapters provide managed acquisition
and queries. An optional PMTiles companion supplies a basemap for a renderer
such as MapLibre. Background, evidence and build instructions: `HANDOFF.md`.

Mission (Martijn, 2026-11-22): be the semantic data layer for offline,
domain-specific mobile apps—not a complete map, navigation engine or editor.
The current implementation is snapshots, queries, area acquisition and
optional basemaps, shipped as one artifact per platform. App-owned field
findings can be handed to established OSM editors or a review queue; editing,
OAuth and upload are not Cantino capabilities. A capability is added only
when it removes substantial repeated work for a demonstrated consumer and
lands on both platforms. Product position and validation plan:
`docs/guide/roadmap.md`.

## Status

0.4.0 (2026-10-04): iOS and Android at parity ([parity](docs/guide/platform-parity.md));
the feature freeze of 2026-10-03 is lifted. There are no external consumers:
API, ABI and file formats may change without backward compatibility
machinery. Preserve examples, developer experience, runtime reliability and
parity: a feature lands on both platforms, with tests on both.

## In scope

- Import a SliceOSM PBF (or OSM XML) extract into a SQLite area file, publish atomically;
  optional tag-filtered import (import profiles, 0.3.0).
- Lookup by ID, index-backed tag queries, bbox spatial candidates, dependency reporting.
- Android (JNI + Kotlin, AAR) and iOS (xcframework + Swift) adapters.
- Area download lifecycle: submit, poll, download, cancel, staged import.
  One area per app. Refresh = full replace.
- Offline basemap: a **separate PMTiles basemap extract** for the same bbox,
  rendered with MapLibre Native, with or without OSM data (`downloadBasemap`).
  The framework does not generate vector tiles.
- Café reference app proving the airplane-mode acceptance scenario.

## Outside the current implementation (needs a scope change and a design note)

- Edits, sync, conflict handling. A possible future module, maintained
  outside this project for now (an editor owns its edit layer and uses
  Cantino as base data). It would need an effective-view read path in the
  core first.
- Upload to the OSM API: not planned, also for a future editing module.

## Out of scope (do not build without a scope change here)

- Offline routing.
- Overlapping areas, incremental `.osc` refresh, country-scale operation.
- On-device vector tile generation, multipolygon assembly for rendering.
- Global tag index.
- Café policy or opening-hours interpretation inside the core (app layer only).

## Plan

Done on branch `basemap-only` (P2 of the modular-SDK assessment):
basemap-only acquisition (`downloadBasemap`) in both adapters. Next: decided
by Martijn (a second demo or explorer mode is being proposed in
`docs/research/2026-10-04-second-demo-app-proposal.md`). Outward-facing steps
(pushing, tagging, publishing, GitHub settings) are done by Martijn; agents
prepare them and print the commands.

## Working rules

- Storage format is documented in `src/schema.rs` / `src/encoding.rs`; bump
  `FORMAT_VERSION` on any incompatible change.
- Rust: toolchain 1.99.0; `scripts/check.sh` (fmt, clippy -D warnings, tests,
  C ABI smoke) must pass.
- Liberal explanatory comments on ownership, transactions and invariants.
- `work/sqlite-prototype` is parked. Do not resume it.
- Spatial results are candidates; never advertise exact intersection.

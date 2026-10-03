# Cantino

Cantino — an offline OpenStreetMap SDK. (In 1502 Alberto Cantino smuggled a
copy of Portugal's secret master map out of Lisbon: a copy of the master map
you carry away.) "OpenStreetMap" is only used descriptively, never as part of
the product name (OSMF trademark policy). Formerly `osm-framework`.

Headless native SDK that gives Android and iOS apps offline access to raw
OpenStreetMap data for an app-defined area. Shared Rust core over a
SQLite store (decided 2026-10-03, replacing OSMExpress; see TODO.md); thin Kotlin and Swift adapters.
Background, evidence and build instructions: `HANDOFF.md`.

## In scope (0.1.0 released 2026-10-03; next: 0.2, see TODO.md "Next up")

- Import a SliceOSM PBF (or OSM XML) extract into a SQLite area file, publish atomically.
- Lookup by ID, index-backed tag queries, bbox spatial candidates, dependency reporting.
- Android (JNI + Kotlin, AAR) and iOS (xcframework + Swift) adapters.
- Area download lifecycle: submit, poll, download, cancel, staged import.
  One area per app. Refresh = full replace.
- Offline basemap: a **separate PMTiles basemap extract** for the same bbox,
  rendered with MapLibre Native. The framework does not generate vector tiles.
- Café reference app proving the airplane-mode acceptance scenario.

## Out of scope (do not build without a scope change here)

- Edits, upload, sync, conflict handling.
- Offline routing (planned for later: TODO.md §8; research first).
- Overlapping areas, incremental `.osc` refresh, country-scale operation.
- On-device vector tile generation, multipolygon assembly for rendering.
- Global tag index.
- Café policy or opening-hours interpretation inside the core (app layer only).

## Plan

`TODO.md` is the plan of record: "Status", then "Next up" in priority order
(each item has a done-criterion), then the dated history of decisions and
evidence. Outward-facing steps (pushing, tagging, publishing, GitHub settings)
are done by Martijn; agents prepare them and print the commands.

## Working rules

- Storage format is documented in `src/schema.rs` / `src/encoding.rs`; bump
  `FORMAT_VERSION` on any incompatible change.
- Rust: toolchain 1.99.0; `scripts/check.sh` (fmt, clippy -D warnings, tests,
  C ABI smoke) must pass.
- Liberal explanatory comments on ownership, transactions and invariants.
- `work/sqlite-prototype` is parked. Do not resume it.
- Spatial results are candidates; never advertise exact intersection.

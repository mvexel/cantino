# osm-framework

Headless native framework that gives Android and iOS apps offline access to raw
OpenStreetMap data for an app-defined area. Shared Rust core over an
OSMExpress (LMDB / Cap'n Proto / S2) backend; thin Kotlin and Swift adapters.
Background, evidence and build instructions: `HANDOFF.md`.

## In scope (current milestone: read-only)

- Import a SliceOSM PBF extract into an OSMExpress database, publish atomically.
- Lookup by ID, tag queries, bbox spatial candidates, dependency reporting.
- Android (JNI + Kotlin, AAR) and iOS (xcframework + Swift) adapters.
- Area download lifecycle: submit, poll, download, cancel, staged import.
  One area per app. Refresh = full replace.
- Offline basemap: a **separate PMTiles basemap extract** for the same bbox,
  rendered with MapLibre Native. The framework does not generate vector tiles.
- Café reference app proving the airplane-mode acceptance scenario.

## Out of scope (do not build without a scope change here)

- Edits, upload, sync, conflict handling.
- Overlapping areas, incremental `.osc` refresh, country-scale operation.
- On-device vector tile generation, multipolygon assembly for rendering.
- Global tag index.
- Café policy or opening-hours interpretation inside the core (app layer only).

## Plan

Phases, in order (tracked as an epic with sub-issues in the GitHub repo):

0. Scope + tracking (this file, repo, epic).
1. Android vertical slice: x86_64 emulator + arm64 phone, instrumented test does
   fixture import / get / query through JNI.
2. Phone measurement, go/no-go on OSMExpress: city extract size, db size,
   peak RSS, import time, query p50/p95. Go/no-go thresholds (city extract,
   mid-range phone, set 2026-10-03): import < 60 s, peak RSS < 300 MB,
   db < 3× PBF size, tag query p95 < 200 ms. A miss means revisiting the
   backend before building further on it.
3. iOS vertical slice: xcframework, Swift XCTest on simulator (parallel to 2).
4. Basemap decision record + 2h PMTiles spike.
5. Area download lifecycle (WorkManager / URLSession background).
6. Café reference app, airplane-mode acceptance.

## Working rules

- Preserve OSMExpress naming, style and storage format; fork changes stay
  limited to embedding/mobile needs (`vendor/OSMExpress`, branch `mobile-core`).
- Rust: rustfmt, `cargo clippy --all-targets -- -D warnings`, toolchain 1.99.0.
- Liberal explanatory comments on ownership, transactions and invariants.
- `work/sqlite-prototype` is parked. Do not resume it.
- Spatial results are candidates; never advertise exact intersection.

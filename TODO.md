# TODO

Verified 2026-10-03.

Plan of record. Scope and phase definitions: `CLAUDE.md`. Verify each `[x]`
against the repo before trusting it.

## 0. Scope and tracking
- [x] `CLAUDE.md` scope file
- [x] GitHub repo `mvexel/osm-framework`, pushed

## 1. Android vertical slice
- [x] Android SDK, platform tools, x86_64 emulator image installed locally
- [x] `build-android.sh` builds x86_64 alongside arm64-v8a
- [x] JNI adapter + Kotlin API (open/close/get/query/import, strings in, JSON out)
- [x] Gradle library module packaging all three `.so` per ABI
- [x] Strip native libraries in the AAR (SDK-managed NDK; arm64 29 MB → 7.8 MB, AAR 6.3 MB)
- [x] Publishable AAR (maven-publish to android/build/repo), typed Kotlin models instead of JSON strings
- [x] Instrumented test: fixture import, get, query, close on one worker thread
- [x] Test green on x86_64 emulator
- [x] Test green on physical arm64 phone

## 2. Phone measurement (go/no-go)
- [x] Pick city extract, fetch via SliceOSM — Salt Lake City bbox (-112.10, 40.70, -111.80, 40.85), 13 MB PBF
- [x] Measure on Pixel 8 (`scripts/bench-android.sh`, results in `docs/bench/2026-10-03-slc-pixel8.json`)
- [x] Compare against thresholds: import 5.0 s ✅; peak RSS 425 MB ❌; db 338 MB = 26× ❌; tag query p95 32.6 s ❌
- [x] **DECISION 2026-10-03: SQLite variant B replaces OSMExpress.** Pixel 8: 128.5 MB db, 5.7 s import, 177 MB peak, tag p95 1.1 ms (`docs/bench/2026-10-03-slc-sqlite-b-pixel8.json`). Variant A stopped unfinished.
- [x] GO 2026-10-03, done: 2h spike, pure-Rust SQLite store (osmpbf + rusqlite) on the same SLC bench. Kill criteria → stay on OSMExpress if db > 215 MB, Pixel import > 60 s, or tag query p95 > 200 ms
- Parked fallback (not now): server component that pre-bakes area databases for download, removing on-phone import. Revisit only if on-device import fails the budget.
- [x] Size threshold replaced: absolute budget < 150 MB for this extract (met: 128.5 MB)
- [x] Pre-baked download format proposal: `docs/research/2026-10-03-prebaked-dataset-format.md` (parked)

## 2b. Migrate core to SQLite (variant B)
- [x] Port `spike/sqlite-b` into `src/` behind the existing Rust API (Store, import_area, Query, get, dependencies, way_coordinates), atomic staged publish kept
- [x] Remove OSMExpress: submodule, `src/ffi.rs`, build.rs native linking, Docker native build; C ABI + JNI unchanged
- [x] Rust integration tests green (adapt OSMExpress-specific ones); Clippy; ABI smoke
- [x] Android instrumented tests + city bench green on Pixel 8 and emulator through the AAR
- [x] Update HANDOFF/CLAUDE.md; archive the OSMExpress fork notes

- [ ] Follow-ups from migration: in-app import 8.2 s vs 5.7 s plain binary (cause unknown); import peak memory grows with area (chunk writes); JNI+JSON ≈ 0.08 ms/object; decide whether to drop unused node_way/member_rel (−25 MB)

## 3. iOS vertical slice
- [ ] Inspect Mac (Xcode, toolchain)
- [ ] xcframework: device arm64 + simulator arm64
- [ ] Swift wrapper + XCTest on simulator (import, get, query)

## 4. Basemap
- [x] Decision record: separate PMTiles basemap for the area bbox (CLAUDE.md scope)
- [x] Obtain extract: `pmtiles extract` (go-pmtiles 1.31.2) from build.protomaps.com/20261002.pmtiles, SLC bbox z0–15 → 12 MB, 748 tiles (work/basemap, not committed)
- [x] Offline style: @protomaps/basemaps 5.7.2 light flavor, glyphs/sprites as asset:// (fonts 14 MB for 3 stacks — subset to Latin ranges later)
- [ ] Render offline in MapLibre Native Android 13.6.1 (sample app module) in airplane mode — waits for 2b migration (both touch android/)

## 5. Area download lifecycle
- [ ] Android: WorkManager submit/poll/download/cancel → staged import
- [ ] iOS: URLSession background equivalent
- [ ] Kill/restart mid-download recovers; failed replace keeps old area

## 6. Café reference app
- [ ] Find nearby cafés, filter outdoor seating + opening hours (unknown stays unknown)
- [ ] Inspect full tags and underlying objects
- [ ] Airplane-mode acceptance scenario passes end to end

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

## 3. iOS vertical slice — SKIPPED for now (2026-10-03, Martijn): finish Android end-to-end first
- [ ] Inspect Mac (Xcode, toolchain)
- [ ] xcframework: device arm64 + simulator arm64
- [ ] Swift wrapper + XCTest on simulator (import, get, query)

## 4. Basemap
- [x] Decision record: separate PMTiles basemap for the area bbox (CLAUDE.md scope)
- [x] Obtain extract: `pmtiles extract` (go-pmtiles 1.31.2) from build.protomaps.com/20261002.pmtiles, SLC bbox z0–15 → 12 MB, 748 tiles (work/basemap, not committed)
- [x] Offline style: @protomaps/basemaps 5.7.2 light flavor, glyphs/sprites as asset:// (fonts 14 MB for 3 stacks — subset to Latin ranges later)
- [x] Render offline in MapLibre Native Android 13.6.1 (`android/sample-app`, assets from `scripts/basemap-assets.sh`) on the Pixel 8 in airplane mode, no INTERNET permission; tiles, glyphs, sprites render, logcat clean (docs/screenshots/2026-10-03-basemap-z13.png, -z15.png). Fonts subset to 6 ranges: 1.7 MB. ACCESS_NETWORK_STATE is required (MapLibre's ConnectivityReceiver crashes without it). Debug APK 50 MB, both ABIs; arm64 share ~29 MB (libmaplibre 12.8, pmtiles 11.7, libosm_framework 3.9)

## 5. Area download lifecycle
Target onboarding flow (Martijn, 2026-10-03): get location → offer to download area (raw OSM + PMTiles basemap) → retrieve both → usable offline. An area is Ready only when both parts are published.
- [x] OSM data part: WorkManager + SliceOSM (`AreaManager`; protocol in Rust `src/slice.rs` behind `osm_framework_slice_*`). Live SliceOSM, downtown SLC 0.01°×0.006° on the Pixel 8: Ready in 3.6 s (submit 0.6 s, slicing 2.2 s, download 0.5 s, import 0.2 s; 15.9k nodes, 2.1 MB db)
- [x] Basemap part (opt-in per download, `BasemapSource` = None | Url | Extract): Rust extract core (c1bc109: golden-identical to go-pmtiles); C ABI `osm_framework_basemap_*` + JNI + `BasemapExtract` (plan/assemble on one native thread, 4 parallel range requests on IO, tile ranges streamed to files, 206 + exact Content-Range required, 200 = permanent failure); Url = plain download + PMTiles v3 validation (`osm_framework_basemap_info`); `ProtomapsBuilds.latestUrl()` for demos (production must mirror). Published at `filesDir/osm-areas/<areaId>.pmtiles`, `AreaInfo.basemapFile` / `pmtilesUrl`. Live on the Pixel 8, downtown SLC 0.01°×0.006°, Extract z0–15 from the 20261003 build: Ready in 6.3 s total, basemap 2.5 s (directories 1.3 s, tiles 1.2 s), 24 requests, 1.93 MB transferred, 1.01 MB file, 16 tiles
- [x] Area = raw + basemap, combined state: Ready only when both are published; data, basemap and sidecar publish together via a roll-forward commit journal (`AreaStorage.commit`); basemap or any failure before the commit point leaves the old area (all parts) intact; `AreaState.Basemap(phase, bytes, total)`
- [ ] Area bbox from location: ~10×10 km box centred on the user by default (Martijn 2026-10-03), a caller parameter, never a hard cap — size must not be a bottleneck. Not measured yet: a 10×10 km z15 extract (request count, MB, time) on the phone
- [x] Android: WorkManager submit/poll/download/cancel → staged import. `AreaManagerTest` against a fake SliceOSM (happy path; 500s while polling → inline + WorkManager retry resuming the same job; cancel mid-download; corrupt PBF; 400 on submit) green on Pixel 8 and emulator
- [ ] iOS: URLSession background equivalent
- [x] Failed or cancelled replace keeps old area (tested: cancel mid-download, corrupt PBF, cancel during import, basemap 404 after data, server ignoring Range, invalid Url basemap); cancel after the commit point reports Ready (tested)
- [ ] Kill/restart mid-download recovers: the job checkpoint resume is tested through a WorkManager retry, not an actual process kill; the commit journal roll-forward after a kill mid-commit is not tested by a real kill either
- [ ] Download follow-ups: `setForeground` (dataSync foreground service + notification) for big areas vs the 10 min run limit and Doze; byte-range resume of the PBF and of the basemap (a retried run re-downloads data and basemap; keeping a staged import across retries of the same work ID would save the re-import); basemap could be fetched while SliceOSM slices (now sequential); Rust import staging dirs orphaned by a kill mid-import (now inside the per-run staging dir, which the next run deletes). Fixed 2026-10-03: a cancel during the import no longer publishes

## 6. Café reference app
- [ ] first-run workflow: ask for location, download and process data for query and basemap
- [ ] Find nearby cafés, filter outdoor seating + opening hours (unknown stays unknown)
- [ ] Inspect full tags and underlying objects
- [ ] Airplane-mode acceptance scenario passes end to end

## 7. Developer documentation
TBD
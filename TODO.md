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
- [x] OSM data part: WorkManager + SliceOSM (`AreaManager`; protocol in Rust `src/slice.rs` behind `cantino_slice_*`). Live SliceOSM, downtown SLC 0.01°×0.006° on the Pixel 8: Ready in 3.6 s (submit 0.6 s, slicing 2.2 s, download 0.5 s, import 0.2 s; 15.9k nodes, 2.1 MB db)
- [x] Basemap part (opt-in per download, `BasemapSource` = None | Url | Extract): Rust extract core (c1bc109: golden-identical to go-pmtiles); C ABI `cantino_basemap_*` + JNI + `BasemapExtract` (plan/assemble on one native thread, 4 parallel range requests on IO, tile ranges streamed to files, 206 + exact Content-Range required, 200 = permanent failure); Url = plain download + PMTiles v3 validation (`cantino_basemap_info`); `ProtomapsBuilds.latestUrl()` for demos (production must mirror). Published at `filesDir/cantino-areas/<areaId>.pmtiles`, `AreaInfo.basemapFile` / `pmtilesUrl`. Live on the Pixel 8, downtown SLC 0.01°×0.006°, Extract z0–15 from the 20261003 build: Ready in 6.3 s total, basemap 2.5 s (directories 1.3 s, tiles 1.2 s), 24 requests, 1.93 MB transferred, 1.01 MB file, 16 tiles
- [x] Area = raw + basemap, combined state: Ready only when both are published; data, basemap and sidecar publish together via a roll-forward commit journal (`AreaStorage.commit`); basemap or any failure before the commit point leaves the old area (all parts) intact; `AreaState.Basemap(phase, bytes, total)`
- [x] Area bbox from location: ~10×10 km box centred on the user by default (Martijn 2026-10-03), a caller parameter, never a hard cap — size must not be a bottleneck. Done in the café app (`Geo.AREA_SIZE_KM`, `Geo.squareAround`). Measured 2026-10-03 on the Pixel 8, downtown SLC 10×10 km, live: Ready in 27.4 s (slicing 19.5 s, PBF 7.8 MB in 0.8 s, import 3.2 s → 75.6 MB db, 778k nodes; basemap Extract z0–15 2.4 s: 28 requests, 7.6 MB transferred, 6.5 MB file, 196 tiles). A refresh of the same area: 14.0 s. Far below WorkManager's 10 min limit, so no `setForeground` was needed at this size
- [x] Android: WorkManager submit/poll/download/cancel → staged import. `AreaManagerTest` against a fake SliceOSM (happy path; 500s while polling → inline + WorkManager retry resuming the same job; cancel mid-download; corrupt PBF; 400 on submit) green on Pixel 8 and emulator
- [ ] iOS: URLSession background equivalent
- [x] Failed or cancelled replace keeps old area (tested: cancel mid-download, corrupt PBF, cancel during import, basemap 404 after data, server ignoring Range, invalid Url basemap); cancel after the commit point reports Ready (tested)
- [ ] Kill/restart mid-download recovers: the job checkpoint resume is tested through a WorkManager retry, not an actual process kill; the commit journal roll-forward after a kill mid-commit is not tested by a real kill either
- [ ] Download follow-ups: `setForeground` (dataSync foreground service + notification) for big areas vs the 10 min run limit and Doze; byte-range resume of the PBF and of the basemap (a retried run re-downloads data and basemap; keeping a staged import across retries of the same work ID would save the re-import); basemap could be fetched while SliceOSM slices (now sequential); Rust import staging dirs orphaned by a kill mid-import (now inside the per-run staging dir, which the next run deletes). Fixed 2026-10-03: a cancel during the import no longer publishes

## 6. Café reference app
`android/cafe-app` (2026-10-03); see HANDOFF.md "Café reference app". Evidence: Pixel 8 screenshots `docs/screenshots/2026-10-03-cafe-*.png`.
- [x] first-run workflow: ask for location, download and process data for query and basemap — permission → LocationManager fix → offer dialog (10×10 km, one-line privacy note) → `AreaManager.download(…, BasemapSource.Extract(latest build, 15))` with per-phase progress → map. Fallbacks: permission denied / no fix → "lat,lon" field or SLC preset (emulator screenshot); failure → Retry (previous area kept); "Refresh area" re-downloads (verified: new snapshot, store reopened). Debug location override `--ef lat/--ef lon` for all automated runs
- [x] Find nearby cafés, filter outdoor seating + opening hours (unknown stays unknown) — 136 cafés (120 nodes, 16 ways) in the SLC area; Open/Closed/Unknown and Yes/No/Unknown filters with counts; nearby list by distance. App-side `opening_hours` evaluator, 14 JVM tests; on this area 104 of 106 present values evaluate, 2 → Unknown (date selectors, a comment), 30 cafés have no tag → Unknown
- [x] Inspect full tags and underlying objects — raw tags, metadata or "not stored", node coordinate, way node list (ordered, repeats, tap → node), relation members (role/kind/id, tap, "not in this area")
- [x] Airplane-mode acceptance scenario passes end to end — Pixel 8, airplane mode on, force-stop, relaunch: basemap, cafés, filters, list, node and way detail, map tap → detail
- [ ] Relation-café and relation-member UI path not exercised on a device (no café relation in the SLC area); "not in this area" member rows likewise
- [ ] Opening hours use the device clock and zone; a café in another zone than the phone is evaluated wrongly (needs the area's zone, e.g. from a tz lookup at download time)
- [ ] Real-GPS first run not exercised by an agent on purpose (privacy); permission-denied path checked on the emulator only

## 7. Developer documentation — shipping milestone 2026-10-03 (GO)
- [x] API freeze pass (4a282d3): public Kotlin surface audit (internal vs public), naming, KDoc on every public symbol, version 0.1.0
- [x] Opt-in foreground-service mode (4a282d3) for large area downloads (WorkManager 10-min limit)
- [x] **Name: Cantino** (Martijn 2026-10-03; story: Cantino planisphere, a smuggled copy of the master map): crate `cantino`, C ABI `cantino_*` (`include/cantino.h`), Kotlin `io.github.mvexel.cantino`, Maven `io.github.mvexel:cantino`, Gradle `:cantino`
- [ ] GitHub repo rename `mvexel/osm-framework` → `mvexel/cantino` (Martijn), then `git remote set-url origin https://github.com/mvexel/cantino.git`
- [x] Pre-0.1.0 API fixes (2026-10-03): output types not data classes (AreaInfo, AreaMetadata, BasemapMetadata, ImportReport, PmtilesInfo, AreaState subtypes); File overloads for OsmStore.open/importArea; drop redundant AreaState.Ready.report/snapshotTimestamp; snapshot timestamp as Instant; foreground-service manifest entries not forced on apps that don't download (moved out of the library manifest: app opt-in snippet, runtime check falls back to background with a warning; sample-app strips WorkManager's FOREGROUND_SERVICE)
- [x] Name research: OSMF policy allows "osm" only descriptively; candidates checked on crates.io/Maven/GitHub
- (superseded) **Name** before going public (OSMF trademark check + availability; research running) → rename package/artifact/JNI/C prefix/crate/repo
- [x] Repo goes public, Apache-2.0 (Martijn 2026-10-03); LICENSE + NOTICE (ODbL/Protomaps attribution) prepared
- [x] Distribution prepared (2026-10-03): `scripts/publish-pages.sh [--worktree]` builds native libs, the release AAR with POM metadata (name, description, url, Apache-2.0, developer, scm) into a GitHub Pages Maven layout + Dokka HTML + landing page (`build/pages`: `/maven/io/github/mvexel/cantino/0.1.0/`, `/api/`, `/index.html`, `.nojekyll`), synced into a local orphan `gh-pages` worktree at `build/gh-pages` (nothing committed, never pushed)
- [ ] Publish step (Martijn): commit + push `gh-pages`, tag `v0.1.0`, enable Pages, make repo public (commands printed by the script)
- [x] README quickstart (install → download area → query → MapLibre basemap), verified 2026-10-03: code blocks extracted verbatim from README.md into a fresh Gradle 9.8 / AGP 9.4.1 project, only the Maven URL swapped for `file://…/build/pages/maven`; `assembleDebug` green; on the x86_64 emulator `libcantino.so` loaded, live download Ready in ~10 s (136 cafés logged), relaunch in airplane mode: cafés + basemap rendered
- [x] Guide `docs/guide/`: concepts, downloading (state machine, cancellation, failures, foreground mode, sizes, privacy, permissions), basemaps, querying, performance
- [x] API reference: Dokka 2.2.0 on `:cantino` (`dokkaGeneratePublicationHtml`, public API only, source links to tag v0.1.0); fixed 4 unresolved KDoc links and added `@property` docs for constructor properties of input types and AreaState subtypes, plus companion docs (only equals/hashCode/toString overrides remain undocumented); published under `/api/` by the Pages script
- [x] Café app walkthrough (`docs/guide/cafe-app.md`), building from source (`building.md`), C ABI notes (`c-abi.md`; its C example compiles and links against `libcantino`)
- [ ] 0.2 API candidates found while documenting (2026-10-03, not decided):
  - coroutines are an `implementation` dependency (via work-runtime-ktx, POM scope runtime) although `Flow` (`AreaManager.state`) and `suspend` (`ProtomapsBuilds.latestUrl`) are public API: apps must declare kotlinx-coroutines themselves (README says so). Make it `api`
  - `state(areaId)` follows the area, not a run: right after `download()` (WorkManager enqueues asynchronously) the flow can still emit the previous run's final state, and `Failed`/`Cancelled` carry no work ID to tell them apart. Quickstart works around it with `dropWhile`. Options: work ID on every state, `state(runId)`, or a `suspend download` that returns once enqueued
  - no `Bbox` around a point (every app writes `squareAround`)
  - way/relation geometry: Kotlin has only per-node `get`; the Rust core has `way_coordinates`/dependencies. Expose a batch get or way coordinates
  - thread confinement + coroutines is an easy trap (store held across suspension on `Dispatchers.IO`); ship a small owner-thread wrapper like the café app's `StoreWorker`
  - `publishedArea`/`dataFile` block on disk: offer `suspend` variants
  - offline style assets (style.json, glyphs, sprites) need a node/npm script; consider publishing them as a downloadable artifact
## 8. Offline routing (later — not started; added 2026-10-03 by Martijn)
- [ ] Research: on-device routing engines (e.g. Valhalla, GraphHopper, OSRM, Ferrostar/Valhalla mobile, pure-Rust options), their data models/graph formats vs our SQLite raw graph (build graph on device from the area db, or download pre-built tiles?), size/import cost for a ~10×10 km area, licensing, Android/iOS fit, and amount of work
- [ ] Café app feature: real distance and travel time per chosen mode (walk/bike/car) in the nearby list, and "route to…" drawn on the map

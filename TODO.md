# TODO

Plan of record for Cantino. Scope: `CLAUDE.md`. Internal background and
evidence: `HANDOFF.md`. Developer docs: `README.md`, `docs/guide/`.
Verify each `[x]` against the repo before trusting it.

## Feature freeze (2026-10-03)

**No new features until the iOS side is built** (`CLAUDE.md` "Feature
freeze"). Android is finished for now. "Next up" holds only iOS work;
everything else waits under "Frozen". Bug fixes and docs are fine.

## Status (verified 2026-10-03)

- **0.3.0 prepared, not yet published**: import profiles (tag-filtered
  import), large-area guidance, real process-death tests. Version bumped,
  CHANGELOG dated, site built into `build/gh-pages`. Publish steps
  (commit + push `gh-pages`, tag `v0.3.0`, push `main`) are Martijn's.
- **0.2.0 is released** (2026-10-03): `lol.osm:cantino:0.2.0` on
  https://mvexel.github.io/cantino/maven, tag `v0.2.0`. 0.1.0 stays under
  `io.github.mvexel:cantino`.
- Android only. Read-only OSM data + opt-in PMTiles basemap, area download
  lifecycle, café reference app (airplane-mode acceptance passed on a Pixel 8).
- iOS: Linux-side groundwork on branch `ios/main` (not merged into `main`;
  it is behind `main` by the 0.3 work): parity corpus with Rust, Kotlin and
  Swift runners (240 calls byte-identical), Swift package tested on Linux,
  area store/journal/failure classification in the Rust core.
- Checkout: `~/dev/cantino`. Devices: Pixel 8 (`38261FDJH00B4F`), AVD
  `osmfw-x86_64` (`emulator-5554`). JDK via `mise exec --` in `android/`.
- Green before any commit: `scripts/check.sh`; `scripts/build-android.sh`;
  `cd android && mise exec -- ./gradlew :cantino:connectedDebugAndroidTest
  :cafe-app:connectedDebugAndroidTest :cafe-app:testDebugUnitTest
  :cafe-app:assembleDebug :sample-app:assembleDebug` on phone + emulator
  (4 tests skip by design: 2 live, CityBenchmark, ProcessDeathTest). When
  touching download or commit code also `ANDROID_SERIAL=…
  scripts/kill-test-android.sh` on both.
- Release a new version: bump `version` in `Cargo.toml` (single source) +
  CHANGELOG, `scripts/publish-pages.sh --worktree`, then Martijn pushes
  `gh-pages` and the tag.

## Next up: iOS (§3), blocked until Martijn is on a Mac

Done when (Martijn): iOS and Android behave identically, tests pass, café
demo compiles. Each step is verified on iOS before the next.

1. **Merge `ios/main` into `main`** (can run on Linux): bring the 0.3 work
   in, add import profiles to the Swift adapter and the parity corpus,
   `scripts/check.sh` green with the Swift tests.
2. **macOS CI** (GitHub Actions; Martijn pushes the workflow): xcframework
   (device arm64 + simulator arm64) and the existing Swift tests, parity
   runner included, green on the simulator.
3. **Swift download orchestration** as a straight port of
   `AreaDownloadWorker` over URLSession, using the Rust area store, journal
   and failure classification already in the core. Same behaviour shown by
   shared scenarios (`AreaManagerTest` cases and the process-death
   scenarios of `scripts/kill-test-android.sh`) against the same fake
   SliceOSM, not by shared code. No Rust download reducer (dropped
   2026-10-03: the parts whose divergence corrupts data are already in
   Rust; the rest is ~150 lines of pipeline).
4. **Café demo builds for iOS**; airplane-mode run on the iPhone.

## Frozen (until iOS is done; each needs scoping before it becomes work)

- Offline editing + upload: needs a scope change in `CLAUDE.md` (design
  notes: edits overlay db ATTACHed to the read-only base,
  `docs/research/2026-10-03-prebaked-dataset-format.md`).
- Offline routing research (§8).
- Dev-review backlog (§10, §12, §13, §15–§17), pre-download size estimate
  (§14), download from SliceOSM with a tag filter (§11).
- Import profiles in cosmo's filter language: considered 2026-10-03
  (extract cosmo's DSL into a `cosmo-filter` crate, replace `KeepRule` with
  a filter string); stopped as feature creep. A local, uncommitted attempt
  may still sit in `~/dev/cosmo` (branch `filter-crate`).

## Done 2026-10-03 (after 0.2.0)

Café relation path tested (§6), import profiles (§11), 50×50 km evidence
and the decision not to bound import memory (§14), real process-death tests
and the decision against byte-range resume (§5). Details in the history.

## Parked
- Server-side pre-baked area files (proposal in `docs/research/`); only if
  on-device import stops meeting budget.

---

# History (chronological log of what was done and decided)

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

- [x] (decided 2026-10-03, see §14: no fix now; node_way/member_rel kept) Follow-ups from migration: in-app import 8.2 s vs 5.7 s plain binary (cause unknown); import peak memory grows with area (chunk writes); JNI+JSON ≈ 0.08 ms/object; decide whether to drop unused node_way/member_rel (−25 MB)

## 3. iOS vertical slice — restarted 2026-10-03 (see Next up)
- xtool evaluated 2026-10-03 (research): builds/signs SwiftPM apps on Linux and installs on a USB device, but needs Xcode.xip for the SDK (Xcode licence: Apple hardware), no simulator, no iOS test runner (xtool issue #177), binary targets (MapLibre xcframework) and Rust staticlib linking undocumented. Martijn: no Xcode SDK on Linux, so iOS builds go to macOS CI / a Mac
- [ ] Inspect Mac (Xcode, toolchain)
- [ ] xcframework: device arm64 + simulator arm64
- [ ] Swift wrapper + XCTest on simulator (import, get, query)
- [x] Linux part (2026-10-03, branch `ios/main`): parity corpus `tests/parity` + Rust runner; Swift package (OsmStore, AsyncOsmStore, models, AreaStorage, Failures) tested on Linux; Kotlin and Swift parity runners, 240 calls byte-identical; area store, commit journal, recovery and failure classification moved into the Rust core
- Dropped 2026-10-03: download state machine as a Rust reducer (overengineering; see Next up 3)

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
- [ ] iOS: URLSession background equivalent (see Next up → iOS)
- [x] Failed or cancelled replace keeps old area (tested: cancel mid-download, corrupt PBF, cancel during import, basemap 404 after data, server ignoring Range, invalid Url basemap); cancel after the commit point reports Ready (tested)
- [x] (2026-10-03) Kill/restart recovers, tested with real `kill -9`: `scripts/kill-test-android.sh` drives `ProcessDeathTest` in phases against a host fake SliceOSM (`scripts/fake-sliceosm.py`, `adb reverse`). Killed mid-download: the old area stays published and whole, WorkManager reruns the same work, which resumes the checkpointed SliceOSM job (no new submit), downloads again and publishes. Killed right after the commit point: the journal rolls forward on the next read (new area whole, no journal left), the rerun finds its area published and reports Ready without downloading. Green on Pixel 8 and emulator (~1 min per device; run it when touching download/commit code, not part of the default gradle run)
- [x] Decided 2026-10-03, **not now**: byte-range resume and basemap-while-slicing. At 10×10 km a rerun repeats PBF (7.8 MB, 0.8 s) + import (3.2 s) + basemap (2.3 s) ≈ 6 s, while the expensive part, slicing (19.5 s), is already resumed via the checkpoint; on a 1 MB/s connection resume would save ~15 s per retry, and only on retries. Basemap during slicing would save ~2.3 s of 27.4 s (8%) at the cost of concurrent failure/cancel handling in the worker. Revisit if field reports show slow-network retries
- [x] (decided above) Download follow-ups (`setForeground` DONE in 4a282d3 as opt-in ForegroundConfig): byte-range resume of the PBF and of the basemap (a retried run re-downloads data and basemap; keeping a staged import across retries of the same work ID would save the re-import); basemap could be fetched while SliceOSM slices (now sequential); Rust import staging dirs orphaned by a kill mid-import (now inside the per-run staging dir, which the next run deletes). Fixed 2026-10-03: a cancel during the import no longer publishes

## 6. Café reference app
`android/cafe-app` (2026-10-03); see HANDOFF.md "Café reference app". Evidence: Pixel 8 screenshots `docs/screenshots/2026-10-03-cafe-*.png`.
- [x] first-run workflow: ask for location, download and process data for query and basemap — permission → LocationManager fix → offer dialog (10×10 km, one-line privacy note) → `AreaManager.download(…, BasemapSource.Extract(latest build, 15))` with per-phase progress → map. Fallbacks: permission denied / no fix → "lat,lon" field or SLC preset (emulator screenshot); failure → Retry (previous area kept); "Refresh area" re-downloads (verified: new snapshot, store reopened). Debug location override `--ef lat/--ef lon` for all automated runs
- [x] Find nearby cafés, filter outdoor seating + opening hours (unknown stays unknown) — 136 cafés (120 nodes, 16 ways) in the SLC area; Open/Closed/Unknown and Yes/No/Unknown filters with counts; nearby list by distance. App-side `opening_hours` evaluator, 14 JVM tests; on this area 104 of 106 present values evaluate, 2 → Unknown (date selectors, a comment), 30 cafés have no tag → Unknown
- [x] Inspect full tags and underlying objects — raw tags, metadata or "not stored", node coordinate, way node list (ordered, repeats, tap → node), relation members (role/kind/id, tap, "not in this area")
- [x] Airplane-mode acceptance scenario passes end to end — Pixel 8, airplane mode on, force-stop, relaunch: basemap, cafés, filters, list, node and way detail, map tap → detail
- [x] Relation-café and relation-member UI path exercised by `RelationCafeTest.relationCafeQueryDetailRowsAndPresentMemberResolve`: relation query, member role/kind/id, absent member, and present-member resolution
- [x] Opening hours time-zone limitation documented (known limitation, not fixed): the app evaluates in the phone zone; the default user-centred area is normally correct, but another-zone areas are wrong
- [ ] Real-GPS first run not exercised by an agent on purpose (privacy); permission-denied path checked on the emulator only

## 7. Developer documentation — shipping milestone 2026-10-03 (GO)
- [x] API freeze pass (4a282d3): public Kotlin surface audit (internal vs public), naming, KDoc on every public symbol, version 0.1.0
- [x] Opt-in foreground-service mode (4a282d3) for large area downloads (WorkManager 10-min limit)
- [x] **Name: Cantino** (Martijn 2026-10-03; story: Cantino planisphere, a smuggled copy of the master map): crate `cantino`, C ABI `cantino_*` (`include/cantino.h`), Kotlin `io.github.mvexel.cantino`, Maven `io.github.mvexel:cantino`, Gradle `:cantino`
- [x] GitHub repo renamed to `mvexel/cantino` (Martijn), remote updated
- [x] Pre-0.1.0 API fixes (2026-10-03): output types not data classes (AreaInfo, AreaMetadata, BasemapMetadata, ImportReport, PmtilesInfo, AreaState subtypes); File overloads for OsmStore.open/importArea; drop redundant AreaState.Ready.report/snapshotTimestamp; snapshot timestamp as Instant; foreground-service manifest entries not forced on apps that don't download (moved out of the library manifest: app opt-in snippet, runtime check falls back to background with a warning; sample-app strips WorkManager's FOREGROUND_SERVICE)
- [x] Name research: OSMF policy allows "osm" only descriptively; candidates checked on crates.io/Maven/GitHub
- (superseded) **Name** before going public (OSMF trademark check + availability; research running) → rename package/artifact/JNI/C prefix/crate/repo
- [x] Repo goes public, Apache-2.0 (Martijn 2026-10-03); LICENSE + NOTICE (ODbL/Protomaps attribution) prepared
- [x] Distribution prepared (2026-10-03): `scripts/publish-pages.sh [--worktree]` builds native libs, the release AAR with POM metadata (name, description, url, Apache-2.0, developer, scm) into a GitHub Pages Maven layout + Dokka HTML + landing page (`build/pages`: `/maven/io/github/mvexel/cantino/0.1.0/`, `/api/`, `/index.html`, `.nojekyll`), synced into a local orphan `gh-pages` worktree at `build/gh-pages`
- [x] **0.1.0 PUBLISHED 2026-10-03** (Martijn): repo renamed to mvexel/cantino and public; gh-pages pushed; tag v0.1.0; Pages live at https://mvexel.github.io/cantino/ (site, /api/, /maven/ all 200). Verified: fresh quickstart project resolves io.github.mvexel:cantino:0.1.0 from the live Maven URL exactly as in README, `clean assembleDebug` green, APK on emulator loads libcantino.so, no crashes. Local checkout moved to ~/dev/cantino
- [x] Publish step (Martijn): gh-pages pushed, tag v0.1.0, Pages enabled, repo public
- [x] README quickstart (install → download area → query → MapLibre basemap), verified 2026-10-03: code blocks extracted verbatim from README.md into a fresh Gradle 9.8 / AGP 9.4.1 project, only the Maven URL swapped for `file://…/build/pages/maven`; `assembleDebug` green; on the x86_64 emulator `libcantino.so` loaded, live download Ready in ~10 s (136 cafés logged), relaunch in airplane mode: cafés + basemap rendered
- [x] Guide `docs/guide/`: concepts, downloading (state machine, cancellation, failures, foreground mode, sizes, privacy, permissions), basemaps, querying, performance
- [x] API reference: Dokka 2.2.0 on `:cantino` (`dokkaGeneratePublicationHtml`, public API only, source links to tag v0.1.0); fixed 4 unresolved KDoc links and added `@property` docs for constructor properties of input types and AreaState subtypes, plus companion docs (only equals/hashCode/toString overrides remain undocumented); published under `/api/` by the Pages script
- [x] Café app walkthrough (`docs/guide/cafe-app.md`), building from source (`building.md`), C ABI notes (`c-abi.md`; its C example compiles and links against `libcantino`)
- [x] 0.2 API candidates found while documenting (2026-10-03), all decided 2026-10-03 and shipped in 0.2.0 except the last (won't for 0.2): coroutines `api`; `AreaState.runId` + `isTerminal`; `Bbox.around`; `wayCoordinates`/`representativePoint`/batch `get`; `AsyncOsmStore`; suspend `loadDataFile`/`loadBasemapFile`/`loadPublishedArea`; style assets artifact WON'T (0.2). Original list:
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

---

# Dev-review backlog (added 2026-10-03; frozen until iOS is done)

Source: two persona reviews of the public 0.1.0 (site, Dokka, GitHub guide;
no checkout): an offline hiking app (H) and an `opening_hours` field editor
(E). Reports: `docs/research/2026-10-03-hiking-dev-review.md`,
`docs/research/2026-10-03-editor-dev-review.md`.
Ranked by how many apps we expect to hit the gap, not by how loud one
reviewer was. Already-planned items both reviewers also raised (reinforced,
not repeated here): run identity in `AreaState`, owner-thread/suspend store
wrapper, batch get (0.2, both); byte-range resume (H); iOS (both;
both asked about Kotlin Multiplatform over the C ABI); edit overlay that
exports osmChange (E); routing (H). All frozen now except iOS.

**Quick wins** (⚡, roughly ≤ ½ day each, no scope change): §9 way
coordinates over JNI + representative point; §10 `NotExists` filter; §12
armeabi-v7a build; §13 Dokka defaults, guide on Pages, the three doc pages,
repo description/topics/Discussions (Martijn). Rule: do them as one slice
within 0.2, not one by one in backlog order.

## 9. Geometry helpers (both) — highest reach; shipped in 0.2
Every app with features mapped as ways (shops as buildings, trails) writes
this itself today. Replaces 0.2's "way geometry / batch get" candidate (§7).
- [x] (0.2.0) ⚡ Expose `way_coordinates` (already in the Rust core, `src/store.rs`)
  through C ABI + JNI + Kotlin: one call per way, not one per node
- [x] (0.2.0) ⚡ Core-computed representative point for ways and relations (what the
  café app does by hand); define it honestly (not a guaranteed
  point-on-surface)
- [x] (0.2.0) Batch `get(ids)` (one JNI crossing; still one lookup per object) — coordinate-only fast path beyond `wayCoordinates` not done
  Batch `get(ids)` / coordinate-only fast path to cut per-object JNI+JSON cost

## 10. Query expressiveness (E; every POI app)
- [x] (0.2.0) ⚡ `TagFilter.NotExists(key)` as a non-driving filter (filters are
  already post-checks on a driver key's index range), e.g. "amenities
  without `opening_hours`"
- [ ] Key/value OR (`ExistsAny(keys)` or any-of values) as a driver: union
  of index ranges
- [ ] Nearest-N / distance-ordered query around a point (uses §9's
  representative point; candidates semantics stay)

## 11. Import profiles / tag-filtered import (both)
~75 MB per 10×10 km of city; POI-only and outdoor apps want a fraction.
- [x] (2026-10-03) Profile model: `ImportProfile { keep: [KeepRule { kinds, key, values? }] }`,
  `osmium tags-filter` semantics with reference closure (kept relations keep
  members recursively, cycle-safe; kept ways keep their nodes; references
  stored whole). Two read-only scans (relations, then ways) compute the keep
  sets, then the normal import pass skips the rest; no profile = one pass as
  before. Recorded in a new `profile` table (no format bump: old readers
  ignore it), `ImportReport.profile`, `Store::profile`, and so in the area
  sidecar on Android. Kotlin `ImportProfile`/`KeepRule`, carried through the
  WorkRequest. Not in the Swift adapter / parity corpus yet (those live on
  `ios/main`; add when it merges)
- [x] Measured on SLC 30×15 km (13 MB PBF, desktop): none 128.5 MB / 2.7 s;
  outdoor 61.6 MB / 1.8 s; routing 41.4 MB / 1.4 s; POI 8.8 MB / 0.7 s.
  Object counts identical to `osmium tags-filter` for all three profiles
  (POI 97,522 / 8,745 / 103)
- [ ] Download stays full size (SliceOSM has no tag filter); only worth
  pursuing if download size becomes the complaint

## 12. Distribution and platforms (both)
Corporate dependency policies flag the current setup. Outward-facing steps
(Sonatype account, signing keys, group ID) are Martijn's.
- [x] (0.2.0) ⚡ armeabi-v7a (`armv7-linux-androideabi` in `scripts/build-android.sh`,
  tests on the emulator) — cheap budget phones used by field mappers and
  hikers
- [ ] Maven Central with signed artifacts; decide the group ID (personal
  `io.github.mvexel` vs an org namespace) before 1.0
- [x] **Namespace → `lol.osm.cantino`** (done 2026-10-03 on v0.2/namespace; 0.1.0 artifacts stay under the old path; Martijn 2026-10-03, "if
  possible"): Kotlin package, Maven group `lol.osm` (artifact `cantino`),
  JNI symbols (`Java_lol_osm_cantino_*` in `src/android.rs`), Dokka/Pages
  paths, README/guide snippets, café/sample apps. Ideally in 0.2 (already
  a breaking release; renaming later breaks twice). Check first: Martijn
  controls `osm.lol` (Maven Central verifies reverse-DNS groups by domain
  TXT record; Pages Maven doesn't care); the OSMF trademark policy is fine
  with "osm" in a package/domain used descriptively (it is not in the
  product name). Mechanical sweep after the 0.2 API slices merge, before
  the release build.

## 13. Docs and developer experience (both)
- [x] (0.2.0) ⚡ Dokka renders non-literal defaults (`Query.maxCandidates`,
  `AreaConfig` timeouts/intervals look required); fix or document them in
  KDoc
- [x] (0.2.0) ⚡ Move the guide onto the Pages site next to `/api/`
- [x] (0.2.0) ⚡ Doc pages: SliceOSM in production (terms, rate limits, lag,
  self-hosting via `sliceBaseUrl`); pairing with an `opening_hours` library;
  "editing apps: what Cantino gives you" (versions in snapshots, freshness)
- [x] (0.2.0, page only; repo description/topics/Discussions still Martijn's) ⚡ Roadmap/maintenance page (who maintains, path to 1.0, expected
  breaking minors); repo description, topics, Discussions (Martijn)
- [x] Typed errors (codes or a sealed exception hierarchy) instead of one
  type with a string; breaking, so part of 0.2. Done on branch
  `v0.2/errors` (2026-10-03): sealed `CantinoException` (InvalidArgument /
  InvalidFile / Io / WrongThread) thrown directly from JNI, Rust
  `ErrorKind` + C ABI `cantino_last_error_code` (compatible), and
  `AreaState.Failed.reason: FailureReason`. Instrumented tests compile, not
  yet run on devices
- [ ] Testability: an interface or fake for `OsmStore`, and a host-side
  native lib for JVM/Robolectric tests (or document instrumented-only)
- [ ] Compose sample

## 14. Large and rural areas (H)
Overlapped the old import-memory item. Scope says no country scale; a 50×50 km
park is in between and needs evidence, not a guess.
- [x] (2026-10-03) Benched two 50×50 km areas on the Pixel 8
  (`docs/bench/2026-10-03-{zion,oberland}-50km-pixel8.json`, table in
  `docs/guide/performance.md` "Large areas"): Zion (sparse) 2.9 MB PBF →
  27.5 MB, 1.6 s, +68 MB peak, basemap 6.2 MB; Bernese Oberland (dense)
  26.3 MB PBF → 208.7 MB, 13.4 s, +204 MB peak, basemap 35.2 MB; SliceOSM
  11 s / 63 s, no limit hit. POI profile: 2.1 / 8.3 MB.
  **Decision: no chunked-write import-memory fix now** (+204 MB for the
  dense case is fine on current phones); revisit for 100 km+ dense areas or
  a low-RAM device target. In-app vs plain-binary import gap still there
  (SLC 8.24 s in app on 0.2.0) and stays unexplained: not worth chasing at
  these times. `node_way`/`member_rel` stay (editing needs parent lookups)
- [ ] Pre-download size estimate (SliceOSM if it offers one, else a density
  heuristic) so apps can warn before the user commits storage. Note: density
  varies 8× between the two 50×50 km areas, so a per-km² constant is useless

## 15. Several files per area (H)
- [ ] Extra PMTiles per area (raster DEM / hillshade) published in the same
  atomic commit as data + basemap; generalise `BasemapSource` to a list

## 16. Route relation assembly (H; hiking and transit apps)
Needs a scope line in `CLAUDE.md` (multipolygon assembly is out; ordered
route linestrings are a different thing).
- [ ] Assemble `route=*` relations into ordered linestrings with gap and
  "continues outside the area" reporting; a hiking sample on top

## 17. Area shapes and several areas (H, E asked) — scope change
`CLAUDE.md`: one area per app, overlapping areas out.
- [ ] Decide on several non-overlapping areas per app (multi-region trips,
  "user moved out of the area")
- [ ] Polygon / corridor areas (long-distance trails); check what SliceOSM
  accepts first

# Cantino handoff

Updated October 3, 2026. This document is for the next developer continuing Cantino (Android and iOS). The project was called osm-framework until the 2026-10-03 rename; dated research docs, bench records and `spike/` keep the old name. Scope and boundaries are in `CLAUDE.md`, and progress is tracked in `TODO.md`.

The core imports an OSM snapshot (PBF or XML) into a single read-only SQLite file, reopens it offline, and answers lookups, tag queries and bbox queries through indexes. One Rust library serves both platforms. Android works end to end on a Pixel 8 and an emulator. iOS is still to be built. Android area downloads (OSM data from SliceOSM plus an opt-in PMTiles basemap, downloaded or extracted on the device) work end to end, and the café reference app (`android/cafe-app`) passes the airplane-mode acceptance scenario on the Pixel 8.

## Product goals

Cantino is an offline OpenStreetMap SDK: a headless native library for app makers who need OpenStreetMap data while offline. Applications define their download area and obtain live extracts from SliceOSM. It retains the raw OSM graph and displays an offline basemap: a separate PMTiles extract rendered with MapLibre Native.

The reference application finds cafés in a city area. The acceptance scenario has these steps:

- download the area
- restart in airplane mode
- display the basemap
- find nearby cafés
- filter by outdoor seating and opening hours
- inspect the full tags and the underlying objects

Missing opening hours stay unknown. Café policy and opening-hours interpretation belong above the data core.

Read-only data access is the current milestone. Edits, upload, conflicts, refresh, overlapping areas and country scale come later.

## Storage decision (2026-10-03)

**The SQLite store replaced OSMExpress.** The first backend was OSMExpress (LMDB) behind a fork. On a Pixel 8 with a Salt Lake City extract (13 MB PBF, 1.44M nodes) it produced a 338 MB database and 425 MB peak memory, and a tag query took 32.6 s at p95. Two SQLite schemas were then spiked side by side, and variant B won. The evidence is in `docs/bench/`, and the spike code is in `spike/` (variant A stopped unfinished).

| Pixel 8, Salt Lake City | OSMExpress | SQLite core, through the AAR |
| --- | --- | --- |
| Database | 338 MB | 128.5 MB (9.8× the PBF) |
| Import | 5.0 s | 8.2 s (5.7 s as a plain binary) |
| Peak memory (VmHWM, includes about 126 MB of test runtime) | 425 MB | 282 MB |
| Tag query p95 (175 cafés) | 32,600 ms | 22 ms (the Rust core alone takes 3–4 ms; the rest is JNI, JSON and Kotlin decoding) |
| Bbox + tag query p95 | 350 ms | 3.7 ms |

OSMExpress is gone from the build. The fork `mvexel/OSMExpress` branch `mobile-core` (commit `1e10945`) is kept for reference. It contains an upstreamable fix for UB in its `CHECK_LMDB` macro (commit `978f265`). That bug was found because the Android run returned not-found for every lookup, even though Linux happened to work. The lesson: native warnings are not noise, and on-device tests catch bugs that desktop tests miss.

The parked server-side option is pre-baked area files for download. Its format proposal is in `docs/research/2026-10-03-prebaked-dataset-format.md`: ship the zstd-compressed SQLite file with a signed manifest.

## Architecture

```text
Android Kotlin (OsmStore, Models.kt)        iOS Swift (pending)
        |  JNI (src/android.rs)                |  C header
        +-------- C ABI: include/cantino.h, owned JSON buffers --------+
                                   |
                          Rust core (src/)
                 import.rs  input.rs  store.rs  encoding.rs
                                   |
                  SQLite (rusqlite, bundled, R-tree enabled)
```

The area format is documented in `src/schema.rs`:

- **Objects:** one row per object with compact blobs. Way references are delta-zigzag varints, and tag keys and roles are interned.
- **Indexes:** `tag_index(k, v, kind, id)`, an `rtree_i32` `geo` table, and the reverse-reference tables `node_way` and `member_rel`.
- **Identification:** the file header carries an `application_id` and a format version.
- **Import:** writes one transaction, builds indexes after loading, and compacts with `VACUUM INTO`. It is staged in a private sibling directory, synced, and published by atomic rename, so a failure leaves any existing area intact.

Ownership and threading:

- A Store is one read-only connection that belongs to its creating thread.
- The C ABI checks the calling thread and returns an error instead of misbehaving. Several Stores may open the same file.
- Errors and panics become status codes and never unwind into the caller.
- Free framework buffers with `cantino_free`.

## Query semantics

| Query | Behavior |
| --- | --- |
| Tags | ANDed `Equals` and `Exists` filters, driven from `tag_index` by the most selective filter. No full scans. A query plan test enforces this |
| Bbox | Nodes use an exact point-in-box test. Ways and relations match when their bounds intersect the box, so a way that crosses the box with no node inside it is included. A way that bends around the box may also be included: bounds are a candidate filter, not exact geometry |
| Bbox on untagged nodes | **Untagged nodes (way vertices) are not spatially indexed**, so a bbox query never returns them. Reach them through their ways |
| Bounds at the edge | Bounds cover only the members present in the extract, so objects clipped at the area edge get smaller boxes |
| Ordering and paging | Results are ordered nodes → ways → relations, then by ID. Keyset pagination uses `after` |
| Metadata | Untagged nodes store only their version by default, so `metadata` is null and `location_version` is set. `preserve_untagged_metadata` keeps full metadata, at a large size cost |

## Build and test

```sh
scripts/check.sh            # fmt, clippy -D warnings, cargo test, C ABI smoke via ctypes
cargo run --release --example offline -- import INPUT.osm.pbf AREA.sqlite
cargo run --release --example offline -- cafes AREA.sqlite
cargo run --release --example offline -- get AREA.sqlite way 1
```

The toolchain is pinned to Rust **1.99.0**. Neither Docker nor a C++ toolchain is needed: `rusqlite` compiles its bundled SQLite. Imports accept PBF and OSM XML. They reject non-ascending IDs, duplicates, deleted or invisible objects, history files and osmChange files.

### Android area downloads

`AreaManager` (Kotlin, `android/cantino`) runs the SliceOSM lifecycle in WorkManager: `download(areaId, bbox, name, basemap = BasemapSource.None)` enqueues unique work per area (REPLACE), `cancel(areaId)`, `state(areaId): Flow<AreaState>`, `dataFile(areaId)`, `basemapFile(areaId)`, `publishedArea(areaId)`. The SliceOSM protocol (request body, URLs, job-ID validation, progress) is in Rust (`src/slice.rs`, C ABI `cantino_slice_*`) so iOS reuses it; Kotlin does only HTTP (`HttpURLConnection`) and scheduling.

- **States:** Idle → Queued → Submitting → Slicing → Downloading → Importing → (Basemap) → Ready, or Failed / Cancelled. `Basemap(phase, bytes, total)` with phase DOWNLOAD (Url), DIRECTORIES or TILES (Extract). Importing now writes into a staging directory; nothing is published before the final commit.
- **Basemap (opt-in):** `BasemapSource.None` (default, data only) | `Url(pmtilesUrl)` (plain download of a ready PMTiles file, then validated as PMTiles v3 by `cantino_basemap_info`) | `Extract(planetUrl, maxZoom = 15, overfetch = 0.05)` (on-device extract with HTTP range requests). `ProtomapsBuilds.latestUrl()` HEADs `build.protomaps.com/YYYYMMDD.pmtiles` from today back 7 days, for demos only: Protomaps discourages hotlinking and builds expire after about a week, so production apps mirror a build (any host with range support). The basemap is published at `filesDir/cantino-areas/<areaId>.pmtiles`; `AreaInfo.basemapFile` and `AreaInfo.pmtilesUrl` (`pmtiles://file:///…` for a MapLibre vector source). A refresh with `None` removes an earlier basemap (refresh = full replace).
- **Extract driver:** the Rust engine (`src/basemap`, C ABI `cantino_basemap_*` in `src/mobile_basemap.rs`) plans and assembles; `BasemapExtract` (Kotlin) fetches. Plan and assembler handles are thread-confined like stores, so all native calls of one extract run on one private thread; HTTP runs on `Dispatchers.IO`, 4 requests in parallel (`AreaConfig.basemapParallelism`). Directory responses cross JNI as `byte[]`; tile ranges are streamed to `range-<id>.part` files and handed over by path (`asm_write_range_file`), so tile data never sits in the heap. A range answer must be 206 with a matching `Content-Range` and exact length; a 200 (server ignored Range) fails permanently without reading the body.
- **Files:** published `filesDir/cantino-areas/<areaId>.sqlite`, `<areaId>.pmtiles` and the `<areaId>.json` sidecar (SliceOSM snapshot timestamp, bbox, import report, basemap source/size/tiles/requests, publishing work ID). The next version is built in `filesDir/cantino-areas/.staging/<areaId>/<workId>/` (same file system, so parts move by rename). Download scratch (PBF, range files, job checkpoint) lives in `noBackupFilesDir/cantino-area-downloads/<areaId>/`.
- **Publish guarantee:** an area is Ready only when the OSM data and the requested basemap are both published. Three files cannot be renamed atomically together, so `AreaStorage.commit` uses a roll-forward journal: under a per-area lock, a last cancellation check, then `<areaId>.commit` is written durably (**the commit point**), then basemap, data and sidecar are renamed into place and the journal deleted. Any failure or cancel before the commit point leaves the old area (data + basemap + sidecar) untouched and the new data unpublished; after it, the new version is published completely, and a process that dies mid-rename is finished by the next `recover()` (every reader and every run calls it). Readers in this process take the same lock, so they never see a half-renamed area. Not covered: another process, or code opening the files directly, during the milliseconds of renames (the sidecar's size checks then report metadata as unknown).
- **Cancellation** is honored until the commit point, including during the native import (it cannot be interrupted; its staged result is discarded when it returns). The final check runs under the lock and also reads WorkManager's own record of the run, because `cancelUniqueWork` marks the work CANCELLED before the coroutine is cancelled. Once the commit point has passed, cancel is ignored: the run finishes and `state()` reports Ready, since a finished run's state is decided by the published sidecar's work ID, not by WorkManager's CANCELLED.
- **Foreground mode (opt-in, `AreaConfig.foreground = ForegroundConfig(...)`):** the worker calls `setForeground` (dataSync type, progress notification with a cancel action, channel/title/icon from the app) so a run is not cut at WorkManager's 10-minute limit. Best effort: where Android refuses (background start on 12+, missing permission) the run logs and continues as background work. The library manifest declares FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC and the SystemForegroundService type; POST_NOTIFICATIONS is the app's (denied = notification hidden, download unaffected). Tested by `foregroundModeRunsTheDownloadAsAForegroundService` (service listed as a running foreground service mid-run).
- **Public API (0.1.0):** the module builds in Kotlin explicit API mode; everything else is `internal`. Version comes from `Cargo.toml` into the Maven publication and `Cantino.VERSION` (generated source). See `CHANGELOG.md`.
- **Retries:** transient errors (IO, 5xx, 408, 429, truncated ranges) retry inline, then through WorkManager backoff. A restarted run resumes polling its checkpointed job instead of resubmitting; a run that died after its commit point succeeds at once. Other 4xx, import failures, invalid PMTiles and servers ignoring Range are final. Downloads (PBF and basemap) restart from byte 0.
- **Tests:** `AreaManagerTest` uses a fake SliceOSM + basemap host (MockWebServer 5.4.0; 5.5.0 needs compileSdk 37) that also serves the fixture PMTiles with range support. Covered: None unchanged, Url published + validated, invalid Url file, Extract byte-identical to the engine run in-process over the same bytes, server ignoring Range, basemap 404 after a successful data part, cancel during import, cancel after the commit point, latest-build lookup. `AreaTestHooks` (internal, null in production) block at the import and commit points so tests can cancel there. The live tests are skipped unless `-Pandroid.testInstrumentationRunnerArguments.live=true`; they log per-state timings under the `AreaLive` tag. Live on the Pixel 8 (downtown SLC, Extract z0–15 from the 2026-10-03 build): Ready in 6.3 s, of which the basemap took 2.5 s; 24 requests, 1.93 MB transferred, 1.01 MB file, 16 tiles.
- The library manifest adds INTERNET and ACCESS_NETWORK_STATE; WorkManager adds WAKE_LOCK, RECEIVE_BOOT_COMPLETED and FOREGROUND_SERVICE. The sample app still removes INTERNET.

### Android

```sh
rustup target add aarch64-linux-android x86_64-linux-android --toolchain 1.99.0
scripts/build-android.sh    # → target/android/{arm64-v8a,x86_64}/libcantino.so
cd android && mise exec -- ./gradlew :cantino:connectedDebugAndroidTest
mise exec -- ./gradlew :cantino:publishReleasePublicationToLocalRepository  # AAR → android/build/repo
ANDROID_SERIAL=<device> scripts/bench-android.sh CITY.osm.pbf                   # city benchmark JSON
```

Local setup:

- **SDK:** `~/Android/Sdk`, with NDK `29.0.14206865` (SDK-managed, also used by Gradle to strip libraries). The emulator image is AVD `osmfw-x86_64` (API 35; local name, predates the rename).
- **JDK:** pinned in `mise.toml`, because the system Java 25 has no `javac`.
- **Build:** Gradle 9.8 wrapper and AGP 9.4.1, with compileSdk 36 and minSdk 26.

The Kotlin API is `OsmStore` (open, importArea, get, query, close) with typed models in `Models.kt`. JSON is only the wire format across JNI. The library depends only on libc, libm and libdl.

`CityBenchmark` reports an `AssumptionViolatedException` when no PBF is passed. That's expected: it is skipped, not broken.

### Café reference app

`android/cafe-app` (package `io.github.mvexel.cantino.cafe`) is the phase-6 reference app; `android/sample-app` stays the minimal offline-basemap demo. Plain Android views built in code, MapLibre Native 13.6.1, no Play Services, no AppCompat/Compose.

- **First run:** location permission → one fix from `LocationManager` (fused/network/GPS, 30 s timeout) → offer dialog: ~10×10 km around the fix, map data + basemap, plus one privacy line (the bbox ≈ the user's location goes to SliceOSM and the PMTiles host) → `AreaManager.download("cafe-area", Geo.squareAround(fix), basemap = Extract(ProtomapsBuilds.latestUrl(), 15))` → progress screen (phase, bytes with total when known, fraction only when SliceOSM reports one, per-phase durations; also logged under tag `CafeDownload`) → map. Denied or no fix: type "lat,lon" or pick the SLC downtown preset. Failure: Retry (the framework keeps the previous area), or back to the map. "Refresh area" re-downloads the same bbox. Area size is `Geo.AREA_SIZE_KM` (a default, not a cap).
- **Debug location override** (debuggable builds only, sticky in prefs, cleared with `--ez clear_debug_location true`): `adb shell am start -S -n io.github.mvexel.cantino.cafe/.MainActivity --ef lat 40.7608 --ef lon -111.8910`. Use it for every automated run so the tester's real location is never sent to SliceOSM/Protomaps. `--ez show_when_locked true` (debug only) lets the screens show over a secure lock screen for adb screenshots.
- **Map:** the area's PMTiles through the sample app's offline style (copied from `sample-app/build-assets` by the `copyBasemapStyleAssets` task, without `slc.pmtiles`; run `scripts/basemap-assets.sh` first). Cafés are a GeoJSON source built from `OsmStore.query(amenity=cafe, area bbox)` (paged); ways and relations are placed at the mean of their resolvable node coordinates (`CafeLoader.representativePoint`, an app-level choice, not exact geometry). Colour = open now / closed now / unknown. Tap a dot → detail. "List" shows the nearby list sorted by distance from the debug override, else a fresh last-known fix inside the area, else the area centre.
- **Filters:** outdoor seating Yes / No / Unknown (`yes`, `no`, plus seating-kind values like `sidewalk`, `garden` = yes; missing or anything else = Unknown with the raw value) and "now" Open / Closed / Unknown. Each choice shows its count; Unknown is never folded into Open or Closed.
- **Opening hours** (`OpeningHours.kt`, app only, 14 JVM tests in `cafe-app/src/test`): own small parser for a strict subset: weekday lists/ranges (wrapping), `PH`, `HH:MM-HH:MM` lists including past-midnight ranges, `off`/`closed`, `24/7`, normal `;` and additional `,` rules. Anything else is Unknown with a reason (month/date, SH, week, nth weekday, sunrise/sunset, `+`, `||`, comments, `unknown`, days without times, typos). PH is evaluated both ways; if the answer differs it is Unknown. StreetComplete's `osm-opening-hours` was considered and not used: it parses only, and the evaluation (the hard part) would still be ours. On the SLC area 104 of 106 present values evaluate. Times use the device clock and zone.
- **Detail:** any object by kind and ID: an "App interpretation" block for cafés (status, reason, raw `opening_hours`, outdoor seating, map point), all raw tags, metadata or "not stored (location version N)", node coordinate, way node refs in order with repeats (tap → node), relation members (role, kind, id; tap → member; "not in this area" when `get()` returns null).
- **Threading:** `StoreWorker` owns the one `OsmStore` on a dedicated `osm-store` thread (a single-thread dispatcher). Every call checks the published area's identity (publishing work ID) and reopens the store after a refresh, since an open store keeps its snapshot.
- **Acceptance (Pixel 8, 2026-10-03, downtown SLC, live services):** fresh install (`pm clear`), debug location, Download: Ready in 27.4 s. Phases: basemap build lookup 0.7 s, submit 0.9 s, slicing 19.5 s, PBF 7.8 MB in 0.8 s, import 3.2 s, basemap directories 0.9 s and tiles 1.4 s. Results: 75.6 MB db (778,251 nodes, 123,332 ways, 931 relations), 6.5 MB PMTiles (196 tiles, z0–15, 28 requests, 7.6 MB transferred). Then airplane mode on, force-stop, relaunch: basemap, 136 cafés, filters, list, node and way detail and map tap all worked. A later refresh took 14.0 s and the map reopened on the new snapshot. Screenshots: `docs/screenshots/2026-10-03-cafe-*.png`.

## Verification evidence (2026-10-03)

- 17 Rust integration tests and 2 unit tests pass, along with Clippy, rustfmt and the ctypes C ABI smoke test. The tests also check that XML and PBF imports produce identical objects and that a way crossing the box without inner nodes is returned.
- Six Android instrumented tests pass on a Pixel 8 (Android 17) and an x86_64 emulator (Android 15). They cover:
  - import, get and query
  - a Unicode round-trip across JNI
  - pagination and bbox
  - an invalid query leaving the store usable
  - thread confinement and a closed store
  - untagged-node metadata
- Area downloads with basemaps (2026-10-03): `AreaManagerTest` (15 tests, the 2 live ones skipped by default) plus the store tests pass on the Pixel 8 and the emulator; the ABI layer of the basemap extract has Rust unit tests (`src/mobile_basemap.rs`, byte-identical to the engine) and a ctypes run in `scripts/mobile-api-smoke.py`.
- The Salt Lake City numbers above are on the Pixel. All café counts (175 total, 15 downtown) match between backends.

## Risks and open work

- **Import inside the app is about 45% slower than the plain binary** (8.2 s vs 5.7 s on the same phone). The cause is unconfirmed; app-storage encryption and the write amplification from `VACUUM INTO` are suspects.
- **Peak memory during import is about 156 MB above the runtime.** The importer holds node coordinates, node→way pairs and tag rows in memory, which grows with area size. Chunked writes would reduce this. Dropping the unused `node_way` and `member_rel` tables would save about 25 MB of file.
- **Query cost through the AAR is dominated by JNI and JSON** (about 0.08 ms per object). A binary or batched wire format is the lever if this matters.
- Fonts for the basemap are 14 MB for three stacks; subset them to the ranges needed.
- Café app: opening hours are evaluated in the device's time zone, not the café's; the relation-café UI path has not run on a device (none in the SLC area); the real-GPS first run is deliberately not exercised by agents.
- Pending phases are tracked in `TODO.md`: the iOS slice and the iOS download lifecycle.

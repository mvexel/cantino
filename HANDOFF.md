# Offline OSM mobile framework handoff

Updated October 3, 2026. This document is for the next developer continuing the Android and iOS framework. Scope and boundaries are in `CLAUDE.md`, and progress is tracked in `TODO.md`.

The core imports an OSM snapshot (PBF or XML) into a single read-only SQLite file, reopens it offline, and answers lookups, tag queries and bbox queries through indexes. One Rust library serves both platforms. Android works end to end on a Pixel 8 and an emulator. iOS and the café reference app are still to be built; Android area downloads (OSM data) work end to end against SliceOSM.

## Product goals

The framework is a headless native framework for app makers who need OpenStreetMap data while offline. Applications define their download area and obtain live extracts from SliceOSM. The framework retains the raw OSM graph and displays an offline basemap: a separate PMTiles extract rendered with MapLibre Native.

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
        +-------- C ABI: include/osm_framework.h, owned JSON buffers --------+
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
- Free framework buffers with `osm_framework_free`.

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

`AreaManager` (Kotlin, `android/osm-framework`) runs the SliceOSM lifecycle in WorkManager: `download(areaId, bbox, name)` enqueues unique work per area (REPLACE), `cancel(areaId)`, `state(areaId): Flow<AreaState>`, `areaFile(areaId)`, `publishedArea(areaId)`. The protocol (request body, URLs, job-ID validation, progress) is in Rust (`src/slice.rs`, C ABI `osm_framework_slice_*`) so iOS reuses it; Kotlin does only HTTP (`HttpURLConnection`) and scheduling.

- **Files:** published area `filesDir/osm-areas/<areaId>.sqlite` plus a `<areaId>.json` sidecar (SliceOSM snapshot timestamp, bbox, import report). Download staging and the job checkpoint live in `noBackupFilesDir/osm-area-downloads/<areaId>/`.
- **Invariant:** the published file changes only through `OsmStore.importArea`'s atomic rename. Failures, cancellation and process death before that leave it intact. Open stores keep the old snapshot until reopened.
- **Retries:** transient errors (IO, 5xx, 408, 429) retry inline, then through WorkManager backoff. A restarted run resumes polling its checkpointed job instead of resubmitting. Other 4xx and import failures are final. The PBF itself restarts from byte 0.
- **Cancellation** is honored until the import starts; the native import is not interruptible.
- **Tests:** `AreaManagerTest` uses a fake SliceOSM (MockWebServer 5.4.0; 5.5.0 needs compileSdk 37). The live test is skipped unless `-Pandroid.testInstrumentationRunnerArguments.live=true`; it logs per-state timings under the `AreaLive` tag.
- The library manifest adds INTERNET and ACCESS_NETWORK_STATE; WorkManager adds WAKE_LOCK, RECEIVE_BOOT_COMPLETED and FOREGROUND_SERVICE. The sample app still removes INTERNET.

### Android

```sh
rustup target add aarch64-linux-android x86_64-linux-android --toolchain 1.99.0
scripts/build-android.sh    # → target/android/{arm64-v8a,x86_64}/libosm_framework.so
cd android && mise exec -- ./gradlew :osm-framework:connectedDebugAndroidTest
mise exec -- ./gradlew :osm-framework:publishReleasePublicationToLocalRepository  # AAR → android/build/repo
ANDROID_SERIAL=<device> scripts/bench-android.sh CITY.osm.pbf                   # city benchmark JSON
```

Local setup:

- **SDK:** `~/Android/Sdk`, with NDK `29.0.14206865` (SDK-managed, also used by Gradle to strip libraries). The emulator image is AVD `osmfw-x86_64` (API 35).
- **JDK:** pinned in `mise.toml`, because the system Java 25 has no `javac`.
- **Build:** Gradle 9.8 wrapper and AGP 9.4.1, with compileSdk 36 and minSdk 26.

The Kotlin API is `OsmStore` (open, importArea, get, query, close) with typed models in `Models.kt`. JSON is only the wire format across JNI. The library depends only on libc, libm and libdl.

`CityBenchmark` reports an `AssumptionViolatedException` when no PBF is passed. That's expected: it is skipped, not broken.

## Verification evidence (2026-10-03)

- 17 Rust integration tests and 2 unit tests pass, along with Clippy, rustfmt and the ctypes C ABI smoke test. The tests also check that XML and PBF imports produce identical objects and that a way crossing the box without inner nodes is returned.
- Six Android instrumented tests pass on a Pixel 8 (Android 17) and an x86_64 emulator (Android 15). They cover:
  - import, get and query
  - a Unicode round-trip across JNI
  - pagination and bbox
  - an invalid query leaving the store usable
  - thread confinement and a closed store
  - untagged-node metadata
- The Salt Lake City numbers above are on the Pixel. All café counts (175 total, 15 downtown) match between backends.

## Risks and open work

- **Import inside the app is about 45% slower than the plain binary** (8.2 s vs 5.7 s on the same phone). The cause is unconfirmed; app-storage encryption and the write amplification from `VACUUM INTO` are suspects.
- **Peak memory during import is about 156 MB above the runtime.** The importer holds node coordinates, node→way pairs and tag rows in memory, which grows with area size. Chunked writes would reduce this. Dropping the unused `node_way` and `member_rel` tables would save about 25 MB of file.
- **Query cost through the AAR is dominated by JNI and JSON** (about 0.08 ms per object). A binary or batched wire format is the lever if this matters.
- Fonts for the basemap are 14 MB for three stacks; subset them to the ranges needed.
- Pending phases are tracked in `TODO.md`: iOS slice, basemap download, the iOS download lifecycle, and the café reference app.

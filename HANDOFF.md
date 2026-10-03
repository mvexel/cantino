# Offline OSM mobile framework handoff

Updated October 3, 2026. This document helps the next developer continue the Android and iOS framework from the first implemented data-core pass. The core now imports OSM data into an OSMExpress database, reopens it offline, returns raw objects, and supports tag queries and spatial candidates. Linux behavior is tested and Android arm64 libraries compile. A phone application, iOS validation, and offline map rendering remain to be built.

## Product goals

Build a headless native framework for app makers who need OpenStreetMap data while offline. Android and iOS share a Rust core. Applications define their download area, obtain live extracts from SliceOSM, retain raw OSM graph data, and eventually display an offline basemap through MapLibre Native.

The reference application finds cafés in an app-defined city area. The intended acceptance scenario is to download the area, restart in airplane mode, display the basemap, find nearby cafés, filter outdoor seating and opening hours, and inspect the complete tags and underlying objects. Missing opening-hours information must remain unknown. Café policy and opening-hours interpretation belong above the general data core.

Read-only data access is the current milestone. Offline edit batches, upload synchronization, conflict handling, refresh, overlapping areas, and country-scale operation are later milestones. Map styling is controlled by app developers against a documented source schema; raw OSM PBF cannot be passed directly to MapLibre as vector tiles.

## Decisions and working style

The user authorized assistant implementation and requested liberal explanatory comments. Preserve OSMExpress's C++ naming, indentation, storage format and general implementation approach. Keep changes to that fork focused on embedding and mobile needs. Rust follows idiomatic Rust conventions and rustfmt. Explain ownership, transactions, invariants and uncertain guarantees in comments and documentation.

OSMExpress is the selected backend for this first pass. Its LMDB tables store coordinates, Cap'n Proto object payloads, S2 node cells and reverse references. The Rust framework handles API ownership, query composition, dependency reporting and area publication. The earlier SQLite draft is parked under `work/sqlite-prototype` and is outside the compiled library; do not accidentally resume that implementation.

## Repositories and locations

| Component | Location and revision |
| --- | --- |
| Upstream OSMExpress | https://github.com/bdon/OSMExpress |
| User's fork | https://github.com/mvexel/OSMExpress |
| Fork implementation branch | `mobile-core`, commit `1e10945` (local until pushed) |
| Upstream starting revision | `045a515132e91a3679ce3df27329937c9b8b221a` |
| Working fork checkout | `/home/mvexel/Documents/Codex/2026-10-03/can-x20/outputs/OSMExpress` |
| Rust framework checkout | `/home/mvexel/Documents/Codex/2026-10-03/i-want-to-build-a-framework/outputs/osm-framework` |
| Framework backend dependency | `vendor/OSMExpress`, a Git submodule pinned to the fork commit above |
| Earlier requirements and lessons | Sibling `../tutorial` directory |

The fork branch has been pushed. The framework is a separate local repository and has no GitHub remote configured. No upstream PR has been opened. The submodule pins an exact backend commit; its configured tracking branch is `mobile-core`, but ordinary submodule checkout uses the pinned commit.

## Architecture

```text
Android Kotlin adapter                 iOS Swift adapter
               \                         /
                Rust framework C ABI
                  owned JSON buffers
                          |
                    Rust data core
                          |
                   OSMExpress C ABI
                          |
             LMDB / Cap'n Proto / S2
```

The platform adapters are pending. Both C headers have explicit handle and buffer ownership. A Store belongs to one worker thread, and returned Rust objects own their strings and references. The framework rejects a second Store for the same canonical path because LMDB forbids opening an environment twice in a process. Reuse the existing Store rather than opening one per query.

The Rust API is usable directly by desktop test harnesses. `include/osm_framework.h` exposes open, import, get, query, close and free calls for platform adapters. Objects and query results cross the boundary as UTF-8 JSON. Native and Rust errors become status codes; neither boundary intentionally unwinds into the caller. Free a framework buffer with `osm_framework_free`; free a backend buffer with `osmx_free`. These allocators are separate contracts.

## Implemented behavior

| Capability | Current behavior |
| --- | --- |
| Raw object model | Typed node, way and relation IDs; raw tags; integer coordinates; ordered way nodes; member kinds, roles and repeated references |
| Metadata | Framework imports retain payloads for untagged nodes; existing upstream files may lack those payloads |
| Import | Synchronous PBF/XML import through libosmium and OSMExpress into a private sibling staging directory |
| Publication | Validate the completed database, sync it and publish by atomic rename; failures leave an existing area intact |
| Lookup and scans | Owned objects by ID and bounded ID-ordered namespace scans |
| General tag queries | ANDed existence/equality predicates, stable kind/ID order and keyset pagination |
| Spatial selection | Bounded S2 node-cell candidates and parent ways/relations; point results receive an exact bbox filter |
| Dependency reporting | Missing references retain their positions; recursive traversal has a visited set and object budget |
| Geometry support | Ordered optional coordinates for a way; missing nodes remain explicit gaps |
| SliceOSM protocol | Validated bbox request body, UUID/status/download URLs and progress decoding; platform HTTP scheduling is pending |
| Mobile interface | Rust and backend C APIs with owned JSON results and explicit cleanup |

Replacing an area does not alter an already-open Store's snapshot. Drop the old Store and reopen the destination to use the replacement. Index construction spans several transactions, so an incomplete staging database must never be presented as an active area.

## Build and test

The Rust project pins toolchain **1.99.0**. Linux native builds require Docker, Git and Python 3. Initialize the backend and its S2 submodule:

```sh
git submodule update --init --recursive
scripts/build-native.sh
```

This builds the native library in a Debian container, then builds Rust, runs its integration tests and Clippy, and exercises both C interfaces. Native artifacts go into `vendor/OSMExpress/build-linux/artifacts`; Cargo uses that directory by default. Set `OSMX_LIB_DIR` to use an existing native build, or `OSMX_SOURCE_DIR` when running the scripts to use another backend checkout.

The desktop harness exercises the same core:

```sh
cargo run --example offline -- import INPUT.osm.pbf AREA.osmx
cargo run --example offline -- cafes AREA.osmx
```

Imports require current snapshot objects with positive IDs in ascending order within each namespace. Duplicate IDs, duplicate tag keys, deleted objects and history input are rejected. There is no built-in input sorting or `.osc` refresh importer in the framework.

### Android build

The verified compilation target is **arm64-v8a, Android API 26, NDK r29**. Install the Rust target for the pinned toolchain, set the NDK location, and run:

```sh
rustup target add aarch64-linux-android --toolchain 1.99.0
export ANDROID_NDK_ROOT=/path/to/android-ndk-r29
scripts/build-android.sh
```

The local NDK used in this session is under `/home/mvexel/Documents/Codex/2026-10-03/can-x20/work/android/android-ndk-r29`. The script cross-compiles the native dependencies and Rust library. The phone package needs all three shared libraries from `target/android/arm64-v8a`: `libosm_framework.so`, `libosmx-mobile.so`, and `libc++_shared.so`. The C header is `include/osm_framework.h`. `scripts/build-android.sh` now builds **arm64-v8a and x86_64** (pass ABIs as arguments to limit it) into `target/android/<ABI>/`.

The Android library lives in `android/` (Gradle 9.8 wrapper, AGP 9.4.1, compileSdk 36, minSdk 26). The JDK is pinned in `mise.toml`, because the system Java 25 is a runtime only. The JNI exports are in `src/android.rs` and wrap the same C ABI, so the thread checks and panic containment are shared with iOS. The Kotlin API is `OsmStore` (open/importArea/get/query/close; JSON strings) and `OsmFrameworkException`. Run the instrumented tests with:

```sh
cd android && mise exec -- ./gradlew :osm-framework:connectedDebugAndroidTest
```

The local SDK is at `~/Android/Sdk`, with the AVD `osmfw-x86_64` (API 35).

On 2026-10-03 the instrumented tests (import, get, query, Unicode round-trip, malformed-query recovery, rejection of calls from another thread, closed store) passed on a **Pixel 8 (Android 17, arm64)** and the **x86_64 emulator**. iOS needs a Mac/Xcode build and runtime proof; access to a Mac was confirmed earlier, but that machine has not been inspected.

## Verification evidence

- **13 Rust integration tests pass**, including metadata preservation, Unicode tags, ordered references, missing dependencies, spatial budgets, pagination, cyclic relations, failed imports, successful replacement, duplicate/deleted input rejection and SliceOSM request validation.
- `cargo fmt --check` and `cargo clippy --all-targets -- -D warnings` are the required Rust checks.
- The backend C API smoke test forces `MDB_MAP_FULL` and checks that errors return without killing the process. Invalid arguments also return recoverable errors.
- A foreign runtime calls the Rust mobile C API through Python ctypes: import, open, lookup, query, malformed-query recovery, close and buffer freeing pass.
- The optional native CLI compatibility harness passes import, node/way/relation lookup, extract, PBF re-import and `.osc` update checks. Updates are tested for upstream compatibility but are not exposed by the framework API.
- A live SliceOSM request for a small Salt Lake City rectangle returned a **90,034-byte PBF**, with snapshot timestamp **2026-10-03T18:44:47Z**. The core imported **4,490 nodes, 527 ways and 53 relations** into a **1,900,544-byte database**, reopened it and found a café through `amenity=cafe`.

The live request and database are scratch evidence under the continuation chat's `work/live-smoke` directory. They are not a city benchmark or a stable test fixture. The first OSMExpress assessment's pinned-container probe is in that chat's `outputs/osmx-evaluation`; its binary image and the inspected source revision were separate evidence. The current tests compile the fork itself.

Native builds emit warnings from upstream/libosmium and the pinned S2 headers. The compilation succeeds. Those warnings have not been eliminated through broad upstream code changes.

### Lesson: native warnings are not noise

The first run on Android found a real bug. Every `get` returned not-found because the fork's recoverable `CHECK_LMDB` macro declared `int retval = (x)`, and call sites pass their own `retval`. The result is a self-initialized variable, which is undefined behavior. Linux happened to work. Clang `-O3` for Android dropped the success path. The fix is fork commit `978f265`. Linux tests cannot catch this class of bug, so the Android instrumented tests are its regression test.

## Limits and open work

**Spatial results are candidates.** OSMExpress indexes nodes in S2 cells and follows reverse references. A way crossing a box with both endpoints outside selected cells can be omitted, and a polygon containing the box without selected member nodes can also be omitted. Relation selection follows the same graph semantics. Do not advertise exact geometry intersection, complete spatial coverage, or automatic recursive extraction completeness.

**Tags are currently scanned.** Tag-only queries stream bounded pages instead of using a global tag index. Country-wide queries can be slow. Pagination limits returned results, not total scan work. The spatial candidate budget is per namespace.

**Memory budgets are partial.** The default map size is 1 GiB of virtual address space. Each of five sorters holds at most 65,536 pairs, totaling roughly 5 MiB for the pair arrays. PBF buffers, object transactions, merge state, JSON conversion and other allocations add memory. Peak resident memory and phone import latency have not been measured. Very small sorter budgets can create many merge runs. Country-scale import needs further work and measurement.

**The source format has limits.** Coordinates use OSMExpress/libosmium's 10⁻⁷-degree precision; arbitrary nanodegree PBF precision is not preserved. The unchanged OSMExpress schema encodes absent metadata fields as zero or empty values; it cannot recover their original presence. Legacy untagged nodes expose null metadata and their location version. New imports can retain available metadata, but cannot restore information already omitted by a downloaded extract.

**Maps and editing are pending.** There is no multipolygon assembly, vector tile generation, rendering source schema, MapLibre integration, café reference UI, opening-hours interpreter, edit journal, uploader, or conflict resolution. The SliceOSM module describes the job protocol; the platform still must submit requests, poll, download, manage cancellation and call staged import.

## Next implementation steps

1. ~~Minimal Android adapter on device.~~ Done 2026-10-03 (see above).
2. Measure a real city extract on that phone: download size, database size, peak memory, import time and query latency. Tune import and query budgets from those results.
3. Build the same native backend and Rust ABI on the available Mac, then prove Swift lookup on an iOS simulator/device.
4. Add area download state and background lifecycle around the SliceOSM protocol. Define cancellation, refresh and overlapping-area policies before implementing them.
5. Define geometry and rendering semantics, including incomplete references, relation cycles, multipolygons and a documented vector-tile schema. Integrate MapLibre Native.
6. Build the offline café acceptance scenario. Add edits and synchronization afterward as separate layers with their own data and failure contracts.

Keep the fork small and consider upstreaming general embedding fixes. Pin new backend revisions deliberately, rerun the compatibility and framework checks, and update this handoff when evidence changes a decision.

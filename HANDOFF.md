# Cantino handoff

Updated 2026-10-04. Scope and next steps are in [CLAUDE.md](CLAUDE.md). There are no external consumers. API, ABI and file formats
may change freely; preserve runtime recovery and the working examples.
Simplification is complete in 0.3.0; iOS reached parity on `ios/main`
(2026-10-04, see [parity](docs/guide/platform-parity.md)). Mission, module
direction and the next feature (basemap-only acquisition) are in CLAUDE.md
and [the modular-SDK assessment](docs/research/2026-10-04-modular-sdk-assessment.md).

## Architecture

The Rust core imports PBF or OSM XML snapshots into a read-only SQLite area.
Android wraps the shared [C ABI](include/cantino.h) through JNI and Kotlin;
iOS through Swift over `CCantino.xcframework` (`scripts/build-xcframework.sh`).
Shared by both through the core: the SliceOSM protocol, the PMTiles extract
engine, the area store (staging, roll-forward commit journal, recovery, area
lock: `src/area_storage.rs`) and the download failure table (`src/failure.rs`).

- Objects use compact blobs, interned tag keys/roles and delta-encoded way refs.
  `tag_index` and an integer R-tree support queries; bounds are candidates for
  ways and relations. [Schema](src/schema.rs), [encoding](src/encoding.rs).
- Imports stage, sync and atomically replace the data file. Downloads stage
  data, optional PMTiles and metadata, then publish under a per-area lock with
  a durable roll-forward journal (in the core). Cancellation is honored before
  its commit point; recovery completes a committed publication after process
  death and fails closed (I/O error, journal kept) when it cannot.
- Android runs downloads in WorkManager. iOS runs them in the app's process
  over URLSession; the request is stored, and the next `AreaManager` resumes
  an interrupted run with the same SliceOSM job.
- Stores and basemap engine handles belong to their creating thread.
  `AsyncOsmStore` owns a dedicated thread for coroutine callers. The café app
  serializes selecting, reopening and using its store after refresh.
- The core handles SliceOSM protocol and PMTiles planning/assembly. The
  adapters handle HTTP and scheduling. Retry policy and parallelism are internal.
- Returned values own their collections; C callers free returned buffers with
  `cantino_free`. Negative C status codes carry typed errors.

## Build, examples and verification

[Building from source](docs/guide/building.md) is the authoritative toolchain,
build, test and publication guide. Keep both Rust examples, the minimal Android
basemap app, both café apps (`android/cafe-app`, `ios/cafe-app`) and both
Inspector apps (`android/inspector-app`, `ios/inspector-app`;
[walkthrough](docs/guide/inspector-app.md)). The [quickstart](README.md#quickstart) and
[guide](docs/guide/README.md) describe the supported API.

The café acceptance flow is download → restart in airplane mode → basemap,
nearby cafés, filters and raw-object details → refresh/cancel. Automated device
runs use the [debug location override](docs/guide/cafe-app.md), never real GPS.
The opening-hours parser belongs to the example and uses the phone's time zone.

Verified on `ios/main`, 2026-10-04 (macOS host, Apple silicon): 93 Rust
tests, the Rust parity runner and the C ABI smoke test; 23 Android JVM tests;
40 instrumented tests on the Pixel 8 emulator (Android 17, arm64; four opt-in
tests skipped), including the Kotlin parity runner; both process-death
scenarios. 70 Swift SDK tests on macOS and the iPhone 18 Pro simulator
(iOS 27), including the Swift parity runner; 24 iOS café app tests. The iOS
café app downloaded downtown SLC live on the simulator and showed the same
136 cafés as Android; a cold launch afterwards made no network connections
(the simulator cannot be put in airplane mode). On a physical iPhone 15 Pro
Max (iOS 27), Martijn's acceptance run passed: a live 10×10 km SLC download
at his location (63.5 MB data, 871,902 nodes; 7.4 MB extracted basemap, z0-15,
32 range requests), a cold relaunch in airplane mode with map, cafés, filters
and details, and a force-quit during a download that resumed on relaunch.
Not yet run on this branch: the instrumented suites on a physical Pixel 8 and
the live opt-in tests.

0.3.0 (Android only) was verified on 2026-10-04 before the iOS merge: 39
instrumented tests per device on Pixel 8 and x86_64 emulator, both
process-death suites, a live SLC download and a cold airplane-mode launch.

## Storage evidence

SQLite replaced OSMExpress after comparing two prototypes. All recorded
measurements remain in [docs/bench](docs/bench/README.md) and the
[performance guide](docs/guide/performance.md). The deleted prototypes can be
reproduced from Git; the benchmark index gives the exact revision.

| Pixel 8, Salt Lake City, 2026-10-03 | OSMExpress | SQLite core through AAR |
| --- | --- | --- |
| Database | 338 MB | 128.5 MB |
| Import | 5.0 s | 8.2 s (5.7 s standalone) |
| Peak memory, including test runtime | 425 MB | 282 MB |
| Tag query p95, 175 cafés | 32,600 ms | 22 ms |
| Bbox + tag query p95 | 350 ms | 3.7 ms |

Import profiles reduced that area to 8.8 MB. Import memory grows with node
count; JNI/JSON dominates small query costs. Neither justifies a rewrite at
current measured sizes. Dated persona reviews and format proposals in
`docs/research` are research, not commitments.

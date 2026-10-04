# Cantino handoff

Updated 2026-10-04. Scope and next steps are in [CLAUDE.md](CLAUDE.md). There are no external consumers. API, ABI and file formats
may change freely; preserve runtime recovery and the working examples.
Simplification is complete in 0.3.0; iOS is next. No new feature backlog.

## Architecture

The Rust core imports PBF or OSM XML snapshots into a read-only SQLite area.
Android wraps the shared [C ABI](include/cantino.h) through JNI and Kotlin.
iOS groundwork exists on the unmerged `ios/main` branch; native Apple builds,
URLSession orchestration and a café app still need macOS validation.

- Objects use compact blobs, interned tag keys/roles and delta-encoded way refs.
  `tag_index` and an integer R-tree support queries; bounds are candidates for
  ways and relations. [Schema](src/schema.rs), [encoding](src/encoding.rs).
- Imports stage, sync and atomically replace the data file. Android downloads
  stage data, optional PMTiles and metadata, then publish under a per-area lock
  with a durable roll-forward journal. Cancellation is honored before its
  commit point; recovery completes a committed publication after process death.
- Stores and basemap engine handles belong to their creating thread.
  `AsyncOsmStore` owns a dedicated thread for coroutine callers. The café app
  serializes selecting, reopening and using its store after refresh.
- The core handles SliceOSM protocol and PMTiles planning/assembly. Android
  handles HTTP and WorkManager. Retry policy and parallelism are internal.
- Returned values own their collections; C callers free returned buffers with
  `cantino_free`. Negative C status codes carry typed errors.

## Build, examples and verification

[Building from source](docs/guide/building.md) is the authoritative toolchain,
build, test and publication guide. Keep both Rust examples, the minimal Android
basemap app and the café app. The [quickstart](README.md#quickstart) and
[guide](docs/guide/README.md) describe the supported API.

The café acceptance flow is download → restart in airplane mode → basemap,
nearby cafés, filters and raw-object details → refresh/cancel. Automated device
runs use the [debug location override](docs/guide/cafe-app.md), never real GPS.
The opening-hours parser belongs to the example and uses the phone's time zone.

Verified for 0.3.0 on 2026-10-04: 62 Rust tests and the C ABI smoke test;
23 JVM tests; 39 passing instrumented tests per device on Pixel 8 and x86_64
emulator (four opt-in tests skipped per device). Both process-death suites pass
with data, basemap and metadata recovery. Both Android apps, Rust examples and
the verbatim README Activity build. A live SLC download and cold airplane-mode
launch on the emulator displayed 136 cafés and the basemap; refresh and
cancellation passed device integration tests.

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

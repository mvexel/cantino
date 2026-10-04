# Changelog

Cantino is under heavy development. API, ABI and file
formats may change without compatibility layers. See the [guide](docs/guide/README.md).

## Unreleased

- **Inspector**, the second example app, for OSM developers and mappers
  (`android/inspector-app`, `ios/inspector-app`, feature-equivalent): a full
  import, a tag query bar (`k=v`, `k=*`, `!k`, ANDed, "this view only", load
  more, count) with validator-style checks, tap-anywhere inspection (bbox
  candidates and app-side hit testing against way geometry, "hit" vs "near"),
  an object navigator (references, missing references, ways using a node,
  osm.org link) and "about this area". App code only; no SDK change.
  `scripts/build-ios-inspector.sh`; [walkthrough](docs/guide/inspector-app.md).
- **Basemap-only areas:** `AreaManager.downloadBasemap(areaId, bbox,
  basemap)` on Android and iOS downloads a basemap without OSM data (no
  SliceOSM job, no import). `AreaInfo.dataFile` / `dataURL` and
  `AreaMetadata.report` are now nullable; an area is still replaced as a
  whole, so a basemap-only refresh removes earlier OSM data.
- C ABI: `cantino_area_commit` takes `CANTINO_AREA_PART_*` bits instead of
  `has_basemap`; `cantino_area_published` returns `"data": null` for a
  basemap-only area; the sidecar's `report` is null without data.

## 0.4.0 — 2026-10-04

iOS and Android at parity: the same SDK on both platforms, tested against the
same corpus and scenarios ([parity](docs/guide/platform-parity.md)).

- **Area store in the Rust core** (`src/area_storage.rs`, C ABI
  `cantino_area_*`): layout, staging, the roll-forward commit journal,
  recovery and the published-area read moved out of Kotlin so Android and
  iOS share one implementation. Keeps 0.3.0's fail-closed recovery (a damaged
  journal or failed rename is an I/O error, journal kept, never a mixed
  area). The per-area lock also takes an `flock` on `<areaId>.lock` across
  processes; readers read under the lock.
- **Failure classification in the core** (`cantino_classify_failure`), the
  retry table used by the Android download worker and the Swift adapter.
- `PmtilesInfo` exposes every `cantino_basemap_info` header field.
- Parity corpus (`tests/parity`): one call list with byte-for-byte expected
  output, run by Rust (`cargo test`), Kotlin (instrumented) and Swift runners.
- Swift package (`swift/`) mirroring the Kotlin API, shipped to Apple
  platforms as `CCantino.xcframework` (`scripts/build-xcframework.sh`):
  store API, import profiles, and `AreaManager` downloads (URLSession in the
  app's process; interrupted runs resume when the app next creates a
  manager). Tested on the iOS simulator and macOS.

## 0.3.0 — 2026-10-04

A smaller SDK with import profiles, simpler public APIs and verified offline
recovery. Both Android examples and both Rust examples remain supported.

### Breaking changes

- **Area format v2: re-import development datasets.** Removed unused reverse
  indexes. Existing area files are rejected; there is no migration layer.
- **Android ABIs: arm64-v8a and x86_64 only.** Removed armeabi-v7a.
- **C ABI:** failures return negative `CANTINO_ERROR_*` categories directly.
  Removed `cantino_last_error_code`; keep freeing returned error messages.
- **Kotlin:** constructible value models; public `AreaConfig` retains only the
  endpoint, HTTP timeouts, import options and foreground settings. Polling,
  retries and parallelism use internal defaults; old queued requests are not
  supported. Area lookup uses `publishedArea` / `loadPublishedArea`; removed
  their four file-only shortcuts.
- Removed SDK representative-point geometry and Protomaps build discovery.
  Marker placement and discovery live in the café example. Batch lookup and
  ordered way coordinates remain available.
- Unknown import options are rejected rather than silently ignored.

### Improvements

- Tag-filtered import profiles retain matching objects and their references;
  a recorded SLC POI import occupies 8.8 MB instead of 128.5 MB before this cleanup.
- Removing reverse indexes reduced the same full SLC dataset from **128.5 to
  102.3 MB (20.4%)**, with identical rows in retained tables. Peak desktop import
  RSS fell **19.9%**; timing results and caveats are in [the benchmark record](docs/bench/2026-10-04-slc-reverse-index-removal-desktop.json).
- Fixed café refresh/read and publication/read races. Failed publication
  recovery preserves the journal and reports storage failure instead of exposing
  mixed files. Download setup/recovery uses the same bounded storage retry policy.
- Cancellation checks use the worker's active coroutine context.
- Real process-death tests cover data, basemap and metadata together. Added
  focused concurrency, recovery and collection-ownership regression tests.
- Removed obsolete storage prototypes and compatibility machinery; consolidated
  build guidance and updated the guide and runnable quickstart.

### Verification

Rust checks and C ABI smoke, JVM tests, and instrumented tests passed on Pixel 8
and x86_64 emulator. Kill/restart scenarios passed on both devices. Both Android
apps, Rust examples and the verbatim quickstart build. The café demo downloaded
the SLC preset and displayed its map and 136 cafés after a cold offline launch.

## Development history

- **v0.2.0, 2026-10-03:** coroutine store, bbox helper, batch lookup and way
  coordinates, absence filters, run identity and typed errors; `lol.osm` namespace.
- **v0.1.0, 2026-10-03:** SQLite import/query core, Android bindings, resumable area
  orchestration, PMTiles extraction, foreground mode and café reference app.

Git retains the detailed changes and superseded API contracts.

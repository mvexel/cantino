# Changelog

Cantino is under development with no external consumers. API, ABI and file
formats may change without compatibility layers. See the [guide](docs/guide/README.md).

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

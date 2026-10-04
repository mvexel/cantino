# Changelog

All notable changes to Cantino (formerly osm-framework). Versions follow semantic versioning;
before 1.0 a minor version may break the API.

## Unreleased

### Changed (internal; no public Kotlin API change)

- **Area store in the Rust core.** The on-disk layout of downloaded areas,
  staging, the roll-forward commit journal, recovery after a kill and the
  reading of the published area (with its sidecar checks) moved from the
  Kotlin `AreaStorage` into `src/area_storage.rs`, so the iOS adapter will
  behave identically by construction. The on-disk format is unchanged:
  areas written by 0.2.0 are read and recovered as they are (tested against
  fixtures laid out as 0.2.0 wrote them). New C ABI: `cantino_area_*`.
- **Area lock across processes.** The per-area lock is now taken in the
  core: the in-process lock as before, plus an exclusive `flock` on a new,
  empty `cantino-areas/<areaId>.lock` (best effort), so readers in another
  process of the app also never see a half-published area. Readers now read
  the published area under the lock (0.2.0 released it before reading).
- **Failure classification in the core.** The table that maps HTTP
  statuses, network/storage I/O errors and native errors to retry behaviour
  and `FailureReason` moved from `SliceHttp`/`AreaDownloadWorker` into
  `src/failure.rs` (`cantino_classify_failure`); same classes and reasons
  as 0.2.0.
- The staging directory is fsynced before the commit journal is written (a
  power loss right after the commit point can no longer lose a staged file).


### Added

- **`AsyncOsmStore`**: a coroutine wrapper that owns one dedicated thread,
  opens the `OsmStore` on it and runs every call there (`suspend` `get`,
  `query`, `close`, plus `withStore { }` to reach any `OsmStore` method).
  Removes the thread-confinement trap of using an `OsmStore` from
  `Dispatchers.IO`. The café app uses it instead of its own worker.
- **`Bbox.around(lat, lon, widthKm, heightKm = widthKm)`**: a box around a
  point (flat-earth approximation). Latitude clamps to ±85, longitude to ±180;
  a box that would cross the antimeridian is cut at it, not wrapped.
- **armeabi-v7a**: the AAR now also packages `armeabi-v7a/libcantino.so`
  (32-bit ARM devices); `scripts/build-android.sh` builds it by default.
- **Way coordinates**: `OsmStore.wayCoordinates(wayId): List<Coordinate?>?`
  returns a way's node coordinates in order (repeats kept) in one native
  call; a null entry is a node outside the area, null means the way is not in
  the area. New `Coordinate` value (`latE7`/`lonE7`, `lat`/`lon`). C ABI:
  `cantino_way_coordinates` (flat `[lat_e7, lon_e7, ...]` array).
- **Representative point**: `OsmStore.representativePoint(id): Coordinate?`,
  computed in the core. A label/anchor point, not a guaranteed
  point-on-surface: node coordinate; closed way = mean of its distinct
  in-area vertices; open way = point at half its in-area polyline length;
  relation = mean of its distinct in-area members' points (nested up to 8
  levels, cycles skipped). C ABI: `cantino_representative_point`. The café
  app uses it instead of its own point code.
- **Batch get**: `OsmStore.get(ids: List<OsmId>): List<OsmObject?>` looks up
  up to `OsmStore.MAX_BATCH` (10 000) objects in one JNI crossing, in input
  order with null for missing. C ABI: `cantino_get_many`.
- **`TagFilter.NotExists(key)`**: "tag absent" as a post-check on another
  filter's candidates (e.g. amenities without `opening_hours`). A query whose
  only filters are `NotExists` and that has no bbox throws
  `CantinoException.InvalidArgument`. Wire form `{"NotExists":"key"}`. Source compatibility:
  an exhaustive `when` over the sealed `TagFilter` needs a new branch.
- `AreaState.runId: UUID?`: the WorkManager ID of the run a state belongs to
  (the `UUID` `AreaManager.download()` returns); null only for `Idle`. Wait for
  your own download with `state(id).first { it.runId == runId && it.isTerminal }`.
- `AreaState.isTerminal`: true for `Ready`, `Failed` and `Cancelled`.
- Suspend, main-safe variants of the blocking disk reads:
  `AreaManager.loadDataFile`, `loadBasemapFile`, `loadPublishedArea`
  (`Dispatchers.IO`).
- **Typed errors**: `CantinoException` subtypes
  `CantinoException.InvalidArgument` (bad query, bbox, ID, batch size, too
  many candidates), `InvalidFile` (not an area, old format version, corrupt
  PBF/XML input, bad PMTiles), `Io` (missing file, disk full, I/O) and
  `WrongThread` (a store used off its owner thread). The native layer throws
  them directly from the category the core assigns; nothing parses messages.
  Every KDoc names the subtypes a method throws. The AAR ships consumer R8
  rules keeping them (they are thrown from JNI by name).
- **`AreaState.Failed.reason: FailureReason`**: `NETWORK`, `SERVER`,
  `INVALID_REQUEST`, `STORAGE`, `INVALID_DATA`, and `UNKNOWN` (an unexpected
  error in the worker, or a failure recorded by 0.1, which stored no reason).
  Filled for every failure path of the download worker; `message` and
  `retryable` are unchanged.
- C ABI: `cantino_last_error_code()` returns the `CANTINO_ERROR_*` category
  (`INVALID_ARGUMENT` 1, `INVALID_FILE` 2, `IO` 3, `WRONG_THREAD` 4,
  `INTERNAL` 5) of the last failed call on the calling thread, errno-style.
  Backward compatible: no signature or return value changed, `-1` still
  means error. Rust: `ErrorKind` and `Error::kind()`, an exhaustive mapping
  of every `Error` variant and SQLite result code.

### Changed

- kotlinx-coroutines (`kotlinx-coroutines-android:1.10.2`) is now an `api`
  dependency (POM compile scope): apps no longer declare it themselves.
- Quickstart and guide use the `runId` match instead of the `dropWhile` workaround.
- Docs: the guide is published as HTML on the Pages site under `/guide/`
  (`scripts/publish-pages.sh`, needs pandoc); new pages for SliceOSM in
  production, opening hours, editing apps, and maintenance/roadmap; the
  defaults of `Query` and `AreaConfig` are stated in KDoc (Dokka does not
  render non-literal defaults).

### Breaking

- **Package and Maven coordinates changed**: `io.github.mvexel.cantino` is now
  `lol.osm.cantino` and `io.github.mvexel:cantino` is now `lol.osm:cantino`
  (the Android namespace follows; the 0.1.0 artifacts stay where they are).
  Migrate: replace imports `io.github.mvexel.cantino.*` with
  `lol.osm.cantino.*`; use the dependency `lol.osm:cantino:0.2.0`.
- `AreaState.Submitting`, `Importing` and `Cancelled` are classes carrying
  `runId`, no longer objects: write `is AreaState.Cancelled`, not
  `AreaState.Cancelled`. `equals`/`hashCode`/`toString` of every state
  subtype include `runId`, so states of different runs are never equal.
- `CantinoException` is a sealed class: it can no longer be constructed
  directly (construct a subtype). `catch (e: CantinoException)` keeps
  working; an exhaustive `when` over it needs the four subtype branches.
- Internal errors of the native core (bugs, caught panics) are
  `IllegalStateException("Cantino internal error ...")` instead of
  `CantinoException`.
- `AreaState.Failed` carries `reason`; its `equals`/`hashCode`/`toString`
  include it.
- A full disk while downloading or importing is reported as
  `reason = STORAGE` (it used to look like a network error) and stays
  retryable: WorkManager retries once storage is no longer low.
- Rust: `Error` gains `Format` (not a Cantino area, another
  `FORMAT_VERSION`, an unclustered PMTiles archive or unsupported internal
  compression, all formerly `Invalid`), `WrongThread` (formerly `Invalid`)
  and `Internal`; `Corrupt` displays as "corrupt file". A missing or
  unreadable PBF input is `Error::Io` (formerly `Input`).

## 0.1.0 — first release

Android library `io.github.mvexel:cantino:0.1.0` (AAR, minSdk 26,
arm64-v8a and x86_64) over a Rust core with a C ABI (`include/cantino.h`, prefix `cantino_`).

- **Offline OSM store** (`OsmStore`): import an OSM PBF or XML extract into a
  SQLite area database, published atomically (`open` and `importArea` take
  `File`s or path strings); lookup by ID; index-backed tag
  queries (`TagFilter.Exists` / `Equals`, ANDed); bbox spatial candidates
  (tagged nodes exact, ways and relations by bounding box); keyset pagination;
  candidate cap that fails instead of truncating. Raw tags, ordered way node
  and relation member references, per-object metadata.
- **Area downloads** (`AreaManager`): download an app-named area for a bbox
  from SliceOSM in WorkManager (survives process death, waits for network,
  inline and backoff retries, resumes the submitted job), import on the
  device, and publish data, basemap and metadata together through a
  roll-forward commit journal. A failed or cancelled refresh never touches
  the previously published area. Progress as `Flow<AreaState>`.
- **Offline basemap** (`BasemapSource`): opt-in per download, either a
  ready-made PMTiles file (`Url`, validated as PMTiles v3) or an on-device
  extract of the area's bbox from a remote PMTiles archive with HTTP range
  requests (`Extract`, byte-identical to go-pmtiles' extract). Published next
  to the data; `AreaInfo.pmtilesUrl` plugs into MapLibre Native.
  `ProtomapsBuilds.latestUrl()` finds a daily Protomaps build for demos;
  `PmtilesInfo.read()` validates and describes a local PMTiles file.
- **Foreground mode** (`AreaConfig.foreground`, opt-in): runs large downloads
  as a `dataSync` foreground service with a progress notification, beyond
  WorkManager's 10-minute limit. The library manifest does **not** declare
  the foreground-service entries (they make Google Play ask for a
  foreground-service declaration), so an app that enables foreground mode
  adds them to its own manifest:

  ```xml
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
  <application>
      <service
          android:name="androidx.work.impl.foreground.SystemForegroundService"
          android:foregroundServiceType="dataSync"
          tools:node="merge" />
  </application>
  ```

  Without them a foreground-mode run logs a warning (tag
  `AreaDownloadWorker`) and runs as ordinary background work.
- **Manifest**: the library adds `INTERNET` and `ACCESS_NETWORK_STATE`;
  WorkManager adds `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED` and
  `FOREGROUND_SERVICE` (no service type, so no Play declaration). Apps that
  never download may remove them with `tools:node="remove"`.
- **Area metadata** (`AreaInfo.metadata`): SliceOSM snapshot timestamp as
  `java.time.Instant` (data age; null if absent or not RFC 3339, the
  sidecar file keeps the server's string), bbox, import report, basemap
  source and transfer statistics. `AreaState.Ready` carries only the
  published `AreaInfo`.
- Kotlin explicit API mode: the public surface is deliberate and documented
  (KDoc on every public symbol); `Cantino.VERSION`; errors are `CantinoException`.
- **Compatibility policy** (semver; before 1.0 a minor version may break the
  API, a patch version does not):
  - Types Cantino *returns* are regular classes with value equality, not
    data classes, and apps cannot construct them (internal constructors):
    `AreaInfo`, `AreaMetadata`, `BasemapMetadata`, `ImportReport`,
    `ObjectCounts`, `PmtilesInfo`, the `AreaState` subtypes,
    `OsmObject.Node`/`Way`/`Relation`, `OsmObject.Member`, `ObjectMetadata`.
    New properties can be added to them in a minor version without breaking
    compiled apps. There is no `copy()` or destructuring on them.
  - Types apps *construct* stay data classes for `copy()` convenience:
    `Query`, `Bbox`, `TagFilter.*`, `OsmId`, `ImportOptions`, `AreaConfig`,
    `ForegroundConfig`, `BasemapSource.*`. Their constructors (with default
    arguments) and properties are covered by the policy above; their
    generated `copy()` and `componentN()` are **not** covered before 1.0:
    a new property may change their signatures, so apps using them should
    expect to recompile on a minor upgrade. Use named arguments.
- **Area file format**: SQLite `application_id` 0x434E544E ("CNTN"),
  `user_version` (FORMAT_VERSION) 1. Pre-release builds wrote "OSMF"
  (0x4F534D46); those files are rejected and must be re-imported.
- Café reference app (`android/cafe-app`) demonstrating the full offline flow
  in airplane mode.

- **Distribution and docs**: static Maven repository at
  `https://mvexel.github.io/cantino/maven` (POM with license, developer and
  SCM), API reference (Dokka) at `https://mvexel.github.io/cantino/api/`,
  guide in `docs/guide/`. `AreaManager.state()` returns a `Flow` but
  kotlinx-coroutines is not an API dependency in 0.1.0: apps declare it
  themselves (see the README's Install section; fixed in 0.2.0).

Not in this release: iOS adapter, edits/upload, incremental refresh,
overlapping areas, byte-range resume of downloads.

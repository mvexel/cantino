# Cantino Swift adapter

SwiftPM package `Cantino`: the Swift API over the Rust core's C ABI
([`include/cantino.h`](../include/cantino.h)). It mirrors the Kotlin API in
[`android/cantino`](../android/cantino) (`OsmStore`, `AsyncOsmStore`, the
models, `AreaManager`), which is the reference: same names, same semantics,
same defaults, except for the deviations listed below.

**Status: store API and area downloads built and tested on iOS (simulator)
and macOS; the store API also on Linux.** Downloads run in the app's process
(URLSession); an interrupted download resumes when the app next creates an
`AreaManager` (see "Area downloads").

```swift
import Cantino

try OsmStore.importArea(input: "snapshot.osm.pbf", destination: "area.sqlite")

// Synchronous, confined to the opening thread:
let store = try OsmStore.open("area.sqlite")
let cafes = try store.query(Query(tags: [.equals("amenity", "cafe")],
                                  bbox: try Bbox.around(lat: 40.76, lon: -111.89, widthKm: 2)))
try store.close()

// From async code, any task:
let shared = try await AsyncOsmStore.open("area.sqlite")
let cafe = try await shared.get(OsmId(.node, 2))
try await shared.close()

// Download (and later refresh) an offline area with its basemap:
let areas = AreaManager()
let run = try areas.download(areaId: "home", bbox: bbox,
                             basemap: try .extract(planetUrl: "https://build.protomaps.com/20261001.pmtiles"))
for await state in try areas.state(areaId: "home") where state.runId == run && state.isTerminal {
    if case .ready(_, let area) = state { /* open area.dataURL, show area.pmtilesURL */ }
    break
}
```

## Layout

```text
Package.swift                 targets, Linux link flags (see below)
Sources/CCantino/             Linux only: module.modulemap -> ../../../include/cantino.h (Apple: the xcframework)
Sources/Cantino/
  OsmStore.swift              OsmStore: open, importArea, get, get(batch), query, wayCoordinates, close
  AsyncOsmStore.swift         AsyncOsmStore actor + StoreBox
  OwnerThread.swift           the dedicated thread AsyncOsmStore runs the store on
  Models.swift                OsmKind, OsmId, OsmObject (+ Node/Way/Relation/Member), ObjectMetadata,
                              Coordinate, Bbox (+ around), TagFilter, Query, ImportOptions, ImportReport, ObjectCounts
  Errors.swift                CantinoError, CantinoStateError, C call plumbing (buffer ownership, error mapping)
  Wire.swift                  Codable decoding of the core's JSON
  SliceProtocol.swift         internal SliceProtocol: jobRequest, job, progress (cantino_slice_*), as Kotlin's
  Basemap.swift               public PmtilesInfo.read; internal BasemapPlan / BasemapAssembler (sans-IO extract engine)
  AreaManager.swift           public AreaManager; the per-directory Registry (runs, states), DownloadRun (one run,
                              its attempts and backoff), DownloadFiles (request, checkpoint, partial files)
  AreaModels.swift            public AreaConfig, BasemapSource, BasemapPhase, AreaInfo, AreaMetadata, BasemapMetadata,
                              BasemapKind, FailureReason, AreaState
  AreaHTTP.swift              URLSession HTTP (SliceOSM, downloads, range requests), DownloadFailure, inline retry, tuning
  BasemapExtract.swift        internal extract driver: HTTP ranges in parallel, engine on its own thread
  AreaStorage.swift           internal AreaStorage (cantino_area_*: layout, staging, commit with hook, recover, published)
  Failures.swift              internal Failures: download failure classification (cantino_classify_failure)
  JSONValue.swift             deterministic JSON writer for requests and the parity canonical form
  Canonical.swift             parity corpus canonical form and outcome envelope (tests/parity/README.md)
Tests/CantinoTests/           ports of OsmStoreTest, AsyncOsmStoreTest, BboxAroundTest, ImportProfileTest,
                              AreaManagerTest (FakeSlice: an in-process SliceOSM as a URLProtocol);
                              canonical, basemap, area store and failure tests; ParityTests (tests/parity)
```

## Build and test

One command, from the repository root. On macOS it builds the xcframework
and runs Xcode's Swift natively; on Linux it needs Docker:

```sh
scripts/swift-test.sh                            # build the core, then swift test (native on macOS, swift:6.4 elsewhere)
scripts/swift-test.sh --filter Bbox              # extra arguments go to swift test
SIMULATOR="iPhone 18 Pro" scripts/swift-test.sh  # macOS: the same tests on an iOS simulator
```

**Apple platforms: the xcframework.** `scripts/build-xcframework.sh`
builds `Artifacts/CCantino.xcframework` (git-ignored): the Rust core as a
static library for iOS device (arm64), iOS simulator (arm64) and macOS
(arm64), each slice with `cantino.h` and a module map declaring
`CCantino`. On Apple platforms `Package.swift` uses it as the binary
target `CCantino`, so the same package serves `swift test` on the Mac and
apps or `xcodebuild test` on iOS. Deployment floors (iOS 15, macOS 12) are
passed to rustc and to the C compiler building SQLite.

**Linux.** By hand, the script does:

```sh
cargo build --release --lib
mkdir -p target/swift && cp target/release/libcantino.a target/swift/libcantino_core.a
docker run --rm --user "$(id -u):$(id -g)" -e HOME=/tmp -v "$PWD":/work -w /work/swift \
    swift:6.4 swift test --scratch-path /work/target/swift-build
```

`Package.swift` links the staticlib with `-L <repo>/target/swift
-lcantino_core -lm -ldl -lpthread` through a system library target whose
module map points at the repository's `include/cantino.h`. Only
`libcantino.a` is copied, because `target/release/` also holds
`libcantino.so`, which the linker would pick over the `.a`.
`CANTINO_LIB_DIR` overrides the directory. `-lm` is for the bundled
SQLite; `-ldl` and `-lpthread` for Rust std (stubs on glibc 2.34+).

The core is built on the host with the pinned Rust 1.99.0 and linked in
the container, so the container's glibc must be at least the host's.
`swift:6.4` (Ubuntu 26.04, glibc 2.43) matches the development host. Set
`SWIFT_IMAGE` for another image.

## Deviations from the Kotlin API

| Kotlin | Swift | Why |
| --- | --- | --- |
| `OsmKind.NODE` / `WAY` / `RELATION` | `.node` / `.way` / `.relation` (raw value = ABI kind code) | Swift naming |
| `sealed interface OsmObject` with `Node`/`Way`/`Relation` classes; `obj as OsmObject.Node` | `enum OsmObject { case node(Node), way(Way), relation(Relation) }` with `id`/`tags`/`metadata` on the enum and `.node`/`.way`/`.relation` optional accessors | Exhaustive `switch`, value semantics |
| `TagFilter.Equals("k","v")` | `.equals("k", "v")`, `.exists("k")`, `.notExists("k")` | Swift naming |
| `query.copy(after = id)` | `var next = query; next.after = id` (`Query` has `var` properties) | Structs are values; no `copy` needed |
| `OsmStore.MAX_BATCH` | `OsmStore.maxBatch` | Swift naming |
| `open(path: String)` / `open(file: File)` | `open(_ path: String)` / `open(_ url: URL)` | `URL` is Swift's file type; a non-file URL throws `.invalidArgument` |
| `importArea(input, destination, options)` | `importArea(input:destination:options:)` (String or URL overloads), `@discardableResult` | Argument labels |
| `CantinoException` subtypes `InvalidArgument`/`InvalidFile`/`Io`/`WrongThread` | `enum CantinoError: Error { case invalidArgument(String), invalidFile(String), io(String), wrongThread(String) }` | Swift errors are enums |
| `IllegalStateException` (closed store, core internal error) | `enum CantinoStateError: Error { case closed(String), internalError(String) }` | A catchable error, not `preconditionFailure`: like Kotlin, a stray call on a closed store must not crash the app, and tests can assert on it. Separate type so `switch` over `CantinoError` stays about the domain. Undecodable core output and unknown error categories are `internalError` too |
| methods throw specific exceptions | methods are untyped `throws` (they can throw either error type); `Bbox.around` is `throws(CantinoError)` | Two error types |
| `Bbox.around(...)` throws `IllegalArgumentException` | throws `CantinoError.invalidArgument`; `heightKm: Double? = nil` means "same as width" | Swift preconditions crash and are untestable; Swift defaults cannot refer to another parameter. Math identical (111.32 km/°, cos of the latitude clamped to ±85, same clamping) |
| non-finite `Bbox` edge: `JSONException` from org.json | `CantinoError.invalidArgument`, before the call | JSON has no NaN; keep it a domain error |
| `ObjectMetadata` etc. are classes with internal constructors | structs with internal initialisers | Same intent (read-only from apps, fields can be added); value types are idiomatic |
| `Int` e7 / `Long` IDs | `Int32` e7 coordinates and `locationVersion`, `Int64` IDs, metadata, counts, bytes; `Int` for `limit`/`maxCandidates`/`cacheMiB` | Same widths as Kotlin and the core |
| `OsmStore` (no finalizer) | `OsmStore` is a non-`Sendable` class; `deinit` closes it best-effort (only works on the owner thread; elsewhere the handle leaks, as in Kotlin) | Compile-time hint for the confinement, plus a safety net |
| `AsyncOsmStore` (suspend functions on a single-thread executor) | `actor AsyncOsmStore` that runs the store on its own `OwnerThread` | An actor's executor is not one fixed thread, and the core confines handles to one OS thread. A custom actor executor would need iOS 17 / macOS 14 |
| `withStore(block: (OsmStore) -> T)` | `withStore(_: @Sendable (OsmStore) throws -> T) async throws -> T` with `T: Sendable` | Block and result cross between the task and the owner thread |
| `AsyncOsmStore.open` checks `ensureActive()` after opening | throws `CancellationError` (store closed again) if the task was cancelled while opening | Swift cancellation |
| dropped `AsyncOsmStore` leaks its thread and connection | `deinit` queues a close and stops the thread | Safety net; still call `close()` |
| `PmtilesInfo` (class; zooms, bounds, counts, file size) | `struct PmtilesInfo` with every field `cantino_basemap_info` reports: also `specVersion`, `center` (`lon`, `lat`, `zoom`), `tileType`, `tileCompression` (PMTiles codes), `clustered` | The parity corpus compares the full C ABI object (tests/parity/README.md asks the platform types to grow them); value type |
| `PmtilesInfo.read(File)` | `PmtilesInfo.read(_ path: String)` / `read(_ url: URL)` (non-file URL: `.invalidArgument`) | As `open` |
| `internal object SliceProtocol` (data classes `JobRequest`, `Job`, `Progress`) | `internal enum SliceProtocol` with structs of the same names and fields; `jobRequest(base:bbox:name:)`, `job(base:response:)`, `progress(status:)` | Argument labels. Internal, as in Kotlin |
| `BasemapExtract` drives `NativeBridge.basemapPlan*` / `basemapAsm*` (raw JSON strings, `Long` handles) | internal `BasemapPlan` / `BasemapAssembler` classes (thread-confined, `deinit` frees best-effort) with typed `ByteRange`, `BasemapStep` (`.fetch`/`.wait`/`.tilesReady`), `TilePlan`, `BasemapProgress`; `BasemapExtract` drives them on an `OwnerThread` | Typed wrappers over the same engine |
| `CantinoException.Internal` / `IllegalStateException` for an internal core error | `CantinoStateError.internalError` (see above) | The parity canonical form maps it to code 5, `internal`, like every other runner |
| `internal class AreaStorage(areas: File, downloads: File)`; `requireValidAreaId` throws `IllegalArgumentException` | `internal struct AreaStorage(root:)` (the caller picks the root; the core owns layout, staging, commit, recovery, the area lock and `published`); `AreaStorage.validate(areaId:)` throws `CantinoError.invalidArgument`. Methods: `layout(areaId:workId:)` (`String?` raw, or `UUID`), `dataFile`, `basemapFile`, `prepareStaging`, `discardStaging`, `writeStagedMetadata`, `commit`, `recover`, `published` | The same role as Kotlin's class minus what is Android-specific: no download scratch directory, no WorkManager checkpoint (the iOS area manager owns its own). Paths are `String`s (as `OsmStore.open`), work IDs `UUID`s |
| `commit(areaId, workId, hasBasemap, beforeCommit: () -> Unit)` with `AreaTestHooks.afterCommitPoint` | `commit(areaId:workId:hasBasemap:beforeCommit:afterCommitPoint:)`, both closures (`beforeCommit` may throw) | The closures reach the core through the C callback and a context pointer; a throw from `beforeCommit` aborts with nothing changed and is rethrown (Swift errors cannot cross the C frame, so the box keeps it). `afterCommitPoint` is a parameter, not a global, for tests that stand in for a kill. Neither may call `AreaStorage` for the same area |
| `AreaStorage.recover` / `published` throw `IOException` | throw `CantinoError.io` | Same fail-closed rule (a pending commit that cannot finish is never read as a mixed area) |
| WorkManager: downloads survive process death, wait for network and storage, retry with backoff | `AreaManager` runs downloads as tasks in the app's process; the request is stored before `download` returns, and the first `AreaManager` created for the directory in a new process resumes it (same run ID and SliceOSM job; a run that died after its commit point succeeds at once). Retries: inline, then up to 5 attempts with exponential backoff (`queued` in between); no network/storage constraints | Decided 2026-10-04: no background URLSession. Tile ranges do not suit it, and in-process runs keep one code path |
| `AreaState` sealed interface; finished work kept ~1 day by WorkManager | `enum AreaState` with the same cases (`idle`, `queued`, `submitting`, `slicing`, `downloading`, `importing`, `basemap`, `ready`, `failed`, `cancelled`), `runId`, `isTerminal`; final states live in memory only (after a restart: `idle` with the published area) | No scheduler database |
| `state(areaId): Flow<AreaState>` | `state(areaId:) throws -> AsyncStream<AreaState>` | Swift concurrency |
| `AreaConfig(sliceBaseUrl, connectTimeoutMillis, readTimeoutMillis, importOptions, foreground)` | `AreaConfig(sliceBaseUrl:timeout:importOptions:)`; no foreground mode | URLSession has one idle timeout; iOS has no foreground services |
| `BasemapSource` sealed interface (`None`, `Url(url)`, `Extract(planetUrl, maxZoom, overfetch)`), constructors throw | `struct BasemapSource`: `.none`, `try .url(_:)`, `try .extract(planetUrl:maxZoom:overfetch:)` | Enum cases cannot validate; same checks, `CantinoError.invalidArgument` |
| `AreaInfo(areaId, dataFile: File, metadata, basemapFile)`, `pmtilesUrl` | `AreaInfo(areaId:dataURL:metadata:basemapURL:)`, `pmtilesURL` | File URLs |
| `AreaMetadata.snapshotTimestamp: Instant?` | `Date?` (written back as Kotlin's `Instant.toString()`) | Foundation |
| `download`/`cancel` throw `IllegalArgumentException` for a bad area ID | throw `CantinoError.invalidArgument` (and `download` `.io` if the request cannot be stored) | One error type for the domain |
| `AreaMetadata.toJson` writes org.json key order | `AreaMetadata.json` / `writeStagedMetadata` write keys sorted (`JSONValue.canonicalString`) | The core validates the sidecar and writes it verbatim; every reader parses it, so key order is not part of the format |
| `Failures.http/io/native` return `Classified(kind, reason)` | `Failures.http(_:context:)`, `io(_:)`, `native(_:context:)`, `native(code:context:)`, all over `Failures.classify(_ input: Input)`; typed contexts (`HTTPContext`, `IOContext`, `NativeContext`); `Classified` also carries `inlineRetry` / `schedulerRetry` | The parity corpus compares the whole classification the core returns |

Thread confinement itself is the core's: every `cantino_*` store call
compares the calling OS thread's Rust `ThreadId` with the opener's and
fails with `CANTINO_ERROR_WRONG_THREAD` (handle untouched), which this
adapter maps to `CantinoError.wrongThread`. The adapter adds no check of
its own, so it cannot disagree with the core.

## Parity

`Tests/CantinoTests/ParityTests.swift` replays
[`tests/parity/calls.json`](../tests/parity/calls.json) through the Swift
API and compares its canonical output byte for byte with
`tests/parity/expected.json` (written by the Rust runner through the C ABI),
skipping the calls marked `abi_only`; a mismatch fails with the call id and
both lines. The contract (ops, outcome envelope, canonical JSON) is
[`tests/parity/README.md`](../tests/parity/README.md); `Canonical.swift`
and `JSONValue.swift` implement it. Store ops and `basemap_info` go through
the public API; the slice and basemap-engine ops through the internal
`SliceProtocol` and `BasemapPlan` / `BasemapAssembler`, and the area and
failure ops (`area_validate_id`, `area_layout`, `area_published`,
`area_recover`, `classify_failure`) through the internal `AreaStorage` and
`Failures` (as the Kotlin runner uses its internals for them). The area ops
run on a copy of `tests/fixtures/area-storage-0.2.0/` in the scratch
directory (recovery mutates it). Current result: 219 calls run, 24
`abi_only` skipped, the same as the Kotlin runner. A mismatch is an adapter bug: fix the
adapter, never `expected.json`.

## Area downloads

`download(areaId:bbox:name:basemap:)` fetches OSM data (and optionally a
basemap); `downloadBasemap(areaId:bbox:basemap:)` fetches only a basemap
(`AreaInfo.dataURL` nil), as on Android.

`AreaManager(directory:config:)` keeps published areas in
`<directory>/cantino-areas` (the core's layout, identical to Android's) and
requests, checkpoints and partial downloads in
`<directory>/cantino-area-downloads` (excluded from backup). The default
directory is Application Support. Runs, states and subscribers are shared
by every manager for the same directory in the process. Everything that
must agree with Android is the core's: the SliceOSM protocol, the extract
engine, staging, the commit journal, recovery, the area lock and the
failure table.

## Not in this slice

- A real kill test on a device (Android has `scripts/kill-test-android.sh`):
  the Swift tests recreate a killed process's on-disk state instead.
- `ProtomapsBuilds` (basemap build discovery) lives in the café example on
  Android and in the iOS café example (`ios/cafe-app`).

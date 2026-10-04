# Cantino Swift adapter

SwiftPM package `Cantino`: the Swift store API over the Rust core's C ABI
([`include/cantino.h`](../include/cantino.h)). It mirrors the Kotlin store
API in [`android/cantino`](../android/cantino) (`OsmStore`, `AsyncOsmStore`,
the models), which is the reference: same names, same semantics, same
defaults, except for the deviations listed below.

**Status: platform-neutral slice, built and tested on Linux.** No Xcode or
iOS SDK is involved yet. The iOS parts (an xcframework binary target,
background `URLSession` downloads, `BGTaskScheduler`, the area manager)
come in a later slice on macOS CI. Nothing in `Sources/` uses an
Apple-only API, so the package is ready to be built for iOS once the binary
target exists; Apple-only code will go behind `#if os(iOS)` /
`#if canImport(Darwin)`.

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
```

## Layout

```text
Package.swift                 targets, Linux link flags (see below)
Sources/CCantino/             module.modulemap -> ../../../include/cantino.h (the header is not copied)
Sources/Cantino/
  OsmStore.swift              OsmStore: open, importArea, get, get(batch), query, wayCoordinates, representativePoint, close
  AsyncOsmStore.swift         AsyncOsmStore actor + StoreBox
  OwnerThread.swift           the dedicated thread AsyncOsmStore runs the store on
  Models.swift                OsmKind, OsmId, OsmObject (+ Node/Way/Relation/Member), ObjectMetadata,
                              Coordinate, Bbox (+ around), TagFilter, Query, ImportOptions, ImportReport, ObjectCounts
  Errors.swift                CantinoError, CantinoStateError, C call plumbing (buffer ownership, error mapping)
  Wire.swift                  Codable decoding of the core's JSON
  JSONValue.swift             deterministic JSON writer for requests and the canonical form
  Canonical.swift             canonical parity rendering (entry point for the parity runner)
Tests/CantinoTests/           ports of OsmStoreTest, AsyncOsmStoreTest, BboxAroundTest; canonical tests; parity stub
```

## Build and test

One command, from the repository root (needs cargo and Docker, no Swift on
the host):

```sh
scripts/swift-test.sh                 # cargo build --release, then swift test in swift:6.4
scripts/swift-test.sh --filter Bbox   # extra arguments go to swift test
```

What it does, if you want to do it by hand:

```sh
cargo build --release --lib
mkdir -p target/swift && cp target/release/libcantino.a target/swift/
docker run --rm --user "$(id -u):$(id -g)" -e HOME=/tmp -v "$PWD":/work -w /work/swift \
    swift:6.4 swift test --scratch-path /work/target/swift-build
```

**Linking (Linux).** `Package.swift` links the Rust staticlib with
`-L <repo>/target/swift -lcantino -lm -ldl -lpthread` (Linux only, via
`linkerSettings`). Only `libcantino.a` is copied to `target/swift/`
because `target/release/` also holds `libcantino.so`, which the linker
would pick over the `.a`. `CANTINO_LIB_DIR` overrides the directory. The
result has the core linked in statically: the test binary does not need
`libcantino.so` at run time. `-lm` is for the bundled SQLite; `-ldl` and
`-lpthread` for Rust std (stubs on glibc 2.34+, harmless).

**Docker image and glibc.** The core is built on the host with the pinned
Rust 1.99.0 and linked in the container, so the container's glibc must be
at least the host's. `swift:6.4` (Swift 6.4, Ubuntu 26.04, glibc 2.43)
matches the development host. Set `SWIFT_IMAGE` for another image; on a
host newer than the image, build the core inside a container with the
image's glibc instead.

`unsafeFlags` is fine for this root package and for local path
dependencies. A remote package dependency will get the xcframework binary
target instead (later slice).

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

Thread confinement itself is the core's: every `cantino_*` store call
compares the calling OS thread's Rust `ThreadId` with the opener's and
fails with `CANTINO_ERROR_WRONG_THREAD` (handle untouched), which this
adapter maps to `CantinoError.wrongThread`. The adapter adds no check of
its own, so it cannot disagree with the core.

## Not in this slice

- Area download lifecycle (`AreaManager`, `AreaConfig`, `AreaState`,
  SliceOSM HTTP), offline basemap (`BasemapExtract`, `PmtilesInfo`), and
  `Cantino.VERSION`: they need iOS background transfer and scheduling.
  The pure protocol helpers in the C ABI (`cantino_slice_*`,
  `cantino_basemap_*`) are ready for them.
- iOS/macOS build: xcframework binary target and simulator tests (macOS CI).
- Parity runner: `Tests/CantinoTests/ParityTests.swift` is the hook (skipped
  until `tests/parity/calls.json` exists, then it fails until implemented);
  `Canonical.swift` is the rendering it will use.
- `scripts/check.sh` does not run the Swift tests (Docker dependency).

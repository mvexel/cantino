# Repository Guidelines

## Project Overview
Cantino is an offline OpenStreetMap SDK: a Rust core over a read-only SQLite store, with thin Kotlin (Android AAR) and Swift (iOS xcframework + SwiftPM) adapters at parity (0.4.0). It imports a SliceOSM PBF/OSM XML extract into a SQLite area file, then offers lookup by ID, tag queries, bbox candidate queries and dependency reporting. It also does PMTiles basemap extraction (sans-IO).

- **Parity rule** (freeze lifted in 0.4.0): a feature lands on both platforms, with tests on both (`docs/guide/platform-parity.md`). No compatibility shims; API, ABI and file formats may change freely.
- **Out of scope**: routing, overlapping areas, `.osc` refresh, vector tile generation, café policy in core. Edits/upload need a scope change (CLAUDE.md). Spatial results are *candidates*; never advertise exact intersection.
- Outward steps (push, tag, publish) are done by Martijn; prepare and print the commands only.
- Never use "OpenStreetMap" as part of the product name. `work/` and `spike/` are parked; ignore. `build/` is generated.
- Background: `CLAUDE.md`, `HANDOFF.md`.

## Architecture & Data Flow
```
PBF/XML → input.rs (readers → Sink) → import.rs Importer → build.sqlite (staging)
        → VACUUM INTO area.sqlite → verify_counts → fsync + atomic rename
area.sqlite → store.rs Store (read-only) → mobile_api.rs (C ABI) ─┬→ android.rs (JNI) → NativeBridge.kt → OsmStore/AsyncOsmStore
                                                                    └→ include/cantino.h → CCantino (xcframework) → swift/Sources/Cantino
```
- `src/input.rs` only translates formats; content rules (ID order, duplicates, deletes, coordinate range) live in `Importer` (`src/import.rs`) so PBF and XML share one contract.
- Import: staging dir `.cantino-stage-*` next to the destination (same filesystem, auto-removed); profile validated first; XML is sniffed by a leading `<`; build DB uses `journal_mode=OFF`; side-index rows are sorted and appended in PK order; `VACUUM INTO` is used (Android temp is RAM). Areas are write-once, and refresh means a full replace. An open `Store` keeps the old inode, so reopen it. Import is synchronous; adapters must call it off the UI thread.
- `src/schema.rs` / `src/encoding.rs`: on-disk format (`APPLICATION_ID`, `FORMAT_VERSION`, kind codes node=0/way=1/relation=2, `packed = id*4+kind` R-tree key, delta-zigzag varint blobs). **Bump `FORMAT_VERSION` on any incompatible change.**
- `src/slice.rs`: pure SliceOSM protocol logic, no HTTP. Adapters own HTTP and scheduling: Android WorkManager + `HttpURLConnection`; iOS in-process `URLSession` (`AreaManager.swift`, `AreaHTTP.swift`), resuming an interrupted run with the same SliceOSM job.
- `src/area_storage.rs` (+ C ABI `src/mobile_area.rs`): downloaded-area layout, staging, roll-forward commit journal, recovery, per-area lock (in-process + `flock`). Recovery fails closed (I/O error, journal kept). `src/failure.rs`: download failure → retry class table (`cantino_classify_failure`). Kotlin and Swift `AreaStorage` are thin wrappers. Basemap-only area versions (`downloadBasemap`) exist in both adapters.
- `src/basemap/`: sans-IO PMTiles v3 extract ported from go-pmtiles (`ExtractPlan`/`Step` state machine, `Assembler` whose `Drop` deletes the staging file). `src/mobile_basemap.rs` exposes it over the C ABI.
- Threading: `Store` is `!Send`/`!Sync` (`PhantomData<Rc<()>>`), opened `READ_ONLY | NO_MUTEX`. C handles record the creating `ThreadId`, and every call (including close/free) returns `WrongThread` on mismatch. Basemap plan/assembler handles follow the same rule; `plan_into_assembler` consumes the plan.

## Key Directories
- `src/`: Rust core (see above). `include/cantino.h`: hand-written C header.
- `tests/`: Rust integration tests; `tests/fixtures/`: shared fixtures (Rust, Python smoke, Android assets).
- `android/`: Gradle project. Modules `:cantino` (AAR, `lol.osm.cantino`), `:cafe-app` (reference app), `:inspector-app` (query/inspect example), `:sample-app` (PMTiles demo).
- `swift/`: SwiftPM package `Cantino` (store API, `AreaManager`, models). Apple platforms link `swift/Artifacts/CCantino.xcframework`; Linux links `target/swift/`.
- `ios/cafe-app`, `ios/inspector-app`: SwiftUI Xcode projects using `../../swift` and MapLibre Native.
- `tests/parity/`: shared ABI parity corpus (`calls.json`/`expected.json`) run by `tests/parity.rs`, Swift `ParityTests`, Kotlin `ParityTest` (instrumented).
- `scripts/`: check, Android/xcframework/iOS app builds, Swift tests, bench, fake SliceOSM server, Pages publishing.
- `docs/guide/` (published guide, pandoc), `docs/bench/` and `docs/research/` (dated evidence, not commitments), `examples/` (`offline.rs`, `basemap_extract.rs`).

## Development Commands
```sh
scripts/check.sh                      # REQUIRED gate: fmt --check, clippy --all-targets -D warnings, cargo test, build --lib, C ABI smoke (python ctypes; .so or .dylib)
scripts/swift-test.sh [--filter X]    # Swift tests: native on macOS, Docker on Linux (not in check.sh)
SIMULATOR="iPhone 18 Pro" scripts/swift-test.sh   # macOS: same tests on an iOS simulator
scripts/build-xcframework.sh          # macOS: swift/Artifacts/CCantino.xcframework
scripts/build-ios-cafe.sh [test|install]          # macOS; also build-ios-inspector.sh
cargo test --test profile closure_keeps   # single integration test
cargo test --lib <name>                   # single unit test
scripts/build-android.sh [arm64-v8a x86_64]  # -> target/android/<ABI>/libcantino.so; rerun after ANY Rust change
cd android && mise exec -- ./gradlew :cantino:testDebugUnitTest :cafe-app:testDebugUnitTest
ANDROID_SERIAL=<dev> mise exec -- ./gradlew :cantino:connectedDebugAndroidTest :cafe-app:connectedDebugAndroidTest
mise exec -- ./gradlew :cantino:assembleRelease           # AAR
mise exec -- ./gradlew :cantino:dokkaGeneratePublicationHtml
scripts/basemap-assets.sh             # required once before building sample/café apps (curl, git, node/npm)
ANDROID_SERIAL=<dev> scripts/kill-test-android.sh   # process-death test; run for download/persistence changes
scripts/publish-pages.sh --guide-only --out build/pages   # preview guide; never commits/pushes
cargo run --release --example offline -- import in.pbf area.sqlite [profile.json]
```
Gradle never builds Rust. It only packages `target/android` as jniLibs.

## Code Conventions & Common Patterns
- Rust 1.99.0, edition 2024. Add liberal explanatory comments on ownership, transactions and invariants.
- **Errors**: `Error` (thiserror) in `src/lib.rs`; `Error::kind()` is an exhaustive match, so classify any new variant. `ErrorKind` is `repr(i32)`: InvalidArgument=1, InvalidFile=2, Io=3, WrongThread=4, Internal=5. These match the `CANTINO_ERROR_*` header defines and the Kotlin `CantinoException` subclasses (Internal → `IllegalStateException`). Classify by what the caller should do; unknown SQLite codes → `Internal`.
- **Limits are errors, never truncation** (`MAX_BATCH`=10_000, `max_candidates`). A NotExists-only query without a bbox is an error.
- **C ABI**: `#[unsafe(no_mangle)] pub unsafe extern "C" fn cantino_<noun_verb>(…, out: *mut *mut c_char, error: *mut *mut c_char) -> i32` with a `/// # Safety` doc. The body runs in `protect(error, || …)` (catch_unwind, NUL escaping). Return 0 for OK, 1 for not-found (`get`/`way_coordinates` only), and `-kind.code()` for errors. Outputs are JSON strings from `CString::into_raw`, and the caller frees every non-NULL buffer with `cantino_free`. JSON enums use serde defaults (`{"Equals":[k,v]}`); `ImportOptions` uses `deny_unknown_fields`. Basemap functions use the `cantino_basemap_` prefix.
- **Changing an exported function** means updating all of: `src/mobile_api.rs` (or `mobile_area.rs`/`mobile_basemap.rs`), `include/cantino.h`, `scripts/mobile-api-smoke.py`, `src/android.rs` (JNI), `NativeBridge.kt`, the Swift adapter (`swift/Sources/Cantino`), and `tests/parity/` (expected output comes from the Rust runner).
- `src/android.rs` calls the C ABI functions, not `Store`, so confinement, panic and error handling have one implementation. JNI names are `Java_lol_osm_cantino_NativeBridge_*`. Exceptions are thrown by class name, so renaming `NativeBridge` or the exception classes breaks the binding. `android/cantino/consumer-rules.pro` keeps them.
- **Swift**: mirrors the Kotlin API names and semantics; deviations are listed in `swift/README.md`. Errors map from the negative status in `Errors.swift`; store handles are thread-confined (`OwnerThread`), `AsyncOsmStore` owns its thread.
- **Kotlin**: `explicitApi()` is on (explicit visibility and types). Public symbols need KDoc. `NativeBridge` is `internal`, strings in and JSON out, handles are `Long`. Coroutines are the async pattern (`AsyncOsmStore`). `AreaManager` and `AreaDownloadWorker` use WorkManager. `AreaTestHooks.downloadTuning` shortens polling in tests. No shared download reducer.
- Don't add Gradle repositories in modules (`FAIL_ON_PROJECT_REPOS`).

## Important Files
- `src/lib.rs` (Error, re-exports), `src/schema.rs`, `src/import.rs`, `src/store.rs`, `src/mobile_api.rs`, `src/mobile_basemap.rs`, `src/mobile_area.rs`, `src/area_storage.rs`, `src/android.rs`
- `include/cantino.h`, `scripts/check.sh`, `Cargo.toml` (single source of the version), `rust-toolchain.toml`, `mise.toml` (Temurin JDK 25)
- `android/cantino/build.gradle.kts`, `android/cantino/consumer-rules.pro`, `android/settings.gradle.kts`
- `swift/Package.swift`, `swift/README.md` (Kotlin deviations), `docs/guide/platform-parity.md`
- `CHANGELOG.md`, `HANDOFF.md`, `docs/guide/building.md` (authoritative build doc)

## Runtime/Tooling Preferences
- Rust via rustup (pinned 1.99.0); SQLite is bundled (`rusqlite` `bundled`, with R-tree), so no C++ toolchain or Docker. Android targets need `rustup target add aarch64-linux-android x86_64-linux-android --toolchain 1.99.0`.
- iOS needs macOS with Xcode 27 (Swift 6.4) and `rustup target add aarch64-apple-ios aarch64-apple-ios-sim aarch64-apple-darwin --toolchain 1.99.0`.
- Always prefix Gradle with `mise exec --` (JDK 25). Gradle 9.8.0, AGP 9.4.1, compileSdk 36, minSdk 26, NDK r29 (`29.0.14206865`). The ABIs are arm64-v8a and x86_64 only. SDK path goes in the gitignored `android/local.properties`.
- Version: set only in `Cargo.toml` (0.4.0); Gradle reads it by regex. Hand-update hardcoded versions in the README install snippet, `docs/guide/README.md` and `CHANGELOG.md`.
- Docs: pandoc 3 renders `docs/guide/*.md`; a new page needs a row in `docs/guide/README.md`. `CHANGELOG.md` is hand-written, newest first: `## X.Y.Z — YYYY-MM-DD` with `### Breaking changes` / `### Improvements` / `### Verification`.
- Keep the README quickstart Activity compiling (it is built verbatim as a check).

## Testing & QA
- Rust: plain `cargo test` with `tempfile`. Integration tests are in `tests/{core,errors,geometry,profile,basemap}.rs`, each with its own helper returning `(TempDir, Store)` (keep the `TempDir` alive). Unit tests are `#[cfg(test)]` modules in `src/`. Error tests assert `ErrorKind` (bad query/ID/bbox → `InvalidArguments`). Paths use `env!("CARGO_MANIFEST_DIR")`.
- Fixtures: `snapshot.osm[.pbf]`, `profile.osm[.pbf]`, `cafe-relation.osm`, `basemap/slc-nw-z12-15.pmtiles`. They are shared by Rust, the Python smoke test and Android assets (flattened, so use `"snapshot.osm"` not a path). **Changing a fixture breaks all three**, because they assert the same counts.
- Android JVM tests (JUnit 4) are in `cantino/src/test`, `cafe-app/src/test`. Instrumented tests (`AndroidJUnit4`, mockwebserver3) are in `src/androidTest`. Run them on an arm64 phone and an x86_64 emulator, after `scripts/build-android.sh`.
  - Single test: `--tests 'lol.osm.cantino.OsmStoreTest.method'` for unit tests, or `-Pandroid.testInstrumentationRunnerArguments.class=lol.osm.cantino.OsmStoreTest` for connected tests.
  - Opt-in (skipped by default): `-Pandroid.testInstrumentationRunnerArguments.live=true` (plus `planetUrl=`), `CityBenchmark` (`pbf=<path>`), and `ProcessDeathTest` (only via `scripts/kill-test-android.sh`).
- Swift: Swift Testing suites in `swift/Tests/CantinoTests` (incl. `ParityTests`); iOS app unit tests via `scripts/build-ios-*.sh test`.
- No coverage tooling. Expectation: `scripts/check.sh` is green, Swift tests pass, and Android suites pass for any Rust or adapter change; parity changes are tested on both platforms. Don't write tests that only pin wiring or wording.

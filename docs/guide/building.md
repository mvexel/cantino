# Building from source

You only need this to change Cantino itself; apps consume the published AAR
([Install](../../README.md#install)).

## Layout

```text
src/                 Rust core: import, SQLite store, queries, SliceOSM protocol, PMTiles extract
  android.rs         JNI adapter (wraps the C ABI functions)
  mobile_api.rs      C ABI (include/cantino.h)
include/cantino.h    C ABI header, the contract for every platform adapter
android/cantino      Kotlin library (AAR): OsmStore, AreaManager, models
android/cafe-app     reference app
android/sample-app   offline basemap demo
swift/               Swift package (OsmStore, AsyncOsmStore, models), platform-neutral, tested on macOS and Linux
ios/cafe-app         iOS reference app (SwiftUI, Xcode project, uses ../../swift)
scripts/             build, check, bench, basemap assets, publishing
```

## Toolchain

| Tool | Version | Notes |
| --- | --- | --- |
| Rust | 1.99.0 (pinned in `rust-toolchain.toml`) | `rustup target add aarch64-linux-android x86_64-linux-android --toolchain 1.99.0` |
| Android NDK | r29 (`29.0.14206865`), SDK-managed | Cross-compiles the Rust library; Gradle also uses it to strip |
| Android SDK | compileSdk 36 | `android/local.properties`: `sdk.dir=…` |
| JDK | Temurin 25 via [mise](https://mise.jdx.dev/) (`mise.toml`) | Run Gradle as `mise exec -- ./gradlew …` |
| Gradle / AGP | 9.8 wrapper / 9.4.1 (built-in Kotlin) | |

No C++ toolchain or Docker: SQLite is compiled by `rusqlite`'s bundled feature.

## Build

```sh
scripts/check.sh               # rustfmt, clippy -D warnings, cargo test, C ABI smoke test (Python ctypes)
scripts/build-android.sh       # → target/android/{arm64-v8a,x86_64}/libcantino.so
cd android
mise exec -- ./gradlew :cantino:assembleRelease                         # the AAR
mise exec -- ./gradlew :cantino:publishReleasePublicationToLocalRepository  # Maven layout in android/build/repo
mise exec -- ./gradlew :cantino:dokkaGeneratePublicationHtml            # API reference → cantino/build/dokka/html
mise exec -- ./gradlew :cantino:testDebugUnitTest :cafe-app:testDebugUnitTest
ANDROID_SERIAL=<device> mise exec -- ./gradlew :cantino:connectedDebugAndroidTest :cafe-app:connectedDebugAndroidTest
```

Run the instrumented suites on an arm64 phone and an x86_64 emulator.
For download/persistence changes also run `ANDROID_SERIAL=<device>
scripts/kill-test-android.sh` from the repository root on each device.

Live service tests are opt-in. Pass
`-Pandroid.testInstrumentationRunnerArguments.live=true` and, for the basemap
test, `-Pandroid.testInstrumentationRunnerArguments.planetUrl=<pmtiles-url>`
to the library instrumented test task. Use a current archive you host.

The AAR packages the two supported ABIs from `target/android/<ABI>/`; rerun
`scripts/build-android.sh` after changing Rust code.

The Pages site (landing page, Maven repository, `/api/`, and this guide as
HTML under `/guide/`) is built by `scripts/publish-pages.sh`. Rendering the
guide needs [pandoc](https://pandoc.org/installing.html) 3.x on the `PATH`
(it converts `docs/guide/*.md` with `scripts/guide-filter.lua` and
`scripts/guide-template.html`; Mermaid diagrams are drawn in the browser).
To check only the guide: `scripts/publish-pages.sh --guide-only --out build/pages`,
then open `build/pages/guide/index.html`.

## Swift (host: macOS or Linux)

The Swift adapter in `swift/` is tested natively on macOS (Xcode's Swift)
and in Docker on Linux:

```sh
scripts/swift-test.sh                              # build the core, then `swift test`
SIMULATOR="iPhone 18 Pro" scripts/swift-test.sh    # macOS: the same tests on an iOS simulator
scripts/build-xcframework.sh                       # macOS: only build swift/Artifacts/CCantino.xcframework
```

It needs cargo, plus Docker on Linux, or on macOS Xcode and the Rust
targets `aarch64-apple-ios`, `aarch64-apple-ios-sim` and
`aarch64-apple-darwin`. On Apple platforms Package.swift uses the binary
target `CCantino.xcframework` (iOS device, iOS simulator and macOS arm64
slices; git-ignored, rebuilt by the script). On Linux the script copies
only `libcantino.a` to `target/swift/libcantino_core.a`, which
Package.swift links statically. SwiftPM's build products go to
`target/swift-build`. On Linux the container's glibc must be at least the
host's (`SWIFT_IMAGE` selects another image). Not part of
`scripts/check.sh`, which needs only cargo. Details and the deviations from the Kotlin API:
[`swift/README.md`](../../swift/README.md).

Sample apps need the offline style assets first:

```sh
scripts/basemap-assets.sh      # → android/sample-app/build-assets (needs curl, git, node/npm)
cd android && mise exec -- ./gradlew :cafe-app:assembleDebug :sample-app:assembleDebug
```

## iOS café app (host: macOS)

[`ios/cafe-app`](../../ios/cafe-app) is a hand-written Xcode project (no
generator): file-system-synchronized groups, the local `swift/` package and
MapLibre Native 6.31.0 from SwiftPM (fetched from github.com on the first
build). A build phase copies style, glyphs and sprites from
`android/sample-app/build-assets`, so run `scripts/basemap-assets.sh` first.

```sh
scripts/build-ios-cafe.sh            # xcframework, then xcodebuild build for the simulator
scripts/build-ios-cafe.sh test       # + the example's unit tests (opening hours, Protomaps builds, café logic)
scripts/build-ios-cafe.sh install    # + install on the booted simulator
SIMULATOR="iPhone 18 Pro" scripts/build-ios-cafe.sh test
```

By hand, from the repository root:

```sh
scripts/build-xcframework.sh
xcodebuild test -project ios/cafe-app/CafeApp.xcodeproj -scheme CafeApp \
    -destination "platform=iOS Simulator,name=iPhone 18 Pro" \
    -derivedDataPath target/ios-cafe/derived -clonedSourcePackagesDirPath target/ios-cafe/packages
```

## Desktop tools

```sh
cargo run --release --example offline -- import INPUT.osm.pbf AREA.sqlite
cargo run --release --example offline -- cafes AREA.sqlite
cargo run --release --example offline -- get AREA.sqlite way 1
ANDROID_SERIAL=<device> scripts/bench-android.sh CITY.osm.pbf   # city benchmark JSON from a device
```

Area files are portable between desktop and device (same format,
`application_id` "CNTN"; see `FORMAT_VERSION` in `src/schema.rs`).

## Versioning

One version for everything, from `Cargo.toml`: the Maven publication and
`Cantino.VERSION` read it at build time. The C ABI header is versioned with
the crate. There are no external consumers yet: the API, ABI and file format
may change without compatibility layers. Re-import development data after
an incompatible format change.

## Publishing (maintainers)

```sh
scripts/publish-pages.sh --worktree   # Maven repo + API docs + landing page → build/pages, synced to build/gh-pages
```

The script builds native libraries, the release AAR with its POM, and the
Dokka HTML, lays them out for GitHub Pages (`/maven/…`, `/api/…`,
`/index.html`), and prints the commands to commit and push the `gh-pages`
branch. It never pushes.

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
scripts/             build, check, bench, basemap assets, publishing
```

## Toolchain

| Tool | Version | Notes |
| --- | --- | --- |
| Rust | 1.99.0 (pinned in `rust-toolchain.toml`) | `rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android --toolchain 1.99.0` |
| Android NDK | r29 (`29.0.14206865`), SDK-managed | Cross-compiles the Rust library; Gradle also uses it to strip |
| Android SDK | compileSdk 36 | `android/local.properties`: `sdk.dir=…` |
| JDK | Temurin 25 via [mise](https://mise.jdx.dev/) (`mise.toml`) | Run Gradle as `mise exec -- ./gradlew …` |
| Gradle / AGP | 9.8 wrapper / 9.4.1 (built-in Kotlin) | |

No C++ toolchain or Docker: SQLite is compiled by `rusqlite`'s bundled feature.

## Build

```sh
scripts/check.sh               # rustfmt, clippy -D warnings, cargo test, C ABI smoke test (Python ctypes)
scripts/build-android.sh       # → target/android/{arm64-v8a,armeabi-v7a,x86_64}/libcantino.so
cd android
mise exec -- ./gradlew :cantino:assembleRelease                         # the AAR
mise exec -- ./gradlew :cantino:publishReleasePublicationToLocalRepository  # Maven layout in android/build/repo
mise exec -- ./gradlew :cantino:dokkaGeneratePublicationHtml            # API reference → cantino/build/dokka/html
mise exec -- ./gradlew :cantino:connectedDebugAndroidTest               # instrumented tests on a device/emulator
```

The AAR packages whatever is in `target/android/<ABI>/`, so rerun
`scripts/build-android.sh` after changing Rust code.

Sample apps need the offline style assets first:

```sh
scripts/basemap-assets.sh      # → android/sample-app/build-assets (needs curl, git, node/npm)
cd android && mise exec -- ./gradlew :cafe-app:assembleDebug :sample-app:assembleDebug
```

## Desktop tools

```sh
cargo run --release --example offline -- import INPUT.osm.pbf AREA.sqlite
cargo run --release --example offline -- cafes AREA.sqlite
cargo run --release --example offline -- get AREA.sqlite way 1
ANDROID_SERIAL=<device> scripts/bench-android.sh CITY.osm.pbf   # city benchmark JSON from a device
```

Area files are portable between desktop and device (same format,
`application_id` "CNTN", format version 1).

## Versioning

One version for everything, from `Cargo.toml`: the Maven publication and
`Cantino.VERSION` read it at build time. The C ABI header is versioned with
the crate. Semantic versioning; before 1.0 a minor version may break the API
(see the compatibility policy in the [CHANGELOG](../../CHANGELOG.md)).

## Publishing (maintainers)

```sh
scripts/publish-pages.sh --worktree   # Maven repo + API docs + landing page → build/pages, synced to build/gh-pages
```

The script builds native libraries, the release AAR with its POM, and the
Dokka HTML, lays them out for GitHub Pages (`/maven/…`, `/api/…`,
`/index.html`), and prints the commands to commit and push the `gh-pages`
branch. It never pushes.

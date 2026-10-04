#!/bin/sh
# Builds (and optionally tests or installs) the iOS café reference app,
# ios/cafe-app, for an iOS simulator with plain xcodebuild.
#
#   scripts/build-ios-cafe.sh            # build the core xcframework, then the app
#   scripts/build-ios-cafe.sh test       # ... and run the example's unit tests
#   scripts/build-ios-cafe.sh install    # ... build, install on the booted simulator
#   SIMULATOR="iPhone 18 Pro" scripts/build-ios-cafe.sh test
#
# Needs: macOS with Xcode, cargo (rust-toolchain.toml pins 1.99.0), and the
# basemap style assets from scripts/basemap-assets.sh (the app's build phase
# copies them from android/sample-app/build-assets and fails without them).
# The first build fetches MapLibre Native (SwiftPM) from github.com.
# Build products, derived data and packages go to target/ios-cafe (ignored).
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
cd "$root"
action=${1:-build}
simulator=${SIMULATOR:-iPhone 18 Pro}
out="$root/target/ios-cafe"

if [ ! -f android/sample-app/build-assets/style.json ]; then
    echo "missing android/sample-app/build-assets/style.json; run scripts/basemap-assets.sh first" >&2
    exit 1
fi
scripts/build-xcframework.sh

run() {
    xcodebuild "$1" -project ios/cafe-app/CafeApp.xcodeproj -scheme CafeApp \
        -destination "platform=iOS Simulator,name=$simulator" \
        -derivedDataPath "$out/derived" -clonedSourcePackagesDirPath "$out/packages"
}

case "$action" in
    build) run build ;;
    test) run test ;;
    install)
        run build
        xcrun simctl install booted "$out/derived/Build/Products/Debug-iphonesimulator/CafeApp.app"
        echo "installed; launch with: xcrun simctl launch booted lol.osm.cantino.cafe -lat 40.7608 -lon -111.8910"
        ;;
    *) echo "usage: $0 [build|test|install]" >&2; exit 2 ;;
esac

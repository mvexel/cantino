#!/bin/sh
# Builds (and optionally tests or installs) the iOS Inspector example app,
# ios/inspector-app, for an iOS simulator with plain xcodebuild. Same shape
# as scripts/build-ios-cafe.sh.
#
#   scripts/build-ios-inspector.sh            # build the core xcframework, then the app
#   scripts/build-ios-inspector.sh test       # ... and run the example's unit tests
#   scripts/build-ios-inspector.sh install    # ... build, install on the simulator
#   SIMULATOR="iPhone 17 Pro" scripts/build-ios-inspector.sh test
#   SIMULATOR_ID=<udid> scripts/build-ios-inspector.sh install   # when names are ambiguous
#
# Needs: macOS with Xcode, cargo (rust-toolchain.toml pins 1.99.0), and the
# basemap style assets from scripts/basemap-assets.sh (the app's build phase
# copies them from android/sample-app/build-assets and fails without them).
# The first build fetches MapLibre Native (SwiftPM) from github.com.
# Build products, derived data and packages go to target/ios-inspector (ignored).
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
cd "$root"
action=${1:-build}
simulator=${SIMULATOR:-iPhone 18 Pro}
if [ -n "${SIMULATOR_ID:-}" ]; then
    destination="platform=iOS Simulator,id=$SIMULATOR_ID"
    device=$SIMULATOR_ID
else
    destination="platform=iOS Simulator,name=$simulator"
    device=booted
fi
out="$root/target/ios-inspector"

if [ ! -f android/sample-app/build-assets/style.json ]; then
    echo "missing android/sample-app/build-assets/style.json; run scripts/basemap-assets.sh first" >&2
    exit 1
fi
scripts/build-xcframework.sh

run() {
    xcodebuild "$1" -project ios/inspector-app/InspectorApp.xcodeproj -scheme InspectorApp \
        -destination "$destination" \
        -derivedDataPath "$out/derived" -clonedSourcePackagesDirPath "$out/packages"
}

case "$action" in
    build) run build ;;
    test) run test ;;
    install)
        run build
        xcrun simctl install "$device" "$out/derived/Build/Products/Debug-iphonesimulator/InspectorApp.app"
        echo "installed; launch with: xcrun simctl launch $device lol.osm.cantino.inspector -lat 40.7608 -lon -111.8910 -auto_download YES"
        ;;
    *) echo "usage: $0 [build|test|install]" >&2; exit 2 ;;
esac

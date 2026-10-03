#!/bin/sh
# Phase 2 measurement: import and query a real extract on a connected device.
# Usage: ANDROID_SERIAL=<device> scripts/bench-android.sh CITY.osm.pbf
# Prints the OSMFW_BENCH JSON line from the device log.
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
pbf="${1:?usage: bench-android.sh CITY.osm.pbf}"
adb="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"
remote=/data/local/tmp/osmfw/bench.osm.pbf
"$adb" shell mkdir -p /data/local/tmp/osmfw
"$adb" push -q "$pbf" "$remote"
"$adb" logcat -c
cd "$root/android"
mise exec -- ./gradlew :osm-framework:connectedDebugAndroidTest -q \
    -Pandroid.testInstrumentationRunnerArguments.class=io.github.mvexel.osmframework.CityBenchmark \
    -Pandroid.testInstrumentationRunnerArguments.pbf="$remote"
"$adb" logcat -d -s OSMFW_BENCH:I | grep -o '{.*}'

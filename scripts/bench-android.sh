#!/bin/sh
# Phase 2 measurement: import and query a real extract on a connected device.
# Usage: ANDROID_SERIAL=<device> scripts/bench-android.sh CITY.osm.pbf
# Prints the CANTINO_BENCH JSON line from the device log.
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
pbf="${1:?usage: bench-android.sh CITY.osm.pbf}"
adb="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"
remote=/data/local/tmp/cantino/bench.osm.pbf
"$adb" shell mkdir -p /data/local/tmp/cantino
"$adb" push -q "$pbf" "$remote"
"$adb" logcat -c
cd "$root/android"
mise exec -- ./gradlew :cantino:connectedDebugAndroidTest -q \
    -Pandroid.testInstrumentationRunnerArguments.class=lol.osm.cantino.CityBenchmark \
    -Pandroid.testInstrumentationRunnerArguments.pbf="$remote"
"$adb" logcat -d -s CANTINO_BENCH:I | grep -o '{.*}'

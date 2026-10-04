#!/bin/sh
# Xcode build phase of the Inspector app (copied from ios/cafe-app): copies the offline basemap style,
# glyphs and sprites that scripts/basemap-assets.sh generates for the Android
# sample app (android/sample-app/build-assets) into the app bundle, without
# its bundled slc.pmtiles (the basemap is downloaded per area at runtime).
# The iOS counterpart of android/inspector-app's copyBasemapStyleAssets Gradle
# task: nothing generated is committed.
#
# MapLibre resolves the style's asset://glyphs/... and asset://sprites/...
# against the main bundle's resource directory, so they go to its root.
set -eu
src="${SRCROOT}/../../android/sample-app/build-assets"
dst="${TARGET_BUILD_DIR}/${UNLOCALIZED_RESOURCES_FOLDER_PATH}"
if [ ! -f "$src/style.json" ]; then
    echo "error: missing $src/style.json; run scripts/basemap-assets.sh first" >&2
    exit 1
fi
mkdir -p "$dst"
rm -rf "$dst/glyphs" "$dst/sprites"
cp "$src/style.json" "$dst/style.json"
cp -R "$src/glyphs" "$src/sprites" "$dst/"

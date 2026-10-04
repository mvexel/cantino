#!/bin/sh
# Builds the Rust core as swift/Artifacts/CCantino.xcframework: a static
# library per Apple platform plus the C header and its Clang module map.
# Package.swift uses it as the binary target `CCantino` on Apple platforms.
#
#   scripts/build-xcframework.sh
#
# Slices: iOS device (arm64), iOS simulator (arm64), macOS (arm64, for
# `swift test` on the Mac). Intel simulators/Macs are not built.
# Needs: macOS with Xcode, and the Rust targets
#   rustup target add aarch64-apple-ios aarch64-apple-ios-sim --toolchain 1.99.0
#
# Deployment targets match the `platforms` floors in Package.swift; they are
# passed to rustc and to the C compiler building the bundled SQLite, so the
# objects are not marked for a newer OS than the package supports.
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
cd "$root"
export IPHONEOS_DEPLOYMENT_TARGET=15.0
export MACOSX_DEPLOYMENT_TARGET=12.0

targets="aarch64-apple-ios aarch64-apple-ios-sim aarch64-apple-darwin"
for target in $targets; do
    cargo build --release --lib --target "$target"
done

# The header directory of every slice: the repository's single header plus
# a module map naming it (the module the Swift target imports).
headers=target/xcframework/headers
rm -rf target/xcframework
mkdir -p "$headers"
cp include/cantino.h "$headers/"
cat > "$headers/module.modulemap" <<'EOF'
module CCantino [system] {
    header "cantino.h"
    export *
}
EOF

output=swift/Artifacts/CCantino.xcframework
rm -rf "$output"
set --
for target in $targets; do
    set -- "$@" -library "target/$target/release/libcantino.a" -headers "$headers"
done
xcodebuild -create-xcframework "$@" -output "$output"

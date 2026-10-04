#!/bin/sh
# Builds the Rust core for the host and runs the Swift adapter's tests
# (swift/, SwiftPM package `Cantino`) in one command: natively on macOS
# (Xcode's Swift), in Docker elsewhere.
#
#   scripts/swift-test.sh                 # build core, swift test
#   scripts/swift-test.sh --filter Bbox   # extra arguments go to `swift test`
#   SIMULATOR="iPhone 18 Pro" scripts/swift-test.sh   # macOS: on an iOS simulator
#                                         # (xcodebuild test; extra arguments go to xcodebuild)
#
# Needs: cargo (rust-toolchain.toml pins 1.99.0), and on Linux Docker (no
# Swift toolchain on the host: the official swift image provides it).
#
# Recipe and why:
# - The core is built on the host (`cargo build --release --lib`) and only
#   `libcantino.a` is copied into target/swift/, the directory Package.swift
#   links from. target/release also holds libcantino.so/.dylib, which the
#   linker would prefer over the .a.
# - A static library built on the host is linked inside the container, so
#   the container's glibc must be at least as new as the host's (versioned
#   glibc symbols resolve at that final link). swift:6.4 is Ubuntu 26.04
#   (glibc 2.43), matching the development host. On a newer host, set
#   SWIFT_IMAGE to a newer swift image, or build the core in a container
#   with the image's glibc instead.
# - The container runs as the calling user and writes its build products
#   to target/swift-build (git-ignored), so no root-owned files appear in
#   the tree.
#
# Not part of scripts/check.sh: it needs Docker.
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
cd "$root"
image=${SWIFT_IMAGE:-swift:6.4}

if [ "$(uname -s)" = Darwin ]; then
    # Apple platforms link the xcframework (see Package.swift).
    scripts/build-xcframework.sh
    cd swift
    if [ -n "${SIMULATOR:-}" ]; then
        exec xcodebuild test -scheme Cantino -destination "platform=iOS Simulator,name=$SIMULATOR" \
            -derivedDataPath "$root/target/ios-derived" "$@"
    fi
    exec swift test --scratch-path "$root/target/swift-build" "$@"
fi

cargo build --release --lib
mkdir -p target/swift target/swift-build
# Named libcantino_core.a so -lcantino can never pick up another library
# (on macOS, SwiftPM's own libCantino.a matched it case-insensitively).
rm -f target/swift/libcantino.a
cp target/release/libcantino.a target/swift/libcantino_core.a

docker run --rm \
    --user "$(id -u):$(id -g)" \
    -e HOME=/tmp \
    -v "$root":/work \
    -w /work/swift \
    "$image" \
    swift test --scratch-path /work/target/swift-build "$@"

#!/bin/sh
# Builds the Rust core for Linux and runs the Swift adapter's tests
# (swift/, SwiftPM package `Cantino`) in Docker, in one command.
#
#   scripts/swift-test.sh                 # build core, swift test
#   scripts/swift-test.sh --filter Bbox   # extra arguments go to `swift test`
#
# Needs: cargo (rust-toolchain.toml pins 1.99.0) and Docker. No Swift
# toolchain on the host: the official swift image provides it.
#
# Recipe and why:
# - The core is built on the host (`cargo build --release --lib`) and only
#   `libcantino.a` is copied into target/swift/, the directory Package.swift
#   links from. target/release also holds libcantino.so, which the linker
#   would prefer over the .a.
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

cargo build --release --lib
mkdir -p target/swift target/swift-build
cp target/release/libcantino.a target/swift/libcantino.a

docker run --rm \
    --user "$(id -u):$(id -g)" \
    -e HOME=/tmp \
    -v "$root":/work \
    -w /work/swift \
    "$image" \
    swift test --scratch-path /work/target/swift-build "$@"

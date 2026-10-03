#!/bin/sh
# The NDK and the Rust target must already be installed; see HANDOFF.md.
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
: "${ANDROID_NDK_ROOT:?Set ANDROID_NDK_ROOT to Android NDK r29}"
source="${OSMX_SOURCE_DIR:-$root/vendor/OSMExpress}"
"$source/mobile/build-android.sh"
export OSMX_LIB_DIR="$source/build-android/artifacts"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang"
cd "$root"
cargo build --release --target aarch64-linux-android
mkdir -p "$root/target/android/arm64-v8a"
cp "$root/target/aarch64-linux-android/release/libosm_framework.so" "$root/target/android/arm64-v8a/"
cp "$OSMX_LIB_DIR/libosmx-mobile.so" "$OSMX_LIB_DIR/libc++_shared.so" "$root/target/android/arm64-v8a/"

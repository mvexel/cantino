#!/bin/sh
# Cross-compiles the Rust core (with its bundled SQLite) for Android.
# Usage: scripts/build-android.sh [ABI...]  (default: arm64-v8a x86_64)
# arm64-v8a runs on phones; x86_64 on emulators on x86_64 hosts.
# Output: target/android/<ABI>/libcantino.so, the only native library the
# AAR packages. Requires the Rust targets (rustup target add
# aarch64-linux-android x86_64-linux-android
# --toolchain 1.99.0) and NDK r29.
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
# Default to the SDK-managed NDK that Gradle also uses for stripping.
case "$(uname -s)" in
Darwin) default_sdk=$HOME/Library/Android/sdk; host=darwin-x86_64 ;; # universal binaries
*) default_sdk=$HOME/Android/Sdk; host=linux-x86_64 ;;
esac
: "${ANDROID_NDK_ROOT:=${ANDROID_HOME:-$default_sdk}/ndk/29.0.14206865}"
toolchain="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/$host/bin"
api=26 # minSdk of the AAR
[ -d "$toolchain" ] || { echo "NDK toolchain not found: $toolchain" >&2; exit 2; }
[ "$#" -gt 0 ] || set -- arm64-v8a x86_64
cd "$root"
# Discard output for the removed ABI, including when rebuilding only one target.
rm -rf "$root/target/android/armeabi-v7a"
for abi in "$@"; do
    case "$abi" in
        arm64-v8a) target=aarch64-linux-android; clang=aarch64-linux-android ;;
        x86_64) target=x86_64-linux-android; clang=x86_64-linux-android ;;
        *) echo "unsupported ABI: $abi" >&2; exit 2 ;;
    esac
    # The cc crate (compiling SQLite) reads CC_<triple>/AR_<triple>; Cargo
    # reads the linker from CARGO_TARGET_<TRIPLE>_LINKER.
    triple=$(echo "$target" | tr '-' '_')
    upper=$(echo "$target" | tr 'a-z-' 'A-Z_')
    env "CC_$triple=$toolchain/${clang}${api}-clang" \
        "AR_$triple=$toolchain/llvm-ar" \
        "CARGO_TARGET_${upper}_LINKER=$toolchain/${clang}${api}-clang" \
        cargo build --release --lib --target "$target"
    out="$root/target/android/$abi"
    # Replace the output for this ABI.
    rm -rf "$out"
    mkdir -p "$out"
    cp "$root/target/$target/release/libcantino.so" "$out/"
done

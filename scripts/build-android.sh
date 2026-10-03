#!/bin/sh
# The NDK and the Rust targets must already be installed; see HANDOFF.md.
# Usage: scripts/build-android.sh [ABI...]  (default: arm64-v8a x86_64)
# arm64-v8a runs on phones; x86_64 runs on emulators on x86_64 hosts.
# Output: target/android/<ABI>/ with the three shared libraries the AAR packages.
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
# Default to the SDK-managed NDK that Gradle also uses for stripping.
: "${ANDROID_NDK_ROOT:=${ANDROID_HOME:-$HOME/Android/Sdk}/ndk/29.0.14206865}"
export ANDROID_NDK_ROOT
source="${OSMX_SOURCE_DIR:-$root/vendor/OSMExpress}"
toolchain="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin"
[ "$#" -gt 0 ] || set -- arm64-v8a x86_64
cd "$root"
for abi in "$@"; do
    case "$abi" in
        arm64-v8a) target=aarch64-linux-android ;;
        x86_64) target=x86_64-linux-android ;;
        *) echo "unsupported ABI: $abi" >&2; exit 2 ;;
    esac
    "$source/mobile/build-android.sh" "$abi"
    # Cargo reads the linker from CARGO_TARGET_<TRIPLE>_LINKER.
    linker_var="CARGO_TARGET_$(echo "$target" | tr 'a-z-' 'A-Z_')_LINKER"
    env OSMX_LIB_DIR="$source/build-android/$abi/artifacts" \
        "$linker_var=$toolchain/${target}26-clang" \
        cargo build --release --target "$target"
    mkdir -p "$root/target/android/$abi"
    cp "$root/target/$target/release/libosm_framework.so" "$root/target/android/$abi/"
    cp "$source/build-android/$abi/artifacts/libosmx-mobile.so" \
        "$source/build-android/$abi/artifacts/libc++_shared.so" "$root/target/android/$abi/"
done

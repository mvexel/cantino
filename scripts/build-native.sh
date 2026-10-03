#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
source="${OSMX_SOURCE_DIR:-$root/vendor/OSMExpress}"
"$source/mobile/build-linux.sh"
export OSMX_LIB_DIR="$source/build-linux/artifacts"
cd "$root"
cargo build
cargo test
cargo clippy --all-targets -- -D warnings
python3 "$source/mobile/tests/c_api.py" "$OSMX_LIB_DIR/libosmx-mobile.so"
python3 scripts/mobile-api-smoke.py target/debug/libosm_framework.so

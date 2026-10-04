#!/bin/sh
# Required checks before committing: formatting, Clippy, Rust tests, and the
# C ABI exercised from a foreign runtime (Python ctypes). No native toolchain
# beyond Cargo is needed: SQLite is compiled by the `rusqlite` bundled feature.
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
cd "$root"
cargo fmt --check
cargo clippy --all-targets -- -D warnings
cargo test
# `cargo test` builds the library's cdylib in target/debug as well.
cargo build --lib
case "$(uname -s)" in
Darwin) cdylib=target/debug/libcantino.dylib ;;
*) cdylib=target/debug/libcantino.so ;;
esac
python3 scripts/mobile-api-smoke.py "$cdylib"

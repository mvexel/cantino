// swift-tools-version:6.0
//
// Cantino's Swift adapter: a thin layer over the Rust core's C ABI
// (include/cantino.h), mirroring the Kotlin store API (android/cantino).
//
// This slice is platform-neutral and built/tested on Linux (Docker, see
// scripts/swift-test.sh). iOS packaging comes later: an xcframework
// `.binaryTarget` replaces the Linux link settings below, and the iOS-only
// parts (background URLSession, BGTaskScheduler) live behind
// `#if os(iOS)`. Nothing in Sources/ uses an Apple-only API today.
import PackageDescription

// Where the Rust static library lives. `scripts/swift-test.sh` builds the
// core with `cargo build --release` and copies ONLY `libcantino.a` into
// `target/swift/`: target/release also holds `libcantino.so` (the crate is
// built as cdylib too), and the linker prefers a shared library over a
// static one when both sit in a `-L` directory, which would leave the test
// binary needing the .so at run time. Override with CANTINO_LIB_DIR.
let libDir = Context.environment["CANTINO_LIB_DIR"]
    ?? "\(Context.packageDirectory)/../target/swift"

let package = Package(
    name: "Cantino",
    // Floors for the later Apple slice. Nothing here needs newer than this:
    // the async wrapper uses its own owner thread, not a custom actor
    // executor (which would need iOS 17 / macOS 14).
    platforms: [.iOS(.v15), .macOS(.v12)],
    products: [
        .library(name: "Cantino", targets: ["Cantino"]),
    ],
    targets: [
        // The C ABI as a Clang module. Its module.modulemap points at the
        // repository's include/cantino.h, so the header has one source of
        // truth (no copy to drift). No `link` directive in the map: linking
        // is a per-platform decision made below (and by the xcframework on
        // Apple platforms later).
        .systemLibrary(name: "CCantino", path: "Sources/CCantino"),
        .target(
            name: "Cantino",
            dependencies: ["CCantino"],
            linkerSettings: [
                // Linux: the Rust staticlib plus the system libraries the
                // Rust std and the bundled SQLite need (libm for SQLite's
                // math, libdl/libpthread for std; on glibc >= 2.34 the last
                // two are stubs, harmless). unsafeFlags is fine for a root
                // package and for local path dependencies; a remote package
                // dependency will instead get the binary target.
                .unsafeFlags(["-L", libDir], .when(platforms: [.linux])),
                .linkedLibrary("cantino", .when(platforms: [.linux])),
                .linkedLibrary("m", .when(platforms: [.linux])),
                .linkedLibrary("dl", .when(platforms: [.linux])),
                .linkedLibrary("pthread", .when(platforms: [.linux])),
            ]
        ),
        .testTarget(name: "CantinoTests", dependencies: ["Cantino"]),
    ]
)

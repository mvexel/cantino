// swift-tools-version:6.0
//
// Cantino's Swift adapter: a thin layer over the Rust core's C ABI
// (include/cantino.h), mirroring the Kotlin store API (android/cantino).
//
// How the core gets in, decided by the platform that evaluates this
// manifest:
// - Apple (macOS host, iOS device and simulator): the binary target
//   `CCantino`, Artifacts/CCantino.xcframework, built by
//   scripts/build-xcframework.sh (git-ignored; build it first). Each slice
//   carries the static library, cantino.h and the module map.
// - Linux: a system library target whose module map points at the
//   repository's include/cantino.h, linked against the staticlib that
//   scripts/swift-test.sh copies to target/swift/libcantino_core.a.
import PackageDescription

#if os(Linux)
// Where the Rust static library lives on Linux. Only libcantino.a is copied
// (renamed libcantino_core.a): target/release also holds libcantino.so,
// which the linker would prefer. Override with CANTINO_LIB_DIR.
let libDir = Context.environment["CANTINO_LIB_DIR"]
    ?? "\(Context.packageDirectory)/../target/swift"
let core: Target = .systemLibrary(name: "CCantino", path: "Sources/CCantino")
let coreLinking: [LinkerSetting] = [
    // The Rust staticlib plus the system libraries the Rust std and the
    // bundled SQLite need (libm for SQLite's math, libdl/libpthread for std;
    // on glibc >= 2.34 the last two are stubs, harmless). unsafeFlags is
    // fine for a root package and for local path dependencies.
    .unsafeFlags(["-L", libDir]),
    .linkedLibrary("cantino_core"),
    .linkedLibrary("m"),
    .linkedLibrary("dl"),
    .linkedLibrary("pthread"),
]
#else
let core: Target = .binaryTarget(name: "CCantino", path: "Artifacts/CCantino.xcframework")
let coreLinking: [LinkerSetting] = []
#endif

let package = Package(
    name: "Cantino",
    // Nothing here needs newer than this: the async wrapper uses its own
    // owner thread, not a custom actor executor (which would need iOS 17 /
    // macOS 14). scripts/build-xcframework.sh builds for the same floors.
    platforms: [.iOS(.v15), .macOS(.v12)],
    products: [
        .library(name: "Cantino", targets: ["Cantino"]),
    ],
    targets: [
        core,
        .target(name: "Cantino", dependencies: ["CCantino"], linkerSettings: coreLinking),
        .testTarget(name: "CantinoTests", dependencies: ["Cantino"]),
    ]
)

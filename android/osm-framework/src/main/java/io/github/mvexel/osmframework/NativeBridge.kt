package io.github.mvexel.osmframework

/**
 * Raw JNI surface implemented in Rust (`src/android.rs`). Strings in, JSON out.
 * Native errors arrive as RuntimeException; [OsmStore] rethrows them as
 * [OsmFrameworkException]. Nothing outside this module calls these directly.
 */
internal object NativeBridge {
    init {
        // Self-contained: SQLite is compiled into the Rust library, which
        // depends only on the platform's libc/libm/libdl.
        System.loadLibrary("osm_framework")
    }

    @JvmStatic external fun open(path: String): Long
    @JvmStatic external fun close(handle: Long)
    @JvmStatic external fun get(handle: Long, kind: Int, id: Long): String?
    @JvmStatic external fun query(handle: Long, request: String): String
    @JvmStatic external fun importArea(input: String, destination: String, options: String?): String
}

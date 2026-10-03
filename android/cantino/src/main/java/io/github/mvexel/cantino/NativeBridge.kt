package io.github.mvexel.cantino

/**
 * Raw JNI surface implemented in Rust (`src/android.rs`). Strings in, JSON out.
 * Native errors arrive as RuntimeException; [OsmStore] rethrows them as
 * [CantinoException]. Nothing outside this module calls these directly.
 */
internal object NativeBridge {
    init {
        // Self-contained: SQLite is compiled into the Rust library, which
        // depends only on the platform's libc/libm/libdl.
        System.loadLibrary("cantino")
    }

    @JvmStatic external fun open(path: String): Long
    @JvmStatic external fun close(handle: Long)
    @JvmStatic external fun get(handle: Long, kind: Int, id: Long): String?
    @JvmStatic external fun query(handle: Long, request: String): String
    @JvmStatic external fun importArea(input: String, destination: String, options: String?): String

    // SliceOSM protocol helpers (pure functions, any thread). `base` null = public service.
    @JvmStatic external fun sliceJobRequest(base: String?, bbox: String, name: String): String
    @JvmStatic external fun sliceJob(base: String?, response: String): String
    @JvmStatic external fun sliceProgress(status: String): String

    // Basemap extract (src/mobile_basemap.rs). Plan and assembler handles are
    // confined to the thread that created them, like store handles; see
    // BasemapExtract. intoAssembler consumes the plan. Tile ranges go in as
    // files so large responses never cross JNI as byte[].
    @JvmStatic external fun basemapPlanNew(bbox: String, minZoom: Int, maxZoom: Int, overfetch: Double): Long
    @JvmStatic external fun basemapPlanFirstRequest(plan: Long): String
    @JvmStatic external fun basemapPlanFeed(plan: Long, id: Long, bytes: ByteArray): String
    @JvmStatic external fun basemapPlanOutstanding(plan: Long): String
    @JvmStatic external fun basemapPlanIntoAssembler(plan: Long, staging: String): Long
    @JvmStatic external fun basemapPlanFree(plan: Long)
    @JvmStatic external fun basemapAsmWriteRange(assembler: Long, id: Long, bytes: ByteArray)
    @JvmStatic external fun basemapAsmWriteRangeFile(assembler: Long, id: Long, path: String)
    @JvmStatic external fun basemapAsmRemaining(assembler: Long): String
    @JvmStatic external fun basemapAsmProgress(assembler: Long): String
    @JvmStatic external fun basemapAsmFinish(assembler: Long, output: String)
    @JvmStatic external fun basemapAsmFree(assembler: Long)

    // Validates a local PMTiles v3 file and describes its header (any thread).
    @JvmStatic external fun basemapInfo(path: String): String
}

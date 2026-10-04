package lol.osm.cantino

/**
 * Raw JNI surface implemented in Rust (`src/android.rs`). Strings in, JSON out.
 * Native errors arrive already typed: the Rust side throws the
 * [CantinoException] subtype for the error's category
 * ([CantinoException.InvalidArgument], [CantinoException.InvalidFile],
 * [CantinoException.Io], [CantinoException.WrongThread]) or
 * [IllegalStateException] for an internal error, so callers need no
 * wrapping and nothing parses messages. Nothing outside this module calls
 * these directly.
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
    @JvmStatic external fun getMany(handle: Long, request: String): String
    @JvmStatic external fun wayCoordinates(handle: Long, id: Long): String?
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

    // Area store (src/area_storage.rs via cantino_area_*): no handles, any
    // thread; `root` is AreaStorage's directory. See AreaStorage.
    @JvmStatic external fun areaValidateId(areaId: String)
    @JvmStatic external fun areaLayout(root: String, areaId: String, workId: String?): String
    @JvmStatic external fun areaPrepareStaging(root: String, areaId: String, workId: String): String
    @JvmStatic external fun areaDiscardStaging(root: String, areaId: String, workId: String)
    @JvmStatic external fun areaWriteStagedMetadata(root: String, areaId: String, workId: String, metadata: String)

    /** True when published; false when [hook] aborted (its exception is then thrown instead). */
    @JvmStatic external fun areaCommit(root: String, areaId: String, workId: String, hasBasemap: Boolean, hook: AreaCommitHook): Boolean
    @JvmStatic external fun areaRecover(root: String, areaId: String)

    /** The published area JSON, or null when none is published. */
    @JvmStatic external fun areaPublished(root: String, areaId: String): String?

    /** cantino_classify_failure: classification JSON, or null when the input is not a failure. */
    @JvmStatic external fun classifyFailure(input: String): String?
}

/**
 * Called by the native commit ([NativeBridge.areaCommit]) on the committing
 * thread, under the area lock, with [STAGE_BEFORE_COMMIT] (throw to abort:
 * nothing is published) and [STAGE_AFTER_COMMIT_POINT] (the version is
 * committed). Must not call back into [AreaStorage] for the same area. Bound
 * by name from Rust (`onStage(I)V`); kept by the consumer R8 rules.
 */
internal fun interface AreaCommitHook {
    fun onStage(stage: Int)

    companion object {
        /** CANTINO_AREA_STAGE_BEFORE_COMMIT. */
        const val STAGE_BEFORE_COMMIT = 0

        /** CANTINO_AREA_STAGE_AFTER_COMMIT_POINT. */
        const val STAGE_AFTER_COMMIT_POINT = 1
    }
}

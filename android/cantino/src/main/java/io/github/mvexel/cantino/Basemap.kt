package io.github.mvexel.cantino

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Where an area's offline basemap comes from. Opt-in per download
 * ([AreaManager.download]); the default [None] downloads OSM data only.
 *
 * The basemap is a PMTiles v3 archive published next to the area at
 * `filesDir/cantino-areas/<areaId>.pmtiles` ([AreaInfo.basemapFile],
 * [AreaInfo.pmtilesUrl] for MapLibre). It is part of the area: the area is
 * [AreaState.Ready] only when OSM data and the requested basemap are both
 * published, and a refresh replaces both (a refresh with [None] removes a
 * previous basemap, since it would describe a different download).
 */
public sealed interface BasemapSource {
    /** No basemap. */
    public data object None : BasemapSource

    /**
     * A ready-made PMTiles file for this area, downloaded as is (for example
     * one an app developer hosts per city). Validated as PMTiles v3 (magic,
     * version, sections within the file) before it is published; anything
     * else fails the download (non-retryable). The constructor throws
     * [IllegalArgumentException] unless [url] is http(s).
     */
    public data class Url(val url: String) : BasemapSource {
        init {
            requireHttp(url)
        }
    }

    /**
     * Cut the area's bbox out of a large remote PMTiles archive on the device
     * with HTTP range requests (the Rust engine plans and assembles, Kotlin
     * fetches with [AreaConfig.basemapParallelism] parallel requests). The
     * server must support ranges (206); a server answering 200 fails the
     * download. Output zooms are the archive's minimum up to [maxZoom]
     * (clamped to the archive's); [overfetch] is the extra bytes allowed per
     * wanted byte to save requests (go-pmtiles' default 0.05).
     *
     * For demos [ProtomapsBuilds.latestUrl] finds the newest Protomaps daily
     * planet build. Production apps should mirror a build to their own
     * storage/CDN: Protomaps discourages hotlinking and builds expire after
     * about a week.
     *
     * The constructor throws [IllegalArgumentException] unless [planetUrl] is
     * http(s), [maxZoom] is 0..31 and [overfetch] is finite and >= 0.
     */
    public data class Extract(val planetUrl: String, val maxZoom: Int = 15, val overfetch: Double = 0.05) : BasemapSource {
        init {
            requireHttp(planetUrl)
            require(maxZoom in 0..31) { "maxZoom must be 0..31: $maxZoom" }
            require(overfetch.isFinite() && overfetch >= 0) { "overfetch must be >= 0: $overfetch" }
        }
    }

    private companion object {
        fun requireHttp(url: String) =
            require(url.startsWith("https://") || url.startsWith("http://")) { "basemap URL must be http(s): $url" }
    }
}

/**
 * Newest Protomaps daily planet build, for demos and tests.
 *
 * Builds are published as `https://build.protomaps.com/YYYYMMDD.pmtiles` and
 * deleted after about a week; Protomaps asks not to hotlink them from
 * production apps. Ship your own mirror (e.g. an R2/S3 bucket with range
 * support) and pass its URL to [BasemapSource.Extract] instead.
 */
public object ProtomapsBuilds {
    /** Where Protomaps publishes its daily builds. */
    public const val BASE_URL: String = "https://build.protomaps.com/"

    /**
     * HEADs the builds of [today] (UTC) and up to [maxAgeDays] days back and
     * returns the URL of the newest one that exists (at most `maxAgeDays + 1`
     * HEAD requests, newest first; network I/O on [Dispatchers.IO], so any
     * caller context is fine). Throws [java.io.IOException] if none exists
     * or the server cannot be reached. [baseUrl] is for tests and mirrors
     * with the same naming.
     */
    @JvmStatic
    @JvmOverloads
    public suspend fun latestUrl(
        today: LocalDate = LocalDate.now(ZoneOffset.UTC),
        maxAgeDays: Int = 7,
        baseUrl: String = BASE_URL,
    ): String {
        val http = SliceHttp(connectTimeoutMillis = 15_000, readTimeoutMillis = 15_000)
        val base = baseUrl.trimEnd('/') + "/"
        for (age in 0..maxAgeDays) {
            val url = base + today.minusDays(age.toLong()).format(DateTimeFormatter.BASIC_ISO_DATE) + ".pmtiles"
            try {
                if (http.exists(url)) return url
            } catch (failure: DownloadFailure) {
                throw java.io.IOException("cannot check $url: ${failure.message}", failure)
            }
        }
        throw java.io.IOException("no Protomaps build found in the last $maxAgeDays days at $base")
    }
}

/** Progress phase of [AreaState.Basemap]. */
public enum class BasemapPhase {
    /** [BasemapSource.Url]: downloading the file. */
    DOWNLOAD,

    /** [BasemapSource.Extract]: reading the archive's header and directories (no total yet). */
    DIRECTORIES,

    /** [BasemapSource.Extract]: fetching tile data; total is the bytes to transfer. */
    TILES,
}

/**
 * Header facts of a local PMTiles v3 file, from the Rust validator
 * (`cantino_basemap_info`). Useful to check a basemap the app ships or
 * downloads itself, or to fit the map camera to [bounds].
 *
 * @property minZoom Lowest zoom level.
 * @property maxZoom Highest zoom level.
 * @property bounds Area covered according to the header, in degrees
 *   (for a world archive west > east is possible near the antimeridian).
 * @property addressedTiles Tiles addressable (run lengths expanded).
 * @property tileEntries Directory entries.
 * @property tileContents Distinct tile blobs (deduplicated).
 * @property fileBytes Size of the file.
 */
public class PmtilesInfo internal constructor(
    public val minZoom: Int,
    public val maxZoom: Int,
    public val bounds: Bbox,
    public val addressedTiles: Long,
    public val tileEntries: Long,
    public val tileContents: Long,
    public val fileBytes: Long,
) {
    override fun equals(other: Any?): Boolean = this === other || other is PmtilesInfo &&
        minZoom == other.minZoom && maxZoom == other.maxZoom && bounds == other.bounds &&
        addressedTiles == other.addressedTiles && tileEntries == other.tileEntries &&
        tileContents == other.tileContents && fileBytes == other.fileBytes

    override fun hashCode(): Int = hash(minZoom, maxZoom, bounds, addressedTiles, tileEntries, tileContents, fileBytes)

    override fun toString(): String =
        "PmtilesInfo(minZoom=$minZoom, maxZoom=$maxZoom, bounds=$bounds, addressedTiles=$addressedTiles, " +
            "tileEntries=$tileEntries, tileContents=$tileContents, fileBytes=$fileBytes)"

    public companion object {
        /**
         * Validates [file] as PMTiles v3 (magic, version, every section inside
         * the file) and reads its header. Throws [CantinoException] if it
         * is missing or not one. Synchronous file I/O (small reads); any thread.
         */
        @JvmStatic
        public fun read(file: File): PmtilesInfo = JSONObject(native { NativeBridge.basemapInfo(file.path) }).let { json ->
            val b = json.getJSONArray("bounds")
            PmtilesInfo(
                minZoom = json.getInt("min_zoom"),
                maxZoom = json.getInt("max_zoom"),
                bounds = Bbox(b.getDouble(0), b.getDouble(1), b.getDouble(2), b.getDouble(3)),
                addressedTiles = json.getLong("addressed_tiles"),
                tileEntries = json.getLong("tile_entries"),
                tileContents = json.getLong("tile_contents"),
                fileBytes = json.getLong("file_bytes"),
            )
        }
    }
}

/** One HTTP range request of the extract engine (bytes `offset until offset + length`). */
internal data class ByteRange(val id: Long, val offset: Long, val length: Long) {
    companion object {
        fun fromJson(json: JSONObject) = ByteRange(json.getLong("id"), json.getLong("offset"), json.getLong("length"))
        fun listFromJson(text: String): List<ByteRange> =
            JSONArray(text).let { array -> List(array.length()) { fromJson(array.getJSONObject(it)) } }
    }
}

/** What an extract did, for the sidecar and for measurements. */
internal data class ExtractStats(
    val requests: Long,
    val transferredBytes: Long,
    val addressedTiles: Long,
    val archiveBytes: Long,
)

/**
 * Drives one basemap extract: the Kotlin half of the sans-IO engine in
 * `src/basemap` (see `include/cantino.h`).
 *
 * Threads: the native plan and assembler are confined to the thread that
 * created them, so every native call of one extract runs on [native], a
 * private single thread. HTTP runs on [Dispatchers.IO], up to
 * [AreaConfig.basemapParallelism] requests at a time; each response is then
 * handed to the native thread. Directory responses (small) go across as
 * bytes; tile ranges are streamed to a temporary file in [workDir] and the
 * engine reads that file, so tile data never sits in the JVM heap (at most
 * `parallelism` range files exist at once).
 *
 * Failure: HTTP failures follow [SliceHttp]'s classes and are retried inline
 * per range ([retryingInline]); a server ignoring Range is permanent. Engine
 * errors (not a PMTiles v3 archive, unclustered, unsupported compression)
 * are permanent. Cancellation aborts in-flight requests. On every exit the
 * native handles are freed on their thread; an unfinished assembler deletes
 * its staging file, so only a successful [run] leaves [output] behind.
 */
internal class BasemapExtract(
    private val http: SliceHttp,
    private val config: AreaConfig,
    private val workDir: File,
) {
    /**
     * Extracts [bbox] from [url] into [output] (via `<output>.part`; same
     * directory, so the final rename is atomic). [onProgress] gets (phase,
     * bytes, total) and may be called from several threads.
     */
    suspend fun run(
        url: String,
        bbox: Bbox,
        maxZoom: Int,
        overfetch: Double,
        output: File,
        onProgress: suspend (BasemapPhase, Long, Long?) -> Unit,
    ): ExtractStats {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "osm-basemap-native") }
        val native = executor.asCoroutineDispatcher()
        var plan = 0L
        var assembler = 0L
        val requests = AtomicLong()
        val transferred = AtomicLong()
        try {
            plan = withContext(native) {
                engine { NativeBridge.basemapPlanNew(bbox.toJson().toString(), -1, maxZoom, overfetch) }
            }

            // --- Directory phase: header + root, then leaves (and metadata
            // if outside the first 16 KiB), one batch per round.
            onProgress(BasemapPhase.DIRECTORIES, 0, null)
            var batch = listOf(withContext(native) { ByteRange.fromJson(JSONObject(engine { NativeBridge.basemapPlanFirstRequest(plan) })) })
            var tiles: JSONObject? = null
            while (tiles == null) {
                check(batch.isNotEmpty()) { "extract plan stalled without outstanding requests" }
                val next = mutableListOf<ByteRange>() // touched only on the native thread
                fetchAll(batch) { range ->
                    val bytes = retryingInline(config) {
                        http.getRange(url, range.offset, range.length, allowShort = range.id == 0L)
                    }
                    requests.incrementAndGet()
                    transferred.addAndGet(bytes.size.toLong())
                    onProgress(BasemapPhase.DIRECTORIES, transferred.get(), null)
                    withContext(native) {
                        val step = engine { NativeBridge.basemapPlanFeed(plan, range.id, bytes) }
                        // Step JSON: "wait" | {"fetch":[...]} | {"tiles_ready":{...}}
                        if (step.trim() != "\"wait\"") {
                            val json = JSONObject(step)
                            json.optJSONArray("fetch")?.let { next += ByteRange.listFromJson(it.toString()) }
                            json.optJSONObject("tiles_ready")?.let { tiles = it }
                        }
                    }
                }
                batch = next
            }
            val tilePlan = tiles!!

            // --- Tile phase: the assembler owns `<output>.part` from here.
            val staging = File(output.parentFile, "${output.name}.part")
            assembler = withContext(native) {
                val consumed = plan
                plan = 0L // into_assembler consumes the plan whatever the outcome
                engine { NativeBridge.basemapPlanIntoAssembler(consumed, staging.path) }
            }
            val total = tilePlan.getLong("transfer_bytes")
            val remaining = withContext(native) { ByteRange.listFromJson(engine { NativeBridge.basemapAsmRemaining(assembler) }) }
            val tileBytes = AtomicLong()
            onProgress(BasemapPhase.TILES, 0, total)
            fetchAll(remaining) { range ->
                val part = File(workDir, "range-${range.id}.part")
                try {
                    retryingInline(config) { http.downloadRange(url, range.offset, range.length, part) }
                    withContext(native) { engine { NativeBridge.basemapAsmWriteRangeFile(assembler, range.id, part.path) } }
                } finally {
                    part.delete()
                }
                requests.incrementAndGet()
                transferred.addAndGet(range.length)
                onProgress(BasemapPhase.TILES, tileBytes.addAndGet(range.length), total)
            }
            withContext(native) { engine { NativeBridge.basemapAsmFinish(assembler, output.path) } }
            return ExtractStats(
                requests = requests.get(),
                transferredBytes = transferred.get(),
                addressedTiles = tilePlan.getLong("addressed_tiles"),
                archiveBytes = tilePlan.getLong("archive_bytes"),
            )
        } finally {
            // Free on the owner thread, even when cancelled. Freeing an
            // unfinished assembler deletes its staging file.
            withContext(NonCancellable + native) {
                if (plan != 0L) runCatching { NativeBridge.basemapPlanFree(plan) }
                if (assembler != 0L) runCatching { NativeBridge.basemapAsmFree(assembler) }
            }
            executor.shutdown()
        }
    }

    /** Runs [block] for every range, [AreaConfig.basemapParallelism] at a time; the first failure cancels the rest. */
    private suspend fun fetchAll(ranges: List<ByteRange>, block: suspend (ByteRange) -> Unit) = coroutineScope {
        val permits = Semaphore(config.basemapParallelism.coerceAtLeast(1))
        for (range in ranges) {
            launch(Dispatchers.IO) { permits.withPermit { block(range) } }
        }
    }

    /** Engine rejections (bad archive, unsupported format) are permanent: retrying the same source cannot help. */
    private inline fun <T> engine(block: () -> T): T = try {
        native(block)
    } catch (error: CantinoException) {
        throw DownloadFailure.Permanent("basemap extract: ${error.message}", error)
    }
}

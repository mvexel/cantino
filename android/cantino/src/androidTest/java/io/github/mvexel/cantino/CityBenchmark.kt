package io.github.mvexel.cantino

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.Executors

/**
 * Phase 2 go/no-go measurement on a real extract. Skipped unless run with
 *   -Pandroid.testInstrumentationRunnerArguments.pbf=/data/local/tmp/cantino/city.osm.pbf
 * (see scripts/bench-android.sh). Prints one JSON line tagged CANTINO_BENCH.
 *
 * Peak RSS is the process high-water mark (VmHWM), which includes the test
 * runtime and SQLite's page cache.
 */
@RunWith(AndroidJUnit4::class)
class CityBenchmark {
    private val args = InstrumentationRegistry.getArguments()
    private val target = InstrumentationRegistry.getInstrumentation().targetContext

    private fun status(field: String): Long =
        File("/proc/self/status").readLines().first { it.startsWith("$field:") }
            .split(Regex("\\s+"))[1].toLong() * 1024

    private fun percentile(samples: List<Double>, p: Double) =
        samples.sorted()[((samples.size - 1) * p).toInt()]

    private fun timeMs(block: () -> Unit): Double {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1e6
    }

    @Test
    fun importAndQueryCity() {
        val pbfArg = args.getString("pbf")
        assumeTrue("pass -e pbf <path> to run", pbfArg != null)
        val source = File(pbfArg!!)
        val directory = File(target.filesDir, "bench").apply { deleteRecursively(); mkdirs() }
        // Copy into app storage so import reads from the same filesystem a real
        // download would land on.
        val input = File(directory, "city.osm.pbf")
        source.copyTo(input, overwrite = true)
        val area = File(directory, "city.sqlite")
        val result = JSONObject()
        val worker = Executors.newSingleThreadExecutor()
        try {
            worker.submit {
                val rssBefore = status("VmRSS")
                lateinit var report: ImportReport
                val importMs = timeMs { report = OsmStore.importArea(input.path, area.path) }
                val hwmAfterImport = status("VmHWM")
                result.put("pbf_bytes", input.length())
                    .put("db_bytes", report.databaseBytes)
                    .put("db_file_bytes", area.length())
                    .put("nodes", report.counts.nodes).put("ways", report.counts.ways).put("relations", report.counts.relations)
                    .put("import_ms", importMs)
                    .put("rss_before_bytes", rssBefore)
                    .put("hwm_after_import_bytes", hwmAfterImport)

                OsmStore.open(area.path).use { store ->
                    val cafes = Query(tags = listOf(TagFilter.Equals("amenity", "cafe")), limit = 1000)
                    // Downtown Salt Lake City, roughly 1 km square.
                    val downtown = Bbox(-111.897, 40.760, -111.885, 40.769)
                    val nearby = Query(tags = listOf(TagFilter.Equals("amenity", "cafe")), bbox = downtown)
                    var cafeCount = 0
                    val coldTagMs = timeMs { cafeCount = store.query(cafes).size }
                    val tag = List(20) { timeMs { store.query(cafes) } }
                    val bbox = List(50) { timeMs { store.query(nearby) } }
                    val someId = store.query(cafes).first().id
                    val get = List(200) { timeMs { store.get(someId) } }
                    result.put("cafes", cafeCount)
                        .put("downtown_cafes", store.query(nearby).size)
                        .put("tag_query_cold_ms", coldTagMs)
                        .put("tag_query_p50_ms", percentile(tag, 0.5)).put("tag_query_p95_ms", percentile(tag, 0.95))
                        .put("bbox_query_p50_ms", percentile(bbox, 0.5)).put("bbox_query_p95_ms", percentile(bbox, 0.95))
                        .put("get_p50_ms", percentile(get, 0.5)).put("get_p95_ms", percentile(get, 0.95))
                }
                result.put("hwm_final_bytes", status("VmHWM"))
            }.get()
        } finally {
            worker.shutdown()
            directory.deleteRecursively()
        }
        Log.i("CANTINO_BENCH", result.toString())
        println("CANTINO_BENCH $result")
    }
}

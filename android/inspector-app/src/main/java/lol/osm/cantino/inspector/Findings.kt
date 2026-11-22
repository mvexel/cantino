package lol.osm.cantino.inspector

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import lol.osm.cantino.OsmId
import lol.osm.cantino.OsmKind
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** A stable point that lets an exported finding be reviewed on a map. */
data class FindingAnchor(val lat: Double, val lon: Double) {
    companion object {
        /** Centre of the object's available geometry; null when the extract has none. */
        fun from(shape: Shape): FindingAnchor? {
            val points = shape.allPoints()
            if (points.isEmpty()) return null
            return FindingAnchor(
                lat = (points.minOf { it.lat } + points.maxOf { it.lat }) / 2.0,
                lon = (points.minOf { it.lon } + points.maxOf { it.lon }) / 2.0,
            )
        }
    }
}

enum class FindingStatus { DRAFT, REVIEWED }

/**
 * One app-owned observation. The base object stays in Cantino's immutable
 * area; this value preserves enough provenance for a later human review.
 */
data class Finding(
    val osmId: OsmId,
    val baseVersion: Long?,
    val baseTags: Map<String, String>,
    val snapshotTimestamp: String?,
    val observedAt: Instant,
    val proposedTags: Map<String, String>,
    val note: String,
    val evidence: String,
    val status: FindingStatus,
    val anchor: FindingAnchor?,
)

/** Human-editable `key=value` lines used by the finding form. */
object ProposedTags {
    fun parse(value: String): Map<String, String> {
        val tags = linkedMapOf<String, String>()
        value.lineSequence().forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEachIndexed
            val separator = line.indexOf('=')
            require(separator > 0) { "Line ${index + 1} must be key=value." }
            val key = line.substring(0, separator).trim()
            val tagValue = line.substring(separator + 1).trim()
            require(key.isNotEmpty()) { "Line ${index + 1} has no tag key." }
            require(tagValue.isNotEmpty()) { "Line ${index + 1} has no tag value." }
            require(tags.put(key, tagValue) == null) { "Tag $key is listed more than once." }
        }
        return tags
    }

    fun format(tags: Map<String, String>): String =
        tags.toSortedMap().entries.joinToString("\n") { (key, value) -> "$key=$value" }
}

/** Small file-backed repository separate from the read-only Cantino database. */
class FindingsRepository internal constructor(private val file: File) {
    companion object {
        private const val FILE_NAME = "field-findings.json"
        private val IO_LOCK = Any()

        fun open(context: Context): FindingsRepository =
            FindingsRepository(File(context.applicationContext.filesDir, FILE_NAME))
    }

    fun all(): List<Finding> = synchronized(IO_LOCK) { read() }

    fun get(id: OsmId): Finding? = synchronized(IO_LOCK) { read().firstOrNull { it.osmId == id } }

    fun upsert(finding: Finding) = synchronized(IO_LOCK) {
        val findings = read().filterNot { it.osmId == finding.osmId } + finding
        write(findings.sortedWith(FINDING_ORDER))
    }

    fun delete(id: OsmId) = synchronized(IO_LOCK) {
        val current = read()
        val remaining = current.filterNot { it.osmId == id }
        if (remaining.size != current.size) write(remaining)
    }

    private fun read(): List<Finding> {
        if (!file.exists()) return emptyList()
        return try {
            FindingsJson.decode(file.readText(StandardCharsets.UTF_8))
        } catch (error: JSONException) {
            throw IOException("Could not read ${file.name}: ${error.message}", error)
        } catch (error: IllegalArgumentException) {
            throw IOException("Could not read ${file.name}: ${error.message}", error)
        }
    }

    private fun write(findings: List<Finding>) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, ".${file.name}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(FindingsJson.encode(findings).toByteArray(StandardCharsets.UTF_8))
                output.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (error: IOException) {
            throw IOException("Could not save ${file.name}: ${error.message}", error)
        } finally {
            temporary.delete()
        }
    }
}

internal object FindingsJson {
    private const val VERSION = 1

    fun encode(findings: List<Finding>): String = JSONObject()
        .put("version", VERSION)
        .put("findings", JSONArray().apply { findings.sortedWith(FINDING_ORDER).forEach { put(encodeFinding(it)) } })
        .toString(2)

    fun decode(value: String): List<Finding> {
        val root = JSONObject(value)
        require(root.getInt("version") == VERSION) { "unsupported findings version ${root.getInt("version")}" }
        val array = root.getJSONArray("findings")
        return List(array.length()) { decodeFinding(array.getJSONObject(it)) }.sortedWith(FINDING_ORDER)
    }

    private fun encodeFinding(finding: Finding): JSONObject = JSONObject()
        .put("osm_type", finding.osmId.kind.name.lowercase())
        .put("osm_id", finding.osmId.id)
        .put("base_version", finding.baseVersion ?: JSONObject.NULL)
        .put("base_tags", tags(finding.baseTags))
        .put("snapshot_timestamp", finding.snapshotTimestamp ?: JSONObject.NULL)
        .put("observed_at", finding.observedAt.toString())
        .put("proposed_tags", tags(finding.proposedTags))
        .put("note", finding.note)
        .put("evidence", finding.evidence)
        .put("status", finding.status.name.lowercase())
        .put(
            "anchor",
            finding.anchor?.let { JSONObject().put("lat", it.lat).put("lon", it.lon) } ?: JSONObject.NULL,
        )

    private fun decodeFinding(json: JSONObject): Finding = Finding(
        osmId = OsmId(OsmKind.valueOf(json.getString("osm_type").uppercase()), json.getLong("osm_id")),
        baseVersion = if (json.isNull("base_version")) null else json.getLong("base_version"),
        baseTags = tags(json.getJSONObject("base_tags")),
        snapshotTimestamp = if (json.isNull("snapshot_timestamp")) null else json.getString("snapshot_timestamp"),
        observedAt = Instant.parse(json.getString("observed_at")),
        proposedTags = tags(json.getJSONObject("proposed_tags")),
        note = json.getString("note"),
        evidence = json.getString("evidence"),
        status = FindingStatus.valueOf(json.getString("status").uppercase()),
        anchor = if (json.isNull("anchor")) null else json.getJSONObject("anchor").let {
            FindingAnchor(it.getDouble("lat"), it.getDouble("lon"))
        },
    )

    private fun tags(tags: Map<String, String>): JSONObject =
        JSONObject().apply { tags.toSortedMap().forEach { (key, value) -> put(key, value) } }

    private fun tags(json: JSONObject): Map<String, String> =
        json.keys().asSequence().sorted().associateWith { json.getString(it) }
}

/** Stable pilot exports for a human review queue; this never writes to OSM. */
object FindingsExport {
    fun geoJson(findings: List<Finding>, generatedAt: Instant = Instant.now()): String {
        val features = JSONArray()
        findings.filter { it.status == FindingStatus.REVIEWED }.sortedWith(FINDING_ORDER).forEach { finding ->
            val properties = JSONObject()
                .put("osm_type", finding.osmId.kind.name.lowercase())
                .put("osm_id", finding.osmId.id)
                .put("base_version", finding.baseVersion ?: JSONObject.NULL)
                .put("base_tags", tags(finding.baseTags))
                .put("snapshot_timestamp", finding.snapshotTimestamp ?: JSONObject.NULL)
                .put("observed_at", finding.observedAt.toString())
                .put("status", finding.status.name.lowercase())
                .put("proposed_tags", tags(finding.proposedTags))
                .put("note", finding.note)
                .put("evidence", finding.evidence)
                .put("osm_url", Labels.osmOrgUrl(finding.osmId))
            val geometry = finding.anchor?.let {
                JSONObject()
                    .put("type", "Point")
                    .put("coordinates", JSONArray().put(it.lon).put(it.lat))
            } ?: JSONObject.NULL
            features.put(JSONObject().put("type", "Feature").put("properties", properties).put("geometry", geometry))
        }
        return JSONObject()
            .put("type", "FeatureCollection")
            .put("name", "Cantino reviewed field findings")
            .put("generated_at", generatedAt.toString())
            .put("features", features)
            .toString(2)
    }

    fun csv(findings: List<Finding>): String {
        val rows = mutableListOf(
            listOf(
                "osm_type", "osm_id", "base_version", "snapshot_timestamp", "observed_at", "status",
                "latitude", "longitude", "proposed_tags", "note", "evidence", "osm_url",
            ),
        )
        findings.filter { it.status == FindingStatus.REVIEWED }.sortedWith(FINDING_ORDER).forEach { finding ->
            rows += listOf(
                finding.osmId.kind.name.lowercase(),
                finding.osmId.id.toString(),
                finding.baseVersion?.toString().orEmpty(),
                finding.snapshotTimestamp.orEmpty(),
                finding.observedAt.toString(),
                finding.status.name.lowercase(),
                finding.anchor?.lat?.toString().orEmpty(),
                finding.anchor?.lon?.toString().orEmpty(),
                ProposedTags.format(finding.proposedTags),
                finding.note,
                finding.evidence,
                Labels.osmOrgUrl(finding.osmId),
            )
        }
        return rows.joinToString("\r\n", postfix = "\r\n") { row -> row.joinToString(",", transform = ::csvCell) }
    }

    private fun tags(tags: Map<String, String>): JSONObject =
        JSONObject().apply { tags.toSortedMap().forEach { (key, value) -> put(key, value) } }

    private fun csvCell(value: String): String {
        val normalized = value.replace("\r\n", "\n").replace('\r', '\n')
        return if (normalized.any { it == ',' || it == '"' || it == '\n' }) {
            "\"${normalized.replace("\"", "\"\"")}\""
        } else {
            normalized
        }
    }
}

private val FINDING_ORDER = compareBy<Finding>({ it.osmId.kind.ordinal }, { it.osmId.id })

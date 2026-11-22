package lol.osm.cantino.inspector

import java.io.File
import java.time.Instant
import lol.osm.cantino.OsmId
import lol.osm.cantino.OsmKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FindingsTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private fun finding(
        status: FindingStatus = FindingStatus.DRAFT,
        note: String = "Level entrance",
    ) = Finding(
        osmId = OsmId(OsmKind.NODE, 123),
        baseVersion = 7,
        baseTags = linkedMapOf("name" to "Library", "entrance" to "main"),
        snapshotTimestamp = "2026-11-22T12:00:00Z",
        observedAt = Instant.parse("2026-11-22T14:30:00Z"),
        proposedTags = linkedMapOf("wheelchair" to "yes", "door:width" to "1.02"),
        note = note,
        evidence = "Measured clear width: 102 cm",
        status = status,
        anchor = FindingAnchor(40.7608, -111.8910),
    )

    @Test
    fun proposedTagsParseAndFormatDeterministically() {
        assertEquals(
            linkedMapOf("wheelchair" to "yes", "kerb" to "lowered"),
            ProposedTags.parse(" wheelchair = yes\n\nkerb=lowered "),
        )
        assertEquals("kerb=lowered\nwheelchair=yes", ProposedTags.format(mapOf("wheelchair" to "yes", "kerb" to "lowered")))
        assertEquals("Line 1 must be key=value.", parseError("wheelchair"))
        assertEquals("Tag wheelchair is listed more than once.", parseError("wheelchair=yes\nwheelchair=no"))
    }

    @Test
    fun jsonRoundTripPreservesProvenanceAndRepositoryUpdatesAtomically() {
        val original = finding()
        assertEquals(listOf(original), FindingsJson.decode(FindingsJson.encode(listOf(original))))

        val file = File(temporary.root, "findings.json")
        val repository = FindingsRepository(file)
        assertTrue(repository.all().isEmpty())
        repository.upsert(original)
        assertEquals(original, repository.get(original.osmId))
        repository.upsert(original.copy(status = FindingStatus.REVIEWED, note = "Reviewed"))
        assertEquals(1, repository.all().size)
        assertEquals("Reviewed", repository.get(original.osmId)?.note)
        assertFalse(File(temporary.root, ".findings.json.tmp").exists())
        repository.delete(original.osmId)
        assertNull(repository.get(original.osmId))
    }

    @Test
    fun exportsCarryReviewDataAndEscapeCsv() {
        val reviewed = finding(status = FindingStatus.REVIEWED, note = "Door, marked \"accessible\"\nChecked twice")
        val draft = finding(note = "Draft only").copy(osmId = OsmId(OsmKind.NODE, 124))
        val geoJson = JSONObject(FindingsExport.geoJson(listOf(draft, reviewed), Instant.parse("2026-11-22T16:00:00Z")))
        assertEquals(1, geoJson.getJSONArray("features").length())
        val feature = geoJson.getJSONArray("features").getJSONObject(0)
        val properties = feature.getJSONObject("properties")
        assertEquals("FeatureCollection", geoJson.getString("type"))
        assertEquals("2026-11-22T16:00:00Z", geoJson.getString("generated_at"))
        assertEquals(7L, properties.getLong("base_version"))
        assertEquals("yes", properties.getJSONObject("proposed_tags").getString("wheelchair"))
        assertEquals("https://www.openstreetmap.org/node/123", properties.getString("osm_url"))
        assertEquals(-111.8910, feature.getJSONObject("geometry").getJSONArray("coordinates").getDouble(0), 0.0)

        val csv = FindingsExport.csv(listOf(draft, reviewed))
        assertTrue(csv.startsWith("osm_type,osm_id,base_version"))
        assertTrue(csv.contains("\"door:width=1.02\nwheelchair=yes\""))
        assertTrue(csv.contains("\"Door, marked \"\"accessible\"\"\nChecked twice\""))
        assertFalse(csv.contains("Draft only"))
        assertTrue(csv.endsWith("\r\n"))
    }

    @Test
    fun geometryAnchorUsesAvailableBounds() {
        assertEquals(
            FindingAnchor(41.0, -111.0),
            FindingAnchor.from(Shape(lines = listOf(listOf(LatLon(40.0, -112.0), LatLon(42.0, -110.0))))),
        )
        assertNull(FindingAnchor.from(Shape()))
    }

    private fun parseError(value: String): String = try {
        ProposedTags.parse(value)
        fail("expected a parse error")
        ""
    } catch (error: IllegalArgumentException) {
        error.message!!
    }
}

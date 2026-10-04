package lol.osm.cantino.inspector

import lol.osm.cantino.TagFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The query bar's syntax. Same cases as ios/inspector-app QueryBarTests. */
class QueryBarTest {
    private fun error(input: String): String = try {
        QueryBar.parse(input)
        fail("expected a syntax error for $input")
        ""
    } catch (e: QuerySyntaxException) {
        e.message!!
    }

    @Test
    fun equalsExistsAndNotExists() {
        assertEquals(listOf(TagFilter.Equals("amenity", "cafe")), QueryBar.parse("amenity=cafe"))
        assertEquals(listOf(TagFilter.Exists("shop")), QueryBar.parse("shop=*"))
        assertEquals(listOf(TagFilter.NotExists("opening_hours")), QueryBar.parse("!opening_hours"))
    }

    @Test
    fun termsAreAndedInOrder() {
        assertEquals(
            listOf(TagFilter.Equals("highway", "crossing"), TagFilter.NotExists("crossing"), TagFilter.Exists("kerb")),
            QueryBar.parse("  highway=crossing   !crossing\tkerb=* "),
        )
    }

    @Test
    fun emptyInputIsNoFilter() {
        assertEquals(emptyList<TagFilter>(), QueryBar.parse(""))
        assertEquals(emptyList<TagFilter>(), QueryBar.parse("   "))
    }

    @Test
    fun quotedValuesKeepSpacesAndLiteralStar() {
        assertEquals(listOf(TagFilter.Equals("name", "Caffe Ibis")), QueryBar.parse("name=\"Caffe Ibis\""))
        assertEquals(listOf(TagFilter.Equals("note", "*")), QueryBar.parse("note=\"*\""))
        assertEquals(listOf(TagFilter.Equals("name", "")), QueryBar.parse("name=\"\""))
    }

    @Test
    fun valuesAreExactAndCaseSensitive() {
        assertEquals(listOf(TagFilter.Equals("Amenity", "Cafe")), QueryBar.parse("Amenity=Cafe"))
        // Only the first "=" separates key and value.
        assertEquals(listOf(TagFilter.Equals("description", "a=b")), QueryBar.parse("description=a=b"))
    }

    @Test
    fun syntaxErrorsExplainTheFix() {
        assertEquals("\"amenity\" needs a value: use amenity=value, amenity=* for any value, or !amenity for missing.", error("amenity"))
        assertEquals("\"=cafe\" has no key before \"=\".", error("=cafe"))
        assertEquals("\"amenity=\" has no value: use amenity=* for any value, or amenity=\"\" for an empty one.", error("amenity="))
        assertEquals("\"!\" needs a key, as in !opening_hours.", error("!"))
        assertEquals("\"!crossing=no\": a missing-tag term takes no value; write !crossing.", error("!crossing=no"))
        assertEquals("Unclosed quote.", error("name=\"Caffe"))
    }

    @Test
    fun formatRoundTrips() {
        listOf("amenity=cafe !opening_hours shop=*", "name=\"Caffe Ibis\"", "note=\"*\"", "name=\"\"").forEach {
            assertEquals(it, QueryBar.format(QueryBar.parse(it)))
        }
    }

    @Test
    fun checksAndAcceptanceQueriesParse() {
        (Checks.ALL.map { it.query } + Checks.ACCEPTANCE).forEach { query ->
            val filters = QueryBar.parse(query)
            // Every preset has a filter that can drive the query over the whole area.
            assertTrue(query, filters.any { it !is TagFilter.NotExists })
        }
        assertEquals(14, Checks.ACCEPTANCE.size)
    }
}

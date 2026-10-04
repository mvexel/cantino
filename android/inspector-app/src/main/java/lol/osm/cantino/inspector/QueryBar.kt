package lol.osm.cantino.inspector

import lol.osm.cantino.Bbox
import lol.osm.cantino.OsmId
import lol.osm.cantino.OsmKind
import lol.osm.cantino.OsmObject
import lol.osm.cantino.OsmStore
import lol.osm.cantino.Query
import lol.osm.cantino.TagFilter

/*
 * The query bar: a tiny text syntax over Cantino's TagFilter, and the paging
 * that runs it. Mirrors ios/inspector-app QueryBar.swift (same syntax, same
 * error texts).
 *
 *   amenity=cafe        TagFilter.Equals  exact, case-sensitive value
 *   shop=*              TagFilter.Exists  any value
 *   !opening_hours      TagFilter.NotExists
 *   name="Caffe Ibis"   a quoted value may contain spaces; "*" quoted is the literal value *
 *
 * Terms are separated by spaces and ANDed. There is no OR and no pattern
 * matching (Cantino has neither). A query of only `!k` terms needs a bbox
 * ("this view only"); without one Cantino rejects it, and the app shows
 * Cantino's message verbatim.
 */

class QuerySyntaxException(message: String) : IllegalArgumentException(message)

object QueryBar {
    /** Parses the bar's text into ANDed filters. Empty text is no filter at all. Throws [QuerySyntaxException]. */
    fun parse(input: String): List<TagFilter> = tokenize(input).map(::term)

    /** The canonical text of [filters] (what the bar shows after a preset). */
    fun format(filters: List<TagFilter>): String = filters.joinToString(" ") { filter ->
        when (filter) {
            is TagFilter.Equals -> "${filter.key}=${quoteIfNeeded(filter.value)}"
            is TagFilter.Exists -> "${filter.key}=*"
            is TagFilter.NotExists -> "!${filter.key}"
        }
    }

    private class Token(val text: String, val quoted: Boolean)

    private fun tokenize(input: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val current = StringBuilder()
        var quoted = false
        var inQuotes = false
        var started = false
        for (c in input) {
            when {
                c == '"' -> { inQuotes = !inQuotes; quoted = true; started = true }
                c.isWhitespace() && !inQuotes -> {
                    if (started) tokens += Token(current.toString(), quoted)
                    current.clear(); quoted = false; started = false
                }
                else -> { current.append(c); started = true }
            }
        }
        if (inQuotes) throw QuerySyntaxException("Unclosed quote.")
        if (started) tokens += Token(current.toString(), quoted)
        return tokens
    }

    private fun term(token: Token): TagFilter {
        val text = token.text
        if (text.startsWith("!")) {
            val key = text.substring(1)
            if (key.isEmpty()) throw QuerySyntaxException("\"!\" needs a key, as in !opening_hours.")
            if ('=' in key) throw QuerySyntaxException("\"$text\": a missing-tag term takes no value; write !${key.substringBefore('=')}.")
            return TagFilter.NotExists(key)
        }
        val eq = text.indexOf('=')
        if (eq < 0) throw QuerySyntaxException("\"$text\" needs a value: use $text=value, $text=* for any value, or !$text for missing.")
        val key = text.substring(0, eq)
        val value = text.substring(eq + 1)
        if (key.isEmpty()) throw QuerySyntaxException("\"$text\" has no key before \"=\".")
        if (value.isEmpty() && !token.quoted) throw QuerySyntaxException("\"$text\" has no value: use $key=* for any value, or $key=\"\" for an empty one.")
        return if (value == "*" && !token.quoted) TagFilter.Exists(key) else TagFilter.Equals(key, value)
    }

    private fun quoteIfNeeded(value: String) =
        if (value.isEmpty() || value == "*" || value.any { it.isWhitespace() }) "\"$value\"" else value
}

/** A validator-style preset: a query with a reason. */
data class Check(val title: String, val query: String, val why: String)

object Checks {
    /** The query bar's presets (the "Checks" menu). Each is a plain query the user can edit. */
    val ALL = listOf(
        Check("Amenities without opening_hours", "amenity=* !opening_hours", "Shops, cafés and services whose hours are not mapped."),
        Check("Crossings without crossing=*", "highway=crossing !crossing", "Crossing nodes that do not say whether they are marked, signalled or unmarked."),
        Check("Sidewalks without surface", "footway=sidewalk !surface", "Sidewalks mapped as separate ways, without a surface."),
        Check("Kerbs without kerb=*", "barrier=kerb !kerb", "Kerbs that do not say whether they are lowered, flush or raised."),
        Check("Steps without handrail", "highway=steps !handrail", "Steps without handrail information."),
        Check("Benches without backrest", "amenity=bench !backrest", "Benches without backrest information."),
    )

    /**
     * The acceptance queries (docs/guide/inspector-app.md): counted over the
     * whole area by the debug `counts` launch option, on both platforms.
     */
    val ACCEPTANCE = listOf(
        "amenity=cafe", "amenity=restaurant", "amenity=bench", "amenity=drinking_water", "amenity=toilets",
        "amenity=bicycle_parking", "amenity=* !opening_hours",
        "highway=footway footway=sidewalk", "footway=crossing", "highway=crossing crossing=*",
        "highway=crossing !crossing", "barrier=kerb kerb=*", "highway=steps", "highway=pedestrian",
    )
}

/** Objects per kind, for counts. */
data class KindCounts(val nodes: Long = 0, val ways: Long = 0, val relations: Long = 0) {
    val total get() = nodes + ways + relations
    operator fun plus(obj: OsmObject) = when (obj.id.kind) {
        OsmKind.NODE -> copy(nodes = nodes + 1)
        OsmKind.WAY -> copy(ways = ways + 1)
        OsmKind.RELATION -> copy(relations = relations + 1)
    }
    override fun toString() = "$total ($nodes n, $ways w, $relations r)"
}

/** Runs query-bar filters on the store's thread. */
object Queries {
    /** Results per page in the list and on the map ("load more" fetches the next). */
    const val PAGE = 500
    private const val COUNT_PAGE = 10_000

    /** One page after [after] (keyset pagination: nodes, ways, relations, each by ID). */
    fun page(store: OsmStore, filters: List<TagFilter>, bbox: Bbox?, after: OsmId?, limit: Int = PAGE): List<OsmObject> =
        store.query(Query(tags = filters, bbox = bbox, after = after, limit = limit))

    /** Every match, counted per kind (pages of 10 000; no geometry is read). */
    fun count(store: OsmStore, filters: List<TagFilter>, bbox: Bbox?): KindCounts {
        var counts = KindCounts()
        var after: OsmId? = null
        while (true) {
            val page = page(store, filters, bbox, after, COUNT_PAGE)
            page.forEach { counts += it }
            if (page.size < COUNT_PAGE) return counts
            after = page.last().id
        }
    }
}

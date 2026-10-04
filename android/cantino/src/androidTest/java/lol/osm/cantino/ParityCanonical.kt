package lol.osm.cantino

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * The canonical JSON form of tests/parity/README.md ("Canonical form"),
 * implemented independently of org.json's own writer (which escapes `/`,
 * drops `.0` and sorts nothing).
 *
 * A canonical value is a tree of: null, Boolean, Int/Long (written as plain
 * decimal), Double (written as a float, always with a fraction), String,
 * `List<*>` and `Map<String, *>`. Nothing else is accepted, so a stray
 * `JSONObject` or `Float` fails loudly instead of being written by accident.
 */
internal object Canonical {
    fun write(value: Any?): String = StringBuilder().also { append(it, value) }.toString()

    /** Object keys compare by Unicode code point (equivalently by UTF-8 bytes), not by UTF-16 unit. */
    val codePointOrder: Comparator<String> = Comparator { a, b ->
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val x = a.codePointAt(i)
            val y = b.codePointAt(j)
            if (x != y) return@Comparator x.compareTo(y)
            i += Character.charCount(x)
            j += Character.charCount(y)
        }
        (a.length - i).compareTo(b.length - j)
    }

    private fun append(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is Boolean -> sb.append(value.toString())
            is Int -> sb.append(value.toString())
            is Long -> sb.append(value.toString())
            is Double -> sb.append(float(value))
            is String -> string(sb, value)
            is List<*> -> {
                sb.append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) sb.append(',')
                    append(sb, item)
                }
                sb.append(']')
            }
            is Map<*, *> -> {
                sb.append('{')
                value.keys.map { it as String }.sortedWith(codePointOrder).forEachIndexed { index, key ->
                    if (index > 0) sb.append(',')
                    string(sb, key)
                    sb.append(':')
                    append(sb, value[key])
                }
                sb.append('}')
            }
            else -> error("not a canonical value: ${value::class.java.name}")
        }
    }

    private fun string(sb: StringBuilder, text: String) {
        sb.append('"')
        for (c in text) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\b' -> sb.append("\\b")
                c == '\t' -> sb.append("\\t")
                c == '\n' -> sb.append("\\n")
                c == '\u000c' -> sb.append("\\f")
                c == '\r' -> sb.append("\\r")
                c < ' ' -> sb.append("\\u00").append("0123456789abcdef"[c.code shr 4]).append("0123456789abcdef"[c.code and 15])
                else -> sb.append(c) // `/` and non-ASCII stay raw
            }
        }
        sb.append('"')
    }

    /**
     * Shortest decimal that round-trips to the same double, plain notation,
     * always with a fraction (`12.0`, `0.25`). Done with BigDecimal rounding
     * to 1..17 significant digits rather than `Double.toString`, whose
     * shortest-ness and exponent thresholds vary by runtime.
     */
    fun float(d: Double): String {
        require(d.isFinite()) { "non-finite float" }
        if (d == 0.0) return if (1.0 / d < 0) "-0.0" else "0.0"
        val exact = BigDecimal(d)
        for (digits in 1..17) {
            val rounded = exact.round(MathContext(digits, RoundingMode.HALF_EVEN))
            if (java.lang.Double.parseDouble(rounded.toString()) == d) {
                val plain = rounded.stripTrailingZeros().toPlainString()
                return if ('.' in plain) plain else "$plain.0"
            }
        }
        error("no round-tripping decimal for $d")
    }

    /** Parses native C ABI JSON into a canonical tree (integers as Long, floats as Double). */
    fun parse(text: String): Any? = convert(JSONTokener(text).nextValue())

    private fun convert(value: Any?): Any? = when {
        value == null || value === JSONObject.NULL -> null
        value is JSONObject -> value.keys().asSequence().associateWith { convert(value.get(it)) }
        value is JSONArray -> List(value.length()) { convert(value.get(it)) }
        value is Int -> value.toLong()
        value is Long || value is Double || value is Boolean || value is String -> value
        else -> error("unexpected JSON value ${value::class.java.name}")
    }
}

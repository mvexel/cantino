package io.github.mvexel.cantino.cafe

import java.time.DayOfWeek
import java.time.LocalDateTime

/*
 * Opening-hours interpretation for the café app.
 *
 * This lives in the APP, never in the framework core (CLAUDE.md: café policy
 * and opening-hours interpretation are app concerns). The core hands us the
 * raw `opening_hours` string; this file decides what we can say about it.
 *
 * Design rule: **unknown stays unknown.** The evaluator answers Open or
 * Closed only when the string is in the subset below AND the answer does not
 * depend on anything we do not know. Everything else is [OpenState.Unknown]
 * with a human-readable reason, and the UI shows the raw string next to it.
 * A missing tag is Unknown too, never Closed.
 *
 * Supported subset (a strict subset of https://wiki.openstreetmap.org/wiki/Key:opening_hours/specification):
 *
 *   hours     := rule ( (";" | ",") rule )* [";"]
 *   rule      := [days] timespec
 *   days      := dayItem ("," dayItem)*            dayItem := DAY | DAY "-" DAY | "PH"
 *   timespec  := "24/7" | "off" | "closed" | range ("," range)* ["open"]
 *   range     := H[H]:MM "-" H[H]:MM               end may be past midnight (22:00-02:00, 18:00-26:00)
 *
 * Semantics:
 * - ";" starts a *normal* rule: for the days it matches it replaces whatever
 *   earlier rules said ("Mo-Su 08:00-20:00; Su off" closes Sunday).
 * - "," between two complete rules starts an *additional* rule: its ranges are
 *   added to the matched days ("Mo-Fr 08:00-12:00, We 14:00-18:00").
 *   An additional "off" rule is rejected (Unknown): the spec is ambiguous.
 * - A rule without days applies to every day, including public holidays.
 * - Day names are matched case-insensitively ("mo-fr" is unambiguous).
 * - Ranges ending after midnight spill into the next day.
 * - PH (public holiday): we do not know whether today (or yesterday, for
 *   spill-over) is a holiday, so the string is evaluated for every
 *   combination; if the answers differ the result is Unknown. So
 *   "Mo-Fr 08:00-17:00; PH off" is Closed on a Saturday (certain) but
 *   Unknown on a weekday during business hours (could be a holiday).
 *
 * Everything else is Unknown, by design: month/date/week/year selectors,
 * school holidays (SH), nth weekdays ("Mo[1]"), sunrise/sunset, open ends
 * ("18:00+"), fallback rules ("||"), comments ("..."), "unknown", days
 * without a time ("Mo-Fr"), and any typo or unexpected character.
 *
 * Time zone: the caller passes the local time. The app uses the device's
 * clock and zone, which is right only while the device is in the café's zone
 * (OSM opening hours are in the place's local time).
 */

/** What we can say about "is it open now". */
sealed interface OpenState {
    data object Open : OpenState
    data object Closed : OpenState

    /** We cannot tell. [reason] is shown to the user next to the raw tag. */
    data class Unknown(val reason: String) : OpenState
}

object OpeningHours {
    /**
     * Evaluates [raw] (the `opening_hours` tag value, null when missing) at
     * local time [at]. Never throws.
     */
    fun evaluate(raw: String?, at: LocalDateTime): OpenState {
        if (raw == null) return OpenState.Unknown("no opening_hours tag")
        if (raw.isBlank()) return OpenState.Unknown("empty opening_hours tag")
        val rules = try {
            Parser(tokenize(raw)).parse()
        } catch (error: Unsupported) {
            return OpenState.Unknown(error.message ?: "unsupported opening_hours")
        }
        return evaluate(rules, at)
    }

    /** True when [raw] is fully inside the supported subset (used by tests and the detail screen). */
    fun isSupported(raw: String): Boolean = try {
        Parser(tokenize(raw)).parse(); true
    } catch (error: Unsupported) {
        false
    }

    // ---- evaluation --------------------------------------------------------

    private fun evaluate(rules: List<Rule>, at: LocalDateTime): OpenState {
        val usesHolidays = rules.any { it.days?.publicHoliday == true }
        // Enumerate what we don't know: is today / yesterday a public holiday?
        val combos = if (usesHolidays) {
            listOf(false to false, false to true, true to false, true to true)
        } else {
            listOf(false to false)
        }
        val answers = combos.map { (todayHoliday, yesterdayHoliday) ->
            openAt(rules, at, todayHoliday, yesterdayHoliday)
        }
        // A single Unknown, or disagreement between the combinations, makes the whole answer Unknown.
        answers.firstOrNull { it is OpenState.Unknown }?.let { return it }
        return if (answers.distinct().size == 1) {
            answers.first()
        } else {
            OpenState.Unknown("depends on whether today is a public holiday")
        }
    }

    private fun openAt(rules: List<Rule>, at: LocalDateTime, todayHoliday: Boolean, yesterdayHoliday: Boolean): OpenState {
        val minute = at.hour * 60 + at.minute
        val today = dayPlan(rules, at.dayOfWeek, todayHoliday)
        if (today.intervals.any { minute >= it.first && minute < it.last }) return OpenState.Open
        val yesterday = dayPlan(rules, at.dayOfWeek.minus(1), yesterdayHoliday)
        val spilled = yesterday.intervals.any { minute + DAY >= it.first && minute + DAY < it.last }
        if (spilled) {
            // "Mo-Su 18:00-02:00; Tu off" at Tu 01:00: does Tuesday's "off"
            // cancel Monday night's spill-over? The spec is not explicit and
            // implementations differ, so we refuse to guess.
            return if (today.explicitlyOff) {
                OpenState.Unknown("overnight hours from the previous day conflict with an \"off\" rule")
            } else {
                OpenState.Open
            }
        }
        return OpenState.Closed
    }

    /**
     * Opening intervals of one weekday, in minutes from that day's midnight
     * (the end may exceed 1440 for spill-over). Rules apply in order; see the
     * file comment for normal vs additional rules.
     */
    private fun dayPlan(rules: List<Rule>, day: DayOfWeek, holiday: Boolean): DayPlan {
        var intervals = emptyList<Interval>()
        var off = false
        for (rule in rules) {
            if (!(rule.days?.matches(day, holiday) ?: true)) continue
            if (rule.additional) {
                intervals = intervals + rule.intervals
            } else {
                intervals = rule.intervals
                off = rule.off
            }
        }
        return DayPlan(intervals, off)
    }

    private const val DAY = 24 * 60

    /** [first] inclusive, [last] exclusive, in minutes. */
    private data class Interval(val first: Int, val last: Int)

    private data class DayPlan(val intervals: List<Interval>, val explicitlyOff: Boolean)

    private class DaySet(val weekdays: Set<DayOfWeek>, val publicHoliday: Boolean) {
        fun matches(day: DayOfWeek, holiday: Boolean) = day in weekdays || (publicHoliday && holiday)
    }

    /** [days] null = every day. [off] rules have no intervals. */
    private class Rule(val days: DaySet?, val intervals: List<Interval>, val off: Boolean, val additional: Boolean)

    // ---- parsing -----------------------------------------------------------

    private class Unsupported(message: String) : Exception(message)

    private sealed interface Token {
        data class Word(val text: String) : Token
        data class Time(val minutes: Int, val text: String) : Token
        data class Punct(val char: Char) : Token
        data object AllWeek : Token // "24/7"
    }

    private val WEEKDAYS = mapOf(
        "mo" to DayOfWeek.MONDAY, "tu" to DayOfWeek.TUESDAY, "we" to DayOfWeek.WEDNESDAY,
        "th" to DayOfWeek.THURSDAY, "fr" to DayOfWeek.FRIDAY, "sa" to DayOfWeek.SATURDAY, "su" to DayOfWeek.SUNDAY,
    )

    private fun tokenize(raw: String): List<Token> {
        // Name the unsupported feature up front when a keyword gives it away
        // ("Dec 17-Dec 31 off" would otherwise fail on "17" with a vaguer reason).
        WORD.findAll(raw).map { it.value.lowercase() }.forEach { word ->
            when (word) {
                in MONTHS -> throw Unsupported("month/date selectors are not evaluated")
                "sh" -> throw Unsupported("school holidays (SH) are not evaluated")
                "week" -> throw Unsupported("week selectors are not evaluated")
                "sunrise", "sunset", "dawn", "dusk" -> throw Unsupported("sunrise/sunset times are not evaluated")
            }
        }
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c.isWhitespace() -> i++
                raw.startsWith("24/7", i) -> { tokens += Token.AllWeek; i += 4 }
                c.isDigit() -> {
                    // H:MM or HH:MM, nothing else (no "8h", "08.00", "0800").
                    val match = TIME.matchAt(raw, i) ?: throw Unsupported("unsupported time near \"${raw.substring(i).take(8)}\"")
                    val (h, m) = match.destructured
                    val minutes = h.toInt() * 60 + m.toInt()
                    if (m.toInt() > 59 || minutes > 2 * DAY) throw Unsupported("invalid time \"${match.value}\"")
                    tokens += Token.Time(minutes, match.value)
                    i += match.value.length
                }
                c.isLetter() -> {
                    var j = i
                    while (j < raw.length && raw[j].isLetter()) j++
                    tokens += Token.Word(raw.substring(i, j))
                    i = j
                }
                c == '-' || c == ',' || c == ';' -> { tokens += Token.Punct(c); i++ }
                c == '"' -> throw Unsupported("comments are not evaluated")
                c == '+' -> throw Unsupported("open-ended times (\"+\") are not evaluated")
                c == '|' -> throw Unsupported("fallback rules (\"||\") are not evaluated")
                c == '[' -> throw Unsupported("nth-weekday selectors are not evaluated")
                else -> throw Unsupported("unsupported character '$c'")
            }
        }
        return tokens
    }

    private val TIME = Regex("""(\d{1,2}):(\d{2})""")
    private val WORD = Regex("[A-Za-z]+")
    private val MONTHS = setOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    private class Parser(private val tokens: List<Token>) {
        private var pos = 0

        fun parse(): List<Rule> {
            if (tokens.isEmpty()) throw Unsupported("empty opening_hours")
            val rules = mutableListOf<Rule>()
            var additional = false
            while (true) {
                rules += rule(additional)
                if (pos == tokens.size) break
                when (val separator = next()) {
                    Token.Punct(';') -> {
                        additional = false
                        if (pos == tokens.size) break // trailing ";" is harmless
                    }
                    // A comma not consumed inside the rule separates rules (additional rule).
                    Token.Punct(',') -> additional = true
                    else -> throw Unsupported("unexpected ${describe(separator)}")
                }
            }
            return rules
        }

        private fun rule(additional: Boolean): Rule {
            val days = if (isDayItem(peek())) days() else null
            val token = peek() ?: throw Unsupported("days without times are not evaluated")
            return when {
                token is Token.AllWeek -> {
                    next()
                    Rule(days, listOf(Interval(0, DAY)), off = false, additional = additional)
                }
                token is Token.Word && token.text.lowercase() in setOf("off", "closed") -> {
                    next()
                    if (additional) throw Unsupported("\"off\" in an additional (comma) rule is ambiguous")
                    Rule(days, emptyList(), off = true, additional = false)
                }
                token is Token.Time -> {
                    val ranges = times()
                    // "open" is the default state; accept it as a no-op modifier.
                    (peek() as? Token.Word)?.takeIf { it.text.lowercase() == "open" }?.let { next() }
                    Rule(days, ranges, off = false, additional = additional)
                }
                days != null && (token == Token.Punct(';') || token == Token.Punct(',')) ->
                    throw Unsupported("days without times are not evaluated")
                else -> throw Unsupported("unsupported ${describe(token)}")
            }
        }

        private fun days(): DaySet {
            val weekdays = mutableSetOf<DayOfWeek>()
            var holiday = false
            do {
                val word = (next() as Token.Word).text
                if (word.equals("PH", ignoreCase = true)) {
                    holiday = true
                    continue
                }
                val start = WEEKDAYS.getValue(word.lowercase())
                if (peek() == Token.Punct('-') && isWeekday(peekAt(1))) {
                    next()
                    val end = WEEKDAYS.getValue((next() as Token.Word).text.lowercase())
                    // Ranges may wrap: Fr-Mo = Fr, Sa, Su, Mo.
                    var day = start
                    while (true) {
                        weekdays += day
                        if (day == end) break
                        day = day.plus(1)
                    }
                } else {
                    weekdays += start
                }
            } while (peek() == Token.Punct(',') && isDayItem(peekAt(1)) && next() != null)
            return DaySet(weekdays, holiday)
        }

        private fun times(): List<Interval> {
            val ranges = mutableListOf<Interval>()
            do {
                val start = next() as? Token.Time ?: throw Unsupported("expected a time")
                if (next() != Token.Punct('-')) throw Unsupported("expected \"-\" after ${start.text}")
                val endToken = next() as? Token.Time ?: throw Unsupported("expected an end time after ${start.text}-")
                if (start.minutes >= DAY) throw Unsupported("start time ${start.text} is past midnight")
                // 22:00-02:00 means until 02:00 the next day; 18:00-26:00 is the explicit form.
                val end = if (endToken.minutes <= start.minutes) endToken.minutes + DAY else endToken.minutes
                if (endToken.minutes == start.minutes) throw Unsupported("empty or ambiguous range ${start.text}-${endToken.text}")
                if (end > 2 * DAY) throw Unsupported("range ${start.text}-${endToken.text} is too long")
                ranges += Interval(start.minutes, end)
            } while (peek() == Token.Punct(',') && peekAt(1) is Token.Time && next() != null)
            return ranges
        }

        private fun isWeekday(token: Token?) = token is Token.Word && token.text.lowercase() in WEEKDAYS

        private fun isDayItem(token: Token?): Boolean {
            if (token !is Token.Word) return false
            if (isWeekday(token) || token.text.equals("PH", ignoreCase = true)) return true
            // Recognisable but unsupported selectors get a specific reason.
            val lower = token.text.lowercase()
            when {
                lower == "sh" -> throw Unsupported("school holidays (SH) are not evaluated")
                lower in MONTHS -> throw Unsupported("month/date selectors are not evaluated")
                lower == "week" -> throw Unsupported("week selectors are not evaluated")
                lower in setOf("sunrise", "sunset", "dawn", "dusk") -> throw Unsupported("sunrise/sunset times are not evaluated")
                lower == "unknown" -> throw Unsupported("opening_hours says \"unknown\"")
                lower in setOf("off", "closed", "open") -> return false
                else -> throw Unsupported("unsupported word \"${token.text}\"")
            }
        }

        private fun peek(): Token? = tokens.getOrNull(pos)
        private fun peekAt(offset: Int): Token? = tokens.getOrNull(pos + offset)
        private fun next(): Token? = tokens.getOrNull(pos)?.also { pos++ }

        private fun describe(token: Token?): String = when (token) {
            null -> "end of value"
            is Token.Word -> "\"${token.text}\""
            is Token.Time -> "time ${token.text}"
            is Token.Punct -> "\"${token.char}\""
            Token.AllWeek -> "\"24/7\""
        }

    }
}

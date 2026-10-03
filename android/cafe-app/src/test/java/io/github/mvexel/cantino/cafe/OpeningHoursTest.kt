package io.github.mvexel.cantino.cafe

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the app-level opening-hours evaluator. The rule under test
 * is "unknown stays unknown": Open/Closed only when certain.
 *
 * Reference week: 2026-10-05 is a Monday.
 */
class OpeningHoursTest {
    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 10, 5 + day, hour, minute)
    private val mon = 0
    private val tue = 1
    private val wed = 2
    private val fri = 4
    private val sat = 5
    private val sun = 6

    private fun eval(raw: String?, time: LocalDateTime) = OpeningHours.evaluate(raw, time)

    private fun assertOpen(raw: String, time: LocalDateTime) = assertEquals("$raw at $time", OpenState.Open, eval(raw, time))
    private fun assertClosed(raw: String, time: LocalDateTime) = assertEquals("$raw at $time", OpenState.Closed, eval(raw, time))
    private fun assertUnknown(raw: String?, time: LocalDateTime = at(mon, 12)) {
        val result = eval(raw, time)
        assertTrue("$raw at $time should be Unknown, was $result", result is OpenState.Unknown)
    }

    @Test
    fun missingAndEmptyAreUnknownNotClosed() {
        assertUnknown(null)
        assertUnknown("")
        assertUnknown("   ")
    }

    @Test
    fun weekdayAndWeekendRules() {
        val raw = "Mo-Fr 07:00-18:00; Sa,Su 08:00-16:00"
        assertOpen(raw, at(mon, 7, 0)) // start is inclusive
        assertOpen(raw, at(wed, 17, 59))
        assertClosed(raw, at(fri, 18, 0)) // end is exclusive
        assertClosed(raw, at(tue, 6, 59))
        assertOpen(raw, at(sat, 8))
        assertClosed(raw, at(sun, 16, 30))
    }

    @Test
    fun alwaysOpen() {
        assertOpen("24/7", at(sun, 3))
        assertOpen("Mo-Su 00:00-24:00", at(wed, 23, 59))
        assertOpen("Mo-Su 24/7", at(tue, 0))
    }

    @Test
    fun offAndClosedOverrideEarlierRules() {
        val raw = "Mo-Su 08:00-20:00; Su off"
        assertOpen(raw, at(sat, 12))
        assertClosed(raw, at(sun, 12))
        assertClosed("Mo-Sa 08:00-20:00; Su closed", at(sun, 12))
        assertClosed("off", at(mon, 12))
    }

    @Test
    fun multipleRangesPerDayAndCommaDayLists() {
        val raw = "Mo,We,Fr 08:00-12:00,13:00-17:30"
        assertOpen(raw, at(mon, 9))
        assertClosed(raw, at(mon, 12, 30)) // lunch break
        assertOpen(raw, at(fri, 17, 29))
        assertClosed(raw, at(tue, 9)) // not listed
    }

    @Test
    fun additionalRulesAddRatherThanReplace() {
        val raw = "Mo-Fr 08:00-12:00, We 14:00-18:00"
        assertOpen(raw, at(wed, 9))
        assertOpen(raw, at(wed, 15))
        assertClosed(raw, at(tue, 15))
    }

    @Test
    fun wrappingDayRangeAndTimeWithoutDays() {
        assertOpen("Fr-Mo 10:00-14:00", at(sun, 11))
        assertClosed("Fr-Mo 10:00-14:00", at(wed, 11))
        assertOpen("06:00-22:00", at(sun, 21))
        assertClosed("06:00-22:00", at(sun, 22))
    }

    @Test
    fun overnightRangesSpillIntoTheNextDay() {
        val raw = "Fr,Sa 18:00-02:00"
        assertOpen(raw, at(fri, 23))
        assertOpen(raw, at(sat, 1, 30)) // Friday night
        assertOpen(raw, at(sun, 1, 30)) // Saturday night
        assertClosed(raw, at(sun, 2, 0))
        assertClosed(raw, at(fri, 1, 0)) // Thursday had no hours
        assertOpen("Mo 20:00-26:00", at(tue, 1))
    }

    @Test
    fun overnightSpillAgainstOffIsUnknown() {
        assertUnknown("Mo-Su 18:00-02:00; Tu off", at(tue, 1))
        assertClosed("Mo-Su 18:00-02:00; Tu off", at(tue, 19))
    }

    @Test
    fun publicHolidaysOnlyAnswerWhenItCannotMatter() {
        val raw = "Mo-Fr 08:00-17:00; PH off"
        assertUnknown(raw, at(mon, 10)) // could be a holiday
        assertClosed(raw, at(sat, 10)) // closed either way
        assertClosed(raw, at(mon, 20)) // closed either way
        assertOpen("Mo-Su 08:00-17:00; PH 08:00-17:00", at(mon, 10)) // same hours either way
        assertUnknown("Mo-Fr 08:00-17:00; PH 10:00-14:00", at(mon, 9))
    }

    @Test
    fun caseAndWhitespaceTolerance() {
        assertOpen("mo-fr 07:00 - 18:00", at(tue, 8))
        assertOpen("Mo-Fr 7:00-18:00", at(tue, 8))
        assertOpen("Mo-Fr 07:00-18:00;", at(tue, 8))
        assertOpen("Mo-Fr 07:00-18:00 open", at(tue, 8))
    }

    @Test
    fun unsupportedFormsAreUnknown() {
        listOf(
            "Mo-Fr 08:00-18:00 || \"by appointment\"",
            "Mo-Fr 08:00+",
            "Mo-Fr sunrise-sunset",
            "Apr-Oct Mo-Su 08:00-20:00",
            "Dec 25 off",
            "SH Mo-Fr 08:00-12:00",
            "Mo[1] 10:00-12:00",
            "week 1-53/2 Mo 08:00-12:00",
            "Mo-Fr 08:00-18:00 \"call first\"",
            "unknown",
            "Mo-Fr",
            "Mo-Fr 08.00-18.00",
            "Mo-Fr 0800-1800",
            "Monday-Friday 8am-6pm",
            "Mo-Fr 08:00-12:00, We off",
            "Mo-Fr 08:00-08:00",
            "Mo-Fr 25:00-26:00",
        ).forEach { raw ->
            assertUnknown(raw, at(mon, 10))
        }
    }

    @Test
    fun unknownReasonsNameTheFeature() {
        // Both seen in the downtown Salt Lake City extract (2026-10-03).
        assertEquals(
            OpenState.Unknown("month/date selectors are not evaluated"),
            eval("Th-Su 09:00-14:00; Mo-We off; Dec 17-Dec 31 off; Jan 1-Jan 3 off", at(mon, 10)),
        )
        assertEquals(
            OpenState.Unknown("comments are not evaluated"),
            eval("Mo-Sa 09:00-15:00 \"09:00-10:00 is walkup service only\"", at(mon, 10)),
        )
        assertEquals(OpenState.Unknown("no opening_hours tag"), eval(null, at(mon, 10)))
    }

    @Test
    fun supportPredicate() {
        assertTrue(OpeningHours.isSupported("Mo-Fr 07:00-18:00; Sa,Su 08:00-16:00"))
        assertTrue(!OpeningHours.isSupported("Mo-Fr 08:00-18:00 || \"by appointment\""))
    }
}

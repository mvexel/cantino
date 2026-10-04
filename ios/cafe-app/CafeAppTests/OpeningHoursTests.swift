import Foundation
import Testing
@testable import CafeApp

/// Port of the Android café app's OpeningHoursTest.kt. The rule under test
/// is "unknown stays unknown": open/closed only when certain.
struct OpeningHoursTests {
    private func at(_ day: Weekday, _ hour: Int, _ minute: Int = 0) -> LocalTime {
        LocalTime(weekday: day, hour: hour, minute: minute)
    }

    private func eval(_ raw: String?, _ time: LocalTime) -> OpenState { OpeningHours.evaluate(raw, at: time) }

    private func expectOpen(_ raw: String, _ time: LocalTime, sourceLocation: SourceLocation = #_sourceLocation) {
        #expect(eval(raw, time) == .open, "\(raw) at \(time)", sourceLocation: sourceLocation)
    }

    private func expectClosed(_ raw: String, _ time: LocalTime, sourceLocation: SourceLocation = #_sourceLocation) {
        #expect(eval(raw, time) == .closed, "\(raw) at \(time)", sourceLocation: sourceLocation)
    }

    private func expectUnknown(_ raw: String?, _ time: LocalTime = LocalTime(weekday: .monday, hour: 12),
                               sourceLocation: SourceLocation = #_sourceLocation) {
        let result = eval(raw, time)
        #expect(result.isUnknown, "\(raw ?? "nil") at \(time) should be unknown, was \(result)", sourceLocation: sourceLocation)
    }

    @Test func missingAndEmptyAreUnknownNotClosed() {
        expectUnknown(nil)
        expectUnknown("")
        expectUnknown("   ")
    }

    @Test func weekdayAndWeekendRules() {
        let raw = "Mo-Fr 07:00-18:00; Sa,Su 08:00-16:00"
        expectOpen(raw, at(.monday, 7, 0)) // start is inclusive
        expectOpen(raw, at(.wednesday, 17, 59))
        expectClosed(raw, at(.friday, 18, 0)) // end is exclusive
        expectClosed(raw, at(.tuesday, 6, 59))
        expectOpen(raw, at(.saturday, 8))
        expectClosed(raw, at(.sunday, 16, 30))
    }

    @Test func alwaysOpen() {
        expectOpen("24/7", at(.sunday, 3))
        expectOpen("Mo-Su 00:00-24:00", at(.wednesday, 23, 59))
        expectOpen("Mo-Su 24/7", at(.tuesday, 0))
    }

    @Test func offAndClosedOverrideEarlierRules() {
        let raw = "Mo-Su 08:00-20:00; Su off"
        expectOpen(raw, at(.saturday, 12))
        expectClosed(raw, at(.sunday, 12))
        expectClosed("Mo-Sa 08:00-20:00; Su closed", at(.sunday, 12))
        expectClosed("off", at(.monday, 12))
    }

    @Test func multipleRangesPerDayAndCommaDayLists() {
        let raw = "Mo,We,Fr 08:00-12:00,13:00-17:30"
        expectOpen(raw, at(.monday, 9))
        expectClosed(raw, at(.monday, 12, 30)) // lunch break
        expectOpen(raw, at(.friday, 17, 29))
        expectClosed(raw, at(.tuesday, 9)) // not listed
    }

    @Test func additionalRulesAddRatherThanReplace() {
        let raw = "Mo-Fr 08:00-12:00, We 14:00-18:00"
        expectOpen(raw, at(.wednesday, 9))
        expectOpen(raw, at(.wednesday, 15))
        expectClosed(raw, at(.tuesday, 15))
    }

    @Test func wrappingDayRangeAndTimeWithoutDays() {
        expectOpen("Fr-Mo 10:00-14:00", at(.sunday, 11))
        expectClosed("Fr-Mo 10:00-14:00", at(.wednesday, 11))
        expectOpen("06:00-22:00", at(.sunday, 21))
        expectClosed("06:00-22:00", at(.sunday, 22))
    }

    @Test func overnightRangesSpillIntoTheNextDay() {
        let raw = "Fr,Sa 18:00-02:00"
        expectOpen(raw, at(.friday, 23))
        expectOpen(raw, at(.saturday, 1, 30)) // Friday night
        expectOpen(raw, at(.sunday, 1, 30)) // Saturday night
        expectClosed(raw, at(.sunday, 2, 0))
        expectClosed(raw, at(.friday, 1, 0)) // Thursday had no hours
        expectOpen("Mo 20:00-26:00", at(.tuesday, 1))
    }

    @Test func overnightSpillAgainstOffIsUnknown() {
        expectUnknown("Mo-Su 18:00-02:00; Tu off", at(.tuesday, 1))
        expectClosed("Mo-Su 18:00-02:00; Tu off", at(.tuesday, 19))
    }

    @Test func publicHolidaysOnlyAnswerWhenItCannotMatter() {
        let raw = "Mo-Fr 08:00-17:00; PH off"
        expectUnknown(raw, at(.monday, 10)) // could be a holiday
        expectClosed(raw, at(.saturday, 10)) // closed either way
        expectClosed(raw, at(.monday, 20)) // closed either way
        expectOpen("Mo-Su 08:00-17:00; PH 08:00-17:00", at(.monday, 10)) // same hours either way
        expectUnknown("Mo-Fr 08:00-17:00; PH 10:00-14:00", at(.monday, 9))
    }

    @Test func caseAndWhitespaceTolerance() {
        expectOpen("mo-fr 07:00 - 18:00", at(.tuesday, 8))
        expectOpen("Mo-Fr 7:00-18:00", at(.tuesday, 8))
        expectOpen("Mo-Fr 07:00-18:00;", at(.tuesday, 8))
        expectOpen("Mo-Fr 07:00-18:00 open", at(.tuesday, 8))
    }

    @Test(arguments: [
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
    ])
    func unsupportedFormsAreUnknown(raw: String) {
        expectUnknown(raw, at(.monday, 10))
    }

    @Test func unknownReasonsNameTheFeature() {
        // Both seen in the downtown Salt Lake City extract (2026-10-03).
        #expect(eval("Th-Su 09:00-14:00; Mo-We off; Dec 17-Dec 31 off; Jan 1-Jan 3 off", at(.monday, 10))
            == .unknown(reason: "month/date selectors are not evaluated"))
        #expect(eval("Mo-Sa 09:00-15:00 \"09:00-10:00 is walkup service only\"", at(.monday, 10))
            == .unknown(reason: "comments are not evaluated"))
        #expect(eval(nil, at(.monday, 10)) == .unknown(reason: "no opening_hours tag"))
    }

    @Test func supportPredicate() {
        #expect(OpeningHours.isSupported("Mo-Fr 07:00-18:00; Sa,Su 08:00-16:00"))
        #expect(!OpeningHours.isSupported("Mo-Fr 08:00-18:00 || \"by appointment\""))
    }

    /// The Kotlin tests use dates of the week of 2026-10-05 (a Monday); here
    /// the evaluator takes a ``LocalTime``, so check the date conversion.
    @Test func localTimeFromADate() throws {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = try #require(TimeZone(identifier: "America/Denver"))
        let monday = try #require(calendar.date(from: DateComponents(year: 2026, month: 10, day: 5, hour: 7, minute: 30)))
        #expect(LocalTime(monday, calendar: calendar) == at(.monday, 7, 30))
        let sunday = try #require(calendar.date(from: DateComponents(year: 2026, month: 10, day: 11, hour: 23, minute: 59)))
        #expect(LocalTime(sunday, calendar: calendar) == at(.sunday, 23, 59))
    }
}

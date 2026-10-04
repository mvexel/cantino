import Foundation

/*
 * Opening-hours interpretation for the café app: a Swift port of the Android
 * café app's OpeningHours.kt, same subset, same semantics, same reasons.
 *
 * This lives in the APP, never in the SDK (CLAUDE.md: café policy and
 * opening-hours interpretation are app concerns). Cantino hands us the raw
 * `opening_hours` string; this file decides what we can say about it.
 *
 * Design rule: **unknown stays unknown.** The evaluator answers open or
 * closed only when the string is in the subset below AND the answer does not
 * depend on anything we do not know. Everything else is `.unknown` with a
 * human-readable reason, and the UI shows the raw string next to it. A
 * missing tag is unknown too, never closed.
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
 *   An additional "off" rule is rejected (unknown): the spec is ambiguous.
 * - A rule without days applies to every day, including public holidays.
 * - Day names are matched case-insensitively ("mo-fr" is unambiguous).
 * - Ranges ending after midnight spill into the next day.
 * - PH (public holiday): we do not know whether today (or yesterday, for
 *   spill-over) is a holiday, so the string is evaluated for every
 *   combination; if the answers differ the result is unknown. So
 *   "Mo-Fr 08:00-17:00; PH off" is closed on a Saturday (certain) but
 *   unknown on a weekday during business hours (could be a holiday).
 *
 * Everything else is unknown, by design: month/date/week/year selectors,
 * school holidays (SH), nth weekdays ("Mo[1]"), sunrise/sunset, open ends
 * ("18:00+"), fallback rules ("||"), comments ("..."), "unknown", days
 * without a time ("Mo-Fr"), and any typo or unexpected character.
 *
 * Time zone: the caller passes the local time. The app uses the device's
 * clock and zone, which is right only while the device is in the café's zone
 * (OSM opening hours are in the place's local time).
 */

/// What we can say about "is it open now".
enum OpenState: Hashable, Sendable {
    case open
    case closed
    /// We cannot tell. `reason` is shown to the user next to the raw tag.
    case unknown(reason: String)

    var isUnknown: Bool { if case .unknown = self { true } else { false } }
}

/// Day of the week, Monday first (as in the opening_hours syntax).
enum Weekday: Int, CaseIterable, Sendable {
    case monday, tuesday, wednesday, thursday, friday, saturday, sunday

    /// The day `days` later (negative: earlier), wrapping around the week.
    func plus(_ days: Int) -> Weekday { Weekday(rawValue: ((rawValue + days) % 7 + 7) % 7)! }
}

/// A local wall-clock time: what the evaluator needs from a date.
struct LocalTime: Hashable, Sendable, CustomStringConvertible {
    var weekday: Weekday
    var hour: Int
    var minute: Int

    /// `date` on `calendar`'s clock (default: the device's zone).
    init(_ date: Date, calendar: Calendar = .current) {
        let parts = calendar.dateComponents([.weekday, .hour, .minute], from: date)
        // Foundation numbers Sunday 1 ... Saturday 7.
        weekday = Weekday(rawValue: (parts.weekday! + 5) % 7)!
        hour = parts.hour!
        minute = parts.minute!
    }

    init(weekday: Weekday, hour: Int, minute: Int = 0) {
        self.weekday = weekday
        self.hour = hour
        self.minute = minute
    }

    static var now: LocalTime { LocalTime(Date()) }

    var description: String { String(format: "%@ %02d:%02d", "\(weekday)", hour, minute) }
}

enum OpeningHours {
    /// Evaluates `raw` (the `opening_hours` tag value, nil when missing) at
    /// local time `at`. Never throws.
    static func evaluate(_ raw: String?, at: LocalTime) -> OpenState {
        guard let raw else { return .unknown(reason: "no opening_hours tag") }
        if raw.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return .unknown(reason: "empty opening_hours tag")
        }
        do {
            var parser = Parser(tokens: try tokenize(raw))
            return evaluate(try parser.parse(), at: at)
        } catch let error as Unsupported {
            return .unknown(reason: error.message)
        } catch {
            return .unknown(reason: "unsupported opening_hours")
        }
    }

    /// True when `raw` is fully inside the supported subset.
    static func isSupported(_ raw: String) -> Bool {
        guard let tokens = try? tokenize(raw) else { return false }
        var parser = Parser(tokens: tokens)
        return (try? parser.parse()) != nil
    }

    // MARK: - evaluation

    private static func evaluate(_ rules: [Rule], at: LocalTime) -> OpenState {
        let usesHolidays = rules.contains { $0.days?.publicHoliday == true }
        // Enumerate what we don't know: is today / yesterday a public holiday?
        let combos: [(Bool, Bool)] = usesHolidays
            ? [(false, false), (false, true), (true, false), (true, true)]
            : [(false, false)]
        let answers = combos.map { openAt(rules, at: at, todayHoliday: $0.0, yesterdayHoliday: $0.1) }
        // A single unknown, or disagreement between the combinations, makes the whole answer unknown.
        if let unknown = answers.first(where: \.isUnknown) { return unknown }
        return Set(answers).count == 1
            ? answers[0]
            : .unknown(reason: "depends on whether today is a public holiday")
    }

    private static func openAt(_ rules: [Rule], at: LocalTime, todayHoliday: Bool, yesterdayHoliday: Bool) -> OpenState {
        let minute = at.hour * 60 + at.minute
        let today = dayPlan(rules, day: at.weekday, holiday: todayHoliday)
        if today.intervals.contains(where: { minute >= $0.first && minute < $0.last }) { return .open }
        let yesterday = dayPlan(rules, day: at.weekday.plus(-1), holiday: yesterdayHoliday)
        let spilled = yesterday.intervals.contains { minute + day >= $0.first && minute + day < $0.last }
        if spilled {
            // "Mo-Su 18:00-02:00; Tu off" at Tu 01:00: does Tuesday's "off"
            // cancel Monday night's spill-over? The spec is not explicit and
            // implementations differ, so we refuse to guess.
            return today.explicitlyOff
                ? .unknown(reason: "overnight hours from the previous day conflict with an \"off\" rule")
                : .open
        }
        return .closed
    }

    /// Opening intervals of one weekday, in minutes from that day's midnight
    /// (the end may exceed 1440 for spill-over). Rules apply in order; see
    /// the file comment for normal vs additional rules.
    private static func dayPlan(_ rules: [Rule], day: Weekday, holiday: Bool) -> DayPlan {
        var intervals: [Interval] = []
        var off = false
        for rule in rules where rule.days?.matches(day, holiday: holiday) ?? true {
            if rule.additional {
                intervals += rule.intervals
            } else {
                intervals = rule.intervals
                off = rule.off
            }
        }
        return DayPlan(intervals: intervals, explicitlyOff: off)
    }

    private static let day = 24 * 60

    /// `first` inclusive, `last` exclusive, in minutes.
    private struct Interval { let first: Int; let last: Int }

    private struct DayPlan { let intervals: [Interval]; let explicitlyOff: Bool }

    private struct DaySet {
        let weekdays: Set<Weekday>
        let publicHoliday: Bool
        func matches(_ day: Weekday, holiday: Bool) -> Bool { weekdays.contains(day) || (publicHoliday && holiday) }
    }

    /// `days` nil = every day. `off` rules have no intervals.
    private struct Rule {
        let days: DaySet?
        let intervals: [Interval]
        let off: Bool
        let additional: Bool
    }

    // MARK: - parsing

    private struct Unsupported: Error { let message: String; init(_ message: String) { self.message = message } }

    private enum Token: Equatable {
        case word(String)
        case time(minutes: Int, text: String)
        case punct(Character)
        case allWeek // "24/7"
    }

    private static let weekdays: [String: Weekday] = [
        "mo": .monday, "tu": .tuesday, "we": .wednesday, "th": .thursday, "fr": .friday, "sa": .saturday, "su": .sunday,
    ]
    private static let months: Set<String> = ["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"]
    private static let sunWords: Set<String> = ["sunrise", "sunset", "dawn", "dusk"]

    private static func isASCIILetter(_ c: Character) -> Bool { c.isASCII && c.isLetter }
    private static func isASCIIDigit(_ c: Character) -> Bool { c.isASCII && c.isNumber }

    private static func tokenize(_ raw: String) throws -> [Token] {
        // Name the unsupported feature up front when a keyword gives it away
        // ("Dec 17-Dec 31 off" would otherwise fail on "17" with a vaguer reason).
        for word in raw.split(whereSeparator: { !isASCIILetter($0) }).map({ $0.lowercased() }) {
            if months.contains(word) { throw Unsupported("month/date selectors are not evaluated") }
            if word == "sh" { throw Unsupported("school holidays (SH) are not evaluated") }
            if word == "week" { throw Unsupported("week selectors are not evaluated") }
            if sunWords.contains(word) { throw Unsupported("sunrise/sunset times are not evaluated") }
        }
        let chars = Array(raw)
        var tokens: [Token] = []
        var i = 0
        while i < chars.count {
            let c = chars[i]
            if c.isWhitespace {
                i += 1
            } else if String(chars[i..<min(i + 4, chars.count)]) == "24/7" {
                tokens.append(.allWeek)
                i += 4
            } else if isASCIIDigit(c) {
                // H:MM or HH:MM, nothing else (no "8h", "08.00", "0800").
                guard let (hour, minute, length) = time(chars, at: i) else {
                    throw Unsupported("unsupported time near \"\(String(chars[i..<min(i + 8, chars.count)]))\"")
                }
                let text = String(chars[i..<i + length])
                let minutes = hour * 60 + minute
                if minute > 59 || minutes > 2 * day { throw Unsupported("invalid time \"\(text)\"") }
                tokens.append(.time(minutes: minutes, text: text))
                i += length
            } else if c.isLetter {
                var j = i
                while j < chars.count && chars[j].isLetter { j += 1 }
                tokens.append(.word(String(chars[i..<j])))
                i = j
            } else if c == "-" || c == "," || c == ";" {
                tokens.append(.punct(c))
                i += 1
            } else if c == "\"" {
                throw Unsupported("comments are not evaluated")
            } else if c == "+" {
                throw Unsupported("open-ended times (\"+\") are not evaluated")
            } else if c == "|" {
                throw Unsupported("fallback rules (\"||\") are not evaluated")
            } else if c == "[" {
                throw Unsupported("nth-weekday selectors are not evaluated")
            } else {
                throw Unsupported("unsupported character '\(c)'")
            }
        }
        return tokens
    }

    /// `H:MM` / `HH:MM` at `start` (Kotlin's `(\d{1,2}):(\d{2})` matched
    /// there): hour, minute and the matched length, or nil. Range checks
    /// (minute over 59, past 48:00) are the caller's.
    private static func time(_ chars: [Character], at start: Int) -> (Int, Int, Int)? {
        func digit(_ index: Int) -> Int? {
            index < chars.count && isASCIIDigit(chars[index]) ? chars[index].wholeNumberValue : nil
        }
        // Try two hour digits first, then one (the regex backtracks the same way).
        for hourDigits in [2, 1] {
            var hour = 0
            var ok = true
            for k in 0..<hourDigits {
                guard let d = digit(start + k) else { ok = false; break }
                hour = hour * 10 + d
            }
            let colon = start + hourDigits
            guard ok, colon < chars.count, chars[colon] == ":",
                  let m1 = digit(colon + 1), let m2 = digit(colon + 2) else { continue }
            return (hour, m1 * 10 + m2, hourDigits + 3)
        }
        return nil
    }

    private struct Parser {
        let tokens: [Token]
        var pos = 0

        init(tokens: [Token]) { self.tokens = tokens }

        mutating func parse() throws -> [Rule] {
            if tokens.isEmpty { throw Unsupported("empty opening_hours") }
            var rules: [Rule] = []
            var additional = false
            while true {
                rules.append(try rule(additional: additional))
                if pos == tokens.count { break }
                let separator = next()
                if separator == .punct(";") {
                    additional = false
                    if pos == tokens.count { break } // trailing ";" is harmless
                } else if separator == .punct(",") {
                    // A comma not consumed inside the rule separates rules (additional rule).
                    additional = true
                } else {
                    throw Unsupported("unexpected \(describe(separator))")
                }
            }
            return rules
        }

        private mutating func rule(additional: Bool) throws -> Rule {
            let days = try isDayItem(peek()) ? try self.days() : nil
            guard let token = peek() else { throw Unsupported("days without times are not evaluated") }
            switch token {
            case .allWeek:
                _ = next()
                return Rule(days: days, intervals: [Interval(first: 0, last: day)], off: false, additional: additional)
            case .word(let text) where ["off", "closed"].contains(text.lowercased()):
                _ = next()
                if additional { throw Unsupported("\"off\" in an additional (comma) rule is ambiguous") }
                return Rule(days: days, intervals: [], off: true, additional: false)
            case .time:
                let ranges = try times()
                // "open" is the default state; accept it as a no-op modifier.
                if case .word(let text)? = peek(), text.lowercased() == "open" { _ = next() }
                return Rule(days: days, intervals: ranges, off: false, additional: additional)
            case .punct(let c) where days != nil && (c == ";" || c == ","):
                throw Unsupported("days without times are not evaluated")
            default:
                throw Unsupported("unsupported \(describe(token))")
            }
        }

        private mutating func days() throws -> DaySet {
            var result: Set<Weekday> = []
            var holiday = false
            repeat {
                guard case .word(let word)? = next() else { throw Unsupported("expected a day") }
                if word.lowercased() == "ph" {
                    holiday = true
                    continue
                }
                let start = weekdays[word.lowercased()]!
                if peek() == .punct("-") && isWeekday(peek(at: 1)) {
                    _ = next()
                    guard case .word(let endWord)? = next() else { throw Unsupported("expected a day") }
                    let end = weekdays[endWord.lowercased()]!
                    // Ranges may wrap: Fr-Mo = Fr, Sa, Su, Mo.
                    var day = start
                    while true {
                        result.insert(day)
                        if day == end { break }
                        day = day.plus(1)
                    }
                } else {
                    result.insert(start)
                }
            } while try peek() == .punct(",") && isDayItem(peek(at: 1)) && next() != nil
            return DaySet(weekdays: result, publicHoliday: holiday)
        }

        private mutating func times() throws -> [Interval] {
            var ranges: [Interval] = []
            repeat {
                guard case .time(let startMinutes, let startText)? = next() else { throw Unsupported("expected a time") }
                if next() != .punct("-") { throw Unsupported("expected \"-\" after \(startText)") }
                guard case .time(let endMinutes, let endText)? = next() else {
                    throw Unsupported("expected an end time after \(startText)-")
                }
                if startMinutes >= day { throw Unsupported("start time \(startText) is past midnight") }
                // 22:00-02:00 means until 02:00 the next day; 18:00-26:00 is the explicit form.
                let end = endMinutes <= startMinutes ? endMinutes + day : endMinutes
                if endMinutes == startMinutes { throw Unsupported("empty or ambiguous range \(startText)-\(endText)") }
                if end > 2 * day { throw Unsupported("range \(startText)-\(endText) is too long") }
                ranges.append(Interval(first: startMinutes, last: end))
            } while peek() == .punct(",") && isTime(peek(at: 1)) && next() != nil
            return ranges
        }

        private func isTime(_ token: Token?) -> Bool { if case .time? = token { true } else { false } }

        private func isWeekday(_ token: Token?) -> Bool {
            if case .word(let text)? = token { weekdays[text.lowercased()] != nil } else { false }
        }

        private func isDayItem(_ token: Token?) throws -> Bool {
            guard case .word(let text)? = token else { return false }
            let lower = text.lowercased()
            if weekdays[lower] != nil || lower == "ph" { return true }
            // Recognisable but unsupported selectors get a specific reason.
            if lower == "sh" { throw Unsupported("school holidays (SH) are not evaluated") }
            if months.contains(lower) { throw Unsupported("month/date selectors are not evaluated") }
            if lower == "week" { throw Unsupported("week selectors are not evaluated") }
            if sunWords.contains(lower) { throw Unsupported("sunrise/sunset times are not evaluated") }
            if lower == "unknown" { throw Unsupported("opening_hours says \"unknown\"") }
            if ["off", "closed", "open"].contains(lower) { return false }
            throw Unsupported("unsupported word \"\(text)\"")
        }

        private func peek() -> Token? { peek(at: 0) }
        private func peek(at offset: Int) -> Token? { pos + offset < tokens.count ? tokens[pos + offset] : nil }
        private mutating func next() -> Token? {
            guard pos < tokens.count else { return nil }
            defer { pos += 1 }
            return tokens[pos]
        }

        private func describe(_ token: Token?) -> String {
            switch token {
            case nil: "end of value"
            case .word(let text): "\"\(text)\""
            case .time(_, let text): "time \(text)"
            case .punct(let c): "\"\(c)\""
            case .allWeek: "\"24/7\""
            }
        }
    }
}

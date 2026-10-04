# Opening hours

Cantino hands you the `opening_hours` tag as the raw string it has in
OpenStreetMap. It does not parse or evaluate it: what "open now" means is
your app's business (opening-hours interpretation is out of the core's scope).
This page says how to pair Cantino with an evaluator.

```kotlin
val cafes = store.query(Query(tags = listOf(TagFilter.Equals("amenity", "cafe")), bbox = bbox))
cafes.forEach { obj ->
    val raw: String? = obj.tags["opening_hours"]   // null = no tag, not "closed"
}
```

## Rules that hold whatever library you use

- **Unknown stays unknown.** A missing tag, a string the evaluator cannot
  parse, and a string whose answer depends on something you do not know
  (public holidays of an unknown country, a comment, a date range) are all
  "unknown", never "closed". Show the raw string next to an unknown state.
- **Time zone: use the place's, not the device's.** `opening_hours` is local
  wall-clock time at the place. Evaluating it in the device's zone is wrong
  when the user is elsewhere (travelling, or browsing an area they plan to
  visit). Cantino stores no time zone for an area in 0.x. Derive it yourself,
  for example from the area's centre with a time zone lookup at download time,
  keep it with your own records, and evaluate `Instant.now()` in that zone.
- **Public holidays (`PH`) and school holidays (`SH`)** need a holiday calendar
  for the region. Without one, a rule that mentions `PH` is unknown on days it
  could apply.
- **Sunrise and sunset** keywords need the place's coordinates
  (`OsmStore.representativePoint`).
- Parse once per object and cache; do not reparse hundreds of strings per
  frame.

## Evaluator libraries

Survey as of 2026-10-03 from each project's README. Cantino endorses none of
them; check licence, activity and API for what you need.

| Library | Language | Licence | Notes |
| --- | --- | --- | --- |
| [osm-opening-hours](https://github.com/westnordost/osm-opening-hours) (`de.westnordost:osm-opening-hours`) | Kotlin multiplatform, no dependencies | MIT | Parses to a typed data model and back; lenient mode; used in StreetComplete. Its README describes parsing and the data model; confirm its API covers "open at time T" before choosing it. |
| [OpeningHoursParser](https://github.com/simonpoole/OpeningHoursParser) (`ch.poole:OpeningHoursParser`) | Java | MIT | Parser for the OSM specification, strict and non-strict modes; used in Vespucci and other Android apps. Check its API for evaluation and holiday support. |
| [opening_hours.js](https://github.com/opening-hours/opening_hours.js) | JavaScript | LGPL-3.0 (from 3.4) | Most complete (variable times, public and school holidays for many countries); distinguishes open, closed and unknown. JavaScript only, so it fits a WebView or embedded-JS setup, not plain Kotlin. |

The OSM wiki's [Key:opening_hours](https://wiki.openstreetmap.org/wiki/Key:opening_hours)
and its [specification](https://wiki.openstreetmap.org/wiki/Key:opening_hours/specification)
are the reference for the syntax.

## The café app's evaluator: an example, not a recommendation

[`OpeningHours.kt`](../../android/cafe-app/src/main/java/io/github/mvexel/cantino/cafe/OpeningHours.kt)
in the café app evaluates a strict subset (weekday ranges, `24/7`, `off`,
past-midnight ranges) with 14 JVM tests and returns Open, Closed or Unknown
with a reason. On the Salt Lake City test area, 104 of 106 present values
evaluate. It exists to demonstrate the rules above. It uses the device's time
zone, which is wrong for a place outside the user's zone
([café walkthrough](cafe-app.md)). For a real app, use a maintained library
and a per-area time zone.

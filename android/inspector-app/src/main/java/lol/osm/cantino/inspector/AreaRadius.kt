package lol.osm.cantino.inspector

import lol.osm.cantino.Bbox
import java.util.Locale

/**
 * The size of the download area, copied from android/cafe-app AreaRadius.kt
 * (only the default differs). The area is a square centred on the user's
 * location (or the place they chose); the user picks its radius on the offer
 * screen.
 *
 * "Radius" means half the side of the square: radius 2.5 km is a 5 × 5 km box
 * reaching 2.5 km north, south, east and west of the centre (its corners are
 * about 3.5 km away). The same in both example apps, on Android and iOS
 * (ios/inspector-app AreaRadius.swift).
 *
 * The values are defaults, not limits: change [CHOICES_KM] and [DEFAULT_KM]
 * freely. Inspector does a full import, so the file grows with the area
 * (about 24 MB for 5 × 5 km of downtown Salt Lake City, 45 MB in Zürich).
 */
object AreaRadius {
    /** The radius choices on the offer screen, in km. */
    val CHOICES_KM = listOf(1.0, 2.5, 5.0, 10.0)

    /** Preselected radius, in km: a 5 × 5 km city centre, so a full import stays small. */
    const val DEFAULT_KM = 2.5

    /** The square of [radiusKm] around [center], via Cantino's [Bbox.around] (which takes the side). */
    fun bbox(center: LatLon, radiusKm: Double): Bbox = Bbox.around(center.lat, center.lon, widthKm = 2 * radiusKm)

    /** [km] if it is one of [CHOICES_KM] (the `radius` launch option), else null. */
    fun choice(km: Double?): Double? = CHOICES_KM.firstOrNull { km != null && kotlin.math.abs(it - km) < 1e-9 }

    /** "2.5 km". */
    fun label(radiusKm: Double): String = "${km(radiusKm)} km"

    /** "5 × 5 km": the box a radius makes. */
    fun sideLabel(radiusKm: Double): String = km(2 * radiusKm).let { "$it × $it km" }

    private fun km(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else String.format(Locale.ROOT, "%.1f", value)
}

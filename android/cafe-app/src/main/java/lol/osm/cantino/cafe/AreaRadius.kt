package lol.osm.cantino.cafe

import lol.osm.cantino.Bbox
import java.util.Locale

/**
 * The size of the download area. The area is a square centred on the user's
 * location (or the place they chose); the user picks its radius on the offer
 * screen.
 *
 * "Radius" means half the side of the square: radius 5 km is a 10 × 10 km box
 * reaching 5 km north, south, east and west of the centre (its corners are
 * about 7 km away). The same in both example apps, on Android and iOS
 * (ios/cafe-app AreaRadius.swift).
 *
 * The values are defaults, not limits: change [CHOICES_KM] and [DEFAULT_KM]
 * freely. A [Bbox] has no size cap; see docs/guide/downloading.md#area-size.
 */
object AreaRadius {
    /** The radius choices on the offer screen, in km. */
    val CHOICES_KM = listOf(1.0, 2.5, 5.0, 10.0)

    /** Preselected radius, in km: a 10 × 10 km box, a city centre's cafés. */
    const val DEFAULT_KM = 5.0

    /** The square of [radiusKm] around [center], via Cantino's [Bbox.around] (which takes the side). */
    fun bbox(center: LatLon, radiusKm: Double): Bbox = Bbox.around(center.lat, center.lon, widthKm = 2 * radiusKm)

    /**
     * The radius of a box made by [bbox], from its north-south extent
     * ([Bbox.around] uses 111.32 km per degree of latitude). Used to label a
     * refresh, which re-downloads the published bbox as it is.
     */
    fun of(bbox: Bbox): Double = (bbox.north - bbox.south) * KM_PER_DEGREE_LAT / 2

    /** [km] if it is one of [CHOICES_KM] (the `radius` launch option), else null. */
    fun choice(km: Double?): Double? = CHOICES_KM.firstOrNull { km != null && kotlin.math.abs(it - km) < 1e-9 }

    /** "2.5 km". */
    fun label(radiusKm: Double): String = "${km(radiusKm)} km"

    /** "10 × 10 km": the box a radius makes. */
    fun sideLabel(radiusKm: Double): String = km(2 * radiusKm).let { "$it × $it km" }

    private fun km(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else String.format(Locale.ROOT, "%.1f", value)

    private const val KM_PER_DEGREE_LAT = 111.32
}

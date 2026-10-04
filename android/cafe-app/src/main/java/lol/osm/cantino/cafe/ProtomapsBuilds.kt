package lol.osm.cantino.cafe

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Demo-only discovery. Production apps should host their own PMTiles archive. */
object ProtomapsBuilds {
    suspend fun latestUrl(
        today: LocalDate = LocalDate.now(ZoneOffset.UTC),
        maxAgeDays: Int = 7,
        baseUrl: String = "https://build.protomaps.com/",
    ): String = withContext(Dispatchers.IO) {
        require(maxAgeDays >= 0)
        val base = baseUrl.trimEnd('/') + "/"
        for (age in 0..maxAgeDays) {
            ensureActive()
            val url = base + today.minusDays(age.toLong()).format(DateTimeFormatter.BASIC_ISO_DATE) + ".pmtiles"
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "HEAD"
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                when (val status = connection.responseCode) {
                    in 200..299 -> return@withContext url
                    404 -> Unit
                    else -> throw IOException("cannot check $url: HTTP $status")
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("no Protomaps build found in the last $maxAgeDays days at $base")
    }
}

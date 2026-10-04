package lol.osm.cantino.cafe

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

class ProtomapsBuildsTest {
    @Test
    fun newestExistingBuildWins() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(404).build())
            server.enqueue(MockResponse.Builder().code(404).build())
            server.enqueue(MockResponse.Builder().code(200).build())
            server.start()
            val url = runBlocking {
                ProtomapsBuilds.latestUrl(LocalDate.of(2026, 10, 3), baseUrl = server.url("/").toString())
            }
            assertEquals(server.url("/20261001.pmtiles").toString(), url)
            for (date in listOf("20261003", "20261002", "20261001")) {
                val request = server.takeRequest()
                assertEquals("HEAD", request.method)
                assertEquals("/$date.pmtiles", request.url.encodedPath)
            }
        }
    }

    @Test
    fun missingBuildsAndServerFailuresAreReported() {
        for (status in listOf(404, 503)) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse.Builder().code(status).build())
                server.start()
                val result = runCatching {
                    runBlocking { ProtomapsBuilds.latestUrl(maxAgeDays = 0, baseUrl = server.url("/").toString()) }
                }
                assertTrue(result.exceptionOrNull() is IOException)
                assertEquals(1, server.requestCount)
            }
        }
    }
}

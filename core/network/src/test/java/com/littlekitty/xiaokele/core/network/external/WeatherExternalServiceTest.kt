package com.littlekitty.xiaokele.core.network.external

import com.littlekitty.xiaokele.core.network.OkHttpHttpTransport
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class WeatherExternalServiceTest {

    @Test
    fun qWeatherParsesGzipResponseThroughOkHttpTransparentDecompression() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Encoding", "gzip")
                    .setBody(gzip(qWeatherHourlyPayload())),
            )

            val result = QWeatherServiceAdapter(
                transport = OkHttpHttpTransport(),
                configProvider = {
                    QWeatherServiceConfig(
                        apiHost = server.url("/").toString(),
                        apiKey = "test-key",
                    )
                },
            ).fetch(
                location = WeatherLocation(
                    placeType = "HOME",
                    latitude = 28.47,
                    longitude = 119.92,
                ),
                profile = WeatherQueryProfile.CORE,
            )

            assertTrue("result=$result", result is WeatherFetchResult.Success)
            val snapshot = (result as WeatherFetchResult.Success).snapshot
            assertEquals("clear", snapshot.condition)
            assertEquals(26.0, snapshot.temperatureCelsius ?: -1.0, 0.001)

            val request = server.takeRequest()
            assertEquals("test-key", request.getHeader("X-QW-Api-Key"))
            assertEquals("/weather/v1/hourly/28.47/119.92", request.path)
        } finally {
            server.shutdown()
        }
    }

    private fun gzip(value: String): Buffer {
        val compressed = ByteArrayOutputStream()
        GZIPOutputStream(compressed).use { output ->
            output.write(value.toByteArray(Charsets.UTF_8))
        }
        return Buffer().write(compressed.toByteArray())
    }

    private fun qWeatherHourlyPayload(): String =
        """
        {
          "hours": [
            {
              "forecastTime": "2026-08-19T02:00:00Z",
              "condition": {"text": "晴"},
              "temperature": {"value": 26},
              "feelsLike": {"value": 27},
              "humidity": 0.7,
              "wind": {
                "direction": {"compass": "东"},
                "speed": {"value": 10}
              },
              "precipitation": {
                "amount": {"value": 0},
                "probability": 0
              }
            }
          ]
        }
        """.trimIndent()
}

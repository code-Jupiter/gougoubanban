package com.littlekitty.xiaokele.settings

import com.littlekitty.xiaokele.core.network.HttpTransport
import com.littlekitty.xiaokele.core.network.external.QWeatherServiceAdapter
import com.littlekitty.xiaokele.core.network.external.QWeatherServiceConfig
import com.littlekitty.xiaokele.core.network.external.WeatherFetchResult
import com.littlekitty.xiaokele.core.network.external.WeatherLocation
import kotlin.system.measureTimeMillis

sealed interface QWeatherTestResult {
    data class Success(val latencyMillis: Long, val summary: String) : QWeatherTestResult
    data class Failure(val message: String) : QWeatherTestResult
}

class QWeatherConnectionTester(
    private val transport: HttpTransport,
) {
    suspend fun test(apiHost: String, apiKey: String): QWeatherTestResult {
        if (apiHost.isBlank() || apiKey.isBlank()) {
            return QWeatherTestResult.Failure("请填写 API Host 和 API Key")
        }
        var result: WeatherFetchResult? = null
        val elapsed = measureTimeMillis {
            result = QWeatherServiceAdapter(
                transport = transport,
                configProvider = { QWeatherServiceConfig(apiHost = apiHost, apiKey = apiKey) },
            ).fetch(
                WeatherLocation(
                    placeType = "SETTINGS_TEST",
                    latitude = 31.23,
                    longitude = 121.47,
                ),
            )
        }
        return when (val value = result) {
            is WeatherFetchResult.Success -> QWeatherTestResult.Success(
                latencyMillis = elapsed,
                summary = "上海 ${value.snapshot.temperatureCelsius?.let { "${it}°C" } ?: "天气"}，${value.snapshot.condition}",
            )
            is WeatherFetchResult.Failure -> QWeatherTestResult.Failure("连接失败，请检查 Host、Key 或网络")
            WeatherFetchResult.Empty, null -> QWeatherTestResult.Failure("没有返回天气数据")
        }
    }
}

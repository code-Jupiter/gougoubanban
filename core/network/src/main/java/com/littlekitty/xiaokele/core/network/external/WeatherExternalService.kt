package com.littlekitty.xiaokele.core.network.external

import com.littlekitty.xiaokele.core.common.error.AppError
import com.littlekitty.xiaokele.core.common.error.ErrorCode
import com.littlekitty.xiaokele.core.network.ServiceAdapter
import com.littlekitty.xiaokele.core.network.HttpMethod
import com.littlekitty.xiaokele.core.network.HttpRequest
import com.littlekitty.xiaokele.core.network.HttpTransport
import com.littlekitty.xiaokele.core.network.NetworkResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Normalized weather shape only. Provider-specific response fields stay inside
 * the QWeather adapter and never leak into the app scene contract.
 */
@Serializable
data class WeatherSnapshotDto(
    @SerialName("location_key")
    val locationKey: String,
    @SerialName("observed_at")
    val observedAt: String,
    val condition: String,
    @SerialName("temperature_celsius")
    val temperatureCelsius: Double? = null,
    @SerialName("precipitation_mm")
    val precipitationMm: Double? = null,
    @SerialName("temperature_trend")
    val temperatureTrend: String? = null,
    /** Signed Celsius delta used by [com.littlekitty.xiaokele.scene.WeatherSceneEvaluator]. */
    @SerialName("temperature_delta_celsius")
    val temperatureDeltaCelsius: Double? = null,
    val aqi: Int? = null,
    @SerialName("aqi_category")
    val aqiCategory: String? = null,
    val alerts: List<WeatherAlertDto> = emptyList(),
    @SerialName("feels_like_celsius")
    val feelsLikeCelsius: Double? = null,
    val humidityPercent: Int? = null,
    @SerialName("wind_direction")
    val windDirection: String? = null,
    @SerialName("wind_speed_kph")
    val windSpeedKph: Double? = null,
    @SerialName("wind_scale")
    val windScale: String? = null,
    @SerialName("visibility_km")
    val visibilityKm: Double? = null,
    @SerialName("pressure_hpa")
    val pressureHpa: Double? = null,
    @SerialName("forecast_daily")
    val forecastDaily: List<WeatherDailyForecastDto> = emptyList(),
    @SerialName("forecast_hourly")
    val forecastHourly: List<WeatherHourlyForecastDto> = emptyList(),
    @SerialName("minutely_precipitation")
    val minutelyPrecipitation: List<WeatherMinutelyPointDto> = emptyList(),
    @SerialName("precipitation_summary")
    val precipitationSummary: String? = null,
    @SerialName("air_quality")
    val airQuality: WeatherAirQualityDto? = null,
    val indices: List<WeatherIndexDto> = emptyList(),
    val astronomy: WeatherAstronomyDto? = null,
    @SerialName("historical_weather")
    val historicalWeather: WeatherHistoricalDto? = null,
    @SerialName("expires_at")
    val expiresAt: String,
    val provenance: String? = null,
)

@Serializable
data class WeatherAlertDto(
    val id: String,
    val severity: String,
    val title: String,
    val source: String,
    @SerialName("starts_at")
    val startsAt: String,
    @SerialName("ends_at")
    val endsAt: String,
    val instruction: String? = null,
)

@Serializable
data class WeatherDailyForecastDto(
    val date: String,
    @SerialName("text_day") val textDay: String? = null,
    @SerialName("text_night") val textNight: String? = null,
    @SerialName("temp_max_celsius") val tempMaxCelsius: Double? = null,
    @SerialName("temp_min_celsius") val tempMinCelsius: Double? = null,
    @SerialName("precipitation_mm") val precipitationMm: Double? = null,
    @SerialName("precipitation_probability_percent") val precipitationProbabilityPercent: Int? = null,
    @SerialName("uv_index") val uvIndex: String? = null,
    val sunrise: String? = null,
    val sunset: String? = null,
)

@Serializable
data class WeatherHourlyForecastDto(
    @SerialName("time") val time: String,
    val text: String? = null,
    @SerialName("temperature_celsius") val temperatureCelsius: Double? = null,
    @SerialName("precipitation_mm") val precipitationMm: Double? = null,
    @SerialName("precipitation_probability_percent") val precipitationProbabilityPercent: Int? = null,
)

@Serializable
data class WeatherMinutelyPointDto(
    @SerialName("time") val time: String,
    @SerialName("precipitation_mm") val precipitationMm: Double,
    val type: String? = null,
)

@Serializable
data class WeatherAirQualityDto(
    val aqi: Int? = null,
    val level: String? = null,
    val category: String? = null,
    @SerialName("primary_pollutant") val primaryPollutant: String? = null,
    @SerialName("health_advice") val healthAdvice: String? = null,
)

@Serializable
data class WeatherIndexDto(
    val type: String,
    val name: String,
    val level: String? = null,
    val category: String? = null,
    val text: String? = null,
)

@Serializable
data class WeatherAstronomyDto(
    val sunrise: String? = null,
    val sunset: String? = null,
    val moonrise: String? = null,
    val moonset: String? = null,
    @SerialName("moon_phase") val moonPhase: String? = null,
    @SerialName("moon_illumination_percent") val moonIlluminationPercent: Int? = null,
)

@Serializable
data class WeatherHistoricalDto(
    val date: String,
    @SerialName("temp_max_celsius") val tempMaxCelsius: Double? = null,
    @SerialName("temp_min_celsius") val tempMinCelsius: Double? = null,
    @SerialName("precipitation_mm") val precipitationMm: Double? = null,
    @SerialName("weather_text") val weatherText: String? = null,
)

data class WeatherLocation(
    val placeType: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val city: String? = null,
)

/** Limits QWeather endpoint fan-out for each product use case. */
enum class WeatherQueryProfile {
    CORE,
    SHIFT_COMMUTE,
    DAILY_BRIEF,
    FULL,
}

data class QWeatherServiceConfig(
    val apiHost: String = "",
    val apiKey: String = "",
    val extendedData: Boolean = true,
) {
    val isConfigured: Boolean
        get() = apiHost.isNotBlank() && apiKey.isNotBlank()

    val normalizedApiHost: String
        get() = apiHost.trim().let {
            if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it"
        }
}

sealed interface WeatherFetchResult {
    data class Success(val snapshot: WeatherSnapshotDto) : WeatherFetchResult
    data object Empty : WeatherFetchResult
    data class Failure(val error: AppError) : WeatherFetchResult
}

interface WeatherService : ServiceAdapter {
    suspend fun fetch(location: WeatherLocation): WeatherFetchResult

    suspend fun fetch(location: WeatherLocation, profile: WeatherQueryProfile): WeatherFetchResult =
        fetch(location)
}

class PendingWeatherService(
    private val configuredApiKey: String = "",
) : WeatherService {
    override val serviceName: String = "weather_pending"
    override val provenance: String = "WEATHER_PENDING"

    override suspend fun fetch(location: WeatherLocation): WeatherFetchResult =
        WeatherFetchResult.Failure(
            AppError(
                code = ErrorCode.WEATHER_NOT_CONFIGURED,
                userMessage = null,
                technicalDetail = if (configuredApiKey.isBlank()) {
                    "qweather_config_missing"
                } else {
                    "qweather_protocol_not_frozen"
                },
            ),
        )

    override suspend fun fetch(location: WeatherLocation, profile: WeatherQueryProfile): WeatherFetchResult =
        fetch(location)
}

/**
 * QWeather weather/v1 adapter. It intentionally returns the app's normalized
 * weather model; no QWeather response type leaves this module.
 *
 * The optional endpoints are best-effort. A credential may have access to
 * weather but not every selected product, so one denied optional endpoint
 * must not hide the current weather or warning data.
 */
class QWeatherServiceAdapter(
    private val transport: HttpTransport,
    private val configProvider: () -> QWeatherServiceConfig,
    private val zoneId: ZoneId = ZoneId.of("Asia/Shanghai"),
) : WeatherService {
    override val serviceName: String = "qweather_weather_v1_hourly"
    override val provenance: String = "QWEATHER_WEATHER_V1_HOURLY"

    override suspend fun fetch(location: WeatherLocation): WeatherFetchResult =
        fetch(location, WeatherQueryProfile.FULL)

    override suspend fun fetch(
        location: WeatherLocation,
        profile: WeatherQueryProfile,
    ): WeatherFetchResult {
        val config = configProvider()
        lastFailure = null
        if (!config.isConfigured) {
            return WeatherFetchResult.Failure(
                AppError(ErrorCode.WEATHER_NOT_CONFIGURED, userMessage = null, technicalDetail = "qweather_config_incomplete"),
            )
        }
        val coordinates = location.latitude?.let { lat -> location.longitude?.let { lon -> "$lon,$lat" } }
            ?: return WeatherFetchResult.Failure(
                AppError(ErrorCode.WEATHER_NOT_CONFIGURED, userMessage = null, technicalDetail = "qweather_coordinates_missing"),
            )
        // This credential has been verified against the provider's weather/v1
        // hourly route. Its first hourly record supplies the current weather
        // fields; the full hourly list remains available to the app context.
        val locationKey = location.city ?: coordinates
        val root = get("/weather/v1/hourly/${location.latitude}/${location.longitude}", emptyMap())
            ?: return WeatherFetchResult.Failure(lastFailure ?: invalid("qweather_hourly_unavailable"))
        val records = root.array("hours").mapNotNull { it.objOrNull()?.toV1HourlyRecord() }
        val current = records.firstOrNull()
            ?: return WeatherFetchResult.Empty
        val hourly = records.map {
            WeatherHourlyForecastDto(
                time = it.forecastTime,
                text = it.conditionText,
                temperatureCelsius = it.temperatureCelsius,
                precipitationMm = it.precipitationMm,
                precipitationProbabilityPercent = it.precipitationProbabilityPercent,
            )
        }
        val expiresAt = runCatching {
            java.time.Instant.parse(current.forecastTime).plusSeconds(60 * 60).toString()
        }.getOrElse { java.time.Instant.now().plusSeconds(60 * 60).toString() }
        return WeatherFetchResult.Success(
            WeatherSnapshotDto(
                locationKey = locationKey,
                observedAt = current.forecastTime,
                condition = normalizeCondition(current.conditionText),
                temperatureCelsius = current.temperatureCelsius,
                precipitationMm = current.precipitationMm,
                temperatureTrend = temperatureTrendFromHourly(records),
                feelsLikeCelsius = current.feelsLikeCelsius,
                humidityPercent = current.humidityPercent,
                windDirection = current.windDirection,
                windSpeedKph = current.windSpeedKph,
                windScale = current.windScale,
                visibilityKm = current.visibilityKm,
                pressureHpa = current.pressureHpa,
                forecastHourly = hourly,
                expiresAt = expiresAt,
                provenance = provenance,
            ),
        )
    }

    private var lastFailure: AppError? = null

    private suspend fun get(path: String, params: Map<String, String>): JsonObject? {
        val config = configProvider()
        val query = params.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        val suffix = query.takeIf { it.isNotBlank() }?.let { "?$it" }.orEmpty()
        return when (val result = transport.execute(HttpRequest(HttpMethod.GET, "${config.normalizedApiHost.trimEnd('/')}$path$suffix", headers = mapOf(
            "X-QW-Api-Key" to config.apiKey,
        )))) {
            is NetworkResult.Success -> if (result.statusCode == 200) {
                runCatching { com.littlekitty.xiaokele.core.common.json.JsonCodec.decode<JsonObject>(result.body) }.getOrNull()
                    ?: run { lastFailure = invalid("qweather_json_invalid"); null }
            } else {
                lastFailure = AppError(ErrorCode.WEATHER_FAILURE, userMessage = null, technicalDetail = "qweather_http_${result.statusCode}")
                null
            }
            is NetworkResult.Failure -> { lastFailure = result.error.copy(userMessage = null); null }
        }
    }

    private suspend fun getAirQuality(location: WeatherLocation): JsonObject? {
        val lat = location.latitude ?: return null
        val lon = location.longitude ?: return null
        return getPath("/airquality/v1/current/$lat/$lon", mapOf("lang" to "zh"))
    }

    private suspend fun getWeatherAlerts(location: WeatherLocation): JsonObject? {
        val lat = location.latitude ?: return null
        val lon = location.longitude ?: return null
        return getPath("/weatheralert/v1/current/$lat/$lon", mapOf("localTime" to "true", "lang" to "zh"))
    }

    private suspend fun getPath(path: String, params: Map<String, String>): JsonObject? = get(path, params)

    private fun parseDaily(root: JsonObject): List<WeatherDailyForecastDto> = root.array("daily").mapNotNull { it.objOrNull()?.let { d ->
        WeatherDailyForecastDto(d.string("fxDate") ?: return@let null, d.string("textDay"), d.string("textNight"), d.number("tempMax"), d.number("tempMin"), d.number("precip"), d.int("pop"), d.string("uvIndex"), d.string("sunrise"), d.string("sunset"))
    } }

    private fun parseHourly(root: JsonObject): List<WeatherHourlyForecastDto> = root.array("hourly").mapNotNull { it.objOrNull()?.let { h ->
        WeatherHourlyForecastDto(h.string("fxTime") ?: return@let null, h.string("text"), h.number("temp"), h.number("precip"), h.int("pop"))
    } }

    private fun parseMinutely(root: JsonObject): List<WeatherMinutelyPointDto> = root.array("minutely").mapNotNull { it.objOrNull()?.let { m ->
        WeatherMinutelyPointDto(m.string("fxTime") ?: return@let null, m.number("precip") ?: return@let null, m.string("type"))
    } }

    private fun parseAlerts(root: JsonObject, fallbackStart: String): List<WeatherAlertDto> = root.array("warning").mapNotNull { it.objOrNull()?.let { w ->
        val id = w.string("id") ?: return@let null
        val starts = w.string("startTime") ?: w.string("pubTime") ?: fallbackStart
        val ends = w.string("endTime") ?: w.string("expires") ?: runCatching { java.time.Instant.parse(starts).plusSeconds(6 * 60 * 60).toString() }.getOrDefault(fallbackStart)
        WeatherAlertDto(id, normalizeSeverity(w.string("level"), w.string("severity")), w.string("title") ?: w.string("event") ?: "天气预警", w.string("sender") ?: "和风天气", starts, ends, w.string("instruction") ?: w.string("description"))
    } }

    private fun parseAirQuality(root: JsonObject): WeatherAirQualityDto? {
        val index = root.array("indexes").firstOrNull()?.objOrNull() ?: root.obj("indexes")
        return index?.let {
            val health = it.obj("health")
            val advice = health?.obj("advice")
            WeatherAirQualityDto(
                aqi = it.int("aqi"),
                level = it.string("level"),
                category = it.string("category"),
                primaryPollutant = it.string("primaryPollutant"),
                healthAdvice = advice?.string("generalPopulation") ?: advice?.string("sensitivePopulation"),
            )
        }
    }

    private fun parseIndices(root: JsonObject): List<WeatherIndexDto> = root.array("daily").mapNotNull { it.objOrNull()?.let { d ->
        WeatherIndexDto(d.string("type") ?: return@let null, d.string("name") ?: return@let null, d.string("level"), d.string("category"), d.string("text"))
    } }

    private fun parseAstronomy(sun: JsonObject?, moon: JsonObject?): WeatherAstronomyDto? = if (sun == null && moon == null) null else WeatherAstronomyDto(
        sunrise = sun?.string("sunrise"), sunset = sun?.string("sunset"), moonrise = moon?.string("moonrise"), moonset = moon?.string("moonset"),
        moonPhase = moon?.array("moonPhase")?.firstOrNull()?.objOrNull()?.string("name"),
        moonIlluminationPercent = moon?.array("moonPhase")?.firstOrNull()?.objOrNull()?.int("illumination"),
    )

    private fun parseHistorical(root: JsonObject): WeatherHistoricalDto? = root.obj("weatherDaily")?.let { d -> WeatherHistoricalDto(d.string("date") ?: return@let null, d.number("tempMax"), d.number("tempMin"), d.number("precip"), d.string("textDay")) }

    private data class V1HourlyRecord(
        val forecastTime: String,
        val conditionText: String?,
        val temperatureCelsius: Double?,
        val feelsLikeCelsius: Double?,
        val humidityPercent: Int?,
        val windDirection: String?,
        val windSpeedKph: Double?,
        val windScale: String?,
        val visibilityKm: Double?,
        val pressureHpa: Double?,
        val precipitationMm: Double?,
        val precipitationProbabilityPercent: Int?,
    )

    private fun JsonObject.toV1HourlyRecord(): V1HourlyRecord? {
        val forecastTime = string("forecastTime") ?: return null
        val condition = obj("condition")
        val temperature = obj("temperature")
        val feelsLike = obj("feelsLike")
        val humidity = number("humidity")
        val wind = obj("wind")
        val windDirection = wind?.obj("direction")
        val windSpeed = wind?.obj("speed")
        val windGust = obj("windGust")
        val precipitation = obj("precipitation")
        val precipitationAmount = precipitation?.obj("amount")
        return V1HourlyRecord(
            forecastTime = forecastTime,
            conditionText = condition?.string("text"),
            temperatureCelsius = temperature?.number("value"),
            feelsLikeCelsius = feelsLike?.number("value"),
            humidityPercent = humidity?.let { if (it <= 1.0) (it * 100).toInt() else it.toInt() },
            windDirection = windDirection?.string("compass") ?: windDirection?.number("degree")?.toString(),
            windSpeedKph = windSpeed?.number("value")?.times(3.6),
            windScale = wind?.string("scale") ?: windGust?.number("value")?.toString(),
            visibilityKm = obj("visibility")?.number("value")?.div(1000.0),
            pressureHpa = obj("pressure")?.number("value"),
            precipitationMm = precipitationAmount?.number("value"),
            precipitationProbabilityPercent = precipitation?.number("probability")?.let {
                if (it <= 1.0) (it * 100).toInt() else it.toInt()
            },
        )
    }

    private fun temperatureTrendFromHourly(records: List<V1HourlyRecord>): String? {
        val current = records.firstOrNull()?.temperatureCelsius ?: return null
        val maximum = records.mapNotNull { it.temperatureCelsius }.maxOrNull() ?: return null
        return if (maximum > current + 3.0) "RISING" else if (maximum < current - 3.0) "FALLING" else "STABLE"
    }

    private fun temperatureTrend(daily: List<WeatherDailyForecastDto>, current: Double?): String? = daily.firstOrNull()?.let { d ->
        if (current == null || d.tempMaxCelsius == null) null else if (d.tempMaxCelsius > current + 3) "RISING" else if (d.tempMaxCelsius < current - 3) "FALLING" else "STABLE"
    }

    private fun normalizeCondition(text: String?): String {
        val value = text.orEmpty().lowercase()
        return when {
            value.contains("雷") || value.contains("thunder") || value.contains("storm") -> "storm"
            value.contains("冰雹") || value.contains("hail") -> "hail"
            value.contains("雨夹雪") || value.contains("sleet") -> "sleet"
            value.contains("雪") || value.contains("snow") -> "snow"
            value.contains("雨") || value.contains("rain") || value.contains("drizzle") -> "rain"
            value.contains("台风") || value.contains("typhoon") -> "typhoon"
            value.contains("沙") || value.contains("dust") -> "dust"
            value.contains("雾") || value.contains("fog") -> "fog"
            value.contains("霾") || value.contains("haze") -> "haze"
            value.contains("晴") || value.contains("clear") -> "clear"
            value.contains("云") || value.contains("cloud") || value.contains("overcast") -> "cloudy"
            else -> text ?: "unknown"
        }
    }

    private fun normalizeSeverity(level: String?, severity: String?): String = when ((level ?: severity).orEmpty().lowercase()) {
        "red", "orange", "high", "severe", "extreme" -> "HIGH"
        "yellow", "medium", "moderate" -> "MEDIUM"
        else -> "LOW"
    }

    private fun apiFailure(root: JsonObject, endpoint: String): AppError = AppError(ErrorCode.WEATHER_FAILURE, userMessage = null, technicalDetail = "$endpoint:${root.string("code") ?: "unknown"}")
    private fun invalid(detail: String): AppError = AppError(ErrorCode.WEATHER_INVALID_RESPONSE, userMessage = null, technicalDetail = detail)
    private fun encode(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.number(key: String): Double? = this[key]?.jsonPrimitive?.doubleOrNull
    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull ?: number(key)?.toInt()
    private fun JsonObject.obj(key: String): JsonObject? = this[key]?.jsonObjectOrNull()
    private fun JsonObject.array(key: String): JsonArray = this[key]?.jsonArrayOrNull() ?: JsonArray(emptyList())
    private fun JsonElement.objOrNull(): JsonObject? = (this as? JsonObject)
    private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject
    private fun JsonElement.jsonArrayOrNull(): JsonArray? = this as? JsonArray

    companion object {
        private const val INDEX_TYPES = "1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16"
        private val DATE: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
    }
}

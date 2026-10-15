package com.littlekitty.xiaokele.external

import com.littlekitty.xiaokele.core.network.external.WeatherSnapshotDto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add

/** A compact, provider-neutral weather view supplied to chat and scene AI. */
fun WeatherSnapshotDto.toWeatherContextJson(): JsonObject = buildJsonObject {
    put("condition", condition)
    temperatureCelsius?.let { put("temperature_celsius", it) }
    feelsLikeCelsius?.let { put("feels_like_celsius", it) }
    humidityPercent?.let { put("humidity_percent", it) }
    precipitationMm?.let { put("precipitation_mm", it) }
    precipitationSummary?.let { put("precipitation_summary", it) }
    temperatureTrend?.let { put("temperature_trend", it) }
    aqi?.let { put("aqi", it) }
    aqiCategory?.let { put("aqi_category", it) }
    windDirection?.let { put("wind_direction", it) }
    windSpeedKph?.let { put("wind_speed_kph", it) }
    windScale?.let { put("wind_scale", it) }
    visibilityKm?.let { put("visibility_km", it) }
    pressureHpa?.let { put("pressure_hpa", it) }
    put("observed_at", observedAt)
    putJsonArray("alerts") {
        alerts.forEach { alert ->
            add(buildJsonObject {
                put("title", alert.title)
                put("severity", alert.severity)
                put("instruction", alert.instruction ?: "")
                put("starts_at", alert.startsAt)
                put("ends_at", alert.endsAt)
            })
        }
    }
    putJsonArray("forecast_daily") {
        forecastDaily.take(7).forEach { day ->
            add(buildJsonObject {
                put("date", day.date)
                day.textDay?.let { put("text_day", it) }
                day.textNight?.let { put("text_night", it) }
                day.tempMaxCelsius?.let { put("temp_max_celsius", it) }
                day.tempMinCelsius?.let { put("temp_min_celsius", it) }
                day.precipitationMm?.let { put("precipitation_mm", it) }
                day.precipitationProbabilityPercent?.let { put("precipitation_probability_percent", it) }
                day.uvIndex?.let { put("uv_index", it) }
                day.sunrise?.let { put("sunrise", it) }
                day.sunset?.let { put("sunset", it) }
            })
        }
    }
    putJsonArray("forecast_hourly") {
        // The wake weather brief talks about the next 24 hours, so expose the
        // full hourly lane instead of the previous 12-hour slice.
        forecastHourly.take(24).forEach { hour ->
            add(buildJsonObject {
                put("time", hour.time)
                hour.text?.let { put("text", it) }
                hour.temperatureCelsius?.let { put("temperature_celsius", it) }
                hour.precipitationMm?.let { put("precipitation_mm", it) }
                hour.precipitationProbabilityPercent?.let { put("precipitation_probability_percent", it) }
            })
        }
    }
    putJsonArray("minutely_precipitation") {
        minutelyPrecipitation.take(24).forEach { point ->
            add(buildJsonObject {
                put("time", point.time)
                put("precipitation_mm", point.precipitationMm)
                point.type?.let { put("type", it) }
            })
        }
    }
    airQuality?.let { quality ->
        put("air_quality", buildJsonObject {
            quality.aqi?.let { put("aqi", it) }
            quality.level?.let { put("level", it) }
            quality.category?.let { put("category", it) }
            quality.primaryPollutant?.let { put("primary_pollutant", it) }
            quality.healthAdvice?.let { put("health_advice", it) }
        })
    }
    putJsonArray("indices") {
        indices.forEach { index ->
            add(buildJsonObject {
                put("type", index.type)
                put("name", index.name)
                index.level?.let { put("level", it) }
                index.category?.let { put("category", it) }
                index.text?.let { put("text", it) }
            })
        }
    }
    astronomy?.let { sky ->
        put("astronomy", buildJsonObject {
            sky.sunrise?.let { put("sunrise", it) }
            sky.sunset?.let { put("sunset", it) }
            sky.moonrise?.let { put("moonrise", it) }
            sky.moonset?.let { put("moonset", it) }
            sky.moonPhase?.let { put("moon_phase", it) }
            sky.moonIlluminationPercent?.let { put("moon_illumination_percent", it) }
        })
    }
    historicalWeather?.let { history ->
        put("historical_weather", buildJsonObject {
            put("date", history.date)
            history.tempMaxCelsius?.let { put("temp_max_celsius", it) }
            history.tempMinCelsius?.let { put("temp_min_celsius", it) }
            history.precipitationMm?.let { put("precipitation_mm", it) }
            history.weatherText?.let { put("weather_text", it) }
        })
    }
}

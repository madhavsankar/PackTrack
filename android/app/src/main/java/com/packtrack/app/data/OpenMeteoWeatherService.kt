package com.packtrack.app.data

import com.packtrack.app.domain.TravelerTemplateCatalog
import com.packtrack.app.domain.WeatherForecast
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder

/**
 * Dedicated Weather & Geocoding Service using Open-Meteo (free, no API key required).
 * Isolated from the AI/Voice agent so weather fetching is testable and reusable.
 */
object OpenMeteoWeatherService {

    private const val GEOCODING_URL = "https://geocoding-api.open-meteo.com/v1/search"
    private const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
    private const val ARCHIVE_URL = "https://archive-api.open-meteo.com/v1/archive"
    private const val DEFAULT_HOME_COUNTRY = "US"

    private fun describeWmoCode(code: Int): String = when (code) {
        0 -> "Clear sky"
        1, 2, 3 -> "Partly cloudy"
        45, 48 -> "Foggy"
        51, 53, 55, 56, 57 -> "Drizzle"
        61, 63, 80, 81 -> "Rain"
        65, 66, 67, 82 -> "Heavy rain"
        71, 73, 77, 85 -> "Snowfall"
        75, 86 -> "Heavy snow"
        95, 96, 99 -> "Thunderstorm"
        else -> "Mixed conditions"
    }

    fun fetchWeather(
        cityQuery: String,
        days: Int,
        startDate: String? = null,
        endDate: String? = null
    ): WeatherForecast {
        val cleanCity = cityQuery.trim().replaceFirstChar { it.uppercase() }
        var resolvedCity = cleanCity
        var resolvedCountry = "United States"
        var resolvedCode = "US"
        var avgHighF = 65.0
        var avgLowF = 50.0
        var maxHighF = 65.0
        var minLowF = 50.0
        var rainyDays = 1
        var hasSnow = false
        var maxWindMph = 10.0
        var maxUvIndex = 5.0
        val dailyBreakdown = mutableListOf<String>()

        try {
            val geoUrl = "$GEOCODING_URL?name=${URLEncoder.encode(cleanCity, "UTF-8")}&count=1&language=en&format=json"
            val geoText = URL(geoUrl).readText()
            val results = JSONObject(geoText).optJSONArray("results")
            if (results != null && results.length() > 0) {
                val top = results.getJSONObject(0)
                val lat = top.getDouble("latitude")
                val lon = top.getDouble("longitude")
                resolvedCity = top.optString("name", cleanCity)
                resolvedCountry = top.optString("country", "United States")
                resolvedCode = top.optString("country_code", "US").uppercase()

                val forecastDays = minOf(14, maxOf(1, days))
                val today = java.time.LocalDate.now()
                val parsedStart = try {
                    startDate?.take(10)?.let { java.time.LocalDate.parse(it) }
                } catch (_: Exception) {
                    null
                }
                val parsedEnd = try {
                    endDate?.take(10)?.let { java.time.LocalDate.parse(it) }
                } catch (_: Exception) {
                    null
                } ?: parsedStart?.plusDays((forecastDays - 1).toLong())

                val weatherUrl = if (parsedStart != null && parsedEnd != null) {
                    val diffFromToday = java.time.temporal.ChronoUnit.DAYS.between(today, parsedStart)
                    if (diffFromToday in 0..14) {
                        val maxAllowedEnd = today.plusDays(15)
                        val clampedEnd = if (parsedEnd.isAfter(maxAllowedEnd)) maxAllowedEnd else parsedEnd
                        val safeEnd = if (clampedEnd.isBefore(parsedStart)) parsedStart else clampedEnd
                        "$FORECAST_URL?latitude=$lat&longitude=$lon" +
                            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum,snowfall_sum,wind_speed_10m_max,uv_index_max" +
                            "&temperature_unit=fahrenheit&wind_speed_unit=mph&timezone=auto&start_date=$parsedStart&end_date=$safeEnd"
                    } else if (diffFromToday > 14) {
                        // Use prior-year seasonal weather archive for the exact calendar dates when beyond 14-day live forecast horizon
                        val histStart = parsedStart.minusYears(1)
                        val histEnd = parsedEnd.minusYears(1).let {
                            if (java.time.temporal.ChronoUnit.DAYS.between(histStart, it) > 14) histStart.plusDays(13) else it
                        }
                        "$ARCHIVE_URL?latitude=$lat&longitude=$lon" +
                            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,snowfall_sum,wind_speed_10m_max" +
                            "&temperature_unit=fahrenheit&wind_speed_unit=mph&timezone=auto&start_date=$histStart&end_date=$histEnd"
                    } else {
                        "$FORECAST_URL?latitude=$lat&longitude=$lon" +
                            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum,snowfall_sum,wind_speed_10m_max,uv_index_max" +
                            "&temperature_unit=fahrenheit&wind_speed_unit=mph&timezone=auto&forecast_days=$forecastDays"
                    }
                } else {
                    "$FORECAST_URL?latitude=$lat&longitude=$lon" +
                        "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum,snowfall_sum,wind_speed_10m_max,uv_index_max" +
                        "&temperature_unit=fahrenheit&wind_speed_unit=mph&timezone=auto&forecast_days=$forecastDays"
                }

                val dailyJson = JSONObject(URL(weatherUrl).readText()).getJSONObject("daily")
                val timeArr = dailyJson.optJSONArray("time")
                val codeArr = dailyJson.optJSONArray("weather_code")
                val maxArr = dailyJson.getJSONArray("temperature_2m_max")
                val minArr = dailyJson.getJSONArray("temperature_2m_min")
                val rainProbArr = dailyJson.optJSONArray("precipitation_probability_max")
                val precipSumArr = dailyJson.optJSONArray("precipitation_sum")
                val snowArr = dailyJson.optJSONArray("snowfall_sum")
                val windArr = dailyJson.optJSONArray("wind_speed_10m_max")
                val uvArr = dailyJson.optJSONArray("uv_index_max")

                var sumMax = 0.0
                var sumMin = 0.0
                var rainCount = 0
                var hiPeak = -200.0
                var loTrough = 200.0
                var windPeak = 0.0
                var uvPeak = 0.0

                val count = minOf(days, maxArr.length())
                for (i in 0 until count) {
                    val dayHigh = maxArr.getDouble(i)
                    val dayLow = minArr.getDouble(i)
                    val rainProb = rainProbArr?.optInt(i, 0) ?: 0
                    val precipMm = precipSumArr?.optDouble(i, 0.0) ?: 0.0
                    val snowCm = snowArr?.optDouble(i, 0.0) ?: 0.0
                    val windMph = windArr?.optDouble(i, 0.0) ?: 0.0
                    val uvIdx = uvArr?.optDouble(i, 0.0) ?: 0.0
                    val wmoCode = codeArr?.optInt(i, 0) ?: 0
                    val dateStr = timeArr?.optString(i, "Day ${i + 1}") ?: "Day ${i + 1}"

                    sumMax += dayHigh
                    sumMin += dayLow
                    if (dayHigh > hiPeak) hiPeak = dayHigh
                    if (dayLow < loTrough) loTrough = dayLow
                    if (windMph > windPeak) windPeak = windMph
                    if (uvIdx > uvPeak) uvPeak = uvIdx

                    if (rainProb >= 40 || precipMm > 1.5 || wmoCode in listOf(51, 53, 55, 61, 63, 65, 80, 81, 82, 95, 96, 99)) {
                        rainCount++
                    }
                    if (snowCm > 0.1 || wmoCode in listOf(71, 73, 75, 77, 85, 86)) {
                        hasSnow = true
                    }

                    val condLabel = describeWmoCode(wmoCode)
                    val extras = buildList {
                        if (rainProb > 0) add("${rainProb}% precip")
                        if (snowCm > 0.1) add("${"%.1f".format(snowCm)}cm snow")
                        if (windMph >= 18.0) add("wind ${windMph.toInt()}mph")
                        if (uvIdx >= 7.0) add("UV ${uvIdx.toInt()}")
                    }.joinToString(", ")
                    dailyBreakdown.add(
                        "Day ${i + 1} ($dateStr): ${dayLow.toInt()}°F–${dayHigh.toInt()}°F, $condLabel" +
                            if (extras.isNotEmpty()) " ($extras)" else ""
                    )
                }
                if (count > 0) {
                    avgHighF = sumMax / count
                    avgLowF = sumMin / count
                    maxHighF = hiPeak
                    minLowF = loTrough
                    rainyDays = rainCount
                    maxWindMph = windPeak
                    maxUvIndex = uvPeak
                }
            }
        } catch (_: Exception) {
            // Offline or timeout: keep sensible climate defaults
        }

        val isIntl = !resolvedCode.equals(DEFAULT_HOME_COUNTRY, ignoreCase = true)
        val plug = TravelerTemplateCatalog.plugTypeForCountry(resolvedCode)

        return WeatherForecast(
            city = resolvedCity,
            country = resolvedCountry,
            countryCode = resolvedCode,
            avgHighF = avgHighF,
            avgLowF = avgLowF,
            rainyDaysCount = rainyDays,
            plugTypes = plug,
            isInternational = isIntl,
            dailyBreakdown = dailyBreakdown,
            hasSnow = hasSnow,
            maxHighF = maxHighF,
            minLowF = minLowF,
            maxWindMph = maxWindMph,
            maxUvIndex = maxUvIndex
        )
    }
}


package com.tunisianprayertimes.weather

import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** The weather at the mosque now, and today's lowest and highest temperatures (°C). */
data class WeatherNow(
    val temperature: Double,
    val code: Int,
    val isDay: Boolean,
    val todayMin: Double? = null,
    val todayMax: Double? = null,
) {
    /**
     * For the screen: "☀️ 24° صحو · 18°–27°" with whole degrees. The numbers are isolated left to
     * right, or an Arabic line would show the range as "°27–°18".
     */
    fun line(): String {
        val range = if (todayMin != null && todayMax != null) " · ${ltr("${degrees(todayMin)}–${degrees(todayMax)}")}" else ""
        return "${OpenMeteo.symbol(code, isDay)} ${ltr(degrees(temperature))} ${OpenMeteo.describe(code)}$range"
    }

    private fun degrees(value: Double) = "${Math.round(value)}°"

    private fun ltr(text: String) = "⁦$text⁩"
}

/**
 * Open-Meteo (open-meteo.com): free weather without an API key, for non-commercial use. Its data is
 * CC BY 4.0, so every screen showing it credits [ATTRIBUTION]. One small request gives the current
 * weather and today's range at the coordinates the mosque uses for its prayer times.
 */
object OpenMeteo {

    const val ATTRIBUTION = "Open-Meteo.com"

    fun forecastUrl(latitude: Double, longitude: Double): String = String.format(
        Locale.ROOT,
        "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f" +
            "&current=temperature_2m,weather_code,is_day" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min" +
            "&timezone=Africa%%2FTunis&forecast_days=1",
        latitude, longitude,
    )

    /** The answer of [forecastUrl], or null when it is an error or not what is expected. Never throws. */
    fun parse(json: String): WeatherNow? = runCatching {
        val root = Json.parseToJsonElement(json) as JsonObject
        if ((root["error"] as? JsonPrimitive)?.booleanOrNull == true) return null
        val current = root["current"] as JsonObject
        val daily = root["daily"] as? JsonObject
        fun first(key: String) = ((daily?.get(key) as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.doubleOrNull
        WeatherNow(
            temperature = (current["temperature_2m"] as JsonPrimitive).doubleOrNull ?: return null,
            code = (current["weather_code"] as JsonPrimitive).intOrNull ?: return null,
            isDay = (current["is_day"] as? JsonPrimitive)?.intOrNull != 0,
            todayMin = first("temperature_2m_min"),
            todayMax = first("temperature_2m_max"),
        )
    }.getOrNull()

    /** The WMO weather code in plain Arabic. */
    fun describe(code: Int): String = when (code) {
        0 -> "صحو"
        1 -> "صحو غالبًا"
        2 -> "غائم جزئيًا"
        3 -> "غائم"
        45, 48 -> "ضباب"
        51, 53, 55 -> "رذاذ"
        56, 57 -> "رذاذ متجمّد"
        61 -> "مطر خفيف"
        63 -> "مطر"
        65 -> "مطر غزير"
        66, 67 -> "مطر متجمّد"
        71, 73, 75, 77 -> "ثلج"
        80 -> "زخات مطر خفيفة"
        81 -> "زخات مطر"
        82 -> "زخات مطر غزيرة"
        85, 86 -> "زخات ثلج"
        95 -> "عاصفة رعدية"
        96, 99 -> "عاصفة رعدية مع برد"
        else -> ""
    }

    fun symbol(code: Int, isDay: Boolean): String = when (code) {
        0, 1 -> if (isDay) "☀️" else "🌙"
        2 -> if (isDay) "⛅" else "☁️"
        3 -> "☁️"
        45, 48 -> "🌫️"
        in 51..67, in 80..82 -> "🌧️"
        in 71..77, 85, 86 -> "🌨️"
        in 95..99 -> "⛈️"
        else -> "🌡️"
    }
}

/** The last weather that was fetched, and when (epoch milliseconds). Old weather is not shown. */
data class CachedWeather(val weather: WeatherNow, val fetchedAtMillis: Long) {
    fun isFresh(nowMillis: Long): Boolean = nowMillis - fetchedAtMillis in 0..MAX_AGE_MILLIS

    companion object {
        /** Past this, the weather on the wall would mislead more than it helps. */
        const val MAX_AGE_MILLIS = 3 * 60 * 60 * 1000L

        /** How often a connected TV asks for the weather: well within the free limits. */
        const val REFRESH_MILLIS = 30 * 60 * 1000L
    }
}

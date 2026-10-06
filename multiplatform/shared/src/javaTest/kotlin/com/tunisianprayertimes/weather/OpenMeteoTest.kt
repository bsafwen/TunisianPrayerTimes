package com.tunisianprayertimes.weather

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenMeteoTest {

    // The shape Open-Meteo answered for Tunis on 2026-09-29.
    private val answer = """
        {"latitude":36.8,"longitude":10.2,"utc_offset_seconds":3600,"timezone":"Africa/Tunis",
         "current_units":{"time":"iso8601","interval":"seconds","temperature_2m":"°C","weather_code":"wmo code","is_day":""},
         "current":{"time":"2026-09-29T11:30","interval":900,"temperature_2m":28.6,"weather_code":1,"is_day":1},
         "daily":{"time":["2026-09-29"],"weather_code":[3],"temperature_2m_max":[30.5],"temperature_2m_min":[21.4]}}
    """.trimIndent()

    @Test
    fun theAnswerIsReadAndShownInArabic() {
        val weather = OpenMeteo.parse(answer)!!
        assertEquals(WeatherNow(28.6, 1, true, 21.4, 30.5), weather)
        assertEquals("☀️ ⁦29°⁩ صحو غالبًا · ⁦21°–31°⁩", weather.line())
    }

    @Test
    fun errorsAndOddAnswersAreIgnored() {
        assertNull(OpenMeteo.parse("""{"error":true,"reason":"Latitude must be in range"}"""))
        assertNull(OpenMeteo.parse("not json"))
        assertNull(OpenMeteo.parse("""{"current":{"temperature_2m":null,"weather_code":1}}"""))
        // Without the daily part, the current weather is still shown.
        val bare = OpenMeteo.parse("""{"current":{"temperature_2m":12.2,"weather_code":61,"is_day":0}}""")!!
        assertEquals("🌧️ ⁦12°⁩ مطر خفيف", bare.line())
    }

    @Test
    fun theRequestAsksForTheMosquesCoordinatesInTunisianTime() {
        val url = OpenMeteo.forecastUrl(36.8065, 10.1815)
        assertTrue(url.startsWith("https://api.open-meteo.com/v1/forecast?latitude=36.807&longitude=10.182"))
        assertTrue(url.contains("timezone=Africa%2FTunis"))
        assertTrue(url.contains("current=temperature_2m,weather_code,is_day"))
    }

    @Test
    fun oldWeatherIsNotShown() {
        val cached = CachedWeather(WeatherNow(20.0, 0, true), fetchedAtMillis = 1_000_000)
        assertTrue(cached.isFresh(1_000_000 + CachedWeather.MAX_AGE_MILLIS))
        assertTrue(!cached.isFresh(1_000_000 + CachedWeather.MAX_AGE_MILLIS + 1))
        assertTrue(!cached.isFresh(999_999), "a clock set back does not make it fresh")
    }
}

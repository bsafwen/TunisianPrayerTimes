package com.tunisianprayertimes.tv.data

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.edit
import com.tunisianprayertimes.weather.CachedWeather
import com.tunisianprayertimes.weather.OpenMeteo
import java.net.HttpURLConnection
import java.net.URL

/**
 * The weather at the mosque, from Open-Meteo, for TVs that are online. The last answer is kept
 * with its time, for the place it was asked for; an answer too old, or for another place, is not shown.
 */
class WeatherRepository(
    private val prefs: SharedPreferences,
    private val fetch: (String) -> String = ::httpGet,
) {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences("weather", Context.MODE_PRIVATE))

    /** The last weather for [place] (a delegation id), fresh or not. */
    fun cached(place: Int): CachedWeather? {
        if (prefs.getInt(KEY_PLACE, -1) != place) return null
        val weather = prefs.getString(KEY_JSON, null)?.let(OpenMeteo::parse) ?: return null
        return CachedWeather(weather, prefs.getLong(KEY_FETCHED, 0L))
    }

    /** Asks Open-Meteo for the weather at [place] and keeps it; null when that fails (offline). Blocking. */
    fun refresh(place: Int, latitude: Double, longitude: Double, nowMillis: Long): CachedWeather? {
        val json = runCatching { fetch(OpenMeteo.forecastUrl(latitude, longitude)) }.getOrNull() ?: return null
        val weather = OpenMeteo.parse(json) ?: return null
        prefs.edit {
            putString(KEY_JSON, json)
            putLong(KEY_FETCHED, nowMillis)
            putInt(KEY_PLACE, place)
        }
        return CachedWeather(weather, nowMillis)
    }

    companion object {
        private const val KEY_JSON = "json"
        private const val KEY_FETCHED = "fetched_at"
        private const val KEY_PLACE = "place"

        private fun httpGet(url: String): String {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.setRequestProperty("User-Agent", "TunisianPrayerTimesTV")
                check(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
                return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            } finally {
                connection.disconnect()
            }
        }

        /** Whether the TV has a network that reaches the internet (never throws). */
        fun isOnline(context: Context): Boolean = runCatching {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }.getOrDefault(false)

        /**
         * Whether the TV has a network that claims the internet, validated or not (never throws). The
         * system's check fails on a box whose clock is years off, which the network time must still reach.
         */
        fun hasInternet(context: Context): Boolean = runCatching {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            manager.getNetworkCapabilities(manager.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }.getOrDefault(false)
    }
}

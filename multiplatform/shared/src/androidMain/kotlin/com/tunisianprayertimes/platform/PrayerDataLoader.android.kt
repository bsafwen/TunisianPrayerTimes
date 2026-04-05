package com.tunisianprayertimes.platform

import android.content.Context
import com.tunisianprayertimes.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale

actual object PrayerDataLoader {
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun requireContext(): Context =
        appContext ?: error("PrayerDataLoader not initialized. Call init(context) first.")

    private fun csvPath(delegationId: Int, year: Int, month: Int): String =
        "csv/$delegationId/$year/${String.format(Locale.US, "%02d", month)}.csv"

    actual fun hasPrayerData(delegationId: Int, year: Int, month: Int): Boolean {
        return try {
            requireContext().assets.open(csvPath(delegationId, year, month)).use { true }
        } catch (_: Exception) {
            false
        }
    }

    actual fun loadPrayerTimes(delegationId: Int, year: Int, month: Int): List<DayPrayerTimes> {
        return try {
            requireContext().assets.open(csvPath(delegationId, year, month)).use { stream ->
                val text = BufferedReader(InputStreamReader(stream)).use { it.readText() }
                CsvParser.parsePrayerTimes(text)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    actual fun loadDayPrayerTimes(delegationId: Int, year: Int, month: Int, day: Int): DayPrayerTimes? {
        return loadPrayerTimes(delegationId, year, month).find { it.day == day }
    }
}

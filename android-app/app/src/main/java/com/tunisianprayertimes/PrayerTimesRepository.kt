package com.tunisianprayertimes

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

object PrayerTimesRepository {

    /**
     * Load prayer times for a given delegation, year, and month from bundled CSV assets.
     * CSV format: Day,Fajr,Shuruk,Duhr,Asr,Maghrib,Isha
     */
    fun loadPrayerTimes(context: Context, delegationId: Int, year: Int, month: Int): List<DayPrayerTimes> {
        val path = "csv/$delegationId/$year/${String.format("%02d", month)}.csv"
        val results = mutableListOf<DayPrayerTimes>()

        context.assets.open(path).use { stream ->
            BufferedReader(InputStreamReader(stream)).use { reader ->
                // Skip header line
                reader.readLine()

                var line = reader.readLine()
                while (line != null) {
                    val parts = line.split(",")
                    if (parts.size >= 7) {
                        val day = parts[0].trim().toIntOrNull() ?: 0
                        val fajr = parseTime(Prayer.FAJR, parts[1].trim())
                        val dhuhr = parseTime(Prayer.DHUHR, parts[3].trim())
                        val asr = parseTime(Prayer.ASR, parts[4].trim())
                        val maghrib = parseTime(Prayer.MAGHRIB, parts[5].trim())
                        val isha = parseTime(Prayer.ISHA, parts[6].trim())

                        results.add(DayPrayerTimes(day, fajr, dhuhr, asr, maghrib, isha))
                    }
                    line = reader.readLine()
                }
            }
        }

        return results
    }

    /**
     * Load prayer times for a specific day.
     */
    fun loadDayPrayerTimes(context: Context, delegationId: Int, year: Int, month: Int, day: Int): DayPrayerTimes? {
        return loadPrayerTimes(context, delegationId, year, month).find { it.day == day }
    }

    private fun parseTime(prayer: Prayer, time: String): PrayerTime {
        val parts = time.split(":")
        return PrayerTime(
            prayer = prayer,
            hour = parts[0].toInt(),
            minute = parts[1].toInt()
        )
    }
}

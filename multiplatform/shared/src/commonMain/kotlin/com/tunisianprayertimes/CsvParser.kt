package com.tunisianprayertimes

/**
 * Parses prayer times CSV content (platform-independent).
 * CSV format: Day,Fajr,Shuruk,Duhr,Asr,Maghrib,Isha
 */
object CsvParser {

    fun parsePrayerTimes(csvContent: String): List<DayPrayerTimes> {
        val results = mutableListOf<DayPrayerTimes>()
        val lines = csvContent.lines().drop(1) // skip header

        for (line in lines) {
            if (line.isBlank()) continue
            val parts = line.split(",")
            if (parts.size >= 7) {
                val day = parts[0].trim().toIntOrNull() ?: continue
                val fajr = parseTime(Prayer.FAJR, parts[1].trim())
                val shurukParts = parts[2].trim().split(":")
                val shurukH = shurukParts[0].toInt()
                val shurukM = shurukParts[1].toInt()
                val dhuhr = parseTime(Prayer.DHUHR, parts[3].trim())
                val asr = parseTime(Prayer.ASR, parts[4].trim())
                val maghrib = parseTime(Prayer.MAGHRIB, parts[5].trim())
                val isha = parseTime(Prayer.ISHA, parts[6].trim())
                results.add(DayPrayerTimes(day, fajr, shurukH, shurukM, dhuhr, asr, maghrib, isha))
            }
        }

        return results
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

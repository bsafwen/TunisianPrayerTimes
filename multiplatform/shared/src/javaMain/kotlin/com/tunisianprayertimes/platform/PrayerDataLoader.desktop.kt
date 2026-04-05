package com.tunisianprayertimes.platform

import com.tunisianprayertimes.*
import java.io.File
import java.util.Locale

/**
 * Desktop implementation: loads CSV from a bundled data directory.
 * The data directory is resolved relative to the JAR/app location or from a configured path.
 */
actual object PrayerDataLoader {
    private var dataDir: File? = null

    /** Call once at startup to set the CSV data directory. */
    fun init(dataDirectory: File) {
        dataDir = dataDirectory
    }

    private fun csvFile(delegationId: Int, year: Int, month: Int): File {
        val dir = dataDir ?: error("PrayerDataLoader not initialized. Call init() first.")
        return File(dir, "csv/$delegationId/$year/${String.format(Locale.US, "%02d", month)}.csv")
    }

    actual fun hasPrayerData(delegationId: Int, year: Int, month: Int): Boolean {
        return csvFile(delegationId, year, month).exists()
    }

    actual fun loadPrayerTimes(delegationId: Int, year: Int, month: Int): List<DayPrayerTimes> {
        val file = csvFile(delegationId, year, month)
        if (!file.exists()) return emptyList()
        return CsvParser.parsePrayerTimes(file.readText())
    }

    actual fun loadDayPrayerTimes(delegationId: Int, year: Int, month: Int, day: Int): DayPrayerTimes? {
        return loadPrayerTimes(delegationId, year, month).find { it.day == day }
    }
}

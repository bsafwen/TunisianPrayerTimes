package com.tunisianprayertimes.platform

import com.tunisianprayertimes.*
import java.io.File
import java.util.Locale

/**
 * Desktop implementation: INM's formula ([InmPrayerTimes], the phone's and the TV's) from the delegation parameters
 * next to the data directory, so every year the formula supports has times; a delegation without parameters, or a
 * data directory without them, reads the bundled CSV tables (2026 only).
 * The data directory is resolved relative to the JAR/app location or from a configured path.
 */
actual object PrayerDataLoader {
    private var dataDir: File? = null
    private var formula: InmPrayerTimes? = null

    /** Call once at startup to set the data directory. */
    fun init(dataDirectory: File) {
        dataDir = dataDirectory
        formula = paramsFile(dataDirectory)?.let { file -> runCatching { InmPrayerTimes.fromParamsJson(file.readText()) }.getOrNull() }
    }

    /** The parameters bundled with the data (the native package), or the repository's beside `docs` (dev mode). */
    private fun paramsFile(dir: File): File? =
        listOf(File(dir, InmPrayerTimes.PARAMS_ASSET), File(dir, "../data/${InmPrayerTimes.PARAMS_ASSET}")).firstOrNull { it.isFile }

    private fun formulaFor(delegationId: Int): InmPrayerTimes? = formula?.takeIf { it.hasDelegation(delegationId) }

    private fun csvFile(delegationId: Int, year: Int, month: Int): File {
        val dir = dataDir ?: error("PrayerDataLoader not initialized. Call init() first.")
        return File(dir, "csv/$delegationId/$year/${String.format(Locale.US, "%02d", month)}.csv")
    }

    actual fun hasPrayerData(delegationId: Int, year: Int, month: Int): Boolean {
        formulaFor(delegationId)?.let { return it.hasPrayerData(delegationId, year, month) }
        return csvFile(delegationId, year, month).exists()
    }

    actual fun loadPrayerTimes(delegationId: Int, year: Int, month: Int): List<DayPrayerTimes> {
        formulaFor(delegationId)?.let { return it.loadPrayerTimes(delegationId, year, month) }
        val file = csvFile(delegationId, year, month)
        if (!file.exists()) return emptyList()
        return CsvParser.parsePrayerTimes(file.readText())
    }

    actual fun loadDayPrayerTimes(delegationId: Int, year: Int, month: Int, day: Int): DayPrayerTimes? {
        formulaFor(delegationId)?.let { return it.loadDayPrayerTimes(delegationId, year, month, day) }
        return loadPrayerTimes(delegationId, year, month).find { it.day == day }
    }
}

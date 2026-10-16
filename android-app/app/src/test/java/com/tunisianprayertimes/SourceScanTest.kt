package com.tunisianprayertimes

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The source scan of plan 6.6 and 9.6. Prayer times reach the phone's code as instants: no `DayPrayerTimes`,
 * `InmPrayerTimes`, `PrayerTime(…)`, `prayerMinutesOfDay` or `Calendar.set` from a prayer time outside the
 * repository and INM's own explainer. And the app shows Western digits only: no Arabic-Indic digit is written.
 */
class SourceScanTest {
    private val main = File("src/main")
    private val sources = File(main, "java").walkTopDown().filter { it.extension == "kt" }.toList()

    /** Where the old types stay: the repository's INM loaders and INM's formula explainer, for Tunisia only (6.9). */
    private val allowList = setOf("PrayerTimesRepository.kt", "PrayerTimeMethodDialog.kt")

    private val forbidden = listOf(
        Regex("""\bDayPrayerTimes\b"""),
        Regex("""\bInmPrayerTimes\b"""),
        Regex("""(?<!\w)PrayerTime\("""),
        Regex("""\bprayerMinutesOfDay\b"""),
        Regex("""set\(Calendar\.(HOUR_OF_DAY|MINUTE),\s*\w*(prayerTime|fajr|dhuhr|asr|maghrib|isha|shuruk|jomoaa)\w*\.""", RegexOption.IGNORE_CASE),
    )

    @Test
    fun prayerTimesReachTheAppAsInstants() {
        assertTrue("no sources under ${main.absolutePath}", sources.size > 50)
        val hits = sources.filter { it.name !in allowList }.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                forbidden.firstOrNull { it.containsMatchIn(line) }?.let { "${file.name}:${index + 1}: ${line.trim()}" }
            }
        }
        assertTrue("Old prayer types outside the repository (6.6):\n" + hits.joinToString("\n"), hits.isEmpty())
    }

    @Test
    fun noArabicIndicDigitIsWritten() {
        val digits = Regex("[٠-٩۰-۹]")
        val resources = File(main, "res").walkTopDown().filter { it.extension == "xml" }.toList()
        assertTrue("no resources under ${main.absolutePath}", resources.isNotEmpty())
        val inResources = resources.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line -> if (digits.containsMatchIn(line)) "${file.name}:${index + 1}" else null }
        }
        // Code may read them, to turn typed digits into Western ones or to strip them; it never writes them.
        val inCode = sources.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                val reads = line.contains("ormaliz") || line.contains("Regex(") || line.trimStart().startsWith("in '")
                if (digits.containsMatchIn(line) && !reads) "${file.name}:${index + 1}" else null
            }
        }
        assertTrue("Arabic-Indic digits:\n" + (inResources + inCode).joinToString("\n"), inResources.isEmpty() && inCode.isEmpty())
    }
}

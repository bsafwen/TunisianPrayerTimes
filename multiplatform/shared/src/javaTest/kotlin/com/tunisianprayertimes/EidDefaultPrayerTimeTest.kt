package com.tunisianprayertimes

import com.tunisianprayertimes.platform.PrayerDataLoader
import java.io.File
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests that the default Eid prayer time uses shuruk from the Eid day,
 * not the currently displayed day.
 */
class EidDefaultPrayerTimeTest {

    private val testDir = File(System.getProperty("java.io.tmpdir"), "eid-prayer-test-${System.currentTimeMillis()}")

    private fun setupCsv(delegationId: Int, year: Int, month: Int, content: String) {
        val dir = File(testDir, "csv/$delegationId/$year")
        dir.mkdirs()
        File(dir, "%02d.csv".format(month)).writeText(content)
    }

    @AfterTest
    fun cleanup() {
        RamadanOverrideChecker.cachedOverride = null
        testDir.deleteRecursively()
    }

    @Test
    fun defaultEidPrayerTime_usesEidDayShuruk() {
        // March data: Eid day (Mar 20) has shuruk at 06:40 → default = 06:40
        // A different day (Mar 18) has shuruk at 06:50 → would be 06:50 if wrongly used
        val marchCsv = """
            Day,Fajr,Shuruk,Duhr,Asr,Maghrib,Isha
            18,05:30,06:50,12:15,15:30,18:10,19:30
            19,05:29,06:45,12:14,15:31,18:11,19:31
            20,05:28,06:40,12:13,15:32,18:12,19:32
            21,05:27,06:38,12:12,15:33,18:13,19:33
        """.trimIndent()
        setupCsv(386, 2026, 3, marchCsv)
        PrayerDataLoader.init(testDir)

        // Eid al-Fitr = 2026-03-20, shuruk = 06:40 → default = 06:40
        val eidDate = LocalDate.of(2026, 3, 20)
        val result = RamadanOverrideChecker.getDefaultEidPrayerTime(386, eidDate)

        assertNotNull(result, "Should return a default time for a valid Eid date")
        assertEquals(6, result.first, "Default Eid prayer hour should be shuruk hour (06:40)")
        assertEquals(40, result.second, "Default Eid prayer minute should be shuruk minute (06:40)")
    }

    @Test
    fun defaultEidPrayerTime_shurukPlusTwentyWithCarry() {
        // Shuruk at 06:55 → default = 06:55
        val csv = """
            Day,Fajr,Shuruk,Duhr,Asr,Maghrib,Isha
            28,05:20,06:55,12:10,15:35,18:15,19:35
        """.trimIndent()
        setupCsv(386, 2026, 5, csv)
        PrayerDataLoader.init(testDir)

        val eidDate = LocalDate.of(2026, 5, 28)
        val result = RamadanOverrideChecker.getDefaultEidPrayerTime(386, eidDate)

        assertNotNull(result)
        assertEquals(6, result.first, "Hour should be shuruk hour: 06:55")
        assertEquals(55, result.second, "Minute should be shuruk minute: 06:55")
    }

    @Test
    fun defaultEidPrayerTime_missingData_returnsNull() {
        // No CSV data set up for this delegation/date
        PrayerDataLoader.init(testDir)

        val result = RamadanOverrideChecker.getDefaultEidPrayerTime(999, LocalDate.of(2026, 3, 20))
        assertNull(result, "Should return null when prayer data is unavailable")
    }
}

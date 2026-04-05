package com.tunisianprayertimes.platform

import com.tunisianprayertimes.*
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrayerDataLoaderTest {

    private val testDir = File(System.getProperty("java.io.tmpdir"), "prayer-test-${System.currentTimeMillis()}")

    private fun setupCsv(delegationId: Int, year: Int, month: Int, content: String) {
        val dir = File(testDir, "csv/$delegationId/$year")
        dir.mkdirs()
        File(dir, "%02d.csv".format(month)).writeText(content)
    }

    @Test
    fun loadsPrayerTimesFromCsvFile() {
        val csv = """
            Day,Fajr,Shuruk,Duhr,Asr,Maghrib,Isha
            1,05:30,06:50,12:15,15:30,18:10,19:30
            2,05:29,06:49,12:14,15:31,18:11,19:31
        """.trimIndent()
        setupCsv(615, 2026, 4, csv)
        PrayerDataLoader.init(testDir)

        assertTrue(PrayerDataLoader.hasPrayerData(615, 2026, 4))
        val times = PrayerDataLoader.loadPrayerTimes(615, 2026, 4)
        assertEquals(2, times.size)
        assertEquals(5, times[0].fajr.hour)
        assertEquals(30, times[0].fajr.minute)

        testDir.deleteRecursively()
    }

    @Test
    fun loadDayPrayerTimesReturnsSpecificDay() {
        val csv = """
            Day,Fajr,Shuruk,Duhr,Asr,Maghrib,Isha
            1,05:30,06:50,12:15,15:30,18:10,19:30
            5,05:25,06:45,12:14,15:32,18:12,19:32
        """.trimIndent()
        setupCsv(615, 2026, 4, csv)
        PrayerDataLoader.init(testDir)

        val day5 = PrayerDataLoader.loadDayPrayerTimes(615, 2026, 4, 5)
        assertNotNull(day5)
        assertEquals(5, day5.day)
        assertEquals(5, day5.fajr.hour)
        assertEquals(25, day5.fajr.minute)

        val day3 = PrayerDataLoader.loadDayPrayerTimes(615, 2026, 4, 3)
        assertNull(day3)

        testDir.deleteRecursively()
    }

    @Test
    fun missingFileReturnsFalseAndEmptyList() {
        val emptyDir = File(System.getProperty("java.io.tmpdir"), "prayer-empty-${System.currentTimeMillis()}")
        emptyDir.mkdirs()
        PrayerDataLoader.init(emptyDir)

        assertFalse(PrayerDataLoader.hasPrayerData(999, 2026, 1))
        assertTrue(PrayerDataLoader.loadPrayerTimes(999, 2026, 1).isEmpty())

        emptyDir.deleteRecursively()
    }
}

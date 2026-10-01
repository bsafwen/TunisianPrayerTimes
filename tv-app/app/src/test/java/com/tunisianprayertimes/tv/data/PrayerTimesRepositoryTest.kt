package com.tunisianprayertimes.tv.data

import com.tunisianprayertimes.DayPrayerTimes
import com.tunisianprayertimes.InmPrayerTimes
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerFormulaSettings
import com.tunisianprayertimes.tv.data.TestData.TUNIS
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrayerTimesRepositoryTest {

    private fun repo() = PrayerTimesRepository(source = { TestData.prayerTimes })

    private fun DayPrayerTimes.minutes(): List<Int> = listOf(
        fajr.hour * 60 + fajr.minute,
        shurukHour * 60 + shurukMinute,
        dhuhr.hour * 60 + dhuhr.minute,
        asr.hour * 60 + asr.minute,
        maghrib.hour * 60 + maghrib.minute,
        isha.hour * 60 + isha.minute,
    )

    @Test
    fun timesContinuePastTheOldCsvCliff() {
        // The bundled CSV tables stopped at 2026-12-31; offline TVs went blank the next day.
        val newYear = repo().loadDay(TUNIS, LocalDate.of(2027, 1, 1))
        assertNotNull(newYear)
        assertEquals(1, newYear!!.day)
    }

    @Test
    fun everyDayOfEverySupportedYearHasOrderedTimes() {
        val source = TestData.prayerTimes
        for (year in InmPrayerTimes.SUPPORTED_YEARS) {
            for (month in 1..12) {
                val days = source.loadPrayerTimes(TUNIS, year, month)
                assertTrue("$year-$month is empty", days.isNotEmpty())
                for (day in days) {
                    val minutes = day.minutes()
                    assertEquals("$year-$month-${day.day} is out of order: $minutes", minutes.sorted(), minutes)
                }
            }
        }
    }

    @Test
    fun matchesTheSharedFormulaTheAppUses() {
        val date = LocalDate.of(2026, 9, 29)
        assertEquals(
            TestData.prayerTimes.loadDayPrayerTimes(TUNIS, 2026, 9, 29),
            repo().loadDay(TUNIS, date),
        )
    }

    private fun DayPrayerTimes.hm(): String = minutes().joinToString(" ") { String.format(java.util.Locale.ROOT, "%02d:%02d", it / 60, it % 60) }

    @Test
    fun theMosqueValuesAreReadAtEachCallAndCanBePassedIn() {
        val date = LocalDate.of(2026, 9, 30)
        val custom = PrayerFormulaSettings(fajrAngle = 16.0, asrShadow = 2, elevation = false).withAdjustment(Prayer.ISHA, 2)
        var stored = PrayerFormulaSettings.OFFICIAL
        val repo = PrayerTimesRepository(source = { TestData.prayerTimes }, settings = { stored })
        // Tunis on 2026-09-30, as meteo.tn publishes it, then with the values the dashboard's formula gives.
        assertEquals("04:47 06:13 12:16 15:31 18:07 19:31", repo.loadDay(TUNIS, date)!!.hm())
        stored = custom
        assertEquals("04:58 06:14 12:16 16:22 18:06 19:32", repo.loadDay(TUNIS, date)!!.hm())
        assertEquals("04:47 06:13 12:16 15:31 18:07 19:31", repo.loadDay(TUNIS, date, PrayerFormulaSettings.OFFICIAL)!!.hm())
        // Without a source of values: INM's.
        assertEquals("04:47 06:13 12:16 15:31 18:07 19:31", repo().loadDay(TUNIS, date)!!.hm())
    }

    @Test
    fun outsideTheSupportedRangeOrUnknownDelegationHasNoTimes() {
        assertNull(repo().loadDay(TUNIS, LocalDate.of(2101, 1, 1)))
        assertNull(repo().loadDay(TUNIS, LocalDate.of(2019, 12, 31)))
        assertNull(repo().loadDay(-1, LocalDate.of(2027, 1, 1)))
    }
}

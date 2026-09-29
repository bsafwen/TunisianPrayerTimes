package com.tunisianprayertimes.tv.data

import com.tunisianprayertimes.DayPrayerTimes
import com.tunisianprayertimes.InmPrayerTimes
import com.tunisianprayertimes.tv.data.TestData.TUNIS
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrayerTimesRepositoryTest {

    private fun repo(today: LocalDate = LocalDate.of(2026, 9, 29)) =
        PrayerTimesRepository(source = { TestData.prayerTimes }, today = { today })

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
        val newYear = repo(today = LocalDate.of(2027, 1, 1)).loadToday(TUNIS)
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
    fun shurukComesFromTheSameDay() {
        val date = LocalDate.of(2027, 3, 9)
        val day = repo(today = date).loadToday(TUNIS)!!
        assertEquals(day.shurukHour to day.shurukMinute, repo(today = date).loadTodayShuruk(TUNIS))
    }

    @Test
    fun matchesTheSharedFormulaTheAppUses() {
        val date = LocalDate.of(2026, 9, 29)
        assertEquals(
            TestData.prayerTimes.loadDayPrayerTimes(TUNIS, 2026, 9, 29),
            repo().loadDay(TUNIS, date),
        )
    }

    @Test
    fun outsideTheSupportedRangeOrUnknownDelegationHasNoTimes() {
        assertNull(repo().loadDay(TUNIS, LocalDate.of(2101, 1, 1)))
        assertNull(repo().loadDay(TUNIS, LocalDate.of(2019, 12, 31)))
        assertNull(repo().loadDay(-1, LocalDate.of(2027, 1, 1)))
    }
}

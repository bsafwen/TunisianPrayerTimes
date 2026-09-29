package com.tunisianprayertimes.tv.data

import com.tunisianprayertimes.DayPrayerTimes
import com.tunisianprayertimes.InmPrayerTimes
import java.time.LocalDate

/**
 * The mosque's daily prayer times, computed offline from INM's formula through the
 * shared [InmPrayerTimes]; the same computation the phone app uses. No tables to expire.
 */
class PrayerTimesRepository(
    private val source: () -> InmPrayerTimes,
    private val today: () -> LocalDate = { LocalDate.now() },
) {

    fun loadDay(delegationId: Int, date: LocalDate): DayPrayerTimes? =
        source().loadDayPrayerTimes(delegationId, date.year, date.monthValue, date.dayOfMonth)

    fun loadToday(delegationId: Int): DayPrayerTimes? = loadDay(delegationId, today())

    /** Today's sunrise as (hour, minute). */
    fun loadTodayShuruk(delegationId: Int): Pair<Int, Int>? =
        loadToday(delegationId)?.let { it.shurukHour to it.shurukMinute }
}

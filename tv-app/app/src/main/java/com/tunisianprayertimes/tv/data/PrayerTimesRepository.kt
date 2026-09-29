package com.tunisianprayertimes.tv.data

import com.tunisianprayertimes.DayPrayerTimes
import com.tunisianprayertimes.InmPrayerTimes
import java.time.LocalDate

/**
 * The mosque's daily prayer times, computed offline from INM's formula through the
 * shared [InmPrayerTimes]; the same computation the phone app uses. No tables to expire.
 * The caller says which day: it comes from the guarded clock, in Tunisia's time, never the device's.
 */
class PrayerTimesRepository(private val source: () -> InmPrayerTimes) {

    fun loadDay(delegationId: Int, date: LocalDate): DayPrayerTimes? =
        source().loadDayPrayerTimes(delegationId, date.year, date.monthValue, date.dayOfMonth)
}

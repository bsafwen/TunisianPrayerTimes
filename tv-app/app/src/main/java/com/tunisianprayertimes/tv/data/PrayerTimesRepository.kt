package com.tunisianprayertimes.tv.data

import com.tunisianprayertimes.DayPrayerTimes
import com.tunisianprayertimes.InmPrayerTimes
import com.tunisianprayertimes.PrayerFormulaSettings
import java.time.LocalDate

/**
 * The mosque's daily prayer times, computed offline from INM's formula through the
 * shared [InmPrayerTimes]; the same computation the phone app uses. No tables to expire.
 * The caller says which day: it comes from the guarded clock, in Tunisia's time, never the device's.
 * The formula's values are the mosque's ([settings], read at each call: INM's official ones unless
 * the admin set others); a caller that keys its state on them passes them in.
 */
class PrayerTimesRepository(
    private val source: () -> InmPrayerTimes,
    private val settings: () -> PrayerFormulaSettings = { PrayerFormulaSettings.OFFICIAL },
) {

    fun loadDay(delegationId: Int, date: LocalDate, settings: PrayerFormulaSettings = this.settings()): DayPrayerTimes? =
        source().loadDayPrayerTimes(delegationId, date.year, date.monthValue, date.dayOfMonth, settings)
}

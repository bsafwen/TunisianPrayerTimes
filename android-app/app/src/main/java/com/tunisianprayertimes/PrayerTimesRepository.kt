package com.tunisianprayertimes

import android.content.Context
import com.tunisianprayertimes.platform.PrayerDataLoader
import java.util.Calendar

/**
 * Prayer times computed on the device with INM's formula ([InmPrayerFormula]), which
 * reproduces the times meteo.tn publishes. Delegation inputs come from the bundled
 * prayer-formula/delegation_params.json (built from data/prayer-formula). The computation
 * is shared with the TV app through [InmPrayerTimes].
 */
object PrayerTimesRepository {

    /** Years offered in the app; meteo.tn publishes (and the formula is validated from) 2020. */
    val SUPPORTED_YEARS = InmPrayerTimes.SUPPORTED_YEARS

    private fun source(context: Context): InmPrayerTimes = PrayerDataLoader.prayerTimes(context)

    /**
     * Returns true when prayer times exist for the month: the delegation is one INM
     * publishes and the year is within [SUPPORTED_YEARS].
     */
    fun hasPrayerData(context: Context, delegationId: Int, year: Int, month: Int): Boolean =
        source(context).hasPrayerData(delegationId, year, month)

    /**
     * Prayer times for every day of the month, or an empty list when the delegation or
     * year has no data.
     */
    fun loadPrayerTimes(context: Context, delegationId: Int, year: Int, month: Int): List<DayPrayerTimes> =
        source(context).loadPrayerTimes(delegationId, year, month)

    /**
     * Load prayer times for a specific day.
     */
    fun loadDayPrayerTimes(context: Context, delegationId: Int, year: Int, month: Int, day: Int): DayPrayerTimes? =
        source(context).loadDayPrayerTimes(delegationId, year, month, day)

    /**
     * Returns the (minMillis, maxMillis) date range with prayer times for the given
     * delegation, or null if it has none.
     */
    fun getDateRange(context: Context, delegationId: Int): Pair<Long, Long>? {
        if (!source(context).hasDelegation(delegationId)) return null
        val minCal = Calendar.getInstance().apply {
            set(SUPPORTED_YEARS.first, Calendar.JANUARY, 1, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val maxCal = Calendar.getInstance().apply {
            set(SUPPORTED_YEARS.last, Calendar.DECEMBER, 31, 23, 59, 59)
            set(Calendar.MILLISECOND, 999)
        }
        return minCal.timeInMillis to maxCal.timeInMillis
    }
}

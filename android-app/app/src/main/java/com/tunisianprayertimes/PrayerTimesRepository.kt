package com.tunisianprayertimes

import android.content.Context
import android.util.Log
import java.util.Calendar
import java.util.GregorianCalendar

/**
 * Prayer times computed on the device with INM's formula ([InmPrayerFormula]), which
 * reproduces the times meteo.tn publishes. Delegation inputs come from the bundled
 * prayer-formula/delegation_params.json (built from data/prayer-formula).
 */
object PrayerTimesRepository {

    private const val TAG = "PrayerTimesRepository"
    private const val PARAMS_ASSET = "prayer-formula/delegation_params.json"

    /** Years offered in the app; meteo.tn publishes (and the formula is validated from) 2020. */
    val SUPPORTED_YEARS = 2020..2100

    @Volatile
    private var locations: Map<Int, InmLocation>? = null

    private fun locations(context: Context): Map<Int, InmLocation> {
        locations?.let { return it }
        return synchronized(this) {
            locations ?: try {
                context.assets.open(PARAMS_ASSET).bufferedReader().use { InmLocation.parseAll(it.readText()) }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load $PARAMS_ASSET: ${e.message}")
                emptyMap()
            }.also { locations = it }
        }
    }

    private fun location(context: Context, delegationId: Int, year: Int): InmLocation? =
        if (year in SUPPORTED_YEARS) locations(context)[delegationId] else null

    /**
     * Returns true when prayer times exist for the month: the delegation is one INM
     * publishes and the year is within [SUPPORTED_YEARS].
     */
    fun hasPrayerData(context: Context, delegationId: Int, year: Int, month: Int): Boolean =
        month in 1..12 && location(context, delegationId, year) != null

    /**
     * Prayer times for every day of the month, or an empty list when the delegation or
     * year has no data.
     */
    fun loadPrayerTimes(context: Context, delegationId: Int, year: Int, month: Int): List<DayPrayerTimes> {
        if (month !in 1..12) return emptyList()
        val location = location(context, delegationId, year) ?: return emptyList()
        val daysInMonth = GregorianCalendar(year, month - 1, 1).getActualMaximum(Calendar.DAY_OF_MONTH)
        return (1..daysInMonth).map { day -> InmPrayerFormula.dayPrayerTimes(location, year, month, day) }
    }

    /**
     * Load prayer times for a specific day.
     */
    fun loadDayPrayerTimes(context: Context, delegationId: Int, year: Int, month: Int, day: Int): DayPrayerTimes? {
        if (month !in 1..12) return null
        val location = location(context, delegationId, year) ?: return null
        val daysInMonth = GregorianCalendar(year, month - 1, 1).getActualMaximum(Calendar.DAY_OF_MONTH)
        if (day !in 1..daysInMonth) return null
        return InmPrayerFormula.dayPrayerTimes(location, year, month, day)
    }

    /**
     * Returns the (minMillis, maxMillis) date range with prayer times for the given
     * delegation, or null if it has none.
     */
    fun getDateRange(context: Context, delegationId: Int): Pair<Long, Long>? {
        if (locations(context)[delegationId] == null) return null
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

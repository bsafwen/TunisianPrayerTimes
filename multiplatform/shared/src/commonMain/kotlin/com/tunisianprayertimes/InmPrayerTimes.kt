package com.tunisianprayertimes

import java.time.YearMonth

/**
 * Prayer times for every delegation INM publishes, computed on the device with [InmPrayerFormula].
 * No network and no bundled tables: any date in [SUPPORTED_YEARS] is available offline.
 */
class InmPrayerTimes(private val locations: Map<Int, InmLocation>) {

    val delegationIds: Set<Int> get() = locations.keys

    fun hasDelegation(delegationId: Int): Boolean = delegationId in locations

    /** The delegation's latitude and longitude (for the weather at the mosque), or null when unknown. */
    fun coordinates(delegationId: Int): Pair<Double, Double>? = locations[delegationId]?.let { it.latitude to it.longitude }

    /**
     * The delegation as the formula sees it (latitude, longitude, elevation), or null when unknown:
     * for the TV's dashboard, which explains and recomputes the times.
     */
    fun location(delegationId: Int): InmLocation? = locations[delegationId]

    /** True when the delegation is one INM publishes and the month is within [SUPPORTED_YEARS]. */
    fun hasPrayerData(delegationId: Int, year: Int, month: Int): Boolean =
        month in 1..12 && supportedLocation(delegationId, year) != null

    /**
     * Prayer times for every day of the month, or an empty list when there is no data; computed
     * with [settings] (INM's official values by default).
     */
    fun loadPrayerTimes(
        delegationId: Int,
        year: Int,
        month: Int,
        settings: PrayerFormulaSettings = PrayerFormulaSettings.OFFICIAL,
    ): List<DayPrayerTimes> {
        if (month !in 1..12) return emptyList()
        val location = supportedLocation(delegationId, year) ?: return emptyList()
        return (1..YearMonth.of(year, month).lengthOfMonth()).map { day ->
            InmPrayerFormula.dayPrayerTimes(location, year, month, day, settings)
        }
    }

    /** One day's prayer times, computed with [settings] (INM's official values by default), or null when there is no data. */
    fun loadDayPrayerTimes(
        delegationId: Int,
        year: Int,
        month: Int,
        day: Int,
        settings: PrayerFormulaSettings = PrayerFormulaSettings.OFFICIAL,
    ): DayPrayerTimes? {
        if (month !in 1..12) return null
        val location = supportedLocation(delegationId, year) ?: return null
        if (day !in 1..YearMonth.of(year, month).lengthOfMonth()) return null
        return InmPrayerFormula.dayPrayerTimes(location, year, month, day, settings)
    }

    private fun supportedLocation(delegationId: Int, year: Int): InmLocation? =
        if (year in SUPPORTED_YEARS) locations[delegationId] else null

    companion object {
        /** meteo.tn publishes (and the formula is validated) from 2020. */
        val SUPPORTED_YEARS = 2020..2100

        /** Asset path both Android apps bundle from data/prayer-formula. */
        const val PARAMS_ASSET = "prayer-formula/delegation_params.json"

        fun fromParamsJson(json: String): InmPrayerTimes = InmPrayerTimes(InmLocation.parseAll(json))
    }
}

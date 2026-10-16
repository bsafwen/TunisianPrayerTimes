package com.tunisianprayertimes.method

import com.tunisianprayertimes.engine.TimeEvent
import java.time.LocalDate
import kotlinx.serialization.Serializable

/**
 * A fit record, written by fit_authority.py (plan 6.3, 9.4): a profile's aggregate over its zones,
 * or one zone's own record, which decides «مطابقة للجدول الرسمي» at the places mapped to it.
 */
@Serializable
data class FitStats(
    val fittedOn: List<FitSample>,
    val heldOut: List<FitSample>,
    /** Held-out share exact per time, quirk days left out. */
    val exact: Map<TimeEvent, Double>,
    /** Held-out share within 1 minute, quirk days left out. */
    val within1: Map<TimeEvent, Double>,
    /** Held-out days per calendar month, 12 counts per time. */
    val daysPerMonth: Map<TimeEvent, List<Int>>,
    /** Held-out Ramadan days, counted before quirk days are left out (E8 methods). */
    val heldOutRamadanDays: Int,
    val quirkDays: Int,
    /** Held-out maximum difference in minutes, quirk days included. */
    val maxDiffMin: Int,
    /** `sa_umm_al_qura` only: the admin1 regions of its held-out points. */
    val regions: Set<String> = emptySet(),
) {
    /**
     * The tier-A gate of 9.5, computed on the phone from these fields: held-out data; every time at
     * least 97 % exact and all within 1 minute; at least 7 held-out days in each calendar month; a
     * whole held-out Ramadan for a method with E8 rules; and, for `sa_umm_al_qura`, held-out points in
     * all 13 of the Kingdom's regions.
     */
    fun passesTierA(method: PrayerMethod): Boolean {
        if (heldOut.isEmpty()) return false
        val everyTime = TimeEvent.entries.all { event ->
            (exact[event] ?: 0.0) >= TIER_A_EXACT &&
                (within1[event] ?: 0.0) >= 1.0 &&
                daysPerMonth[event]?.let { it.size == 12 && it.all { days -> days >= MIN_DAYS_PER_MONTH } } == true
        }
        val ramadan = !(method.hasRamadanRules || method.ramadanDays == RamadanDays.UMM_AL_QURA_COMPUTED) ||
            heldOutRamadanDays >= MIN_RAMADAN_DAYS
        val kingdom = method.id != "sa_umm_al_qura" || regions.size >= SAUDI_REGIONS
        return everyTime && ramadan && kingdom
    }

    companion object {
        const val TIER_A_EXACT = 0.97
        const val MIN_DAYS_PER_MONTH = 7
        const val MIN_RAMADAN_DAYS = 29
        const val SAUDI_REGIONS = 13
    }
}

/** One body of official data a fit used or was scored on (plan 6.3). */
@Serializable
data class FitSample(
    val source: String,
    @Serializable(with = LocalDateIsoSerializer::class) val from: LocalDate,
    @Serializable(with = LocalDateIsoSerializer::class) val to: LocalDate,
    val days: Int,
    val zones: Int,
    val monthSplit: Boolean = false,
)

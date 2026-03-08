package com.tunisianprayertimes

import java.time.chrono.HijrahDate
import java.time.temporal.ChronoField

object RamadanDetector {
    private const val HIJRI_RAMADAN = 9

    /**
     * Returns true if today is during Ramadan (Hijri month 9),
     * with a ±1 day buffer to account for moon-sighting differences.
     */
    fun isRamadan(): Boolean = isRamadan(HijrahDate.now())

    fun isRamadan(date: HijrahDate): Boolean {
        val month = date.get(ChronoField.MONTH_OF_YEAR)
        val day = date.get(ChronoField.DAY_OF_MONTH)
        val daysInMonth = date.lengthOfMonth()

        // Exact Ramadan
        if (month == HIJRI_RAMADAN) return true

        // Last day of Sha'ban (month 8) — buffer for possible early start
        if (month == HIJRI_RAMADAN - 1 && day == daysInMonth) return true

        // First day of Shawwal (month 10) — buffer for possible late end
        if (month == HIJRI_RAMADAN + 1 && day == 1) return true

        return false
    }
}

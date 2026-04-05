package com.tunisianprayertimes

import java.time.chrono.HijrahDate
import java.time.temporal.ChronoField

object RamadanDetector {
    private const val HIJRI_RAMADAN = 9

    fun isRamadan(): Boolean = isRamadan(HijrahDate.now())

    fun isRamadan(date: HijrahDate): Boolean {
        val month = date.get(ChronoField.MONTH_OF_YEAR)
        val day = date.get(ChronoField.DAY_OF_MONTH)
        val daysInMonth = date.lengthOfMonth()

        if (month == HIJRI_RAMADAN) return true
        if (month == HIJRI_RAMADAN - 1 && day == daysInMonth) return true
        if (month == HIJRI_RAMADAN + 1 && day == 1) return true

        return false
    }
}

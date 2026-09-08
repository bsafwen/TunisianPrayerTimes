package com.tunisianprayertimes

import java.time.LocalDate
import java.time.chrono.HijrahDate
import java.time.temporal.ChronoField

object RamadanDetector {
    private const val HIJRI_RAMADAN = 9

    fun isRamadan(): Boolean = isRamadan(HijrahDate.now())

    fun isRamadan(date: HijrahDate): Boolean {
        val gregorian = LocalDate.from(date)
        val calendar = RamadanOverrideChecker.calendar()
        val year = calendar.date(gregorian).year
        val start = calendar.month(year, HIJRI_RAMADAN).start
        val firstShawwal = calendar.month(year, HIJRI_RAMADAN + 1).start
        // These are app-behavior buffers, not extra days assigned to the Ramadan month.
        return !gregorian.isBefore(start.minusDays(1)) && !gregorian.isAfter(firstShawwal)
    }

    /** Original algorithmic detection using HijrahDate (Umm al-Qura calendar). */
    fun isRamadanByHijrahDate(date: HijrahDate): Boolean {
        val month = date.get(ChronoField.MONTH_OF_YEAR)
        val day = date.get(ChronoField.DAY_OF_MONTH)
        val daysInMonth = date.lengthOfMonth()

        if (month == HIJRI_RAMADAN) return true
        if (month == HIJRI_RAMADAN - 1 && day == daysInMonth) return true
        if (month == HIJRI_RAMADAN + 1 && day == 1) return true

        return false
    }
}

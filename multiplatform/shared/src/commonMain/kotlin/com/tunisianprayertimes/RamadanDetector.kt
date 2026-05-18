package com.tunisianprayertimes

import java.time.LocalDate
import java.time.chrono.HijrahDate
import java.time.temporal.ChronoField

object RamadanDetector {
    private const val HIJRI_RAMADAN = 9

    fun isRamadan(): Boolean {
        // Check remote override first
        val override = RamadanOverrideChecker.cachedOverride
        if (override?.ramadanStart != null) {
            val today = LocalDate.now()
            val hijriYear = HijrahDate.from(today).get(ChronoField.YEAR)
            if (override.hijriYear != hijriYear) return isRamadanByHijrahDate(HijrahDate.now())
            val eidDate = override.eidFitrDate
            // Ramadan: from ramadanStart up to (but not including) eidFitrDate.
            // If eidFitrDate is unknown, assume 30 days of Ramadan.
            val endExclusive = eidDate ?: override.ramadanStart.plusDays(30)
            val inRamadan = !today.isBefore(override.ramadanStart) && today.isBefore(endExclusive)
            // Also include last day of Sha'ban (day before Ramadan) and first day of Shawwal
            val lastShaban = override.ramadanStart.minusDays(1)
            val firstShawwal = eidDate
            if (today == lastShaban) return true
            if (firstShawwal != null && today == firstShawwal) return true
            return inRamadan
        }

        // Fallback to algorithmic HijrahDate
        return isRamadanByHijrahDate(HijrahDate.now())
    }

    fun isRamadan(date: HijrahDate): Boolean {
        // If a remote override is cached and maps to the same Hijri year, use it
        val override = RamadanOverrideChecker.cachedOverride
        if (override != null && override.ramadanStart != null
            && override.hijriYear == date.get(ChronoField.YEAR)) {
            val gregorian = LocalDate.from(date)
            val endExclusive = override.eidFitrDate ?: override.ramadanStart.plusDays(30)
            val inRamadan = !gregorian.isBefore(override.ramadanStart) && gregorian.isBefore(endExclusive)
            val lastShaban = override.ramadanStart.minusDays(1)
            val firstShawwal = override.eidFitrDate
            if (gregorian == lastShaban) return true
            if (firstShawwal != null && gregorian == firstShawwal) return true
            return inRamadan
        }

        return isRamadanByHijrahDate(date)
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

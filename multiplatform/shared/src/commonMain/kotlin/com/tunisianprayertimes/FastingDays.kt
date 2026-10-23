package com.tunisianprayertimes

import java.time.LocalDate

/** Why a sunnah-fast day gets no reminder. */
enum class FastingBlock {
    /** The day is in Ramadan: its fast is owed, not a sunnah. */
    RAMADAN,
    /** 1 Shawwal: fasting it is forbidden. */
    EID_FITR,
    /** 10 Dhul Hijja: fasting it is forbidden. */
    EID_ADHA,
    /** 11 to 13 Dhul Hijja: the Maliki school fasts the 13th only for a vow (Mukhtasar Khalil). */
    TASHRIQ,
}

/**
 * The sunnah fasts tied to a Hijri date, as the majority of scholars hold them: Arafa for whoever is not on
 * Hajj (Muslim 1162), Tasu'a and Ashura (Muslim 1134, 1162), the white days (Tirmidhi 761) and six days of
 * Shawwal (Muslim 1164).
 */
enum class FastingOccasion {
    /** 9 Dhul Hijja. */
    ARAFA,
    /** 9 Muharram. */
    TASUA,
    /** 10 Muharram. */
    ASHURA,
    /** 13 to 15 of every month; in Ramadan and on the 13th of Dhul Hijja the day's own rule wins. */
    WHITE_DAYS,
    /** 2 to 7 Shawwal: six days straight after the Eid, the way most who fast them do. */
    SHAWWAL,
}

/**
 * Whether a day may get the sunnah-fast reminder. [block] is the forbidden day itself or, with [margin], the
 * forbidden day next to it that may still move onto it: its month is not announced yet, or the place is
 * abroad ([abroad]), where Eid may fall a day apart from Tunisia's.
 */
data class FastingVerdict(val block: FastingBlock?, val margin: Boolean = false, val abroad: Boolean = false) {
    val allowed: Boolean get() = block == null
}

/**
 * Monday and Thursday fasting on the Tunisian Hijri calendar, shared so the phone and the TV agree. A day is
 * silenced rather than guessed: a missed sunnah owes nothing, a fast on Eid is forbidden, and a voluntary
 * intention does not count for a day that turns out to be Ramadan.
 */
object FastingDays {

    fun verdict(day: LocalDate, abroad: Boolean): FastingVerdict = verdict(day, abroad, OfficialIslamicDates.calendar())

    fun verdict(day: LocalDate, abroad: Boolean, calendar: TunisianHijriCalendar): FastingVerdict {
        forbidden(day, calendar)?.let { return FastingVerdict(it) }
        for (near in listOf(day.minusDays(1), day.plusDays(1))) {
            val block = forbidden(near, calendar) ?: continue
            // Abroad, the local Eid may differ from Tunisia's whatever Tunisia announces; at home, a month not
            // announced yet may start a day off.
            if (abroad) return FastingVerdict(block, margin = true, abroad = true)
            if (calendar.date(near).isEstimated) return FastingVerdict(block, margin = true)
        }
        return FastingVerdict(null)
    }

    fun occasion(day: LocalDate, abroad: Boolean): FastingOccasion? = occasion(day, abroad, OfficialIslamicDates.calendar())

    /** The Hijri-dated sunnah fasts in the [days] days from [from], read from one calendar. */
    fun occasions(from: LocalDate, days: Int, abroad: Boolean): Map<LocalDate, FastingOccasion> {
        val calendar = OfficialIslamicDates.calendar()
        return (0L until days).mapNotNull { offset ->
            val day = from.plusDays(offset)
            occasion(day, abroad, calendar)?.let { day to it }
        }.toMap()
    }

    /**
     * The Hijri-dated sunnah fast [day] is, if any. Arafa next to an Eid not yet announced is held by [verdict].
     * When 2 Shawwal is held (abroad, or the Eid not announced), the 8th takes its place, so the six stay six.
     */
    fun occasion(day: LocalDate, abroad: Boolean, calendar: TunisianHijriCalendar): FastingOccasion? {
        val hijri = calendar.date(day)
        return when {
            hijri.month == 12 && hijri.day == 9 -> FastingOccasion.ARAFA
            hijri.month == 1 && hijri.day == 9 -> FastingOccasion.TASUA
            hijri.month == 1 && hijri.day == 10 -> FastingOccasion.ASHURA
            hijri.month == 10 && hijri.day in 2..7 -> FastingOccasion.SHAWWAL
            hijri.month == 10 && hijri.day == 8 && !verdict(day.minusDays(6), abroad, calendar).allowed -> FastingOccasion.SHAWWAL
            hijri.day in 13..15 -> FastingOccasion.WHITE_DAYS
            else -> null
        }
    }

    fun mayBeEid(day: LocalDate, abroad: Boolean): Boolean = mayBeEid(day, abroad, OfficialIslamicDates.calendar())

    /** [day] is one of the two Eids, or may be: abroad, or before the announcement, the day next to one may be it. */
    fun mayBeEid(day: LocalDate, abroad: Boolean, calendar: TunisianHijriCalendar): Boolean {
        fun eid(d: LocalDate) = forbidden(d, calendar).let { it == FastingBlock.EID_FITR || it == FastingBlock.EID_ADHA }
        if (eid(day)) return true
        return listOf(day.minusDays(1), day.plusDays(1)).any { near -> eid(near) && (abroad || calendar.date(near).isEstimated) }
    }

    /** The day of the Hijri month, which names a white day or a day of Shawwal. */
    fun hijriDay(day: LocalDate): Int = OfficialIslamicDates.calendar().date(day).day

    private fun forbidden(day: LocalDate, calendar: TunisianHijriCalendar): FastingBlock? {
        val hijri = calendar.date(day)
        return when {
            hijri.month == 9 -> FastingBlock.RAMADAN
            hijri.month == 10 && hijri.day == 1 -> FastingBlock.EID_FITR
            hijri.month == 12 && hijri.day == 10 -> FastingBlock.EID_ADHA
            hijri.month == 12 && hijri.day in 11..13 -> FastingBlock.TASHRIQ
            else -> null
        }
    }
}

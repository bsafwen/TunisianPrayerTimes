package com.tunisianprayertimes

import java.time.LocalDate

/** A civil day in the Tunisian Islamic calendar, as mosque screens need it (no visibility buffers). */
data class IslamicDay(
    val date: LocalDate,
    val hijri: HijriCalendarDate,
    /** 1 to 30 during Ramadan (31 only between an admin's date and an announcement that leave no other way), null otherwise. */
    val ramadanDay: Int?,
    val isArafah: Boolean,
    val isEidFitr: Boolean,
    val isEidAdha: Boolean,
) {
    val isRamadan: Boolean get() = ramadanDay != null

    /** From the 21st of Ramadan: the last ten days and their nights. */
    val isLastTenDays: Boolean get() = (ramadanDay ?: 0) >= 21

    val isEid: Boolean get() = isEidFitr || isEidAdha
}

/** Where a Ramadan or Eid date comes from. */
enum class DateSource { MANUAL, OFFICIAL, ESTIMATE }

/**
 * One event of a Hijri year as the calendar resolves it. [withoutManual] is the announced or
 * estimated date the admin's date replaces ([announced] when it is an announcement), so the admin
 * can see a disagreement.
 */
data class EventDate(val date: LocalDate, val source: DateSource, val withoutManual: LocalDate, val announced: Boolean) {
    /** An admin date that differs from an actual announcement (not from a mere estimate). */
    val conflictsWithAnnouncement: Boolean get() = source == DateSource.MANUAL && announced && date != withoutManual
}

data class YearDates(val hijriYear: Int, val ramadanStart: EventDate, val eidFitr: EventDate, val eidAdha: EventDate)

/** Ramadan and Eid days from the shared calendar: the admin's dates, then announcements, then estimates. */
object IslamicDays {

    fun of(date: LocalDate): IslamicDay = of(date, OfficialIslamicDates.calendar())

    fun of(date: LocalDate, calendar: TunisianHijriCalendar): IslamicDay {
        val hijri = calendar.date(date)
        return IslamicDay(
            date = date,
            hijri = hijri,
            ramadanDay = hijri.day.takeIf { hijri.month == 9 },
            isArafah = hijri.month == 12 && hijri.day == 9,
            isEidFitr = hijri.month == 10 && hijri.day == 1,
            isEidAdha = hijri.month == 12 && hijri.day == 10,
        )
    }

    /** The Hijri year that [date] falls in. */
    fun hijriYearOf(date: LocalDate): Int = OfficialIslamicDates.calendar().date(date).year

    /** How far the admin may move a Ramadan or Eid date from its announcement or estimate on the TV. */
    const val MAX_MANUAL_SHIFT_DAYS = 3L

    /**
     * The year whose Ramadan and Eids an admin prepares on [today]: this year until the last day its
     * Eid al-Adha could still be moved to, then the next. From announcements and estimates only, so
     * the admin's own changes never turn the page to another year under the remote (a wrong press of
     * − on Eid morning stays undoable), and a late Eid is still settable on its day.
     */
    fun upcomingYear(today: LocalDate): Int = upcomingYear(today, OfficialIslamicDates.officialCalendar())

    internal fun upcomingYear(today: LocalDate, calendar: TunisianHijriCalendar): Int {
        val year = calendar.date(today).year
        val eidAdha = calendar.month(year, 12).start.plusDays(9)
        return if (today.isAfter(eidAdha.plusDays(MAX_MANUAL_SHIFT_DAYS + 1))) year + 1 else year
    }

    fun yearDates(hijriYear: Int): YearDates = yearDates(
        hijriYear,
        merged = OfficialIslamicDates.calendar(),
        official = OfficialIslamicDates.officialCalendar(),
        manual = ManualIslamicDateOverrides.forYear(hijriYear),
    )

    /**
     * The year's dates as they would be with [manual] as the admin's dates for it, before saving them:
     * where a date returned to automatic goes, the admin's other dates kept.
     */
    fun yearDates(hijriYear: Int, manual: ManualIslamicDates): YearDates = yearDates(
        hijriYear,
        merged = OfficialIslamicDates.calendarWith(hijriYear, manual),
        official = OfficialIslamicDates.officialCalendar(),
        manual = manual,
    )

    internal fun yearDates(
        hijriYear: Int,
        merged: TunisianHijriCalendar,
        official: TunisianHijriCalendar,
        manual: ManualIslamicDates,
    ): YearDates {
        fun event(month: Int, offsetDays: Long, manualDate: LocalDate?): EventDate {
            val officialMonth = official.month(hijriYear, month)
            val mergedMonth = merged.month(hijriYear, month)
            val date = mergedMonth.start.plusDays(offsetDays)
            // What the calendar used: an admin's date it could not keep is not the one shown.
            val source = when {
                manualDate != null && manualDate == date -> DateSource.MANUAL
                !mergedMonth.isEstimated -> DateSource.OFFICIAL
                else -> DateSource.ESTIMATE
            }
            return EventDate(
                date = date,
                source = source,
                withoutManual = officialMonth.start.plusDays(offsetDays),
                announced = !officialMonth.isEstimated,
            )
        }
        return YearDates(
            hijriYear = hijriYear,
            ramadanStart = event(9, 0, manual.ramadanStart),
            eidFitr = event(10, 0, manual.eidFitr),
            eidAdha = event(12, 9, manual.eidAdha),
        )
    }
}

/** Arabic Hijri labels in Tunisian usage, shared by the phone and TV apps. */
object HijriLabels {
    /**
     * The months as Tunisia names them («ربيع الثاني» and «جمادى الثانية», where the platform's
     * CLDR data says «الآخر» and «الآخرة»), written here so every box reads the same whatever its ICU.
     */
    private val MONTHS = listOf(
        "محرم", "صفر", "ربيع الأول", "ربيع الثاني", "جمادى الأولى", "جمادى الثانية",
        "رجب", "شعبان", "رمضان", "شوال", "ذو القعدة", "ذو الحجة",
    )

    /** After a day's number the month is genitive: «9 ذي الحجة». */
    private val GENITIVE = mapOf(11 to "ذي القعدة", 12 to "ذي الحجة")

    fun monthName(year: Int, month: Int): String {
        require(month in 1..12) { "Unsupported Hijri month: $year/$month" }
        return MONTHS[month - 1]
    }

    fun monthLabel(year: Int, month: Int): String = "${monthName(year, month)} $year هـ"

    fun dateLabel(date: HijriCalendarDate): String =
        "${date.day} ${GENITIVE[date.month] ?: monthName(date.year, date.month)} ${date.year} هـ"
}

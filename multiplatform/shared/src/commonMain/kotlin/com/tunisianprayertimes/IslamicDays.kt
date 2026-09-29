package com.tunisianprayertimes

import java.time.LocalDate
import java.time.chrono.HijrahChronology
import java.time.chrono.HijrahDate
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.util.Locale

/** A civil day in the Tunisian Islamic calendar, as mosque screens need it (no visibility buffers). */
data class IslamicDay(
    val date: LocalDate,
    val hijri: HijriCalendarDate,
    /** 1 to 30 during Ramadan, null otherwise. */
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

    /** The year whose Ramadan and Eids an admin prepares on [today]: this year until its Eid al-Adha, then the next. */
    fun upcomingYear(today: LocalDate): Int = upcomingYear(today, OfficialIslamicDates.calendar())

    internal fun upcomingYear(today: LocalDate, calendar: TunisianHijriCalendar): Int {
        val year = calendar.date(today).year
        val eidAdha = calendar.month(year, 12).start.plusDays(9)
        return if (today.isAfter(eidAdha)) year + 1 else year
    }

    fun yearDates(hijriYear: Int): YearDates = yearDates(
        hijriYear,
        merged = OfficialIslamicDates.calendar(),
        official = OfficialIslamicDates.officialCalendar(),
        manual = ManualIslamicDateOverrides.forYear(hijriYear),
    )

    internal fun yearDates(
        hijriYear: Int,
        merged: TunisianHijriCalendar,
        official: TunisianHijriCalendar,
        manual: ManualIslamicDates,
    ): YearDates {
        fun event(month: Int, offsetDays: Long, manualDate: LocalDate?): EventDate {
            val officialMonth = official.month(hijriYear, month)
            val source = when {
                manualDate != null -> DateSource.MANUAL
                !officialMonth.isEstimated -> DateSource.OFFICIAL
                else -> DateSource.ESTIMATE
            }
            return EventDate(
                date = merged.month(hijriYear, month).start.plusDays(offsetDays),
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
    private val locale: Locale = Locale.forLanguageTag("ar-TN-u-nu-latn")

    private val monthFormatter = DateTimeFormatter.ofPattern("MMMM", locale)
        .withChronology(HijrahChronology.INSTANCE)
        .withDecimalStyle(DecimalStyle.STANDARD)

    // CLDR localizes Hijri month 4 as "ربيع الآخر" (Rabiʿ al-Akhir). Tunisian usage prefers
    // "ربيع الثاني" (Rabiʿ al-Thani), and the platform exposes no way to request the
    // alternate name, so we override the months where the two differ.
    private val monthNameOverrides = mapOf(4 to "ربيع الثاني")

    // Only use the built-in chronology to localize a month name. An official Tunisian
    // month can contain day 30 even when that same Umm al-Qura month has only 29 days.
    fun monthName(year: Int, month: Int): String =
        monthNameOverrides[month] ?: monthFormatter.format(HijrahDate.of(year, month, 1))

    fun monthLabel(year: Int, month: Int): String = "${monthName(year, month)} $year هـ"

    fun dateLabel(date: HijriCalendarDate): String = "${date.day} ${monthLabel(date.year, date.month)}"
}

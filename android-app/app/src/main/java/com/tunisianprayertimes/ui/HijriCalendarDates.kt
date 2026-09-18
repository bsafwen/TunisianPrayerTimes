package com.tunisianprayertimes.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tunisianprayertimes.HijriCalendarDate
import com.tunisianprayertimes.OfficialIslamicDates
import com.tunisianprayertimes.TunisianHijriCalendar
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.chrono.HijrahChronology
import java.time.chrono.HijrahDate
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.time.temporal.ChronoField
import java.util.Locale

internal val calendarLocale: Locale = Locale.forLanguageTag("ar-TN-u-nu-latn")

private val hijriMonthFormatter = DateTimeFormatter.ofPattern("MMMM", calendarLocale)
    .withChronology(HijrahChronology.INSTANCE)
    .withDecimalStyle(DecimalStyle.STANDARD)
private val gregorianDateFormatter = DateTimeFormatter.ofPattern("EEEE، d MMMM yyyy 'م'", calendarLocale)
    .withDecimalStyle(DecimalStyle.STANDARD)

// Prayer data and day navigation use the device's local civil date, not a UTC date.
internal fun calendarLocalDate(timeMillis: Long): LocalDate =
    Instant.ofEpochMilli(timeMillis).atZone(ZoneId.systemDefault()).toLocalDate()

internal fun calendarDateMillis(date: LocalDate): Long =
    date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

// CLDR localizes Hijri month 4 as "ربيع الآخر" (Rabiʿ al-Akhir). Tunisian usage prefers
// "ربيع الثاني" (Rabiʿ al-Thani), and the platform exposes no way to request the
// alternate name, so we override the months where the two differ.
private val hijriMonthNameOverrides = mapOf(
    4 to "ربيع الثاني",
)

// Only use the built-in chronology to localize a month name. An official Tunisian
// month can contain day 30 even when that same Umm al-Qura month has only 29 days.
internal fun hijriMonthLabel(year: Int, month: Int): String {
    val name = hijriMonthNameOverrides[month]
        ?: hijriMonthFormatter.format(HijrahDate.of(year, month, 1))
    return "$name $year هـ"
}

internal fun hijriDateLabel(date: HijriCalendarDate): String =
    "${date.day} ${hijriMonthLabel(date.year, date.month)}"

internal fun gregorianDateLabel(date: LocalDate): String = gregorianDateFormatter.format(date)

internal fun algorithmicHijriYear(date: LocalDate): Int = HijrahDate.from(date).get(ChronoField.YEAR)

@Composable
internal fun rememberTunisianHijriCalendar(): TunisianHijriCalendar {
    val overrides by OfficialIslamicDates.updates.collectAsStateWithLifecycle()
    return remember(overrides) { TunisianHijriCalendar(overrides) }
}

package com.tunisianprayertimes.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tunisianprayertimes.HijriCalendarDate
import com.tunisianprayertimes.HijriLabels
import com.tunisianprayertimes.OfficialIslamicDates
import com.tunisianprayertimes.TunisianHijriCalendar
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.chrono.HijrahDate
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.time.temporal.ChronoField
import java.util.Locale

internal val calendarLocale: Locale = Locale.forLanguageTag("ar-TN-u-nu-latn")

private val gregorianDateFormatter = DateTimeFormatter.ofPattern("EEEE، d MMMM yyyy 'م'", calendarLocale)
    .withDecimalStyle(DecimalStyle.STANDARD)

// Prayer data and day navigation use the device's local civil date, not a UTC date.
internal fun calendarLocalDate(timeMillis: Long): LocalDate =
    Instant.ofEpochMilli(timeMillis).atZone(ZoneId.systemDefault()).toLocalDate()

internal fun calendarDateMillis(date: LocalDate): Long =
    date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

// Tunisian month names ("ربيع الثاني", not CLDR's "ربيع الآخر"), shared with the TV app.
internal fun hijriMonthLabel(year: Int, month: Int): String = HijriLabels.monthLabel(year, month)

internal fun hijriDateLabel(date: HijriCalendarDate): String = HijriLabels.dateLabel(date)

internal fun gregorianDateLabel(date: LocalDate): String = gregorianDateFormatter.format(date)

internal fun algorithmicHijriYear(date: LocalDate): Int = HijrahDate.from(date).get(ChronoField.YEAR)

@Composable
internal fun rememberTunisianHijriCalendar(): TunisianHijriCalendar {
    val overrides by OfficialIslamicDates.updates.collectAsStateWithLifecycle()
    return remember(overrides) { TunisianHijriCalendar(overrides) }
}

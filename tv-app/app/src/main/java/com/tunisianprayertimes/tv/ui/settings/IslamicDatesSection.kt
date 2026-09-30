package com.tunisianprayertimes.tv.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.DateSource
import com.tunisianprayertimes.EventDate
import com.tunisianprayertimes.IslamicDays
import com.tunisianprayertimes.ManualIslamicDateOverrides
import com.tunisianprayertimes.ManualIslamicDates
import com.tunisianprayertimes.OfficialIslamicDates
import com.tunisianprayertimes.YearDates
import com.tunisianprayertimes.mosque.MosqueSettingsFile
import com.tunisianprayertimes.mosque.MosqueSettingsFile.DateEvent
import com.tunisianprayertimes.tv.data.WeatherRepository
import com.tunisianprayertimes.tv.ui.TvStrings
import com.tunisianprayertimes.tv.ui.common.AdminPage
import com.tunisianprayertimes.tv.ui.common.FocusableSurface
import com.tunisianprayertimes.tv.ui.common.Stepper
import com.tunisianprayertimes.tv.ui.common.initialFocus
import com.tunisianprayertimes.tv.ui.common.onSurfaceText
import com.tunisianprayertimes.tv.ui.kiosk.HealthLevel
import com.tunisianprayertimes.tv.ui.kiosk.HealthRow
import com.tunisianprayertimes.tv.ui.theme.Midad
import com.tunisianprayertimes.tv.ui.theme.midadStyle
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** The coming Hijri year's dates as the calendar resolves them now, and what the admin set by hand. */
internal class UpcomingDates(val year: Int, val dates: YearDates, val manual: ManualIslamicDates)

/**
 * Where [event] of Hijri [year] goes once its manual date is cleared: the admin's other dates stay,
 * and a manual Ramadan start may have moved the Eid al-Fitr with it. The TV's page and the phone's
 * «تلقائي» both say this date.
 */
internal fun automaticDate(year: Int, manual: ManualIslamicDates, event: DateEvent): LocalDate {
    val cleared = when (event) {
        DateEvent.RAMADAN_START -> manual.copy(ramadanStart = null)
        DateEvent.EID_FITR -> manual.copy(eidFitr = null)
        DateEvent.EID_ADHA -> manual.copy(eidAdha = null)
    }
    return IslamicDays.yearDates(year, cleared).let {
        when (event) {
            DateEvent.RAMADAN_START -> it.ramadanStart
            DateEvent.EID_FITR -> it.eidFitr
            DateEvent.EID_ADHA -> it.eidAdha
        }.date
    }
}

/**
 * The coming year's dates, following the admin's changes and any announcement that arrives. Which
 * year follows the announcements only: the admin's own change never turns the page to another year.
 */
@Composable
internal fun rememberUpcomingDates(today: LocalDate): UpcomingDates {
    val manualAll by ManualIslamicDateOverrides.updates.collectAsState()
    val official by OfficialIslamicDates.updates.collectAsState()
    val year = remember(today, official) { IslamicDays.upcomingYear(today) }
    val dates = remember(year, manualAll, official) { IslamicDays.yearDates(year) }
    return UpcomingDates(year, dates, manualAll[year] ?: ManualIslamicDates())
}

/**
 * A Ramadan or Eid date that is still the estimate a few days before it: half the mosques are offline
 * and never receive the announcement, so the wall and the kiosk page ask the admin to confirm it.
 */
internal object EstimatedDates {

    /** How many days ahead an estimated date is brought to the admin. */
    const val NOTICE_DAYS = 3L

    /** Whether [event] is an estimate, from [NOTICE_DAYS] days before it to its day. */
    fun isAhead(today: LocalDate, event: EventDate): Boolean =
        event.source == DateSource.ESTIMATE && !event.date.isBefore(today) && !event.date.isAfter(today.plusDays(NOTICE_DAYS))

    /** The first of the year's events that [isAhead], with its name. */
    fun ahead(today: LocalDate, dates: YearDates): Pair<String, EventDate>? =
        listOf(TvStrings.RAMADAN_START to dates.ramadanStart, TvStrings.EID_FITR to dates.eidFitr, TvStrings.EID_ADHA to dates.eidAdha)
            .firstOrNull { (_, event) -> isAhead(today, event) }

    /** What the TV asks the admin to confirm on [today]: nothing [online], where the announcement is on its way. */
    fun pending(today: LocalDate, online: Boolean): Pair<String, EventDate>? =
        if (online) null else ahead(today, IslamicDays.yearDates(IslamicDays.upcomingYear(today)))

    /** The kiosk page's row for [ahead]. */
    fun rows(ahead: Pair<String, EventDate>?): List<HealthRow> = listOfNotNull(
        ahead?.let { (name, event) -> HealthRow(HealthLevel.WARNING, TvStrings.estimatedDateRow(name, event.date), fix = TvStrings.ESTIMATED_DATE_FIX) },
    )
}

/** A date one press of − or + saves for an event; [date] null returns it to automatic. */
internal data class DateStep(val date: LocalDate?)

/**
 * One press of − or + ([days]) on [event]: a day on from the admin's own [manual] date (one the
 * calendar could not keep still moves with each press), within a few days of the announcement or
 * estimate, else null; a date set further off from the phone or a key still steps back toward them.
 * Back onto [automatic], where the event goes once its manual date is cleared (the admin's other
 * dates kept), is automatic again, so a later announcement still applies.
 */
internal fun stepDate(event: EventDate, manual: LocalDate?, days: Long, automatic: () -> LocalDate): DateStep? {
    val from = manual ?: event.date
    val next = from.plusDays(days)
    fun away(date: LocalDate) = abs(ChronoUnit.DAYS.between(event.withoutManual, date))
    if (away(next) > IslamicDays.MAX_MANUAL_SHIFT_DAYS && away(next) >= away(from)) return null
    return DateStep(next.takeUnless { manual != null && it == automatic() })
}

/** Where a date comes from, in a word. */
internal fun sourceLabel(source: DateSource): String = when (source) {
    DateSource.MANUAL -> TvStrings.SOURCE_MANUAL
    DateSource.OFFICIAL -> TvStrings.SOURCE_OFFICIAL
    DateSource.ESTIMATE -> TvStrings.SOURCE_ESTIMATE
}

/**
 * Ramadan and Eid dates for the coming year, each from the admin, an announcement or the estimate.
 * The admin can move a date by a day (a mosque that follows its own sighting, or an offline TV that
 * never received the announcement), confirm an estimate a few days before it on an offline TV, or
 * return a date to automatic. A date that would leave a month other than 29 or 30 days with the other
 * dates is not saved, and the row says why. Changes are saved at once; Back (handled by the settings)
 * returns to the menu.
 */
@Composable
fun IslamicDatesSection(today: LocalDate) {
    val upcoming = rememberUpcomingDates(today)
    val manual = upcoming.manual
    // Online, the announcement is on its way: a confirmed estimate would stand in its place.
    val context = LocalContext.current
    val offline = remember { !WeatherRepository.isOnline(context) }
    fun confirmable(event: EventDate) = offline && EstimatedDates.isAhead(today, event)
    // The admin's dates with [event] on [date] (null: automatic).
    fun proposed(event: DateEvent, date: LocalDate?) = when (event) {
        DateEvent.RAMADAN_START -> manual.copy(ramadanStart = date)
        DateEvent.EID_FITR -> manual.copy(eidFitr = date)
        DateEvent.EID_ADHA -> manual.copy(eidAdha = date)
    }
    // Saves the admin's date for [event] (null: automatic), or says why it cannot go with the others.
    fun change(event: DateEvent, date: LocalDate?): String? {
        val proposed = proposed(event, date)
        MosqueSettingsFile.datesConflict(upcoming.dates, proposed, setOf(event))?.let { return it }
        ManualIslamicDateOverrides.set(upcoming.year, proposed)
        return null
    }
    // Where [event] goes once its manual date is cleared: the other dates of the admin may have moved it.
    fun automatic(event: DateEvent): LocalDate = automaticDate(upcoming.year, manual, event)

    AdminPage(
        title = "${TvStrings.ISLAMIC_DATES_TITLE} ${TvStrings.hijriYear(upcoming.year)}",
        hints = listOf(TvStrings.HINT_SAVED_AT_ONCE, TvStrings.HINT_BACK_TO_SETTINGS),
    ) {
        Text(TvStrings.ISLAMIC_DATES_HINT, style = midadStyle(14.sp, color = Midad.Muted))
        Column(Modifier.widthIn(max = 720.dp).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val dates = upcoming.dates
            EventRow(TvStrings.RAMADAN_START, dates.ramadanStart, manual.ramadanStart, confirmable(dates.ramadanStart),
                automatic = { automatic(DateEvent.RAMADAN_START) }, focusFirst = true) { change(DateEvent.RAMADAN_START, it) }
            EventRow(TvStrings.EID_FITR, dates.eidFitr, manual.eidFitr, confirmable(dates.eidFitr),
                automatic = { automatic(DateEvent.EID_FITR) }) { change(DateEvent.EID_FITR, it) }
            EventRow(TvStrings.EID_ADHA, dates.eidAdha, manual.eidAdha, confirmable(dates.eidAdha),
                automatic = { automatic(DateEvent.EID_ADHA) }) { change(DateEvent.EID_ADHA, it) }
        }
    }
}

/**
 * One event: its name, − date +, where it comes from, and «تلقائي» once the admin set it («تأكيد» for
 * an estimate that is [confirmable]). [manual] is the admin's stored date, [automatic] where the date
 * goes without it, [onChange] saves a date (null: automatic) or says why it cannot be used. The name is
 * a stop that does nothing: the page opens on it and «تلقائي» or «تأكيد» give the focus back to it, so a
 * double press of OK never moves a date.
 */
@Composable
private fun EventRow(
    label: String,
    event: EventDate,
    manual: LocalDate?,
    confirmable: Boolean,
    automatic: () -> LocalDate,
    focusFirst: Boolean = false,
    onChange: (LocalDate?) -> String?,
) {
    val name = remember { FocusRequester() }
    // Why the last change could not go with the other dates; the next one that can clears it.
    var problem by remember { mutableStateOf<String?>(null) }
    fun step(days: Long) {
        stepDate(event, manual, days, automatic)?.let { problem = onChange(it.date) }
    }
    val action: Pair<String, () -> String?>? = when {
        manual != null -> TvStrings.AUTOMATIC to { onChange(null) }
        confirmable -> TvStrings.CONFIRM to { onChange(event.date) }
        else -> null
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(Midad.SurfaceRaised, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(Modifier.heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FocusableSurface(
                onClick = {},
                modifier = Modifier.width(120.dp).focusRequester(name).initialFocus(focusFirst),
                rest = Midad.SurfaceRaised,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
            ) { focused ->
                Text(label, style = midadStyle(17.sp, FontWeight.Medium, onSurfaceText(focused)), maxLines = 1)
            }
            Stepper(
                value = TvStrings.gregorianDate(event.date),
                onMinus = { step(-1) },
                onPlus = { step(1) },
                valueWidth = 210.dp,
            )
            Text(sourceLabel(event.source), style = midadStyle(14.sp, color = Midad.Muted), modifier = Modifier.width(56.dp))
            // Always the same room, so the three rows keep their columns whether a date is set by hand or not.
            Box(Modifier.width(92.dp)) {
                if (action != null) {
                    FocusableSurface(
                        onClick = {
                            problem = action.second()
                            runCatching { name.requestFocus() }
                        },
                        rest = Midad.Surface,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxWidth(),
                    ) { focused ->
                        Text(action.first, style = midadStyle(15.sp, color = onSurfaceText(focused)), textAlign = TextAlign.Center)
                    }
                }
            }
        }
        if (event.conflictsWithAnnouncement) {
            Text(
                "${TvStrings.ANNOUNCED_DATE}: ${TvStrings.gregorianDate(event.withoutManual)}",
                style = midadStyle(14.sp, color = Midad.Alert),
            )
        }
        problem?.let { Text(it, style = midadStyle(14.sp, color = Midad.Alert)) }
    }
}

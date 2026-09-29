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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import com.tunisianprayertimes.tv.ui.TvStrings
import com.tunisianprayertimes.tv.ui.common.AdminPage
import com.tunisianprayertimes.tv.ui.common.FocusableSurface
import com.tunisianprayertimes.tv.ui.common.Stepper
import com.tunisianprayertimes.tv.ui.common.initialFocus
import com.tunisianprayertimes.tv.ui.common.onSurfaceText
import com.tunisianprayertimes.tv.ui.theme.Midad
import com.tunisianprayertimes.tv.ui.theme.midadStyle
import java.time.LocalDate

/** How far a hand-set date may move from the announcement or estimate; the calendar rejects wilder anchors. */
private const val MAX_SHIFT_DAYS = 3L

/** The coming Hijri year's dates as the calendar resolves them now, and what the admin set by hand. */
internal class UpcomingDates(val year: Int, val dates: YearDates, val manual: ManualIslamicDates)

/** The coming year's dates, following the admin's changes and any announcement that arrives. */
@Composable
internal fun rememberUpcomingDates(today: LocalDate): UpcomingDates {
    val manualAll by ManualIslamicDateOverrides.updates.collectAsState()
    val official by OfficialIslamicDates.updates.collectAsState()
    val year = remember(today, manualAll, official) { IslamicDays.upcomingYear(today) }
    val dates = remember(year, manualAll, official) { IslamicDays.yearDates(year) }
    return UpcomingDates(year, dates, manualAll[year] ?: ManualIslamicDates())
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
 * never received the announcement) or return it to automatic. Changes are saved at once; Back
 * (handled by the settings) returns to the menu.
 */
@Composable
fun IslamicDatesSection(today: LocalDate) {
    val upcoming = rememberUpcomingDates(today)
    val manual = upcoming.manual
    fun save(update: ManualIslamicDates) = ManualIslamicDateOverrides.set(upcoming.year, update)

    AdminPage(
        title = "${TvStrings.ISLAMIC_DATES_TITLE} ${TvStrings.hijriYear(upcoming.year)}",
        hints = listOf(TvStrings.HINT_SAVED_AT_ONCE, TvStrings.HINT_BACK_TO_SETTINGS),
    ) {
        Text(TvStrings.ISLAMIC_DATES_HINT, style = midadStyle(14.sp, color = Midad.Muted))
        Column(Modifier.widthIn(max = 720.dp).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            EventRow(
                TvStrings.RAMADAN_START, upcoming.dates.ramadanStart, focusFirst = true,
                onSet = { save(manual.copy(ramadanStart = it)) }, onAutomatic = { save(manual.copy(ramadanStart = null)) },
            )
            EventRow(
                TvStrings.EID_FITR, upcoming.dates.eidFitr,
                onSet = { save(manual.copy(eidFitr = it)) }, onAutomatic = { save(manual.copy(eidFitr = null)) },
            )
            EventRow(
                TvStrings.EID_ADHA, upcoming.dates.eidAdha,
                onSet = { save(manual.copy(eidAdha = it)) }, onAutomatic = { save(manual.copy(eidAdha = null)) },
            )
        }
    }
}

/** One event: − date +, where it comes from, and a way back to automatic once it is set by hand. */
@Composable
private fun EventRow(
    label: String,
    event: EventDate,
    onSet: (LocalDate) -> Unit,
    onAutomatic: () -> Unit,
    focusFirst: Boolean = false,
) {
    val earliest = event.withoutManual.minusDays(MAX_SHIFT_DAYS)
    val latest = event.withoutManual.plusDays(MAX_SHIFT_DAYS)
    // «تلقائي» goes away once pressed: the focus moves to the date's − rather than into nowhere.
    val minus = remember { FocusRequester() }
    Column(
        Modifier
            .fillMaxWidth()
            .background(Midad.SurfaceRaised, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(Modifier.heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, style = midadStyle(17.sp, FontWeight.Medium), modifier = Modifier.width(110.dp))
            Stepper(
                value = TvStrings.gregorianDate(event.date),
                onMinus = { event.date.minusDays(1).takeIf { !it.isBefore(earliest) }?.let(onSet) },
                onPlus = { event.date.plusDays(1).takeIf { !it.isAfter(latest) }?.let(onSet) },
                valueWidth = 210.dp,
                minusModifier = Modifier.focusRequester(minus).initialFocus(focusFirst),
            )
            Text(sourceLabel(event.source), style = midadStyle(14.sp, color = Midad.Muted), modifier = Modifier.width(56.dp))
            // Always the same room, so the three rows keep their columns whether a date is set by hand or not.
            Box(Modifier.width(92.dp)) {
                if (event.source == DateSource.MANUAL) {
                    FocusableSurface(
                        onClick = {
                            onAutomatic()
                            runCatching { minus.requestFocus() }
                        },
                        rest = Midad.Surface,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxWidth(),
                    ) { focused ->
                        Text(TvStrings.AUTOMATIC, style = midadStyle(15.sp, color = onSurfaceText(focused)), textAlign = TextAlign.Center)
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
    }
}

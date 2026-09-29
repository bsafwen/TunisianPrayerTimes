package com.tunisianprayertimes.tv.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.DateSource
import com.tunisianprayertimes.EventDate
import com.tunisianprayertimes.IslamicDays
import com.tunisianprayertimes.ManualIslamicDateOverrides
import com.tunisianprayertimes.ManualIslamicDates
import com.tunisianprayertimes.OfficialIslamicDates
import com.tunisianprayertimes.tv.ui.TvStrings
import com.tunisianprayertimes.tv.ui.common.initialFocus
import com.tunisianprayertimes.tv.ui.common.FocusableButton
import com.tunisianprayertimes.tv.ui.common.FocusableListItem
import com.tunisianprayertimes.tv.ui.theme.Gold
import java.time.LocalDate

/** How far a hand-set date may move from the announcement or estimate; the calendar rejects wilder anchors. */
private const val MAX_SHIFT_DAYS = 3L

/**
 * Ramadan and Eid dates for the coming year, each from the admin, an announcement or the estimate.
 * The admin can move a date by a day (a mosque that follows its own sighting, or an offline TV that
 * never received the announcement) or return it to automatic. Minimal; it will be redesigned.
 */
@Composable
fun IslamicDatesSection(today: LocalDate, onBack: () -> Unit) {
    val manualAll by ManualIslamicDateOverrides.updates.collectAsState()
    val official by OfficialIslamicDates.updates.collectAsState()
    val year = remember(today, manualAll, official) { IslamicDays.upcomingYear(today) }
    val dates = remember(year, manualAll, official) { IslamicDays.yearDates(year) }
    val manual = manualAll[year] ?: ManualIslamicDates()

    fun save(update: ManualIslamicDates) = ManualIslamicDateOverrides.set(year, update)

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("${TvStrings.ISLAMIC_DATES_TITLE} $year هـ", color = Gold, fontSize = 28.sp)
        Text(TvStrings.ISLAMIC_DATES_HINT, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
        EventRow(TvStrings.RAMADAN_START, dates.ramadanStart,
            onSet = { save(manual.copy(ramadanStart = it)) }, onAutomatic = { save(manual.copy(ramadanStart = null)) })
        EventRow(TvStrings.EID_FITR, dates.eidFitr,
            onSet = { save(manual.copy(eidFitr = it)) }, onAutomatic = { save(manual.copy(eidFitr = null)) })
        EventRow(TvStrings.EID_ADHA, dates.eidAdha,
            onSet = { save(manual.copy(eidAdha = it)) }, onAutomatic = { save(manual.copy(eidAdha = null)) })
        Column(Modifier.fillMaxWidth(0.4f)) { FocusableListItem(text = TvStrings.SAVE, onClick = onBack, modifier = Modifier.initialFocus()) }
    }
}

@Composable
private fun EventRow(label: String, event: EventDate, onSet: (LocalDate) -> Unit, onAutomatic: () -> Unit) {
    val earliest = event.withoutManual.minusDays(MAX_SHIFT_DAYS)
    val latest = event.withoutManual.plusDays(MAX_SHIFT_DAYS)
    Column(
        modifier = Modifier
            .fillMaxWidth(0.8f)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, color = Gold, fontSize = 20.sp, modifier = Modifier.width(130.dp))
            FocusableButton(text = "−", onClick = { event.date.minusDays(1).takeIf { !it.isBefore(earliest) }?.let(onSet) })
            Text(event.date.toString(), color = MaterialTheme.colorScheme.onSurface, fontSize = 20.sp, modifier = Modifier.width(130.dp))
            FocusableButton(text = "+", onClick = { event.date.plusDays(1).takeIf { !it.isAfter(latest) }?.let(onSet) })
            Text(sourceLabel(event.source), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp, modifier = Modifier.width(80.dp))
            if (event.source == DateSource.MANUAL) FocusableButton(text = "↺", onClick = onAutomatic)
        }
        if (event.conflictsWithAnnouncement) {
            Text("${TvStrings.ANNOUNCED_DATE}: ${event.withoutManual}", color = MaterialTheme.colorScheme.error, fontSize = 15.sp)
        }
    }
}

private fun sourceLabel(source: DateSource): String = when (source) {
    DateSource.MANUAL -> TvStrings.SOURCE_MANUAL
    DateSource.OFFICIAL -> TvStrings.SOURCE_OFFICIAL
    DateSource.ESTIMATE -> TvStrings.SOURCE_ESTIMATE
}

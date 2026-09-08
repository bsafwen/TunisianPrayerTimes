package com.tunisianprayertimes.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tunisianprayertimes.R
import com.tunisianprayertimes.HijriCalendarDate
import com.tunisianprayertimes.HijriCalendarMonth
import com.tunisianprayertimes.TunisianHijriCalendar
import com.tunisianprayertimes.ui.theme.BgCream
import com.tunisianprayertimes.ui.theme.Divider
import com.tunisianprayertimes.ui.theme.GreenPrimary
import com.tunisianprayertimes.ui.theme.GreenPrimaryDark
import com.tunisianprayertimes.ui.theme.TextDark
import com.tunisianprayertimes.ui.theme.TextMuted
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.time.format.TextStyle
import java.time.temporal.WeekFields

@Composable
internal fun HijriCalendarDialog(
    selectedDate: Long,
    dateRange: Pair<Long, Long>?,
    onDateSelected: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val calendar = rememberTunisianHijriCalendar()
    val bounds = remember(dateRange, calendar) { calendarBounds(dateRange, calendar) }
    // Snapshot the opening civil day for this dialog session. The prayer table may
    // roll its own selected date forward at midnight while a choice is in progress.
    val openingEpoch = rememberSaveable {
        val date = calendarLocalDate(selectedDate)
        val initial = if (bounds.first <= bounds.last) date.coerceIn(bounds.first, bounds.last)
        else date.coerceIn(bounds.supportedFirst, bounds.supportedLast)
        initial.toEpochDay()
    }
    val openingDate = LocalDate.ofEpochDay(openingEpoch)
    var pickedEpoch by rememberSaveable {
        mutableLongStateOf(openingEpoch)
    }
    // Keep the viewed logical month and selected civil day stable when a fetched
    // official anchor changes that month's start or number of days.
    var viewedYear by rememberSaveable {
        mutableIntStateOf(calendar.monthFor(openingDate).year)
    }
    var viewedMonth by rememberSaveable {
        mutableIntStateOf(calendar.monthFor(openingDate).month)
    }
    val pickedDate = LocalDate.ofEpochDay(pickedEpoch)
    val pickedHijriDate = remember(pickedDate, calendar) { calendar.date(pickedDate) }
    val hijriMonth = remember(viewedYear, viewedMonth, calendar) { calendar.month(viewedYear, viewedMonth) }
    val monthStart = hijriMonth.start
    val monthEnd = hijriMonth.endExclusive.minusDays(1)
    val days = remember(hijriMonth) {
        List(hijriMonth.lengthOfMonth) { monthStart.plusDays(it.toLong()) }
    }
    LoadOfficialCalendarYear(viewedYear)
    val today = rememberCalendarToday()
    val previousMonth = remember(hijriMonth, calendar) { calendar.adjacentMonth(viewedYear, viewedMonth, -1) }
    val nextMonth = remember(hijriMonth, calendar) { calendar.adjacentMonth(viewedYear, viewedMonth, 1) }
    val navigateToMonth: (HijriCalendarMonth) -> Unit = {
        viewedYear = it.year
        viewedMonth = it.month
    }
    val monthFormatter = remember {
        DateTimeFormatter.ofPattern("MMMM yyyy", calendarLocale).withDecimalStyle(DecimalStyle.STANDARD)
    }
    val solarStart = monthStart.format(monthFormatter)
    val solarEnd = monthEnd.format(monthFormatter)
    val solarRange = if (solarStart == solarEnd) solarStart else {
        stringResource(R.string.calendar_month_range, solarStart, solarEnd)
    }
    val weekStart = remember { WeekFields.of(calendarLocale).firstDayOfWeek }
    val weekdays = remember { List(7) { weekStart.plus(it.toLong()) } }
    val leadingDays = (monthStart.dayOfWeek.value - weekStart.value + 7) % 7
    val weekRows = remember(hijriMonth) {
        (List<LocalDate?>(leadingDays) { null } + days).chunked(7)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            BoxWithConstraints(
                Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 12.dp, vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                val availableHeight = maxHeight
                Surface(
                    modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().fillMaxHeight(0.94f),
                    shape = RoundedCornerShape(24.dp),
                    color = Color.White,
                ) {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val density = LocalDensity.current
                        val measurer = rememberTextMeasurer()
                        val primaryStyle = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        )
                        val secondaryStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp)
                        // Measure at the user's actual font scale, including Android's nonlinear scaling.
                        // Seven real touch targets must fit; otherwise use full-width, height-growing days.
                        val widestNumber = (1..31).maxOf {
                            measurer.measure(it.toString(), primaryStyle).size.width
                        }
                        val widestWeekday = weekdays.maxOf {
                            measurer.measure(it.getDisplayName(TextStyle.NARROW, calendarLocale), secondaryStyle).size.width
                        }
                        val todayWidth = measurer.measure(
                            stringResource(R.string.calendar_today), secondaryStyle,
                        ).size.width
                        val minimumColumn = with(density) {
                            maxOf(widestNumber.toDp() + 20.dp, widestWeekday.toDp() + 8.dp,
                                todayWidth.toDp() + 8.dp, 48.dp)
                        }
                        val useDayList = maxWidth - 24.dp < minimumColumn * 7
                        val scroll = rememberLazyListState()
                        LaunchedEffect(viewedYear, viewedMonth, useDayList) { scroll.scrollToItem(0) }
                        Column(Modifier.fillMaxSize()) {
                            LazyColumn(
                                state = scroll,
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                contentPadding = PaddingValues(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                item(key = "selection") {
                                    Column(
                                        Modifier.fillMaxWidth().background(BgCream, RoundedCornerShape(16.dp)).padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(stringResource(R.string.calendar_selected_date), fontSize = 12.sp, color = GreenPrimary)
                                        Text(hijriDateLabel(pickedHijriDate), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = GreenPrimaryDark)
                                        Text(gregorianDateLabel(pickedDate), fontSize = 14.sp, color = TextMuted)
                                        if (!pickedHijriDate.isEstimated) Text(
                                            stringResource(R.string.calendar_official_start_short),
                                            fontSize = 12.sp, color = TextMuted,
                                        )
                                    }
                                }
                                item(key = "month") {
                                    Column(
                                        Modifier.fillMaxWidth().padding(top = 8.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Text(
                                            hijriMonthLabel(hijriMonth.year, hijriMonth.month),
                                            modifier = Modifier.semantics { heading() },
                                            fontSize = 22.sp, fontWeight = FontWeight.Bold,
                                            textAlign = TextAlign.Center, color = GreenPrimaryDark,
                                        )
                                        Text(
                                            stringResource(R.string.calendar_gregorian_month, solarRange),
                                            fontSize = 14.sp, color = TextMuted, textAlign = TextAlign.Center,
                                        )
                                        if (!hijriMonth.isEstimated) Text(
                                            stringResource(R.string.calendar_official_start_short),
                                            fontSize = 12.sp, color = TextMuted, textAlign = TextAlign.Center,
                                        )
                                    }
                                }
                                item(key = "navigation") {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        CalendarMonthArrow(
                                            previous = true,
                                            enabled = previousMonth?.let(bounds::intersectsMonth) == true,
                                            onClick = { previousMonth?.let(navigateToMonth) },
                                        )
                                        TextButton(
                                            onClick = {
                                                val currentToday = LocalDate.now()
                                                if (bounds.contains(currentToday)) {
                                                    pickedEpoch = currentToday.toEpochDay()
                                                    navigateToMonth(calendar.monthFor(currentToday))
                                                }
                                            },
                                            enabled = bounds.contains(today),
                                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                        ) {
                                            Text(stringResource(R.string.calendar_today), color = if (bounds.contains(today)) GreenPrimary else TextMuted,
                                                fontSize = 16.sp, textAlign = TextAlign.Center)
                                        }
                                        CalendarMonthArrow(
                                            previous = false,
                                            enabled = nextMonth?.let(bounds::intersectsMonth) == true,
                                            onClick = { nextMonth?.let(navigateToMonth) },
                                        )
                                    }
                                }
                                if (useDayList) {
                                    items(days, key = { it.toEpochDay() }) { date ->
                                        CalendarDay(
                                            date = date, selected = date == pickedDate, today = date == today,
                                            hijriDate = calendar.date(date),
                                            enabled = bounds.contains(date), expanded = true,
                                            modifier = Modifier.fillMaxWidth(),
                                            onClick = { pickedEpoch = date.toEpochDay() },
                                        )
                                    }
                                } else {
                                    item(key = "weekdays") {
                                        Row(Modifier.fillMaxWidth()) {
                                            weekdays.forEach { day ->
                                                Text(
                                                    day.getDisplayName(TextStyle.NARROW, calendarLocale),
                                                    modifier = Modifier.weight(1f).semantics {
                                                        contentDescription = day.getDisplayName(TextStyle.FULL, calendarLocale)
                                                    },
                                                    style = secondaryStyle, color = TextMuted, textAlign = TextAlign.Center,
                                                )
                                            }
                                        }
                                    }
                                    items(weekRows.size, key = { "week:$it" }) { index ->
                                        Row(Modifier.fillMaxWidth()) {
                                            val week = weekRows[index]
                                            repeat(7) { column ->
                                                val date = week.getOrNull(column)
                                                if (date == null) Spacer(Modifier.weight(1f)) else CalendarDay(
                                                    date = date, selected = date == pickedDate, today = date == today,
                                                    hijriDate = calendar.date(date),
                                                    enabled = bounds.contains(date), expanded = false,
                                                    modifier = Modifier.weight(1f),
                                                    onClick = { pickedEpoch = date.toEpochDay() },
                                                )
                                            }
                                        }
                                    }
                                }
                                item(key = "method") {
                                    Text(
                                        stringResource(R.string.calendar_calculation_method),
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                        fontSize = 12.sp, color = TextMuted, textAlign = TextAlign.Center,
                                    )
                                }
                            }
                            HorizontalDivider(color = Divider)
                            // A separately scrollable footer keeps calendar navigation usable even when
                            // accessibility text is taller than the space available in landscape.
                            CalendarConfirmation(
                                modifier = Modifier.fillMaxWidth().heightIn(max = availableHeight * 0.42f)
                                    .verticalScroll(rememberScrollState()).padding(12.dp),
                                enabled = bounds.contains(pickedDate),
                                onConfirm = {
                                    onDateSelected(calendarDateMillis(pickedDate))
                                    onDismiss()
                                },
                                onDismiss = onDismiss,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarDay(
    date: LocalDate,
    hijriDate: HijriCalendarDate,
    selected: Boolean,
    today: Boolean,
    enabled: Boolean,
    expanded: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val mainColor = if (selected) Color.White else if (enabled) TextDark else TextMuted.copy(alpha = 0.55f)
    val smallColor = if (selected) Color.White else if (enabled) TextMuted else TextMuted.copy(alpha = 0.55f)
    val todayLabel = stringResource(R.string.calendar_today)
    val description = listOfNotNull(
        stringResource(R.string.calendar_day_description, hijriDateLabel(hijriDate), gregorianDateLabel(date)),
        if (!hijriDate.isEstimated) stringResource(R.string.calendar_official_start_short) else null,
        todayLabel.takeIf { today },
    ).joinToString("، ")
    Column(
        modifier = modifier.heightIn(min = 64.dp).clip(shape)
            .background(if (selected) GreenPrimaryDark else Color.Transparent)
            .then(if (today) Modifier.border(BorderStroke(1.dp, GreenPrimary), shape) else Modifier)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = if (expanded) 12.dp else 2.dp, vertical = 8.dp),
        horizontalAlignment = if (expanded) Alignment.Start else Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (expanded) hijriDateLabel(hijriDate) else hijriDate.day.toString(),
                modifier = Modifier.weight(1f, fill = false).clearAndSetSemantics {},
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold), color = mainColor,
            )
            if (selected) Canvas(Modifier.size(12.dp)) {
                drawLine(mainColor, Offset(size.width * 0.15f, size.height * 0.5f), Offset(size.width * 0.4f, size.height * 0.75f), 2.dp.toPx(), StrokeCap.Round)
                drawLine(mainColor, Offset(size.width * 0.4f, size.height * 0.75f), Offset(size.width * 0.85f, size.height * 0.25f), 2.dp.toPx(), StrokeCap.Round)
            }
        }
        Text(
            if (expanded) gregorianDateLabel(date) else date.dayOfMonth.toString(),
            modifier = Modifier.clearAndSetSemantics {},
            style = MaterialTheme.typography.bodySmall.copy(fontSize = if (expanded) 14.sp else 12.sp), color = smallColor,
        )
        if (today) Text(todayLabel, modifier = Modifier.clearAndSetSemantics {},
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = mainColor)
    }
}

@Composable
private fun CalendarMonthArrow(previous: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val label = stringResource(if (previous) R.string.calendar_previous_month else R.string.calendar_next_month)
    val color = if (enabled) GreenPrimary else TextMuted.copy(alpha = 0.4f)
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp).semantics { contentDescription = label }) {
        // In this RTL calendar, earlier dates are to the right and later dates to the left.
        Canvas(Modifier.size(24.dp)) {
            val tipX = size.width * if (previous) 0.65f else 0.35f
            val tailX = size.width * if (previous) 0.35f else 0.65f
            drawLine(color, Offset(tailX, size.height * 0.25f), Offset(tipX, size.height * 0.5f), 2.dp.toPx(), StrokeCap.Round)
            drawLine(color, Offset(tipX, size.height * 0.5f), Offset(tailX, size.height * 0.75f), 2.dp.toPx(), StrokeCap.Round)
        }
    }
}

@Composable
private fun CalendarConfirmation(modifier: Modifier, enabled: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val confirm = stringResource(R.string.calendar_confirm)
    val cancel = stringResource(R.string.calendar_cancel)
    val style = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val buttonsWidth = with(density) {
        (measurer.measure(confirm, style).size.width + measurer.measure(cancel, style).size.width).toDp() + 112.dp
    }
    BoxWithConstraints(modifier) {
        val stacked = maxWidth < buttonsWidth
        val confirmButton: @Composable (Modifier) -> Unit = { buttonModifier ->
            Button(
                onClick = onConfirm, enabled = enabled,
                colors = ButtonDefaults.buttonColors(containerColor = GreenPrimaryDark),
                modifier = buttonModifier.heightIn(min = 48.dp),
            ) { Text(confirm, style = style, textAlign = TextAlign.Center) }
        }
        val cancelButton: @Composable (Modifier) -> Unit = { buttonModifier ->
            TextButton(onClick = onDismiss, modifier = buttonModifier.heightIn(min = 48.dp)) {
                Text(cancel, style = style, color = GreenPrimary, textAlign = TextAlign.Center)
            }
        }
        if (stacked) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            confirmButton(Modifier.fillMaxWidth())
            cancelButton(Modifier.fillMaxWidth())
        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            confirmButton(Modifier.weight(1f))
            cancelButton(Modifier.weight(1f))
        }
    }
}

private data class CalendarBounds(
    val first: LocalDate,
    val last: LocalDate,
    val supportedFirst: LocalDate,
    val supportedLast: LocalDate,
) {
    fun contains(date: LocalDate) = !date.isBefore(first) && !date.isAfter(last)
    fun intersectsMonth(month: HijriCalendarMonth): Boolean =
        first <= last && !month.start.isAfter(last) && month.endExclusive.isAfter(first)
}

private fun calendarBounds(dateRange: Pair<Long, Long>?, calendar: TunisianHijriCalendar): CalendarBounds {
    val supportedFirst = calendar.supportedFirst
    val supportedLast = calendar.supportedLast
    return CalendarBounds(
        first = maxOf(dateRange?.first?.let(::calendarLocalDate) ?: supportedFirst, supportedFirst),
        last = minOf(dateRange?.second?.let(::calendarLocalDate) ?: supportedLast, supportedLast),
        supportedFirst = supportedFirst,
        supportedLast = supportedLast,
    )
}

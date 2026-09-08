package com.tunisianprayertimes.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.PrayerTimesRepository
import com.tunisianprayertimes.HijriCalendarDate
import com.tunisianprayertimes.R
import com.tunisianprayertimes.ui.theme.Gold
import com.tunisianprayertimes.ui.theme.GoldLight
import com.tunisianprayertimes.ui.theme.GreenPrimary
import com.tunisianprayertimes.ui.theme.GreenPrimaryDark
import com.tunisianprayertimes.ui.theme.TextMuted
import java.time.LocalDate

@Composable
internal fun DateNavigationRow(
    delegationId: Int,
    selectedDate: Long,
    isToday: Boolean,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onDateSelected: (Long) -> Unit,
) {
    val context = LocalContext.current
    val date = calendarLocalDate(selectedDate)
    val calendar = rememberTunisianHijriCalendar()
    val hijriDate = remember(date, calendar) { calendar.date(date) }
    LoadOfficialCalendarYear(algorithmicHijriYear(date))
    val dateRange = remember(context, delegationId) {
        PrayerTimesRepository.getDateRange(context, delegationId)
    }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var pickerOpeningDate by rememberSaveable { mutableLongStateOf(selectedDate) }
    val shape = RoundedCornerShape(16.dp)
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val primaryTextStyle = LocalTextStyle.current.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold)
    val primaryTextWidth = textMeasurer.measure(
        text = hijriDateLabel(hijriDate),
        style = primaryTextStyle,
        softWrap = false,
        maxLines = 1,
    ).size.width

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(GoldLight.copy(alpha = 0.18f))
            .border(BorderStroke(1.dp, Gold.copy(alpha = 0.22f)), shape)
            .padding(6.dp),
    ) {
        // Measure the actual text with the user's font settings, including nonlinear
        // scaling. Move controls below when they would crowd the primary date.
        val stackControls = maxWidth < with(density) { primaryTextWidth.toDp() } + 112.dp
        val dateLabel: @Composable (Modifier) -> Unit = { modifier ->
            DualDateLabel(
                date = date,
                hijriDate = hijriDate,
                modifier = modifier,
                onClick = {
                    pickerOpeningDate = selectedDate
                    showDatePicker = true
                },
            )
        }
        val previousButton: @Composable () -> Unit = {
            DateDayButton(
                previous = true,
                enabled = canGoBack,
                onClick = onPrevious,
            )
        }
        val nextButton: @Composable () -> Unit = {
            DateDayButton(
                previous = false,
                enabled = canGoForward,
                onClick = onNext,
            )
        }
        val todayButton: @Composable () -> Unit = {
            if (!isToday) {
                TextButton(
                    onClick = { onDateSelected(calendarDateMillis(LocalDate.now())) },
                    modifier = Modifier.testTag(TestTags.DATE_TODAY_BUTTON).heightIn(min = 48.dp),
                ) {
                    Text(
                        text = stringResource(R.string.date_go_back_today),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = GreenPrimary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (stackControls) {
                dateLabel(Modifier.fillMaxWidth())
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    previousButton()
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { todayButton() }
                    nextButton()
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    previousButton()
                    dateLabel(Modifier.weight(1f))
                    nextButton()
                }
                todayButton()
            }
        }
    }

    if (showDatePicker) {
        HijriCalendarDialog(
            selectedDate = pickerOpeningDate,
            dateRange = dateRange,
            onDateSelected = {
                showDatePicker = false
                onDateSelected(it)
            },
            onDismiss = { showDatePicker = false },
        )
    }
}

@Composable
private fun DualDateLabel(date: LocalDate, hijriDate: HijriCalendarDate, modifier: Modifier, onClick: () -> Unit) {
    val pickerLabel = stringResource(R.string.calendar_open_picker)
    Column(
        modifier = modifier
            .testTag(TestTags.DATE_LABEL)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClickLabel = pickerLabel, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = hijriDateLabel(hijriDate),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = GreenPrimaryDark,
            textAlign = TextAlign.Center,
        )
        Text(
            text = gregorianDateLabel(date),
            fontSize = 13.sp,
            color = TextMuted,
            textAlign = TextAlign.Center,
        )
        if (!hijriDate.isEstimated) Text(
            text = stringResource(R.string.calendar_official_start_short),
            fontSize = 11.sp,
            color = TextMuted,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DateDayButton(previous: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val label = stringResource(if (previous) R.string.calendar_previous_day else R.string.calendar_next_day)
    val color = if (enabled) GreenPrimary else TextMuted.copy(alpha = 0.35f)
    // RTL reading order places the previous-day control on the right. Its arrow
    // points outwards, as does the next-day control on the opposite side.
    val isRtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    val pointsRight = previous == isRtl
    Box(
        modifier = Modifier
            .testTag(if (previous) TestTags.DATE_PREVIOUS_BUTTON else TestTags.DATE_NEXT_BUTTON)
            .size(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) Color.White.copy(alpha = 0.75f) else Color.Transparent)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) {
            val outerX = size.width * if (pointsRight) 0.35f else 0.65f
            val tipX = size.width * if (pointsRight) 0.65f else 0.35f
            drawLine(color, Offset(outerX, size.height * 0.2f), Offset(tipX, size.height * 0.5f), 2.dp.toPx(), StrokeCap.Round)
            drawLine(color, Offset(tipX, size.height * 0.5f), Offset(outerX, size.height * 0.8f), 2.dp.toPx(), StrokeCap.Round)
        }
    }
}

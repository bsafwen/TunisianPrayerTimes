package com.tunisianprayertimes.tv.ui.clock

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.tv.ui.TvStrings
import com.tunisianprayertimes.tv.ui.common.AdminPage
import com.tunisianprayertimes.tv.ui.common.Digits
import com.tunisianprayertimes.tv.ui.common.FocusableListItem
import com.tunisianprayertimes.tv.ui.common.Stepper
import com.tunisianprayertimes.tv.ui.common.adminPanel
import com.tunisianprayertimes.tv.ui.common.initialFocus
import com.tunisianprayertimes.tv.ui.common.rtl
import com.tunisianprayertimes.tv.ui.theme.Midad
import com.tunisianprayertimes.tv.ui.theme.midadStyle
import java.time.LocalDateTime
import java.util.Locale

/**
 * Shown instead of the prayer times when the device clock cannot be right. The admin fixes the
 * system time, sets it here (boxes with locked system settings), or confirms it. Back never leaves
 * this screen: wrong prayer times on the wall are worse than none. On the right what is wrong and the
 * ways out, on the left the time being set, one stepper per field.
 */
@Composable
fun ClockWarningScreen(
    deviceTime: String,
    initial: LocalDateTime,
    canOpenSystemSettings: Boolean,
    onOpenSystemSettings: () -> Unit,
    onSetTime: (LocalDateTime) -> Unit,
    onConfirm: () -> Unit,
) {
    BackHandler { }
    var time by remember(initial.toLocalDate()) { mutableStateOf(initial.withSecond(0).withNano(0)) }
    AdminPage(
        title = TvStrings.CLOCK_WRONG_TITLE,
        modifier = Modifier.background(Midad.Ground).padding(horizontal = 48.dp, vertical = 27.dp),
    ) {
        Row(Modifier.fillMaxSize().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.width(380.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(TvStrings.CLOCK_WRONG_HINT, style = midadStyle(17.sp, color = Midad.Muted, lineHeight = 1.5f).rtl())
                Spacer(Modifier.height(6.dp))
                Text(TvStrings.CLOCK_DEVICE_TIME, style = midadStyle(14.sp, color = Midad.Muted))
                // Its own paragraph, left to right: "2001-01-01 00:03 (Africa/Tunis)" must not turn around.
                Text(deviceTime, style = midadStyle(17.sp, FontWeight.Medium, Midad.Alert).copy(textDirection = TextDirection.Ltr))
                Spacer(Modifier.weight(1f))
                FocusableListItem(text = TvStrings.CLOCK_SET_HERE, onClick = { onSetTime(time) }, modifier = Modifier.initialFocus())
                if (canOpenSystemSettings) FocusableListItem(text = TvStrings.CLOCK_OPEN_SETTINGS, onClick = onOpenSystemSettings)
                FocusableListItem(text = TvStrings.CLOCK_CONFIRM, onClick = onConfirm)
            }
            Column(
                Modifier.weight(1f).fillMaxHeight().adminPanel(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // What «اعتماد هذا الوقت» will set, written out: the weekday is the easiest check.
                Text(TvStrings.gregorianDate(time.toLocalDate()), style = midadStyle(17.sp, color = Midad.Muted))
                Digits(TvStrings.hm(time.toLocalTime()), midadStyle(40.sp, FontWeight.SemiBold))
                Spacer(Modifier.height(4.dp))
                TimeRow(TvStrings.CLOCK_MONTH, TvStrings.monthYear(time.toLocalDate()), { time = time.minusMonths(1) }, { time = time.plusMonths(1) })
                TimeRow(TvStrings.CLOCK_DATE, time.dayOfMonth.toString(), { time = time.minusDays(1) }, { time = time.plusDays(1) })
                TimeRow(TvStrings.CLOCK_HOUR, twoDigits(time.hour), { time = time.minusHours(1) }, { time = time.plusHours(1) })
                TimeRow(TvStrings.CLOCK_MINUTE, twoDigits(time.minute), { time = time.minusMinutes(1) }, { time = time.plusMinutes(1) })
            }
        }
    }
}

/** A field of the time on a row of the panel, as in the settings tables: its name, then − value +. */
@Composable
private fun TimeRow(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Midad.SurfaceRaised, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = midadStyle(17.sp, FontWeight.Medium), modifier = Modifier.weight(1f))
        Stepper(value = value, onMinus = onMinus, onPlus = onPlus, valueWidth = 150.dp)
    }
}

private fun twoDigits(value: Int): String = String.format(Locale.ROOT, "%02d", value)

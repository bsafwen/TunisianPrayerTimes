package com.tunisianprayertimes.tv.ui.clock

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.tv.ui.TvStrings
import com.tunisianprayertimes.tv.ui.common.initialFocus
import com.tunisianprayertimes.tv.ui.common.FocusableButton
import com.tunisianprayertimes.tv.ui.common.FocusableListItem
import com.tunisianprayertimes.tv.ui.theme.Gold
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Shown instead of the prayer times when the device clock cannot be right. The admin fixes the
 * system time, sets it here (boxes with locked system settings), or confirms it. Back never leaves
 * this screen: wrong prayer times on the wall are worse than none. Minimal; it will be redesigned.
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
    Column(
        // Scrolls with the remote's focus: every option must be reachable on a 720p screen.
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState()).padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(TvStrings.CLOCK_WRONG_TITLE, color = Gold, fontSize = 26.sp)
        Text("${TvStrings.CLOCK_DEVICE_TIME}: $deviceTime", color = MaterialTheme.colorScheme.onBackground, fontSize = 18.sp)
        Text(TvStrings.CLOCK_WRONG_HINT, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp, textAlign = TextAlign.Center)
        TimeStepper(TvStrings.CLOCK_MONTH, time.format(DateTimeFormatter.ofPattern("yyyy-MM")), { time = time.minusMonths(1) }, { time = time.plusMonths(1) })
        TimeStepper(TvStrings.CLOCK_DATE, time.format(DateTimeFormatter.ISO_LOCAL_DATE), { time = time.minusDays(1) }, { time = time.plusDays(1) })
        TimeStepper(TvStrings.CLOCK_HOUR, "%02d".format(java.util.Locale.ROOT, time.hour), { time = time.minusHours(1) }, { time = time.plusHours(1) })
        TimeStepper(TvStrings.CLOCK_MINUTE, "%02d".format(java.util.Locale.ROOT, time.minute), { time = time.minusMinutes(1) }, { time = time.plusMinutes(1) })
        Column(Modifier.fillMaxWidth(0.45f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FocusableListItem(text = TvStrings.CLOCK_SET_HERE, onClick = { onSetTime(time) }, modifier = Modifier.initialFocus())
            if (canOpenSystemSettings) FocusableListItem(text = TvStrings.CLOCK_OPEN_SETTINGS, onClick = onOpenSystemSettings)
            FocusableListItem(text = TvStrings.CLOCK_CONFIRM, onClick = onConfirm)
        }
    }
}

@Composable
private fun TimeStepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 18.sp, modifier = Modifier.width(90.dp))
        FocusableButton(text = "−", onClick = onMinus)
        Text(value, color = MaterialTheme.colorScheme.onBackground, fontSize = 22.sp, textAlign = TextAlign.Center, modifier = Modifier.width(160.dp))
        FocusableButton(text = "+", onClick = onPlus)
    }
}

package com.tunisianprayertimes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.DelayMode
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerSilenceConfig
import com.tunisianprayertimes.PrayerTime
import com.tunisianprayertimes.R
import com.tunisianprayertimes.SilenceMode
import com.tunisianprayertimes.ui.theme.GreenPrimaryDark
import com.tunisianprayertimes.ui.theme.SilenceRed
import com.tunisianprayertimes.ui.theme.TextMuted
import java.util.Locale

private const val NO_END_OFFSET = Int.MIN_VALUE

private val PrayerEditorConfigSaver = listSaver<PrayerSilenceConfig, Any>(
    save = {
        listOf(
            it.mode.name, it.afterMinutes, it.fixedHour, it.fixedMinute,
            it.delayMode.name, it.delayMinutes, it.delayFixedHour, it.delayFixedMinute,
            it.endOffsetMinutes ?: NO_END_OFFSET,
        )
    },
    restore = {
        PrayerSilenceConfig(
            mode = SilenceMode.valueOf(it[0] as String),
            afterMinutes = it[1] as Int,
            fixedHour = it[2] as Int,
            fixedMinute = it[3] as Int,
            delayMode = DelayMode.valueOf(it[4] as String),
            delayMinutes = it[5] as Int,
            delayFixedHour = it[6] as Int,
            delayFixedMinute = it[7] as Int,
            endOffsetMinutes = (it[8] as Int).takeIf { value -> value != NO_END_OFFSET },
        )
    },
)

/**
 * Precision editor for a single endpoint. The draft is committed atomically
 * through Apply; the window preview reflects the whole interval.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PrayerSilenceEditorSheet(
    prayer: Prayer,
    prayerName: String,
    prayerTime: PrayerTime,
    config: PrayerSilenceConfig,
    endpoint: SilenceEndpoint,
    onDismiss: () -> Unit,
    onSave: (PrayerSilenceConfig) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var draft by rememberSaveable(prayer, config, endpoint, stateSaver = PrayerEditorConfigSaver) {
        mutableStateOf(config)
    }
    var saved by remember(prayer, config, endpoint) { mutableStateOf(false) }
    val window = resolvePrayerTimelineWindow(prayerTime, draft)
    val valid = configResolvesToValidWindow(prayerTime, draft)
    val endpointTitle = stringResource(
        if (endpoint == SilenceEndpoint.START) {
            R.string.prayer_silence_start_group
        } else {
            R.string.prayer_silence_end_group
        },
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.White,
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding(),
        ) {
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp).padding(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.prayer_editor_endpoint_title, endpointTitle, prayerName),
                        color = GreenPrimaryDark,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        stringResource(R.string.prayer_editor_daily_note),
                        color = TextMuted,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )
                }
                PrayerEditorWindowPreview(if (valid) window else null)
                EndpointRuleControls(
                    endpoint = endpoint,
                    config = draft,
                    prayerTime = prayerTime,
                    window = window,
                    enabled = true,
                    onConfigChange = { updated -> draft = updated },
                    showTitle = false,
                    prayerName = prayerName,
                )
                if (!valid) {
                    Text(
                        text = stringResource(R.string.prayer_editor_invalid_window),
                        color = SilenceRed,
                        fontSize = 14.sp,
                    )
                }
            }
            PrayerEditorActions(
                canSave = valid && !saved,
                onSave = {
                    if (valid && !saved) {
                        saved = true
                        focusManager.clearFocus()
                        onSave(draft)
                    }
                },
                onCancel = { focusManager.clearFocus(); onDismiss() },
            )
        }
    }
}

@Composable
private fun PrayerEditorWindowPreview(window: PrayerTimelineWindow?) {
    Column(
        Modifier.fillMaxWidth()
            .background(GreenPrimaryDark.copy(alpha = 0.045f), RoundedCornerShape(14.dp))
            .border(1.dp, GreenPrimaryDark.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            stringResource(R.string.prayer_editor_preview),
            color = GreenPrimaryDark,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
        if (window == null) {
            Text(stringResource(R.string.prayer_editor_invalid_window), color = SilenceRed, fontSize = 14.sp)
        } else {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth.value / LocalDensity.current.fontScale >= 240f) {
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        PrayerEditorPreviewEndpoint(stringResource(R.string.prayer_editor_start), window.startMinutes, Modifier.weight(1f))
                        PrayerEditorPreviewEndpoint(stringResource(R.string.prayer_editor_end), window.endMinutes, Modifier.weight(1f))
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        PrayerEditorPreviewEndpoint(stringResource(R.string.prayer_editor_start), window.startMinutes, Modifier.fillMaxWidth())
                        PrayerEditorPreviewEndpoint(stringResource(R.string.prayer_editor_end), window.endMinutes, Modifier.fillMaxWidth())
                    }
                }
            }
            Text(
                stringResource(R.string.prayer_editor_preview_duration, window.durationMinutes),
                color = TextMuted,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
        }
    }
}

@Composable
private fun PrayerEditorPreviewEndpoint(label: String, minutes: Int, modifier: Modifier) {
    val day = Math.floorDiv(minutes, 1440)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = TextMuted, fontSize = 12.sp)
        Text(
            prayerEditorClockText(minutes),
            color = GreenPrimaryDark,
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
            style = TextStyle(textDirection = TextDirection.Ltr),
        )
        if (day != 0) {
            Text(
                text = when (day) {
                    -1 -> stringResource(R.string.prayer_editor_previous_day)
                    1 -> stringResource(R.string.prayer_editor_next_day)
                    else -> stringResource(
                        if (day < 0) R.string.prayer_editor_days_before else R.string.prayer_editor_days_after,
                        kotlin.math.abs(day),
                    )
                },
                color = TextMuted,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }
    }
}

@Composable
private fun PrayerEditorActions(canSave: Boolean, onSave: () -> Unit, onCancel: () -> Unit) {
    Column {
        HorizontalDivider(color = GreenPrimaryDark.copy(alpha = 0.10f))
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            val save: @Composable (Modifier) -> Unit = { modifier ->
                Button(
                    onClick = onSave,
                    enabled = canSave,
                    modifier = modifier.heightIn(min = 48.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = GreenPrimaryDark),
                ) {
                    Text(stringResource(R.string.prayer_editor_apply), fontWeight = FontWeight.Bold)
                }
            }
            val cancel: @Composable (Modifier) -> Unit = { modifier ->
                TextButton(onClick = onCancel, modifier = modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(10.dp)) {
                    Text(stringResource(R.string.prayer_editor_cancel), color = GreenPrimaryDark)
                }
            }
            if (maxWidth.value / LocalDensity.current.fontScale >= 240f) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    save(Modifier.weight(1.4f))
                    cancel(Modifier.weight(1f))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    save(Modifier.fillMaxWidth())
                    cancel(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

private fun prayerEditorClockText(minutes: Int): String {
    val clock = Math.floorMod(minutes, 1440)
    return String.format(Locale.US, "%02d:%02d", clock / 60, clock % 60)
}

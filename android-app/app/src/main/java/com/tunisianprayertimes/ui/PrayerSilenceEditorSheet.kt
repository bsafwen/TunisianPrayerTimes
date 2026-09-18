package com.tunisianprayertimes.ui

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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
import kotlin.math.abs

private const val NO_END_OFFSET = Int.MIN_VALUE
private const val MINUTES_PER_DAY = 24 * 60

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
 * Precision editor for a single endpoint. The draft, including temporary
 * invalid text, stays local; only Apply commits the selected endpoint's rule.
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
    onSave: (PrayerSilenceConfig) -> Boolean,
) {
    val focusManager = LocalFocusManager.current
    val start = endpoint == SilenceEndpoint.START
    val prayerMinutes = prayerMinutesOfDay(prayerTime)
    val initialWindow = resolvePrayerTimelineWindow(prayerTime, config)
    val initialResolved = if (start) initialWindow.startMinutes else initialWindow.endMinutes
    val initialOffset = initialResolved - prayerMinutes
    val initialClock = Math.floorMod(initialResolved, MINUTES_PER_DAY)

    var draft by rememberSaveable(prayer, config, endpoint, stateSaver = PrayerEditorConfigSaver) {
        mutableStateOf(config)
    }
    var relativeText by rememberSaveable(prayer, config, endpoint) {
        mutableStateOf(abs(initialOffset).toString())
    }
    var hourText by rememberSaveable(prayer, config, endpoint) {
        mutableStateOf(clockField(initialClock / 60))
    }
    var minuteText by rememberSaveable(prayer, config, endpoint) {
        mutableStateOf(clockField(initialClock % 60))
    }
    var saved by remember(prayer, config, endpoint) { mutableStateOf(false) }

    val window = resolvePrayerTimelineWindow(prayerTime, draft)
    val fixed = (if (start) draft.startRuleMode() else draft.endRuleMode()) == EndpointRuleMode.FIXED_TIME
    val resolvedMinutes = if (start) window.startMinutes else window.endMinutes
    val resolvedOffset = resolvedMinutes - prayerMinutes

    val relativeValid = fixed || resolvedOffset == 0 ||
        relativeText.toIntOrNull()?.let { it in 1..EndpointOffsetLimitMinutes } == true
    val hourValue = hourText.toIntOrNull()
    val minuteValue = minuteText.toIntOrNull()
    val clockValid = !fixed ||
        (hourValue != null && hourValue in 0..23 && minuteValue != null && minuteValue in 0..59)
    val windowValid = configResolvesToValidWindow(prayerTime, draft)
    val valid = relativeValid && clockValid && windowValid

    fun applyRule(mode: EndpointRuleMode, offsetMinutes: Int, clockMinutes: Int) {
        draft = if (start) {
            draft.withStartRule(mode, offsetMinutes, clockMinutes)
        } else {
            draft.withEndRule(mode, offsetMinutes, clockMinutes)
        }
    }

    fun updateFixedDraft() {
        val h = hourText.toIntOrNull() ?: return
        val m = minuteText.toIntOrNull() ?: return
        if (h !in 0..23 || m !in 0..59) return
        applyRule(EndpointRuleMode.FIXED_TIME, resolvedOffset, h * 60 + m)
    }

    val endpointTitle = stringResource(
        if (start) R.string.prayer_silence_start_group else R.string.prayer_silence_end_group,
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.White,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp).padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
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
                EndpointRuleEditor(
                    endpoint = endpoint,
                    config = draft,
                    prayerTime = prayerTime,
                    window = window,
                    enabled = !saved,
                    relativeValue = relativeText,
                    hourValue = hourText,
                    minuteValue = minuteText,
                    onModeSelected = { target ->
                        val currentWindow = resolvePrayerTimelineWindow(prayerTime, draft)
                        val currentResolved = if (start) currentWindow.startMinutes else currentWindow.endMinutes
                        if (target == EndpointRuleMode.FIXED_TIME) {
                            applyRule(
                                EndpointRuleMode.FIXED_TIME,
                                currentResolved - prayerMinutes,
                                currentResolved,
                            )
                            val clock = Math.floorMod(currentResolved, MINUTES_PER_DAY)
                            hourText = clockField(clock / 60)
                            minuteText = clockField(clock % 60)
                        } else {
                            val offset = currentResolved - prayerMinutes
                            applyRule(EndpointRuleMode.ADHAN, offset, currentResolved)
                            relativeText = abs(offset).toString()
                        }
                    },
                    onDirectionSelected = { direction ->
                        val magnitude = relativeText.toIntOrNull()?.coerceAtLeast(1)
                            ?: abs(resolvedOffset).coerceAtLeast(1)
                        val signed = when (direction) {
                            EndpointDirection.BEFORE -> -magnitude
                            EndpointDirection.AT_ADHAN -> 0
                            EndpointDirection.AFTER -> magnitude
                        }
                        applyRule(EndpointRuleMode.ADHAN, signed, resolvedMinutes)
                        relativeText = when {
                            direction == EndpointDirection.AT_ADHAN -> "0"
                            relativeText.toIntOrNull() == null ||
                                relativeText.toIntOrNull() == 0 -> magnitude.toString()
                            else -> relativeText
                        }
                    },
                    onRelativeValueChange = { raw ->
                        val digits = normalizeEndpointNumber(raw).take(4)
                        relativeText = digits
                        val parsed = digits.toIntOrNull()
                        if (parsed != null && parsed <= EndpointOffsetLimitMinutes) {
                            val signed = when {
                                parsed == 0 -> 0
                                resolvedOffset < 0 -> -parsed
                                else -> parsed
                            }
                            applyRule(EndpointRuleMode.ADHAN, signed, resolvedMinutes)
                        }
                    },
                    onHourValueChange = { raw ->
                        hourText = normalizeEndpointNumber(raw).take(2)
                        updateFixedDraft()
                    },
                    onMinuteValueChange = { raw ->
                        minuteText = normalizeEndpointNumber(raw).take(2)
                        updateFixedDraft()
                    },
                )
                when {
                    fixed && !clockValid -> EditorError(stringResource(R.string.prayer_editor_invalid_time))
                    !fixed && !relativeValid -> EditorError(stringResource(R.string.prayer_editor_invalid_number))
                    !windowValid -> EditorError(stringResource(R.string.prayer_silence_end_before_start_error))
                }
                EndpointResultPreview(window)
            }
            PrayerEditorActions(
                canSave = valid && !saved,
                onSave = {
                    if (valid && !saved) {
                        saved = true
                        focusManager.clearFocus()
                        if (!onSave(draft.withLegacyDurationSynced(prayerTime))) {
                            saved = false
                        }
                    }
                },
                onCancel = { focusManager.clearFocus(); onDismiss() },
            )
        }
    }
}

@Composable
private fun EditorError(message: String) {
    Text(message, color = SilenceRed, fontSize = 14.sp, lineHeight = 19.sp)
}

/** Compact date-aware interval preview shown below the active inputs. */
@Composable
private fun EndpointResultPreview(window: PrayerTimelineWindow) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HorizontalDivider(color = GreenPrimaryDark.copy(alpha = 0.10f))
        Text(
            stringResource(R.string.prayer_editor_result_label),
            color = TextMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PreviewEndpoint(
                label = stringResource(R.string.prayer_silence_start_label),
                minutes = window.startMinutes,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (window.durationMinutes < 0) {
                    "—"
                } else {
                    pluralStringResource(
                        R.plurals.prayer_silence_duration_minutes,
                        window.durationMinutes,
                        window.durationMinutes,
                    )
                },
                color = PrayerSilencePalette.InteractiveTeal,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
            )
            PreviewEndpoint(
                label = stringResource(R.string.prayer_silence_end_label),
                minutes = window.endMinutes,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PreviewEndpoint(label: String, minutes: Int, modifier: Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, color = TextMuted, fontSize = 12.sp)
        Text(
            text = silenceClockText(minutes),
            color = GreenPrimaryDark,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
        )
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

private fun clockField(value: Int): String = value.toString().padStart(2, '0')

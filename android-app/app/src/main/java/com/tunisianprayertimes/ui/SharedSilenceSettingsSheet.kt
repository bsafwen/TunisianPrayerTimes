package com.tunisianprayertimes.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.background
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerSilenceConfig
import com.tunisianprayertimes.PrayerTime
import com.tunisianprayertimes.R
import com.tunisianprayertimes.ui.theme.SilenceRed
import kotlin.math.abs

private const val SharedOffsetLimitMinutes = 10_080

/** The shared start/end rule draft applied to the selected prayers. */
internal data class SharedSilenceRuleDraft(
    val startMode: EndpointRuleMode = EndpointRuleMode.ADHAN,
    val startOffsetMinutes: Int = 0,
    val startClockMinutes: Int = 0,
    val endMode: EndpointRuleMode = EndpointRuleMode.ADHAN,
    val endOffsetMinutes: Int = 30,
    val endClockMinutes: Int = 0,
)

/** Applies this rule to one prayer without touching its enabled state. */
internal fun SharedSilenceRuleDraft.applyTo(
    base: PrayerSilenceConfig,
    prayerTime: PrayerTime,
): PrayerSilenceConfig {
    val started = base.withStartRule(startMode, startOffsetMinutes, startClockMinutes)
    val ended = started.withEndRule(endMode, endOffsetMinutes, endClockMinutes)
    return ended.withLegacyDurationSynced(prayerTime)
}

private enum class SharedClockTarget { START, END }

/**
 * Compact shared-rule editor. Nothing changes until the explicit apply action;
 * per-prayer enabled states are preserved by the caller.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SharedSilenceSettingsSheet(
    prayerNames: List<Pair<Prayer, String>>,
    prayerTimes: Map<Prayer, PrayerTime>,
    initialDraft: SharedSilenceRuleDraft,
    onDismiss: () -> Unit,
    onApply: (List<Prayer>, SharedSilenceRuleDraft) -> Unit,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var selected by rememberSaveable { mutableStateOf(prayerNames.map { it.first.name }) }
    var startMode by rememberSaveable { mutableStateOf(initialDraft.startMode.name) }
    var startOffsetText by rememberSaveable { mutableStateOf(initialDraft.startOffsetMinutes.toString()) }
    var startClock by rememberSaveable { mutableIntStateOf(initialDraft.startClockMinutes) }
    var endMode by rememberSaveable { mutableStateOf(initialDraft.endMode.name) }
    var endOffsetText by rememberSaveable { mutableStateOf(initialDraft.endOffsetMinutes.toString()) }
    var endClock by rememberSaveable { mutableIntStateOf(initialDraft.endClockMinutes) }
    var clockTarget by remember { mutableStateOf<SharedClockTarget?>(null) }

    val startAdhan = startMode == EndpointRuleMode.ADHAN.name
    val endAdhan = endMode == EndpointRuleMode.ADHAN.name
    val startOffset = startOffsetText.trim().toIntOrNull()?.takeIf { abs(it) <= SharedOffsetLimitMinutes }
    val endOffset = endOffsetText.trim().toIntOrNull()?.takeIf { abs(it) <= SharedOffsetLimitMinutes }
    val offsetsValid = (!startAdhan || startOffset != null) && (!endAdhan || endOffset != null)
    val draft = SharedSilenceRuleDraft(
        startMode = EndpointRuleMode.valueOf(startMode),
        startOffsetMinutes = startOffset ?: 0,
        startClockMinutes = startClock,
        endMode = EndpointRuleMode.valueOf(endMode),
        endOffsetMinutes = endOffset ?: 0,
        endClockMinutes = endClock,
    )
    val selectedPrayers = prayerNames.map { it.first }.filter { it.name in selected }
    val invalidPrayer = if (offsetsValid && selected.isNotEmpty()) {
        selectedPrayers.firstOrNull { prayer ->
            val time = prayerTimes[prayer] ?: return@firstOrNull false
            !configResolvesToValidWindow(time, draft.applyTo(PrayerSilenceConfig(), time))
        }
    } else {
        null
    }
    val valid = offsetsValid && selected.isNotEmpty() && invalidPrayer == null

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = PrayerSilencePalette.Surface,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.prayer_silence_shared_title),
                        color = PrayerSilencePalette.PrimaryText,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.prayer_silence_shared_body),
                        color = PrayerSilencePalette.PrimaryText.copy(alpha = 0.7f),
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.prayer_silence_shared_select_prayers),
                        color = PrayerSilencePalette.PrimaryText,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                    )
                    prayerNames.forEach { (prayer, name) ->
                        val checked = prayer.name in selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .toggleable(
                                    value = checked,
                                    role = Role.Checkbox,
                                    onValueChange = { isChecked ->
                                        selected = if (isChecked) {
                                            selected + prayer.name
                                        } else {
                                            selected - prayer.name
                                        }
                                    },
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = null,
                                colors = CheckboxDefaults.colors(
                                    checkedColor = PrayerSilencePalette.InteractiveTeal,
                                    uncheckedColor = PrayerSilencePalette.InactiveTrack,
                                    checkmarkColor = Color.White,
                                ),
                            )
                            Text(
                                text = name,
                                color = PrayerSilencePalette.PrimaryText,
                                fontSize = 15.sp,
                            )
                        }
                    }
                }
                HorizontalDivider(color = PrayerSilencePalette.SoftBorder)
                SharedEndpointRuleEditor(
                    title = stringResource(R.string.prayer_silence_shared_start_rule),
                    adhanMode = startAdhan,
                    offsetValue = startOffset ?: 0,
                    clockMinutes = startClock,
                    onModeChange = { adhan -> startMode = if (adhan) EndpointRuleMode.ADHAN.name else EndpointRuleMode.FIXED_TIME.name },
                    onOffsetChange = { startOffsetText = it },
                    onClockClick = { clockTarget = SharedClockTarget.START },
                )
                SharedEndpointRuleEditor(
                    title = stringResource(R.string.prayer_silence_shared_end_rule),
                    adhanMode = endAdhan,
                    offsetValue = endOffset ?: 0,
                    clockMinutes = endClock,
                    onModeChange = { adhan -> endMode = if (adhan) EndpointRuleMode.ADHAN.name else EndpointRuleMode.FIXED_TIME.name },
                    onOffsetChange = { endOffsetText = it },
                    onClockClick = { clockTarget = SharedClockTarget.END },
                )
                if (startAdhan || endAdhan) {
                    Text(
                        text = stringResource(R.string.prayer_silence_shared_relative_help),
                        color = PrayerSilencePalette.PrimaryText.copy(alpha = 0.7f),
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                    )
                }
                if (!startAdhan || !endAdhan) {
                    Text(
                        text = stringResource(R.string.prayer_silence_shared_fixed_help),
                        color = PrayerSilencePalette.PrimaryText.copy(alpha = 0.7f),
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                    )
                }
                if (!offsetsValid) {
                    Text(
                        text = stringResource(R.string.prayer_editor_invalid_number),
                        color = SilenceRed,
                        fontSize = 14.sp,
                    )
                }
                if (invalidPrayer != null) {
                    Text(
                        text = stringResource(
                            R.string.prayer_silence_shared_invalid_prayer,
                            prayerNames.firstOrNull { it.first == invalidPrayer }?.second ?: invalidPrayer.name,
                        ),
                        color = SilenceRed,
                        fontSize = 14.sp,
                    )
                }
                if (selected.isEmpty()) {
                    Text(
                        text = stringResource(R.string.prayer_silence_shared_empty),
                        color = SilenceRed,
                        fontSize = 14.sp,
                    )
                }
            }
            HorizontalDivider(color = PrayerSilencePalette.SoftBorder)
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                val applyLabel = stringResource(R.string.prayer_silence_shared_apply)
                val cancelLabel = stringResource(R.string.prayer_silence_shared_cancel)
                val apply: @Composable (Modifier) -> Unit = { modifier ->
                    Button(
                        onClick = {
                            if (valid) {
                                focusManager.clearFocus()
                                onApply(selectedPrayers, draft)
                            }
                        },
                        enabled = valid,
                        modifier = modifier.heightIn(min = 48.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrayerSilencePalette.InteractiveTeal,
                        ),
                    ) {
                        Text(applyLabel, fontWeight = FontWeight.Bold)
                    }
                }
                val cancel: @Composable (Modifier) -> Unit = { modifier ->
                    TextButton(
                        onClick = { focusManager.clearFocus(); onDismiss() },
                        modifier = modifier.heightIn(min = 48.dp),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(cancelLabel, color = PrayerSilencePalette.PrimaryText)
                    }
                }
                if (maxWidth.value / LocalDensity.current.fontScale >= 280f) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        apply(Modifier.weight(1.4f))
                        cancel(Modifier.weight(1f))
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        apply(Modifier.fillMaxWidth())
                        cancel(Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }

    clockTarget?.let { target ->
        DisposableEffect(target) {
            val clock = Math.floorMod(if (target == SharedClockTarget.START) startClock else endClock, 24 * 60)
            val picker = TimePickerDialog(
                context,
                { _, hour, minute ->
                    if (target == SharedClockTarget.START) {
                        startClock = hour * 60 + minute
                    } else {
                        endClock = hour * 60 + minute
                    }
                    clockTarget = null
                },
                clock / 60,
                clock % 60,
                true,
            )
            picker.setTitle(
                context.getString(
                    if (target == SharedClockTarget.START) {
                        R.string.prayer_editor_choose_start_clock
                    } else {
                        R.string.prayer_editor_choose_end_clock
                    },
                ),
            )
            picker.setOnDismissListener { clockTarget = null }
            picker.show()
            onDispose {
                picker.setOnDismissListener(null)
                picker.dismiss()
            }
        }
    }
}

@Composable
private fun SharedEndpointRuleEditor(
    title: String,
    adhanMode: Boolean,
    offsetValue: Int,
    clockMinutes: Int,
    onModeChange: (Boolean) -> Unit,
    onOffsetChange: (String) -> Unit,
    onClockClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            color = PrayerSilencePalette.PrimaryText,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
        )
        EndpointModeChoices(
            choices = listOf(
                stringResource(R.string.prayer_silence_mode_adhan),
                stringResource(R.string.prayer_silence_mode_fixed),
            ),
            selected = if (adhanMode) 0 else 1,
            enabled = true,
            onSelected = { index -> onModeChange(index == 0) },
        )
        if (adhanMode) {
            EndpointDirectionChoices(
                selected = offsetDirection(offsetValue),
                enabled = true,
                onSelected = { direction ->
                    val magnitude = abs(offsetValue).coerceAtLeast(1)
                    val target = when (direction) {
                        EndpointDirection.BEFORE -> -magnitude
                        EndpointDirection.AT_ADHAN -> 0
                        EndpointDirection.AFTER -> magnitude
                    }
                    onOffsetChange(target.toString())
                },
            )
            EndpointMinuteStepper(
                value = abs(offsetValue).toString(),
                enabled = true,
                decreaseLabel = stringResource(R.string.prayer_silence_decrease_minute),
                increaseLabel = stringResource(R.string.prayer_silence_increase_minute),
                unitLabel = stringResource(R.string.prayer_editor_minutes_unit),
                onDecrease = { onOffsetChange((offsetValue - 1).toString()) },
                onIncrease = { onOffsetChange((offsetValue + 1).toString()) },
                onValueChange = { text ->
                    val digits = normalizeEndpointNumber(text)
                    val parsed = digits.toIntOrNull()
                    val signed = when {
                        parsed == null -> digits
                        parsed == 0 -> "0"
                        offsetValue < 0 -> (-parsed).toString()
                        else -> parsed.toString()
                    }
                    onOffsetChange(signed)
                },
            )
        } else {
            EndpointClockButton(
                label = stringResource(R.string.prayer_silence_mode_fixed),
                minutes = clockMinutes,
                enabled = true,
                onClick = onClockClick,
            )
        }
    }
}

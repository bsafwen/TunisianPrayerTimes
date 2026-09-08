package com.tunisianprayertimes.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
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
import com.tunisianprayertimes.ui.theme.TextDark
import com.tunisianprayertimes.ui.theme.TextMuted
import java.util.Locale

private val PrayerEditorConfigSaver = listSaver<PrayerSilenceConfig, Any>(
    save = {
        listOf(it.mode.name, it.afterMinutes, it.fixedHour, it.fixedMinute,
            it.delayMode.name, it.delayMinutes, it.delayFixedHour, it.delayFixedMinute)
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
        )
    },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PrayerSilenceEditorSheet(
    prayer: Prayer,
    prayerName: String,
    prayerTime: PrayerTime,
    config: PrayerSilenceConfig,
    onDismiss: () -> Unit,
    onSave: (PrayerSilenceConfig) -> Unit,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val durationFocusRequester = remember { FocusRequester() }
    var draft by rememberSaveable(prayer, config, stateSaver = PrayerEditorConfigSaver) {
        mutableStateOf(config)
    }
    var offsetText by rememberSaveable(prayer, config) { mutableStateOf(config.delayMinutes.toString()) }
    var durationText by rememberSaveable(prayer, config) { mutableStateOf(config.afterMinutes.toString()) }
    var modeError by rememberSaveable(prayer, config) { mutableStateOf(false) }
    var saved by remember(prayer, config) { mutableStateOf(false) }
    var clockTarget by remember(prayer, config) { mutableStateOf<PrayerEditorClockTarget?>(null) }
    val parsedOffset = offsetText.toIntOrNull()
    val parsedDuration = durationText.toIntOrNull()?.takeIf { it >= 0 }
    val offsetValid = draft.delayMode != DelayMode.MINUTES || parsedOffset != null
    val durationValid = draft.mode != SilenceMode.DURATION || parsedDuration != null
    val window = prayerEditorValidWindow(prayerTime, draft)
    val valid = offsetValid && durationValid && window != null

    fun switchStartMode(mode: DelayMode) {
        if (mode == draft.delayMode) return
        val converted = if (valid) {
            val conversionConfig = draft.copy(
                delayMode = mode,
                // This explicit mode choice activates the clock; the mapper then
                // replaces the seed with the current window's representable time.
                delayFixedHour = if (mode == DelayMode.FIXED_TIME) 0 else draft.delayFixedHour,
                delayFixedMinute = if (mode == DelayMode.FIXED_TIME) 0 else draft.delayFixedMinute,
            )
            prayerTimelineConfigForWindow(prayerTime, conversionConfig, window)
        } else null
        if (converted == null) {
            modeError = true
            return
        }
        // Switching the start mode must not materialize an unrelated unset end clock.
        val next = draft.copy(
            delayMode = mode,
            delayMinutes = converted.delayMinutes,
            delayFixedHour = converted.delayFixedHour,
            delayFixedMinute = converted.delayFixedMinute,
        )
        if (prayerEditorValidWindow(prayerTime, next) != window) {
            modeError = true
            return
        }
        draft = next
        offsetText = next.delayMinutes.toString()
        modeError = false
        focusManager.clearFocus()
    }

    fun switchEndMode(mode: SilenceMode) {
        if (mode == draft.mode) return
        val converted = if (valid) {
            val conversionConfig = draft.copy(
                mode = mode,
                fixedHour = if (mode == SilenceMode.FIXED_TIME) 0 else draft.fixedHour,
                fixedMinute = if (mode == SilenceMode.FIXED_TIME) 0 else draft.fixedMinute,
            )
            prayerTimelineConfigForWindow(prayerTime, conversionConfig, window)
        } else null
        if (converted == null) {
            modeError = true
            return
        }
        val next = draft.copy(
            mode = mode,
            afterMinutes = converted.afterMinutes,
            fixedHour = converted.fixedHour,
            fixedMinute = converted.fixedMinute,
        )
        if (prayerEditorValidWindow(prayerTime, next) != window) {
            modeError = true
            return
        }
        draft = next
        durationText = next.afterMinutes.toString()
        modeError = false
        focusManager.clearFocus()
    }

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
                        stringResource(R.string.prayer_editor_title, prayerName),
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
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.prayer_editor_start), fontWeight = FontWeight.Bold, fontSize = 17.sp, color = GreenPrimaryDark)
                    PrayerEditorModeChoices(
                        choices = listOf(stringResource(R.string.prayer_editor_offset_mode), stringResource(R.string.prayer_editor_start_clock_mode)),
                        selected = if (draft.delayMode == DelayMode.MINUTES) 0 else 1,
                        onSelected = { switchStartMode(if (it == 0) DelayMode.MINUTES else DelayMode.FIXED_TIME) },
                    )
                    if (draft.delayMode == DelayMode.MINUTES) {
                        PrayerEditorNumberField(
                            label = stringResource(R.string.prayer_editor_offset_label),
                            value = offsetText,
                            onValueChange = { text ->
                                offsetText = text
                                text.toIntOrNull()?.let { draft = draft.copy(delayMinutes = it) }
                                modeError = false
                            },
                            valid = offsetValid,
                            signed = true,
                            imeAction = if (draft.mode == SilenceMode.DURATION) ImeAction.Next else ImeAction.Done,
                            onNext = if (draft.mode == SilenceMode.DURATION) {
                                { durationFocusRequester.requestFocus() }
                            } else null,
                        )
                        Text(stringResource(R.string.prayer_editor_offset_help), fontSize = 13.sp, color = TextMuted)
                    } else {
                        PrayerEditorClockButton(
                            label = stringResource(R.string.prayer_editor_choose_start_clock),
                            minutes = prayerEditorPickerMinutes(PrayerEditorClockTarget.START, prayerTime, draft, window),
                            onClick = { focusManager.clearFocus(); clockTarget = PrayerEditorClockTarget.START },
                        )
                        Text(stringResource(R.string.prayer_editor_start_clock_help), fontSize = 13.sp, color = TextMuted)
                    }
                }
                HorizontalDivider(color = GreenPrimaryDark.copy(alpha = 0.10f))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.prayer_editor_end), fontWeight = FontWeight.Bold, fontSize = 17.sp, color = GreenPrimaryDark)
                    PrayerEditorModeChoices(
                        choices = listOf(stringResource(R.string.prayer_editor_duration_mode), stringResource(R.string.prayer_editor_end_clock_mode)),
                        selected = if (draft.mode == SilenceMode.DURATION) 0 else 1,
                        onSelected = { switchEndMode(if (it == 0) SilenceMode.DURATION else SilenceMode.FIXED_TIME) },
                    )
                    if (draft.mode == SilenceMode.DURATION) {
                        PrayerEditorNumberField(
                            label = stringResource(R.string.prayer_editor_duration_label),
                            value = durationText,
                            onValueChange = { text ->
                                durationText = text
                                text.toIntOrNull()?.takeIf { it >= 0 }?.let { draft = draft.copy(afterMinutes = it) }
                                modeError = false
                            },
                            valid = durationValid,
                            modifier = Modifier.focusRequester(durationFocusRequester)
                                .testTag(TestTags.durationInput(prayer.name)),
                        )
                    } else {
                        PrayerEditorClockButton(
                            label = stringResource(R.string.prayer_editor_choose_end_clock),
                            minutes = prayerEditorPickerMinutes(PrayerEditorClockTarget.END, prayerTime, draft, window),
                            onClick = { focusManager.clearFocus(); clockTarget = PrayerEditorClockTarget.END },
                        )
                        Text(stringResource(R.string.prayer_editor_end_clock_help), fontSize = 13.sp, color = TextMuted)
                    }
                }
                if (modeError) {
                    Text(stringResource(R.string.prayer_editor_mode_unavailable), color = SilenceRed, fontSize = 14.sp)
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

    clockTarget?.let { target ->
        DisposableEffect(target) {
            val clock = Math.floorMod(prayerEditorPickerMinutes(target, prayerTime, draft, window), 1440)
            val picker = TimePickerDialog(context, { _, hour, minute ->
                draft = if (target == PrayerEditorClockTarget.START) {
                    draft.copy(delayFixedHour = hour, delayFixedMinute = minute)
                } else {
                    draft.copy(fixedHour = hour, fixedMinute = minute)
                }
                modeError = false
                clockTarget = null
            }, clock / 60, clock % 60, true)
            picker.setTitle(context.getString(if (target == PrayerEditorClockTarget.START) R.string.prayer_editor_choose_start_clock else R.string.prayer_editor_choose_end_clock))
            picker.setOnDismissListener { clockTarget = null }
            picker.show()
            onDispose { picker.setOnDismissListener(null); picker.dismiss() }
        }
    }
}

private enum class PrayerEditorClockTarget { START, END }

private fun prayerEditorPickerMinutes(
    target: PrayerEditorClockTarget,
    prayerTime: PrayerTime,
    config: PrayerSilenceConfig,
    window: PrayerTimelineWindow?,
): Int = when {
    target == PrayerEditorClockTarget.START && config.delayFixedHour in 0..23 && config.delayFixedMinute in 0..59 ->
        config.delayFixedHour * 60 + config.delayFixedMinute
    target == PrayerEditorClockTarget.END && config.fixedHour in 0..23 && config.fixedMinute in 0..59 ->
        config.fixedHour * 60 + config.fixedMinute
    window != null -> if (target == PrayerEditorClockTarget.START) window.startMinutes else window.endMinutes
    else -> prayerTime.hour * 60 + prayerTime.minute
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
                when (day) {
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
                    Text(stringResource(R.string.prayer_editor_save), fontWeight = FontWeight.Bold)
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

@Composable
private fun PrayerEditorModeChoices(choices: List<String>, selected: Int, onSelected: (Int) -> Unit) {
    BoxWithConstraints(
        Modifier.fillMaxWidth().selectableGroup()
            .background(GreenPrimaryDark.copy(alpha = 0.035f), RoundedCornerShape(12.dp))
            .padding(4.dp),
    ) {
        if (maxWidth.value / LocalDensity.current.fontScale >= 280f) {
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                choices.forEachIndexed { index, label ->
                    PrayerEditorModeOption(label, selected == index, { onSelected(index) }, Modifier.weight(1f).fillMaxHeight())
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                choices.forEachIndexed { index, label ->
                    PrayerEditorModeOption(label, selected == index, { onSelected(index) }, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun PrayerEditorModeOption(label: String, selected: Boolean, onSelect: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(9.dp)
    Row(
        modifier.heightIn(min = 48.dp).clip(shape)
            .background(if (selected) Color.White else Color.Transparent)
            .border(1.dp, if (selected) GreenPrimaryDark.copy(alpha = 0.30f) else Color.Transparent, shape)
            .selectable(selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(
            selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(selectedColor = GreenPrimaryDark, unselectedColor = TextMuted),
        )
        Text(
            label,
            modifier = Modifier.weight(1f),
            color = if (selected) GreenPrimaryDark else TextDark,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            lineHeight = 20.sp,
        )
    }
}

@Composable
private fun PrayerEditorNumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    valid: Boolean,
    modifier: Modifier = Modifier,
    signed: Boolean = false,
    imeAction: ImeAction = ImeAction.Done,
    onNext: (() -> Unit)? = null,
) {
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = TextDark, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        BasicTextField(
            value = value,
            onValueChange = { text -> onValueChange(normalizePrayerEditorNumber(text)) },
            modifier = modifier.fillMaxWidth().heightIn(min = 56.dp)
                .onFocusChanged { focused = it.isFocused }
                .background(if (focused) Color.White else GreenPrimaryDark.copy(alpha = 0.025f), shape)
                .border(
                    if (focused) 2.dp else 1.dp,
                    when {
                        !valid -> SilenceRed
                        focused -> GreenPrimaryDark
                        else -> GreenPrimaryDark.copy(alpha = 0.22f)
                    },
                    shape,
                )
                .semantics { contentDescription = label },
            textStyle = TextStyle(fontSize = 20.sp, color = TextDark, textDirection = TextDirection.Ltr, textAlign = TextAlign.Start),
            keyboardOptions = KeyboardOptions(keyboardType = if (signed) KeyboardType.Phone else KeyboardType.Number, imeAction = imeAction),
            keyboardActions = KeyboardActions(
                onDone = { focusManager.clearFocus() },
                onNext = { onNext?.invoke() ?: focusManager.clearFocus() },
            ),
            singleLine = true,
            decorationBox = { innerTextField ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.weight(1f)) { innerTextField() }
                    Text(stringResource(R.string.prayer_editor_minutes_unit), color = TextMuted, fontSize = 13.sp)
                }
            },
        )
        if (!valid) Text(stringResource(R.string.prayer_editor_invalid_number), color = SilenceRed, fontSize = 13.sp)
    }
}

@Composable
private fun PrayerEditorClockButton(label: String, minutes: Int, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = GreenPrimaryDark, fontSize = 14.sp, textAlign = TextAlign.Center)
            Text(prayerEditorClockText(minutes), color = GreenPrimaryDark, fontSize = 22.sp, style = TextStyle(textDirection = TextDirection.Ltr))
        }
    }
}

private fun prayerEditorClockText(minutes: Int): String {
    val clock = Math.floorMod(minutes, 1440)
    return String.format(Locale.US, "%02d:%02d", clock / 60, clock % 60)
}

private fun normalizePrayerEditorNumber(input: String): String {
    val normalized = buildString {
        input.forEach { char ->
            append(when (char) {
                in '\u0660'..'\u0669' -> '0' + (char - '\u0660')
                in '\u06F0'..'\u06F9' -> '0' + (char - '\u06F0')
                '\u2212' -> '-'
                else -> char
            })
        }
    }.filterNot { char ->
        char == '\u061C' || char == '\u200E' || char == '\u200F' ||
            char in '\u202A'..'\u202E' || char in '\u2066'..'\u2069'
    }.trim()
    val digits = normalized.filter { it in '0'..'9' }
    return if (normalized.startsWith('-')) "-$digits" else digits
}

/** Check arithmetic before calling the Int-based timeline resolver. */
private fun prayerEditorValidWindow(prayerTime: PrayerTime, config: PrayerSilenceConfig): PrayerTimelineWindow? {
    val fixedStart = config.delayMode == DelayMode.FIXED_TIME && config.delayFixedHour >= 0 && config.delayFixedMinute >= 0
    val fixedEnd = config.mode == SilenceMode.FIXED_TIME && config.fixedHour >= 0 && config.fixedMinute >= 0
    if (fixedStart && (config.delayFixedHour !in 0..23 || config.delayFixedMinute !in 0..59)) return null
    if (fixedEnd && (config.fixedHour !in 0..23 || config.fixedMinute !in 0..59)) return null
    val start = if (fixedStart) config.delayFixedHour * 60L + config.delayFixedMinute
        else prayerTime.hour * 60L + prayerTime.minute + config.delayMinutes
    val end = if (fixedEnd) {
        val clock = config.fixedHour * 60L + config.fixedMinute
        if (clock < start) clock + 1440L else clock
    } else start + config.afterMinutes
    val bounds = Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
    if (start !in bounds || end !in bounds || end < start || end - start > Int.MAX_VALUE) return null
    return resolvePrayerTimelineWindow(prayerTime, config)
}

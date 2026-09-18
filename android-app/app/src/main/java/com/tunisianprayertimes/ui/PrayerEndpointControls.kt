package com.tunisianprayertimes.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
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
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.PrayerSilenceConfig
import com.tunisianprayertimes.PrayerTime
import com.tunisianprayertimes.R
import com.tunisianprayertimes.ui.theme.SilenceRed
import kotlin.math.abs

private const val EndpointOffsetLimitMinutes = 10_080

/**
 * The one start/end rule editor shared by the expanded prayer rows and the
 * precision sheet, so both expose identical controls and conversions.
 */
@Composable
internal fun EndpointRuleControls(
    endpoint: SilenceEndpoint,
    config: PrayerSilenceConfig,
    prayerTime: PrayerTime,
    window: PrayerTimelineWindow,
    enabled: Boolean,
    onConfigChange: (PrayerSilenceConfig) -> Unit,
    modifier: Modifier = Modifier,
    showTitle: Boolean = true,
    prayerName: String? = null,
    compact: Boolean = false,
    onOpenEditor: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val prayerMinutes = prayerMinutesOfDay(prayerTime)
    val start = endpoint == SilenceEndpoint.START
    val fixed = (if (start) config.startRuleMode() else config.endRuleMode()) == EndpointRuleMode.FIXED_TIME
    val resolvedMinutes = if (start) window.startMinutes else window.endMinutes
    val resolvedOffset = resolvedMinutes - prayerMinutes
    val title = stringResource(
        if (start) R.string.prayer_silence_start_group else R.string.prayer_silence_end_group,
    )
    val clockPickerLabel = stringResource(
        if (start) R.string.prayer_editor_choose_start_clock else R.string.prayer_editor_choose_end_clock,
    )
    val clockPickerTitle = if (prayerName.isNullOrBlank()) {
        clockPickerLabel
    } else {
        stringResource(R.string.prayer_editor_clock_title, prayerName, clockPickerLabel)
    }
    var error by remember(endpoint, config) { mutableStateOf(false) }
    var clockOpen by remember(endpoint) { mutableStateOf(false) }
    var minuteText by remember(endpoint, resolvedOffset, fixed) {
        mutableStateOf(abs(resolvedOffset).toString())
    }

    fun ruleFor(mode: EndpointRuleMode, offsetMinutes: Int, clockMinutes: Int): PrayerSilenceConfig =
        if (start) {
            config.withStartRule(mode, offsetMinutes, clockMinutes)
        } else {
            config.withEndRule(mode, offsetMinutes, clockMinutes)
        }

    fun clampedOffset(target: Int): Int {
        val otherOffset = if (start) {
            window.endMinutes - prayerMinutes
        } else {
            window.startMinutes - prayerMinutes
        }
        val clamped = if (start) {
            target.coerceAtMost(otherOffset - 1)
        } else {
            target.coerceAtLeast(otherOffset + 1)
        }
        return clamped.coerceIn(-EndpointOffsetLimitMinutes, EndpointOffsetLimitMinutes)
    }

    fun commit(candidate: PrayerSilenceConfig) {
        if (!enabled) return
        val synced = candidate.withLegacyDurationSynced(prayerTime)
        if (configResolvesToValidWindow(prayerTime, synced)) {
            error = false
            onConfigChange(synced)
        } else {
            error = true
        }
    }

    fun commitOffset(target: Int) {
        if (!enabled) return
        val offset = clampedOffset(target)
        minuteText = abs(offset).toString()
        commit(ruleFor(EndpointRuleMode.ADHAN, offset, resolvedMinutes))
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showTitle) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = PrayerSilencePalette.PrimaryText,
            )
        }
        EndpointModeChoices(
            choices = listOf(
                stringResource(R.string.prayer_silence_mode_adhan),
                stringResource(R.string.prayer_silence_mode_fixed),
            ),
            selected = if (fixed) 1 else 0,
            enabled = enabled,
            onSelected = { index ->
                val mode = if (index == 0) EndpointRuleMode.ADHAN else EndpointRuleMode.FIXED_TIME
                if (mode == EndpointRuleMode.FIXED_TIME) {
                    commit(ruleFor(mode, resolvedOffset, resolvedMinutes))
                } else {
                    commit(ruleFor(mode, clampedOffset(resolvedOffset), resolvedMinutes))
                }
            },
        )
        if (compact) {
            CompactEndpointValue(
                text = if (fixed) {
                    stringResource(R.string.prayer_silence_inline_fixed_value, silenceClockText(resolvedMinutes))
                } else {
                    endpointRuleCaption(config, window, endpoint, prayerMinutes)
                },
                enabled = enabled,
                description = stringResource(
                    if (start) R.string.prayer_silence_start_field_desc else R.string.prayer_silence_end_field_desc,
                    prayerName ?: title,
                    if (fixed) {
                        stringResource(R.string.prayer_silence_fixed_time_desc, prayerClockText(resolvedMinutes))
                    } else {
                        endpointRelationText(resolvedOffset)
                    },
                ),
                onClick = { onOpenEditor?.invoke() },
            )
        } else if (fixed) {
            EndpointClockButton(
                label = stringResource(R.string.prayer_silence_mode_fixed),
                minutes = resolvedMinutes,
                enabled = enabled,
                onClick = { focusManager.clearFocus(); clockOpen = true },
            )
        } else {
            EndpointDirectionChoices(
                selected = offsetDirection(resolvedOffset),
                enabled = enabled,
                onSelected = { direction ->
                    val magnitude = abs(resolvedOffset).coerceAtLeast(1)
                    commitOffset(
                        when (direction) {
                            EndpointDirection.BEFORE -> -magnitude
                            EndpointDirection.AT_ADHAN -> 0
                            EndpointDirection.AFTER -> magnitude
                        },
                    )
                },
            )
            EndpointMinuteStepper(
                value = minuteText,
                enabled = enabled,
                decreaseLabel = stringResource(R.string.prayer_silence_decrease_minute),
                increaseLabel = stringResource(R.string.prayer_silence_increase_minute),
                unitLabel = stringResource(R.string.prayer_editor_minutes_unit),
                onDecrease = { commitOffset(resolvedOffset - 1) },
                onIncrease = { commitOffset(resolvedOffset + 1) },
                onValueChange = { text ->
                    val digits = normalizeEndpointNumber(text)
                    minuteText = digits
                    val parsed = digits.toIntOrNull()
                    if (parsed != null) {
                        val signed = when {
                            parsed == 0 -> 0
                            resolvedOffset < 0 -> -parsed
                            else -> parsed
                        }
                        commitOffset(signed)
                    }
                },
            )
        }
        if (error) {
            Text(
                text = stringResource(R.string.prayer_silence_end_before_start_error),
                color = SilenceRed,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }
    }

    if (clockOpen) {
        DisposableEffect(endpoint, resolvedMinutes) {
            val clock = Math.floorMod(resolvedMinutes, 24 * 60)
            val picker = TimePickerDialog(
                context,
                { _, hour, minute ->
                    commit(ruleFor(EndpointRuleMode.FIXED_TIME, resolvedOffset, hour * 60 + minute))
                    clockOpen = false
                },
                clock / 60,
                clock % 60,
                true,
            )
            picker.setTitle(clockPickerTitle)
            picker.setOnDismissListener { clockOpen = false }
            picker.show()
            onDispose {
                picker.setOnDismissListener(null)
                picker.dismiss()
            }
        }
    }
}

/** Compact inline rule text; tapping opens the endpoint's precision editor. */
@Composable
private fun CompactEndpointValue(
    text: String,
    enabled: Boolean,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = description,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp, vertical = 10.dp)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = PrayerSilencePalette.PrimaryText,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            lineHeight = 21.sp,
            maxLines = 2,
        )
    }
}

internal enum class EndpointDirection { BEFORE, AT_ADHAN, AFTER }

internal fun offsetDirection(offsetMinutes: Int): EndpointDirection = when {
    offsetMinutes < 0 -> EndpointDirection.BEFORE
    offsetMinutes > 0 -> EndpointDirection.AFTER
    else -> EndpointDirection.AT_ADHAN
}

@Composable
internal fun EndpointDirectionChoices(
    selected: EndpointDirection,
    enabled: Boolean,
    onSelected: (EndpointDirection) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(
            EndpointDirection.BEFORE to R.string.prayer_silence_direction_before,
            EndpointDirection.AT_ADHAN to R.string.prayer_silence_direction_at,
            EndpointDirection.AFTER to R.string.prayer_silence_direction_after,
        ).forEach { (direction, labelRes) ->
            EndpointChoiceChip(
                label = stringResource(labelRes),
                selected = selected == direction,
                enabled = enabled,
                onClick = { onSelected(direction) },
            )
        }
    }
}

@Composable
internal fun EndpointMinuteStepper(
    value: String,
    enabled: Boolean,
    decreaseLabel: String,
    increaseLabel: String,
    unitLabel: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    onValueChange: (String) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            StepperButton(
                iconRes = R.drawable.ic_remove,
                description = decreaseLabel,
                enabled = enabled,
                onClick = onDecrease,
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                modifier = Modifier
                    .weight(1f)
                    .widthIn(min = 40.dp)
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(PrayerSilencePalette.TintedStrip)
                    .border(1.dp, PrayerSilencePalette.SoftBorder, RoundedCornerShape(10.dp))
                    .padding(horizontal = 8.dp)
                    .semantics { contentDescription = unitLabel },
                textStyle = TextStyle(
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = PrayerSilencePalette.PrimaryText,
                    textDirection = TextDirection.Ltr,
                    textAlign = TextAlign.Center,
                ),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                singleLine = true,
                decorationBox = { innerTextField ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Box { innerTextField() }
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = unitLabel,
                            color = PrayerSilencePalette.SecondaryText,
                            fontSize = 12.sp,
                        )
                    }
                },
            )
            StepperButton(
                iconRes = R.drawable.ic_add,
                description = increaseLabel,
                enabled = enabled,
                onClick = onIncrease,
            )
        }
    }
}

@Composable
private fun StepperButton(
    iconRes: Int,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(PrayerSilencePalette.TintedStrip)
            .border(1.dp, PrayerSilencePalette.SoftBorder, CircleShape)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = description,
                onClick = onClick,
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = PrayerSilencePalette.InteractiveTeal,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
internal fun EndpointClockButton(
    label: String,
    minutes: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(PrayerSilencePalette.TintedStrip)
            .border(1.dp, PrayerSilencePalette.SoftBorder, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(label, color = PrayerSilencePalette.SecondaryText, fontSize = 12.sp)
        Text(
            text = prayerClockText(minutes),
            color = PrayerSilencePalette.PrimaryText,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            style = TextStyle(textDirection = TextDirection.Ltr, fontFeatureSettings = "tnum"),
            maxLines = 1,
        )
    }
}

@Composable
private fun EndpointChoiceChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(9.dp)
    Box(
        modifier = Modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(
                if (selected) PrayerSilencePalette.InteractiveTeal.copy(alpha = 0.12f) else PrayerSilencePalette.TintedStrip,
                shape,
            )
            .border(
                1.dp,
                if (selected) PrayerSilencePalette.InteractiveTeal else PrayerSilencePalette.SoftBorder,
                shape,
            )
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) PrayerSilencePalette.PrimaryText else PrayerSilencePalette.SecondaryText,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

@Composable
internal fun EndpointModeChoices(
    choices: List<String>,
    selected: Int,
    enabled: Boolean,
    onSelected: (Int) -> Unit,
) {
    BoxWithConstraints(
        Modifier.fillMaxWidth().selectableGroup()
            .background(PrayerSilencePalette.TintedStrip, RoundedCornerShape(12.dp))
            .padding(4.dp),
    ) {
        if (maxWidth.value / LocalDensity.current.fontScale >= 220f) {
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                choices.forEachIndexed { index, label ->
                    EndpointModeOption(
                        label = label,
                        selected = selected == index,
                        enabled = enabled,
                        onClick = { onSelected(index) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                choices.forEachIndexed { index, label ->
                    EndpointModeOption(
                        label = label,
                        selected = selected == index,
                        enabled = enabled,
                        onClick = { onSelected(index) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun EndpointModeOption(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val shape = RoundedCornerShape(9.dp)
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(if (selected) PrayerSilencePalette.Surface else Color.Transparent)
            .border(
                1.dp,
                if (selected) PrayerSilencePalette.InteractiveTeal else Color.Transparent,
                shape,
            )
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) PrayerSilencePalette.PrimaryText else PrayerSilencePalette.SecondaryText,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            lineHeight = 17.sp,
        )
    }
}

@Composable
internal fun endpointRelationText(offsetMinutes: Int): String = when {
    offsetMinutes == 0 -> stringResource(R.string.prayer_silence_at_adhan)
    offsetMinutes < 0 -> pluralStringResource(
        R.plurals.prayer_silence_before_adhan,
        -offsetMinutes,
        -offsetMinutes,
    )
    else -> pluralStringResource(
        R.plurals.prayer_silence_after_adhan,
        offsetMinutes,
        offsetMinutes,
    )
}

/** Digits-only normalization shared by the stepper and the inline sheets. */
internal fun normalizeEndpointNumber(input: String): String {
    val normalized = buildString {
        input.forEach { char ->
            append(
                when (char) {
                    in '\u0660'..'\u0669' -> '0' + (char - '\u0660')
                    in '\u06F0'..'\u06F9' -> '0' + (char - '\u06F0')
                    '\u2212' -> '-'
                    else -> char
                },
            )
        }
    }.filterNot { char ->
        char == '\u061C' || char == '\u200E' || char == '\u200F' ||
            char in '\u202A'..'\u202E' || char in '\u2066'..'\u2069'
    }.trim()
    return normalized.filter { it in '0'..'9' }
}

package com.tunisianprayertimes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import kotlin.math.abs

internal const val EndpointOffsetLimitMinutes = 10_080

/**
 * The one endpoint rule editor used by the precision sheet. It is stateless:
 * the sheet owns the draft and the temporary text so invalid input stays local
 * until Apply validates it.
 */
@Composable
internal fun EndpointRuleEditor(
    endpoint: SilenceEndpoint,
    config: PrayerSilenceConfig,
    prayerTime: PrayerTime,
    window: PrayerTimelineWindow,
    enabled: Boolean,
    relativeValue: String,
    hourValue: String,
    minuteValue: String,
    onModeSelected: (EndpointRuleMode) -> Unit,
    onDirectionSelected: (EndpointDirection) -> Unit,
    onRelativeValueChange: (String) -> Unit,
    onHourValueChange: (String) -> Unit,
    onMinuteValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val start = endpoint == SilenceEndpoint.START
    val prayerMinutes = prayerMinutesOfDay(prayerTime)
    val mode = if (start) config.startRuleMode() else config.endRuleMode()
    val fixed = mode == EndpointRuleMode.FIXED_TIME
    val resolvedMinutes = if (start) window.startMinutes else window.endMinutes
    val resolvedOffset = resolvedMinutes - prayerMinutes
    val direction = offsetDirection(resolvedOffset)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        EndpointModeChoices(
            choices = listOf(
                stringResource(R.string.prayer_silence_mode_adhan),
                stringResource(R.string.prayer_silence_mode_fixed),
            ),
            selected = if (fixed) 1 else 0,
            enabled = enabled,
            onSelected = { index ->
                val target = if (index == 0) EndpointRuleMode.ADHAN else EndpointRuleMode.FIXED_TIME
                if (target != mode) onModeSelected(target)
            },
        )
        Text(
            text = if (fixed) {
                stringResource(R.string.prayer_silence_explain_fixed, silenceClockText(resolvedMinutes))
            } else if (resolvedOffset == 0) {
                stringResource(R.string.prayer_silence_explain_follows_adhan)
            } else {
                endpointRelationText(resolvedOffset)
            },
            color = PrayerSilencePalette.SecondaryText,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
        if (fixed) {
            EndpointInlineClockInput(
                hourValue = hourValue,
                minuteValue = minuteValue,
                enabled = enabled,
                onHourValueChange = onHourValueChange,
                onMinuteValueChange = onMinuteValueChange,
            )
        } else {
            EndpointDirectionChoices(
                selected = direction,
                enabled = enabled,
                onSelected = onDirectionSelected,
            )
            if (direction == EndpointDirection.AT_ADHAN) {
                CompactResolvedTime(resolvedMinutes)
            } else {
                EndpointMinuteStepper(
                    value = relativeValue,
                    enabled = enabled,
                    decreaseLabel = stringResource(R.string.prayer_silence_decrease_minute),
                    increaseLabel = stringResource(R.string.prayer_silence_increase_minute),
                    unitLabel = stringResource(R.string.prayer_editor_minutes_unit),
                    onDecrease = {
                        val current = relativeValue.toIntOrNull() ?: abs(resolvedOffset)
                        onRelativeValueChange((current - 1).coerceAtLeast(1).toString())
                    },
                    onIncrease = {
                        val current = relativeValue.toIntOrNull() ?: abs(resolvedOffset)
                        onRelativeValueChange((current + 1).toString())
                    },
                    onValueChange = onRelativeValueChange,
                )
            }
        }
    }
}

/** Compact resolved clock shown for at-adhan mode, which needs no numeric input. */
@Composable
private fun CompactResolvedTime(minutes: Int) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = silenceClockText(minutes),
            color = PrayerSilencePalette.PrimaryText,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * Compact inline hour/minute entry for fixed mode. No dialog and no auto-focus:
 * the keyboard appears only after the user deliberately taps one of the fields.
 */
@Composable
internal fun EndpointInlineClockInput(
    hourValue: String,
    minuteValue: String,
    enabled: Boolean,
    onHourValueChange: (String) -> Unit,
    onMinuteValueChange: (String) -> Unit,
) {
    val hourLabel = stringResource(R.string.prayer_editor_hour_label)
    val minuteLabel = stringResource(R.string.prayer_editor_minute_label)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ClockNumberField(
                value = hourValue,
                label = hourLabel,
                enabled = enabled,
                imeAction = ImeAction.Next,
                onValueChange = onHourValueChange,
                modifier = Modifier.weight(1f).widthIn(max = 96.dp),
            )
            Text(
                text = ":",
                color = PrayerSilencePalette.PrimaryText,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
            ClockNumberField(
                value = minuteValue,
                label = minuteLabel,
                enabled = enabled,
                imeAction = ImeAction.Done,
                onValueChange = onMinuteValueChange,
                modifier = Modifier.weight(1f).widthIn(max = 96.dp),
            )
        }
    }
}

@Composable
private fun ClockNumberField(
    value: String,
    label: String,
    enabled: Boolean,
    imeAction: ImeAction,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        modifier = modifier
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(PrayerSilencePalette.TintedStrip)
            .border(1.dp, PrayerSilencePalette.SoftBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp)
            .semantics { contentDescription = label },
        textStyle = TextStyle(
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
            color = PrayerSilencePalette.PrimaryText,
            textDirection = TextDirection.Ltr,
            textAlign = TextAlign.Center,
        ),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        singleLine = true,
        decorationBox = { innerTextField -> Box(contentAlignment = Alignment.Center) { innerTextField() } },
    )
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
    // RTL order: "before" sits on the physical right, "at" in the middle and
    // "after" on the physical left, matching the summary/timeline geometry.
    val choices = listOf(
        stringResource(R.string.prayer_silence_direction_before),
        stringResource(R.string.prayer_silence_direction_at),
        stringResource(R.string.prayer_silence_direction_after),
    )
    val selectedIndex = when (selected) {
        EndpointDirection.BEFORE -> 0
        EndpointDirection.AT_ADHAN -> 1
        EndpointDirection.AFTER -> 2
    }
    EndpointModeChoices(
        choices = choices,
        selected = selectedIndex,
        enabled = enabled,
        onSelected = { index ->
            onSelected(
                when (index) {
                    0 -> EndpointDirection.BEFORE
                    1 -> EndpointDirection.AT_ADHAN
                    else -> EndpointDirection.AFTER
                },
            )
        },
    )
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
                            maxLines = 1,
                            softWrap = false,
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

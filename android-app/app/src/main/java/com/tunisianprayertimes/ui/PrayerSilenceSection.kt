package com.tunisianprayertimes.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerSilenceConfig
import com.tunisianprayertimes.PrayerTime
import com.tunisianprayertimes.R
import com.tunisianprayertimes.ui.theme.NextPrayerBg

/**
 * One prayer's silence controls. The header, compact interval summary and the
 * full timeline are always visible and directly editable; expanding reveals the
 * independent endpoint rule editors below the timeline.
 */
@Composable
internal fun PrayerSilenceSection(
    prayer: Prayer,
    prayerName: String,
    prayerTime: PrayerTime,
    config: PrayerSilenceConfig,
    window: PrayerTimelineWindow,
    scale: PrayerTimelineScale,
    enabled: Boolean,
    showSwitch: Boolean,
    isNextPrayer: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onPreviewWindow: (PrayerTimelineWindow?) -> Unit,
    onCommitWindow: (PrayerTimelineWindow) -> Unit,
    onCommitConfig: (PrayerSilenceConfig) -> Unit,
    onEditEndpoint: (SilenceEndpoint) -> Unit,
    onPrayerTimeClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("prayer_timeline_${prayer.name}")
            .padding(
                horizontal = PrayerSilenceDimens.SectionHorizontalPadding,
                vertical = PrayerSilenceDimens.SectionVerticalPadding,
            ),
        verticalArrangement = Arrangement.spacedBy(PrayerSilenceDimens.SectionSpacing),
    ) {
        PrayerSilenceHeader(
            prayer = prayer,
            prayerName = prayerName,
            prayerTime = prayerTime,
            isNextPrayer = isNextPrayer,
            enabled = enabled,
            showSwitch = showSwitch,
            expanded = expanded,
            onExpandedChange = onExpandedChange,
            onEnabledChange = onEnabledChange,
            onPrayerTimeClick = onPrayerTimeClick,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (enabled) 1f else PrayerSilencePalette.DeemphasisAlpha),
            verticalArrangement = Arrangement.spacedBy(PrayerSilenceDimens.SectionDetailsSpacing),
        ) {
            SilenceIntervalDetails(
                prayerName = prayerName,
                prayerTime = prayerTime,
                window = window,
                config = config,
                enabled = enabled,
                onEditStart = { onEditEndpoint(SilenceEndpoint.START) },
                onEditEnd = { onEditEndpoint(SilenceEndpoint.END) },
            )
            // The slider maps offsets physically: earlier times on the right,
            // later times on the left, independent of layout mirroring.
            PrayerSilenceRangeSlider(
                prayer = prayer,
                prayerName = prayerName,
                prayerTime = prayerTime,
                config = config,
                scale = scale,
                window = window,
                enabled = enabled,
                onPreviewWindow = onPreviewWindow,
                onCommitWindow = onCommitWindow,
                onEditEndpoint = onEditEndpoint,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (enabled) 1f else PrayerSilencePalette.DeemphasisAlpha)
                    .padding(top = 2.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                EndpointAdvancedControls(
                    prayerName = prayerName,
                    config = config,
                    prayerTime = prayerTime,
                    window = window,
                    enabled = enabled,
                    onCommitConfig = onCommitConfig,
                    onEditEndpoint = onEditEndpoint,
                )
                SilenceRuleSummary(
                    config = config,
                    prayerTime = prayerTime,
                    window = window,
                )
            }
        }
    }
}

@Composable
private fun EndpointAdvancedControls(
    prayerName: String,
    config: PrayerSilenceConfig,
    prayerTime: PrayerTime,
    window: PrayerTimelineWindow,
    enabled: Boolean,
    onCommitConfig: (PrayerSilenceConfig) -> Unit,
    onEditEndpoint: (SilenceEndpoint) -> Unit,
) {
    // RTL chronology: the beginning is always on the physical right and the end
    // on the physical left; identities never swap when an endpoint crosses adhan.
    val endpoints = listOf(SilenceEndpoint.START, SilenceEndpoint.END)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val sideBySide = maxWidth.value / LocalDensity.current.fontScale >= 340f
        if (sideBySide) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                endpoints.forEach { endpoint ->
                    EndpointRuleControls(
                        endpoint = endpoint,
                        config = config,
                        prayerTime = prayerTime,
                        window = window,
                        enabled = enabled,
                        onConfigChange = onCommitConfig,
                        modifier = Modifier.weight(1f),
                        prayerName = prayerName,
                        compact = true,
                        onOpenEditor = { onEditEndpoint(endpoint) },
                    )
                }
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                endpoints.forEach { endpoint ->
                    EndpointRuleControls(
                        endpoint = endpoint,
                        config = config,
                        prayerTime = prayerTime,
                        window = window,
                        enabled = enabled,
                        onConfigChange = onCommitConfig,
                        prayerName = prayerName,
                        compact = true,
                        onOpenEditor = { onEditEndpoint(endpoint) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PrayerSilenceHeader(
    prayer: Prayer,
    prayerName: String,
    prayerTime: PrayerTime,
    isNextPrayer: Boolean,
    enabled: Boolean,
    showSwitch: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onPrayerTimeClick: (() -> Unit)?,
) {
    val timeText = prayerClockText(prayerMinutesOfDay(prayerTime))
    val switchDescription = stringResource(R.string.prayer_silence_switch_desc, prayerName)
    val disclosureLabel = stringResource(
        if (expanded) R.string.prayer_silence_collapse_desc else R.string.prayer_silence_expand_desc,
        prayerName,
    )
    val disclosureState = stringResource(
        if (expanded) R.string.prayer_silence_expanded_state else R.string.prayer_silence_collapsed_state,
    )
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = PrayerSilenceDimens.MinTouchTarget),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // RTL places the first child on the right: the disclosure chevron
            // stays at the far right, immediately beside the prayer name, and is
            // unaffected by the optional "القادمة" badge inside the FlowRow.
            Box(
                modifier = Modifier
                    .size(PrayerSilenceDimens.MinTouchTarget)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(
                        role = Role.Button,
                        onClickLabel = disclosureLabel,
                        onClick = { onExpandedChange(!expanded) },
                    )
                    .semantics { stateDescription = disclosureState },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(
                        if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more,
                    ),
                    contentDescription = null,
                    tint = PrayerSilencePalette.SecondaryText,
                    modifier = Modifier.size(22.dp),
                )
            }
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = prayerName,
                    fontSize = PrayerSilenceTypography.PrayerName,
                    fontWeight = FontWeight.Bold,
                    color = PrayerSilencePalette.PrimaryText,
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(
                            role = Role.Button,
                            onClickLabel = disclosureLabel,
                            onClick = { onExpandedChange(!expanded) },
                        )
                        .padding(horizontal = 2.dp, vertical = 6.dp)
                        .semantics { stateDescription = disclosureState },
                )
                PrayerSilenceClock(
                    prayer = prayer,
                    timeText = timeText,
                    onPrayerTimeClick = onPrayerTimeClick,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
                if (isNextPrayer) {
                    Text(
                        text = stringResource(R.string.prayer_timeline_up_next),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = PrayerSilencePalette.PrimaryText,
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .clip(RoundedCornerShape(6.dp))
                            .background(NextPrayerBg)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }
        if (showSwitch) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(R.string.prayer_silence_switch),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = PrayerSilencePalette.PrimaryText,
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                    modifier = Modifier
                        .heightIn(min = PrayerSilenceDimens.MinTouchTarget)
                        .semantics { contentDescription = switchDescription },
                    thumbContent = if (enabled) {
                        {
                            Icon(
                                painter = painterResource(R.drawable.ic_check),
                                contentDescription = null,
                                tint = PrayerSilencePalette.InteractiveTeal,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    } else {
                        null
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = PrayerSilencePalette.InteractiveTeal,
                        checkedBorderColor = PrayerSilencePalette.InteractiveTeal,
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = PrayerSilencePalette.InactiveTrack,
                        uncheckedBorderColor = PrayerSilencePalette.InactiveTrack,
                    ),
                )
            }
        }
    }
}

@Composable
private fun PrayerSilenceClock(
    prayer: Prayer,
    timeText: String,
    onPrayerTimeClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val editLabel = when (prayer) {
        Prayer.JOMOAA -> stringResource(R.string.prayer_silence_edit_jomoaa_time)
        Prayer.AID_FITR -> stringResource(R.string.prayer_silence_edit_aid_fitr_time)
        Prayer.AID_ADHA -> stringResource(R.string.prayer_silence_edit_aid_adha_time)
        else -> stringResource(
            R.string.prayer_timeline_edit_prayer_time,
            stringResource(prayer.displayNameRes()),
        )
    }
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier.then(
            if (onPrayerTimeClick != null) {
                Modifier
                    .heightIn(min = PrayerSilenceDimens.MinTouchTarget)
                    .clip(shape)
                    .clickable(role = Role.Button, onClickLabel = editLabel, onClick = onPrayerTimeClick)
                    .padding(horizontal = 4.dp)
            } else {
                Modifier
            },
        ),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = timeText,
                fontSize = PrayerSilenceTypography.PrayerTime,
                fontWeight = FontWeight.SemiBold,
                color = PrayerSilencePalette.GoldAccent,
                style = TextStyle(textDirection = TextDirection.Ltr, fontFeatureSettings = "tnum"),
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.testTag("prayer_time_${prayer.name}"),
            )
            if (onPrayerTimeClick != null) {
                Icon(
                    painter = painterResource(R.drawable.ic_edit),
                    contentDescription = null,
                    tint = PrayerSilencePalette.SecondaryText,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

private fun Prayer.displayNameRes(): Int = when (this) {
    Prayer.FAJR -> R.string.prayer_fajr
    Prayer.DHUHR -> R.string.prayer_dhuhr
    Prayer.ASR -> R.string.prayer_asr
    Prayer.MAGHRIB -> R.string.prayer_maghrib
    Prayer.ISHA -> R.string.prayer_isha
    Prayer.JOMOAA -> R.string.prayer_jomoaa
    Prayer.AID_FITR -> R.string.prayer_aid_fitr
    Prayer.AID_ADHA -> R.string.prayer_aid_adha
}

@Composable
private fun SilenceIntervalDetails(
    prayerName: String,
    prayerTime: PrayerTime,
    window: PrayerTimelineWindow,
    config: PrayerSilenceConfig,
    enabled: Boolean,
    onEditStart: () -> Unit,
    onEditEnd: () -> Unit,
) {
    val prayerMinutes = prayerMinutesOfDay(prayerTime)
    val startCaption = stringResource(
        R.string.prayer_silence_endpoint_caption,
        stringResource(R.string.prayer_silence_start_label),
        endpointRuleCaption(config, window, SilenceEndpoint.START, prayerMinutes),
    )
    val endCaption = stringResource(
        R.string.prayer_silence_endpoint_caption,
        stringResource(R.string.prayer_silence_end_label),
        endpointRuleCaption(config, window, SilenceEndpoint.END, prayerMinutes),
    )
    val startDescription = stringResource(
        R.string.prayer_silence_start_field_desc,
        prayerName,
        silenceEndpointState(config, window, SilenceEndpoint.START, prayerMinutes),
    )
    val endDescription = stringResource(
        R.string.prayer_silence_end_field_desc,
        prayerName,
        silenceEndpointState(config, window, SilenceEndpoint.END, prayerMinutes),
    )
    val invalid = window.durationMinutes < 0
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val singleRow = maxWidth.value / LocalDensity.current.fontScale >= 300f
        if (singleRow) {
            // RTL chronology: beginning on the physical right, end on the left.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CompactEndpointSummary(
                    time = silenceClockText(window.startMinutes),
                    caption = startCaption,
                    description = startDescription,
                    enabled = enabled,
                    onClick = onEditStart,
                    modifier = Modifier.weight(1f),
                )
                SilenceDurationLabel(
                    durationMinutes = window.durationMinutes,
                    invalid = invalid,
                )
                CompactEndpointSummary(
                    time = silenceClockText(window.endMinutes),
                    caption = endCaption,
                    description = endDescription,
                    enabled = enabled,
                    onClick = onEditEnd,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CompactEndpointSummary(
                        time = silenceClockText(window.startMinutes),
                        caption = startCaption,
                        description = startDescription,
                        enabled = enabled,
                        onClick = onEditStart,
                        modifier = Modifier.weight(1f),
                    )
                    CompactEndpointSummary(
                        time = silenceClockText(window.endMinutes),
                        caption = endCaption,
                        description = endDescription,
                        enabled = enabled,
                        onClick = onEditEnd,
                        modifier = Modifier.weight(1f),
                    )
                }
                SilenceDurationLabel(
                    durationMinutes = window.durationMinutes,
                    invalid = invalid,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
    }
}

/** Compact tappable endpoint text: no permanent background, border or field box. */
@Composable
private fun CompactEndpointSummary(
    time: String,
    caption: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .heightIn(min = PrayerSilenceDimens.MinTouchTarget)
            .clip(RoundedCornerShape(10.dp))
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = description,
                onClick = onClick,
            )
            .padding(horizontal = 4.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = time,
            fontSize = PrayerSilenceTypography.FieldValue,
            fontWeight = FontWeight.SemiBold,
            color = PrayerSilencePalette.PrimaryText,
            style = TextStyle(textDirection = TextDirection.Ltr, fontFeatureSettings = "tnum"),
            maxLines = 1,
            softWrap = false,
        )
        Text(
            text = caption,
            fontSize = PrayerSilenceTypography.FieldLabel,
            color = PrayerSilencePalette.SecondaryText,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

@Composable
internal fun endpointRuleCaption(
    config: PrayerSilenceConfig,
    window: PrayerTimelineWindow,
    endpoint: SilenceEndpoint,
    prayerMinutes: Int,
): String {
    val mode = if (endpoint == SilenceEndpoint.START) config.startRuleMode() else config.endRuleMode()
    if (mode == EndpointRuleMode.FIXED_TIME) {
        return stringResource(R.string.prayer_silence_mode_fixed)
    }
    val offset = if (endpoint == SilenceEndpoint.START) {
        window.startMinutes - prayerMinutes
    } else {
        window.endMinutes - prayerMinutes
    }
    return when {
        offset == 0 -> stringResource(R.string.prayer_silence_at_adhan)
        offset < 0 -> stringResource(R.string.prayer_silence_caption_before_adhan, -offset)
        else -> stringResource(R.string.prayer_silence_caption_after_adhan, offset)
    }
}

@Composable
private fun SilenceRuleSummary(
    config: PrayerSilenceConfig,
    prayerTime: PrayerTime,
    window: PrayerTimelineWindow,
    modifier: Modifier = Modifier,
) {
    val prayerMinutes = prayerMinutesOfDay(prayerTime)
    val durationText = if (window.durationMinutes < 0) {
        "—"
    } else {
        pluralStringResource(
            R.plurals.prayer_silence_duration_minutes,
            window.durationMinutes,
            window.durationMinutes,
        )
    }
    val startPhrase = endpointSummaryPhrase(config, window, SilenceEndpoint.START, prayerMinutes)
    val endPhrase = endpointSummaryPhrase(config, window, SilenceEndpoint.END, prayerMinutes)
    val shape = RoundedCornerShape(PrayerSilenceDimens.FieldCorner)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(PrayerSilencePalette.TintedStrip)
            .border(1.dp, PrayerSilencePalette.SoftBorder, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(R.string.prayer_silence_summary_duration, durationText),
                fontSize = PrayerSilenceTypography.Duration,
                fontWeight = FontWeight.SemiBold,
                color = PrayerSilencePalette.InteractiveTeal,
            )
            Text(
                text = "$startPhrase $endPhrase",
                fontSize = 12.sp,
                color = PrayerSilencePalette.SecondaryText,
                lineHeight = 17.sp,
            )
        }
        Icon(
            painter = painterResource(R.drawable.ic_bell_off),
            contentDescription = null,
            tint = PrayerSilencePalette.SecondaryText,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** "تبدأ ..." / "وتنتهي ..." phrases describing the endpoint's actual rule. */
@Composable
private fun endpointSummaryPhrase(
    config: PrayerSilenceConfig,
    window: PrayerTimelineWindow,
    endpoint: SilenceEndpoint,
    prayerMinutes: Int,
): String {
    val start = endpoint == SilenceEndpoint.START
    val mode = if (start) config.startRuleMode() else config.endRuleMode()
    if (mode == EndpointRuleMode.FIXED_TIME) {
        val clock = silenceClockText(if (start) window.startMinutes else window.endMinutes)
        return stringResource(
            if (start) R.string.prayer_silence_summary_start_fixed else R.string.prayer_silence_summary_end_fixed,
            clock,
        )
    }
    val offset = if (start) {
        window.startMinutes - prayerMinutes
    } else {
        window.endMinutes - prayerMinutes
    }
    return when {
        offset == 0 -> stringResource(
            if (start) R.string.prayer_silence_summary_start_at else R.string.prayer_silence_summary_end_at,
        )
        offset < 0 -> stringResource(
            if (start) R.string.prayer_silence_summary_start_before else R.string.prayer_silence_summary_end_before,
            -offset,
        )
        else -> stringResource(
            if (start) R.string.prayer_silence_summary_start_after else R.string.prayer_silence_summary_end_after,
            offset,
        )
    }
}

@Composable
private fun SilenceDurationLabel(
    durationMinutes: Int,
    invalid: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // RTL row: the text is the first child (physical right), so the muted
        // bell icon stays on the physical left as in the summary panels.
        Text(
            text = if (invalid) {
                "—"
            } else {
                pluralStringResource(
                    R.plurals.prayer_silence_duration_minutes,
                    durationMinutes,
                    durationMinutes,
                )
            },
            fontSize = PrayerSilenceTypography.Duration,
            fontWeight = FontWeight.SemiBold,
            color = PrayerSilencePalette.InteractiveTeal,
            // The phrase itself keeps its natural Arabic ordering.
            style = TextStyle(textDirection = TextDirection.Content),
            maxLines = 1,
        )
        Icon(
            painter = painterResource(R.drawable.ic_bell_off),
            contentDescription = null,
            tint = PrayerSilencePalette.SecondaryText,
            modifier = Modifier.size(15.dp),
        )
    }
}

@Composable
private fun silenceEndpointState(
    config: PrayerSilenceConfig,
    window: PrayerTimelineWindow,
    endpoint: SilenceEndpoint,
    prayerMinutes: Int,
): String {
    val mode = if (endpoint == SilenceEndpoint.START) config.startRuleMode() else config.endRuleMode()
    if (mode == EndpointRuleMode.FIXED_TIME) {
        val clock = when (endpoint) {
            SilenceEndpoint.START -> window.startMinutes
            SilenceEndpoint.END -> window.endMinutes
        }
        return stringResource(R.string.prayer_silence_fixed_time_desc, prayerClockText(clock))
    }
    val offset = when (endpoint) {
        SilenceEndpoint.START -> window.startMinutes - prayerMinutes
        SilenceEndpoint.END -> window.endMinutes - prayerMinutes
    }
    return endpointRelationText(offset)
}

@Composable
internal fun silenceClockText(absoluteMinutes: Int): String {
    val day = prayerDayOffset(absoluteMinutes)
    val clock = prayerClockText(absoluteMinutes)
    val suffix = when {
        day == -1 -> stringResource(R.string.prayer_silence_previous_day)
        day == 1 -> stringResource(R.string.prayer_silence_next_day)
        day < -1 -> stringResource(R.string.prayer_silence_days_earlier, -day)
        day > 1 -> stringResource(R.string.prayer_silence_days_later, day)
        else -> return clock
    }
    return "$clock ($suffix)"
}

package com.tunisianprayertimes.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.SubcomposeLayout
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
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerSilenceConfig
import com.tunisianprayertimes.PrayerTime
import com.tunisianprayertimes.R
import com.tunisianprayertimes.ui.theme.NextPrayerBg
import kotlin.math.roundToInt

/**
 * One prayer's silence controls. The header, compact interval summary and the
 * full timeline are always visible and directly editable. Expansion is reserved
 * for genuine advanced settings; when none exist the prayer shows no chevron.
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
    onEnabledChange: (Boolean) -> Unit,
    onPreviewWindow: (PrayerTimelineWindow?) -> Unit,
    onCommitWindow: (PrayerTimelineWindow) -> Unit,
    onEditEndpoint: (SilenceEndpoint) -> Unit,
    onPrayerTimeClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    advancedContent: (@Composable () -> Unit)? = null,
) {
    var expanded by rememberSaveable(prayer) { mutableStateOf(false) }
    val advanced = advancedContent
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
            hasAdvanced = advanced != null,
            expanded = expanded,
            onExpandedChange = { expanded = it },
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
        if (advanced != null) {
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(if (enabled) 1f else PrayerSilencePalette.DeemphasisAlpha)
                        .padding(top = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    advanced()
                }
            }
        }
    }
}

private const val HeaderNameSlot = "prayer_silence_header_name"
private const val HeaderSwitchSlot = "prayer_silence_header_switch"

private val HeaderNameTextStyle = TextStyle(
    fontSize = PrayerSilenceTypography.PrayerName,
    fontWeight = FontWeight.Bold,
)

private val HeaderClockTextStyle = TextStyle(
    fontSize = PrayerSilenceTypography.PrayerTime,
    fontWeight = FontWeight.SemiBold,
    fontFeatureSettings = "tnum",
)

@Composable
private fun PrayerSilenceHeader(
    prayer: Prayer,
    prayerName: String,
    prayerTime: PrayerTime,
    isNextPrayer: Boolean,
    enabled: Boolean,
    showSwitch: Boolean,
    hasAdvanced: Boolean,
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
    val nameModifier = if (hasAdvanced) {
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                role = Role.Button,
                onClickLabel = disclosureLabel,
                onClick = { onExpandedChange(!expanded) },
            )
            .padding(horizontal = 2.dp, vertical = 6.dp)
            .semantics { stateDescription = disclosureState }
    } else {
        Modifier.padding(horizontal = 2.dp, vertical = 6.dp)
    }
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    SubcomposeLayout(modifier = Modifier.fillMaxWidth()) { constraints ->
        val maxWidth = constraints.maxWidth
        val gapPx = with(density) { 10.dp.toPx() }.roundToInt()
        val minTouchPx = with(density) { PrayerSilenceDimens.MinTouchTarget.toPx() }.roundToInt()
        val nameGroupMeasurable = subcompose(HeaderNameSlot) {
            HeaderNameGroup(
                prayer = prayer,
                prayerName = prayerName,
                prayerTime = prayerTime,
                isNextPrayer = isNextPrayer,
                hasAdvanced = hasAdvanced,
                expanded = expanded,
                disclosureLabel = disclosureLabel,
                disclosureState = disclosureState,
                nameModifier = nameModifier,
                onExpandedChange = onExpandedChange,
                onPrayerTimeClick = onPrayerTimeClick,
            )
        }.first()
        val switchMeasurable = if (showSwitch) {
            subcompose(HeaderSwitchSlot) {
                SilenceSwitchRow(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                    description = switchDescription,
                )
            }.first()
        } else {
            null
        }
        val switchPlaceable = switchMeasurable?.measure(Constraints())
        val switchWidth = switchPlaceable?.width ?: 0
        // Content-driven fit: the "up next" badge may wrap, but the name and clock
        // line has to share the row with the switch for the header to stay single-line.
        val nameWidth = textMeasurer.measure(prayerName, HeaderNameTextStyle).size.width.toFloat()
        val clockWidth = textMeasurer.measure(timeText, HeaderClockTextStyle, maxLines = 1).size.width.toFloat() +
            if (onPrayerTimeClick != null) with(density) { 26.dp.toPx() } else 0f
        val chevronWidth = if (hasAdvanced) with(density) { 54.dp.toPx() } else 0f
        val nameClockWidth = chevronWidth + nameWidth + with(density) { 6.dp.toPx() } + clockWidth
        val sharesLine = switchPlaceable == null || nameClockWidth + gapPx + switchWidth <= maxWidth
        val startIsRight = layoutDirection == LayoutDirection.Rtl
        if (sharesLine) {
            val remaining = (maxWidth - (if (switchPlaceable != null) gapPx + switchWidth else 0))
                .coerceAtLeast(1)
            val nameGroup = nameGroupMeasurable.measure(Constraints(maxWidth = remaining))
            val rowHeight = maxOf(nameGroup.height, switchPlaceable?.height ?: 0, minTouchPx)
            layout(maxWidth, rowHeight) {
                nameGroup.place(
                    if (startIsRight) maxWidth - nameGroup.width else 0,
                    (rowHeight - nameGroup.height) / 2,
                )
                switchPlaceable?.place(
                    if (startIsRight) 0 else maxWidth - switchPlaceable.width,
                    (rowHeight - switchPlaceable.height) / 2,
                )
            }
        } else {
            val nameGroup = nameGroupMeasurable.measure(Constraints(maxWidth = maxWidth))
            val switchHeight = switchPlaceable?.height ?: 0
            val rowHeight = maxOf(nameGroup.height + gapPx + switchHeight, minTouchPx)
            layout(maxWidth, rowHeight) {
                nameGroup.place(0, 0)
                switchPlaceable?.place(
                    if (startIsRight) 0 else maxWidth - switchPlaceable.width,
                    nameGroup.height + gapPx,
                )
            }
        }
    }
}

@Composable
private fun HeaderNameGroup(
    prayer: Prayer,
    prayerName: String,
    prayerTime: PrayerTime,
    isNextPrayer: Boolean,
    hasAdvanced: Boolean,
    expanded: Boolean,
    disclosureLabel: String,
    disclosureState: String,
    nameModifier: Modifier,
    onExpandedChange: (Boolean) -> Unit,
    onPrayerTimeClick: (() -> Unit)?,
) {
    val timeText = prayerClockText(prayerMinutesOfDay(prayerTime))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // RTL places the first child on the right: the disclosure chevron stays at
        // the far right, immediately beside the prayer name.
        if (hasAdvanced) {
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
        }
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Name and adhan time stay together as one coherent group; only the
            // optional badge may wrap when the header gets tight.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = prayerName,
                    style = HeaderNameTextStyle,
                    color = PrayerSilencePalette.PrimaryText,
                    modifier = Modifier.align(Alignment.CenterVertically).then(nameModifier),
                )
                PrayerSilenceClock(
                    prayer = prayer,
                    timeText = timeText,
                    onPrayerTimeClick = onPrayerTimeClick,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
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
}

@Composable
private fun SilenceSwitchRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String,
) {
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
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier
                .heightIn(min = PrayerSilenceDimens.MinTouchTarget)
                .semantics { contentDescription = description },
            thumbContent = if (checked) {
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

private val EndpointTimeTextStyle = TextStyle(
    fontSize = PrayerSilenceTypography.FieldValue,
    fontWeight = FontWeight.SemiBold,
    fontFeatureSettings = "tnum",
)

private val EndpointCaptionTextStyle = TextStyle(
    fontSize = PrayerSilenceTypography.FieldLabel,
)

private val EndpointDurationTextStyle = TextStyle(
    fontSize = PrayerSilenceTypography.Duration,
    fontWeight = FontWeight.SemiBold,
)

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
    val startIdentity = stringResource(R.string.prayer_silence_start_label)
    val endIdentity = stringResource(R.string.prayer_silence_end_label)
    val startRule = endpointRuleCaption(config, window, SilenceEndpoint.START, prayerMinutes)
    val endRule = endpointRuleCaption(config, window, SilenceEndpoint.END, prayerMinutes)
    val startCaption = stringResource(R.string.prayer_silence_endpoint_caption, startIdentity, startRule)
    val endCaption = stringResource(R.string.prayer_silence_endpoint_caption, endIdentity, endRule)
    val startTime = silenceClockText(window.startMinutes)
    val endTime = silenceClockText(window.endMinutes)
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
    val durationText = if (invalid) {
        "—"
    } else {
        pluralStringResource(
            R.plurals.prayer_silence_duration_minutes,
            window.durationMinutes,
            window.durationMinutes,
        )
    }
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Decide the arrangement from the actual width and the rendered text sizes
        // instead of a font-scale threshold.
        val fullWidthPx = constraints.maxWidth.toFloat()
        val gapPx = with(density) { 10.dp.toPx() }
        val durationWidthPx = textMeasurer.measure(
            text = durationText,
            style = EndpointDurationTextStyle,
            maxLines = 1,
        ).size.width.toFloat() + with(density) { 19.dp.toPx() }
        val columnWidthPx = ((fullWidthPx - durationWidthPx - gapPx * 2f) / 2f).coerceAtLeast(0f)
        val summaryPaddingPx = with(density) { 8.dp.toPx() }
        val captionWidthPx = (columnWidthPx - summaryPaddingPx - with(density) { 16.dp.toPx() })
            .coerceAtLeast(1f)
        val timeWidthPx = (columnWidthPx - summaryPaddingPx).coerceAtLeast(1f)
        val timeStyle = EndpointTimeTextStyle.copy(textDirection = TextDirection.Ltr)
        fun columnFits(time: String, caption: String): Boolean {
            val timeLayout = textMeasurer.measure(
                text = time,
                style = timeStyle,
                maxLines = 1,
                constraints = Constraints(maxWidth = timeWidthPx.toInt().coerceAtLeast(1)),
            )
            if (timeLayout.hasVisualOverflow) return false
            val captionLayout = textMeasurer.measure(
                text = caption,
                style = EndpointCaptionTextStyle,
                maxLines = 2,
                constraints = Constraints(maxWidth = captionWidthPx.toInt().coerceAtLeast(1)),
            )
            return !captionLayout.hasVisualOverflow
        }
        val singleRow = columnWidthPx > 0f &&
            columnFits(startTime, startCaption) &&
            columnFits(endTime, endCaption)
        if (singleRow) {
            // Visual RTL chronology: beginning on the physical right, end on the
            // left. Composed beginning, end, then duration so accessibility reads
            // in the same order while the duration stays centered between them.
            Layout(
                modifier = Modifier.fillMaxWidth(),
                content = {
                    CompactEndpointSummary(
                        time = startTime,
                        caption = startCaption,
                        description = startDescription,
                        enabled = enabled,
                        onClick = onEditStart,
                    )
                    CompactEndpointSummary(
                        time = endTime,
                        caption = endCaption,
                        description = endDescription,
                        enabled = enabled,
                        onClick = onEditEnd,
                    )
                    SilenceDurationLabel(
                        durationMinutes = window.durationMinutes,
                        invalid = invalid,
                    )
                },
            ) { measurables, layoutConstraints ->
                val maxWidthPx = layoutConstraints.maxWidth
                val durationPlaceable = measurables[2].measure(Constraints())
                val summaryColumnWidthPx = ((maxWidthPx - durationPlaceable.width - gapPx * 2f) / 2f)
                    .roundToInt()
                    .coerceAtLeast(0)
                val summaryConstraints = Constraints(
                    minWidth = summaryColumnWidthPx,
                    maxWidth = summaryColumnWidthPx,
                )
                val startPlaceable = measurables[0].measure(summaryConstraints)
                val endPlaceable = measurables[1].measure(summaryConstraints)
                val rowHeight = maxOf(
                    startPlaceable.height,
                    endPlaceable.height,
                    durationPlaceable.height,
                )
                layout(maxWidthPx, rowHeight) {
                    val startX = if (layoutDirection == LayoutDirection.Rtl) {
                        maxWidthPx - summaryColumnWidthPx
                    } else {
                        0
                    }
                    val endX = if (layoutDirection == LayoutDirection.Rtl) 0 else maxWidthPx - summaryColumnWidthPx
                    startPlaceable.place(startX, (rowHeight - startPlaceable.height) / 2)
                    endPlaceable.place(endX, (rowHeight - endPlaceable.height) / 2)
                    durationPlaceable.place(
                        ((maxWidthPx - durationPlaceable.width) / 2f).roundToInt(),
                        (rowHeight - durationPlaceable.height) / 2,
                    )
                }
            }
        } else {
            // Text no longer fits side by side: stack full-width endpoint groups
            // (beginning first, then end) and give the duration its own line.
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StackedEndpointSummary(
                    identity = startIdentity,
                    time = startTime,
                    rule = startRule,
                    description = startDescription,
                    enabled = enabled,
                    onClick = onEditStart,
                )
                StackedEndpointSummary(
                    identity = endIdentity,
                    time = endTime,
                    rule = endRule,
                    description = endDescription,
                    enabled = enabled,
                    onClick = onEditEnd,
                )
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
            style = EndpointTimeTextStyle.copy(textDirection = TextDirection.Ltr),
            color = PrayerSilencePalette.PrimaryText,
            maxLines = 1,
            softWrap = false,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = caption,
                style = EndpointCaptionTextStyle,
                color = PrayerSilencePalette.SecondaryText,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
            Icon(
                painter = painterResource(R.drawable.ic_expand_more),
                contentDescription = null,
                tint = PrayerSilencePalette.SecondaryText,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/**
 * Full-width, start-aligned endpoint group for tight widths: identity with the
 * dropdown indicator, the prominent time, then the rule description, wrapping
 * naturally over the whole width.
 */
@Composable
private fun StackedEndpointSummary(
    identity: String,
    time: String,
    rule: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
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
        horizontalAlignment = Alignment.Start,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = identity,
                style = EndpointCaptionTextStyle,
                color = PrayerSilencePalette.SecondaryText,
            )
            Icon(
                painter = painterResource(R.drawable.ic_expand_more),
                contentDescription = null,
                tint = PrayerSilencePalette.SecondaryText,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            text = time,
            style = EndpointTimeTextStyle.copy(textDirection = TextDirection.Ltr),
            color = PrayerSilencePalette.PrimaryText,
            maxLines = 1,
            softWrap = false,
        )
        Text(
            text = rule,
            style = EndpointCaptionTextStyle,
            color = PrayerSilencePalette.SecondaryText,
            textAlign = TextAlign.Start,
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

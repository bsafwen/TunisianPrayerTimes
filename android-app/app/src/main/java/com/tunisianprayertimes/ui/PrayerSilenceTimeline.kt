package com.tunisianprayertimes.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.DelayMode
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerSilenceConfig
import com.tunisianprayertimes.PrayerTime
import com.tunisianprayertimes.R
import com.tunisianprayertimes.SilenceMode
import com.tunisianprayertimes.ui.theme.Divider
import com.tunisianprayertimes.ui.theme.Gold
import com.tunisianprayertimes.ui.theme.GreenPrimary
import com.tunisianprayertimes.ui.theme.GreenPrimaryDark
import com.tunisianprayertimes.ui.theme.TextMuted
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private enum class TimelineDragPart { Window, Start, End }

private data class TimelineDragState(
    val part: TimelineDragPart,
    val original: PrayerTimelineWindow,
    val range: PrayerTimelineRange,
    val window: PrayerTimelineWindow,
    val pixelsAcross: Float,
    val accumulatedPixels: Float = 0f,
)

/** A draft remains local to the gesture; only a completed drag writes the setting. */
@Composable
internal fun PrayerSilenceTimeline(
    prayerTime: PrayerTime,
    config: PrayerSilenceConfig,
    selected: Boolean,
    onSelect: () -> Unit,
    onCommit: (PrayerSilenceConfig) -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    onDeselect: (() -> Unit)? = null,
) {
    val hapticFeedback = LocalHapticFeedback.current
    val savedWindow = remember(prayerTime, config) { resolvePrayerTimelineWindow(prayerTime, config) }
    val savedRange = remember(prayerTime, savedWindow) { prayerTimelineRange(prayerTime, savedWindow) }
    val validWindow = savedWindow.endMinutes >= savedWindow.startMinutes
    val canAdjust = selected && validWindow
    var drag by remember(prayerTime, config, selected) { mutableStateOf<TimelineDragState?>(null) }
    val window = drag?.window ?: savedWindow
    val range = drag?.range ?: savedRange
    val prayerMinute = prayerTime.hour * 60 + prayerTime.minute
    val startText = timelineClockText(window.startMinutes)
    val endText = timelineClockText(window.endMinutes)
    val intervalText = stringResource(R.string.prayer_timeline_interval, startText, endText)
    val editLabel = stringResource(R.string.prayer_timeline_exact_edit)
    val moveEarlierLabel = stringResource(R.string.prayer_timeline_move_earlier)
    val moveLaterLabel = stringResource(R.string.prayer_timeline_move_later)
    val startEarlierLabel = stringResource(R.string.prayer_timeline_start_earlier)
    val startLaterLabel = stringResource(R.string.prayer_timeline_start_later)
    val endEarlierLabel = stringResource(R.string.prayer_timeline_end_earlier)
    val endLaterLabel = stringResource(R.string.prayer_timeline_end_later)
    val startHandleLabel = stringResource(R.string.prayer_timeline_start_handle, startText)
    val endHandleLabel = stringResource(R.string.prayer_timeline_end_handle, endText)
    val moveLabel = stringResource(R.string.prayer_timeline_move_window)

    fun commitWindow(candidate: PrayerTimelineWindow): Boolean {
        val nextConfig = prayerTimelineConfigForWindow(prayerTime, config, candidate) ?: return false
        if (nextConfig != config) onCommit(nextConfig)
        return true
    }

    fun nudge(part: TimelineDragPart, delta: Int): Boolean {
        if (!canAdjust) return false
        val candidate = shiftedTimelineWindow(savedWindow, part, delta.toLong()) ?: return false
        return commitWindow(candidate)
    }

    Column(
        modifier = modifier.testTag("prayer_timeline_${prayerTime.prayer.name}"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Clock time increases from left to right even when the surrounding UI is Arabic.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth().height(if (canAdjust) 96.dp else 48.dp),
            ) {
                val density = LocalDensity.current
                val inset = with(density) { 24.dp.toPx() }
                val trackWidth = (constraints.maxWidth.toFloat() - inset * 2f).coerceAtLeast(1f)
                val handleSeparation = with(density) { 48.dp.roundToPx().toFloat() }
                val handleTrackWidth = (trackWidth - handleSeparation).coerceAtLeast(1f)
                val rangeMinutes = (range.endMinutes.toLong() - range.startMinutes).coerceAtLeast(1L)
                fun xForMinute(minute: Int): Float = inset +
                    ((minute.toLong() - range.startMinutes).toDouble() / rangeMinutes * trackWidth).toFloat()
                fun handleXForMinute(minute: Int): Float = inset +
                    ((minute.toLong() - range.startMinutes).toDouble() / rangeMinutes * handleTrackWidth).toFloat()

                val startX = xForMinute(window.startMinutes)
                val endX = xForMinute(window.endMinutes)
                // Two parallel time scales keep 48dp between the touch targets even
                // at zero duration. Their scales and separation stay fixed during drag.
                val startHandleX = handleXForMinute(window.startMinutes)
                val endHandleX = handleXForMinute(window.endMinutes) + handleSeparation
                val prayerX = xForMinute(prayerMinute)
                val centerY = with(density) { (if (canAdjust) 72.dp else 24.dp).toPx() }

                fun beginDrag(part: TimelineDragPart) {
                    if (!validWindow) return
                    drag = TimelineDragState(
                        part, savedWindow, savedRange, savedWindow,
                        pixelsAcross = if (part == TimelineDragPart.Window) trackWidth else handleTrackWidth,
                    )
                }

                fun updateDrag(deltaPixels: Float) {
                    val current = drag ?: return
                    val pixels = current.accumulatedPixels + deltaPixels
                    val minutesAcross = current.range.endMinutes.toLong() - current.range.startMinutes
                    val snapped = ((pixels.toDouble() / current.pixelsAcross * minutesAcross) / 5.0).roundToLong() * 5L
                    val minimum = when (current.part) {
                        TimelineDragPart.Window, TimelineDragPart.Start ->
                            current.range.startMinutes.toLong() - current.original.startMinutes
                        TimelineDragPart.End -> current.original.startMinutes.toLong() - current.original.endMinutes
                    }
                    val maximum = when (current.part) {
                        TimelineDragPart.Window, TimelineDragPart.End ->
                            current.range.endMinutes.toLong() - current.original.endMinutes
                        TimelineDragPart.Start -> current.original.endMinutes.toLong() - current.original.startMinutes
                    }
                    if (minimum > maximum) return
                    val candidate = shiftedTimelineWindow(current.original, current.part, snapped.coerceIn(minimum, maximum))
                    val valid = candidate?.takeIf { prayerTimelineConfigForWindow(prayerTime, config, it) != null }
                    if (valid != null && valid != current.window) {
                        // The platform implementation respects the user's touch-feedback setting.
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    drag = current.copy(accumulatedPixels = pixels, window = valid ?: current.window)
                }

                fun finishDrag() {
                    val completed = drag
                    drag = null
                    if (completed != null && completed.window != savedWindow) {
                        // Selecting after release avoids shifting a compact row beneath the finger.
                        onSelect()
                        commitWindow(completed.window)
                    }
                }

                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawLine(Divider, Offset(inset, centerY), Offset(size.width - inset, centerY), 4.dp.toPx(), StrokeCap.Round)
                    val tickStep = listOf(5L, 15L, 30L, 60L, 180L, 360L, 720L, 1440L)
                        .firstOrNull { rangeMinutes / it <= 10L } ?: (rangeMinutes / 10L).coerceAtLeast(1L)
                    val firstTick = Math.floorDiv(range.startMinutes.toLong(), tickStep) * tickStep
                    for (index in 0..11) {
                        val minute = firstTick + index * tickStep
                        if (minute < range.startMinutes || minute > range.endMinutes) continue
                        val tickX = xForMinute(minute.toInt())
                        drawLine(
                            TextMuted.copy(alpha = 0.22f),
                            Offset(tickX, centerY - 4.dp.toPx()), Offset(tickX, centerY + 4.dp.toPx()),
                            1.dp.toPx(), StrokeCap.Round,
                        )
                    }
                    if (canAdjust) {
                        drawLine(GreenPrimary.copy(alpha = 0.25f), Offset(startHandleX, 34.dp.toPx()), Offset(startX, centerY), 1.5.dp.toPx())
                        drawLine(GreenPrimary.copy(alpha = 0.25f), Offset(endHandleX, 34.dp.toPx()), Offset(endX, centerY), 1.5.dp.toPx())
                    }
                    if (drag?.part == TimelineDragPart.Window) {
                        drawLine(GreenPrimary.copy(alpha = 0.10f), Offset(startX, centerY), Offset(endX, centerY), 22.dp.toPx(), StrokeCap.Round)
                    }
                    if (window.durationMinutes == 0) {
                        drawCircle(GreenPrimary, 6.dp.toPx(), Offset(startX, centerY))
                    } else if (window.endMinutes > window.startMinutes) {
                        drawLine(GreenPrimary, Offset(startX, centerY), Offset(endX, centerY), 10.dp.toPx(), StrokeCap.Round)
                    }
                    drawLine(Gold, Offset(prayerX, centerY - 10.dp.toPx()), Offset(prayerX, centerY + 12.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
                    val diamondCenter = Offset(prayerX, centerY - 13.dp.toPx())
                    val diamondRadius = 4.5.dp.toPx()
                    drawPath(Path().apply {
                        moveTo(diamondCenter.x, diamondCenter.y - diamondRadius)
                        lineTo(diamondCenter.x + diamondRadius, diamondCenter.y)
                        lineTo(diamondCenter.x, diamondCenter.y + diamondRadius)
                        lineTo(diamondCenter.x - diamondRadius, diamondCenter.y)
                        close()
                    }, Gold)
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset(y = if (canAdjust) 48.dp else 0.dp)
                        .height(48.dp)
                        .timelineHorizontalDrag(
                            enabled = validWindow,
                            gestureKey = listOf(prayerTime, config, trackWidth, TimelineDragPart.Window),
                            canStart = { point -> drag == null && point.x >= startX - inset && point.x <= endX + inset },
                            onStart = { beginDrag(TimelineDragPart.Window) },
                            onDelta = ::updateDrag,
                            onFinish = ::finishDrag,
                            onCancel = { drag = null },
                        )
                        .clickable(onClick = onSelect)
                        .semantics {
                            contentDescription = moveLabel
                            stateDescription = intervalText
                            if (canAdjust) customActions = listOf(
                                CustomAccessibilityAction(moveEarlierLabel) { nudge(TimelineDragPart.Window, -1) },
                                CustomAccessibilityAction(moveLaterLabel) { nudge(TimelineDragPart.Window, 1) },
                            )
                        },
                )
                if (canAdjust) {
                    TimelineHandle(
                        modifier = Modifier.offset { IntOffset((startHandleX - inset).roundToInt(), 0) },
                        active = drag?.part == TimelineDragPart.Start,
                        description = startHandleLabel,
                        earlierLabel = startEarlierLabel,
                        laterLabel = startLaterLabel,
                        onEarlier = { nudge(TimelineDragPart.Start, -1) },
                        onLater = { nudge(TimelineDragPart.Start, 1) },
                        onEdit = onEdit,
                        gestureModifier = Modifier.timelineHorizontalDrag(
                            enabled = true,
                            gestureKey = listOf(prayerTime, config, trackWidth, TimelineDragPart.Start),
                            canStart = { drag == null },
                            onStart = { beginDrag(TimelineDragPart.Start) },
                            onDelta = ::updateDrag,
                            onFinish = ::finishDrag,
                            onCancel = { drag = null },
                        ),
                    )
                    TimelineHandle(
                        modifier = Modifier.offset { IntOffset((endHandleX - inset).roundToInt(), 0) },
                        active = drag?.part == TimelineDragPart.End,
                        description = endHandleLabel,
                        earlierLabel = endEarlierLabel,
                        laterLabel = endLaterLabel,
                        onEarlier = { nudge(TimelineDragPart.End, -1) },
                        onLater = { nudge(TimelineDragPart.End, 1) },
                        onEdit = onEdit,
                        gestureModifier = Modifier.timelineHorizontalDrag(
                            enabled = true,
                            gestureKey = listOf(prayerTime, config, trackWidth, TimelineDragPart.End),
                            canStart = { drag == null },
                            onStart = { beginDrag(TimelineDragPart.End) },
                            onDelta = ::updateDrag,
                            onFinish = ::finishDrag,
                            onCancel = { drag = null },
                        ),
                    )
                }
            }
        }
        TextButton(
            onClick = { onSelect(); onEdit() },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag("prayer_timeline_edit_${prayerTime.prayer.name}")
                .semantics {
                    onClick(label = editLabel) { onSelect(); onEdit(); true }
                },
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.textButtonColors(
                contentColor = GreenPrimaryDark,
                containerColor = GreenPrimary.copy(alpha = if (canAdjust) 0.055f else 0.025f),
            ),
            border = BorderStroke(1.dp, GreenPrimary.copy(alpha = if (canAdjust) 0.12f else 0.07f)),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(text = intervalText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    text = timelineModeText(prayerTime.prayer, prayerMinute, config, window),
                    fontSize = 12.sp,
                    color = TextMuted,
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_edit),
                contentDescription = null,
                tint = GreenPrimary,
                modifier = Modifier.padding(start = 12.dp).size(18.dp),
            )
        }
        if (canAdjust || (selected && onDeselect != null)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = if (canAdjust) stringResource(R.string.prayer_timeline_drag_hint) else "",
                    fontSize = 12.sp,
                    color = TextMuted,
                    modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                )
                if (onDeselect != null) {
                    TextButton(
                        onClick = onDeselect,
                        modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = GreenPrimaryDark),
                    ) {
                        Text(text = stringResource(R.string.prayer_timeline_done), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineHandle(
    modifier: Modifier,
    active: Boolean,
    description: String,
    earlierLabel: String,
    laterLabel: String,
    onEarlier: () -> Boolean,
    onLater: () -> Boolean,
    onEdit: () -> Unit,
    gestureModifier: Modifier,
) {
    Box(
        modifier = modifier.size(48.dp)
            .then(gestureModifier)
            .clip(CircleShape)
            .clickable(onClick = onEdit)
            .semantics {
                contentDescription = description
                customActions = listOf(
                    CustomAccessibilityAction(earlierLabel, onEarlier),
                    CustomAccessibilityAction(laterLabel, onLater),
                )
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (active) drawCircle(GreenPrimary.copy(alpha = 0.10f), 22.dp.toPx())
            val topLeft = Offset(center.x - 9.dp.toPx(), center.y - 13.dp.toPx())
            val thumbSize = Size(18.dp.toPx(), 26.dp.toPx())
            val radius = CornerRadius(6.dp.toPx())
            drawRoundRect(if (active) GreenPrimary else Color.White, topLeft, thumbSize, radius)
            drawRoundRect(if (active) GreenPrimaryDark else GreenPrimary, topLeft, thumbSize, radius, style = Stroke(1.5.dp.toPx()))
            for (grip in listOf(-2.dp.toPx(), 2.dp.toPx())) {
                drawLine(
                    if (active) Color.White else GreenPrimary.copy(alpha = 0.65f),
                    Offset(center.x + grip, center.y - 5.dp.toPx()),
                    Offset(center.x + grip, center.y + 5.dp.toPx()),
                    1.dp.toPx(), StrokeCap.Round,
                )
            }
        }
    }
}

/** Wait for horizontal slop without consuming down: the enclosing vertical scroll can win. */
@Composable
private fun Modifier.timelineHorizontalDrag(
    enabled: Boolean,
    gestureKey: Any,
    canStart: (Offset) -> Boolean = { true },
    onStart: () -> Unit,
    onDelta: (Float) -> Unit,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
): Modifier {
    val currentCanStart by rememberUpdatedState(canStart)
    val currentStart by rememberUpdatedState(onStart)
    val currentDelta by rememberUpdatedState(onDelta)
    val currentFinish by rememberUpdatedState(onFinish)
    val currentCancel by rememberUpdatedState(onCancel)
    return pointerInput(enabled, gestureKey) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!currentCanStart(down.position)) return@awaitEachGesture
            var overSlop = 0f
            val accepted = awaitHorizontalTouchSlopOrCancellation(down.id) { change, amount ->
                overSlop = amount
                change.consume()
            } ?: return@awaitEachGesture
            // A second pointer may have passed the initial check before the first
            // one crossed slop. It must not replace or cancel that active draft.
            if (!currentCanStart(down.position)) return@awaitEachGesture
            var completed = false
            try {
                currentStart()
                currentDelta(overSlop)
                val released = horizontalDrag(accepted.id) { change ->
                    currentDelta(change.positionChange().x)
                    change.consume()
                }
                if (released) {
                    currentFinish()
                    completed = true
                }
            } finally {
                if (!completed) currentCancel()
            }
        }
    }
}

private fun shiftedTimelineWindow(window: PrayerTimelineWindow, part: TimelineDragPart, delta: Long): PrayerTimelineWindow? {
    val start = window.startMinutes.toLong() + if (part != TimelineDragPart.End) delta else 0L
    val end = window.endMinutes.toLong() + if (part != TimelineDragPart.Start) delta else 0L
    if (start !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() ||
        end !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() || end < start || end - start > Int.MAX_VALUE
    ) return null
    return PrayerTimelineWindow(start.toInt(), end.toInt())
}

@Composable
private fun timelineClockText(absoluteMinute: Int): String {
    val day = Math.floorDiv(absoluteMinute, 1440)
    val clock = Math.floorMod(absoluteMinute, 1440)
    val time = "\u2066${String.format(Locale.US, "%02d:%02d", clock / 60, clock % 60)}\u2069"
    val suffix = when {
        day == -1 -> stringResource(R.string.prayer_timeline_previous_day)
        day == 1 -> stringResource(R.string.prayer_timeline_next_day)
        day < -1 -> stringResource(R.string.prayer_timeline_days_earlier, -day)
        day > 1 -> stringResource(R.string.prayer_timeline_days_later, day)
        else -> return time
    }
    return "$time ($suffix)"
}

@Composable
private fun timelineModeText(prayer: Prayer, prayerMinute: Int, config: PrayerSilenceConfig, window: PrayerTimelineWindow): String {
    val offset = window.startMinutes.toLong() - prayerMinute
    val start = when {
        config.delayMode == DelayMode.FIXED_TIME && config.delayFixedHour >= 0 && config.delayFixedMinute >= 0 ->
            stringResource(R.string.prayer_timeline_fixed_start)
        offset < 0 -> stringResource(R.string.prayer_timeline_before_prayer, abs(offset))
        offset > 0 -> stringResource(R.string.prayer_timeline_after_prayer, offset)
        else -> stringResource(R.string.prayer_timeline_at_prayer)
    }
    val end = if (config.mode == SilenceMode.FIXED_TIME && config.fixedHour >= 0 && config.fixedMinute >= 0) {
        stringResource(R.string.prayer_timeline_fixed_end)
    } else {
        stringResource(R.string.prayer_timeline_duration, window.durationMinutes)
    }
    val recurrence = when (prayer) {
        Prayer.JOMOAA -> R.string.prayer_timeline_friday_mode
        Prayer.AID_FITR, Prayer.AID_ADHA -> R.string.prayer_timeline_eid_mode
        else -> R.string.prayer_timeline_daily_mode
    }
    return stringResource(recurrence, start, end)
}

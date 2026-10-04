package com.tunisianprayertimes.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.PrayerWakeSubAlarm
import com.tunisianprayertimes.R
import com.tunisianprayertimes.ui.theme.Gold
import com.tunisianprayertimes.ui.theme.GreenPrimaryDark
import kotlin.math.abs
import kotlin.math.roundToInt

internal enum class WakeStopState { RUNG, NEXT, UPCOMING }

/** One ring of an alarm's coming occurrence: an extra alarm, or the main alarm at offset 0. */
internal data class WakeTimelineStop(
    val atMillis: Long,
    val signedOffsetMinutes: Int,
    val state: WakeStopState,
) {
    val isMain: Boolean get() = signedOffsetMinutes == 0
}

private const val MINUTE_MILLIS = 60_000L

/**
 * Every ring of the occurrence that [nextTriggerAtMillis] belongs to, earliest first. Stops before the
 * next trigger have rung, the stop at it rings next. [occurrenceAtMillis] is the main alarm's time.
 */
internal fun wakeTimelineStops(
    subAlarms: List<PrayerWakeSubAlarm>,
    occurrenceAtMillis: Long,
    nextTriggerAtMillis: Long,
): List<WakeTimelineStop> {
    val offsets = (subAlarms.map { it.signedOffsetMinutes } + 0).distinct().sorted()
    return offsets.map { offset ->
        val atMillis = occurrenceAtMillis + offset * MINUTE_MILLIS
        WakeTimelineStop(
            atMillis = atMillis,
            signedOffsetMinutes = offset,
            state = when {
                atMillis < nextTriggerAtMillis -> WakeStopState.RUNG
                atMillis == nextTriggerAtMillis -> WakeStopState.NEXT
                else -> WakeStopState.UPCOMING
            },
        )
    }
}

private val TimelineNodeZone = 36.dp
private val TimelineMinCell = 46.dp
private val TimelineMaxCell = 96.dp
private val TimelineSegmentInset = 12.dp
private val TimelinePanelShape = RoundedCornerShape(12.dp)

/**
 * The Alarms hero's strip of every ring of the next occurrence. The UI is Arabic-only, so the first
 * stop sits on the right like the editor's timeline. Cells are evenly spaced, not to scale, because
 * extras are often a few minutes apart on a 180-minute range; the gap is written under each time.
 * When the stops no longer fit (large text, many extras) the strip scrolls and keeps the next one in view.
 */
@Composable
internal fun WakeNextAlarmTimeline(
    stops: List<WakeTimelineStop>,
    nowMillis: Long,
    formatTime: (Long) -> String,
    modifier: Modifier = Modifier,
) {
    if (stops.isEmpty()) return
    val density = LocalDensity.current
    val scrollState = rememberScrollState()
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clip(TimelinePanelShape)
            .background(Color(0xFF002620).copy(alpha = 0.52f))
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)), TimelinePanelShape)
            .padding(horizontal = 6.dp),
    ) {
        val count = stops.size
        val minCell = TimelineMinCell * maxOf(1f, density.fontScale)
        val cell = maxOf(minOf(maxWidth / count, TimelineMaxCell), minCell)
        val contentWidth = cell * count
        val scrolls = contentWidth > maxWidth + 0.5.dp

        val nextIndex = stops.indexOfFirst { it.state == WakeStopState.NEXT }
        val cellPx = with(density) { cell.toPx() }
        val viewportPx = with(density) { maxWidth.toPx() }
        LaunchedEffect(nextIndex, scrolls, cellPx, viewportPx) {
            if (scrolls && nextIndex >= 0) {
                val target = ((nextIndex + 0.5f) * cellPx - viewportPx / 2f).roundToInt().coerceAtLeast(0)
                scrollState.animateScrollTo(target)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (scrolls) Modifier.horizontalScroll(scrollState) else Modifier),
            contentAlignment = Alignment.TopCenter,
        ) {
            TimelineStops(
                stops = stops,
                nowMillis = nowMillis,
                formatTime = formatTime,
                cellWidth = cell,
                modifier = Modifier.width(contentWidth),
            )
        }
    }
}

@Composable
private fun TimelineStops(
    stops: List<WakeTimelineStop>,
    nowMillis: Long,
    formatTime: (Long) -> String,
    cellWidth: Dp,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val cellPx = size.width / stops.size
            val centerY = TimelineNodeZone.toPx() / 2f
            val rtl = layoutDirection == LayoutDirection.Rtl
            val direction = if (rtl) -1f else 1f
            val inset = TimelineSegmentInset.toPx()
            val strokeWidth = 2.dp.toPx()
            val dashes = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx()))
            fun centerX(index: Int): Float =
                if (rtl) size.width - (index + 0.5f) * cellPx else (index + 0.5f) * cellPx

            for (i in 0 until stops.size - 1) {
                val from = centerX(i) + direction * inset
                val to = centerX(i + 1) - direction * inset
                val earlier = stops[i]
                val later = stops[i + 1]
                when {
                    later.state == WakeStopState.RUNG -> drawLine(
                        color = StopGold.copy(alpha = 0.9f),
                        start = Offset(from, centerY),
                        end = Offset(to, centerY),
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round,
                    )
                    earlier.state == WakeStopState.RUNG -> {
                        drawLine(
                            color = StopGold.copy(alpha = 0.6f),
                            start = Offset(from, centerY),
                            end = Offset(to, centerY),
                            strokeWidth = strokeWidth,
                            pathEffect = dashes,
                        )
                        val progress = ((nowMillis - earlier.atMillis).toFloat() /
                            (later.atMillis - earlier.atMillis).toFloat()).coerceIn(0f, 1f)
                        drawLine(
                            color = StopGold.copy(alpha = 0.95f),
                            start = Offset(from, centerY),
                            end = Offset(from + (to - from) * progress, centerY),
                            strokeWidth = strokeWidth,
                            cap = StrokeCap.Round,
                        )
                    }
                    else -> drawLine(
                        color = Color.White.copy(alpha = 0.3f),
                        start = Offset(from, centerY),
                        end = Offset(to, centerY),
                        strokeWidth = strokeWidth,
                        pathEffect = dashes,
                    )
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            stops.forEach { stop ->
                TimelineStop(stop = stop, timeText = formatTime(stop.atMillis), modifier = Modifier.width(cellWidth))
            }
        }
    }
}

@Composable
private fun TimelineStop(
    stop: WakeTimelineStop,
    timeText: String,
    modifier: Modifier = Modifier,
) {
    val gapText = when {
        stop.isMain -> stringResource(R.string.wake_timeline_main)
        stop.signedOffsetMinutes < 0 ->
            stringResource(R.string.wake_timeline_before, abs(stop.signedOffsetMinutes).toString())
        else -> stringResource(R.string.wake_timeline_after, stop.signedOffsetMinutes.toString())
    }
    val description = if (stop.isMain) {
        stringResource(R.string.wake_timeline_a11y_main, timeText)
    } else {
        stringResource(R.string.wake_timeline_a11y_extra, timeText, gapText)
    }
    val stateText = when (stop.state) {
        WakeStopState.RUNG -> stringResource(R.string.wake_timeline_state_rung)
        WakeStopState.NEXT -> stringResource(R.string.wake_timeline_state_next)
        WakeStopState.UPCOMING -> null
    }
    val rung = stop.state == WakeStopState.RUNG
    val timeColor = when (stop.state) {
        WakeStopState.NEXT -> StopGold
        WakeStopState.RUNG -> Color.White.copy(alpha = 0.45f)
        WakeStopState.UPCOMING -> Color.White
    }
    val gapColor = when {
        stop.isMain && rung -> StopGold.copy(alpha = 0.55f)
        stop.isMain -> StopGold
        rung -> Color.White.copy(alpha = 0.45f)
        else -> Color.White.copy(alpha = 0.72f)
    }

    Column(
        modifier = modifier
            .padding(bottom = 6.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                if (stateText != null) stateDescription = stateText
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.height(TimelineNodeZone),
            contentAlignment = Alignment.Center,
        ) {
            TimelineNode(isMain = stop.isMain, state = stop.state)
        }
        Text(
            text = timeText,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Bold,
            color = timeColor,
            maxLines = 1,
            softWrap = false,
            style = TextStyle(textDirection = TextDirection.Ltr, fontFeatureSettings = "tnum"),
        )
        Text(
            text = gapText,
            fontSize = 10.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = gapColor,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun TimelineNode(isMain: Boolean, state: WakeStopState) {
    val next = state == WakeStopState.NEXT
    val rung = state == WakeStopState.RUNG
    val size = when {
        isMain -> 26.dp
        rung -> 16.dp
        else -> 14.dp
    }
    val fill = when {
        next -> StopGold
        isMain && rung -> Gold.copy(alpha = 0.16f)
        isMain -> Gold.copy(alpha = 0.22f)
        rung -> Color.White.copy(alpha = 0.25f)
        else -> Color.Transparent
    }
    val border = when {
        next -> BorderStroke(if (isMain) 1.5.dp else 2.dp, StopGold)
        isMain && rung -> BorderStroke(1.5.dp, StopGold.copy(alpha = 0.5f))
        isMain -> BorderStroke(1.5.dp, StopGold)
        rung -> BorderStroke(1.5.dp, Color.White.copy(alpha = 0.45f))
        else -> BorderStroke(2.dp, Color.White.copy(alpha = 0.88f))
    }
    val iconRes = when {
        rung -> R.drawable.ic_check
        isMain -> R.drawable.ic_tab_alarms
        else -> null
    }
    val iconTint = when {
        next -> GreenPrimaryDark
        isMain && rung -> StopGold.copy(alpha = 0.8f)
        isMain -> StopGold
        else -> Color.White.copy(alpha = 0.85f)
    }

    // The soft ring marks the stop that rings next.
    Box(
        modifier = Modifier
            .size(size + 10.dp)
            .then(if (next) Modifier.background(StopGold.copy(alpha = 0.28f), CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .background(fill, CircleShape)
                .border(border, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (iconRes != null) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(if (isMain) 16.dp else 10.dp),
                )
            }
        }
    }
}

private val StopGold = HeroIconRingIconTint

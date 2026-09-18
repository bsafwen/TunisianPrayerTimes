package com.tunisianprayertimes.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.tunisianprayertimes.DelayMode
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerSilenceConfig
import com.tunisianprayertimes.PrayerTime
import com.tunisianprayertimes.R
import com.tunisianprayertimes.SilenceMode
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private enum class SilenceDragPart { Start, End, Window }

/** Physical timeline edge that is being pushed outward during a drag. */
private enum class SilenceEdgeSide { EARLIER, LATER }

private data class SilenceDragState(
    val part: SilenceDragPart,
    /** Window at the moment the gesture started; decides whether release commits. */
    val startWindow: PrayerTimelineWindow,
    /** Rebased reference window for pointer-to-value mapping after domain growth. */
    val baseWindow: PrayerTimelineWindow,
    val window: PrayerTimelineWindow,
    val accumulatedPixels: Float,
    val widthPx: Float,
    /** Live viewing domain for this gesture; grows only through deliberate edge intent. */
    val scale: PrayerTimelineScale,
    val edgeSide: SilenceEdgeSide?,
    val edgeArmed: Boolean,
)

private enum class LegendAnchor { LEFT, CENTER, RIGHT }

private data class LegendEntry(
    val offsetMinutes: Int,
    val text: String,
    val style: TextStyle,
    val layout: TextLayoutResult,
    val anchor: LegendAnchor,
    val mandatory: Boolean,
)

private val TickStepCandidates = listOf(15, 30, 60, 120, 180, 360, 720, 1440, 2880)
private const val MaxTickIntervals = 8

/** Pointer distance beyond a physical track edge that counts as outward intent. */
private val EdgeOutwardOvershoot = 4.dp

/**
 * Physical top-left alignment. The timeline positions its labels, tooltip and
 * handle semantics with raw pixel offsets from the physical left, so the Box
 * must not mirror those children again for the RTL layout direction.
 */
private val AbsoluteTopLeft = object : Alignment {
    override fun align(size: IntSize, space: IntSize, layoutDirection: LayoutDirection): IntOffset =
        IntOffset.Zero
}

/**
 * Always-interactive prayer-relative range slider. Both handles rest visibly on
 * the track and adjust their endpoint after horizontal touch slop only, so the
 * enclosing vertical scroll keeps priority and nothing changes before drag
 * intent is established. Every prayer shares [scale] so rows are comparable,
 * while the adhan marker stays fixed at offset zero.
 */
@Composable
internal fun PrayerSilenceRangeSlider(
    prayer: Prayer,
    prayerName: String,
    prayerTime: PrayerTime,
    config: PrayerSilenceConfig,
    scale: PrayerTimelineScale,
    window: PrayerTimelineWindow,
    enabled: Boolean,
    onPreviewWindow: (PrayerTimelineWindow?) -> Unit,
    onCommitWindow: (PrayerTimelineWindow) -> Unit,
    onEditEndpoint: (SilenceEndpoint) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hapticFeedback = LocalHapticFeedback.current
    val prayerMinutes = prayerMinutesOfDay(prayerTime)
    val validWindow = window.endMinutes >= window.startMinutes
    val canAdjust = enabled && validWindow
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    val startOffset = window.startMinutes - prayerMinutes
    val endOffset = window.endMinutes - prayerMinutes
    val startRelation = relationshipText(startOffset)
    val endRelation = relationshipText(endOffset)
    val fixedStart = config.delayMode == DelayMode.FIXED_TIME &&
        config.delayFixedHour >= 0 && config.delayFixedMinute >= 0
    val fixedEnd = config.mode == SilenceMode.FIXED_TIME &&
        config.fixedHour >= 0 && config.fixedMinute >= 0
    val startState = if (fixedStart) {
        stringResource(R.string.prayer_silence_fixed_time_desc, prayerClockText(window.startMinutes))
    } else {
        startRelation
    }
    val endState = if (fixedEnd) {
        stringResource(R.string.prayer_silence_fixed_time_desc, prayerClockText(window.endMinutes))
    } else {
        endRelation
    }
    val startDescription = stringResource(R.string.prayer_silence_start_handle_desc, prayerName, startState)
    val endDescription = stringResource(R.string.prayer_silence_end_handle_desc, prayerName, endState)

    var drag by remember(prayerTime, config, scale) { mutableStateOf<SilenceDragState?>(null) }
    // The active row may widen its own viewing domain while a deliberate edge drag is
    // in progress; every other row keeps the shared scale until the drag commits.
    val activeScale = drag?.scale ?: scale
    val currentWindow by rememberUpdatedState(window)
    val currentConfig by rememberUpdatedState(config)
    val currentCanAdjust by rememberUpdatedState(canAdjust)
    val currentPreviewWindow by rememberUpdatedState(onPreviewWindow)

    val earlierLabel = stringResource(R.string.prayer_silence_adjust_earlier)
    val laterLabel = stringResource(R.string.prayer_silence_adjust_later)
    val atAdhanLabel = stringResource(R.string.prayer_silence_at_adhan)
    val adhanLabel = stringResource(R.string.prayer_silence_adhan_label)
    val extendEarlierCue = stringResource(R.string.prayer_silence_extend_earlier)
    val extendLaterCue = stringResource(R.string.prayer_silence_extend_later)
    val valueLabelStyle = TextStyle(
        fontSize = PrayerSilenceTypography.ValueLabel,
        fontWeight = FontWeight.SemiBold,
    )
    val adhanLabelStyle = TextStyle(
        fontSize = PrayerSilenceTypography.AdhanLabel,
        fontWeight = FontWeight.SemiBold,
    )
    val scaleLabelStyle = TextStyle(
        fontSize = PrayerSilenceTypography.ScaleLabel,
        textDirection = TextDirection.Ltr,
    )
    val tooltipPaddingHorizontal = PrayerSilenceDimens.SliderTooltipPaddingHorizontal
    val tooltipPaddingVertical = PrayerSilenceDimens.SliderTooltipPaddingVertical
    val scaleBeforeText = stringResource(
        R.string.prayer_silence_scale_before,
        abs(activeScale.startOffsetMinutes),
    )
    val scaleAfterText = stringResource(R.string.prayer_silence_scale_after, activeScale.endOffsetMinutes)
    // Measurement drives both the stable feedback band and the shared legend
    // baseline, so the layout adapts to font scale instead of fixed heights.
    val tooltipProbeText = stringResource(
        R.string.prayer_silence_value_label,
        "00:00",
        atAdhanLabel,
    )
    val tooltipProbe = textMeasurer.measure(tooltipProbeText, valueLabelStyle, maxLines = 1)
    // Reference ticks adapt to an extended domain instead of crowding the legend.
    val tickStepMinutes = TickStepCandidates.firstOrNull { activeScale.spanMinutes / it <= MaxTickIntervals }
        ?: TickStepCandidates.last()
    val firstTickOffset = Math.floorDiv(activeScale.startOffsetMinutes, tickStepMinutes) * tickStepMinutes
    val tickOffsets = buildList {
        var offset = firstTickOffset
        while (offset <= activeScale.endOffsetMinutes) {
            if (offset >= activeScale.startOffsetMinutes) add(offset)
            offset += tickStepMinutes
        }
    }
    val legendEntries = mutableListOf<LegendEntry>()
    legendEntries += LegendEntry(
        offsetMinutes = activeScale.startOffsetMinutes,
        text = scaleBeforeText,
        style = scaleLabelStyle,
        layout = textMeasurer.measure(scaleBeforeText, scaleLabelStyle, maxLines = 1),
        // The earliest offset now sits at the physical right edge.
        anchor = LegendAnchor.RIGHT,
        mandatory = true,
    )
    legendEntries += LegendEntry(
        offsetMinutes = 0,
        text = adhanLabel,
        style = adhanLabelStyle,
        layout = textMeasurer.measure(adhanLabel, adhanLabelStyle, maxLines = 1),
        anchor = LegendAnchor.CENTER,
        mandatory = true,
    )
    tickOffsets.forEach { offset ->
        if (offset == activeScale.startOffsetMinutes || offset == activeScale.endOffsetMinutes || offset == 0) {
            return@forEach
        }
        val text = if (offset > 0) {
            stringResource(R.string.prayer_silence_scale_after, offset)
        } else {
            stringResource(R.string.prayer_silence_scale_before, -offset)
        }
        legendEntries += LegendEntry(
            offsetMinutes = offset,
            text = text,
            style = scaleLabelStyle,
            layout = textMeasurer.measure(text, scaleLabelStyle, maxLines = 1),
            anchor = LegendAnchor.CENTER,
            mandatory = false,
        )
    }
    legendEntries += LegendEntry(
        offsetMinutes = activeScale.endOffsetMinutes,
        text = scaleAfterText,
        style = scaleLabelStyle,
        layout = textMeasurer.measure(scaleAfterText, scaleLabelStyle, maxLines = 1),
        // The latest offset now sits at the physical left edge.
        anchor = LegendAnchor.LEFT,
        mandatory = true,
    )
    val legendBaselinePx = legendEntries.maxOf { it.layout.firstBaseline }
    val trackHeightPx = with(density) { PrayerSilenceDimens.SliderTrackHeight.toPx() }
    val valueBandPx = maxOf(
        tooltipProbe.size.height.toFloat() +
            with(density) { tooltipPaddingVertical.toPx() } * 2f,
        with(density) { PrayerSilenceDimens.SliderValueLabelHeight.toPx() },
    )
    val labelsBandPx = maxOf(
        legendEntries.maxOf { entry ->
            (legendBaselinePx - entry.layout.firstBaseline) + entry.layout.size.height
        },
        with(density) { PrayerSilenceDimens.SliderLabelsHeight.toPx() },
    )
    val sliderHeight = with(density) {
        (valueBandPx + trackHeightPx + labelsBandPx).toDp()
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(sliderHeight)
            .testTag("prayer_silence_slider_${prayer.name}")
            .semantics { if (!enabled) disabled() },
        contentAlignment = AbsoluteTopLeft,
    ) {
        val trackThicknessPx = with(density) { PrayerSilenceDimens.SliderTrackThickness.toPx() }
        val handleRadiusPx = with(density) { (PrayerSilenceDimens.SliderHandleSize / 2).toPx() }
        val touchRadiusPx = with(density) { (PrayerSilenceDimens.SliderHandleTouchTarget / 2).toPx() }
        val edgeInsetPx = handleRadiusPx + with(density) { 3.dp.toPx() }
        val trackWidthPx = (constraints.maxWidth - edgeInsetPx * 2f).coerceAtLeast(1f)
        val centerYPx = valueBandPx + trackHeightPx / 2f
        val labelsTopPx = valueBandPx + trackHeightPx

        // Arabic right-to-left timeline: earlier times (negative offsets) are on
        // the physical right, later times on the physical left, and adhan (0)
        // sits 20% of the track in from the right on the standard domain.
        fun xForOffset(offset: Int): Float =
            constraints.maxWidth - edgeInsetPx - activeScale.fractionForOffset(offset) * trackWidthPx

        fun offsetForX(x: Float): Int =
            activeScale.offsetForFraction(
                ((constraints.maxWidth - edgeInsetPx - x) / trackWidthPx).coerceIn(0f, 1f),
            )

        fun visibleWindow(): PrayerTimelineWindow = drag?.window ?: currentWindow

        fun hitPart(position: Offset): SilenceDragPart? {
            if (abs(position.y - centerYPx) > touchRadiusPx) return null
            val visible = visibleWindow()
            val visibleStartX = xForOffset(visible.startMinutes - prayerMinutes)
            val visibleEndX = xForOffset(visible.endMinutes - prayerMinutes)
            val distanceStart = abs(position.x - visibleStartX)
            val distanceEnd = abs(position.x - visibleEndX)
            return when {
                distanceStart <= touchRadiusPx && distanceStart <= distanceEnd -> SilenceDragPart.Start
                distanceEnd <= touchRadiusPx -> SilenceDragPart.End
                // The window spans between the handles in either physical order.
                position.x in minOf(visibleStartX, visibleEndX)..maxOf(visibleStartX, visibleEndX) ->
                    SilenceDragPart.Window
                else -> null
            }
        }

        fun shifted(
            base: PrayerTimelineWindow,
            part: SilenceDragPart,
            deltaMinutes: Int,
            liveScale: PrayerTimelineScale = activeScale,
        ): PrayerTimelineWindow? =
            silenceShiftedWindow(
                window = base,
                part = part,
                deltaMinutes = deltaMinutes,
                scale = liveScale,
                prayerMinutes = prayerMinutes,
            )

        fun isValid(candidate: PrayerTimelineWindow): Boolean =
            prayerTimelineConfigForWindow(prayerTime, currentConfig, candidate) != null

        fun nudgeTo(endpoint: SilenceEndpoint, targetOffset: Int): Boolean {
            if (!currentCanAdjust) return false
            val visible = visibleWindow()
            val visibleStartOffset = visible.startMinutes - prayerMinutes
            val visibleEndOffset = visible.endMinutes - prayerMinutes
            val candidate = when (endpoint) {
                SilenceEndpoint.START -> {
                    val maxStart = minOf(activeScale.endOffsetMinutes, visibleEndOffset - 1)
                    if (maxStart < activeScale.startOffsetMinutes) return false
                    visible.copy(
                        startMinutes = prayerMinutes + targetOffset.coerceIn(
                            activeScale.startOffsetMinutes,
                            maxStart,
                        ),
                    )
                }
                SilenceEndpoint.END -> {
                    val minEnd = maxOf(activeScale.startOffsetMinutes + 1, visibleStartOffset + 1)
                    if (minEnd > activeScale.endOffsetMinutes) return false
                    visible.copy(
                        endMinutes = prayerMinutes + targetOffset.coerceIn(minEnd, activeScale.endOffsetMinutes),
                    )
                }
            }
            if (candidate == visible || !isValid(candidate)) return false
            onCommitWindow(candidate)
            return true
        }

        fun nudge(part: SilenceDragPart, deltaMinutes: Int): Boolean {
            if (!currentCanAdjust) return false
            val candidate = shifted(visibleWindow(), part, deltaMinutes, activeScale) ?: return false
            if (!isValid(candidate)) return false
            onCommitWindow(candidate)
            return true
        }

        /**
         * The physical edge is being pushed outward while the active endpoint sits on
         * its boundary. Reaching an edge is not enough: the pointer must be beyond it.
         */
        fun edgeSideFor(
            part: SilenceDragPart,
            visible: PrayerTimelineWindow,
            pointerX: Float,
            liveScale: PrayerTimelineScale,
        ): SilenceEdgeSide? {
            val trackLeftPx = edgeInsetPx
            val trackRightPx = constraints.maxWidth - edgeInsetPx
            val overshootPx = with(density) { EdgeOutwardOvershoot.toPx() }
            val pushingEarlier = pointerX - trackRightPx > overshootPx
            val pushingLater = trackLeftPx - pointerX > overshootPx
            val startOffset = visible.startMinutes - prayerMinutes
            val endOffset = visible.endMinutes - prayerMinutes
            return when (part) {
                SilenceDragPart.Start -> if (
                    startOffset <= liveScale.startOffsetMinutes && pushingEarlier
                ) {
                    SilenceEdgeSide.EARLIER
                } else {
                    null
                }
                SilenceDragPart.End -> if (
                    endOffset >= liveScale.endOffsetMinutes && pushingLater
                ) {
                    SilenceEdgeSide.LATER
                } else {
                    null
                }
                SilenceDragPart.Window -> when {
                    startOffset <= liveScale.startOffsetMinutes && pushingEarlier -> SilenceEdgeSide.EARLIER
                    endOffset >= liveScale.endOffsetMinutes && pushingLater -> SilenceEdgeSide.LATER
                    else -> null
                }
            }
        }

        fun updateDrag(deltaPixels: Float, pointerX: Float) {
            val current = drag ?: return
            val pixels = current.accumulatedPixels + deltaPixels
            val liveScale = current.scale
            // Moving the pointer right goes back in time on the RTL timeline.
            val deltaMinutes = (-pixels / current.widthPx * liveScale.spanMinutes).roundToInt()
            val candidate = shifted(current.baseWindow, current.part, deltaMinutes, liveScale)
            val validated = candidate?.takeIf { isValid(it) } ?: current.window
            val edgeSide = edgeSideFor(current.part, validated, pointerX, liveScale)
            val stillArmed = current.edgeArmed && edgeSide != null && edgeSide == current.edgeSide
            if (validated != current.window) {
                val previousOffset = endpointOffset(current.window, current.part, prayerMinutes)
                val nextOffset = endpointOffset(validated, current.part, prayerMinutes)
                val crossedZero = nextOffset == 0 && previousOffset != 0
                val crossedStep = Math.floorDiv(nextOffset, 5) != Math.floorDiv(previousOffset, 5)
                if (crossedZero || crossedStep) {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            }
            drag = current.copy(
                accumulatedPixels = pixels,
                window = validated,
                edgeSide = edgeSide,
                edgeArmed = stillArmed,
            )
            if (validated != current.window) currentPreviewWindow(validated)
        }

        /**
         * One deliberate extension step: advance the active endpoint by one minute and
         * grow the viewing domain to keep it on the physical edge. The untouched
         * endpoint, its rule and its resolved time do not change.
         */
        fun extendEdgeStep(): Boolean {
            val current = drag ?: return false
            val side = current.edgeSide ?: return false
            if (!current.edgeArmed || !currentCanAdjust) return false
            val earlier = side == SilenceEdgeSide.EARLIER
            if (current.part == SilenceDragPart.Start && !earlier) return false
            if (current.part == SilenceDragPart.End && earlier) return false
            val step = TIMELINE_EDGE_STEP_MINUTES
            val moved = when (current.part) {
                SilenceDragPart.Start -> current.window.copy(
                    startMinutes = current.window.startMinutes - step,
                )
                SilenceDragPart.End -> current.window.copy(
                    endMinutes = current.window.endMinutes + step,
                )
                SilenceDragPart.Window -> if (earlier) {
                    current.window.copy(
                        startMinutes = current.window.startMinutes - step,
                        endMinutes = current.window.endMinutes - step,
                    )
                } else {
                    current.window.copy(
                        startMinutes = current.window.startMinutes + step,
                        endMinutes = current.window.endMinutes + step,
                    )
                }
            }
            if (moved.endMinutes < moved.startMinutes || !isValid(moved)) return false
            val liveScale = current.scale
            val extendedScale = if (earlier) {
                if (liveScale.startOffsetMinutes <= Int.MIN_VALUE + 1) return false
                liveScale.copy(startOffsetMinutes = liveScale.startOffsetMinutes - step)
            } else {
                if (liveScale.endOffsetMinutes >= Int.MAX_VALUE - 1) return false
                liveScale.copy(endOffsetMinutes = liveScale.endOffsetMinutes + step)
            }
            // Rebase the gesture so the domain growth itself cannot rescale the value.
            drag = current.copy(
                baseWindow = moved,
                window = moved,
                accumulatedPixels = 0f,
                scale = extendedScale,
            )
            currentPreviewWindow(moved)
            return true
        }

        fun finishDrag() {
            val completed = drag
            drag = null
            if (completed != null && completed.window != completed.startWindow) {
                currentPreviewWindow(null)
                onCommitWindow(completed.window)
            } else {
                currentPreviewWindow(null)
            }
        }

        fun handleTrackTap(position: Offset) {
            if (abs(position.y - centerYPx) > touchRadiusPx) return
            if (position.x < edgeInsetPx || position.x > constraints.maxWidth - edgeInsetPx) return
            val visible = visibleWindow()
            val visibleStartOffset = visible.startMinutes - prayerMinutes
            val visibleEndOffset = visible.endMinutes - prayerMinutes
            val target = offsetForX(position.x)
            val moveStart = abs(target - visibleStartOffset) <= abs(target - visibleEndOffset)
            val candidate = if (moveStart) {
                val maxStart = visibleEndOffset - 1
                if (maxStart < activeScale.startOffsetMinutes) return
                visible.copy(
                    startMinutes = prayerMinutes + target.coerceIn(activeScale.startOffsetMinutes, maxStart),
                )
            } else {
                val minEnd = maxOf(activeScale.startOffsetMinutes + 1, visibleStartOffset + 1)
                if (minEnd > activeScale.endOffsetMinutes) return
                visible.copy(
                    endMinutes = prayerMinutes + target.coerceIn(minEnd, activeScale.endOffsetMinutes),
                )
            }
            if (candidate == visible || !isValid(candidate)) return
            onCommitWindow(candidate)
        }

        fun handleTap(position: Offset) {
            if (!currentCanAdjust) return
            when (hitPart(position)) {
                SilenceDragPart.Start -> onEditEndpoint(SilenceEndpoint.START)
                SilenceDragPart.End -> onEditEndpoint(SilenceEndpoint.END)
                SilenceDragPart.Window -> handleTrackTap(position)
                null -> handleTrackTap(position)
            }
        }

        // Deliberate-edge arming/extension ticker. Reaching an edge alone arms nothing:
        // the pointer must stay beyond the physical edge, and only then does the
        // domain grow one minute per step. Any inward move, release or cancellation
        // changes the key and stops the loop without momentum.
        val edgeTickerKey = drag?.let { state -> state.edgeSide?.let { side -> side to state.edgeArmed } }
        LaunchedEffect(edgeTickerKey) {
            val key = edgeTickerKey ?: return@LaunchedEffect
            val (side, armed) = key
            if (!armed) {
                delay(TIMELINE_EDGE_ARM_DELAY_MS)
                val current = drag
                if (current != null && current.edgeSide == side && !current.edgeArmed) {
                    drag = current.copy(edgeArmed = true)
                }
            } else {
                while (true) {
                    delay(TIMELINE_EDGE_STEP_INTERVAL_MS)
                    val current = drag ?: break
                    if (current.edgeSide != side || !current.edgeArmed) break
                    if (!extendEdgeStep()) {
                        // A genuine scheduling constraint stops deliberate intent for
                        // this gesture: drop the highlight and cue instead of retrying.
                        val stopped = drag
                        if (stopped != null) {
                            drag = stopped.copy(edgeSide = null, edgeArmed = false)
                        }
                        break
                    }
                }
            }
        }

        val dragModifier = Modifier.silenceSliderDrag(
            enabled = canAdjust,
            gestureKey = listOf(prayerTime, config, scale, constraints.maxWidth),
            choosePart = { position -> hitPart(position) },
            onStart = { part ->
                val origin = currentWindow
                drag = SilenceDragState(
                    part = part,
                    startWindow = origin,
                    baseWindow = origin,
                    window = origin,
                    accumulatedPixels = 0f,
                    widthPx = trackWidthPx,
                    scale = scale,
                    edgeSide = null,
                    edgeArmed = false,
                )
                currentPreviewWindow(origin)
            },
            onDelta = { deltaPixels, pointer -> updateDrag(deltaPixels, pointer.x) },
            onFinish = { finishDrag() },
            onCancel = {
                drag = null
                currentPreviewWindow(null)
            },
        )
        val tapModifier = Modifier.pointerInput(canAdjust, prayerTime, scale, constraints.maxWidth) {
            if (!canAdjust) return@pointerInput
            detectTapGestures { position -> handleTap(position) }
        }
        val visible = drag?.window ?: window
        val visibleStartX = xForOffset(visible.startMinutes - prayerMinutes)
        val visibleEndX = xForOffset(visible.endMinutes - prayerMinutes)
        val prayerX = xForOffset(0)

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .then(dragModifier)
                .then(tapModifier),
        ) {
            drawLine(
                PrayerSilencePalette.InactiveTrack,
                Offset(edgeInsetPx, centerYPx),
                Offset(size.width - edgeInsetPx, centerYPx),
                trackThicknessPx,
                StrokeCap.Round,
            )
            if (visibleStartX != visibleEndX) {
                drawLine(
                    PrayerSilencePalette.InteractiveTeal,
                    Offset(minOf(visibleStartX, visibleEndX), centerYPx),
                    Offset(maxOf(visibleStartX, visibleEndX), centerYPx),
                    trackThicknessPx,
                    StrokeCap.Round,
                )
            }
            // Restrained highlight on the edge that is being deliberately pushed
            // outward; drawn under the ticks and handles so they stay readable.
            val highlightedEdge = drag?.edgeSide
            if (highlightedEdge != null) {
                val highlightCenterX = if (highlightedEdge == SilenceEdgeSide.EARLIER) {
                    constraints.maxWidth - edgeInsetPx
                } else {
                    edgeInsetPx
                }
                val highlightHalfWidth = 26.dp.toPx()
                val highlightHalfHeight = trackThicknessPx * 2.4f
                val highlightAlpha = if (drag?.edgeArmed == true) 0.20f else 0.10f
                val highlightLeft = (highlightCenterX - highlightHalfWidth).coerceIn(
                    0f,
                    (constraints.maxWidth - highlightHalfWidth * 2f).coerceAtLeast(0f),
                )
                drawRoundRect(
                    color = PrayerSilencePalette.InteractiveTeal.copy(alpha = highlightAlpha),
                    topLeft = Offset(highlightLeft, centerYPx - highlightHalfHeight),
                    size = Size(highlightHalfWidth * 2f, highlightHalfHeight * 2f),
                    cornerRadius = CornerRadius(highlightHalfWidth),
                )
            }
            // Ticks stay above the fills so they read on both the pale track and
            // the teal selected interval.
            val visibleStartOffset = visible.startMinutes - prayerMinutes
            val visibleEndOffset = visible.endMinutes - prayerMinutes
            tickOffsets.forEach { offset ->
                if (offset == 0) return@forEach
                val tickX = xForOffset(offset)
                val onSelectedRange = visibleStartX != visibleEndX &&
                    offset in visibleStartOffset..visibleEndOffset
                drawLine(
                    color = if (onSelectedRange) {
                        PrayerSilencePalette.TickOnRange
                    } else {
                        PrayerSilencePalette.Tick
                    },
                    start = Offset(tickX, centerYPx - 4.dp.toPx()),
                    end = Offset(tickX, centerYPx + 4.dp.toPx()),
                    strokeWidth = 1.5.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
            val markerTop = centerYPx - 17.dp.toPx()
            drawLine(
                PrayerSilencePalette.GoldAccent,
                Offset(prayerX, markerTop),
                Offset(prayerX, centerYPx + 7.dp.toPx()),
                2.dp.toPx(),
                StrokeCap.Round,
            )
            drawDiamond(
                center = Offset(prayerX, markerTop - 4.dp.toPx()),
                radiusPx = 4.5.dp.toPx(),
                color = PrayerSilencePalette.GoldAccent,
            )
            drawHandle(
                x = visibleStartX,
                centerY = centerYPx,
                radiusPx = handleRadiusPx,
                active = drag?.part == SilenceDragPart.Start,
                borderPx = 2.dp.toPx(),
            )
            drawHandle(
                x = visibleEndX,
                centerY = centerYPx,
                radiusPx = handleRadiusPx,
                active = drag?.part == SilenceDragPart.End,
                borderPx = 2.dp.toPx(),
            )
        }

        val activeDrag = drag
        if (activeDrag != null) {
            val activeX = when (activeDrag.part) {
                SilenceDragPart.Start -> xForOffset(activeDrag.window.startMinutes - prayerMinutes)
                SilenceDragPart.End -> xForOffset(activeDrag.window.endMinutes - prayerMinutes)
                SilenceDragPart.Window -> xForOffset(activeDrag.window.startMinutes - prayerMinutes)
            }
            val activeOffset = endpointOffset(activeDrag.window, activeDrag.part, prayerMinutes)
            val timeText = when (activeDrag.part) {
                SilenceDragPart.End -> prayerClockText(activeDrag.window.endMinutes)
                else -> prayerClockText(activeDrag.window.startMinutes)
            }
            val edgeCue = when (activeDrag.edgeSide) {
                SilenceEdgeSide.EARLIER -> extendEarlierCue
                SilenceEdgeSide.LATER -> extendLaterCue
                null -> null
            }
            val relation = when {
                activeOffset == 0 -> atAdhanLabel
                edgeCue != null -> edgeCue
                else -> relationshipText(activeOffset)
            }
            val fullText = stringResource(R.string.prayer_silence_value_label, timeText, relation)
            val tooltipPaddingHorizontalPx = with(density) { tooltipPaddingHorizontal.toPx() }
            val maxTooltipTextWidthPx = (constraints.maxWidth - tooltipPaddingHorizontalPx * 2f)
                .coerceAtLeast(1f)
            val fullMeasure = textMeasurer.measure(
                text = fullText,
                style = valueLabelStyle,
                maxLines = 1,
                constraints = Constraints(maxWidth = maxTooltipTextWidthPx.toInt().coerceAtLeast(1)),
            )
            val fullFits = fullMeasure.size.width < maxTooltipTextWidthPx
            val chosenText = if (fullFits) fullText else timeText
            val chosenTextWidthPx = if (fullFits) {
                fullMeasure.size.width.toFloat()
            } else {
                textMeasurer.measure(timeText, valueLabelStyle, maxLines = 1).size.width.toFloat()
            }
            val bubbleWidthPx = chosenTextWidthPx + tooltipPaddingHorizontalPx * 2f
            val labelLeft = (activeX - bubbleWidthPx / 2f)
                .coerceIn(0f, (constraints.maxWidth - bubbleWidthPx).coerceAtLeast(0f))
            Box(
                modifier = Modifier
                    .absoluteOffset { IntOffset(labelLeft.roundToInt(), 0) }
                    .clip(RoundedCornerShape(9.dp))
                    .background(PrayerSilencePalette.PrimaryText)
                    .padding(horizontal = tooltipPaddingHorizontal, vertical = tooltipPaddingVertical),
            ) {
                Text(
                    text = chosenText,
                    color = PrayerSilencePalette.OnAccent,
                    style = valueLabelStyle,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }

        val legendGapPx = with(density) { 6.dp.toPx() }
        val placedLabels = mutableListOf<ClosedFloatingPointRange<Float>>()
        val renderedLabels = mutableListOf<Pair<LegendEntry, Float>>()

        fun legendNaturalLeft(entry: LegendEntry): Float {
            val width = entry.layout.size.width.toFloat()
            return when (entry.anchor) {
                LegendAnchor.LEFT -> xForOffset(entry.offsetMinutes)
                LegendAnchor.CENTER -> xForOffset(entry.offsetMinutes) - width / 2f
                LegendAnchor.RIGHT -> xForOffset(entry.offsetMinutes) - width
            }
        }

        fun legendLeft(entry: LegendEntry): Float {
            val width = entry.layout.size.width.toFloat()
            // Mandatory labels (adhan + domain endpoints) clamp to stay inside the
            // content bounds; intermediate labels stay centered under their ticks
            // and are dropped instead of shifted when they would overflow.
            return legendNaturalLeft(entry)
                .coerceIn(0f, (constraints.maxWidth - width).coerceAtLeast(0f))
        }

        fun placeLegendEntry(entry: LegendEntry): Boolean {
            val width = entry.layout.size.width.toFloat()
            if (!entry.mandatory) {
                val natural = legendNaturalLeft(entry)
                if (natural < 0f || natural + width > constraints.maxWidth) return false
            }
            val left = legendLeft(entry)
            val right = left + width
            if (placedLabels.any { existing ->
                    existing.start - legendGapPx < right && left < existing.endInclusive + legendGapPx
                }
            ) {
                return false
            }
            renderedLabels += entry to left
            placedLabels += left..right
            return true
        }

        // Adhan first, then the domain endpoints, then intermediate labels as
        // space allows, so narrow widths or large fonts drop labels instead of
        // overlapping them.
        legendEntries.filter { it.mandatory && it.offsetMinutes == 0 }.forEach { placeLegendEntry(it) }
        legendEntries.filter { it.mandatory && it.offsetMinutes != 0 }.forEach { placeLegendEntry(it) }
        legendEntries.filter { !it.mandatory }
            .sortedBy { abs(it.offsetMinutes) }
            .forEach { placeLegendEntry(it) }

        renderedLabels.forEach { (entry, left) ->
            Text(
                text = entry.text,
                color = if (entry.style == adhanLabelStyle) {
                    PrayerSilencePalette.GoldAccent
                } else {
                    PrayerSilencePalette.SecondaryText
                },
                style = entry.style,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.absoluteOffset {
                    IntOffset(
                        left.roundToInt(),
                        (labelsTopPx + (legendBaselinePx - entry.layout.firstBaseline)).roundToInt(),
                    )
                },
            )
        }

        HandleSemantics(
            x = visibleStartX,
            centerY = centerYPx,
            description = startDescription,
            stateDescription = startState,
            offsetMinutes = visible.startMinutes - prayerMinutes,
            scale = activeScale,
            earlierLabel = earlierLabel,
            laterLabel = laterLabel,
            enabled = canAdjust,
            onAdjust = { targetOffset -> nudgeTo(SilenceEndpoint.START, targetOffset) },
            onEarlier = { nudge(SilenceDragPart.Start, -1) },
            onLater = { nudge(SilenceDragPart.Start, 1) },
        )
        HandleSemantics(
            x = visibleEndX,
            centerY = centerYPx,
            description = endDescription,
            stateDescription = endState,
            offsetMinutes = visible.endMinutes - prayerMinutes,
            scale = activeScale,
            earlierLabel = earlierLabel,
            laterLabel = laterLabel,
            enabled = canAdjust,
            onAdjust = { targetOffset -> nudgeTo(SilenceEndpoint.END, targetOffset) },
            onEarlier = { nudge(SilenceDragPart.End, -1) },
            onLater = { nudge(SilenceDragPart.End, 1) },
        )
    }
}

private fun endpointOffset(window: PrayerTimelineWindow, part: SilenceDragPart, prayerMinutes: Int): Int =
    when (part) {
        SilenceDragPart.End -> window.endMinutes - prayerMinutes
        else -> window.startMinutes - prayerMinutes
    }

private fun silenceShiftedWindow(
    window: PrayerTimelineWindow,
    part: SilenceDragPart,
    deltaMinutes: Int,
    scale: PrayerTimelineScale,
    prayerMinutes: Int,
): PrayerTimelineWindow? {
    val startOffset = window.startMinutes - prayerMinutes
    val endOffset = window.endMinutes - prayerMinutes
    val minimumGap = 1
    return when (part) {
        SilenceDragPart.Start -> {
            if (endOffset - startOffset >= minimumGap) {
                val newOffset = (startOffset + deltaMinutes)
                    .coerceIn(scale.startOffsetMinutes, endOffset - minimumGap)
                if (newOffset == startOffset) null
                else PrayerTimelineWindow(prayerMinutes + newOffset, window.endMinutes)
            } else {
                if (deltaMinutes >= 0) null
                else PrayerTimelineWindow(
                    prayerMinutes + (startOffset + deltaMinutes).coerceAtLeast(scale.startOffsetMinutes),
                    window.endMinutes,
                )
            }
        }
        SilenceDragPart.End -> {
            if (endOffset - startOffset >= minimumGap) {
                val newOffset = (endOffset + deltaMinutes)
                    .coerceIn(startOffset + minimumGap, scale.endOffsetMinutes)
                if (newOffset == endOffset) null
                else PrayerTimelineWindow(window.startMinutes, prayerMinutes + newOffset)
            } else {
                if (deltaMinutes <= 0) null
                else PrayerTimelineWindow(
                    window.startMinutes,
                    prayerMinutes + (endOffset + deltaMinutes).coerceAtMost(scale.endOffsetMinutes),
                )
            }
        }
        SilenceDragPart.Window -> {
            val duration = endOffset - startOffset
            val maxStart = scale.endOffsetMinutes - duration
            if (maxStart < scale.startOffsetMinutes) null
            else {
                val newStart = (startOffset + deltaMinutes).coerceIn(scale.startOffsetMinutes, maxStart)
                if (newStart == startOffset) null
                else PrayerTimelineWindow(prayerMinutes + newStart, prayerMinutes + newStart + duration)
            }
        }
    }
}

@Composable
private fun HandleSemantics(
    x: Float,
    centerY: Float,
    description: String,
    stateDescription: String,
    offsetMinutes: Int,
    scale: PrayerTimelineScale,
    earlierLabel: String,
    laterLabel: String,
    enabled: Boolean,
    onAdjust: (Int) -> Boolean,
    onEarlier: () -> Boolean,
    onLater: () -> Boolean,
) {
    val density = LocalDensity.current
    val touchTargetPx = with(density) { PrayerSilenceDimens.SliderHandleTouchTarget.toPx() }
    Box(
        modifier = Modifier
            .absoluteOffset {
                IntOffset(
                    (x - touchTargetPx / 2f).roundToInt(),
                    (centerY - touchTargetPx / 2f).roundToInt(),
                )
            }
            .size(PrayerSilenceDimens.SliderHandleTouchTarget)
            .semantics {
                this.contentDescription = description
                this.stateDescription = stateDescription
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = (offsetMinutes - scale.startOffsetMinutes).toFloat(),
                    range = 0f..scale.spanMinutes.toFloat(),
                )
                if (enabled) {
                    setProgress { target ->
                        onAdjust(scale.startOffsetMinutes + target.roundToInt())
                    }
                    customActions = listOf(
                        CustomAccessibilityAction(earlierLabel) { onEarlier() },
                        CustomAccessibilityAction(laterLabel) { onLater() },
                    )
                } else {
                    disabled()
                }
            },
    )
}

private fun DrawScope.drawHandle(
    x: Float,
    centerY: Float,
    radiusPx: Float,
    active: Boolean,
    borderPx: Float,
) {
    if (active) {
        drawCircle(PrayerSilencePalette.Halo, radiusPx + 9.dp.toPx(), Offset(x, centerY))
    }
    drawCircle(PrayerSilencePalette.HandleFill, radiusPx, Offset(x, centerY))
    drawCircle(
        color = PrayerSilencePalette.InteractiveTeal,
        radius = radiusPx,
        center = Offset(x, centerY),
        style = Stroke(width = borderPx),
    )
    if (active) {
        drawCircle(PrayerSilencePalette.InteractiveTeal, radiusPx * 0.34f, Offset(x, centerY))
    }
}

private fun DrawScope.drawDiamond(center: Offset, radiusPx: Float, color: Color) {
    drawPath(
        Path().apply {
            moveTo(center.x, center.y - radiusPx)
            lineTo(center.x + radiusPx, center.y)
            lineTo(center.x, center.y + radiusPx)
            lineTo(center.x - radiusPx, center.y)
            close()
        },
        color,
    )
}

@Composable
private fun relationshipText(offsetMinutes: Int): String = when {
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

/**
 * Wait for horizontal slop without consuming down: the enclosing vertical scroll
 * keeps priority, and no value changes before drag intent is established.
 */
@Composable
private fun Modifier.silenceSliderDrag(
    enabled: Boolean,
    gestureKey: Any,
    choosePart: (Offset) -> SilenceDragPart?,
    onStart: (SilenceDragPart) -> Unit,
    onDelta: (Float, Offset) -> Unit,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
): Modifier {
    val currentChoosePart by rememberUpdatedState(choosePart)
    val currentStart by rememberUpdatedState(onStart)
    val currentDelta by rememberUpdatedState(onDelta)
    val currentFinish by rememberUpdatedState(onFinish)
    val currentCancel by rememberUpdatedState(onCancel)
    return pointerInput(enabled, gestureKey) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val part = currentChoosePart(down.position) ?: return@awaitEachGesture
            var overSlop = 0f
            val accepted = awaitHorizontalTouchSlopOrCancellation(down.id) { change, amount ->
                overSlop = amount
                change.consume()
            } ?: return@awaitEachGesture
            var completed = false
            try {
                currentStart(part)
                currentDelta(overSlop, accepted.position)
                val released = horizontalDrag(accepted.id) { change ->
                    currentDelta(change.positionChange().x, change.position)
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

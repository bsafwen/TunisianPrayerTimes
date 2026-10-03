package com.tunisianprayertimes.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.tunisianprayertimes.OffsetDirection
import com.tunisianprayertimes.PrayerWakeSubAlarm
import com.tunisianprayertimes.R
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private val TrackTopClearance = 10.dp
private val TrackAreaHeight = 36.dp
private val TrackThickness = 8.dp
private val HandleSize = 22.dp
private val HandleTouchTarget = 48.dp
private val AddColumnWidth = 48.dp
private val AddButtonSize = 30.dp

/** How far past the end of the track the handle must be pulled to count as pushing outward. */
private val EdgeOvershoot = 4.dp

/** A hold on the edge starts growing the timeline after this long, then once per tick. */
private const val EDGE_ARM_DELAY_MILLIS = 350L
private const val EDGE_TICK_MILLIS = 160L
private const val SPAN_ANIMATION_MILLIS = 180

private val TickStepCandidates = listOf(5, 15, 30, 60)
private const val MaxTickIntervals = 12

private data class ExtraAlarmDrag(
    val id: String,
    val direction: OffsetDirection,
    /** Offset when the gesture started; decides whether release commits. */
    val startMinutes: Int,
    val minutes: Int,
    /** Minutes shown on each side during this gesture; it only grows, through a hold on the edge. */
    val spanMinutes: Int,
    /** Where the finger sits relative to the handle's centre, so the handle doesn't jump to it. */
    val grabDeltaPx: Float,
    val pushingEdge: Boolean,
)

/**
 * Extra alarms as draggable handles on one timeline, with the main alarm as a gold bar in the
 * middle and an add button at each end. Like the prayer silence slider it is a physical
 * timeline: earlier on the right, later on the left, whatever the layout direction.
 *
 * A handle stays on its own side of the main alarm. Dragging moves it one minute at a time while
 * the timeline is zoomed in; holding it against the end grows the timeline, faster the longer the
 * hold lasts, and a zoomed-out timeline drags in coarser steps. Nothing is committed before the
 * finger lifts: [onPreview] follows the drag and [onCommit] gets the final offset.
 */
@Composable
internal fun WakeExtraAlarmSlider(
    subAlarms: List<PrayerWakeSubAlarm>,
    canAdd: Boolean,
    highlightedId: String?,
    timeTextFor: (signedOffsetMinutes: Int) -> String,
    onAdd: (OffsetDirection) -> Unit,
    onPreview: (PrayerWakeSubAlarm?) -> Unit,
    onCommit: (PrayerWakeSubAlarm) -> Unit,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hapticFeedback = LocalHapticFeedback.current
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    var drag by remember { mutableStateOf<ExtraAlarmDrag?>(null) }
    val currentSubAlarms by rememberUpdatedState(subAlarms)
    val currentOnPreview by rememberUpdatedState(onPreview)
    val currentOnCommit by rememberUpdatedState(onCommit)
    val currentOnOpen by rememberUpdatedState(onOpen)

    val fittedSpan = extraAlarmTimelineSpanMinutes(subAlarms)
    val liveSpan = drag?.spanMinutes ?: fittedSpan
    // Drawing follows the animated span; pointer positions map through the live one.
    val drawnSpan by animateFloatAsState(
        targetValue = liveSpan.toFloat(),
        animationSpec = tween(durationMillis = SPAN_ANIMATION_MILLIS),
        label = "wakeExtraAlarmSpan",
    )
    val shownAlarms = subAlarms.map { alarm ->
        drag?.takeIf { active -> active.id == alarm.id }
            ?.let { active -> alarm.copy(minutesOffset = active.minutes) }
            ?: alarm
    }

    DisposableEffect(Unit) {
        onDispose { if (drag != null) currentOnPreview(null) }
    }

    val scaleLabelStyle = TextStyle(
        fontSize = PrayerSilenceTypography.ScaleLabel,
        // Left-to-right so the signed value renders before the "د" unit: "+30 د".
        textDirection = TextDirection.Ltr,
    )
    val mainLabelStyle = TextStyle(
        fontSize = PrayerSilenceTypography.AdhanLabel,
        fontWeight = FontWeight.SemiBold,
    )
    val valueLabelStyle = TextStyle(
        fontSize = PrayerSilenceTypography.ValueLabel,
        fontWeight = FontWeight.SemiBold,
        textDirection = TextDirection.Rtl,
    )
    val beforeScaleText = stringResource(R.string.prayer_silence_scale_before, "-$liveSpan")
    val afterScaleText = stringResource(R.string.prayer_silence_scale_after, "+$liveSpan")
    val mainLabelText = stringResource(R.string.wake_editor_extra_main_label)
    val earlierLabel = stringResource(R.string.prayer_silence_adjust_earlier)
    val laterLabel = stringResource(R.string.prayer_silence_adjust_later)
    val editLabel = stringResource(R.string.wake_editor_extra_edit)

    // Physical timeline: the "after" button on the left, the "before" button on the right.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .testTag("wake_extra_alarm_slider"),
            verticalAlignment = Alignment.Top,
        ) {
            ExtraAlarmAddButton(
                description = stringResource(R.string.wake_editor_extra_add_after),
                caption = stringResource(R.string.wake_editor_subalarm_after),
                enabled = canAdd,
                onClick = { onAdd(OffsetDirection.AFTER) },
            )
            BoxWithConstraints(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.TopStart,
            ) {
                val widthPx = constraints.maxWidth.toFloat()
                val handleRadiusPx = with(density) { (HandleSize / 2).toPx() }
                val touchRadiusPx = with(density) { (HandleTouchTarget / 2).toPx() }
                val edgeInsetPx = handleRadiusPx + with(density) { 3.dp.toPx() }
                val halfTrackPx = ((widthPx - edgeInsetPx * 2f) / 2f).coerceAtLeast(1f)
                val centerXPx = widthPx / 2f
                val trackTopPx = with(density) { TrackTopClearance.toPx() }
                val trackAreaPx = with(density) { TrackAreaHeight.toPx() }
                val centerYPx = trackTopPx + trackAreaPx / 2f
                val legendTopPx = trackTopPx + trackAreaPx
                val overshootPx = with(density) { EdgeOvershoot.toPx() }

                fun xForOffset(signedOffsetMinutes: Int, spanMinutes: Float): Float =
                    centerXPx - (signedOffsetMinutes / spanMinutes).coerceIn(-1f, 1f) * halfTrackPx

                fun dragStepFor(spanMinutes: Int): Int =
                    extraAlarmDragStepMinutes(halfTrackPx / density.density / spanMinutes)

                fun hitHandle(position: Offset): PrayerWakeSubAlarm? {
                    if (abs(position.y - centerYPx) > touchRadiusPx) return null
                    return shownAlarms
                        .map { alarm ->
                            alarm to abs(position.x - xForOffset(alarm.signedOffsetMinutes, drawnSpan))
                        }
                        .filter { (_, distance) -> distance <= touchRadiusPx }
                        // Stacked handles: the highlighted one is drawn on top, so it wins the touch.
                        .minWithOrNull(
                            compareBy<Pair<PrayerWakeSubAlarm, Float>> { (_, distance) -> distance }
                                .thenBy { (alarm, _) -> if (alarm.id == highlightedId) 0 else 1 },
                        )
                        ?.first
                }

                fun startDrag(alarm: PrayerWakeSubAlarm, pointerX: Float, overSlopPx: Float) {
                    // Measured on the settled span, the one the drag maps through: grabbing a handle
                    // while the timeline is still zooming must not change its value.
                    val handleX = xForOffset(alarm.signedOffsetMinutes, fittedSpan.toFloat())
                    drag = ExtraAlarmDrag(
                        id = alarm.id,
                        direction = alarm.direction,
                        startMinutes = alarm.minutesOffset,
                        minutes = alarm.minutesOffset,
                        spanMinutes = fittedSpan,
                        // The handle starts moving from where it rests, by the distance past slop.
                        grabDeltaPx = pointerX - overSlopPx - handleX,
                        pushingEdge = false,
                    )
                    currentOnPreview(alarm)
                }

                fun moveDrag(pointerX: Float) {
                    val current = drag ?: return
                    val alarm = currentSubAlarms.firstOrNull { it.id == current.id } ?: return
                    val handleX = pointerX - current.grabDeltaPx
                    val distancePx = if (current.direction == OffsetDirection.BEFORE) {
                        handleX - centerXPx
                    } else {
                        centerXPx - handleX
                    }
                    val span = current.spanMinutes
                    val minutes = snapExtraAlarmOffset(
                        rawMinutes = distancePx / halfTrackPx * span,
                        stepMinutes = dragStepFor(span),
                        spanMinutes = span,
                    )
                    // Reaching the end is not enough: the handle has to be pulled past it.
                    val pushing = span < WAKE_EXTRA_ALARM_MAX_OFFSET_MINUTES &&
                        distancePx > halfTrackPx + overshootPx
                    if (minutes == current.minutes && pushing == current.pushingEdge) return
                    drag = current.copy(minutes = minutes, pushingEdge = pushing)
                    if (minutes != current.minutes) {
                        if (Math.floorDiv(minutes, 5) != Math.floorDiv(current.minutes, 5)) {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        currentOnPreview(alarm.copy(minutesOffset = minutes))
                    }
                }

                fun finishDrag(commit: Boolean) {
                    val completed = drag ?: return
                    drag = null
                    currentOnPreview(null)
                    if (commit && completed.minutes != completed.startMinutes) {
                        currentSubAlarms.firstOrNull { it.id == completed.id }
                            ?.let { alarm -> currentOnCommit(alarm.copy(minutesOffset = completed.minutes)) }
                    }
                }

                // Held against the end, the handle keeps going and takes the timeline with it. Any
                // move back inside, release or cancellation changes the key and stops the loop.
                val pushingId = drag?.takeIf { active -> active.pushingEdge }?.id
                LaunchedEffect(pushingId) {
                    if (pushingId == null) return@LaunchedEffect
                    delay(EDGE_ARM_DELAY_MILLIS)
                    var tick = 0
                    while (true) {
                        val current = drag?.takeIf { active -> active.id == pushingId && active.pushingEdge }
                            ?: break
                        val alarm = currentSubAlarms.firstOrNull { it.id == pushingId } ?: break
                        val next = nextExtraAlarmEdgeOffset(
                            minutes = current.minutes,
                            tick = tick,
                            minStepMinutes = dragStepFor(current.spanMinutes),
                        )
                        if (next == current.minutes) break
                        drag = current.copy(minutes = next, spanMinutes = maxOf(current.spanMinutes, next))
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        currentOnPreview(alarm.copy(minutesOffset = next))
                        tick++
                        delay(EDGE_TICK_MILLIS)
                    }
                }

                val currentHit by rememberUpdatedState<(Offset) -> PrayerWakeSubAlarm?> { position ->
                    hitHandle(position)
                }
                val dragModifier = Modifier.extraAlarmHandleDrag(
                    gestureKey = constraints.maxWidth,
                    chooseHandle = { position -> hitHandle(position) },
                    onStart = { alarm, pointer, overSlopPx -> startDrag(alarm, pointer.x, overSlopPx) },
                    onMove = { pointer -> moveDrag(pointer.x) },
                    onFinish = { finishDrag(commit = true) },
                    onCancel = { finishDrag(commit = false) },
                )
                // A tap on a handle opens that alarm's sheet (sound, side, delete).
                val tapModifier = Modifier.pointerInput(constraints.maxWidth) {
                    detectTapGestures { position ->
                        currentHit(position)?.let { alarm -> currentOnOpen(alarm.id) }
                    }
                }

                val tickStepMinutes = TickStepCandidates.firstOrNull { step ->
                    liveSpan * 2 / step <= MaxTickIntervals
                } ?: TickStepCandidates.last()
                val farthestBefore = shownAlarms
                    .filter { alarm -> alarm.direction == OffsetDirection.BEFORE }
                    .maxOfOrNull { alarm -> alarm.minutesOffset }
                val farthestAfter = shownAlarms
                    .filter { alarm -> alarm.direction == OffsetDirection.AFTER }
                    .maxOfOrNull { alarm -> alarm.minutesOffset }
                val draggedId = drag?.id

                Canvas(
                    modifier = Modifier
                        .matchParentSize()
                        .then(dragModifier)
                        .then(tapModifier),
                ) {
                    val trackThicknessPx = TrackThickness.toPx()
                    drawLine(
                        PrayerSilencePalette.InactiveTrack,
                        Offset(edgeInsetPx, centerYPx),
                        Offset(size.width - edgeInsetPx, centerYPx),
                        trackThicknessPx,
                        StrokeCap.Round,
                    )
                    // Filled from the main alarm out to the farthest extra alarm on each side.
                    listOfNotNull(farthestBefore?.let { -it }, farthestAfter).forEach { signedOffset ->
                        drawLine(
                            PrayerSilencePalette.InteractiveTeal,
                            Offset(centerXPx, centerYPx),
                            Offset(xForOffset(signedOffset, drawnSpan), centerYPx),
                            trackThicknessPx,
                            StrokeCap.Round,
                        )
                    }
                    var tickOffset = tickStepMinutes
                    while (tickOffset <= drawnSpan) {
                        listOf(-tickOffset to farthestBefore, tickOffset to farthestAfter)
                            .forEach { (signedOffset, farthest) ->
                                val tickX = xForOffset(signedOffset, drawnSpan)
                                drawLine(
                                    color = if (farthest != null && tickOffset <= farthest) {
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
                        tickOffset += tickStepMinutes
                    }
                    // The dragged or highlighted handle is drawn last so it stays on top of a stack.
                    shownAlarms
                        .sortedBy { alarm ->
                            when (alarm.id) {
                                draggedId -> 2
                                highlightedId -> 1
                                else -> 0
                            }
                        }
                        .forEach { alarm ->
                            drawAlarmHandle(
                                x = xForOffset(alarm.signedOffsetMinutes, drawnSpan),
                                centerY = centerYPx,
                                radiusPx = handleRadiusPx,
                                dragging = alarm.id == draggedId,
                                highlighted = alarm.id == highlightedId,
                            )
                        }
                    // Over the handles: on a zoomed-out timeline an alarm a few minutes away sits
                    // on the middle, and the main alarm has to stay visible through it.
                    drawMainAlarmBar(centerX = centerXPx, centerY = centerYPx)
                }

                val activeDrag = drag
                if (activeDrag != null) {
                    val signedOffset = if (activeDrag.direction == OffsetDirection.BEFORE) {
                        -activeDrag.minutes
                    } else {
                        activeDrag.minutes
                    }
                    val relation = pluralStringResource(
                        if (activeDrag.direction == OffsetDirection.BEFORE) {
                            R.plurals.wake_editor_extra_before_value
                        } else {
                            R.plurals.wake_editor_extra_after_value
                        },
                        activeDrag.minutes,
                        activeDrag.minutes,
                    )
                    // "--:--" when the main alarm's next ring can't be computed: say the offset only.
                    val timeText = timeTextFor(signedOffset).takeIf { text -> text.any(Char::isDigit) }
                    val fullText = if (timeText != null) {
                        stringResource(R.string.prayer_silence_value_label, "⁦$timeText⁩", relation)
                    } else {
                        relation
                    }
                    val paddingHorizontalPx = with(density) {
                        PrayerSilenceDimens.SliderTooltipPaddingHorizontal.toPx()
                    }
                    val paddingVerticalPx = with(density) {
                        PrayerSilenceDimens.SliderTooltipPaddingVertical.toPx()
                    }
                    // The bubble may spread over the add buttons' columns, not beyond them.
                    val sideRoomPx = with(density) { AddColumnWidth.toPx() }
                    val maxTextWidthPx = (widthPx + sideRoomPx * 2f - paddingHorizontalPx * 2f).coerceAtLeast(1f)
                    val fullLayout = textMeasurer.measure(
                        text = fullText,
                        style = valueLabelStyle,
                        maxLines = 1,
                        constraints = Constraints(maxWidth = maxTextWidthPx.toInt().coerceAtLeast(1)),
                    )
                    val shortText = timeText?.let { text -> "⁦$text⁩" }
                    val chosenText = if (fullLayout.hasVisualOverflow && shortText != null) shortText else fullText
                    val chosenLayout = if (chosenText == fullText) {
                        fullLayout
                    } else {
                        textMeasurer.measure(chosenText, valueLabelStyle, maxLines = 1)
                    }
                    val bubbleWidthPx = chosenLayout.size.width + paddingHorizontalPx * 2f
                    val bubbleHeightPx = chosenLayout.size.height + paddingVerticalPx * 2f
                    val handleX = xForOffset(signedOffset, drawnSpan)
                    val bubbleLeft = (handleX - bubbleWidthPx / 2f).coerceIn(
                        -sideRoomPx,
                        (widthPx + sideRoomPx - bubbleWidthPx).coerceAtLeast(-sideRoomPx),
                    )
                    // Anchored by its bottom edge just above the main alarm's marker.
                    val bubbleTop = -bubbleHeightPx
                    Box(
                        modifier = Modifier
                            .absoluteOffset { IntOffset(bubbleLeft.roundToInt(), bubbleTop.roundToInt()) }
                            .clip(RoundedCornerShape(9.dp))
                            .background(PrayerSilencePalette.PrimaryText)
                            .padding(
                                horizontal = PrayerSilenceDimens.SliderTooltipPaddingHorizontal,
                                vertical = PrayerSilenceDimens.SliderTooltipPaddingVertical,
                            ),
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

                val beforeScaleLayout = textMeasurer.measure(beforeScaleText, scaleLabelStyle, maxLines = 1)
                val afterScaleLayout = textMeasurer.measure(afterScaleText, scaleLabelStyle, maxLines = 1)
                val mainLabelLayout = textMeasurer.measure(mainLabelText, mainLabelStyle, maxLines = 1)
                val legendHeightPx = maxOf(
                    beforeScaleLayout.size.height,
                    afterScaleLayout.size.height,
                    mainLabelLayout.size.height,
                )
                val legendBaselinePx = maxOf(
                    beforeScaleLayout.firstBaseline,
                    afterScaleLayout.firstBaseline,
                    mainLabelLayout.firstBaseline,
                )
                val mainLabelLeft = centerXPx - mainLabelLayout.size.width / 2f
                val legendGapPx = with(density) { 6.dp.toPx() }
                // The span labels give way when a large font leaves no room beside the main label.
                val scaleLabelsFit = afterScaleLayout.size.width + legendGapPx <= mainLabelLeft &&
                    mainLabelLeft + mainLabelLayout.size.width + legendGapPx <=
                    widthPx - beforeScaleLayout.size.width

                // Content-driven height: the box wraps this spacer, the canvas matches it.
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(with(density) { (legendTopPx + legendHeightPx).toDp() }),
                )

                Text(
                    text = mainLabelText,
                    color = PrayerSilencePalette.GoldAccent,
                    style = mainLabelStyle,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .absoluteOffset {
                            IntOffset(
                                mainLabelLeft.roundToInt(),
                                (legendTopPx + legendBaselinePx - mainLabelLayout.firstBaseline).roundToInt(),
                            )
                        }
                        .clearAndSetSemantics {},
                )
                if (scaleLabelsFit) {
                    Text(
                        text = afterScaleText,
                        color = PrayerSilencePalette.SecondaryText,
                        style = scaleLabelStyle,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .absoluteOffset {
                                IntOffset(
                                    0,
                                    (legendTopPx + legendBaselinePx - afterScaleLayout.firstBaseline).roundToInt(),
                                )
                            }
                            .clearAndSetSemantics {},
                    )
                    Text(
                        text = beforeScaleText,
                        color = PrayerSilencePalette.SecondaryText,
                        style = scaleLabelStyle,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .absoluteOffset {
                                IntOffset(
                                    (widthPx - beforeScaleLayout.size.width).roundToInt(),
                                    (legendTopPx + legendBaselinePx - beforeScaleLayout.firstBaseline).roundToInt(),
                                )
                            }
                            .clearAndSetSemantics {},
                    )
                }

                shownAlarms.forEach { alarm ->
                    val before = alarm.direction == OffsetDirection.BEFORE
                    ExtraAlarmHandleSemantics(
                        x = xForOffset(alarm.signedOffsetMinutes, drawnSpan),
                        centerY = centerYPx,
                        description = pluralStringResource(
                            if (before) {
                                R.plurals.wake_editor_extra_before_value
                            } else {
                                R.plurals.wake_editor_extra_after_value
                            },
                            alarm.minutesOffset,
                            alarm.minutesOffset,
                        ),
                        stateDescription = timeTextFor(alarm.signedOffsetMinutes),
                        minutes = alarm.minutesOffset,
                        editLabel = editLabel,
                        earlierLabel = earlierLabel,
                        laterLabel = laterLabel,
                        onOpen = { currentOnOpen(alarm.id) },
                        onSetMinutes = { target ->
                            val next = target.coerceIn(1, WAKE_EXTRA_ALARM_MAX_OFFSET_MINUTES)
                            if (next == alarm.minutesOffset) {
                                false
                            } else {
                                currentOnCommit(alarm.copy(minutesOffset = next))
                                true
                            }
                        },
                        // Earlier means farther from the main alarm before it, nearer after it.
                        earlierDeltaMinutes = if (before) 1 else -1,
                    )
                }
            }
            ExtraAlarmAddButton(
                description = stringResource(R.string.wake_editor_extra_add_before),
                caption = stringResource(R.string.wake_editor_subalarm_before),
                enabled = canAdd,
                onClick = { onAdd(OffsetDirection.BEFORE) },
            )
        }
    }
}

/** A small round "+" at one end of the timeline, captioned with the side it adds to. */
@Composable
private fun ExtraAlarmAddButton(
    description: String,
    caption: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(AddColumnWidth)
            .padding(top = 5.dp)
            .alpha(if (enabled) 1f else 0.38f)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(top = 8.dp, bottom = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Centred on the track: 5 + 8 + half of the 30dp circle = the track's centre line.
        Box(
            modifier = Modifier
                .size(AddButtonSize)
                .border(
                    BorderStroke(1.5.dp, PrayerSilencePalette.InteractiveTeal.copy(alpha = 0.55f)),
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_add),
                contentDescription = description,
                tint = PrayerSilencePalette.InteractiveTeal,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(modifier = Modifier.height(1.dp))
        Text(
            text = caption,
            color = PrayerSilencePalette.SecondaryText,
            fontSize = PrayerSilenceTypography.ScaleLabel,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/** Invisible 48dp node over a handle: what TalkBack focuses, adjusts and activates. */
@Composable
private fun ExtraAlarmHandleSemantics(
    x: Float,
    centerY: Float,
    description: String,
    stateDescription: String,
    minutes: Int,
    editLabel: String,
    earlierLabel: String,
    laterLabel: String,
    onOpen: () -> Unit,
    onSetMinutes: (Int) -> Boolean,
    earlierDeltaMinutes: Int,
) {
    val density = LocalDensity.current
    val touchTargetPx = with(density) { HandleTouchTarget.toPx() }
    Box(
        modifier = Modifier
            .absoluteOffset {
                IntOffset(
                    (x - touchTargetPx / 2f).roundToInt(),
                    (centerY - touchTargetPx / 2f).roundToInt(),
                )
            }
            .size(HandleTouchTarget)
            .semantics {
                this.contentDescription = description
                this.stateDescription = stateDescription
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = minutes.toFloat(),
                    range = 1f..WAKE_EXTRA_ALARM_MAX_OFFSET_MINUTES.toFloat(),
                )
                setProgress { target -> onSetMinutes(target.roundToInt()) }
                onClick(label = editLabel) {
                    onOpen()
                    true
                }
                customActions = listOf(
                    CustomAccessibilityAction(earlierLabel) { onSetMinutes(minutes + earlierDeltaMinutes) },
                    CustomAccessibilityAction(laterLabel) { onSetMinutes(minutes - earlierDeltaMinutes) },
                )
            },
    )
}

/** The main alarm: a gold bar through the middle of the track with the prayer slider's diamond. */
private fun DrawScope.drawMainAlarmBar(centerX: Float, centerY: Float) {
    val barTop = centerY - 17.dp.toPx()
    drawLine(
        PrayerSilencePalette.GoldAccent,
        Offset(centerX, barTop),
        Offset(centerX, centerY + 9.dp.toPx()),
        3.dp.toPx(),
        StrokeCap.Round,
    )
    val diamondCenterY = barTop - 4.dp.toPx()
    val diamondRadius = 4.5.dp.toPx()
    drawPath(
        Path().apply {
            moveTo(centerX, diamondCenterY - diamondRadius)
            lineTo(centerX + diamondRadius, diamondCenterY)
            lineTo(centerX, diamondCenterY + diamondRadius)
            lineTo(centerX - diamondRadius, diamondCenterY)
            close()
        },
        PrayerSilencePalette.GoldAccent,
    )
}

private fun DrawScope.drawAlarmHandle(
    x: Float,
    centerY: Float,
    radiusPx: Float,
    dragging: Boolean,
    highlighted: Boolean,
) {
    if (dragging) {
        drawCircle(PrayerSilencePalette.Halo, radiusPx + 9.dp.toPx(), Offset(x, centerY))
    }
    drawCircle(PrayerSilencePalette.HandleFill, radiusPx, Offset(x, centerY))
    drawCircle(
        color = PrayerSilencePalette.InteractiveTeal,
        radius = radiusPx,
        center = Offset(x, centerY),
        style = Stroke(width = 2.dp.toPx()),
    )
    if (dragging || highlighted) {
        drawCircle(PrayerSilencePalette.InteractiveTeal, radiusPx * 0.34f, Offset(x, centerY))
    }
}

/**
 * Waits for horizontal slop without consuming the down, so the editor's vertical scroll keeps
 * priority and nothing moves before the drag is meant. Positions are reported as-is: the slider
 * maps them to offsets itself, which keeps a handle under the finger while the timeline grows.
 */
@Composable
private fun Modifier.extraAlarmHandleDrag(
    gestureKey: Any,
    chooseHandle: (Offset) -> PrayerWakeSubAlarm?,
    onStart: (alarm: PrayerWakeSubAlarm, pointer: Offset, overSlopPx: Float) -> Unit,
    onMove: (Offset) -> Unit,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
): Modifier {
    val currentChooseHandle by rememberUpdatedState(chooseHandle)
    val currentStart by rememberUpdatedState(onStart)
    val currentMove by rememberUpdatedState(onMove)
    val currentFinish by rememberUpdatedState(onFinish)
    val currentCancel by rememberUpdatedState(onCancel)
    return pointerInput(gestureKey) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val alarm = currentChooseHandle(down.position) ?: return@awaitEachGesture
            var overSlop = 0f
            val accepted = awaitHorizontalTouchSlopOrCancellation(down.id) { change, amount ->
                overSlop = amount
                change.consume()
            } ?: return@awaitEachGesture
            var completed = false
            try {
                currentStart(alarm, accepted.position, overSlop)
                currentMove(accepted.position)
                val released = horizontalDrag(accepted.id) { change ->
                    currentMove(change.position)
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

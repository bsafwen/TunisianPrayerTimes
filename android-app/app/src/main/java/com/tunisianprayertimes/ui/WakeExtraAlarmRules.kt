package com.tunisianprayertimes.ui

import com.tunisianprayertimes.OffsetDirection
import com.tunisianprayertimes.PrayerWakeSubAlarm
import com.tunisianprayertimes.WakePlaybackOptions
import kotlin.math.roundToInt

/** Enough for a pre-alarm and a couple of re-rings; more just adds noise to the morning. */
internal const val WAKE_MAX_EXTRA_ALARMS = 5
internal const val WAKE_EXTRA_ALARM_MAX_OFFSET_MINUTES = 180
internal const val WAKE_EXTRA_ALARM_DEFAULT_OFFSET_MINUTES = 5
private const val WAKE_EXTRA_ALARM_FINE_STEP_LIMIT = 5

/** Extra alarms in the order they ring around the main alarm; ties keep their list order. */
internal fun List<PrayerWakeSubAlarm>.inRingOrder(): List<PrayerWakeSubAlarm> =
    sortedBy { subAlarm -> subAlarm.signedOffsetMinutes }

/**
 * Offset for a new extra alarm on [direction]: 5 minutes from the main alarm, or the nearest free
 * 5-minute slot on that side, so adding twice never stacks two alarms on the same minute.
 */
internal fun nextExtraAlarmOffsetMinutes(
    existing: List<PrayerWakeSubAlarm>,
    direction: OffsetDirection,
): Int {
    val taken = existing
        .filter { subAlarm -> subAlarm.direction == direction }
        .map { subAlarm -> subAlarm.minutesOffset }
        .toSet()
    val slots = WAKE_EXTRA_ALARM_DEFAULT_OFFSET_MINUTES..WAKE_EXTRA_ALARM_MAX_OFFSET_MINUTES step
        WAKE_EXTRA_ALARM_DEFAULT_OFFSET_MINUTES
    return slots.firstOrNull { slot -> slot !in taken } ?: WAKE_EXTRA_ALARM_MAX_OFFSET_MINUTES
}

/** New extra alarms sound like the main alarm; they don't inherit its stop challenge. */
internal fun newExtraAlarm(
    id: String,
    existing: List<PrayerWakeSubAlarm>,
    direction: OffsetDirection,
    mainPlayback: WakePlaybackOptions,
): PrayerWakeSubAlarm {
    val subAlarm = PrayerWakeSubAlarm(
        id = id,
        minutesOffset = nextExtraAlarmOffsetMinutes(existing, direction),
        direction = direction,
    )
    return subAlarm.copy(playback = subAlarm.playback.withSoundOf(mainPlayback))
}

/** One minute at a time up to 5, then 5-minute steps that snap odd values back onto the grid. */
internal fun stepExtraAlarmOffset(minutes: Int, increase: Boolean): Int {
    val next = if (increase) {
        if (minutes < WAKE_EXTRA_ALARM_FINE_STEP_LIMIT) {
            minutes + 1
        } else {
            (minutes / WAKE_EXTRA_ALARM_FINE_STEP_LIMIT + 1) * WAKE_EXTRA_ALARM_FINE_STEP_LIMIT
        }
    } else {
        if (minutes <= WAKE_EXTRA_ALARM_FINE_STEP_LIMIT) {
            minutes - 1
        } else {
            ((minutes - 1) / WAKE_EXTRA_ALARM_FINE_STEP_LIMIT) * WAKE_EXTRA_ALARM_FINE_STEP_LIMIT
        }
    }
    return next.coerceIn(1, WAKE_EXTRA_ALARM_MAX_OFFSET_MINUTES)
}

// --- Timeline slider -------------------------------------------------------

/** Minutes shown on each side of the main alarm; the timeline settles on the first that fits. */
private val WAKE_EXTRA_TIMELINE_SPANS = listOf(20, 30, 45, 60, 90, 120, 180)

/**
 * Minutes the timeline shows on each side of the main alarm, which stays in the middle: the
 * smallest standard span that still shows every extra alarm.
 */
internal fun extraAlarmTimelineSpanMinutes(existing: List<PrayerWakeSubAlarm>): Int {
    val farthest = existing.maxOfOrNull { subAlarm -> subAlarm.minutesOffset } ?: 0
    return WAKE_EXTRA_TIMELINE_SPANS.firstOrNull { span -> span >= farthest }
        ?: WAKE_EXTRA_ALARM_MAX_OFFSET_MINUTES
}

private val WAKE_EXTRA_DRAG_STEPS = listOf(1, 5, 10, 15)
private const val WAKE_EXTRA_DRAG_MIN_STEP_DP = 2f

/**
 * Minutes one drag step covers. A single minute while the timeline is zoomed in; once it has
 * zoomed out, the first coarser step that is still wide enough for a finger to aim at.
 */
internal fun extraAlarmDragStepMinutes(dpPerMinute: Float): Int =
    WAKE_EXTRA_DRAG_STEPS.firstOrNull { step -> step * dpPerMinute >= WAKE_EXTRA_DRAG_MIN_STEP_DP }
        ?: WAKE_EXTRA_DRAG_STEPS.last()

/**
 * A dragged position snapped to [stepMinutes]. An extra alarm stays on its own side of the main
 * alarm and inside the visible span, so the result is never 0 and never beyond [spanMinutes].
 */
internal fun snapExtraAlarmOffset(rawMinutes: Float, stepMinutes: Int, spanMinutes: Int): Int {
    val span = spanMinutes.coerceIn(1, WAKE_EXTRA_ALARM_MAX_OFFSET_MINUTES)
    val snapped = (rawMinutes / stepMinutes).roundToInt() * stepMinutes
    return snapped.coerceIn(minOf(stepMinutes, span), span)
}

private const val WAKE_EXTRA_EDGE_FINE_TICKS = 5
private const val WAKE_EXTRA_EDGE_MEDIUM_TICKS = 13

/**
 * Minutes each tick adds while a handle is held against the end of the timeline. The longer the
 * hold, the bigger the step, so a far offset doesn't cost one long press per minute.
 */
internal fun extraAlarmEdgeStepMinutes(tick: Int): Int = when {
    tick < WAKE_EXTRA_EDGE_FINE_TICKS -> 1
    tick < WAKE_EXTRA_EDGE_MEDIUM_TICKS -> 5
    else -> 15
}

/**
 * Offset after edge tick number [tick] (from 0). Coarse steps land on their own grid, and a tick
 * never moves less than [minStepMinutes], the drag step of the span being pushed.
 */
internal fun nextExtraAlarmEdgeOffset(minutes: Int, tick: Int, minStepMinutes: Int = 1): Int {
    val step = maxOf(extraAlarmEdgeStepMinutes(tick), minStepMinutes)
    return ((minutes / step + 1) * step).coerceAtMost(WAKE_EXTRA_ALARM_MAX_OFFSET_MINUTES)
}

internal fun WakePlaybackOptions.withSoundOf(source: WakePlaybackOptions): WakePlaybackOptions = copy(
    ringtone = source.ringtone,
    customRingtoneUri = source.customRingtoneUri,
    vibrationOnly = source.vibrationOnly,
    progressiveVolume = source.progressiveVolume,
)

internal fun PrayerWakeSubAlarm.soundMatches(mainPlayback: WakePlaybackOptions): Boolean =
    playback.vibrationOnly == mainPlayback.vibrationOnly &&
        (
            mainPlayback.vibrationOnly ||
                (playback.ringtone == mainPlayback.ringtone && playback.customRingtoneUri == mainPlayback.customRingtoneUri)
            )

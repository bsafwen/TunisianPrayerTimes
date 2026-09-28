package com.tunisianprayertimes.ui

import com.tunisianprayertimes.OffsetDirection
import com.tunisianprayertimes.PrayerWakeSubAlarm
import com.tunisianprayertimes.WakePlaybackOptions

/** Enough for a pre-alarm and a couple of re-rings; more just adds noise to the morning. */
internal const val WAKE_MAX_EXTRA_ALARMS = 5
internal const val WAKE_EXTRA_ALARM_MAX_OFFSET_MINUTES = 180
private const val WAKE_EXTRA_ALARM_GAP_MINUTES = 10
private const val WAKE_EXTRA_ALARM_FINE_STEP_LIMIT = 5

/** Extra alarms in the order they ring around the main alarm; ties keep their list order. */
internal fun List<PrayerWakeSubAlarm>.inRingOrder(): List<PrayerWakeSubAlarm> =
    sortedBy { subAlarm -> subAlarm.signedOffsetMinutes }

/**
 * Offset for a new extra alarm on [direction]: 10 minutes beyond the farthest one already on that
 * side, so adding twice never stacks two alarms on the same minute.
 */
internal fun nextExtraAlarmOffsetMinutes(
    existing: List<PrayerWakeSubAlarm>,
    direction: OffsetDirection,
): Int {
    val farthest = existing
        .filter { subAlarm -> subAlarm.direction == direction }
        .maxOfOrNull { subAlarm -> subAlarm.minutesOffset }
        ?: 0
    return (farthest + WAKE_EXTRA_ALARM_GAP_MINUTES).coerceAtMost(WAKE_EXTRA_ALARM_MAX_OFFSET_MINUTES)
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

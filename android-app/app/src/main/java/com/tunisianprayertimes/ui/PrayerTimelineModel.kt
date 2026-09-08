package com.tunisianprayertimes.ui

import com.tunisianprayertimes.DelayMode
import com.tunisianprayertimes.PrayerSilenceConfig
import com.tunisianprayertimes.PrayerTime
import com.tunisianprayertimes.SilenceMode

/** Minutes from midnight on the prayer's date, including previous/next-day endpoints. */
internal data class PrayerTimelineWindow(
    val startMinutes: Int,
    val endMinutes: Int,
) {
    val durationMinutes: Int get() = endMinutes - startMinutes
}

internal data class PrayerTimelineRange(
    val startMinutes: Int,
    val endMinutes: Int,
)

/**
 * Uses the same independent start/end rules as SilenceAlarmComputer. In particular,
 * a fixed end rolls forward once only when it is before the start; equality is a
 * zero-length window. Unset fixed fields retain their offset/duration fallback.
 */
internal fun resolvePrayerTimelineWindow(
    prayerTime: PrayerTime,
    config: PrayerSilenceConfig,
): PrayerTimelineWindow {
    val prayerMinutes = prayerTime.hour * 60 + prayerTime.minute
    val start = if (
        config.delayMode == DelayMode.FIXED_TIME &&
        config.delayFixedHour >= 0 && config.delayFixedMinute >= 0
    ) {
        config.delayFixedHour * 60 + config.delayFixedMinute
    } else {
        prayerMinutes + config.delayMinutes
    }
    val end = if (
        config.mode == SilenceMode.FIXED_TIME &&
        config.fixedHour >= 0 && config.fixedMinute >= 0
    ) {
        val fixedEnd = config.fixedHour * 60 + config.fixedMinute
        if (fixedEnd < start) fixedEnd + MINUTES_PER_DAY else fixedEnd
    } else {
        start + config.afterMinutes
    }
    return PrayerTimelineWindow(start, end)
}

/**
 * Maps a displayed window back to the existing settings without changing modes
 * or materializing unset clocks that currently fall back to an offset/duration.
 * Fixed-clock settings cannot encode arbitrary dates, so the scheduler's resolved
 * result must match both requested endpoints before a candidate can be saved.
 */
internal fun prayerTimelineConfigForWindow(
    prayerTime: PrayerTime,
    config: PrayerSilenceConfig,
    window: PrayerTimelineWindow,
): PrayerSilenceConfig? {
    if (window.endMinutes < window.startMinutes) return null
    var candidate = config
    val fixedStart = config.delayMode == DelayMode.FIXED_TIME &&
        config.delayFixedHour >= 0 && config.delayFixedMinute >= 0
    if (fixedStart) {
        if (window.startMinutes !in 0 until MINUTES_PER_DAY) return null
        candidate = candidate.copy(
            delayFixedHour = window.startMinutes / 60,
            delayFixedMinute = window.startMinutes % 60,
        )
    } else {
        val offset = window.startMinutes.toLong() - (prayerTime.hour * 60L + prayerTime.minute)
        if (offset !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) return null
        candidate = candidate.copy(delayMinutes = offset.toInt())
    }
    val fixedEnd = config.mode == SilenceMode.FIXED_TIME &&
        config.fixedHour >= 0 && config.fixedMinute >= 0
    if (fixedEnd) {
        if (window.endMinutes < 0) return null
        val endClock = window.endMinutes % MINUTES_PER_DAY
        candidate = candidate.copy(
            fixedHour = endClock / 60,
            fixedMinute = endClock % 60,
        )
    } else {
        val duration = window.endMinutes.toLong() - window.startMinutes
        if (duration > Int.MAX_VALUE) return null
        candidate = candidate.copy(afterMinutes = duration.toInt())
    }
    return candidate.takeIf { resolvePrayerTimelineWindow(prayerTime, it) == window }
}

/**
 * Fits the marker and the complete saved window, even when it crosses midnight or
 * is longer than the usual prayer interval. The UI freezes this range during a
 * gesture so dragging an endpoint does not move the scale beneath the finger.
 */
internal fun prayerTimelineRange(
    prayerTime: PrayerTime,
    window: PrayerTimelineWindow,
): PrayerTimelineRange {
    val prayerMinutes = prayerTime.hour * 60L + prayerTime.minute
    val earliest = minOf(prayerMinutes, window.startMinutes.toLong(), window.endMinutes.toLong())
    val latest = maxOf(prayerMinutes, window.startMinutes.toLong(), window.endMinutes.toLong())
    val padding = ((latest - earliest) / 10L).coerceIn(15L, 60L)
    var start = earliest - padding
    var end = latest + padding
    val missingSpan = (120L - (end - start)).coerceAtLeast(0L)
    start -= missingSpan / 2L
    end += missingSpan - missingSpan / 2L
    return PrayerTimelineRange(
        startMinutes = start.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt(),
        endMinutes = end.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt(),
    )
}

private const val MINUTES_PER_DAY = 24 * 60

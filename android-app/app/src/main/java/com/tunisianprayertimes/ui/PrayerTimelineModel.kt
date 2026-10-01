package com.tunisianprayertimes.ui

import com.tunisianprayertimes.DelayMode
import com.tunisianprayertimes.PrayerSilenceConfig
import com.tunisianprayertimes.PrayerTime
import com.tunisianprayertimes.SilenceMode
import java.util.Locale

/** The two editable endpoints of a prayer's silence window. */
internal enum class SilenceEndpoint { START, END }

/** How one endpoint is scheduled. */
internal enum class EndpointRuleMode { ADHAN, FIXED_TIME }

/** Minutes from midnight on the prayer's date, including previous/next-day endpoints. */
internal data class PrayerTimelineWindow(
    val startMinutes: Int,
    val endMinutes: Int,
) {
    val durationMinutes: Int get() = endMinutes - startMinutes
}

/**
 * One offset range shared by every prayer so the sliders are visually comparable.
 * Offsets are minutes relative to adhan (0 = adhan). The default window always
 * covers [-15, +60]; any saved window outside it extends the range instead of
 * being clamped.
 */
internal data class PrayerTimelineScale(
    val startOffsetMinutes: Int,
    val endOffsetMinutes: Int,
) {
    val spanMinutes: Int get() = (endOffsetMinutes - startOffsetMinutes).coerceAtLeast(1)

    fun offsetForFraction(fraction: Float): Int =
        startOffsetMinutes + (fraction * spanMinutes).toInt()

    fun fractionForOffset(offset: Int): Float =
        (offset - startOffsetMinutes).toFloat() / spanMinutes
}

internal const val DEFAULT_TIMELINE_START_OFFSET_MINUTES = -15
internal const val DEFAULT_TIMELINE_END_OFFSET_MINUTES = 60

/** Deliberate-edge extension policy: how long outward intent must hold before the domain grows. */
internal const val TIMELINE_EDGE_ARM_DELAY_MS = 400L

/** Deliberate-edge extension policy: interval between one-minute viewing-domain steps. */
internal const val TIMELINE_EDGE_STEP_INTERVAL_MS = 200L

/** Deliberate-edge extension policy: minutes added to the endpoint and viewing domain per step. */
internal const val TIMELINE_EDGE_STEP_MINUTES = 1

private const val TIMELINE_SETTLE_STEP_MINUTES = 15

/**
 * Viewing boundaries settle outward to whole quarter-hours after an extended drag
 * ([floorToTimelineBoundary]/[ceilToTimelineBoundary]) while the selected values are
 * never rounded, so an extended timeline stays extended instead of collapsing onto
 * the dragged endpoint.
 */
internal fun sharedPrayerTimelineScale(
    windowsByPrayer: List<Pair<PrayerTime, PrayerTimelineWindow>>,
): PrayerTimelineScale {
    var start = DEFAULT_TIMELINE_START_OFFSET_MINUTES
    var end = DEFAULT_TIMELINE_END_OFFSET_MINUTES
    windowsByPrayer.forEach { (prayerTime, window) ->
        val prayerMinutes = prayerTime.hour * 60 + prayerTime.minute
        val startOffset = (window.startMinutes - prayerMinutes).coerceAtLeast(Int.MIN_VALUE + 1)
        val endOffset = (window.endMinutes - prayerMinutes).coerceAtMost(Int.MAX_VALUE - 1)
        if (startOffset < start) start = startOffset
        if (endOffset > end) end = endOffset
    }
    val settledStart = minOf(start, floorToTimelineBoundary(start))
    val settledEnd = maxOf(end, ceilToTimelineBoundary(end))
    return PrayerTimelineScale(settledStart, settledEnd.coerceAtLeast(settledStart + 1))
}

/** Rounds an earlier viewing boundary outward to the previous quarter-hour. */
internal fun floorToTimelineBoundary(offsetMinutes: Int): Int =
    Math.floorDiv(offsetMinutes, TIMELINE_SETTLE_STEP_MINUTES) * TIMELINE_SETTLE_STEP_MINUTES

/** Rounds a later viewing boundary outward to the next quarter-hour. */
internal fun ceilToTimelineBoundary(offsetMinutes: Int): Int =
    -Math.floorDiv(-offsetMinutes, TIMELINE_SETTLE_STEP_MINUTES) * TIMELINE_SETTLE_STEP_MINUTES

internal fun prayerMinutesOfDay(prayerTime: PrayerTime): Int = prayerTime.hour * 60 + prayerTime.minute

/** Clock value of an absolute minute-of-day value (includes previous/next-day rows). */
internal fun prayerClockText(absoluteMinutes: Int): String {
    val clock = Math.floorMod(absoluteMinutes, MINUTES_PER_DAY)
    return "\u2066${String.format(Locale.US, "%02d:%02d", clock / 60, clock % 60)}\u2069"
}

internal fun prayerDayOffset(absoluteMinutes: Int): Int = Math.floorDiv(absoluteMinutes, MINUTES_PER_DAY)

// --- Endpoint rule helpers -------------------------------------------------

internal fun PrayerSilenceConfig.startRuleMode(): EndpointRuleMode =
    if (delayMode == DelayMode.FIXED_TIME && delayFixedHour >= 0 && delayFixedMinute >= 0) {
        EndpointRuleMode.FIXED_TIME
    } else {
        EndpointRuleMode.ADHAN
    }

/** A legacy duration end reads as an adhan-relative rule while its start is unchanged. */
internal fun PrayerSilenceConfig.endRuleMode(): EndpointRuleMode =
    if (mode == SilenceMode.FIXED_TIME && fixedHour >= 0 && fixedMinute >= 0) {
        EndpointRuleMode.FIXED_TIME
    } else {
        EndpointRuleMode.ADHAN
    }

/** Replaces one endpoint's rule without touching the other endpoint. */
internal fun PrayerSilenceConfig.withStartRule(
    mode: EndpointRuleMode,
    offsetMinutes: Int,
    clockMinutes: Int,
): PrayerSilenceConfig = if (mode == EndpointRuleMode.FIXED_TIME) {
    val clock = Math.floorMod(clockMinutes, MINUTES_PER_DAY)
    copy(
        delayMode = DelayMode.FIXED_TIME,
        delayFixedHour = clock / 60,
        delayFixedMinute = clock % 60,
    )
} else {
    copy(delayMode = DelayMode.MINUTES, delayMinutes = offsetMinutes)
}

/** Replaces one endpoint's rule without touching the other endpoint. */
internal fun PrayerSilenceConfig.withEndRule(
    mode: EndpointRuleMode,
    offsetMinutes: Int,
    clockMinutes: Int,
): PrayerSilenceConfig = if (mode == EndpointRuleMode.FIXED_TIME) {
    val clock = Math.floorMod(clockMinutes, MINUTES_PER_DAY)
    copy(
        mode = SilenceMode.FIXED_TIME,
        fixedHour = clock / 60,
        fixedMinute = clock % 60,
        endOffsetMinutes = null,
    )
} else {
    copy(mode = SilenceMode.DURATION, endOffsetMinutes = offsetMinutes)
}

/**
 * Uses the same independent start/end rules as SilenceAlarmComputer. In particular,
 * a fixed end rolls forward once only when it is before the start; an adhan-relative
 * end stays on the prayer's date and is independent of the start; the legacy
 * duration end remains start + afterMinutes.
 */
internal fun resolvePrayerTimelineWindow(
    prayerTime: PrayerTime,
    config: PrayerSilenceConfig,
): PrayerTimelineWindow {
    val prayerMinutes = prayerTime.hour * 60 + prayerTime.minute
    val start = if (config.startRuleMode() == EndpointRuleMode.FIXED_TIME) {
        config.delayFixedHour * 60 + config.delayFixedMinute
    } else {
        prayerMinutes + config.delayMinutes
    }
    val configEndOffset = config.endOffsetMinutes
    val end = when {
        config.endRuleMode() == EndpointRuleMode.FIXED_TIME -> {
            val fixedEnd = config.fixedHour * 60 + config.fixedMinute
            if (fixedEnd < start) fixedEnd + MINUTES_PER_DAY else fixedEnd
        }
        configEndOffset != null -> prayerMinutes + configEndOffset
        else -> start + config.afterMinutes
    }
    return PrayerTimelineWindow(start, end)
}

/**
 * Keeps [PrayerSilenceConfig.afterMinutes] usable for legacy readers whenever an
 * adhan-relative end is stored; the scheduler prefers [PrayerSilenceConfig.endOffsetMinutes].
 */
internal fun PrayerSilenceConfig.withLegacyDurationSynced(prayerTime: PrayerTime): PrayerSilenceConfig {
    if (mode != SilenceMode.DURATION || endOffsetMinutes == null) return this
    val window = resolvePrayerTimelineWindow(prayerTime, this)
    return copy(afterMinutes = window.durationMinutes.coerceAtLeast(0))
}

/** Long-arithmetic validity check that also rejects out-of-range resolved values. */
internal fun configResolvesToValidWindow(prayerTime: PrayerTime, config: PrayerSilenceConfig): Boolean {
    val prayerMinutes = prayerTime.hour * 60L + prayerTime.minute
    val start = if (config.startRuleMode() == EndpointRuleMode.FIXED_TIME) {
        config.delayFixedHour * 60L + config.delayFixedMinute
    } else {
        prayerMinutes + config.delayMinutes
    }
    val configEndOffset = config.endOffsetMinutes
    val end = when {
        config.endRuleMode() == EndpointRuleMode.FIXED_TIME -> {
            val fixedEnd = config.fixedHour * 60L + config.fixedMinute
            if (fixedEnd < start) fixedEnd + MINUTES_PER_DAY else fixedEnd
        }
        configEndOffset != null -> prayerMinutes + configEndOffset
        else -> start + config.afterMinutes
    }
    val bounds = Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
    return start in bounds && end in bounds && end >= start
}
/**
 * Maps a displayed window back to explicit independent endpoint rules. The active
 * gesture only ever moves one endpoint, so the untouched endpoint keeps its
 * resolved time while legacy duration rules are normalized to their equivalent
 * explicit rule for the displayed date.
 */
internal fun prayerTimelineConfigForWindow(
    prayerTime: PrayerTime,
    config: PrayerSilenceConfig,
    window: PrayerTimelineWindow,
): PrayerSilenceConfig? {
    if (window.endMinutes < window.startMinutes) return null
    var candidate = config
    if (config.startRuleMode() == EndpointRuleMode.FIXED_TIME) {
        if (window.startMinutes !in 0 until MINUTES_PER_DAY) return null
        candidate = candidate.copy(
            delayMode = DelayMode.FIXED_TIME,
            delayFixedHour = window.startMinutes / 60,
            delayFixedMinute = window.startMinutes % 60,
        )
    } else {
        val offset = window.startMinutes.toLong() - (prayerTime.hour * 60L + prayerTime.minute)
        if (offset !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) return null
        candidate = candidate.copy(delayMode = DelayMode.MINUTES, delayMinutes = offset.toInt())
    }
    if (config.endRuleMode() == EndpointRuleMode.FIXED_TIME) {
        if (window.endMinutes < 0) return null
        val endClock = window.endMinutes % MINUTES_PER_DAY
        candidate = candidate.copy(
            mode = SilenceMode.FIXED_TIME,
            fixedHour = endClock / 60,
            fixedMinute = endClock % 60,
            endOffsetMinutes = null,
        )
    } else {
        val endOffset = window.endMinutes.toLong() - (prayerTime.hour * 60L + prayerTime.minute)
        if (endOffset !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) return null
        candidate = candidate.copy(
            mode = SilenceMode.DURATION,
            endOffsetMinutes = endOffset.toInt(),
            // Legacy readers still use the duration; keep it in sync for the displayed date.
            afterMinutes = window.durationMinutes.coerceAtLeast(0),
        )
    }
    return candidate.takeIf { resolvePrayerTimelineWindow(prayerTime, it) == window }
}

private const val MINUTES_PER_DAY = 24 * 60

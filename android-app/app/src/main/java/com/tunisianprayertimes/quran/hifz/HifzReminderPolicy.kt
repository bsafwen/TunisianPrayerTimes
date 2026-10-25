package com.tunisianprayertimes.quran.hifz

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal const val HifzAlertGapMs = 60 * 60_000L
internal const val HifzCatchUpMs = 90 * 60_000L
internal const val HifzLaterMs = 30 * 60_000L

/** Current notification controls; [nextAllowedAt] is a restriction, not a promised reminder time. */
data class HifzReminderControlState(
    val pausedToday: Boolean,
    val snoozedUntil: Long?,
    val alertsToday: Int,
    val nextAllowedAt: Long?,
)

/** End of the quiet period containing [at], otherwise [at]. Equal endpoints disable the period. */
internal fun hifzAfterQuietHours(at: Long, settings: HifzSettings, zone: ZoneId = ZoneId.systemDefault()): Long {
    if (!settings.quietHours || settings.quietStartMinute == settings.quietEndMinute) return at
    val local = Instant.ofEpochMilli(at).atZone(zone)
    val minute = local.hour * 60 + local.minute
    val start = settings.quietStartMinute.coerceIn(0, 1439)
    val end = settings.quietEndMinute.coerceIn(0, 1439)
    val overnight = start > end
    val quiet = if (overnight) minute >= start || minute < end else minute in start until end
    if (!quiet) return at
    val date = local.toLocalDate().plusDays(if (overnight && minute >= start) 1 else 0)
    val boundary = date.atTime(end / 60, end % 60).atZone(zone)
    val futureBoundary = if (boundary.toInstant().toEpochMilli() < at) boundary.withLaterOffsetAtOverlap() else boundary
    return futureBoundary.toInstant().toEpochMilli()
}

internal fun hifzNextDayStart(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
    LocalDate.ofEpochDay(epochDay(now, zone) + 1).atStartOfDay(zone).toInstant().toEpochMilli()

/** An automatic cue cannot exceed the daily budget; explicit Later skips only the automatic gap. */
internal fun hifzAlertNotBefore(
    now: Long,
    settings: HifzSettings,
    alertsToday: Int,
    pausedToday: Boolean,
    lastAlertAt: Long,
    explicitLater: Boolean = false,
    zone: ZoneId = ZoneId.systemDefault(),
): Long {
    val dayStart = LocalDate.ofEpochDay(epochDay(now, zone)).atStartOfDay(zone).toInstant().toEpochMilli()
    val budgetStart = if (pausedToday || alertsToday >= settings.dailyAlertLimit.coerceAtLeast(1)) hifzNextDayStart(now, zone) else now
    // A timestamp from a clock accidentally set forward cannot suppress reminders after it is corrected.
    val validLast = lastAlertAt.takeIf { it in (dayStart - HifzAlertGapMs)..now }
    val gapEnd = if (!explicitLater && validLast != null) validLast + HifzAlertGapMs else now
    return hifzAfterQuietHours(maxOf(budgetStart, gapEnd), settings, zone)
}

/** Prayer and reading deferrals can themselves end in quiet hours; resolve their combined boundary. */
internal fun hifzAllowedAlertAt(
    at: Long,
    settings: HifzSettings,
    readingUntil: Long?,
    outsidePrayers: (Long) -> Long,
): Long {
    var allowed = maxOf(at, readingUntil ?: at)
    repeat(16) {
        val next = hifzAfterQuietHours(outsidePrayers(allowed), settings)
        if (next <= allowed) return allowed
        allowed = next
    }
    return allowed
}

/** Retains the selected slot's original instant while deciding whether a deferred cue is still timely. */
internal fun hifzCanCatchUp(originalAt: Long, fireAt: Long): Boolean = fireAt - originalAt in 0..HifzCatchUpMs

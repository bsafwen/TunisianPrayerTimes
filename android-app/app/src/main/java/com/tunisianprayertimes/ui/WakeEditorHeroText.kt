package com.tunisianprayertimes.ui

import com.tunisianprayertimes.formatArabicMinutes
import java.util.Calendar
import java.util.TimeZone

private const val MINUTE_MILLIS = 60_000L
private const val MINUTES_PER_HOUR = 60
private const val MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR

internal enum class WakeHeroRelativeDay { TODAY, TOMORROW, LATER }

/**
 * Time left until an alarm rings, e.g. "يوم و21 ساعة و26 دقيقة".
 * Partial minutes round up, matching the main screen countdown.
 */
internal fun formatWakeCountdownDuration(remainingMillis: Long): String {
    if (remainingMillis < MINUTE_MILLIS) {
        return "أقل من دقيقة"
    }

    val totalMinutes = ((remainingMillis + MINUTE_MILLIS - 1L) / MINUTE_MILLIS).toInt()
    val days = totalMinutes / MINUTES_PER_DAY
    val hours = (totalMinutes % MINUTES_PER_DAY) / MINUTES_PER_HOUR
    val minutes = totalMinutes % MINUTES_PER_HOUR
    return listOfNotNull(
        days.takeIf { it > 0 }?.let(::formatArabicDays),
        hours.takeIf { it > 0 }?.let(::formatArabicHours),
        minutes.takeIf { it > 0 }?.let(::formatArabicMinutes),
    ).joinToString(separator = " و")
}

/**
 * Time left until [targetMillis] as the clock reads it: the whole minutes between the two clock times, so a
 * countdown agrees with the time written beside it (a check due at 05:14:46 is 4 minutes after 05:10, not 5).
 * For a ring on a whole minute this is the same as rounding the time left up.
 */
internal fun wakeClockCountdownMillis(targetMillis: Long, nowMillis: Long): Long =
    (Math.floorDiv(targetMillis, MINUTE_MILLIS) - Math.floorDiv(nowMillis, MINUTE_MILLIS)) * MINUTE_MILLIS

internal fun wakeHeroRelativeDay(
    triggerAtMillis: Long,
    nowMillis: Long,
    timeZone: TimeZone = TimeZone.getDefault(),
): WakeHeroRelativeDay {
    val trigger = Calendar.getInstance(timeZone).apply { timeInMillis = triggerAtMillis }
    val day = Calendar.getInstance(timeZone).apply { timeInMillis = nowMillis }
    if (day.isSameDayAs(trigger)) {
        return WakeHeroRelativeDay.TODAY
    }
    day.add(Calendar.DAY_OF_YEAR, 1)
    return if (day.isSameDayAs(trigger)) WakeHeroRelativeDay.TOMORROW else WakeHeroRelativeDay.LATER
}

private fun Calendar.isSameDayAs(other: Calendar): Boolean =
    get(Calendar.YEAR) == other.get(Calendar.YEAR) &&
        get(Calendar.DAY_OF_YEAR) == other.get(Calendar.DAY_OF_YEAR)

private fun formatArabicDays(count: Int): String = when (count) {
    1 -> "يوم"
    2 -> "يومين"
    in 3..10 -> "$count أيام"
    else -> "$count يومًا"
}

private fun formatArabicHours(count: Int): String = when (count) {
    1 -> "ساعة"
    2 -> "ساعتين"
    in 3..10 -> "$count ساعات"
    else -> "$count ساعة"
}

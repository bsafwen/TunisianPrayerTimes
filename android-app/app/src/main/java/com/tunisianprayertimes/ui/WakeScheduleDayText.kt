package com.tunisianprayertimes.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.tunisianprayertimes.ALL_WAKE_SCHEDULE_DAYS
import com.tunisianprayertimes.R
import com.tunisianprayertimes.WakeScheduleDay
import com.tunisianprayertimes.normalizedWakeScheduleDays

internal fun isEveryWakeScheduleDay(days: Collection<WakeScheduleDay>): Boolean =
    days.normalizedWakeScheduleDays() == ALL_WAKE_SCHEDULE_DAYS

internal fun formatWakeScheduleDaysSummary(
    context: Context,
    days: Collection<WakeScheduleDay>,
): String {
    val normalizedDays = days.normalizedWakeScheduleDays()
    if (normalizedDays == ALL_WAKE_SCHEDULE_DAYS) {
        return context.getString(R.string.wake_schedule_days_every_day)
    }

    return normalizedDays.joinToString(separator = "، ") { day ->
        context.getString(wakeScheduleDayNameRes(day))
    }
}

@Composable
internal fun wakeScheduleDayLabel(day: WakeScheduleDay): String =
    stringResource(wakeScheduleDayNameRes(day))

private fun wakeScheduleDayNameRes(day: WakeScheduleDay): Int = when (day) {
    WakeScheduleDay.MONDAY -> R.string.wake_schedule_day_monday
    WakeScheduleDay.TUESDAY -> R.string.wake_schedule_day_tuesday
    WakeScheduleDay.WEDNESDAY -> R.string.wake_schedule_day_wednesday
    WakeScheduleDay.THURSDAY -> R.string.wake_schedule_day_thursday
    WakeScheduleDay.FRIDAY -> R.string.wake_schedule_day_friday
    WakeScheduleDay.SATURDAY -> R.string.wake_schedule_day_saturday
    WakeScheduleDay.SUNDAY -> R.string.wake_schedule_day_sunday
}

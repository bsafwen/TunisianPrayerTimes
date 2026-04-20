package com.tunisianprayertimes.wake

import android.content.Context
import com.tunisianprayertimes.OffsetDirection
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.R
import com.tunisianprayertimes.WakeMainAlarmMode
import java.util.Locale

internal fun Prayer.displayName(context: Context): String = when (this) {
    Prayer.FAJR -> context.getString(R.string.prayer_fajr)
    Prayer.DHUHR -> context.getString(R.string.prayer_dhuhr)
    Prayer.ASR -> context.getString(R.string.prayer_asr)
    Prayer.MAGHRIB -> context.getString(R.string.prayer_maghrib)
    Prayer.ISHA -> context.getString(R.string.prayer_isha)
    Prayer.JOMOAA -> context.getString(R.string.prayer_jomoaa)
    Prayer.AID_FITR -> context.getString(R.string.prayer_aid_fitr)
    Prayer.AID_ADHA -> context.getString(R.string.prayer_aid_adha)
}

internal fun formatWakeTime(hour: Int, minute: Int): String =
    String.format(Locale.getDefault(), "%02d:%02d", hour, minute)

internal fun formatWakeOffset(
    context: Context,
    minutes: Int,
    direction: OffsetDirection,
): String = context.getString(
    if (direction == OffsetDirection.BEFORE) {
        R.string.wake_alarm_offset_before
    } else {
        R.string.wake_alarm_offset_after
    },
    minutes,
)

internal fun WakeTriggerPayload.title(context: Context): String =
    if (mainAlarmMode == WakeMainAlarmMode.FIXED_TIME || mainAlarmMode == WakeMainAlarmMode.FROM_NOW) {
        context.getString(R.string.wake_alarm_fallback_title)
    } else {
        context.getString(R.string.wake_alarm_notification_title, effectivePrayer.displayName(context))
    }

internal fun WakeTriggerPayload.contentText(context: Context): String =
    if (mainAlarmMode == WakeMainAlarmMode.FROM_NOW && isSubAlarm && offsetMinutes != null && offsetDirection != null) {
        context.getString(
            R.string.wake_alarm_notification_text_one_off_subalarm,
            formatWakeOffset(context, offsetMinutes, offsetDirection),
            formatWakeTime(hour, minute),
        )
    } else if (mainAlarmMode == WakeMainAlarmMode.FROM_NOW) {
        context.getString(
            R.string.wake_alarm_notification_text_one_off_main,
            formatWakeTime(hour, minute),
        )
    } else if (mainAlarmMode == WakeMainAlarmMode.FIXED_TIME && isSubAlarm && offsetMinutes != null && offsetDirection != null) {
        context.getString(
            R.string.wake_alarm_notification_text_fixed_subalarm,
            formatWakeOffset(context, offsetMinutes, offsetDirection),
            formatWakeTime(hour, minute),
        )
    } else if (mainAlarmMode == WakeMainAlarmMode.FIXED_TIME) {
        context.getString(
            R.string.wake_alarm_notification_text_fixed_main,
            formatWakeTime(hour, minute),
        )
    } else if (isSubAlarm && offsetMinutes != null && offsetDirection != null) {
        context.getString(
            R.string.wake_alarm_notification_text_subalarm,
            formatWakeOffset(context, offsetMinutes, offsetDirection),
            effectivePrayer.displayName(context),
            formatWakeTime(hour, minute),
        )
    } else {
        context.getString(
            R.string.wake_alarm_notification_text_main,
            effectivePrayer.displayName(context),
            formatWakeTime(hour, minute),
        )
    }

internal fun WakeTriggerPayload.statusText(context: Context): String = when {
    vibrationOnly -> context.getString(R.string.wake_alarm_vibration_only)
    isSubAlarm && offsetMinutes != null && offsetDirection != null -> context.getString(
        R.string.wake_alarm_subalarm_label,
        formatWakeOffset(context, offsetMinutes, offsetDirection),
    )

    else -> context.getString(R.string.wake_alarm_main_label)
}
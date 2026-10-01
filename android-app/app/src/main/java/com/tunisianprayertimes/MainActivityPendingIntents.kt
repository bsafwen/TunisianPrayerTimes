package com.tunisianprayertimes

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * PendingIntents that open [MainActivity] from notifications and alarm-clock info.
 *
 * Each sender gets its own data URI so Intent.filterEquals() keeps their
 * PendingIntent records apart. Sharing one record would be wrong because
 * FLAG_UPDATE_CURRENT only replaces extras: the launch flags would stay those
 * of whichever sender created the record first, and every other sender would
 * overwrite the extras of the notifications already showing it.
 */
object MainActivityPendingIntents {
    const val AWAKE_CHECK_URI = "tunisianprayertimes://main/alarms/awake-check"
    const val WAKE_ALARM_CLOCK_URI = "tunisianprayertimes://main/alarm-clock/wake"
    const val SILENCE_ALARM_CLOCK_URI = "tunisianprayertimes://main/alarm-clock/silence"
    const val SILENCE_GUARD_URI = "tunisianprayertimes://main/silence/guard"

    /** Awake-check notification: brings the app back on the alarms tab. */
    fun awakeCheck(context: Context): PendingIntent = activity(
        context,
        mainIntent(context, AWAKE_CHECK_URI)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainTabNavigation.EXTRA_DESTINATION, MainTabNavigation.DESTINATION_ALARMS),
    )

    /** AlarmClockInfo show intent for wake alarms. */
    fun wakeAlarmClock(context: Context): PendingIntent = activity(
        context,
        mainIntent(context, WAKE_ALARM_CLOCK_URI).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
    )

    /** AlarmClockInfo show intent for silence and reschedule alarms. */
    fun silenceAlarmClock(context: Context): PendingIntent = activity(
        context,
        mainIntent(context, SILENCE_ALARM_CLOCK_URI),
    )

    /** Silence guard foreground notification. */
    fun silenceGuard(context: Context): PendingIntent = activity(
        context,
        mainIntent(context, SILENCE_GUARD_URI),
    )

    private fun mainIntent(context: Context, uri: String): Intent =
        Intent(context, MainActivity::class.java).setData(Uri.parse(uri))

    private fun activity(context: Context, intent: Intent): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

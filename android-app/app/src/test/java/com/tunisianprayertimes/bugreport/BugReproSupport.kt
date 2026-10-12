package com.tunisianprayertimes.bugreport

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import org.robolectric.Shadows
import java.util.Calendar
import java.util.TimeZone

/*
 * Regression tests for the bugs listed in android-app/BUG_REPORT.md. Each test is named
 * after its finding and asserts the correct behavior; it failed until that bug was fixed.
 */

/** Clears the preference files the silence and wake code uses. */
internal fun Context.clearSilenceAndWakePrefs() {
    listOf("prayer_silence_prefs", "wake_alarm_scheduler", "nap_prefs", "privacy_safe_analytics")
        .forEach { name -> getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit() }
}

/** Grants DND access in the shadow and puts the phone in normal mode with no DND. */
internal fun Context.resetPhoneToNormal() {
    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    Shadows.shadowOf(notificationManager).setNotificationPolicyAccessGranted(true)
    notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
    (getSystemService(Context.AUDIO_SERVICE) as AudioManager).ringerMode = AudioManager.RINGER_MODE_NORMAL
}

internal fun Context.ringerMode(): Int =
    (getSystemService(Context.AUDIO_SERVICE) as AudioManager).ringerMode

internal fun Context.interruptionFilter(): Int =
    (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).currentInterruptionFilter

internal fun Context.shadowAlarms() =
    Shadows.shadowOf(getSystemService(Context.ALARM_SERVICE) as AlarmManager)

internal fun calendarAt(year: Int, month: Int, day: Int, hour: Int, minute: Int): Calendar =
    Calendar.getInstance().apply {
        clear()
        set(year, month, day, hour, minute, 0)
    }

/**
 * Runs [block] with a default time zone chosen so that the local wall clock now reads
 * [hour]:[minute] (to the minute). Code that reads Calendar.getInstance() then sees that
 * local time, which makes time-of-day dependent paths deterministic.
 */
internal fun <T> withLocalClockAt(hour: Int, minute: Int, block: () -> T): T {
    val original = TimeZone.getDefault()
    val utcNow = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    val utcMinuteOfDay = utcNow.get(Calendar.HOUR_OF_DAY) * 60 + utcNow.get(Calendar.MINUTE)
    var offsetMinutes = (hour * 60 + minute) - utcMinuteOfDay
    if (offsetMinutes > 12 * 60) offsetMinutes -= 24 * 60
    if (offsetMinutes < -12 * 60) offsetMinutes += 24 * 60
    val sign = if (offsetMinutes < 0) "-" else "+"
    val absolute = kotlin.math.abs(offsetMinutes)
    val id = "GMT%s%02d:%02d".format(sign, absolute / 60, absolute % 60)
    TimeZone.setDefault(TimeZone.getTimeZone(id))
    return try {
        block()
    } finally {
        TimeZone.setDefault(original)
    }
}

package com.tunisianprayertimes

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.annotation.VisibleForTesting
import java.time.LocalDate
import java.util.Calendar
import java.util.concurrent.TimeUnit

object SilenceScheduler {
    private const val TAG = "SilenceScheduler"
    private const val ACTION_SILENCE = "com.tunisianprayertimes.ACTION_SILENCE"
    private const val ACTION_UNSILENCE = "com.tunisianprayertimes.ACTION_UNSILENCE"
    private const val ACTION_DELEGATION_CHECK = "com.tunisianprayertimes.ACTION_DELEGATION_CHECK"
    private const val ACTION_RESCHEDULE = "com.tunisianprayertimes.ACTION_RESCHEDULE"
    private const val EXTRA_PRAYER = "extra_prayer"
    private const val DELEGATION_CHECK_REQUEST_CODE_BASE = 10_000
    private const val POST_FINAL_WINDOW_RESCHEDULE_REQUEST_CODE = 9998
    private const val MIDNIGHT_RESCHEDULE_REQUEST_CODE = 9999
    private const val DELEGATION_CHECK_LEAD_MINUTES = 45L
    private const val POST_FINAL_WINDOW_RESCHEDULE_DELAY_MINUTES = 2

    // Measured max prayer-time spread across bundled Tunisian delegation data is 39 minutes.
    // Keep a 45-minute margin for delegation-change checks and shifted-window tolerance.
    private const val MAX_DELEGATION_SHIFT_MS = DELEGATION_CHECK_LEAD_MINUTES * 60 * 1000L

    /**
     * Schedule silence and unsilence alarms for all prayers today (and tomorrow if today's are past).
     */
    fun scheduleAll(context: Context) {
        RamadanOverrideChecker.loadCachedOverrideIfNeeded()
        scheduleAllInternal(context, Calendar.getInstance())
    }

    /**
     * Returns the [Prayer] whose silence window currently contains [now], or null.
     * Also returns the nearest upcoming prayer if auto-silence is already active
     * and that prayer starts within [MAX_DELEGATION_SHIFT_MS] (imminent window).
     */
    fun currentSilenceWindowPrayer(context: Context): Prayer? {
        RamadanOverrideChecker.loadCachedOverrideIfNeeded()
        val now = Calendar.getInstance()
        val delegationId = PrefsManager.getDelegationId(context)
        val todayTimes = PrayerTimesRepository.loadDayPrayerTimes(
            context, delegationId,
            now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH),
        ) ?: return null
        val isFriday = now.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY
        val jomoaaH = PrefsManager.getJomoaaTimeHour(context)
        val jomoaaM = PrefsManager.getJomoaaTimeMinute(context)

        var nearestImminentPrayer: Prayer? = null
        var nearestImminentDistance = Long.MAX_VALUE

        for (prayerTime in scheduledPrayersForDate(context, todayTimes, now, isFriday, jomoaaH, jomoaaM)) {
            val config = PrefsManager.getConfig(context, prayerTime.prayer)
            val silenceTime = if (config.delayMode == DelayMode.FIXED_TIME && config.delayFixedHour >= 0 && config.delayFixedMinute >= 0) {
                (now.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, config.delayFixedHour)
                    set(Calendar.MINUTE, config.delayFixedMinute)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
            } else {
                (now.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, prayerTime.hour)
                    set(Calendar.MINUTE, prayerTime.minute)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                    add(Calendar.MINUTE, config.delayMinutes)
                }
            }
            val unsilenceTime = if (config.mode == SilenceMode.FIXED_TIME && config.fixedHour >= 0 && config.fixedMinute >= 0) {
                (now.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, config.fixedHour)
                    set(Calendar.MINUTE, config.fixedMinute)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                    if (before(silenceTime)) add(Calendar.DAY_OF_YEAR, 1)
                }
            } else {
                (silenceTime.clone() as Calendar).apply { add(Calendar.MINUTE, config.afterMinutes) }
            }
            if (!now.before(silenceTime) && now.before(unsilenceTime)) {
                return prayerTime.prayer
            }
            // Track the nearest upcoming prayer within the delegation-shift tolerance
            if (silenceTime.after(now)) {
                val distance = silenceTime.timeInMillis - now.timeInMillis
                if (distance <= MAX_DELEGATION_SHIFT_MS && distance < nearestImminentDistance) {
                    nearestImminentDistance = distance
                    nearestImminentPrayer = prayerTime.prayer
                }
            }
        }
        // If auto-silence is active but we're in the gap between old/new delegation times,
        // return the imminent prayer so the UI can record dismissal correctly.
        if (nearestImminentPrayer != null && PrefsManager.isAutoSilenceActive(context)) {
            return nearestImminentPrayer
        }
        return null
    }

    @VisibleForTesting
    internal fun scheduleAllInternal(context: Context, now: Calendar) {
        if (!PrefsManager.isEnabled(context)) {
            cancelAll(context)
            return
        }

        PrefsManager.applyRamadanIshaOverrideIfNeeded(context)

        val delegationId = PrefsManager.getDelegationId(context)
        val year = now.get(Calendar.YEAR)
        val month = now.get(Calendar.MONTH) + 1
        val day = now.get(Calendar.DAY_OF_MONTH)
        val isFriday = now.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY

        val todayTimes = PrayerTimesRepository.loadDayPrayerTimes(context, delegationId, year, month, day)

        if (todayTimes == null) {
            Log.w(TAG, "No prayer times found for $delegationId/$year/$month/$day")
            if (SilenceModeController.disableAutoSilence(context)) {
                SilenceModeController.notifyIfMissedCallDuringSilence(context)
            }
            return
        }

        var currentlyInSilenceWindow = false
        var earliestUpcomingSilenceMs = Long.MAX_VALUE
        var latestWindowEndMs = Long.MIN_VALUE

        val jomoaaH = PrefsManager.getJomoaaTimeHour(context)
        val jomoaaM = PrefsManager.getJomoaaTimeMinute(context)

        for (prayerTime in scheduledPrayersForDate(context, todayTimes, now, isFriday, jomoaaH, jomoaaM)) {
            val config = PrefsManager.getConfig(context, prayerTime.prayer)

            val prayerStartTime = prayerStartTime(now, prayerTime)
            scheduleDelegationCheckIfNeeded(context, now, prayerStartTime, prayerTime.prayer)

            // Silence start: apply delay to prayer time
            val silenceTime = if (config.delayMode == DelayMode.FIXED_TIME && config.delayFixedHour >= 0 && config.delayFixedMinute >= 0) {
                (now.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, config.delayFixedHour)
                    set(Calendar.MINUTE, config.delayFixedMinute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
            } else {
                (now.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, prayerTime.hour)
                    set(Calendar.MINUTE, prayerTime.minute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                    add(Calendar.MINUTE, config.delayMinutes)
                }
            }

            // Unsilence based on mode: fixed time or duration (duration is relative to silence start)
            val unsilenceTime = if (config.mode == SilenceMode.FIXED_TIME && config.fixedHour >= 0 && config.fixedMinute >= 0) {
                (now.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, config.fixedHour)
                    set(Calendar.MINUTE, config.fixedMinute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                    if (before(silenceTime)) {
                        add(Calendar.DAY_OF_YEAR, 1)
                    }
                }
            } else {
                (silenceTime.clone() as Calendar).apply {
                    add(Calendar.MINUTE, config.afterMinutes)
                }
            }
            latestWindowEndMs = maxOf(latestWindowEndMs, unsilenceTime.timeInMillis)

            // Check if we are currently inside this prayer's silence window
            if (!now.before(silenceTime) && now.before(unsilenceTime)) {
                currentlyInSilenceWindow = true

                // If the user manually dismissed auto-silence during this specific
                // prayer's window, don't re-enable it.  Check both the timestamp
                // and the prayer name so overlapping windows from different prayers
                // are not affected.
                val dismissedAt = PrefsManager.getAutoSilenceDismissedUntilMillis(context)
                val dismissedPrayer = PrefsManager.getAutoSilenceDismissedPrayer(context)
                if (dismissedAt >= silenceTime.timeInMillis - MAX_DELEGATION_SHIFT_MS && dismissedPrayer == prayerTime.prayer.name) {
                    scheduleExactAlarm(context, unsilenceTime.timeInMillis, ACTION_UNSILENCE, prayerTime.prayer)
                    Log.d(TAG, "In silence window for ${prayerTime.prayer} but user dismissed; only scheduling UNSILENCE")
                    continue
                }

                // We're in the middle of a silence window — ensure phone is silenced
                // and schedule the unsilence
                PrefsManager.clearAutoSilenceDismissed(context)
                SilenceModeController.enableAutoSilence(context, prayerTime.prayer)
                scheduleExactAlarm(context, unsilenceTime.timeInMillis, ACTION_UNSILENCE, prayerTime.prayer)
                Log.d(TAG, "Currently in silence window for ${prayerTime.prayer}, scheduled UNSILENCE at ${unsilenceTime.time}")
                continue
            }

            // Only schedule if in the future
            if (silenceTime.after(now)) {
                earliestUpcomingSilenceMs = minOf(earliestUpcomingSilenceMs, silenceTime.timeInMillis)
                scheduleExactAlarm(context, silenceTime.timeInMillis, ACTION_SILENCE, prayerTime.prayer)
                Log.d(TAG, "Scheduled SILENCE for ${prayerTime.prayer} at ${silenceTime.time}")
            }

            if (unsilenceTime.after(now)) {
                scheduleExactAlarm(context, unsilenceTime.timeInMillis, ACTION_UNSILENCE, prayerTime.prayer)
                Log.d(TAG, "Scheduled UNSILENCE for ${prayerTime.prayer} at ${unsilenceTime.time}")
            }

            // Cancel stale alarms for prayers whose window has fully passed.
            // Without this, a delegation change can leave an old UNSILENCE alarm
            // that fires during the imminent window of the next prayer, bypassing
            // the silence-bridging protection.
            if (!silenceTime.after(now) && !unsilenceTime.after(now)) {
                cancelPrayerAlarms(context, prayerTime.prayer)
                Log.d(TAG, "Cancelled stale alarms for past prayer ${prayerTime.prayer}")
            }
        }

        // If we're not in any silence window, restore the previous ringer state
        // if auto-silence was active (handles missed unsilence alarms).
        // This is a no-op when the flag is not set, so manual silence is preserved.
        // However, if auto-silence IS active and a prayer window starts within
        // MAX_DELEGATION_SHIFT_MS, a delegation change likely shifted the window
        // slightly — keep the phone silenced so the user isn't interrupted.
        if (!currentlyInSilenceWindow) {
            val imminentWindow = PrefsManager.isAutoSilenceActive(context)
                && earliestUpcomingSilenceMs - now.timeInMillis <= MAX_DELEGATION_SHIFT_MS
            if (!imminentWindow) {
                if (SilenceModeController.disableAutoSilence(context)) {
                    SilenceModeController.notifyIfMissedCallDuringSilence(context)
                }
                Log.d(TAG, "Not in any silence window, ensuring phone is in previous mode")
            } else {
                Log.d(TAG, "Not in window but silence active and next window imminent; keeping silence")
            }
        }

        // Pre-schedule ALL of tomorrow's prayers once today's final silence
        // window has passed. This keeps tomorrow's alarms on exact AlarmManager
        // delivery instead of depending on a frequent WorkManager verifier. Wait
        // for the latest window end so custom fixed-time windows cannot be
        // overwritten by tomorrow's alarms sharing the same request codes.
        if (latestWindowEndMs != Long.MIN_VALUE && now.timeInMillis < latestWindowEndMs) {
            schedulePostFinalWindowReschedule(context, latestWindowEndMs)
        }
        if (latestWindowEndMs != Long.MIN_VALUE && now.timeInMillis >= latestWindowEndMs && !currentlyInSilenceWindow) {
            scheduleTomorrowPrayers(context, delegationId, now)
        }

        // Also schedule a daily reschedule at midnight to set up next day's alarms
        scheduleMidnightReschedule(context)
    }

    /**
     * Pre-schedule ALL of tomorrow's silence/unsilence alarms.
     * This acts as a safety net: if the midnight reschedule alarm is killed by aggressive
     * OEM battery optimization, prayers will still fire because they were set hours in advance.
     */
    internal fun scheduleTomorrowPrayers(context: Context, delegationId: Int, now: Calendar) {
        val tomorrow = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        val tYear = tomorrow.get(Calendar.YEAR)
        val tMonth = tomorrow.get(Calendar.MONTH) + 1
        val tDay = tomorrow.get(Calendar.DAY_OF_MONTH)

        val tomorrowTimes = PrayerTimesRepository.loadDayPrayerTimes(context, delegationId, tYear, tMonth, tDay)
            ?: PrayerTimesRepository.loadDayPrayerTimes(context, delegationId, tYear - 1, tMonth, tDay)
            ?: return

        val isFriday = tomorrow.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY
        val jomoaaH = PrefsManager.getJomoaaTimeHour(context)
        val jomoaaM = PrefsManager.getJomoaaTimeMinute(context)

        for (prayerTime in scheduledPrayersForDate(context, tomorrowTimes, tomorrow, isFriday, jomoaaH, jomoaaM)) {
            val config = PrefsManager.getConfig(context, prayerTime.prayer)
            val prayerStartTime = prayerStartTime(tomorrow, prayerTime)
            scheduleDelegationCheckIfNeeded(context, now, prayerStartTime, prayerTime.prayer)

            val silenceTime = if (config.delayMode == DelayMode.FIXED_TIME && config.delayFixedHour >= 0 && config.delayFixedMinute >= 0) {
                (tomorrow.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, config.delayFixedHour)
                    set(Calendar.MINUTE, config.delayFixedMinute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
            } else {
                (tomorrow.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, prayerTime.hour)
                    set(Calendar.MINUTE, prayerTime.minute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                    add(Calendar.MINUTE, config.delayMinutes)
                }
            }

            val unsilenceTime = if (config.mode == SilenceMode.FIXED_TIME && config.fixedHour >= 0 && config.fixedMinute >= 0) {
                (tomorrow.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, config.fixedHour)
                    set(Calendar.MINUTE, config.fixedMinute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                    if (before(silenceTime)) {
                        add(Calendar.DAY_OF_YEAR, 1)
                    }
                }
            } else {
                (silenceTime.clone() as Calendar).apply {
                    add(Calendar.MINUTE, config.afterMinutes)
                }
            }

            if (silenceTime.after(now)) {
                scheduleExactAlarm(context, silenceTime.timeInMillis, ACTION_SILENCE, prayerTime.prayer)
                Log.d(TAG, "Pre-scheduled tomorrow's SILENCE for ${prayerTime.prayer} at ${silenceTime.time}")
            }
            if (unsilenceTime.after(now)) {
                scheduleExactAlarm(context, unsilenceTime.timeInMillis, ACTION_UNSILENCE, prayerTime.prayer)
                Log.d(TAG, "Pre-scheduled tomorrow's UNSILENCE for ${prayerTime.prayer} at ${unsilenceTime.time}")
            }
        }
    }

    fun cancelAll(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (prayer in Prayer.values()) {
            val silenceIntent = createPendingIntent(context, ACTION_SILENCE, prayer)
            val unsilenceIntent = createPendingIntent(context, ACTION_UNSILENCE, prayer)
            val delegationCheckIntent = createPendingIntent(context, ACTION_DELEGATION_CHECK, prayer)
            alarmManager.cancel(silenceIntent)
            alarmManager.cancel(unsilenceIntent)
            alarmManager.cancel(delegationCheckIntent)
        }
        // Cancel daily reschedule safety nets.
        alarmManager.cancel(createReschedulePendingIntent(context, MIDNIGHT_RESCHEDULE_REQUEST_CODE))
        alarmManager.cancel(createReschedulePendingIntent(context, POST_FINAL_WINDOW_RESCHEDULE_REQUEST_CODE))
        // Restore normal mode when cancelling all alarms
        SilenceModeController.disableAutoSilence(context)
    }

    private fun scheduleExactAlarm(context: Context, triggerAtMillis: Long, action: String, prayer: Prayer) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = createPendingIntent(context, action, prayer)

        // Check exact alarm permission on Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            Log.w(TAG, "Cannot schedule exact alarms - permission not granted, skipping $action for ${prayer.name}")
            return
        }

        val showIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.setAlarmClock(
            AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent),
            pendingIntent
        )
    }

    private fun scheduleDelegationCheckIfNeeded(context: Context, now: Calendar, prayerStartTime: Calendar, prayer: Prayer) {
        if (!PrefsManager.isAutoLocationUpdateEnabled(context) || !DelegationLocator.hasLocationPermission(context)) {
            return
        }
        if (!prayerStartTime.after(now)) {
            return
        }

        val idealTriggerAtMillis = prayerStartTime.timeInMillis - TimeUnit.MINUTES.toMillis(DELEGATION_CHECK_LEAD_MINUTES)
        val triggerAtMillis = maxOf(idealTriggerAtMillis, now.timeInMillis)
        scheduleDelegationCheckAlarm(context, triggerAtMillis, prayer)
    }

    private fun scheduleDelegationCheckAlarm(context: Context, triggerAtMillis: Long, prayer: Prayer) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            Log.w(TAG, "Cannot schedule exact alarms - permission not granted, skipping delegation check for ${prayer.name}")
            return
        }

        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAtMillis,
            createPendingIntent(context, ACTION_DELEGATION_CHECK, prayer),
        )
    }

    private fun scheduleMidnightReschedule(context: Context) {
        val midnight = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 1)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        scheduleRescheduleAlarm(
            context = context,
            triggerAtMillis = midnight.timeInMillis,
            requestCode = MIDNIGHT_RESCHEDULE_REQUEST_CODE,
            label = "midnight reschedule",
        )
    }

    private fun schedulePostFinalWindowReschedule(context: Context, latestWindowEndMs: Long) {
        val triggerAtMillis = latestWindowEndMs + TimeUnit.MINUTES.toMillis(POST_FINAL_WINDOW_RESCHEDULE_DELAY_MINUTES.toLong())
        scheduleRescheduleAlarm(
            context = context,
            triggerAtMillis = triggerAtMillis,
            requestCode = POST_FINAL_WINDOW_RESCHEDULE_REQUEST_CODE,
            label = "post-final-window reschedule",
        )
    }

    private fun scheduleRescheduleAlarm(context: Context, triggerAtMillis: Long, requestCode: Int, label: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            Log.w(TAG, "Cannot schedule exact alarms - permission not granted, skipping $label")
            return
        }

        val showIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.setAlarmClock(
            AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent),
            createReschedulePendingIntent(context, requestCode),
        )
    }

    private fun createReschedulePendingIntent(context: Context, requestCode: Int): PendingIntent {
        val intent = Intent(context, SilenceReceiver::class.java).apply {
            action = ACTION_RESCHEDULE
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun cancelPrayerAlarms(context: Context, prayer: Prayer) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(createPendingIntent(context, ACTION_SILENCE, prayer))
        alarmManager.cancel(createPendingIntent(context, ACTION_UNSILENCE, prayer))
        alarmManager.cancel(createPendingIntent(context, ACTION_DELEGATION_CHECK, prayer))
    }

    private fun prayerStartTime(date: Calendar, prayerTime: PrayerTime): Calendar {
        return (date.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, prayerTime.hour)
            set(Calendar.MINUTE, prayerTime.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }

    private fun scheduledPrayersForDate(
        context: Context,
        dayTimes: DayPrayerTimes,
        date: Calendar,
        isFriday: Boolean,
        jomoaaHour: Int,
        jomoaaMinute: Int,
    ): List<PrayerTime> {
        val prayers = dayTimes.scheduledPrayers(isFriday, jomoaaHour, jomoaaMinute).toMutableList()
        val localDate = date.toLocalDate()

        if (RamadanOverrideChecker.isEidFitr(localDate)) {
            prayers += eidPrayerTime(
                prayer = Prayer.AID_FITR,
                dayTimes = dayTimes,
                hour = PrefsManager.getAidFitrTimeHour(context),
                minute = PrefsManager.getAidFitrTimeMinute(context),
            )
        }

        if (RamadanOverrideChecker.isEidAdha(localDate)) {
            prayers += eidPrayerTime(
                prayer = Prayer.AID_ADHA,
                dayTimes = dayTimes,
                hour = PrefsManager.getAidAdhaTimeHour(context),
                minute = PrefsManager.getAidAdhaTimeMinute(context),
            )
        }

        return prayers.sortedBy { it.hour * 60 + it.minute }
    }

    private fun eidPrayerTime(prayer: Prayer, dayTimes: DayPrayerTimes, hour: Int, minute: Int): PrayerTime {
        return PrayerTime(
            prayer = prayer,
            hour = if (hour >= 0) hour else dayTimes.shurukHour,
            minute = if (minute >= 0) minute else dayTimes.shurukMinute,
        )
    }

    private fun Calendar.toLocalDate(): LocalDate = LocalDate.of(
        get(Calendar.YEAR),
        get(Calendar.MONTH) + 1,
        get(Calendar.DAY_OF_MONTH),
    )

    private fun createPendingIntent(context: Context, action: String, prayer: Prayer): PendingIntent {
        val requestCode = when (action) {
            ACTION_SILENCE -> prayer.ordinal * 2
            ACTION_UNSILENCE -> prayer.ordinal * 2 + 1
            ACTION_DELEGATION_CHECK -> DELEGATION_CHECK_REQUEST_CODE_BASE + prayer.ordinal
            else -> prayer.ordinal * 2
        }
        val intent = Intent(context, SilenceReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_PRAYER, prayer.name)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

}

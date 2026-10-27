package com.tunisianprayertimes

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.tunisianprayertimes.place.PrayerPlace
import com.tunisianprayertimes.silence.SilenceDay
import com.tunisianprayertimes.silence.SilenceRule
import com.tunisianprayertimes.silence.SilenceWindow
import com.tunisianprayertimes.silence.SilenceWindowPlanner
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Arms the prayers' silence windows (plan 6.9). Every run takes the planner's windows of prayer days D−2..D+1,
 * D being today in the place's zone, and arms the start of every window that has not started and the end of
 * every window that has not ended, each under its own prayer day's request code, so all of D+1 is armed a day
 * ahead and a window carried over from an earlier day keeps its end. A window that contains now is silenced at
 * once unless the user dismissed that occurrence.
 */
object SilenceScheduler {
    private const val TAG = "SilenceScheduler"
    private const val ACTION_SILENCE = "com.tunisianprayertimes.ACTION_SILENCE"
    private const val ACTION_UNSILENCE = "com.tunisianprayertimes.ACTION_UNSILENCE"
    private const val ACTION_DELEGATION_CHECK = "com.tunisianprayertimes.ACTION_DELEGATION_CHECK"
    private const val ACTION_RESCHEDULE = "com.tunisianprayertimes.ACTION_RESCHEDULE"
    internal const val EXTRA_PRAYER = "extra_prayer"
    internal const val EXTRA_PRAYER_DAY = "extra_prayer_day"

    /** Four day slots per prayer and action (100–163), so the four scanned prayer days never share a code. */
    private const val SLOT_CODE_BASE = 100
    private const val DAY_SLOTS = 4
    private const val DELEGATION_CHECK_REQUEST_CODE_BASE = 10_000
    private const val LEGACY_POST_FINAL_WINDOW_RESCHEDULE_REQUEST_CODE = 9998
    private const val MIDNIGHT_RESCHEDULE_REQUEST_CODE = 9999
    private const val DELEGATION_CHECK_LEAD_MINUTES = 45L

    // The 45-minute tolerance stays for bridging and dismissal matching of the same prayer at the same place.
    private const val MAX_DELEGATION_SHIFT_MS = DELEGATION_CHECK_LEAD_MINUTES * 60 * 1000L

    /**
     * The current instant. Windows are instants in the place's zone (6.6), so a test that moves the phone's
     * zone does not move them; tests set this instead.
     */
    @VisibleForTesting
    internal var clock: () -> Instant = { Instant.now() }

    internal fun now(): Instant = clock()

    /** Schedule every window of the scan (D−2..D+1). */
    fun scheduleAll(context: Context) {
        RamadanOverrideChecker.loadCachedOverrideIfNeeded()
        scheduleAllInternal(context, Calendar.getInstance())
    }

    /** Called when announcements change, including while no screen is open: Eid windows move with them. */
    @Synchronized
    fun onOfficialDatesChanged(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!PrefsManager.isEnabled(context) ||
            !notificationManager.isNotificationPolicyAccessGranted ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms())) {
            cancelAll(context)
            return
        }
        // The scan re-arms every window, so a running window and its end alarm are kept.
        scheduleAllInternal(context, Calendar.getInstance(), allowDelegationBridge = false)
    }

    /**
     * The planner over the saved place: the enabled prayers' rules (6.9, an unedited Dhuhr or Jumu'a endpoint
     * unset), each prayer day's times, the Friday's Jumu'a time (an edited time, else A18's default) and, at a
     * Tunisian place, its Eid prayers (A10).
     */
    fun planner(context: Context, place: PrayerPlace = PrayerTimesRepository.place(context)): SilenceWindowPlanner {
        val rules = Prayer.entries.filter { PrefsManager.isPrayerSilenceEnabled(context, it) }
            .associateWith { PrefsManager.getSilenceRule(context, it) }
        return planner(context, place, rules)
    }

    /** The planner with one prayer's window replaced by an editor's draft (the wake conflict guide, 6.9). */
    fun plannerWithDraft(context: Context, prayer: Prayer, draft: PrayerSilenceConfig?): SilenceWindowPlanner {
        val place = PrayerTimesRepository.place(context)
        val rules = Prayer.entries.filter { PrefsManager.isPrayerSilenceEnabled(context, it) }
            .associateWith { PrefsManager.getSilenceRule(context, it) }
            .let { enabled -> if (draft != null) enabled + (prayer to SilenceRule.of(draft)) else enabled }
        return planner(context, place, rules)
    }

    internal fun planner(context: Context, place: PrayerPlace, rules: Map<Prayer, SilenceRule>): SilenceWindowPlanner =
        SilenceWindowPlanner(place.zone, place is PrayerPlace.Inm, rules) { date ->
            val day = PrayerTimesRepository.prayerDay(context, date, place)
            SilenceDay(
                date = date,
                day = day,
                jumua = if (date.dayOfWeek == DayOfWeek.FRIDAY && day != null) PrayerTimesRepository.jumuaTime(context, date, place) else null,
                eid = EidSilenceWindows.eidPrayers(context, place, date, day),
            )
        }

    /**
     * The prayer whose window contains now, the one that started first; or, while auto-silence is on, the
     * prayer whose window starts within the 45-minute tolerance (a place change shifted it slightly).
     */
    fun currentSilenceWindowPrayer(context: Context): Prayer? {
        RamadanOverrideChecker.loadCachedOverrideIfNeeded()
        val now = now()
        val planner = planner(context)
        planner.windowAt(now)?.let { return it.prayer }
        if (!PrefsManager.isAutoSilenceActive(context)) return null
        return planner.windows(now)
            .filter { it.start > now && it.start.toEpochMilli() - now.toEpochMilli() <= MAX_DELEGATION_SHIFT_MS }
            .minByOrNull { it.start }?.prayer
    }

    /** The latest end of the enabled windows containing now, or null outside every window (6.9). */
    fun currentSilenceWindowEnd(context: Context): Long? {
        RamadanOverrideChecker.loadCachedOverrideIfNeeded()
        return planner(context).latestEndContaining(now())?.toEpochMilli()
    }

    /**
     * Whether a window other than the ending occurrence contains now: another prayer's, an Eid window, or
     * another occurrence of the same prayer (a window may last up to 36 h, 6.9). [endingDay] is the ending
     * alarm's prayer day; without it (an alarm armed before 6.9), every window of [endingPrayer] counts as ending.
     */
    fun isOtherSilenceWindowActive(context: Context, endingPrayer: Prayer?, endingDay: LocalDate? = null): Boolean {
        if (!PrefsManager.isEnabled(context)) return false
        RamadanOverrideChecker.loadCachedOverrideIfNeeded()
        return planner(context).containing(now()).any { window ->
            window.prayer != endingPrayer || (endingDay != null && window.prayerDay != endingDay)
        }
    }

    /**
     * True when the occurrence ([prayer], [day]) was dismissed (6.9), or, for a dismissal recorded the old way
     * (the prayer and an instant), when it was made at most 45 min before the window's start or later.
     */
    fun isDismissed(context: Context, prayer: Prayer, day: LocalDate): Boolean {
        if ((prayer to day) in PrefsManager.getAutoSilenceDismissals(context)) return true
        if (PrefsManager.getAutoSilenceDismissedPrayer(context) != prayer.name) return false
        val start = windowOf(context, prayer, day)?.start?.toEpochMilli() ?: return false
        return PrefsManager.getAutoSilenceDismissedUntilMillis(context) >= start - MAX_DELEGATION_SHIFT_MS
    }

    /**
     * Records a dismissal of auto-silence now: the prayer day of each window containing now, or [fallback]'s
     * nearest window (the bridging case), so only those occurrences stay quiet.
     */
    fun dismissCurrentWindows(context: Context, fallback: Prayer? = null) {
        val now = now()
        val planner = planner(context)
        val open = planner.containing(now).ifEmpty {
            planner.windows(now).filter { it.prayer == fallback && it.start > now }.sortedBy { it.start }.take(1)
        }
        if (open.isEmpty()) return
        val today = now.atZone(PrayerTimesRepository.place(context).zone).toLocalDate()
        PrefsManager.addAutoSilenceDismissals(context, open.map { it.prayer to it.prayerDay }, keepFrom = today.minusDays(SilenceWindowPlanner.LOOK_BACK_DAYS + 1))
    }

    private fun isDismissed(context: Context, prayer: Prayer, day: LocalDate, window: SilenceWindow): Boolean {
        if ((prayer to day) in PrefsManager.getAutoSilenceDismissals(context)) return true
        return PrefsManager.getAutoSilenceDismissedPrayer(context) == prayer.name &&
            PrefsManager.getAutoSilenceDismissedUntilMillis(context) >= window.start.toEpochMilli() - MAX_DELEGATION_SHIFT_MS
    }

    /** The window of one occurrence, when it is still in the scan around now. */
    internal fun windowOf(context: Context, prayer: Prayer, day: LocalDate): SilenceWindow? =
        planner(context).windowsOf(day).firstOrNull { it.prayer == prayer }

    @VisibleForTesting
    @Synchronized
    internal fun scheduleAllInternal(context: Context, now: Calendar, allowDelegationBridge: Boolean = true) {
        if (!PrefsManager.isEnabled(context)) {
            cancelAll(context)
            return
        }

        PrefsManager.applyRamadanIshaOverrideIfNeeded(context)
        val place = PrayerTimesRepository.place(context)
        val planner = planner(context, place)
        val nowMillis = now.timeInMillis
        val nowInstant = Instant.ofEpochMilli(nowMillis)
        val today = nowInstant.atZone(place.zone).toLocalDate()
        val scanned = (-SilenceWindowPlanner.LOOK_BACK_DAYS..SilenceWindowPlanner.LOOK_AHEAD_DAYS).map { today.plusDays(it) }
        val windows = planner.windows(nowInstant)
        val armed = HashSet<Pair<Prayer, LocalDate>>()
        var currentlyInSilenceWindow = false
        var earliestUpcomingSilenceMs = Long.MAX_VALUE

        for (window in windows) {
            val occurrence = window.prayer to window.prayerDay
            armed += occurrence
            val start = window.start.toEpochMilli()
            val end = window.end.toEpochMilli()
            scheduleDelegationCheckIfNeeded(context, nowMillis, window)
            if (nowMillis in start until end) {
                currentlyInSilenceWindow = true
                cancelPrayerAlarm(context, ACTION_SILENCE, window.prayer, window.prayerDay)
                scheduleExactAlarm(context, end, ACTION_UNSILENCE, window.prayer, window.prayerDay)
                if (isDismissed(context, window.prayer, window.prayerDay, window)) {
                    Log.d(TAG, "In ${window.prayer} of ${window.prayerDay}, dismissed; only its end is armed")
                } else {
                    SilenceModeController.enableAutoSilence(context, window.prayer)
                    Log.d(TAG, "In ${window.prayer} of ${window.prayerDay} until ${window.end}")
                }
                continue
            }
            if (start > nowMillis) {
                earliestUpcomingSilenceMs = minOf(earliestUpcomingSilenceMs, start)
                scheduleExactAlarm(context, start, ACTION_SILENCE, window.prayer, window.prayerDay)
            } else {
                cancelPrayerAlarm(context, ACTION_SILENCE, window.prayer, window.prayerDay)
            }
            if (end > nowMillis) {
                scheduleExactAlarm(context, end, ACTION_UNSILENCE, window.prayer, window.prayerDay)
            } else {
                cancelPrayerAlarm(context, ACTION_UNSILENCE, window.prayer, window.prayerDay)
            }
        }

        // A day slot no window of the scan uses (a prayer switched off, a time the day lacks, a moved window)
        // keeps no alarm. Each slot names exactly one of the four scanned prayer days.
        for (prayer in Prayer.entries) {
            for (day in scanned) {
                if ((prayer to day) !in armed) cancelPrayerAlarms(context, prayer, day)
            }
        }

        // Not in any window: restore the previous ringer state if auto-silence was on (a missed end alarm).
        // While auto-silence is on and a window starts within the tolerance, a place change likely shifted
        // the window slightly: keep the phone silenced.
        if (!currentlyInSilenceWindow) {
            val imminentWindow = allowDelegationBridge && PrefsManager.isAutoSilenceActive(context) &&
                earliestUpcomingSilenceMs - nowMillis <= MAX_DELEGATION_SHIFT_MS
            if (!imminentWindow) {
                if (SilenceModeController.disableAutoSilence(context)) {
                    SilenceModeController.notifyIfMissedCallDuringSilence(context)
                }
            } else {
                Log.d(TAG, "Not in a window, but silence is on and the next window is imminent; keeping silence")
            }
        }

        scheduleMidnightReschedule(context, place, today)
    }

    @Synchronized
    fun cancelAll(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (prayer in Prayer.entries) {
            for (slot in 0 until DAY_SLOTS) {
                for (action in listOf(ACTION_SILENCE, ACTION_UNSILENCE, ACTION_DELEGATION_CHECK)) cancelCode(context, action, requestCode(action, prayer, slot))
            }
        }
        cancelLegacyAlarms(context)
        alarmManager.cancel(createReschedulePendingIntent(context, MIDNIGHT_RESCHEDULE_REQUEST_CODE))
        SilenceModeController.disableAutoSilence(context)
    }

    /**
     * The codes of the scheduler before 6.9, cancelled on every `MY_PACKAGE_REPLACED` before [scheduleAll]:
     * the starts and ends 0–15, the location checks 10_000–10_007 (whose codes and action the new checks of
     * Fajr and Dhuhr reuse, so they are armed again by the next run) and the post-window reschedule 9998.
     */
    fun cancelLegacyAlarms(context: Context) {
        for (prayer in Prayer.entries) {
            cancelCode(context, ACTION_SILENCE, prayer.ordinal * 2)
            cancelCode(context, ACTION_UNSILENCE, prayer.ordinal * 2 + 1)
            cancelCode(context, ACTION_DELEGATION_CHECK, DELEGATION_CHECK_REQUEST_CODE_BASE + prayer.ordinal)
        }
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
            .cancel(createReschedulePendingIntent(context, LEGACY_POST_FINAL_WINDOW_RESCHEDULE_REQUEST_CODE))
    }

    private fun scheduleExactAlarm(context: Context, triggerAtMillis: Long, action: String, prayer: Prayer, day: LocalDate) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            Log.w(TAG, "Cannot schedule exact alarms - permission not granted, skipping $action for ${prayer.name}")
            return
        }
        val showIntent = MainActivityPendingIntents.silenceAlarmClock(context)
        alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent), createPendingIntent(context, action, prayer, day))
    }

    /**
     * The pre-prayer location check, 45 min before the adhan, under the window's day slot. Only with the
     * background-location permission: without it Android refuses the fix to an app with no screen open, and the
     * alarm would only wake the phone.
     */
    private fun scheduleDelegationCheckIfNeeded(context: Context, nowMillis: Long, window: SilenceWindow) {
        val adhan = window.adhan.toEpochMilli()
        if (adhan <= nowMillis || !PrefsManager.isAutoLocationUpdateEnabled(context) ||
            !DelegationLocator.hasBackgroundLocationPermission(context)
        ) {
            cancelPrayerAlarm(context, ACTION_DELEGATION_CHECK, window.prayer, window.prayerDay)
            return
        }
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            Log.w(TAG, "Cannot schedule exact alarms - permission not granted, skipping location check for ${window.prayer}")
            return
        }
        val triggerAtMillis = maxOf(adhan - TimeUnit.MINUTES.toMillis(DELEGATION_CHECK_LEAD_MINUTES), nowMillis)
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAtMillis,
            createPendingIntent(context, ACTION_DELEGATION_CHECK, window.prayer, window.prayerDay),
        )
    }

    /** 00:01 of D+1 in the place's zone, armed even when a day has no times (6.9). */
    private fun scheduleMidnightReschedule(context: Context, place: PrayerPlace, today: LocalDate) {
        val triggerAtMillis = ZonedDateTime.of(today.plusDays(1), LocalTime.of(0, 1), place.zone).toInstant().toEpochMilli()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            Log.w(TAG, "Cannot schedule exact alarms - permission not granted, skipping midnight reschedule")
            return
        }
        val showIntent = MainActivityPendingIntents.silenceAlarmClock(context)
        alarmManager.setAlarmClock(
            AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent),
            createReschedulePendingIntent(context, MIDNIGHT_RESCHEDULE_REQUEST_CODE),
        )
    }

    private fun createReschedulePendingIntent(context: Context, requestCode: Int): PendingIntent {
        val intent = Intent(context, SilenceReceiver::class.java).apply { action = ACTION_RESCHEDULE }
        return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun cancelPrayerAlarms(context: Context, prayer: Prayer, day: LocalDate) {
        cancelPrayerAlarm(context, ACTION_SILENCE, prayer, day)
        cancelPrayerAlarm(context, ACTION_UNSILENCE, prayer, day)
        cancelPrayerAlarm(context, ACTION_DELEGATION_CHECK, prayer, day)
    }

    private fun cancelPrayerAlarm(context: Context, action: String, prayer: Prayer, day: LocalDate) =
        cancelCode(context, action, requestCode(action, prayer, slot(day)))

    private fun cancelCode(context: Context, action: String, requestCode: Int) {
        val intent = Intent(context, SilenceReceiver::class.java).apply { this.action = action }
        val pendingIntent = PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) ?: return
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pendingIntent)
        pendingIntent.cancel()
    }

    private fun slot(day: LocalDate): Int = Math.floorMod(day.toEpochDay(), DAY_SLOTS.toLong()).toInt()

    @VisibleForTesting
    internal fun requestCode(action: String, prayer: Prayer, slot: Int): Int = when (action) {
        ACTION_SILENCE -> SLOT_CODE_BASE + (prayer.ordinal * 2) * DAY_SLOTS + slot
        ACTION_UNSILENCE -> SLOT_CODE_BASE + (prayer.ordinal * 2 + 1) * DAY_SLOTS + slot
        ACTION_DELEGATION_CHECK -> DELEGATION_CHECK_REQUEST_CODE_BASE + prayer.ordinal * DAY_SLOTS + slot
        else -> error("no request code for $action")
    }

    private fun createPendingIntent(context: Context, action: String, prayer: Prayer, day: LocalDate): PendingIntent {
        val intent = Intent(context, SilenceReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_PRAYER, prayer.name)
            putExtra(EXTRA_PRAYER_DAY, day.toEpochDay())
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode(action, prayer, slot(day)),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** The prayer day an alarm names, or null for an alarm armed before 6.9. */
    internal fun prayerDayOf(intent: Intent): LocalDate? =
        if (intent.hasExtra(EXTRA_PRAYER_DAY)) runCatching { LocalDate.ofEpochDay(intent.getLongExtra(EXTRA_PRAYER_DAY, 0L)) }.getOrNull() else null
}

package com.tunisianprayertimes.adhkar

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.tunisianprayertimes.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** [periods] are the indices of the periods the message concerns; empty when it concerns them all. */
data class DhikrPeriodProblem(val message: String, val periods: Set<Int>)

object DhikrReminderScheduler {
    // v2: raised importance so the reminder shows as a heads-up banner; v1 was
    // silent+default and OEMs hid its banners. Sound stays off, vibration on.
    const val CHANNEL_ID = "adhkar_reminders_v2"
    const val QUIET_CHANNEL_ID = "adhkar_reminders_quiet_v1"
    private const val LEGACY_CHANNEL_ID = "adhkar_reminders_v1"
    private const val TAG = "DhikrReminder"
    const val EXTRA_REMINDER_ID = "com.tunisianprayertimes.extra.DHIKR_REMINDER_ID"
    const val EXTRA_OCCURRENCE_ID = "com.tunisianprayertimes.extra.DHIKR_OCCURRENCE_ID"
    internal const val ACTION_REMIND = "com.tunisianprayertimes.action.DHIKR_REMIND"
    internal const val ACTION_SNOOZE = "com.tunisianprayertimes.action.DHIKR_SNOOZE"
    internal const val ACTION_DONE = "com.tunisianprayertimes.action.DHIKR_DONE"
    internal const val ACTION_COUNT = "com.tunisianprayertimes.action.DHIKR_COUNT"
    /** Left-to-right marks keep the plus on the left of the number inside the right-to-left notification. */
    internal const val COUNT_ACTION_TITLE = "\u200E+1\u200E"
    internal const val WORK_NAME = "adhkar_schedule_repair"
    internal val schedulingLock = Any()
    private const val PREFS = "adhkar_schedule_v2"
    /** How long an unknown-length silence may postpone a nudge before re-checking. */
    private const val SILENCE_RETRY_MILLIS = 15 * 60_000L
    private const val BLOCKED_NOTIFICATION_RETRY_MILLIS = 15 * 60_000L
    /** Why a nudge was put off by a minute or two without giving up its slot. */
    private val POSTPONED_REASONS = setOf("spacing", "retry")
    private val months = linkedMapOf<String, List<DayPrayerTimes>>()
    @Volatile private var scheduleScan: Pair<List<Any>, DhikrPeriodProblem?>? = null
    private val laterDate = DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ar-TN-u-nu-latn"))
    private const val OVERNIGHT_HINT = " إذا كانت الفترة ليلية، فعّل «تنتهي في اليوم التالي»."

    fun ensureChannel(context: Context) {
        val notificationManager = manager(context)
        notificationManager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        notificationManager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "تذكيرات الأذكار", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "تذكيرات الأذكار في الأوقات التي تختارها"
            setSound(null, null)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 250, 120, 250)
            setShowBadge(false)
        })
        notificationManager.createNotificationChannel(NotificationChannel(QUIET_CHANNEL_ID, "تذكيرات الأذكار دون اهتزاز", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "تذكيرات الأذكار دون صوت أو اهتزاز"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        })
    }
    fun channelId(vibrate: Boolean): String = if (vibrate) CHANNEL_ID else QUIET_CHANNEL_ID
    fun notificationsEnabled(context: Context, vibrate: Boolean = true): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            manager(context).getNotificationChannel(channelId(vibrate))?.importance?.let {
                it != NotificationManager.IMPORTANCE_NONE
            } == true

    fun exactAlarmsEnabled(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun isKnownDhikr(context: Context, id: String): Boolean =
        DhikrCatalog.find(id) != null || DhikrRepository(context).state.value.customEntries.any { it.id == id }
    private fun hasReminderContent(state: DhikrState, rule: DhikrReminder): Boolean =
        rule.collection?.let { state.collectionEntries(it).isNotEmpty() } ?: true
    /**
     * Whether the occasion this window of a collection reminder reads is already complete. The
     * occasion is the one that begins inside the window, else the one under way at its start: a
     * period that opens shortly before its prayer belongs to that day's reading, not yesterday's.
     */
    internal fun isCollectionReadingDone(context: Context, state: DhikrState, rule: DhikrReminder, window: DhikrWindow): Boolean =
        rule.collection?.let { category ->
            state.isCollectionPeriodComplete(category, collectionWindowPeriodKey(context, category, window))
        } == true
    internal fun collectionWindowPeriodKey(context: Context, category: DhikrCategory, window: DhikrWindow): String {
        val kind = collectionOccasionStart(category)
        val firstDay = Instant.ofEpochMilli(window.startMillis).atZone(ZoneId.systemDefault()).toLocalDate()
        val occasionStart = if (kind == null) null else (0L..1L).firstNotNullOfOrNull { offset ->
            resolveTime(context, DhikrTime(kind), firstDay.plusDays(offset))
                ?.takeIf { it in window.startMillis until window.endMillis }
        }
        return collectionReadingPeriodKey(context, category, null, occasionStart ?: window.startMillis)
    }
    /** The times themselves: what a schedule needs whatever the dhikr and the goal. */
    private fun timesError(rule: DhikrReminder): String? = when {
        rule.daysOfWeek.isEmpty() || rule.daysOfWeek.any { it !in 1..7 } -> "اختر يومًا واحدًا على الأقل."
        rule.intervals().any { interval -> listOf(interval.start, interval.end).any {
            it.minuteOfDay !in 0..1439 || it.offsetMinutes !in -MAX_DHIKR_OFFSET_MINUTES..MAX_DHIKR_OFFSET_MINUTES
        } } -> "راجع أوقات البداية والنهاية، والتعديلات بالدقائق."
        else -> null
    }
    internal fun structuralError(context: Context, rule: DhikrReminder): String? = when {
        // A collection rule's dhikrId is only a representative entry; the collection identifies it.
        rule.id.isBlank() || '|' in rule.id || (rule.collection == null && !isKnownDhikr(context, rule.dhikrId)) -> "اختر ذكرًا."
        rule.targetCount !in 1..100_000 -> "أدخل هدفًا بين 1 و100,000."
        rule.collection == null && rule.dhikrId == DhikrCatalog.SALAH_HUNDRED_ID && rule.targetCount != 100 ->
            "هذا الذكر مائة جزء: 33 تسبيحًا و33 تحميدًا و33 تكبيرًا وتهليل مرة واحدة."
        rule.daysOfWeek.isEmpty() || rule.daysOfWeek.any { it !in 1..7 } -> "اختر يومًا واحدًا على الأقل."
        rule.intervalMinutes !in MIN_DHIKR_INTERVAL_MINUTES..1440 && rule.cadence == DhikrCadence.CUSTOM -> "اختر فاصلًا بين 15 دقيقة و24 ساعة."
        else -> timesError(rule)
    }
    fun validate(context: Context, rule: DhikrReminder, today: LocalDate = LocalDate.now()): String? {
        structuralError(context, rule)?.let { return it }
        scheduleProblem(context, rule, today)?.let { return it.message }
        val periods = rule.intervals().map(DhikrInterval::scheduleIdentity).toSet()
        val duplicate = DhikrRepository(context).state.value.reminders.any {
            // A collection rule's dhikrId is only a representative entry; the collection identifies it.
            it.id != rule.id && it.collection == rule.collection &&
                (rule.collection != null || it.dhikrId == rule.dhikrId) && it.daysOfWeek == rule.daysOfWeek &&
                it.intervals().map(DhikrInterval::scheduleIdentity).toSet() == periods
        }
        return if (duplicate) "يوجد تذكير " + (if (rule.collection != null) "لهذه المجموعة" else "لهذا الذكر") +
            " في الأيام والأوقات نفسها. يمكنك تعديله من «تذكيراتي»." else null
    }
    /**
     * Prayer times drift through the year, so periods tied to them are checked on every selected
     * day of the coming year; clock-only periods repeat unchanged and need only the coming days.
     */
    private fun scheduleProblem(context: Context, rule: DhikrReminder, today: LocalDate): DhikrPeriodProblem? {
        // The editor validates on every change; only a changed schedule is scanned again.
        val key = listOf(PrefsManager.getDelegationId(context), ZoneId.systemDefault(), today, rule.daysOfWeek, rule.intervals())
        scheduleScan?.takeIf { it.first == key }?.let { return it.second }
        val clockOnly = rule.intervals().all { it.start.kind == DhikrTimeKind.FIXED && it.end.kind == DhikrTimeKind.FIXED }
        val several = rule.intervals().size > 1
        var available = false
        // Weekdays whose periods already worked on an earlier date of the scan.
        val workingDays = mutableSetOf<Int>()
        var error: DhikrPeriodProblem? = null
        for (offset in 0L..(if (clockOnly) 8L else 365L)) {
            val date = today.plusDays(offset)
            if (date.dayOfWeek.value !in rule.daysOfWeek) continue
            val bounds = resolvedBounds(context, rule, date) ?: continue
            val problem = windowProblem(context, rule, date, bounds)
            if (problem != null) {
                // Prayer times drift, so times that work today can stop working later in the year: say
                // from when. Only a period running into the next selected day's first one depends on the
                // days chosen: on a weekday that never worked, that is their conflict, not the season's.
                val weekdayBound = problem.issue == WindowIssue.RUNS_INTO_NEXT_DAY && problem.other != null
                val seasonal = !clockOnly && available && (!weekdayBound || date.dayOfWeek.value in workingDays)
                error = DhikrPeriodProblem(problem.message(several, if (seasonal) date.format(laterDate) else null),
                    setOfNotNull(problem.period, problem.other))
                break
            }
            available = true
            workingDays += date.dayOfWeek.value
        }
        if (error == null && !available)
            error = DhikrPeriodProblem("مواقيت الصلاة المطلوبة غير متاحة لموقعك. اختر أوقاتًا ثابتة أو غيّر موقعك.", emptySet())
        scheduleScan = key to error
        return error
    }
    /**
     * What is wrong with the periods alone, whatever the dhikr and the goal: the period dialog shows
     * this. With several periods the message names the one concerned («الفترة 2: …»).
     */
    fun periodProblem(context: Context, rule: DhikrReminder): DhikrPeriodProblem? =
        if (timesError(rule) != null) null else scheduleProblem(context, rule, LocalDate.now())
    fun periodError(context: Context, rule: DhikrReminder): String? = periodProblem(context, rule)?.message
    /** False when the prayer times a rule depends on cannot be resolved in the coming days. */
    fun prayerTimesAvailable(context: Context, rule: DhikrReminder): Boolean =
        (0L..8L).any { resolvedBounds(context, rule, LocalDate.now().plusDays(it)) != null }
    /**
     * [scheduleOnly] resolves the periods of a rule that is not complete yet (no dhikr, no valid
     * goal), so the editor can show their clock times before the rest of the form is filled in.
     */
    fun resolveWindows(context: Context, rule: DhikrReminder, date: LocalDate, scheduleOnly: Boolean = false): List<DhikrWindow> {
        if (date.dayOfWeek.value !in rule.daysOfWeek ||
            (if (scheduleOnly) timesError(rule) else structuralError(context, rule)) != null) return emptyList()
        val bounds = resolvedBounds(context, rule, date) ?: return emptyList()
        if (windowProblem(context, rule, date, bounds) != null) return emptyList()
        val eligible = bounds.filter { it.second.first >= rule.notBeforeMillis }
        if (eligible.isEmpty()) return emptyList()
        val progressStart = eligible.minOf { it.second.first }
        val progressEnd = eligible.maxOf { it.second.second }
        val key = rule.id + "|" + rule.revision + "|" + date
        return eligible.sortedBy { it.second.first }.map { (index, interval) ->
            DhikrWindow(interval.first, interval.second, key, date, index, progressStart, progressEnd)
        }
    }
    fun resolveWindow(context: Context, rule: DhikrReminder, date: LocalDate): DhikrWindow? =
        resolveWindows(context, rule, date).firstOrNull()
    private enum class WindowIssue { END_BEFORE_START, TOO_LONG, TOO_LONG_NEXT_DAY_TICKED, OVERLAP, RUNS_INTO_NEXT_DAY }
    /** [period] is the period at fault and [other] the one it collides with, as indices into the rule's periods. */
    private class WindowProblem(val issue: WindowIssue, val period: Int, val other: Int? = null) {
        /**
         * With [several] periods the message names the one concerned. [since] is the date from which
         * times that work today stop working; the overnight hint would not help there, so it is left out.
         */
        fun message(several: Boolean, since: String?): String {
            val name = "الفترة " + (period + 1)
            if (since != null) return (if (several && issue != WindowIssue.OVERLAP) "$name: " else "") + "ابتداءً من $since " + when (issue) {
                WindowIssue.END_BEFORE_START -> "يصبح وقت النهاية قبل وقت البداية"
                WindowIssue.TOO_LONG, WindowIssue.TOO_LONG_NEXT_DAY_TICKED -> "تتجاوز الفترة يومًا واحدًا"
                WindowIssue.OVERLAP -> "تتداخل الفترتان " + (period + 1) + " و" + ((other ?: period) + 1)
                WindowIssue.RUNS_INTO_NEXT_DAY -> "تنتهي الفترة بعد بدء فترة اليوم التالي"
            } + "، لأن مواقيت الصلاة تتغيّر على مدار السنة. اختر أوقاتًا تصلح طوال السنة."
            return when (issue) {
                WindowIssue.END_BEFORE_START -> (if (several) "$name: " else "") + "يجب أن يكون وقت النهاية بعد وقت البداية.$OVERNIGHT_HINT"
                WindowIssue.TOO_LONG -> (if (several) "$name: " else "") + "اختر فترة لا تتجاوز يومًا واحدًا."
                WindowIssue.TOO_LONG_NEXT_DAY_TICKED -> (if (several) "$name: " else "") +
                    "تتجاوز هذه الفترة يومًا واحدًا. نهايتها تأتي بعد بدايتها من غير «تنتهي في اليوم التالي»، فأزل هذه العلامة."
                WindowIssue.OVERLAP -> "الفترتان " + (period + 1) + " و" + ((other ?: period) + 1) +
                    " متداخلتان. غيّر وقت بداية إحداهما أو نهايتها."
                WindowIssue.RUNS_INTO_NEXT_DAY -> when {
                    !several -> "تنتهي هذه الفترة بعد بدء فترة اليوم التالي. اختر وقت نهاية أبكر."
                    other == null || other == period -> "$name: تنتهي بعد بدء موعدها في اليوم التالي. اختر وقت نهاية أبكر."
                    else -> "$name: تنتهي بعد بدء الفترة " + (other + 1) + " في اليوم التالي. اختر وقت نهاية أبكر."
                }
            }
        }
    }
    private fun windowProblem(context: Context, rule: DhikrReminder, date: LocalDate,
                              bounds: List<Pair<Int, Pair<Long, Long>>>): WindowProblem? {
        val sorted = bounds.sortedBy { it.second.first }
        val nextDate = date.plusDays(1)
        val nextBounds = resolvedBounds(context, rule, nextDate)
        for ((index, interval) in sorted) {
            val (start, end) = interval
            if (end <= start) return WindowProblem(WindowIssue.END_BEFORE_START, index)
            val nextDayLimit = Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault()).plusDays(1).toInstant().toEpochMilli()
            // From a prayer to the same prayer next day runs a minute or two over 24 hours when the
            // prayer is moving later; it still ends by the time its next occurrence begins.
            val ownNextStart = nextBounds?.firstOrNull { it.first == index }?.second?.first
            if (end > nextDayLimit && (ownNextStart == null || end > ownNextStart)) {
                val period = rule.intervals()[index]
                val sameDayEnd = if (period.endNextDay == true) resolveTime(context, period.end, date) else null
                return WindowProblem(if (sameDayEnd != null && sameDayEnd > start) WindowIssue.TOO_LONG_NEXT_DAY_TICKED
                    else WindowIssue.TOO_LONG, index)
            }
        }
        sorted.zipWithNext().firstOrNull { (first, second) -> first.second.second > second.second.first }
            ?.let { (first, second) -> return WindowProblem(WindowIssue.OVERLAP, minOf(first.first, second.first), maxOf(first.first, second.first)) }
        // Even on an unselected weekday, an overnight interval cannot run into
        // the time its own next occurrence would begin.
        if (nextBounds != null) bounds.firstOrNull { (index, interval) ->
            nextBounds.any { it.first == index && interval.second > it.second.first }
        }?.let { return WindowProblem(WindowIssue.RUNS_INTO_NEXT_DAY, it.first) }
        val nextFirst = if (nextDate.dayOfWeek.value in rule.daysOfWeek) nextBounds?.minByOrNull { it.second.first } else null
        if (nextFirst != null) sorted.firstOrNull { it.second.second > nextFirst.second.first }
            ?.let { return WindowProblem(WindowIssue.RUNS_INTO_NEXT_DAY, it.first, nextFirst.first) }
        return null
    }
    private fun resolvedBounds(context: Context, rule: DhikrReminder, date: LocalDate): List<Pair<Int, Pair<Long, Long>>>? {
        return rule.intervals().mapIndexed { index, interval ->
            val start = resolveTime(context, interval.start, date) ?: return null
            var end = resolveTime(context, interval.end,
                if (interval.endNextDay == true) date.plusDays(1) else date) ?: return null
            if (interval.endNextDay == null && end <= start)
                end = resolveTime(context, interval.end, date.plusDays(1)) ?: return null
            index to (start to end)
        }
    }
    private fun nudgeSlots(context: Context, rule: DhikrReminder, window: DhikrWindow): List<IndexedValue<Long>> =
        dhikrNudgeTimes(rule, resolveWindows(context, rule, window.date)).withIndex()
            .filter { it.value in window.startMillis until window.endMillis }
    fun currentOrNextWindow(context: Context, rule: DhikrReminder, nowMillis: Long = System.currentTimeMillis(),
                            scheduleOnly: Boolean = false): DhikrWindow? =
        windows(context, rule, nowMillis, scheduleOnly).firstOrNull()
    /**
     * Next actual nudge instant for the UI: honors remaining nudge slots, a pending snooze,
     * and skips occurrences that are already completed or skipped. Null when nothing remains.
     * [ignoreNotificationAccess] previews it for a rule about to be saved, before access is asked for.
     */
    fun nextNudge(context: Context, rule: DhikrReminder, now: Long = System.currentTimeMillis(),
                  ignoreNotificationAccess: Boolean = false): Long? {
        if (!rule.enabled || structuralError(context, rule) != null) return null
        val state = DhikrRepository(context).state.value
        if (!hasReminderContent(state, rule) ||
            (!ignoreNotificationAccess && !notificationsEnabled(context, rule.vibrate))) return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val scheduled = prefs.getString("event:" + rule.id, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        for (window in windows(context, rule, now)) {
            val occurrence = state.occurrences[window.progressKey]
            if (occurrence != null && (occurrence.status != DhikrOccurrenceStatus.OPEN ||
                    occurrence.count >= occurrence.target)) continue
            if (isCollectionReadingDone(context, state, rule, window)) continue
            if (scheduled?.optString("occurrence") == window.progressKey &&
                scheduled.optString("signature") == window.startMillis.toString() + ":" + window.endMillis + ":" + rule.toJson() &&
                !prefs.contains("done:" + scheduled.optString("eventId")) && scheduled.optLong("at") < window.endMillis) {
                val coveredBySilence = scheduled.optString("deferredFor") == "silence" &&
                    SilenceStatus.appSilenceEndsAt(context, now)?.let { it >= window.endMillis } == true
                if (!coveredBySilence) return maxOf(now, scheduled.getLong("at"))
                continue
            }
            if (occurrence != null && occurrence.snoozedUntilMillis > now &&
                occurrence.snoozedUntilMillis in window.startMillis until window.endMillis)
                return occurrence.snoozedUntilMillis
            val slots = nudgeSlots(context, rule, window)
            val slot = slots.firstOrNull { it.value >= now && !prefs.contains("done:" + window.progressKey + ":" + it.index) }
                ?: if (now in window.startMillis until window.endMillis)
                    slots.lastOrNull { it.value >= rule.createdAtMillis && !prefs.contains("done:" + window.progressKey + ":" + it.index) }
                else null
            if (slot != null) return maxOf(now, slot.value)
        }
        return null
    }
    /**
     * The rule as saving it at [now] stores it; the editor previews the next period and nudge with the
     * same form. An edit stops the period of the stored rule that is running and leaves the day's
     * later periods to the new revision. A rule that is switched off has nothing running, and a day
     * already completed, marked done or skipped stays closed: the edit starts after it.
     */
    internal fun storedForm(context: Context, state: DhikrState, rule: DhikrReminder, now: Long): DhikrReminder {
        val running = state.reminders.find { it.id == rule.id && it.enabled }
            ?.let { stored -> windows(context, stored, now).firstOrNull { it.startMillis <= now } }
        val closedUntil = state.occurrences.values.filter {
            it.ruleId == rule.id && now in it.startMillis until it.endMillis &&
                it.status != DhikrOccurrenceStatus.OPEN && it.status != DhikrOccurrenceStatus.REPLACED
        }.maxOfOrNull { it.endMillis }
        return state.storedForm(rule, now, maxOf(running?.endMillis ?: now, closedUntil ?: now))
    }
    private fun windows(context: Context, rule: DhikrReminder, now: Long, scheduleOnly: Boolean = false): List<DhikrWindow> {
        val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        return (-2L..8L).flatMap { resolveWindows(context, rule, today.plusDays(it), scheduleOnly) }
            .filter { it.endMillis > now }.sortedBy { it.startMillis }
    }
    fun resolveTime(context: Context, time: DhikrTime, date: LocalDate): Long? {
        val minute = if (time.kind == DhikrTimeKind.FIXED) time.minuteOfDay else {
            val id = PrefsManager.getDelegationId(context)
            // Prayer times are computed for PrayerTimesRepository.SUPPORTED_YEARS.
            // Following the app's existing next-day prayer fallback, reuse the last
            // supported year beyond it. February 29 uses February 28 in a non-leap year.
            val lastYear = PrayerTimesRepository.SUPPORTED_YEARS.last
            val candidateYears = listOf(minOf(date.year, lastYear))
            val day = candidateYears.firstNotNullOfOrNull { year ->
                val key = "$id|$year|" + date.monthValue
                val month = synchronized(months) {
                    months.getOrPut(key) { PrayerTimesRepository.loadPrayerTimes(context, id, year, date.monthValue) }
                        .also { if (months.size > 24) months.remove(months.keys.first()) }
                }
                month.find { it.day == date.dayOfMonth }
                    ?: if (date.monthValue == 2 && date.dayOfMonth == 29 && year != date.year)
                        month.find { it.day == 28 }
                    else null
            } ?: return null
            val pairs = listOf(day.fajr.hour to day.fajr.minute, day.shurukHour to day.shurukMinute,
                day.dhuhr.hour to day.dhuhr.minute, day.asr.hour to day.asr.minute, day.maghrib.hour to day.maghrib.minute, day.isha.hour to day.isha.minute)
            if (pairs.any { it.first !in 0..23 || it.second !in 0..59 }) return null
            val values = pairs.map { it.first * 60 + it.second }
            if (values.zipWithNext().any { it.first >= it.second }) return null
            values[time.kind.ordinal - 1]
        }
        return date.atStartOfDay().plusMinutes((minute + time.offsetMinutes).toLong()).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
    fun invalidatePrayerCache() = synchronized(months) { months.clear(); scheduleScan = null }

    /** One inexact pending event per rule. A stable event token survives cold-process delivery. */
    fun refresh(context: Context, rearm: Boolean = false, nowMillis: Long = System.currentTimeMillis()): Unit = synchronized(schedulingLock) {
        val app = context.applicationContext
        ensureChannel(app)
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val repo = DhikrRepository(app)
        val rules = repo.state.value.reminders
        // Small intervals create many consumed-nudge records; keep only the recent week.
        val doneCutoff = nowMillis - 7L * 24 * 60 * 60_000
        val staleDone = prefs.all.entries.filter { (key, value) ->
            key.startsWith("done:") && value is String &&
                runCatching { JSONObject(value).optLong("actual") }.getOrDefault(0) in 1 until doneCutoff
        }.map { it.key }
        if (staleDone.isNotEmpty()) prefs.edit().apply { staleDone.forEach { remove(it) } }.commit()
        // Upgrade: invalidate pre-redesign counter actions and pending alarms.
        if (!prefs.getBoolean("legacy_cancelled", false)) {
            val legacy = app.getSharedPreferences("adhkar_schedule", Context.MODE_PRIVATE)
            legacy.getStringSet("scheduled_ids", emptySet()).orEmpty().forEach { id ->
                val intent = Intent(app, DhikrReminderReceiver::class.java).setAction(ACTION_REMIND)
                    .setData(Uri.parse("tunisianprayertimes://adhkar/remind/" + Uri.encode(id)))
                PendingIntent.getBroadcast(app, 0, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let {
                    app.getSystemService(AlarmManager::class.java).cancel(it); it.cancel()
                }
            }
            manager(app).activeNotifications.filter { it.notification.channelId == CHANNEL_ID }.forEach { manager(app).cancel(it.tag, it.id) }
            prefs.edit().putBoolean("legacy_cancelled", true).commit()
        }
        val previousIds = prefs.getStringSet("scheduled_ids", emptySet()).orEmpty()
        val scheduled = mutableSetOf<String>()
        // Current/next windows and posted notifications; history pruning must keep these.
        val liveOccurrenceIds = mutableSetOf<String>()
        // Resolved once per refresh; null while no app-managed silence is active.
        val silenceActive = SilenceStatus.isAppControlledSilenceActive(app)
        val activeSilenceEnd = SilenceStatus.appSilenceEndsAt(app, nowMillis)
        rules.filter { it.enabled && hasReminderContent(repo.state.value, it) }.forEach { rule ->
            for (window in windows(app, rule, nowMillis)) {
                val occurrence = repo.ensureOccurrence(rule, window)
                liveOccurrenceIds.add(occurrence.id)
                if (occurrence.status != DhikrOccurrenceStatus.OPEN || occurrence.count >= occurrence.target) continue
                if (isCollectionReadingDone(app, repo.state.value, rule, window)) continue
                val slots = nudgeSlots(app, rule, window)
                val snooze = occurrence.snoozedUntilMillis
                val previous = prefs.getString("event:" + rule.id, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
                val signature = window.startMillis.toString() + ":" + window.endMillis + ":" + rule.toJson()
                val snoozeId = occurrence.id + ":snooze:" + snooze
                val unconsumedSnooze = snooze in window.startMillis until window.endMillis &&
                    !prefs.contains("done:" + snoozeId)
                val slot = slots.firstOrNull { it.value >= nowMillis && !prefs.contains("done:" + occurrence.id + ":" + it.index) }
                    // If the last nudge was missed but its window is still open,
                    // recover just that nudge. Never replay a series of missed alerts,
                    // nor one that predates the rule itself.
                    ?: if (nowMillis in window.startMillis until window.endMillis)
                        slots.lastOrNull { it.value >= rule.createdAtMillis && !prefs.contains("done:" + occurrence.id + ":" + it.index) }
                    else null
                val intended = if (unconsumedSnooze) maxOf(snooze, nowMillis) else slot?.let { maxOf(it.value, nowMillis) }
                val eventId = if (unconsumedSnooze) snoozeId else slot?.let { occurrence.id + ":" + it.index }
                val wasDeferredForSilence = previous?.optString("deferredFor") == "silence"
                val wasDeferredForBlockedNotification = previous?.optString("deferredFor") == "notification_blocked"
                // Held back two minutes behind another reminder's alert, or retried after a failed post:
                // still the nudge of its own slot, though the next slot is now the first one ahead.
                val wasPostponed = previous?.optString("deferredFor") in POSTPONED_REASONS
                val retained = previous?.takeIf {
                    it.optString("occurrence") == occurrence.id && it.optString("signature") == signature &&
                    !prefs.contains("done:" + it.optString("eventId")) &&
                    (!unconsumedSnooze || it.optString("eventId") == snoozeId) && it.optLong("at") < window.endMillis
                        && (it.optString("eventId") == eventId || it.optLong("at") < nowMillis ||
                            wasDeferredForSilence || wasDeferredForBlockedNotification || wasPostponed)
                }
                // A nudge that would land inside app-managed silence (prayer, manual, or
                // wake) is postponed to the end of the silence instead of being swallowed by DND.
                val silencedUntil = if (retained == null && intended != null) activeSilenceEnd else null
                val deliveryAt = when {
                    intended == null -> null
                    silencedUntil != null && silencedUntil >= window.endMillis -> window.endMillis - 1
                    silencedUntil != null && silencedUntil > intended -> silencedUntil
                    else -> intended
                }
                val deferredForSilence = silencedUntil != null && deliveryAt != null && deliveryAt > intended!!
                if (retained == null && (deliveryAt == null || eventId == null)) continue
                val releaseDeferred = retained != null && !silenceActive &&
                    (wasDeferredForSilence || (wasDeferredForBlockedNotification && notificationsEnabled(app, rule.vibrate)))
                val event = if (releaseDeferred) {
                    // A silence can end, or notification access can return, before
                    // the retry. Release the same unconsumed nudge immediately.
                    JSONObject(requireNotNull(retained).toString()).put("at", maxOf(nowMillis, window.startMillis)).apply { remove("deferredFor") }
                } else retained ?: JSONObject().put("rule", rule.id).put("occurrence", occurrence.id).put("date", window.date.toString())
                    .put("windowStart", window.startMillis).put("signature", signature).put("eventId", eventId)
                    .put("at", requireNotNull(deliveryAt)).apply {
                        if (deferredForSilence) put("deferredFor", "silence")
                    }
                // Give each instant a distinct PendingIntent. A rule-wide PendingIntent could
                // update the extras of an already queued broadcast with the next nudge's time.
                val eventChanged = previous != null && previous.toString() != event.toString()
                if (eventChanged) cancelDelivery(app, rule.id, previous!!)
                val legacyCancelled = cancelLegacyDelivery(app, rule.id)
                if (retained == null || eventChanged || rearm || legacyCancelled ||
                    event.getLong("at") < nowMillis || !previousIds.contains(rule.id)) {
                    scheduleDelivery(app, rule.id, event)
                }
                prefs.edit().putString("event:" + rule.id, event.toString()).commit()
                scheduled.add(rule.id)
                break
            }
        }
        (previousIds - scheduled).forEach { id ->
            prefs.getString("event:" + id, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?.let { cancelDelivery(app, id, it) }
            cancelLegacyDelivery(app, id)
            prefs.edit().remove("event:" + id).commit()
        }
        prefs.edit().putStringSet("scheduled_ids", scheduled).commit()
        manager(app).activeNotifications.filter { it.notification.channelId == CHANNEL_ID || it.notification.channelId == QUIET_CHANNEL_ID }.forEach { notification ->
            val occurrence = repo.state.value.occurrences[notification.tag]
            val rule = rules.firstOrNull { it.id == occurrence?.ruleId }
            val activeInterval = rule?.let { currentRule ->
                runCatching { resolveWindows(app, currentRule, LocalDate.parse(occurrence!!.date)) }
                    .getOrDefault(emptyList()).firstOrNull { nowMillis in it.startMillis until it.endMillis }
            }
            if (occurrence == null || occurrence.status != DhikrOccurrenceStatus.OPEN ||
                activeInterval == null ||
                rules.none { it.id == occurrence.ruleId && it.enabled && it.revision == occurrence.revision } ||
                !hasReminderContent(repo.state.value, rule) ||
                rule?.let { isCollectionReadingDone(app, repo.state.value, it, activeInterval) } == true ||
                occurrence.snoozedUntilMillis > nowMillis || DhikrReadingPresence.occurrenceId == occurrence.id ||
                !notificationsEnabled(app, rule?.vibrate ?: true)) manager(app).cancel(notification.tag, notification.id)
            else liveOccurrenceIds.add(occurrence.id)
        }
        // Count taps refresh without rearm, so they never pay for this scan and rewrite.
        if (rearm) repo.pruneHistory(nowMillis, liveOccurrenceIds + listOfNotNull(DhikrReadingPresence.occurrenceId))
        runCatching {
            if (rules.none { it.enabled && hasReminderContent(repo.state.value, it) })
                WorkManager.getInstance(app).cancelUniqueWork(WORK_NAME)
            else WorkManager.getInstance(app).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<DhikrReminderRepairWorker>(6, TimeUnit.HOURS).build())
        }
        Unit
    }
    internal fun receive(context: Context, intent: Intent, now: Long = System.currentTimeMillis()): Unit = synchronized(schedulingLock) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val repo = DhikrRepository(context)
        if (intent.action == ACTION_DONE) {
            val id = intent.getStringExtra("occurrence") ?: return@synchronized
            repo.markDone(id, now)
            return@synchronized
        }
        if (intent.action == ACTION_COUNT) {
            val id = intent.getStringExtra("occurrence") ?: return@synchronized
            countFromNotification(context, repo, id, now)
            return@synchronized
        }
        // New notifications no longer offer snooze (a swipe does the same job); this still serves
        // the button on notifications posted by an older build.
        if (intent.action == ACTION_SNOOZE) {
            val id = intent.getStringExtra("occurrence") ?: return@synchronized
            val occurrence = repo.state.value.occurrences[id] ?: return@synchronized
            if (repo.state.value.reminders.none { it.id == occurrence.ruleId && it.enabled && it.revision == occurrence.revision }) return@synchronized
            // Too close to the end of the period to come back: the tap still puts the notification away.
            if (!repo.snooze(id, now)) manager(context).cancel(id, 1)
            return@synchronized
        }
        if (intent.action != ACTION_REMIND) return@synchronized
        val event = intent.getStringExtra("event")?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return@synchronized
        val ruleId = event.optString("rule")
        val stored = prefs.getString("event:" + ruleId, null) ?: return@synchronized
        if (stored != event.toString() || prefs.contains("done:" + event.optString("eventId"))) return@synchronized
        if (now < event.optLong("at")) {
            // An OEM or an already dispatched legacy PendingIntent may arrive before the
            // event it carries. It must not consume a nudge that has not happened yet.
            Log.w(TAG, "Early reminder delivery for rule=$ruleId; rearming")
            scheduleDelivery(context, ruleId, event)
            return@synchronized
        }
        val rule = repo.state.value.reminders.find { it.id == ruleId && it.enabled }
        val window = rule?.let { currentRule -> runCatching {
            val resolved = resolveWindows(context, currentRule, LocalDate.parse(event.getString("date")))
            if (event.has("windowStart")) resolved.firstOrNull { it.startMillis == event.optLong("windowStart") }
            else resolved.firstOrNull()
        }.getOrNull() }
        val hasContent = rule != null && hasReminderContent(repo.state.value, rule)
        val occurrence = if (hasContent && window != null) repo.ensureOccurrence(rule!!, window) else null
        ensureChannel(context)
        val eligible = hasContent && occurrence != null && window != null && occurrence.id == event.optString("occurrence") &&
            occurrence.status == DhikrOccurrenceStatus.OPEN && occurrence.count < occurrence.target &&
            !isCollectionReadingDone(context, repo.state.value, rule!!, window) &&
            now in window.startMillis until window.endMillis && now >= event.optLong("at") &&
            (occurrence.snoozedUntilMillis <= now) &&
            event.optString("signature") == window.startMillis.toString() + ":" + window.endMillis + ":" + rule!!.toJson()
        if (eligible && SilenceStatus.isAppControlledSilenceActive(context)) {
            val silenceEnd = SilenceStatus.appSilenceEndsAt(context, now)
            val retryAt = silenceEnd ?: (now + SILENCE_RETRY_MILLIS)
            if (retryAt >= window!!.endMillis && now >= window.endMillis - 1) {
                // The window really ended under silence; keep the reading available.
                cancelDelivery(context, ruleId, event)
                prefs.edit().remove("event:" + ruleId).putString("done:" + event.getString("eventId"),
                    JSONObject().put("intended", event.optLong("at")).put("actual", now).put("outcome", "silenced").toString()).commit()
                refresh(context, nowMillis = now + 1)
                return@synchronized
            }
            // Hold one nudge until the last instant of this window. An early
            // silence release can then rearm it immediately instead of losing it.
            deferEvent(context, prefs, ruleId, event, minOf(maxOf(retryAt, now + 1), window.endMillis - 1), "silence")
            return@synchronized
        }
        if (eligible && !notificationsEnabled(context, rule!!.vibrate)) {
            // A one-shot alarm has fired, so leave another alarm pending while this
            // window remains open. Refresh releases it early if access returns.
            Log.w(TAG, "Reminder notification blocked for rule=$ruleId")
            val retryAt = minOf(now + BLOCKED_NOTIFICATION_RETRY_MILLIS, window!!.endMillis - 1)
            if (retryAt > now) {
                deferEvent(context, prefs, ruleId, event, retryAt, "notification_blocked")
            } else {
                cancelDelivery(context, ruleId, event)
                prefs.edit().remove("event:" + ruleId).putString("done:" + event.getString("eventId"),
                    JSONObject().put("intended", event.optLong("at")).put("actual", now).put("outcome", "notification_blocked").toString()).commit()
                refresh(context, nowMillis = now + 1)
            }
            return@synchronized
        }
        val reading = DhikrReadingPresence.occurrenceId == occurrence?.id
        val nextUnspaced = prefs.getLong("lastAlert", 0) + 2 * 60_000L
        if (eligible && !reading && nextUnspaced > now && nextUnspaced < window!!.endMillis) {
            deferEvent(context, prefs, ruleId, event, nextUnspaced, "spacing")
            return@synchronized
        }
        val posted = eligible && !reading && postNotification(context, occurrence!!, window!!.endMillis, now)
        if (eligible && !reading && !posted && now + 60_000L < window!!.endMillis) {
            deferEvent(context, prefs, ruleId, event, now + 60_000L, "retry")
            return@synchronized
        }
        val outcome = if (!eligible) "ineligible" else if (reading) "reading" else if (posted) "shown" else "post_failed"
        Log.d(TAG, "receive rule=$ruleId event=" + event.optString("eventId") + " outcome=$outcome")
        cancelDelivery(context, ruleId, event)
        val done = prefs.edit().remove("event:" + ruleId).putString("done:" + event.getString("eventId"),
            JSONObject().put("intended", event.optLong("at")).put("actual", now).put("outcome", outcome).toString())
        if (eligible) {
            // One late delivery represents the nudge for this moment. Mark older
            // cadence slots elapsed so a repair does not replay a burst of alerts.
            dhikrNudgeTimes(rule!!, resolveWindows(context, rule, window!!.date)).forEachIndexed { index, time ->
                val key = "done:" + occurrence!!.id + ":" + index
                if (time <= now && !prefs.contains(key) && key != "done:" + event.optString("eventId"))
                    done.putString(key, JSONObject().put("intended", time).put("actual", now)
                        .put("outcome", "coalesced").toString())
            }
        }
        done.commit()
        if (posted) prefs.edit().putLong("lastAlert", now).commit()
        refresh(context, nowMillis = now + 1)
    }
    /**
     * Adds one recitation to a single-dhikr reminder straight from its notification. It goes through
     * the reader's own counting, so the Adhkar tab, the goal and the completion state stay in step.
     */
    /**
     * A tap-to-count button only suits a plain repeated phrase: a collection has no single count, a
     * stepped dhikr counts several phrases in turn, and a goal of one is finished by "تم".
     */
    internal fun supportsNotificationCounter(state: DhikrState, rule: DhikrReminder?, occurrence: DhikrOccurrence): Boolean =
        rule != null && rule.collection == null && occurrence.target >= 2 &&
            state.findDhikr(occurrence.dhikrId)?.let { it.steps.isEmpty() } == true
    private fun countFromNotification(context: Context, repo: DhikrRepository, occurrenceId: String, now: Long) {
        val occurrence = repo.state.value.occurrences[occurrenceId] ?: return
        val rule = repo.state.value.reminders.find {
            it.id == occurrence.ruleId && it.enabled && it.revision == occurrence.revision
        }
        if (!supportsNotificationCounter(repo.state.value, rule, occurrence) ||
            occurrence.status != DhikrOccurrenceStatus.OPEN || now !in occurrence.startMillis until occurrence.endMillis) {
            manager(context).cancel(occurrenceId, 1)
            return
        }
        val sessionId = runCatching {
            repo.openSession(listOf(occurrence.dhikrId), occurrenceId = occurrenceId, now = now)
        }.getOrNull() ?: return
        repo.count(sessionId, 1, now)
        // A finished goal is cancelled by the refresh inside count(); an open one shows the new total.
        repo.state.value.occurrences[occurrenceId]?.takeIf { it.status == DhikrOccurrenceStatus.OPEN }?.let {
            postNotification(context, it, it.endMillis, now, alert = false)
        }
    }
    private fun deferEvent(context: Context, prefs: SharedPreferences, ruleId: String, event: JSONObject, at: Long,
                           reason: String? = null) {
        cancelDelivery(context, ruleId, event)
        event.put("at", at)
        if (reason == null) event.remove("deferredFor") else event.put("deferredFor", reason)
        prefs.edit().putString("event:" + ruleId, event.toString()).commit()
        scheduleDelivery(context, ruleId, event)
    }
    private fun postNotification(context: Context, occurrence: DhikrOccurrence, endMillis: Long, now: Long,
                                 alert: Boolean = true): Boolean {
        val rule = DhikrRepository(context).state.value.reminders.find { it.id == occurrence.ruleId }
        val collection = rule?.collection
        // A collection is announced by its own name; only a single dhikr needs its entry.
        val title = collection?.let { if (it == DhikrCategory.SALAH) "أذكار بعد الصلاة" else "أذكار " + it.title }
            ?: DhikrRepository(context).state.value.findDhikr(occurrence.dhikrId)?.title ?: return false
        val body = if (collection != null) "حان وقت قراءة الأذكار" else "حان وقت الذكر • " + occurrence.count + " من " + occurrence.target
        val openIntent = Intent(context, MainActivity::class.java)
            .setData(Uri.parse("tunisianprayertimes://adhkar/open/" + Uri.encode(occurrence.id)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainTabNavigation.EXTRA_DESTINATION, MainTabNavigation.DESTINATION_ADHKAR)
            .putExtra(EXTRA_REMINDER_ID, occurrence.ruleId).putExtra(EXTRA_OCCURRENCE_ID, occurrence.id)
        val open = PendingIntent.getActivity(context, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(context, channelId(rule?.vibrate != false)).setSmallIcon(R.drawable.ic_tab_adhkar)
            .setContentTitle(title).setContentText(body)
            .setCategory(NotificationCompat.CATEGORY_REMINDER).setContentIntent(open).setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setTimeoutAfter((endMillis - now).coerceAtLeast(1)).setOnlyAlertOnce(!alert)
        // Android shows three actions. A countable dhikr trades "continue" (the tap on the notification
        // opens the same reader) for a counter; anything else keeps "continue".
        if (supportsNotificationCounter(DhikrRepository(context).state.value, rule, occurrence)) {
            val count = Intent(context, DhikrReminderReceiver::class.java).setAction(ACTION_COUNT)
                .setData(Uri.parse("tunisianprayertimes://adhkar/count/" + Uri.encode(occurrence.id)))
                .putExtra("occurrence", occurrence.id)
            builder.addAction(0, COUNT_ACTION_TITLE, PendingIntent.getBroadcast(context, 0, count,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        } else builder.addAction(0, "متابعة الذكر", open)
        val done = Intent(context, DhikrReminderReceiver::class.java).setAction(ACTION_DONE)
            .setData(Uri.parse("tunisianprayertimes://adhkar/done/" + Uri.encode(occurrence.id)))
            .putExtra("occurrence", occurrence.id)
        builder.addAction(0, "تم", PendingIntent.getBroadcast(context, 0, done,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        builder.setVibrate(if (rule?.vibrate == false || !alert) longArrayOf(0L) else longArrayOf(0, 250, 120, 250))
        Log.d(TAG, "posting " + occurrence.id + " title=$title")
        return runCatching { manager(context).notify(occurrence.id, 1, builder.build()) }
            .onFailure { Log.w(TAG, "Could not post reminder for " + occurrence.id, it) }.isSuccess
    }
    /** Keep an inexact copy: revoking exact-alarm access cancels all exact alarms. */
    private fun scheduleDelivery(context: Context, id: String, event: JSONObject) {
        val triggerAtMillis = event.getLong("at")
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val pending = alarmIntent(context, id, event)
        if (exactAlarmsEnabled(context)) {
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis,
                    alarmIntent(context, id, event, backup = true))
                return
            } catch (error: SecurityException) {
                Log.w(TAG, "Exact alarm access was revoked; using inexact delivery", error)
            }
        }
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
    }
    private fun alarmIntent(context: Context, id: String, event: JSONObject, backup: Boolean = false): PendingIntent {
        val intent = eventIntent(context, id, event, backup).putExtra("event", event.toString())
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    private fun eventIntent(context: Context, id: String, event: JSONObject, backup: Boolean = false): Intent =
        Intent(context, DhikrReminderReceiver::class.java).setAction(ACTION_REMIND)
            .setData(Uri.parse("tunisianprayertimes://adhkar/" + (if (backup) "v3-backup/" else "v3/") + Uri.encode(id) + "/" +
                Uri.encode(event.optString("eventId")) + "/" + event.optLong("at")))

    private fun cancelDelivery(context: Context, id: String, event: JSONObject) {
        for (backup in listOf(false, true)) {
            val pending = PendingIntent.getBroadcast(context, 0, eventIntent(context, id, event, backup),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) ?: continue
            context.getSystemService(AlarmManager::class.java).cancel(pending)
            pending.cancel()
        }
    }

    private fun cancelLegacyDelivery(context: Context, id: String): Boolean {
        val intent = Intent(context, DhikrReminderReceiver::class.java).setAction(ACTION_REMIND)
            .setData(Uri.parse("tunisianprayertimes://adhkar/v2/" + Uri.encode(id)))
        val pending = PendingIntent.getBroadcast(context, 0, intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) ?: return false
        context.getSystemService(AlarmManager::class.java).cancel(pending)
        pending.cancel()
        return true
    }
    private fun manager(context: Context): NotificationManager = context.getSystemService(NotificationManager::class.java)
}

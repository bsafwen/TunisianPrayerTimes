package com.tunisianprayertimes.adhkar

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
import java.util.concurrent.TimeUnit
import org.json.JSONObject

object DhikrReminderScheduler {
    // v2: raised importance so the reminder shows as a heads-up banner; v1 was
    // silent+default and OEMs hid its banners. Sound stays off, vibration on.
    const val CHANNEL_ID = "adhkar_reminders_v2"
    private const val LEGACY_CHANNEL_ID = "adhkar_reminders_v1"
    private const val TAG = "DhikrReminder"
    const val EXTRA_REMINDER_ID = "com.tunisianprayertimes.extra.DHIKR_REMINDER_ID"
    const val EXTRA_OCCURRENCE_ID = "com.tunisianprayertimes.extra.DHIKR_OCCURRENCE_ID"
    internal const val ACTION_REMIND = "com.tunisianprayertimes.action.DHIKR_REMIND"
    internal const val ACTION_SNOOZE = "com.tunisianprayertimes.action.DHIKR_SNOOZE"
    internal const val WORK_NAME = "adhkar_schedule_repair"
    internal val schedulingLock = Any()
    private const val PREFS = "adhkar_schedule_v2"
    /** How long an unknown-length silence may postpone a nudge before re-checking. */
    private const val SILENCE_RETRY_MILLIS = 15 * 60_000L
    private val months = linkedMapOf<String, List<DayPrayerTimes>>()

    fun ensureChannel(context: Context) {
        val notificationManager = manager(context)
        notificationManager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        notificationManager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "تذكيرات الأذكار", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "تذكيرات هادئة خلال الفترة التي تختارها"
            setSound(null, null)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 250, 120, 250)
            setShowBadge(false)
        })
    }
    fun notificationsEnabled(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            manager(context).getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE

    private fun isKnownDhikr(context: Context, id: String): Boolean =
        DhikrCatalog.find(id) != null || DhikrRepository(context).state.value.customEntries.any { it.id == id }
    internal fun structuralError(context: Context, rule: DhikrReminder): String? = when {
        rule.id.isBlank() || '|' in rule.id || !isKnownDhikr(context, rule.dhikrId) -> "اختر ذكرًا."
        rule.targetCount !in 1..100_000 -> "أدخل هدفًا من 1 إلى 100,000."
        rule.daysOfWeek.isEmpty() || rule.daysOfWeek.any { it !in 1..7 } -> "اختر يومًا واحدًا على الأقل."
        rule.intervalMinutes !in MIN_DHIKR_INTERVAL_MINUTES..1440 && rule.cadence == DhikrCadence.CUSTOM -> "اختر فاصلًا من 5 دقائق إلى 24 ساعة."
        listOf(rule.start, rule.end).any { it.minuteOfDay !in 0..1439 || it.offsetMinutes !in -720..720 } -> "تحقق من الوقت وفرق الدقائق."
        else -> null
    }
    fun validate(context: Context, rule: DhikrReminder): String? {
        structuralError(context, rule)?.let { return it }
        var available = false
        for (offset in 0L..8L) {
            val date = LocalDate.now().plusDays(offset)
            if (date.dayOfWeek.value !in rule.daysOfWeek) continue
            val bounds = bounds(context, rule, date) ?: continue
            available = true
            if (bounds.second <= bounds.first) return "النهاية تسبق البداية. اختر «اليوم التالي» للفترة الليلية."
            val limit = Instant.ofEpochMilli(bounds.first).atZone(ZoneId.systemDefault()).plusDays(1).toInstant().toEpochMilli()
            if (bounds.second > limit) return "اختر فترة لا تتجاوز يومًا واحدًا."
        }
        if (!available) return "مواقيت الصلاة المطلوبة غير متاحة لهذا الموقع. اختر وقتًا ثابتًا أو موقعًا تتوفر له المواقيت."
        val duplicate = DhikrRepository(context).state.value.reminders.any {
            it.id != rule.id && it.dhikrId == rule.dhikrId && it.daysOfWeek == rule.daysOfWeek &&
                it.start == rule.start && it.end == rule.end && it.endNextDay == rule.endNextDay
        }
        return if (duplicate) "يوجد تذكير بهذا الذكر في الفترة نفسها. يمكنك تعديله من تذكيراتي." else null
    }
    fun resolveWindow(context: Context, rule: DhikrReminder, date: LocalDate): DhikrWindow? {
        if (date.dayOfWeek.value !in rule.daysOfWeek || structuralError(context, rule) != null) return null
        val (start, end) = bounds(context, rule, date) ?: return null
        if (start < rule.notBeforeMillis || end <= start) return null
        val nextStart = resolveTime(context, rule.start, date.plusDays(1))
            ?: Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault()).plusDays(1).toInstant().toEpochMilli()
        if (end > nextStart) return null
        return DhikrWindow(start, end, rule.id + "|" + rule.revision + "|" + date, date)
    }
    private fun bounds(context: Context, rule: DhikrReminder, date: LocalDate): Pair<Long, Long>? {
        val start = resolveTime(context, rule.start, date) ?: return null
        var end = resolveTime(context, rule.end, if (rule.endNextDay == true) date.plusDays(1) else date) ?: return null
        if (rule.endNextDay == null && end <= start) end = resolveTime(context, rule.end, date.plusDays(1)) ?: return null
        return start to end
    }
    fun currentOrNextWindow(context: Context, rule: DhikrReminder, nowMillis: Long = System.currentTimeMillis()): DhikrWindow? =
        windows(context, rule, nowMillis).firstOrNull()
    /**
     * Next actual nudge instant for the UI: honors remaining nudge slots, a pending snooze,
     * and skips occurrences that are already completed or skipped. Null when nothing remains.
     */
    fun nextNudge(context: Context, rule: DhikrReminder, now: Long = System.currentTimeMillis()): Long? {
        if (!rule.enabled || structuralError(context, rule) != null) return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        for (window in windows(context, rule, now)) {
            val occurrence = DhikrRepository(context).state.value.occurrences[window.progressKey]
            if (occurrence != null && (occurrence.status == DhikrOccurrenceStatus.SKIPPED ||
                    occurrence.status == DhikrOccurrenceStatus.REPLACED || occurrence.count >= occurrence.target)) continue
            if (occurrence != null && occurrence.snoozedUntilMillis > now && occurrence.snoozedUntilMillis < window.endMillis)
                return occurrence.snoozedUntilMillis
            val times = dhikrNudgeTimes(rule, window)
            val index = times.indices.firstOrNull { times[it] >= now && !prefs.contains("done:" + window.progressKey + ":" + it) }
            if (index != null) return times[index]
        }
        return null
    }
    private fun windows(context: Context, rule: DhikrReminder, now: Long): List<DhikrWindow> {
        val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        return (-2L..8L).mapNotNull { resolveWindow(context, rule, today.plusDays(it)) }
            .filter { it.endMillis > now }.sortedBy { it.startMillis }
    }
    fun resolveTime(context: Context, time: DhikrTime, date: LocalDate): Long? {
        val minute = if (time.kind == DhikrTimeKind.FIXED) time.minuteOfDay else {
            val id = PrefsManager.getDelegationId(context)
            val key = "$id|" + date.year + "|" + date.monthValue
            val month = synchronized(months) {
                months.getOrPut(key) { PrayerTimesRepository.loadPrayerTimes(context, id, date.year, date.monthValue) }
                    .also { if (months.size > 24) months.remove(months.keys.first()) }
            }
            val day = month.find { it.day == date.dayOfMonth } ?: return null
            val pairs = listOf(day.fajr.hour to day.fajr.minute, day.shurukHour to day.shurukMinute,
                day.dhuhr.hour to day.dhuhr.minute, day.asr.hour to day.asr.minute, day.maghrib.hour to day.maghrib.minute, day.isha.hour to day.isha.minute)
            if (pairs.any { it.first !in 0..23 || it.second !in 0..59 }) return null
            val values = pairs.map { it.first * 60 + it.second }
            if (values.zipWithNext().any { it.first >= it.second }) return null
            values[time.kind.ordinal - 1]
        }
        return date.atStartOfDay().plusMinutes((minute + time.offsetMinutes).toLong()).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
    fun invalidatePrayerCache() = synchronized(months) { months.clear() }

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
        // Resolved once per refresh; null while no app-managed silence is active.
        val activeSilenceEnd = SilenceStatus.appSilenceEndsAt(app)
        rules.filter { it.enabled }.forEach { rule ->
            for (window in windows(app, rule, nowMillis)) {
                val occurrence = repo.ensureOccurrence(rule, window)
                if (occurrence.status != DhikrOccurrenceStatus.OPEN || occurrence.count >= occurrence.target) continue
                val times = dhikrNudgeTimes(rule, window)
                val snooze = occurrence.snoozedUntilMillis
                val previous = prefs.getString("event:" + rule.id, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
                val signature = window.startMillis.toString() + ":" + window.endMillis + ":" + rule.toJson()
                val snoozeId = occurrence.id + ":snooze:" + snooze
                val unconsumedSnooze = snooze > 0 && !prefs.contains("done:" + snoozeId) && snooze < window.endMillis
                val index = times.indices.firstOrNull { times[it] >= nowMillis && !prefs.contains("done:" + occurrence.id + ":" + it) }
                val intended = if (unconsumedSnooze) maxOf(snooze, nowMillis) else index?.let { times[it] }
                val eventId = if (unconsumedSnooze) snoozeId else index?.let { occurrence.id + ":" + it }
                val retained = previous?.takeIf {
                    it.optString("occurrence") == occurrence.id && it.optString("signature") == signature &&
                        !prefs.contains("done:" + it.optString("eventId")) &&
                        (!unconsumedSnooze || it.optString("eventId") == snoozeId) && it.optLong("at") < window.endMillis
                }
                // A nudge that would land inside app-managed silence (prayer, manual, or
                // wake) is postponed to the end of the silence instead of being swallowed by DND.
                val silencedUntil = if (retained == null && intended != null) activeSilenceEnd else null
                val deliveryAt = when {
                    intended == null -> null
                    silencedUntil != null && silencedUntil >= window.endMillis -> null
                    silencedUntil != null && silencedUntil > intended -> silencedUntil
                    else -> intended
                }
                if (retained == null && (deliveryAt == null || eventId == null)) continue
                val event = retained ?: JSONObject().put("rule", rule.id).put("occurrence", occurrence.id).put("date", window.date.toString())
                    .put("signature", signature).put("eventId", eventId).put("at", requireNotNull(deliveryAt))
                val pending = alarmIntent(app, rule.id, event)
                if (retained == null || rearm || !previousIds.contains(rule.id)) {
                    scheduleDelivery(app, event.getLong("at"), pending)
                }
                prefs.edit().putString("event:" + rule.id, event.toString()).commit()
                scheduled.add(rule.id)
                break
            }
        }
        (previousIds - scheduled).forEach { id ->
            app.getSystemService(AlarmManager::class.java).cancel(alarmIntent(app, id, null))
            prefs.edit().remove("event:" + id).commit()
        }
        prefs.edit().putStringSet("scheduled_ids", scheduled).commit()
        manager(app).activeNotifications.filter { it.notification.channelId == CHANNEL_ID }.forEach { notification ->
            val occurrence = repo.state.value.occurrences[notification.tag]
            if (occurrence == null || occurrence.status != DhikrOccurrenceStatus.OPEN ||
                nowMillis !in occurrence.startMillis until occurrence.endMillis ||
                rules.none { it.id == occurrence.ruleId && it.enabled && it.revision == occurrence.revision } ||
                occurrence.snoozedUntilMillis > nowMillis || DhikrReadingPresence.occurrenceId == occurrence.id ||
                !notificationsEnabled(app)) manager(app).cancel(notification.tag, notification.id)
        }
        runCatching {
            if (rules.none { it.enabled }) WorkManager.getInstance(app).cancelUniqueWork(WORK_NAME)
            else WorkManager.getInstance(app).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<DhikrReminderRepairWorker>(6, TimeUnit.HOURS).build())
        }
        Unit
    }
    internal fun receive(context: Context, intent: Intent, now: Long = System.currentTimeMillis()): Unit = synchronized(schedulingLock) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val repo = DhikrRepository(context)
        if (intent.action == ACTION_SNOOZE) {
            val id = intent.getStringExtra("occurrence") ?: return@synchronized
            val occurrence = repo.state.value.occurrences[id] ?: return@synchronized
            if (repo.state.value.reminders.none { it.id == occurrence.ruleId && it.enabled && it.revision == occurrence.revision }) return@synchronized
            repo.snooze(id, now)
            return@synchronized
        }
        if (intent.action != ACTION_REMIND) return@synchronized
        val event = intent.getStringExtra("event")?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return@synchronized
        val ruleId = event.optString("rule")
        val stored = prefs.getString("event:" + ruleId, null) ?: return@synchronized
        if (stored != event.toString() || prefs.contains("done:" + event.optString("eventId"))) return@synchronized
        val rule = repo.state.value.reminders.find { it.id == ruleId && it.enabled }
        val window = rule?.let { runCatching { resolveWindow(context, it, LocalDate.parse(event.getString("date"))) }.getOrNull() }
        val occurrence = if (rule != null && window != null) repo.ensureOccurrence(rule, window) else null
        if (rule != null && window != null && occurrence != null && occurrence.id == event.optString("occurrence") &&
            SilenceStatus.isAppControlledSilenceActive(context)) {
            val silenceEnd = SilenceStatus.appSilenceEndsAt(context)
            val retryAt = silenceEnd ?: (now + SILENCE_RETRY_MILLIS)
            if (retryAt >= window.endMillis) {
                // Silence outlasts the occurrence: drop the nudge, keep the reading available.
                prefs.edit().remove("event:" + ruleId).putString("done:" + event.getString("eventId"),
                    JSONObject().put("intended", event.optLong("at")).put("actual", now).put("outcome", "silenced").toString()).commit()
                refresh(context, nowMillis = now + 1)
                return@synchronized
            }
            val deferred = event.put("at", maxOf(retryAt, now + 1))
            prefs.edit().putString("event:" + ruleId, deferred.toString()).commit()
            scheduleDelivery(context, deferred.getLong("at"), alarmIntent(context, ruleId, deferred))
            return@synchronized
        }
        val eligible = occurrence != null && window != null && occurrence.id == event.optString("occurrence") &&
            occurrence.status == DhikrOccurrenceStatus.OPEN && occurrence.count < occurrence.target &&
            now in window.startMillis until window.endMillis && now >= event.optLong("at") &&
            (occurrence.snoozedUntilMillis <= now) && notificationsEnabled(context) &&
            event.optString("signature") == window.startMillis.toString() + ":" + window.endMillis + ":" + rule!!.toJson()
        val reading = DhikrReadingPresence.occurrenceId == occurrence?.id
        val burst = now - prefs.getLong("lastAlert", 0) < 2 * 60_000L
        val outcome = if (!eligible) "ineligible" else if (reading) "reading" else if (burst) "spaced" else "shown"
        Log.d(TAG, "receive rule=$ruleId event=" + event.optString("eventId") + " outcome=$outcome")
        prefs.edit().remove("event:" + ruleId).putString("done:" + event.getString("eventId"),
            JSONObject().put("intended", event.optLong("at")).put("actual", now).put("outcome", outcome).toString()).commit()
        if (eligible && !reading && !burst) {
            postNotification(context, occurrence, now)
            prefs.edit().putLong("lastAlert", now).commit()
        }
        refresh(context, nowMillis = now + 1)
    }
    private fun postNotification(context: Context, occurrence: DhikrOccurrence, now: Long) {
        val entry = DhikrRepository(context).state.value.findDhikr(occurrence.dhikrId) ?: return
        val rule = DhikrRepository(context).state.value.reminders.find { it.id == occurrence.ruleId }
        val collection = rule?.collection
        val title = collection?.let { if (it == DhikrCategory.SALAH) "أذكار بعد الصلاة" else "أذكار " + it.title } ?: entry.title
        val body = if (collection != null) "لحظة لقراءة المجموعة" else "لحظة للذكر • " + occurrence.count + " من " + occurrence.target
        val openIntent = Intent(context, MainActivity::class.java)
            .setData(Uri.parse("tunisianprayertimes://adhkar/open/" + Uri.encode(occurrence.id)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainTabNavigation.EXTRA_DESTINATION, MainTabNavigation.DESTINATION_ADHKAR)
            .putExtra(EXTRA_REMINDER_ID, occurrence.ruleId).putExtra(EXTRA_OCCURRENCE_ID, occurrence.id)
        val open = PendingIntent.getActivity(context, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID).setSmallIcon(R.drawable.ic_tab_adhkar)
            .setContentTitle(title).setContentText(body)
            .setCategory(NotificationCompat.CATEGORY_REMINDER).setContentIntent(open).setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setTimeoutAfter((occurrence.endMillis - now).coerceAtLeast(1)).addAction(0, "متابعة الذكر", open)
        builder.setVibrate(if (rule?.vibrate == false) longArrayOf(0L) else longArrayOf(0, 250, 120, 250))
        if (now + 30 * 60_000L < occurrence.endMillis) {
            val snooze = Intent(context, DhikrReminderReceiver::class.java).setAction(ACTION_SNOOZE)
                .setData(Uri.parse("tunisianprayertimes://adhkar/snooze/" + Uri.encode(occurrence.id)))
                .putExtra("occurrence", occurrence.id)
            builder.addAction(0, "بعد 30 دقيقة", PendingIntent.getBroadcast(context, 0, snooze, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        Log.d(TAG, "posting " + occurrence.id + " title=$title")
        runCatching { manager(context).notify(occurrence.id, 1, builder.build()) }
    }
    /**
     * Nudges must land inside the occurrence window, so exact delivery matters.
     * When the "Alarms & reminders" special access is missing, fall back to
     * [AlarmManager.setAlarmClock] like the silence and wake schedules do; it
     * stays exact without that access.
     */
    private fun scheduleDelivery(context: Context, triggerAtMillis: Long, pending: PendingIntent) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
            return
        }
        runCatching {
            val showIntent = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent), pending)
        }.onFailure { error ->
            Log.w(TAG, "Exact fallback unavailable; using while-idle batching", error)
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
        }
    }
    private fun alarmIntent(context: Context, id: String, event: JSONObject?): PendingIntent {
        val intent = Intent(context, DhikrReminderReceiver::class.java).setAction(ACTION_REMIND)
            .setData(Uri.parse("tunisianprayertimes://adhkar/v2/" + Uri.encode(id)))
        event?.let { intent.putExtra("event", it.toString()) }
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    private fun manager(context: Context): NotificationManager = context.getSystemService(NotificationManager::class.java)
}

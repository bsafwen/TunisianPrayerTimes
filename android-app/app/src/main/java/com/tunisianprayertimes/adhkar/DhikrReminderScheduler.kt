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
import java.util.concurrent.TimeUnit
import org.json.JSONObject

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
    internal const val WORK_NAME = "adhkar_schedule_repair"
    internal val schedulingLock = Any()
    private const val PREFS = "adhkar_schedule_v2"
    /** How long an unknown-length silence may postpone a nudge before re-checking. */
    private const val SILENCE_RETRY_MILLIS = 15 * 60_000L
    private const val BLOCKED_NOTIFICATION_RETRY_MILLIS = 15 * 60_000L
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
        notificationManager.createNotificationChannel(NotificationChannel(QUIET_CHANNEL_ID, "تذكيرات الأذكار بلا اهتزاز", NotificationManager.IMPORTANCE_HIGH).apply {
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
    internal fun structuralError(context: Context, rule: DhikrReminder): String? = when {
        rule.id.isBlank() || '|' in rule.id || !isKnownDhikr(context, rule.dhikrId) -> "اختر ذكرًا."
        rule.targetCount !in 1..100_000 -> "أدخل هدفًا من 1 إلى 100,000."
        rule.daysOfWeek.isEmpty() || rule.daysOfWeek.any { it !in 1..7 } -> "اختر يومًا واحدًا على الأقل."
        rule.intervalMinutes !in MIN_DHIKR_INTERVAL_MINUTES..1440 && rule.cadence == DhikrCadence.CUSTOM -> "اختر فاصلًا من 15 دقيقة إلى 24 ساعة."
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
            windowError(context, rule, date, bounds)?.let { return it }
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
        if (start < rule.notBeforeMillis || windowError(context, rule, date, start to end) != null) return null
        return DhikrWindow(start, end, rule.id + "|" + rule.revision + "|" + date, date)
    }
    private fun windowError(context: Context, rule: DhikrReminder, date: LocalDate, bounds: Pair<Long, Long>): String? {
        val (start, end) = bounds
        if (end <= start) return "النهاية تسبق البداية. اختر «اليوم التالي» للفترة الليلية."
        val nextDayLimit = Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault()).plusDays(1).toInstant().toEpochMilli()
        if (end > nextDayLimit) return "اختر فترة لا تتجاوز يومًا واحدًا."
        val nextStart = resolveTime(context, rule.start, date.plusDays(1)) ?: nextDayLimit
        if (end > nextStart) return "تنتهي الفترة بعد بداية فترة اليوم التالي. قدّم وقت النهاية."
        return null
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
        val state = DhikrRepository(context).state.value
        if (!hasReminderContent(state, rule) || !notificationsEnabled(context, rule.vibrate)) return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val scheduled = prefs.getString("event:" + rule.id, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        for (window in windows(context, rule, now)) {
            val occurrence = state.occurrences[window.progressKey]
            if (occurrence != null && (occurrence.status == DhikrOccurrenceStatus.SKIPPED ||
                    occurrence.status == DhikrOccurrenceStatus.REPLACED || occurrence.count >= occurrence.target)) continue
            if (scheduled?.optString("occurrence") == window.progressKey &&
                scheduled.optString("signature") == window.startMillis.toString() + ":" + window.endMillis + ":" + rule.toJson() &&
                !prefs.contains("done:" + scheduled.optString("eventId")) && scheduled.optLong("at") < window.endMillis) {
                val coveredBySilence = scheduled.optString("deferredFor") == "silence" &&
                    SilenceStatus.appSilenceEndsAt(context, now)?.let { it >= window.endMillis } == true
                if (!coveredBySilence) return maxOf(now, scheduled.getLong("at"))
                continue
            }
            if (occurrence != null && occurrence.snoozedUntilMillis > now && occurrence.snoozedUntilMillis < window.endMillis)
                return occurrence.snoozedUntilMillis
            val times = dhikrNudgeTimes(rule, window)
            val index = times.indices.firstOrNull { times[it] >= now && !prefs.contains("done:" + window.progressKey + ":" + it) }
                ?: if (now in window.startMillis until window.endMillis)
                    times.indices.lastOrNull { !prefs.contains("done:" + window.progressKey + ":" + it) }
                else null
            if (index != null) return maxOf(now, times[index])
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
            // Bundled prayer tables currently stop at 2026. Following the app's
            // existing next-day prayer fallback, reuse the latest available year
            // for future dates. February 29 uses February 28 in a non-leap table.
            val candidateYears = if (date.year > 2026) {
                val recent = (date.year downTo maxOf(2026, date.year - 10)).toList()
                if (2026 in recent) recent else recent + 2026
            } else listOf(date.year)
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
        val silenceActive = SilenceStatus.isAppControlledSilenceActive(app)
        val activeSilenceEnd = SilenceStatus.appSilenceEndsAt(app, nowMillis)
        rules.filter { it.enabled && hasReminderContent(repo.state.value, it) }.forEach { rule ->
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
                    // If the last nudge was missed but its window is still open,
                    // recover just that nudge. Never replay a series of missed alerts.
                    ?: if (nowMillis in window.startMillis until window.endMillis)
                        times.indices.lastOrNull { !prefs.contains("done:" + occurrence.id + ":" + it) }
                    else null
                val intended = if (unconsumedSnooze) maxOf(snooze, nowMillis) else index?.let { maxOf(times[it], nowMillis) }
                val eventId = if (unconsumedSnooze) snoozeId else index?.let { occurrence.id + ":" + it }
                val wasDeferredForSilence = previous?.optString("deferredFor") == "silence"
                val wasDeferredForBlockedNotification = previous?.optString("deferredFor") == "notification_blocked"
                val retained = previous?.takeIf {
                    it.optString("occurrence") == occurrence.id && it.optString("signature") == signature &&
                    !prefs.contains("done:" + it.optString("eventId")) &&
                    (!unconsumedSnooze || it.optString("eventId") == snoozeId) && it.optLong("at") < window.endMillis
                        && (it.optString("eventId") == eventId || it.optLong("at") < nowMillis ||
                            wasDeferredForSilence || wasDeferredForBlockedNotification)
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
                    .put("signature", signature).put("eventId", eventId).put("at", requireNotNull(deliveryAt)).apply {
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
            if (occurrence == null || occurrence.status != DhikrOccurrenceStatus.OPEN ||
                nowMillis !in occurrence.startMillis until occurrence.endMillis ||
                rules.none { it.id == occurrence.ruleId && it.enabled && it.revision == occurrence.revision } ||
                (rule != null && !hasReminderContent(repo.state.value, rule)) ||
                occurrence.snoozedUntilMillis > nowMillis || DhikrReadingPresence.occurrenceId == occurrence.id ||
                !notificationsEnabled(app, rule?.vibrate ?: true)) manager(app).cancel(notification.tag, notification.id)
        }
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
        if (now < event.optLong("at")) {
            // An OEM or an already dispatched legacy PendingIntent may arrive before the
            // event it carries. It must not consume a nudge that has not happened yet.
            Log.w(TAG, "Early reminder delivery for rule=$ruleId; rearming")
            scheduleDelivery(context, ruleId, event)
            return@synchronized
        }
        val rule = repo.state.value.reminders.find { it.id == ruleId && it.enabled }
        val window = rule?.let { runCatching { resolveWindow(context, it, LocalDate.parse(event.getString("date"))) }.getOrNull() }
        val hasContent = rule != null && hasReminderContent(repo.state.value, rule)
        val occurrence = if (hasContent && window != null) repo.ensureOccurrence(rule!!, window) else null
        ensureChannel(context)
        val eligible = hasContent && occurrence != null && window != null && occurrence.id == event.optString("occurrence") &&
            occurrence.status == DhikrOccurrenceStatus.OPEN && occurrence.count < occurrence.target &&
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
            deferEvent(context, prefs, ruleId, event, nextUnspaced)
            return@synchronized
        }
        val posted = eligible && !reading && postNotification(context, occurrence!!, now)
        if (eligible && !reading && !posted && now + 60_000L < window!!.endMillis) {
            deferEvent(context, prefs, ruleId, event, now + 60_000L)
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
            dhikrNudgeTimes(rule!!, window!!).forEachIndexed { index, time ->
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
    private fun deferEvent(context: Context, prefs: SharedPreferences, ruleId: String, event: JSONObject, at: Long,
                           reason: String? = null) {
        cancelDelivery(context, ruleId, event)
        event.put("at", at)
        if (reason == null) event.remove("deferredFor") else event.put("deferredFor", reason)
        prefs.edit().putString("event:" + ruleId, event.toString()).commit()
        scheduleDelivery(context, ruleId, event)
    }
    private fun postNotification(context: Context, occurrence: DhikrOccurrence, now: Long): Boolean {
        val entry = DhikrRepository(context).state.value.findDhikr(occurrence.dhikrId) ?: return false
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
        val builder = NotificationCompat.Builder(context, channelId(rule?.vibrate != false)).setSmallIcon(R.drawable.ic_tab_adhkar)
            .setContentTitle(title).setContentText(body)
            .setCategory(NotificationCompat.CATEGORY_REMINDER).setContentIntent(open).setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setTimeoutAfter((occurrence.endMillis - now).coerceAtLeast(1)).addAction(0, "متابعة الذكر", open)
        builder.setVibrate(if (rule?.vibrate == false) longArrayOf(0L) else longArrayOf(0, 250, 120, 250))
        if (now < occurrence.endMillis - 1) {
            val snooze = Intent(context, DhikrReminderReceiver::class.java).setAction(ACTION_SNOOZE)
                .setData(Uri.parse("tunisianprayertimes://adhkar/snooze/" + Uri.encode(occurrence.id)))
                .putExtra("occurrence", occurrence.id)
            builder.addAction(0, "تأجيل", PendingIntent.getBroadcast(context, 0, snooze, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
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

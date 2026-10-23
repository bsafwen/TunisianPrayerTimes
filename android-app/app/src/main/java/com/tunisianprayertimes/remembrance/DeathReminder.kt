package com.tunisianprayertimes.remembrance

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tunisianprayertimes.FastingDays
import com.tunisianprayertimes.MainActivity
import com.tunisianprayertimes.MainTabNavigation
import com.tunisianprayertimes.PrayerTimesRepository
import com.tunisianprayertimes.R
import com.tunisianprayertimes.adhkar.DeathRemembranceTexts
import com.tunisianprayertimes.adhkar.DhikrReminderScheduler
import com.tunisianprayertimes.engine.TimeEvent
import com.tunisianprayertimes.place.PrayerPlace
import com.tunisianprayertimes.fasting.FastingReminder
import com.tunisianprayertimes.quran.QuranReading
import com.tunisianprayertimes.quran.hifz.hifzOutsidePrayers
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Random
import java.util.SplittableRandom
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * «ذكر الموت»: «أكثروا ذكر هادم اللذات» (Tirmidhi 2307), with hope, worship, repentance and people's rights. The
 * card in «عباداتي» gives one text a day; this reminder, off by default, brings it now and then at a moment of the
 * day nobody expects, as death comes, but only in daylight and outside prayer times, without a sound. The Prophet ﷺ
 * spaced his exhortations so as not to weary (Bukhari 68): twice a week unless the reader asks for more or less.
 */
object DeathReminder {
    const val CHANNEL_ID = "death_remembrance"
    private const val PREFS = "death_remembrance"
    private const val NOTIFICATION_ID = 740301
    private const val ACTION_REMIND = "com.tunisianprayertimes.remembrance.REMIND"
    private const val ACTION_PAUSE = "com.tunisianprayertimes.remembrance.PAUSE_MONTH"
    private const val REPAIR_WORK = "death_remembrance_repair"
    /** The day's window: from an hour after sunrise to 45 minutes before Maghrib, never at night. */
    private const val AFTER_SUNRISE_MINUTES = 60L
    private const val BEFORE_MAGHRIB_MINUTES = 45L
    /** A pause of a month, for whoever grieves or is anxious. */
    private const val PAUSE_DAYS = 30L
    /** How far ahead a reminder day is looked for. */
    private const val HORIZON_DAYS = 21
    private const val NONE = Long.MIN_VALUE

    enum class Frequency(internal val key: String, val label: String) {
        DAILY("daily", "كل يوم"),
        TWICE("twice", "مرتين في الأسبوع"),
        WEEKLY("weekly", "مرة في الأسبوع"),
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** One refresh at a time: a reminder that is due is posted once, whoever notices it first. */
    private val lock = Any()

    /** Refreshes off the caller's thread: it reads prayer days and the reading's hold. */
    fun refreshLater(context: Context) {
        val app = context.applicationContext
        background.launch {
            runCatching { refresh(app) }.onFailure { Log.w("DeathReminder", "Could not refresh", it) }
        }
    }

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean("enabled", false)

    fun setEnabled(context: Context, enabled: Boolean) {
        val edit = prefs(context).edit().putBoolean("enabled", enabled).remove("paused_until_day")
        // The reader's own draw of days and minutes, so two phones do not ring together.
        if (enabled && !prefs(context).contains("salt")) edit.putLong("salt", Random().nextLong())
        edit.apply()
        if (!enabled) cancelNotification(context)
        refreshLater(context)
    }

    fun frequency(context: Context): Frequency =
        Frequency.entries.firstOrNull { it.key == prefs(context).getString("frequency", null) } ?: Frequency.TWICE

    fun setFrequency(context: Context, frequency: Frequency) {
        prefs(context).edit().putString("frequency", frequency.key).apply()
        refreshLater(context)
    }

    private fun salt(context: Context): Long = prefs(context).getLong("salt", 0L)

    private fun zone(context: Context): ZoneId = FastingReminder.zone(context)

    fun today(context: Context, now: Instant = Instant.now()): LocalDate = now.atZone(zone(context)).toLocalDate()

    /** The first day the reminder comes again after a pause, as an epoch day. */
    private fun pausedUntilDay(context: Context): Long = prefs(context).getLong("paused_until_day", 0L)

    fun pausedUntil(context: Context, now: Instant = Instant.now()): LocalDate? =
        pausedUntilDay(context).takeIf { it > today(context, now).toEpochDay() }?.let(LocalDate::ofEpochDay)

    fun pauseMonth(context: Context, now: Instant = Instant.now()) {
        prefs(context).edit().putLong("paused_until_day", today(context, now).plusDays(PAUSE_DAYS).toEpochDay()).apply()
        cancelNotification(context)
        refresh(context, now)
    }

    fun resume(context: Context) {
        prefs(context).edit().remove("paused_until_day").apply()
        refreshLater(context)
    }

    /** The text of [day], the same on the card and in that day's reminder. */
    fun remembranceFor(day: LocalDate): DeathRemembranceTexts.Remembrance = DeathRemembranceTexts.forDay(day.toEpochDay())

    // ---- The days and the moment ----

    /**
     * Whether [day] gets a reminder: every day, or one or two days of its week drawn from the reader's [salt], the two
     * never side by side within the week.
     */
    internal fun chosenDay(day: LocalDate, frequency: Frequency, salt: Long): Boolean {
        if (frequency == Frequency.DAILY) return true
        // Weeks from Monday: 1970-01-01, epoch day 0, was a Thursday.
        val week = Math.floorDiv(day.toEpochDay() + 3, 7L)
        val weekday = Math.floorMod(day.toEpochDay() + 3, 7L).toInt()
        val draw = draw(salt, week, WEEK_STREAM)
        val first = draw.nextInt(7)
        if (frequency == Frequency.WEEKLY) return weekday == first
        val second = (first + 2 + draw.nextInt(4)) % 7
        return weekday == first || weekday == second
    }

    /** One day's reminder: when it comes, and the end of the day's window, after which it no longer does. */
    data class Moment(val day: LocalDate, val at: Instant, val windowEnd: Instant)

    /** A minute of [day]'s daylight window drawn from [salt], moved out of any prayer; null when the day has none. */
    private fun momentFor(context: Context, day: LocalDate, salt: Long): Moment? {
        val prayers = PrayerTimesRepository.prayerDay(context, day) ?: return null
        val sunrise = prayers[TimeEvent.SUNRISE]?.at ?: return null
        val maghrib = prayers[TimeEvent.MAGHRIB]?.at ?: return null
        val start = sunrise.plus(Duration.ofMinutes(AFTER_SUNRISE_MINUTES)).toEpochMilli()
        val end = maghrib.minus(Duration.ofMinutes(BEFORE_MAGHRIB_MINUTES)).toEpochMilli()
        if (end <= start) return null
        val draw = draw(salt, day.toEpochDay(), MINUTE_STREAM)
        repeat(3) {
            val at = hifzOutsidePrayers(start + (draw.nextDouble() * (end - start)).toLong())
            if (at < end) return Moment(day, Instant.ofEpochMilli(at), Instant.ofEpochMilli(end))
        }
        return null
    }

    private const val WEEK_STREAM = 0x5745454BL
    private const val MINUTE_STREAM = 0x4D494E55L

    /**
     * The reader's draw for [index] (a week or a day): the same on every refresh, and unrelated from one index to the
     * next. Seeds side by side make java.util.Random's first numbers nearly equal; SplittableRandom mixes each output.
     */
    private fun draw(salt: Long, index: Long, stream: Long) =
        SplittableRandom(salt xor (index * -7046029254386353131L) xor (stream * 0x2545F4914F6CDD1DL))

    /** The two Eids are days of joy: no reminder of death on them, nor on a day that may be one abroad or unannounced. */
    private fun eid(context: Context, day: LocalDate): Boolean =
        FastingDays.mayBeEid(day, abroad = PrayerTimesRepository.place(context) !is PrayerPlace.Inm)

    /** The next day's reminder whose window is not over and that was not shown yet. */
    fun next(context: Context, now: Instant = Instant.now()): Moment? {
        if (!isEnabled(context)) return null
        val frequency = frequency(context)
        val salt = salt(context)
        val today = today(context, now)
        val posted = prefs(context).getLong("posted_day", NONE)
        val pausedUntil = pausedUntilDay(context)
        return generateSequence(today) { it.plusDays(1) }.take(HORIZON_DAYS)
            .filter { it.toEpochDay() >= pausedUntil && it.toEpochDay() != posted }
            .filter { chosenDay(it, frequency, salt) && !eid(context, it) }
            .mapNotNull { momentFor(context, it, salt) }
            .firstOrNull { it.windowEnd > now }
    }

    // ---- The notification ----

    private fun channel(context: Context): NotificationManager? {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return null
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "ذكر الموت", NotificationManager.IMPORTANCE_LOW).apply {
            description = "تذكير هادئ في النهار بالآخرة وبخطوة في العبادة أو الإحسان"
        })
        return manager
    }

    private fun cancelNotification(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    /** The day's text in a line: the prophetic words as the title, the day's good deed as the text, no Qur'an text. */
    private fun post(context: Context, moment: Moment, now: Instant) {
        val manager = channel(context) ?: return
        // Text ends on a word: a phone in another language lays it out left to right.
        val deed = remembranceFor(moment.day).deed.trimEnd('.')
        val open = PendingIntent.getActivity(
            context, NOTIFICATION_ID,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainTabNavigation.EXTRA_DESTINATION, MainTabNavigation.DESTINATION_WORSHIP)
                .putExtra(MainTabNavigation.EXTRA_WORSHIP_ACTION, MainTabNavigation.WORSHIP_ACTION_REMEMBRANCE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val endOfDay = moment.day.plusDays(1).atStartOfDay(zone(context)).toInstant()
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_hourglass)
            .setContentTitle(DeathRemembranceTexts.anchorWords)
            .setContentText(deed)
            .setStyle(NotificationCompat.BigTextStyle().bigText(deed))
            .setSubText("ذكر الموت")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setTimeoutAfter(Duration.between(now, endOfDay).toMillis().coerceAtLeast(60_000L))
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(0, "أوقف التذكير شهرًا", broadcast(context, ACTION_PAUSE, 1))
        manager.notify(NOTIFICATION_ID, builder.build())
    }

    private fun broadcast(context: Context, action: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
        context, code, Intent(context, DeathReminderReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    // ---- Scheduling ----

    private fun alarmIntent(context: Context, backup: Boolean): PendingIntent = PendingIntent.getBroadcast(
        context, if (backup) 21 else 20,
        Intent(context, DeathReminderReceiver::class.java).setAction(ACTION_REMIND)
            .setData(Uri.parse(if (backup) "remembrance://day/backup" else "remembrance://day")),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * Posts the day's reminder once its moment has come, unless a prayer is going on or the Qur'an is being read (then
     * it waits for their end, within the day's window), and arms one alarm for what comes next.
     */
    fun refresh(context: Context, now: Instant = Instant.now()) {
        synchronized(lock) { refreshLocked(context.applicationContext, now) }
    }

    private fun refreshLocked(app: Context, now: Instant) {
        val alarms = app.getSystemService(AlarmManager::class.java) ?: return
        val exact = alarmIntent(app, backup = false)
        val backup = alarmIntent(app, backup = true)
        alarms.cancel(exact)
        alarms.cancel(backup)
        runCatching {
            val work = WorkManager.getInstance(app)
            if (isEnabled(app)) {
                work.enqueueUniquePeriodicWork(REPAIR_WORK, ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<DeathReminderRepairWorker>(12, TimeUnit.HOURS).build())
            } else {
                work.cancelUniqueWork(REPAIR_WORK)
            }
        }.onFailure { Log.w("DeathReminder", "Could not update the repair work", it) }
        if (!isEnabled(app)) return
        var next = next(app, now) ?: return
        if (next.at <= now) {
            val nowMs = now.toEpochMilli()
            // Held by a prayer or by the Qur'an being read: the reminder waits, still within the day's window.
            val free = hifzOutsidePrayers(maxOf(nowMs, QuranReading.holdsUntil(app, nowMs) ?: nowMs))
            when {
                FastingReminder.placeElsewhere(app, now) -> skip(app, next.day)
                free <= nowMs -> {
                    post(app, next, now)
                    skip(app, next.day)
                }
                else -> {
                    next = next.copy(at = Instant.ofEpochMilli(free))
                    if (next.at >= next.windowEnd) skip(app, next.day)
                }
            }
            if (prefs(app).getLong("posted_day", NONE) == next.day.toEpochDay()) next = next(app, now) ?: return
        }
        val at = next.at.toEpochMilli()
        if (DhikrReminderScheduler.exactAlarmsEnabled(app)) {
            runCatching { alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, exact) }
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, backup)
    }

    /** [day] is done: shown, or let go for a traveller whose times are another place's. */
    private fun skip(context: Context, day: LocalDate) {
        prefs(context).edit().putLong("posted_day", day.toEpochDay()).apply()
    }

    internal fun onAction(context: Context, action: String?) {
        when (action) {
            ACTION_REMIND -> refresh(context)
            ACTION_PAUSE -> pauseMonth(context)
        }
    }
}

class DeathReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                DeathReminder.onAction(app, intent.action)
            } catch (error: Exception) {
                Log.w("DeathReminder", "Could not handle ${intent.action}", error)
            } finally {
                result.finish()
            }
        }
    }
}

/** Twice a day: a reminder an OEM held back still comes within its window, and the next one is armed again. */
class DeathReminderRepairWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        DeathReminder.refresh(applicationContext)
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }
}

package com.tunisianprayertimes.quran.hifz

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.tunisianprayertimes.MainActivity
import com.tunisianprayertimes.MainTabNavigation
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.R
import com.tunisianprayertimes.adhkar.DhikrReminderScheduler
import com.tunisianprayertimes.adhkar.DhikrTime
import com.tunisianprayertimes.adhkar.DhikrTimeKind
import com.tunisianprayertimes.quran.QuranMushafText
import com.tunisianprayertimes.quran.QuranRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The one reminder of «المراجعة», at the times the reader chose, after each prayer by default. It asks about the points
 * due then, each by its question and never its answer, and opens «مراجعة اليوم», where the verse waits covered and
 * the answer given decides when it comes back. A fresh slip, or one failed in a check, is asked at every reminder
 * until it is answered right [HifzFreshRecalls] times; a spaced check, at the first reminder of its day. Nothing is
 * posted when nothing is due; a point answered or looked at since leaves the reminder shown, which goes once nothing
 * is due any more.
 */
object HifzReminder {
    const val CHANNEL_ID = "hifz_daily"
    private const val NOTIFICATION_ID = 740_101
    private const val PREFS = "quran_hifz"
    internal const val ACTION_REMIND = "com.tunisianprayertimes.hifz.REMIND"
    private const val EXTRA_DAY = "com.tunisianprayertimes.hifz.extra.DAY"
    private const val EXTRA_TIME = "com.tunisianprayertimes.hifz.extra.TIME"
    /** Later than this, a reminder is dropped: the next one is near. */
    private const val STALE_MS = 3 * 60 * 60_000L
    private val laterDate = DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ar-TN-u-nu-latn"))
    /** A no-break space: a count stays on one line with its noun, «3 مرات», and a clock with «الساعة». */
    const val NB = "\u00A0"

    /**
     * Arms the next reminder, or cancels it, and takes away from the one shown the points no longer to ask: answered,
     * or looked at since, from the review or elsewhere. Call off the main thread.
     */
    fun refresh(context: Context, now: Long = System.currentTimeMillis()) {
        val app = context.applicationContext
        val alarms = app.getSystemService(AlarmManager::class.java) ?: return
        HifzRepository.load(app)
        val settings = HifzSettings.read(app)
        val points = HifzRepository.points.value
        val today = epochDay(now)
        val learning = HifzLearning.read(app, today)
        val askable = points.askable(today, now, learning.seen, learning.exposed)
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val posted = prefs.getString("posted", null).orEmpty().split(',').mapNotNull { it.toLongOrNull() }.toSet()
        val gone = posted.any { id -> askable.none { it.id == id } }
        // What it asked and is still to answer: a reminder asks what it rang for, not what other times are for.
        val still = askable.filter { it.id in posted }
        when {
            !settings.reminder || askable.isEmpty() -> clearReminder(app)
            // Some of what it asked was answered or looked at since: it asks the rest, without a second sound, or goes.
            gone && showing(app) -> if (still.isEmpty()) clearReminder(app)
                else runCatching { post(app, still.reviewBatch(today, now, HifzRoundSize), still.size, quiet = true) }
            // Swiped away: nothing left to update.
            gone -> prefs.edit().remove("posted").apply()
        }
        if (!settings.reminder || points.isEmpty()) {
            alarms.cancel(remindIntent(app, null))
            prefs.edit().remove("armed").apply()
            return
        }
        // Never the reminder just shown again, should its alarm have come a little early; but one due in a few
        // seconds is still to come, even when a refresh runs just before it.
        val shown = prefs.getLong("shown_at", 0L)
        // A reminder whose time came while its alarm waited, as an inexact one may for up to an hour, is still
        // owed: a refresh then shows it at once rather than moving on to the next prayer.
        val owed = prefs.getString("armed", null)?.let(::decodeSlot)?.takeIf {
            it.at in (shown + 1)..now && askable.isNotEmpty() && (now - it.at < STALE_MS || !laterAsksAll(app, settings, it.day, it.time, now))
        }
        val next = owed ?: points.nextReminder(
            settings.times, today, maxOf(now, shown), prefs.getLong("reminded_day", -1L), learning.exposed, learning.seen,
            dailyAsked = dailyAsked(prefs),
        ) { day, time -> slotAt(app, day, time) }
        if (next == null) {
            alarms.cancel(remindIntent(app, null))
            prefs.edit().remove("armed").apply()
            return
        }
        prefs.edit().putString("armed", "${next.day}|${next.time.key}|${next.at}").apply()
        val pending = remindIntent(app, next)
        // After a prayer means after it, and a time is a time: exact when the system allows it, otherwise as close as it lets.
        val at = maxOf(next.at, now)
        if (DhikrReminderScheduler.exactAlarmsEnabled(app)) {
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
                return
            } catch (_: SecurityException) {
            }
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
    }

    /** Takes the reminder away: its review was opened, or nothing it asked is due any more. */
    fun clearReminder(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("posted").apply()
    }

    /** The reminder is in the shade. */
    private fun showing(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java)?.activeNotifications?.any { it.id == NOTIFICATION_ID } == true

    /** Another reminder of [times] on the prayer day [day] is still to come after [now]. */
    private fun laterSlot(context: Context, times: HifzTimes, day: Long, now: Long): Boolean =
        times.times.any { time -> (slotAt(context, day, time) ?: Long.MIN_VALUE) > now }

    /**
     * A reminder at [time] hours late gives way to a later one of its day only if that one asks all it would: the
     * spaced checks at a later time of the settings, each slip at a later time of its own. A slip asked at one time of
     * day has no later one: its reminder comes late rather than not at all.
     */
    private fun laterAsksAll(context: Context, settings: HifzSettings, day: Long, time: HifzWhen, now: Long): Boolean {
        val today = epochDay(now)
        val learning = HifzLearning.read(context, today)
        val due = HifzRepository.points.value.askable(today, now, learning.seen, learning.exposed)
        val checksLater = due.none { it.fresh == 0 && it.dueDay <= day } || laterSlot(context, settings.times, day, now)
        return checksLater && due.filter { it.fresh > 0 && time in (it.times ?: settings.times) }
            .all { !it.daily && laterSlot(context, it.times ?: settings.times, day, now) }
    }

    /** The points the reminder in the shade asks, for the review it opens. */
    fun postedIds(context: Context): Set<Long> = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString("posted", null).orEmpty().split(',').mapNotNull { it.toLongOrNull() }.toSet()

    private fun decodeSlot(value: String): HifzSlot? {
        val parts = value.split('|')
        if (parts.size != 3) return null
        val time = HifzWhen.of(parts[1]) ?: return null
        return HifzSlot(parts[0].toLongOrNull() ?: return null, time, parts[2].toLongOrNull() ?: return null)
    }

    /** When the reminder at [time] on [day] comes; null when that day's prayer times are not known. */
    fun slotAt(context: Context, day: Long, time: HifzWhen): Long? = when (time) {
        is HifzWhen.At -> LocalDate.ofEpochDay(day).atTime(time.minute / 60, time.minute % 60)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        is HifzWhen.After -> afterPrayerAt(context, day, time.prayer)
    }

    /**
     * When the reminder after [prayer] on [day] comes: once the prayer's silence ends, the silence as the reader set it
     * even when the app does not silence that prayer, so it never rings during the prayer or a Friday's khutba; 20
     * minutes after the adhan when no window is known; never sooner than 10 minutes after it. On Friday, Dhuhr is the
     * Jumu'a. Null when that day's prayer times are not known.
     */
    private fun afterPrayerAt(context: Context, day: Long, prayer: Prayer): Long? {
        val date = LocalDate.ofEpochDay(day)
        val kind = when (prayer) {
            Prayer.FAJR -> DhikrTimeKind.FAJR
            Prayer.DHUHR -> DhikrTimeKind.DHUHR
            Prayer.ASR -> DhikrTimeKind.ASR
            Prayer.MAGHRIB -> DhikrTimeKind.MAGHRIB
            Prayer.ISHA -> DhikrTimeKind.ISHA
            else -> return null
        }
        val friday = prayer == Prayer.DHUHR && date.dayOfWeek == DayOfWeek.FRIDAY
        val silenced = if (friday) Prayer.JOMOAA else prayer
        // The planner's window of that prayer day (6.9): its adhan (the Jumu'a time on a Friday) and its end
        // as instants, so a change of the clocks that day does not move the reminder.
        val window = runCatching {
            com.tunisianprayertimes.SilenceScheduler.planner(
                context, com.tunisianprayertimes.PrayerTimesRepository.place(context),
                mapOf(silenced to PrefsManager.getSilenceRule(context, silenced)),
            ).windowsOf(date).firstOrNull { it.prayer == silenced }
        }.getOrNull()
        val adhan = window?.adhan?.toEpochMilli()
            ?: (if (friday) com.tunisianprayertimes.PrayerTimesRepository.jumuaTime(context, date)?.toEpochMilli() else null)
            ?: DhikrReminderScheduler.resolveTime(context, DhikrTime(kind), date)
            ?: return null
        return maxOf(adhan + 10 * 60_000L, window?.end?.toEpochMilli()?.plus(2 * 60_000L) ?: (adhan + 20 * 60_000L))
    }

    /**
     * The first reminder that may ask about a slip recorded at [now], at its own [times] or else the settings'; null with
     * the reminder off. Call off the main thread.
     */
    fun firstQuestion(
        context: Context, now: Long = System.currentTimeMillis(), fromDay: Long = Long.MIN_VALUE, times: HifzTimes? = null,
    ): HifzSlot? {
        val settings = HifzSettings.read(context)
        if (!settings.reminder) return null
        return (times ?: settings.times).firstAfter(now + HifzFreshGapMs - 1, fromDay) { day, time -> slotAt(context, day, time) }
    }

    /**
     * [timesPhrase] said as a slip's reminders: one time of day, or clock times, are every day's, «كل يوم بعد العشاء»,
     * «كل يوم في الساعة 15:35 و21:30»; prayers alone, «بعد كل صلاة», «بعد الفجر والعشاء».
     */
    fun slipTimesPhrase(times: HifzTimes): String = timesPhrase(times).let {
        if ((times.count == 1 || times.minutes.isNotEmpty()) && !it.startsWith("كل يوم")) "كل يوم $it" else it
    }

    /**
     * What becomes of a slip recorded now with its plan, [recalls] right answers asked at every reminder or [daily],
     * said on the record sheet before it is saved: «نذكّرك بها اليوم بعد العصر، ثم بعد كل صلاة، إلى أن تتذكّرها دون
     * مساعدة 3 مرات. بعد ذلك تقلّ التذكيرات شيئًا فشيئًا.» Counted as the review's own answer says it, «دون مساعدة»:
     * the reader judges, the app does not listen. Call off the main thread.
     */
    fun freshSentence(
        context: Context, recalls: Int = HifzFreshRecalls,
        /** The slip's own reminder times; null for the settings'. One time of day is once a day. */
        times: HifzTimes? = null,
        now: Long = System.currentTimeMillis(),
        /** Asked once a day and already asked today: its first question is tomorrow's. */
        tomorrow: Boolean = false,
        /** Asked once a day, the slip of a point a reminder already asked on this prayer day: not before the next. */
        askedDay: Long? = null,
    ): String {
        val settings = HifzSettings.read(context)
        val today = epochDay(now)
        val daily = times?.count == 1
        val until = untilPhrase(recalls)
        if (!settings.reminder) return "التذكير متوقف: تجدها في «مراجعة اليوم» " +
            (if (tomorrow) "غدًا" else "بعد ${HifzFreshGapMs / 60_000L}${NB}دقيقة") +
            "، $until. بعد ذلك تقلّ الأسئلة عنها شيئًا فشيئًا."
        val first = firstQuestion(
            context, now, maxOf(if (tomorrow) today + 1 else Long.MIN_VALUE, if (daily) askedDay?.plus(1) ?: Long.MIN_VALUE else Long.MIN_VALUE),
            times,
        )
        val shared = times ?: settings.times
        // One time of day is said once: «اليوم الساعة 15:35، ثم كل يوم في الوقت نفسه».
        val often = if (shared.count == 1 && first != null) "كل يوم في الوقت نفسه" else slipTimesPhrase(shared)
        val rest = "$until. بعد ذلك تقلّ التذكيرات شيئًا فشيئًا."
        first ?: return "نذكّرك بها $often، $rest"
        return "نذكّرك بها ${slotLabel(first, today)}، ثم $often، $rest"
    }

    /** «إلى أن تتذكّرها دون مساعدة 3 مرات»; one answer needs no count: «إلى أن تتذكّرها دون مساعدة». */
    fun untilPhrase(recalls: Int): String = "إلى أن تتذكّرها دون مساعدة" + if (recalls == 1) "" else " " + recallsPhrase(recalls)

    /**
     * The reminder that next asks [point], a fresh one, as [nextReminder] arms it: a while after its slip, its answer
     * and its verse last [seen]; and, asked once a day, not on a day a reminder already asked it. Null with the reminder
     * off, or for a point no longer fresh. Call off the main thread.
     */
    fun nextAsk(context: Context, point: HifzPoint, seen: Long?, now: Long = System.currentTimeMillis()): HifzSlot? {
        val settings = HifzSettings.read(context)
        if (!settings.reminder || point.fresh <= 0) return null
        val from = if (!point.daily) Long.MIN_VALUE
            else maxOf(point.dueDay, dailyAskedDay(context, point.id)?.plus(1) ?: Long.MIN_VALUE)
        return (point.times ?: settings.times).firstAfter(maxOf(now, point.freshDueAt(seen) - 1), from) { d, t -> slotAt(context, d, t) }
    }

    /** The prayer day a reminder last asked [id], a point asked once a day; null when none did. */
    fun dailyAskedDay(context: Context, id: Long): Long? =
        dailyAsked(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))[id]

    /**
     * «غدًا بعد الفجر»: the first reminder of [day], where a spaced check is asked, and a point asked once a day; null
     * with the reminder off. Call off the main thread.
     */
    fun dayFirstLabel(context: Context, day: Long, now: Long = System.currentTimeMillis()): String? {
        val settings = HifzSettings.read(context)
        if (!settings.reminder) return null
        return settings.times.firstAfter(now, day) { d, t -> slotAt(context, d, t) }?.let { slotLabel(it, epochDay(now)) }
    }

    /**
     * When a recorded point is asked next, for its fix, in the plan's own voice. A fresh one says its whole plan once:
     * «نذكّرك بها اليوم الساعة 15:35، ثم كل يوم في الوقت نفسه، إلى أن تتذكّرها دون مساعدة 3 مرات»; a spaced check,
     * «نسألك عنها الأحد بعد الفجر». A fresh point's answer seen, as in this fix, puts its question a while later; a check
     * whose verse was looked at today waits for the next day. No full stop: it is one of the fix's lines of facts. Call
     * off the main thread.
     */
    fun pointSentence(context: Context, point: HifzPoint, now: Long = System.currentTimeMillis()): String {
        val settings = HifzSettings.read(context)
        val today = epochDay(now)
        val learning = HifzLearning.read(context, today)
        val slotFrom = { after: Long, day: Long -> settings.times.firstAfter(after, day) { d, t -> slotAt(context, d, t) } }
        if (point.fresh > 0) {
            // Its answer is in view in its fix, as it is said: asked a while after; once a day, not before its day,
            // nor on a day a reminder already asked it.
            val seen = maxOf(now, learning.seen[point.verse] ?: now)
            val until = untilPhrase(point.fresh)
            if (!settings.reminder) return "التذكير متوقف: تجدها في «مراجعة اليوم» ${inReviewLabel(point.freshAskAt(seen), today)}، $until"
            val shared = point.times ?: settings.times
            val often = if (shared.count == 1) "كل يوم في الوقت نفسه" else slipTimesPhrase(shared)
            val next = nextAsk(context, point, seen, now) ?: return "نذكّرك بها ${slipTimesPhrase(shared)}، $until"
            return "نذكّرك بها ${slotLabel(next, today)}، ثم $often، $until"
        }
        // Its answer is in view in its fix: a check that counts is the next day's.
        val dueDay = if (point.dueDay <= today) today + 1 else point.dueDay
        if (!settings.reminder) return "التذكير متوقف: تجدها في «مراجعة اليوم» ${dayLabel(dueDay, today)}"
        val first = slotFrom(now, dueDay) ?: return "نسألك عنها ${dayLabel(dueDay, today)}"
        return "نسألك عنها ${slotLabel(first, today)}"
    }

    /**
     * When a fresh point answered or seen now is asked again: «اليوم بعد المغرب», «غدًا بعد الفجر»; «بعد 45 دقيقة» with
     * the reminder off, in «مراجعة اليوم». Call off the main thread.
     */
    fun freshAgainLabel(context: Context, now: Long = System.currentTimeMillis(), point: HifzPoint? = null): String =
        // At the point's own times; once a day, on another day.
        firstQuestion(context, now, if (point?.daily == true) epochDay(now) + 1 else Long.MIN_VALUE, point?.times)?.let { slotLabel(it, epochDay(now)) }
            ?: when {
                HifzSettings.read(context).reminder -> "في تذكير لاحق"
                point?.daily == true -> "في «مراجعة اليوم» غدًا"
                else -> "في «مراجعة اليوم» بعد ${HifzFreshGapMs / 60_000L}${NB}دقيقة"
            }

    /**
     * From when a question waits in «مراجعة اليوم» with the reminder off: «من الساعة 15:20» today, «غدًا» from a day's
     * start, «غدًا من الساعة 05:10»: never «مراجعة اليوم» followed by «اليوم».
     */
    fun inReviewLabel(at: Long, today: Long): String {
        val time = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
        val day = time.toLocalDate().toEpochDay()
        val minute = time.hour * 60 + time.minute
        return when {
            day <= today -> "من الساعة$NB${clock(minute)}"
            minute == 0 -> dayLabel(day, today)
            else -> "${dayLabel(day, today)} من الساعة$NB${clock(minute)}"
        }
    }

    /** «اليوم الساعة 14:20», «غدًا الساعة 05:10». */
    fun dayAndClock(at: Long, today: Long): String {
        val time = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
        return "${dayLabel(time.toLocalDate().toEpochDay(), today)} الساعة$NB${clock(time.hour * 60 + time.minute)}"
    }

    /** «بعد كل صلاة», «بعد الفجر والظهر والعشاء», «بعد الفجر وفي الساعة 13:00 و21:30». */
    fun timesPhrase(times: HifzTimes): String {
        if (HifzTimesPreset.of(times) == HifzTimesPreset.Once) return "كل يوم بعد الفجر"
        val prayers = HifzPrayers.filter { it in times.prayers }.map { prayerName(it, null) }
        val after = when {
            prayers.size == HifzPrayers.size -> "بعد كل صلاة"
            prayers.isNotEmpty() -> "بعد " + prayers.joinToString(" و")
            else -> null
        }
        val at = times.minutes.sorted().takeIf { it.isNotEmpty() }?.joinToString(" و", prefix = "في الساعة$NB") { clock(it) }
        return listOfNotNull(after, at).joinToString(" و")
    }

    /** After a verb: «مرة واحدة», «مرتين», «3 مرات»; the plan's count, «تتذكّرها 3 مرات دون مساعدة». */
    fun recallsPhrase(count: Int): String = when {
        count == 1 -> "مرة واحدة"
        count == 2 -> "مرتين"
        count % 100 in 3..10 -> "$count${NB}مرات"
        else -> "$count${NB}مرة"
    }

    /** «08:00», «21:30»: a minute of the day, by the 24-hour clock. */
    fun clock(minute: Int): String = "${(minute / 60).toString().padStart(2, '0')}:${(minute % 60).toString().padStart(2, '0')}"

    /** «بعد الظهر», «بعد الجمعة», «الساعة 21:30». */
    fun timeLabel(time: HifzWhen, day: Long?): String = when (time) {
        is HifzWhen.After -> "بعد ${prayerName(time.prayer, day)}"
        is HifzWhen.At -> "الساعة$NB${clock(time.minute)}"
    }

    /** The name of [prayer]: «الفجر» … «العشاء». */
    fun prayerLabel(prayer: Prayer): String = prayerName(prayer, null)

    /** «اليوم بعد العصر», «غدًا بعد الفجر», «الأحد بعد الجمعة», «اليوم الساعة 21:30». */
    // A prayer day's Isha after midnight is still «اليوم» to the reader, never yesterday's weekday.
    fun slotLabel(slot: HifzSlot, today: Long): String = "${dayLabel(maxOf(slot.day, today), today)} ${timeLabel(slot.time, slot.day)}"

    /**
     * «اليوم», «غدًا», the day of the week, «الخميس القادم» the next week, where the name is today's again, and
     * with the date further off: «الجمعة 23 أكتوبر».
     */
    fun dayLabel(day: Long, today: Long): String {
        if (day - today == 0L) return "اليوم"
        if (day - today == 1L) return "غدًا"
        val dayOfWeek = LocalDate.ofEpochDay(day).dayOfWeek
        val name = when (dayOfWeek) {
            DayOfWeek.MONDAY -> "الإثنين"
            DayOfWeek.TUESDAY -> "الثلاثاء"
            DayOfWeek.WEDNESDAY -> "الأربعاء"
            DayOfWeek.THURSDAY -> "الخميس"
            DayOfWeek.FRIDAY -> "الجمعة"
            DayOfWeek.SATURDAY -> "السبت"
            DayOfWeek.SUNDAY -> "الأحد"
        }
        return when {
            day - today < 7L -> name
            day - today >= 14L -> "$name ${LocalDate.ofEpochDay(day).format(laterDate)}"
            dayOfWeek == DayOfWeek.FRIDAY -> "$name القادمة"
            else -> "$name القادم"
        }
    }

    /** On a Friday [day], Dhuhr is «الجمعة»; with no day, «الظهر». */
    private fun prayerName(prayer: Prayer, day: Long?): String = when (prayer) {
        Prayer.FAJR -> "الفجر"
        Prayer.DHUHR -> if (day != null && LocalDate.ofEpochDay(day).dayOfWeek == DayOfWeek.FRIDAY) "الجمعة" else "الظهر"
        Prayer.ASR -> "العصر"
        Prayer.MAGHRIB -> "المغرب"
        Prayer.ISHA -> "العشاء"
        else -> ""
    }

    /** The app may post notifications: the permission is granted and they are not turned off for the app. */
    fun appNotificationsAllowed(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** Which of [channels] the reader turned off, if any. */
    fun blockedChannel(context: Context, channels: List<String>): String? {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return null
        return channels.firstOrNull { manager.getNotificationChannel(it)?.importance == NotificationManager.IMPORTANCE_NONE }
    }

    /**
     * Why the reminders in [channels] cannot reach the reader, in a line, or null when they can. Nothing is
     * said for no channel: no reminder is on.
     */
    fun notificationProblem(context: Context, channels: List<String>): String? = when {
        channels.isEmpty() -> null
        !appNotificationsAllowed(context) -> "الإشعارات متوقفة: لن يصلك أي تذكير"
        blockedChannel(context, channels) == CHANNEL_ID -> "تذكير المراجعة متوقف في إعدادات الإشعارات"
        else -> null
    }

    /**
     * Posts the reminder at [time] of the prayer day [day], unless it is hours late with another of that day still to
     * come, then arms the next one.
     */
    internal fun deliver(context: Context, day: Long, time: HifzWhen?) {
        val app = context.applicationContext
        HifzRepository.load(app)
        val now = System.currentTimeMillis()
        val settings = HifzSettings.read(app)
        if (time != null && settings.reminder) {
            val at = slotAt(app, day, time)
            // The silence or the city changed since the alarm was set, and the reminder now comes later: wait for it,
            // armed anew at its new time, not owed at its old one.
            if (at != null && at - now > 60_000L) {
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("armed").apply()
                refresh(app)
                return
            }
            // Marked shown before it is posted: a refresh running meanwhile, as the app's start does, must not take
            // this reminder for one still owed and bring it a second time.
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("shown_at", maxOf(at ?: now, now)).commit()
            if (at == null || now - at < STALE_MS || !laterAsksAll(app, settings, day, time, now)) remind(app, day, time, maxOf(now, at ?: now))
        }
        refresh(app)
    }

    /**
     * Asks about what is due at [now], the reminder at [time] of the prayer day [day]: when a fresh point whose times
     * hold [time] is, or when spaced checks of that day are, at the settings' times, and no reminder of it brought them
     * yet. Then the fresh points and the checks together, as the review they open asks them. Isha's reminder after
     * midnight is its day's, so it never takes the next day's checks.
     */
    private fun remind(context: Context, day: Long, time: HifzWhen, now: Long) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val settings = HifzSettings.read(context)
        val today = epochDay(now)
        val learning = HifzLearning.read(context, today)
        val due = HifzRepository.points.value.askable(today, now, learning.seen, learning.exposed)
        val checks = time in settings.times && due.any { it.fresh == 0 && it.dueDay <= day }
        // A slip rings at its own times. One asked once a day, already asked by a reminder of this day, waits in
        // «مراجعة اليوم» without sounding again.
        val asked = dailyAsked(prefs)
        val fresh = due.filter {
            it.fresh > 0 && time in (it.times ?: settings.times) && !(it.daily && (asked[it.id] ?: Long.MIN_VALUE) >= day)
        }
        if (fresh.isEmpty() && (!checks || prefs.getLong("reminded_day", -1L) >= day)) return
        // A reminder that cannot reach the reader leaves the day's checks to a later one.
        if (!appNotificationsAllowed(context) || blockedChannel(context, listOf(CHANNEL_ID)) != null) return
        // It asks what it rang for: the slips of this time and, at the settings' times, the day's checks; a slip set
        // for other times waits for its own.
        val slot = fresh + if (checks) due.filter { it.fresh == 0 } else emptyList()
        val batch = slot.reviewBatch(today, now, HifzRoundSize)
        val posted = runCatching { post(context, batch, slot.size) }.isSuccess
        if (!posted) return
        if (checks) prefs.edit().putLong("reminded_day", maxOf(day, prefs.getLong("reminded_day", -1L))).apply()
        // Only those it shows count as asked today: one left for another round still rings at its next time.
        val dailyNow = batch.filter { it.daily && it.fresh > 0 }.associate { it.id to day }
        if (dailyNow.isNotEmpty()) prefs.edit().putString(
            "daily_asked",
            (asked.filterValues { it >= day - 2 } + dailyNow).entries.joinToString(",") { "${it.key}:${it.value}" },
        ).apply()
    }

    /** The prayer day each point asked once a day was last asked by a reminder; see [remind]. */
    private fun dailyAsked(prefs: android.content.SharedPreferences): Map<Long, Long> =
        prefs.getString("daily_asked", null).orEmpty().split(',').mapNotNull { row ->
            val parts = row.split(':')
            val id = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val day = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            id to day
        }.toMap()

    /**
     * [due] the review's points, [dueAll] every point to ask now, also those beyond one review's. [quiet]: the reminder
     * already shown, asking fewer points now, without sounding again.
     */
    private fun post(context: Context, due: List<HifzPoint>, dueAll: Int, quiet: Boolean = false) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "المراجعة", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "أسئلة عن مواضعك بعد الصلوات أو في الأوقات التي اخترتها، دون أجوبتها"
        })
        // The reminders of per-point plans, which showed the answers, are gone with their channel.
        manager.deleteNotificationChannel("hifz_repeat")
        val catalog = QuranRepository.load(context)
        val name = { n: Int -> catalog.surahs.getOrNull(n - 1)?.name.orEmpty() }
        // A sentence, as the reader is spoken to everywhere; the system says «المراجعة» and when, above it.
        val title = "نسألك عن ${pointsAbout(maxOf(dueAll, due.size))}"
        val more = (dueAll - due.size).coerceAtLeast(0)
        // The verses by surah, in order, short enough to be read whole: «البقرة 248، 250، 254 وموضعان آخران». What each
        // asks is in the lines below, as the review asks it.
        val summary = due.groupBy { it.verse.surah }.entries.joinToString(" · ") { (surah, points) ->
            name(surah) + " " + points.map { it.verse.ayah }.distinct().sorted().joinToString("، ")
        } + if (more > 0) " و" + otherPoints(more) else ""
        // What each check will ask, never its answer: read here, it would be no check at all. Nor is there anything to
        // listen to or to mark as known: the answer is seen once it is recalled, in the review, with the verse covered.
        val lines = due.mapNotNull { point ->
            val fix = hifzFix(catalog, point) ?: return@mapNotNull null
            "${name(point.verse.surah)} ${point.verse.ayah} · ${plain(fix.digestPrompt)}"
        } + listOfNotNull(more.takeIf { it > 0 }?.let { "ثم " + otherPoints(it) })
        val style = Notification.InboxStyle().setBigContentTitle(title).also { inbox -> lines.forEach(inbox::addLine) }
        val review = openIntent(context, NOTIFICATION_ID, MainTabNavigation.QURAN_ACTION_HIFZ_REVIEW)
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tab_quran)
            .setContentTitle(title)
            .setContentText(summary)
            .setSubText("المراجعة")
            .setStyle(style)
            .setContentIntent(review)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setOnlyAlertOnce(quiet)
            .addAction(Notification.Action.Builder(null, "ابدأ المراجعة", review).build())
        manager.notify(NOTIFICATION_ID, builder.build())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("posted", due.joinToString(",") { it.id.toString() }).apply()
    }

    /** Qur'an words in a notification, which shows them in the system's font, not the mushaf's. */
    private fun plain(text: String): String = QuranMushafText.plain(text)

    private fun openIntent(context: Context, requestCode: Int, quranAction: String): PendingIntent = PendingIntent.getActivity(
        context, requestCode,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainTabNavigation.EXTRA_DESTINATION, MainTabNavigation.DESTINATION_QURAN)
            .putExtra(MainTabNavigation.EXTRA_QURAN_ACTION, quranAction),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** As a subject: «موضع واحد», «موضعان», «3 مواضع», «11 موضعًا». */
    fun pointsLabel(count: Int): String = when {
        count == 1 -> "موضع واحد"
        count == 2 -> "موضعان"
        count % 100 in 3..10 -> "$count${NB}مواضع"
        count >= 100 && count % 100 in 0..2 -> "$count${NB}موضع"
        else -> "$count${NB}موضعًا"
    }

    /** After «عن» or «في»: «موضع واحد», «موضعين», «3 مواضع», «11 موضعًا». */
    fun pointsAbout(count: Int): String = if (count == 2) "موضعين" else pointsLabel(count)

    /** As an object: «موضعًا واحدًا», «موضعين», «3 مواضع», «11 موضعًا». */
    fun pointsObject(count: Int): String = when (count) {
        1 -> "موضعًا واحدًا"
        2 -> "موضعين"
        else -> pointsLabel(count)
    }

    /** The rest, as a subject: «موضع آخر», «موضعان آخران», «3 مواضع أخرى», «11 موضعًا آخر». */
    fun otherPoints(count: Int): String = when {
        count == 1 -> "موضع آخر"
        count == 2 -> "موضعان آخران"
        count % 100 in 3..10 -> "$count${NB}مواضع أخرى"
        count >= 100 && count % 100 in 0..2 -> "$count${NB}موضع آخر"
        else -> "$count${NB}موضعًا آخر"
    }

    /** The rest, after «عن»: «موضع آخر», «موضعين آخرين», «3 مواضع أخرى». */
    fun otherPointsAbout(count: Int): String = if (count == 2) "موضعين آخرين" else otherPoints(count)

    /** The alarm for the next reminder; one at a time, so [slot] only changes its extras. */
    private fun remindIntent(context: Context, slot: HifzSlot?): PendingIntent = PendingIntent.getBroadcast(
        context, NOTIFICATION_ID,
        Intent(context, HifzReminderReceiver::class.java).setAction(ACTION_REMIND).apply {
            if (slot != null) putExtra(EXTRA_DAY, slot.day).putExtra(EXTRA_TIME, slot.time.key)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    internal fun receive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REMIND) {
            deliver(context, intent.getLongExtra(EXTRA_DAY, todayEpochDay()), HifzWhen.of(intent.getStringExtra(EXTRA_TIME)))
        }
    }
}

class HifzReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != HifzReminder.ACTION_REMIND) return
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                HifzReminder.receive(context, intent)
            } finally {
                result.finish()
            }
        }
    }
}

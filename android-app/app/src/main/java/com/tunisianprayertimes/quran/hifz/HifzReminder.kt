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
import com.tunisianprayertimes.quran.QuranReading
import com.tunisianprayertimes.quran.QuranRepository
import com.tunisianprayertimes.silence.SilenceWindow
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
    private const val EXTRA_SNOOZE = "com.tunisianprayertimes.hifz.extra.SNOOZE"
    private const val EXTRA_GENERATION = "com.tunisianprayertimes.hifz.extra.GENERATION"
    internal const val ACTION_LATER = "com.tunisianprayertimes.hifz.LATER"
    internal const val ACTION_PAUSE = "com.tunisianprayertimes.hifz.PAUSE"
    private val laterDate = DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ar-TN-u-nu-latn"))
    /** Prayer spans are kept this long, and dropped at each [refresh] and [deliver]: the silences or the city may change. */
    private const val SPAN_CACHE_MS = 60_000L
    private val spanLock = Any()
    private val spanCache = object : LinkedHashMap<Long, Pair<Long, List<HifzPrayerSpan>>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Pair<Long, List<HifzPrayerSpan>>>?) = size > 32
    }
    /** Counts the forgettings, so that spans read before one are not kept after it. */
    private var spanGeneration = 0L
    /**
     * One at a time: a delivery deciding whether its reminder was already shown, and a refresh deciding what is owed.
     * The app's start refreshes while the alarm that woke it delivers; apart, both could bring the same reminder.
     */
    private val deliveryLock = Any()
    /** A no-break space: a count stays on one line with its noun, «3 مرات», and a clock with «الساعة». */
    const val NB = "\u00A0"

    /**
     * Arms the next reminder, or cancels it, and takes away from the one shown the points no longer to ask: answered,
     * or looked at since, from the review or elsewhere. Call off the main thread.
     */
    fun refresh(context: Context) {
        // The clock read once the lock is held: a delivery just before may have shown a reminder meanwhile.
        synchronized(deliveryLock) { refreshLocked(context, System.currentTimeMillis()) }
    }

    private fun refreshLocked(context: Context, now: Long) {
        val app = context.applicationContext
        val alarms = app.getSystemService(AlarmManager::class.java) ?: return
        forgetPrayerSpans()
        HifzPrayerSpans.around(now)
        HifzRepository.load(app)
        val settings = HifzSettings.read(app)
        val points = HifzRepository.points.value
        val today = epochDay(now)
        val learning = HifzLearning.read(app, today)
        val askable = points.askable(today, now, learning.seen, learning.exposed)
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val posted = postedIds(app)
        val gone = posted.any { id -> askable.none { it.id == id } }
        val still = askable.filter { it.id in posted }
        when {
            !settings.reminder || askable.isEmpty() -> clearReminder(app)
            gone && showing(app) -> if (still.isEmpty()) clearReminder(app)
                else runCatching { post(app, still.reviewBatch(today, now, HifzRoundSize), still.size, quiet = true) }
            gone -> prefs.edit().remove("posted").apply()
        }
        if (!settings.reminder || points.isEmpty()) {
            alarms.cancel(remindIntent(app, null))
            prefs.edit().remove("armed").remove("snooze_until").remove("snooze_ids").apply()
            return
        }
        val state = controlState(app, now)
        val readingUntil = QuranReading.holdsUntil(app, now)
        // Later is a one-off request for the shown points, separate from all recurring slot identities.
        val snooze = prefs.getLong("snooze_until", 0L)
        if (snooze > 0 && idSet(prefs.getString("snooze_ids", null)).isNotEmpty()) {
            val allowed = hifzAlertNotBefore(now, settings, state.alertsToday, state.pausedToday,
                prefs.getLong("last_alert_at", 0L), explicitLater = true)
            val fireAt = hifzAllowedAlertAt(maxOf(now, snooze, allowed), settings, readingUntil, ::hifzOutsidePrayers)
            prefs.edit().putLong("snooze_until", fireAt).apply()
            arm(app, alarms, fireAt, null, snooze = true)
            return
        }
        val shown = prefs.getLong("shown_at", 0L).let { if (it > now + 2 * 60_000L) now else it }
        val allowed = hifzAlertNotBefore(now, settings, state.alertsToday, state.pausedToday, prefs.getLong("last_alert_at", 0L))
        var after = maxOf(now, shown)
        if (state.pausedToday || state.alertsToday >= settings.dailyAlertLimit.coerceAtLeast(1)) {
            after = maxOf(after, hifzNextDayStart(now) - 1)
        }
        val checksAsked = checksAsked(prefs, points)
        var candidate = prefs.getString("armed", null)?.let(::decodeSlot)?.takeIf {
            it.at in (shown + 1)..now && hifzCanCatchUp(it.at, now) && askable.isNotEmpty() && after == maxOf(now, shown)
        }
        // Keep the selected slot's original timestamp; only the alarm's fire time is deferred. If a restriction
        // would make it over 90 minutes late, choose a later selected slot instead of ringing on restriction expiry.
        repeat(64) {
            val next = candidate ?: points.nextReminder(
                settings.times, today, after, -1L, learning.exposed, learning.seen,
                dailyAsked = dailyAsked(prefs), checksAsked = checksAsked,
            ) { day, time -> slotAt(app, day, time) }
            if (next == null) {
                alarms.cancel(remindIntent(app, null))
                prefs.edit().remove("armed").apply()
                return
            }
            val fireAt = hifzAllowedAlertAt(maxOf(next.at, now, allowed), settings, readingUntil, ::hifzOutsidePrayers)
            if (hifzCanCatchUp(next.at, fireAt)) {
                prefs.edit().putString("armed", "${next.day}|${next.time.key}|${next.at}").apply()
                arm(app, alarms, fireAt, next)
                return
            }
            after = maxOf(after, next.at, fireAt - HifzCatchUpMs - 1)
            candidate = null
        }
        // A bounded search never re-arms an already rejected slot in a tight loop.
        alarms.cancel(remindIntent(app, null))
        prefs.edit().remove("armed").apply()
    }

    private fun arm(context: Context, alarms: AlarmManager, at: Long, slot: HifzSlot?, snooze: Boolean = false) {
        val pending = remindIntent(context, slot, snooze)
        if (DhikrReminderScheduler.exactAlarmsEnabled(context)) {
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
                return
            } catch (_: SecurityException) {
            }
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
    }

    fun controlState(context: Context, now: Long = System.currentTimeMillis()): HifzReminderControlState {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = epochDay(now)
        val settings = HifzSettings.read(context)
        val count = if (prefs.getLong("alert_day", -1L) == today) prefs.getInt("alert_count", 0).coerceAtLeast(0) else 0
        val paused = prefs.getLong("paused_day", -1L) == today
        val allowed = hifzAlertNotBefore(now, settings, count, paused, prefs.getLong("last_alert_at", 0L))
        return HifzReminderControlState(paused,
            prefs.getLong("snooze_until", 0L).takeIf { it > 0 }, count, allowed.takeIf { it > now })
    }

    /** Pause only notifications until the next local day. Due work and learning history are unchanged. */
    fun pauseToday(context: Context) = synchronized(deliveryLock) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("paused_day", todayEpochDay())
            .remove("snooze_until").remove("snooze_ids").apply()
        clearReminder(app)
        refreshLocked(app, System.currentTimeMillis())
    }

    fun resume(context: Context) = synchronized(deliveryLock) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("paused_day")
            .remove("snooze_until").remove("snooze_ids").apply()
        refreshLocked(app, System.currentTimeMillis())
    }

    /** Explicit Later may bypass the automatic one-hour gap, but never the daily budget or eligibility. */
    fun later(context: Context): Boolean = synchronized(deliveryLock) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val settings = HifzSettings.read(app)
        val state = controlState(app, now)
        val ids = postedIds(app)
        if (!settings.reminder || state.pausedToday || ids.isEmpty() || state.alertsToday >= settings.dailyAlertLimit) return@synchronized false
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("snooze_until", now + HifzLaterMs)
            .putString("snooze_ids", ids.joinToString(",")).apply()
        clearReminder(app)
        refreshLocked(app, now)
        true
    }

    private fun countAlert(context: Context, now: Long) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val count = controlState(context, now).alertsToday
        prefs.edit().putLong("alert_day", epochDay(now)).putInt("alert_count", count + 1).putLong("last_alert_at", now).apply()
    }

    private fun idSet(value: String?): Set<Long> = value.orEmpty().split(',').mapNotNull { it.toLongOrNull() }.toSet()

    /** Takes the reminder away: its review was opened, or nothing it asked is due any more. */
    fun clearReminder(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("posted").apply()
    }

    /** The reminder is in the shade. */
    private fun showing(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java)?.activeNotifications?.any { it.id == NOTIFICATION_ID } == true

    /** The points the reminder in the shade asks, for the review it opens. */
    fun postedIds(context: Context): Set<Long> = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString("posted", null).orEmpty().split(',').mapNotNull { it.toLongOrNull() }.toSet()

    private fun decodeSlot(value: String): HifzSlot? {
        val parts = value.split('|')
        if (parts.size != 3) return null
        val time = HifzWhen.of(parts[1]) ?: return null
        return HifzSlot(parts[0].toLongOrNull() ?: return null, time, parts[2].toLongOrNull() ?: return null)
    }

    /**
     * When the reminder at [time] on [day] comes; null when that day's prayer times are not known. Never during a
     * prayer: a clock time that falls in one, or the end of a prayer that falls in the next, waits for its end.
     */
    fun slotAt(context: Context, day: Long, time: HifzWhen): Long? = when (time) {
        is HifzWhen.At -> clockAt(day, time.minute)
        is HifzWhen.After -> afterPrayerAt(context, day, time.prayer)
    }?.let(::hifzOutsidePrayers)

    /** [minute] of [day] by the phone's clock. */
    private fun clockAt(day: Long, minute: Int): Long =
        LocalDate.ofEpochDay(day).atTime(minute / 60, minute % 60).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** When the reminder after [prayer] on [day] comes: the end of its span; see [spansOf]. */
    private fun afterPrayerAt(context: Context, day: Long, prayer: Prayer): Long? =
        cachedSpans(context, day).firstOrNull { it.prayer == prayer }?.end

    /**
     * The prayers of the prayer day [day], each as long as a reminder keeps clear of it: to the end of its silence, the
     * silence as the reader set it even when the app does not silence that prayer, so it never rings during the prayer
     * or a Friday's khutba; to 20 minutes after the adhan when no window is known; never less than 10 minutes after it.
     * On Friday, Dhuhr is the Jumu'a; at a Tunisian place, the Eid prayers come too, as their silences set them. A
     * prayer whose time is not known has none.
     */
    private fun spansOf(context: Context, day: Long): List<HifzPrayerSpan> {
        val date = LocalDate.ofEpochDay(day)
        val friday = date.dayOfWeek == DayOfWeek.FRIDAY
        // The planner's windows of that prayer day (6.9): adhan (the Jumu'a time on a Friday) and ends as instants, so
        // a change of the clocks that day does not move the reminder.
        val windows = runCatching {
            com.tunisianprayertimes.SilenceScheduler.planner(
                context, com.tunisianprayertimes.PrayerTimesRepository.place(context),
                Prayer.entries.associateWith { PrefsManager.getSilenceRule(context, it) },
            ).windowsOf(date)
        }.getOrDefault(emptyList())
        fun span(prayer: Prayer, adhan: Long, window: SilenceWindow?) = HifzPrayerSpan(
            prayer,
            minOf(adhan, window?.start?.toEpochMilli() ?: adhan),
            maxOf(adhan + 10 * 60_000L, window?.end?.toEpochMilli()?.plus(2 * 60_000L) ?: (adhan + 20 * 60_000L)),
        )
        val five = HifzPrayers.mapNotNull { prayer ->
            val kind = when (prayer) {
                Prayer.FAJR -> DhikrTimeKind.FAJR
                Prayer.DHUHR -> DhikrTimeKind.DHUHR
                Prayer.ASR -> DhikrTimeKind.ASR
                Prayer.MAGHRIB -> DhikrTimeKind.MAGHRIB
                Prayer.ISHA -> DhikrTimeKind.ISHA
                else -> return@mapNotNull null
            }
            val jumua = friday && prayer == Prayer.DHUHR
            val window = windows.firstOrNull { it.prayer == if (jumua) Prayer.JOMOAA else prayer }
            val adhan = window?.adhan?.toEpochMilli()
                ?: (if (jumua) com.tunisianprayertimes.PrayerTimesRepository.jumuaTime(context, date)?.toEpochMilli() else null)
                ?: DhikrReminderScheduler.resolveTime(context, DhikrTime(kind), date)
                ?: return@mapNotNull null
            span(prayer, adhan, window)
        }
        val eid = windows.filter { it.prayer == Prayer.AID_FITR || it.prayer == Prayer.AID_ADHA }
            .map { span(it.prayer, it.adhan.toEpochMilli(), it) }
        return five + eid
    }

    /** Gives [HifzPrayerSpans] the reader's prayers; called once, as the app starts. */
    fun installPrayerSpans(context: Context) {
        val app = context.applicationContext
        HifzPrayerSpans.around = { at -> epochDay(at).let { day -> (day - 1..day + 1).flatMap { cachedSpans(app, it) } } }
        // A wait over a day ago is over whatever the prayers: none to read for it, as for most of the notebook. Not
        // sooner: a screen whose clock is a little behind must find the wait as a prayer ended it.
        HifzPrayerSpans.forWait = { from ->
            if (from + HifzFreshGapMs + 24 * 60 * 60_000L <= System.currentTimeMillis()) emptyList() else HifzPrayerSpans.around(from)
        }
    }

    /** [spansOf], kept a while: a fresh point's wait asks for them whenever it is shown. */
    private fun cachedSpans(context: Context, day: Long): List<HifzPrayerSpan> {
        val now = System.currentTimeMillis()
        val generation = synchronized(spanLock) {
            spanCache[day]?.takeIf { now - it.first in 0 until SPAN_CACHE_MS }?.let { return it.second }
            spanGeneration
        }
        val spans = spansOf(context, day)
        // Not kept over a forgetting meanwhile: they may be of the silences as they were.
        synchronized(spanLock) { if (spanGeneration == generation) spanCache[day] = now to spans }
        return spans
    }

    private fun forgetPrayerSpans() = synchronized(spanLock) {
        spanCache.clear()
        spanGeneration++
    }

    /** After a slip seen at [now], with the reminder off: «بعد 45 دقيقة», or «بعد صلاة العصر» when that one is over first. */
    fun waitPhrase(@Suppress("UNUSED_PARAMETER") now: Long): String = "بعد ${HifzFreshGapMs / 60_000L}${NB}دقيقة"

    /**
     * How a slip's one time of day comes back after its first reminder [first]: «كل يوم في الوقت نفسه»; a clock time
     * that falls in a prayer, «كل يوم بعد الظهر» when it falls in it the next day too, else as chosen, «كل يوم في الساعة
     * 13:10». More times are said as chosen.
     */
    private fun oftenPhrase(context: Context, shared: HifzTimes, first: HifzSlot): String {
        if (shared.count != 1 || first.time !in shared) return slipTimesPhrase(shared)
        val held = heldAfter(first) ?: return "كل يوم في الوقت نفسه"
        val next = slotAt(context, first.day + 1, first.time)?.let { heldAfter(HifzSlot(first.day + 1, first.time, it)) }
        return if (next == held) "كل يوم بعد ${prayerName(held, null)}" else slipTimesPhrase(shared)
    }

    /** The prayer [minute] falls in today, so that its reminder waits for the prayer's end; null when none. */
    fun prayerAt(minute: Int, now: Long = System.currentTimeMillis()): Prayer? {
        val at = clockAt(epochDay(now), minute)
        return HifzPrayerSpans.around(at).firstOrNull { at >= it.start && at < it.end }?.prayer
    }

    /** «صلاة الظهر», «صلاة الجمعة» on a Friday [day], «صلاة العيد». */
    fun prayerPhrase(prayer: Prayer, day: Long?): String = "صلاة ${prayerName(prayer, day)}"

    /** The prayer a clock time of [slot] fell in, when the reminder waits for its end: «بعد الظهر», not «الساعة 13:10». */
    fun heldAfter(slot: HifzSlot): Prayer? {
        val time = slot.time as? HifzWhen.At ?: return null
        if (slot.at <= clockAt(slot.day, time.minute)) return null
        return HifzPrayerSpans.around(slot.at).firstOrNull { it.end == slot.at }?.prayer
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
        return firstAllowedQuestion(context, settings, times ?: settings.times, now, hifzWaitEnd(now), fromDay)
    }

    /** Read-only projection of the scheduler's current restrictions, with the actual deferred time for display. */
    private fun firstAllowedQuestion(
        context: Context, settings: HifzSettings, times: HifzTimes, now: Long, eligibleAt: Long, fromDay: Long,
    ): HifzSlot? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // A pending explicit Later owns the next alert. Its outcome can change the budget; do not promise a
        // recurring point's next alert until that one-off request finishes.
        if (prefs.getLong("snooze_until", 0L) > 0L) return null
        val state = controlState(context, now)
        val allowed = hifzAlertNotBefore(now, settings, state.alertsToday, state.pausedToday, prefs.getLong("last_alert_at", 0L))
        val readingUntil = QuranReading.holdsUntil(context, now)
        var after = maxOf(now, eligibleAt - 1)
        if (state.pausedToday || state.alertsToday >= settings.dailyAlertLimit.coerceAtLeast(1)) after = maxOf(after, hifzNextDayStart(now) - 1)
        val shown = prefs.getLong("shown_at", 0L).let { if (it > now + 2 * 60_000L) now else it }
        var candidate = prefs.getString("armed", null)?.let(::decodeSlot)?.takeIf {
            it.day >= fromDay && it.time in times && it.at > shown && now - it.at <= HifzCatchUpMs &&
                !state.pausedToday && state.alertsToday < settings.dailyAlertLimit
        }
        repeat(64) {
            val next = candidate ?: times.firstAfter(after, fromDay) { day, time -> slotAt(context, day, time) } ?: return null
            val fireAt = hifzAllowedAlertAt(maxOf(now, next.at, allowed), settings, readingUntil, ::hifzOutsidePrayers)
            if (fireAt >= eligibleAt && hifzCanCatchUp(next.at, fireAt)) return displaySlot(next, fireAt)
            after = maxOf(after, next.at, fireAt - HifzCatchUpMs - 1)
            candidate = null
        }
        return null
    }

    /** A restriction may shift a selected prayer or clock slot; show that actual clock, keeping alarm identities elsewhere. */
    private fun displaySlot(selected: HifzSlot, fireAt: Long): HifzSlot {
        if (fireAt == selected.at) return selected
        val local = Instant.ofEpochMilli(fireAt).atZone(ZoneId.systemDefault())
        return HifzSlot(local.toLocalDate().toEpochDay(), HifzWhen.At(local.hour * 60 + local.minute), fireAt)
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
     * مساعدة 3 مرات. بعد ذلك تقلّ الأسئلة عنها شيئًا فشيئًا.» Counted as the review's own answer says it, «دون مساعدة»:
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
        oncePerDay: Boolean = times?.count == 1,
    ): String {
        val settings = HifzSettings.read(context)
        val today = epochDay(now)
        val daily = oncePerDay
        val until = untilPhrase(recalls)
        if (!settings.reminder) return "التذكير متوقّف: تجدها في «مراجعة اليوم» " +
            (if (tomorrow) "غدًا" else waitPhrase(now)) +
            "، $until. بعد ذلك تتباعد مواعيد مراجعتها شيئًا فشيئًا."
        val first = firstQuestion(
            context, now, maxOf(if (tomorrow) today + 1 else Long.MIN_VALUE, if (daily) askedDay?.plus(1) ?: Long.MIN_VALUE else Long.MIN_VALUE),
            times,
        )
        val shared = times ?: settings.times
        // One time of day is said once: «اليوم الساعة 15:35، ثم كل يوم في الوقت نفسه»; a prayer moving it, «اليوم بعد
        // الظهر، ثم كل يوم بعد الظهر».
        val often = if (first != null) oftenPhrase(context, shared, first) else slipTimesPhrase(shared)
        val rest = "$until. بعد ذلك تتباعد مواعيد مراجعتها شيئًا فشيئًا."
        first ?: return "تجد سؤالها في «مراجعة اليوم» ${if (tomorrow) "غدًا" else waitPhrase(now)}؛ نذكّرك حين يتاح موعد وفق إعدادات التذكير، $rest"
        return "نذكّرك بها ${slotLabel(first, today)}، ثم $often، $rest"
    }

    /** «إلى أن تتذكّرها دون مساعدة 3 مرات»; one answer needs no count: «إلى أن تتذكّرها دون مساعدة». */
    fun untilPhrase(recalls: Int): String = "إلى أن تتذكّرها " + (if (recalls == 1) "" else recallsPhrase(recalls) + " ") + "دون مساعدة"

    /**
     * The reminder that next asks [point], a fresh one, as [nextReminder] arms it: a while after its slip, its answer
     * and its verse last [seen]; and, asked once a day, not on a day a reminder already asked it. Null with the reminder
     * off, or for a point no longer fresh. Call off the main thread.
     */
    fun nextAsk(context: Context, point: HifzPoint, seen: Long?, now: Long = System.currentTimeMillis()): HifzSlot? {
        val settings = HifzSettings.read(context)
        if (!settings.reminder || point.fresh <= 0) return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val snooze = prefs.getLong("snooze_until", 0L)
        if (snooze > 0 && point.id in idSet(prefs.getString("snooze_ids", null))) {
            val state = controlState(context, now)
            val allowed = hifzAlertNotBefore(now, settings, state.alertsToday, state.pausedToday, prefs.getLong("last_alert_at", 0L), explicitLater = true)
            val fireAt = hifzAllowedAlertAt(maxOf(now, snooze, allowed), settings, QuranReading.holdsUntil(context, now), ::hifzOutsidePrayers)
            if (point.freshAskAt(seen) <= fireAt) return displaySlot(HifzSlot(epochDay(snooze), HifzWhen.At(0), -1L), fireAt)
            return null
        }
        val from = if (!point.daily) Long.MIN_VALUE
            else maxOf(point.dueDay, dailyAskedDay(context, point.id)?.plus(1) ?: Long.MIN_VALUE)
        return firstAllowedQuestion(context, settings, point.times ?: settings.times, now, point.freshDueAt(seen), from)
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
        return firstAllowedQuestion(context, settings, settings.times, now, now, day)?.let { slotLabel(it, epochDay(now)) }
    }

    /**
     * When a recorded point is asked next, for its fix, in the plan's own voice. A fresh one says its whole plan once:
     * «نذكّرك بها اليوم الساعة 15:35، ثم كل يوم في الوقت نفسه، إلى أن تتذكّرها دون مساعدة 3 مرات»; a spaced check,
     * «نسألك عنها الأحد بعد الفجر». A fresh point's answer seen, as in this fix, puts its question a while later; a check
     * whose verse was looked at today waits for the next day. No full stop: it is one of the fix's lines of facts. Call
     * off the main thread.
     */
    fun pointSentence(
        context: Context,
        point: HifzPoint,
        now: Long = System.currentTimeMillis(),
        /** False behind its question, «حان وقتُ السؤال عن …»: its answer is not in view, and its reminder still asks it. */
        answerShown: Boolean = true,
    ): String {
        val settings = HifzSettings.read(context)
        val today = epochDay(now)
        val learning = HifzLearning.read(context, today)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val alreadyAsked = if (prefs.contains("checks_asked")) readAsked(prefs, "checks_asked")[point.id] ?: Long.MIN_VALUE
            else prefs.getLong("reminded_day", -1L)
        val slotFrom = { day: Long -> firstAllowedQuestion(context, settings, settings.times, now, now, maxOf(day, alreadyAsked + 1)) }
        if (point.fresh > 0) {
            // Its answer is in view in its fix, as it is said: asked a while after; once a day, not before its day,
            // nor on a day a reminder already asked it.
            val seen = if (answerShown) maxOf(now, learning.seen[point.verse] ?: now) else learning.seen[point.verse]
            val until = untilPhrase(point.fresh)
            if (!settings.reminder) return "التذكير متوقّف: تجدها في «مراجعة اليوم» ${inReviewLabel(point.freshAskAt(seen), today)}، $until"
            val shared = point.times ?: settings.times
            val next = nextAsk(context, point, seen, now) ?: return "تجد سؤالها في «مراجعة اليوم» ${inReviewLabel(point.freshAskAt(seen), today)}؛ نذكّرك حين يتاح موعد وفق إعدادات التذكير، $until"
            val often = oftenPhrase(context, shared, next)
            return "نذكّرك بها ${slotLabel(next, today)}، ثم $often، $until"
        }
        // Its answer is in view in its fix: a check that counts is the next day's. Behind its question, still today's.
        val dueDay = if (point.dueDay <= today) (if (answerShown) today + 1 else today) else point.dueDay
        if (!settings.reminder) return "التذكير متوقّف: تجدها في «مراجعة اليوم» ${if (dueDay == today) "الآن" else dayLabel(dueDay, today)}"
        val first = slotFrom(dueDay) ?: return "تجد سؤالها في «مراجعة اليوم» ${if (dueDay <= today) "الآن" else dayLabel(dueDay, today)}؛ نذكّرك حين يتاح موعد وفق إعدادات التذكير"
        return "نسألك عنها ${slotLabel(first, today)}"
    }

    /**
     * When a fresh point answered or seen now is asked again: «اليوم بعد المغرب», «غدًا بعد الفجر»; «بعد 45 دقيقة» or
     * «بعد صلاة الظهر» with the reminder off, in «مراجعة اليوم». Call off the main thread.
     */
    fun freshAgainLabel(context: Context, now: Long = System.currentTimeMillis(), point: HifzPoint? = null): String =
        // At the point's own times; once a day, on another day.
        firstQuestion(context, now, if (point?.daily == true) epochDay(now) + 1 else Long.MIN_VALUE, point?.times)?.let { slotLabel(it, epochDay(now)) }
            ?: when {
                HifzSettings.read(context).reminder -> "في تذكير لاحق"
                point?.daily == true -> "في «مراجعة اليوم» غدًا"
                else -> "في «مراجعة اليوم» ${waitPhrase(now)}"
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
        is HifzWhen.After -> "بعد ${prayerName(time.prayer, day).let { if (it == "الجمعة") "صلاة $it" else it }}"
        is HifzWhen.At -> "الساعة$NB${clock(time.minute)}"
    }

    /** The name of [prayer]: «الفجر» … «العشاء». */
    fun prayerLabel(prayer: Prayer): String = prayerName(prayer, null)

    /**
     * «اليوم بعد العصر», «غدًا بعد الفجر», «الأحد بعد الجمعة», «اليوم الساعة 21:30»; a clock time that falls in a prayer,
     * «اليوم بعد الظهر».
     */
    // A prayer day's Isha after midnight is still «اليوم» to the reader, never yesterday's weekday.
    fun slotLabel(slot: HifzSlot, today: Long): String = "${dayLabel(maxOf(slot.day, today), today)} " +
        (heldAfter(slot)?.let { if (it == Prayer.AID_FITR || it == Prayer.AID_ADHA) "بعد ${prayerPhrase(it, slot.day)}" else "بعد ${prayerName(it, slot.day).let { n -> if (n == "الجمعة") "صلاة $n" else n }}" }
            ?: timeLabel(slot.time, slot.day))

    /**
     * «اليوم», «غدًا», the day of the week, «الخميس القادم» the next week, where the name is today's again, and
     * with the date further off: «الجمعة 23 أكتوبر».
     */
    fun dayLabel(day: Long, today: Long): String {
        if (day - today == 0L) return "اليوم"
        if (day - today == 1L) return "غدًا"
        val dayOfWeek = LocalDate.ofEpochDay(day).dayOfWeek
        val name = when (dayOfWeek) {
            DayOfWeek.MONDAY -> "الاثنين"
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
        Prayer.JOMOAA -> "الجمعة"
        Prayer.AID_FITR, Prayer.AID_ADHA -> "العيد"
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
        !appNotificationsAllowed(context) -> "الإشعارات متوقّفة: لن يصلك أي تذكير."
        blockedChannel(context, channels) == CHANNEL_ID -> "إشعارات «المراجعة» متوقّفة في إعدادات الهاتف."
        else -> null
    }

    /**
     * Posts eligible work from the selected prayer-day slots, within the catch-up window and shared alert policy.
     */
    internal fun deliver(context: Context, day: Long, time: HifzWhen?, now: Long = System.currentTimeMillis()) {
        synchronized(deliveryLock) { deliverLocked(context, day, time, now) }
    }

    private fun deliverLocked(context: Context, day: Long, time: HifzWhen?, now: Long) {
        val app = context.applicationContext
        HifzRepository.load(app)
        forgetPrayerSpans()
        HifzPrayerSpans.around(now)
        val settings = HifzSettings.read(app)
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (time != null && settings.reminder) {
            val at = slotAt(app, day, time)
            if (prefs.getString("shown_slot", null) == "$day|${time.key}") {
                refreshLocked(app, now)
                return
            }
            if (at != null && at > now) {
                prefs.edit().remove("armed").apply()
                refreshLocked(app, now)
                return
            }
            val state = controlState(app, now)
            val allowed = hifzAllowedAlertAt(
                hifzAlertNotBefore(now, settings, state.alertsToday, state.pausedToday, prefs.getLong("last_alert_at", 0L)),
                settings, QuranReading.holdsUntil(app, now), ::hifzOutsidePrayers,
            )
            if (allowed > now || prefs.getLong("snooze_until", 0L) > 0L) {
                refreshLocked(app, now)
                return
            }
            // Retire the received slot before the refresh can see it again; the selected slot remains distinct
            // from the eventual fire instant, and every coalesced slot has its own catch-up limit.
            prefs.edit().putLong("shown_at", now).putString("shown_slot", "$day|${time.key}").commit()
            val ringing = timesComing(app, settings, HifzRepository.points.value, day, time, at, now)
                .mapValues { (d, times) -> times.filterTo(mutableSetOf()) { t ->
                    slotAt(app, d, t)?.let { hifzCanCatchUp(it, now) } == true
                } }.filterValues { it.isNotEmpty() }
            if (ringing.isNotEmpty()) remind(app, ringing, now)
        }
        refreshLocked(app, now)
    }

    internal fun deliverSnoozed(context: Context, now: Long = System.currentTimeMillis()) = synchronized(deliveryLock) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val until = prefs.getLong("snooze_until", 0L)
        val ids = idSet(prefs.getString("snooze_ids", null))
        if (until <= 0 || ids.isEmpty()) return@synchronized
        val settings = HifzSettings.read(app)
        val state = controlState(app, now)
        val allowed = hifzAllowedAlertAt(maxOf(until,
            hifzAlertNotBefore(now, settings, state.alertsToday, state.pausedToday, prefs.getLong("last_alert_at", 0L), explicitLater = true)),
            settings, QuranReading.holdsUntil(app, now), ::hifzOutsidePrayers)
        if (allowed > now) {
            refreshLocked(app, now)
            return@synchronized
        }
        prefs.edit().remove("snooze_until").remove("snooze_ids").apply()
        if (settings.reminder && appNotificationsAllowed(app) && blockedChannel(app, listOf(CHANNEL_ID)) == null) {
            HifzRepository.load(app)
            val today = epochDay(now)
            val learning = HifzLearning.read(app, today)
            val due = HifzRepository.points.value.filter { it.id in ids }.askable(today, now, learning.seen, learning.exposed)
            val batch = due.reviewBatch(today, now, HifzRoundSize)
            if (batch.isNotEmpty() && runCatching { post(app, batch, due.size) }.getOrDefault(false)) {
                countAlert(app, now)
                saveAsked(prefs, "checks_asked", checksAsked(prefs, HifzRepository.points.value), batch.filter { it.fresh == 0 }.associate { it.id to today }, today)
                saveAsked(prefs, "daily_asked", dailyAsked(prefs), batch.filter { it.fresh > 0 && it.daily }.associate { it.id to today }, today)
            }
        }
        refreshLocked(app, now)
    }

    /**
     * [time] of the prayer day [day], with every other time whose reminder comes from [at] to [now], by prayer day: a
     * clock time a prayer moved to its end, as the reminder after that prayer does, or one a late alarm reached; the day
     * before or after too, across midnight. They ring once, together: rung apart, the second would come after the first
     * was shown, and be passed over.
     */
    internal fun timesComing(
        context: Context, settings: HifzSettings, points: List<HifzPoint>, day: Long, time: HifzWhen, at: Long?, now: Long,
    ): Map<Long, Set<HifzWhen>> {
        if (at == null) return mapOf(day to setOf(time))
        val all = (settings.times.times + points.filter { it.fresh > 0 }.flatMap { it.times?.times.orEmpty() }).toSet()
        val coming = at..maxOf(at, now)
        // From the day before to the day after the one it rings on: a phone off a day and more brings what came on time.
        val last = (epochDay(maxOf(at, now)) + 1).coerceIn(day + 1, day + 7)
        return (day - 1..last).associateWith { d ->
            all.filterTo(if (d == day) mutableSetOf(time) else mutableSetOf()) { t -> slotAt(context, d, t)?.let { it in coming } == true }
        }.filterValues { it.isNotEmpty() }
    }

    /**
     * Asks about what is due at [now], the reminder at [slots], times by prayer day: when a fresh point whose times hold
     * one of them is, or when spaced checks are, at the settings' times, of the latest such day, and no reminder of it
     * brought them yet. Then the fresh points and the checks together, as the review they open asks them. Isha's
     * reminder after midnight is its day's, so it never takes the next day's checks, unless that day's own rings with it.
     */
    private fun remind(context: Context, slots: Map<Long, Set<HifzWhen>>, now: Long) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val settings = HifzSettings.read(context)
        val today = epochDay(now)
        val learning = HifzLearning.read(context, today)
        val due = HifzRepository.points.value.askable(today, now, learning.seen, learning.exposed)
        val checksDay = slots.filterValues { times -> times.any { it in settings.times } }.keys.maxOrNull()
        val checksAsked = checksAsked(prefs, HifzRepository.points.value)
        val checks = if (checksDay != null) {
            due.filter { it.fresh == 0 && it.dueDay <= checksDay && (checksAsked[it.id] ?: Long.MIN_VALUE) < checksDay }
        } else emptyList()
        // A slip rings at its own times, for the latest prayer day that holds one. One asked once a day, already asked
        // by a reminder of that day, waits in «مراجعة اليوم» without sounding again.
        val asked = dailyAsked(prefs)
        val freshDay = due.filter { it.fresh > 0 }.mapNotNull { point ->
            val own = point.times ?: settings.times
            slots.filter { (d, times) -> times.any { it in own } && (!point.daily || (d >= point.dueDay && (asked[point.id] ?: Long.MIN_VALUE) < d)) }
                .keys.maxOrNull()?.let { point to it }
        }.toMap()
        val fresh = freshDay.keys.toList()
        if (fresh.isEmpty() && checks.isEmpty()) return
        // A reminder that cannot reach the reader leaves the day's checks to a later one.
        if (!appNotificationsAllowed(context) || blockedChannel(context, listOf(CHANNEL_ID)) != null) return
        // It asks what it rang for: the slips of this time and, at the settings' times, the day's checks; a slip set
        // for other times waits for its own.
        val slot = fresh + checks
        val batch = slot.reviewBatch(today, now, HifzRoundSize)
        val posted = runCatching { post(context, batch, slot.size) }.getOrDefault(false)
        if (!posted) return
        countAlert(context, now)
        saveAsked(prefs, "checks_asked", checksAsked, batch.filter { it.fresh == 0 }.associate { it.id to checksDay!! }, today)
        // Only those it shows count as asked today: one left for another round still rings at its next time.
        val dailyNow = batch.filter { it.daily && it.fresh > 0 }.mapNotNull { point -> freshDay[point]?.let { point.id to it } }.toMap()
        saveAsked(prefs, "daily_asked", asked, dailyNow, today)
    }

    /** The prayer day each point asked once a day was last asked by a reminder; see [remind]. */
    private fun dailyAsked(prefs: android.content.SharedPreferences): Map<Long, Long> = readAsked(prefs, "daily_asked")

    private fun readAsked(prefs: android.content.SharedPreferences, key: String): Map<Long, Long> =
        prefs.getString(key, null).orEmpty().split(',').mapNotNull { row ->
            val parts = row.split(':')
            val id = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val day = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            id to day
        }.toMap()

    /** Migrate the previous all-checks day once, preserving its existing suppression on upgrade. */
    private fun checksAsked(prefs: android.content.SharedPreferences, points: List<HifzPoint>): Map<Long, Long> {
        if (prefs.contains("checks_asked")) return readAsked(prefs, "checks_asked")
        val legacy = prefs.getLong("reminded_day", -1L)
        val migrated = if (legacy >= 0) points.filter { it.fresh == 0 }.associate { it.id to legacy } else emptyMap()
        prefs.edit().putString("checks_asked", migrated.entries.joinToString(",") { "${it.key}:${it.value}" })
            .remove("reminded_day").apply()
        return migrated
    }

    private fun saveAsked(prefs: android.content.SharedPreferences, key: String, previous: Map<Long, Long>, next: Map<Long, Long>, today: Long) {
        if (next.isEmpty()) return
        prefs.edit().putString(key, (previous.filterValues { it >= today - 2 && it <= today } + next)
            .entries.joinToString(",") { "${it.key}:${it.value}" }).apply()
    }

    /**
     * [due] the review's points, [dueAll] every point to ask now, also those beyond one review's. [quiet]: the reminder
     * already shown, asking fewer points now, without sounding again.
     */
    private fun post(context: Context, due: List<HifzPoint>, dueAll: Int, quiet: Boolean = false): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
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
        // The verses by surah, in order, as labels short enough to be read whole: «البقرة · الآيات 248، 250، 254؛ الملك ·
        // الآية 2 وموضعان آخران». What each asks is in the lines below, as the review asks it.
        val summary = due.groupBy { it.verse.surah }.entries.joinToString("؛ ") { (surah, points) ->
            val ayahs = points.map { it.verse.ayah }.distinct().sorted()
            name(surah) + (if (ayahs.size == 1) " · الآية " else " · الآيات ") + ayahs.joinToString("، ")
        } + if (more > 0) " و" + otherPoints(more) else ""
        // Each line is the question the review asks, its surah named, one sentence: never the answer nor where the verse
        // slipped, which read here would be no check at all. Nor is there anything to listen to or to mark as known: the
        // answer is seen once it is recalled, in the review, with the verse covered.
        val lines = due.mapNotNull { point ->
            catalog.verse(point.verse) ?: return@mapNotNull null
            hifzQuestionAbout(catalog, point)
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
        val settings = HifzSettings.read(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val generation = prefs.getLong("posted_generation", 0L) + 1
        val alerts = controlState(context).alertsToday + if (quiet) 0 else 1
        if (alerts < settings.dailyAlertLimit) builder.addAction(Notification.Action.Builder(null, "لاحقًا بعد 30 دقيقة", actionIntent(context, ACTION_LATER, generation)).build())
        builder.addAction(Notification.Action.Builder(null, "إيقاف تذكير اليوم", actionIntent(context, ACTION_PAUSE, generation)).build())
        manager.notify(NOTIFICATION_ID, builder.build())
        prefs.edit().putString("posted", due.joinToString(",") { it.id.toString() }).putLong("posted_generation", generation).apply()
        return true
    }

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

    private fun actionIntent(context: Context, action: String, generation: Long): PendingIntent = PendingIntent.getBroadcast(
        context, generation.toInt(), Intent(context, HifzReminderReceiver::class.java).setAction(action).putExtra(EXTRA_GENERATION, generation),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** One physical alarm; recurring slots retain their logical identity, Later has an explicit flag. */
    private fun remindIntent(context: Context, slot: HifzSlot?, snooze: Boolean = false): PendingIntent = PendingIntent.getBroadcast(
        context, NOTIFICATION_ID,
        Intent(context, HifzReminderReceiver::class.java).setAction(ACTION_REMIND).apply {
            putExtra(EXTRA_SNOOZE, snooze)
            if (slot != null) putExtra(EXTRA_DAY, slot.day).putExtra(EXTRA_TIME, slot.time.key)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    internal fun receive(context: Context, intent: Intent): Unit = synchronized(deliveryLock) {
        if (intent.action == ACTION_LATER || intent.action == ACTION_PAUSE) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (postedIds(context).isEmpty() || intent.getLongExtra(EXTRA_GENERATION, -1L) != prefs.getLong("posted_generation", 0L)) return@synchronized
        }
        when (intent.action) {
            ACTION_REMIND -> if (intent.getBooleanExtra(EXTRA_SNOOZE, false)) deliverSnoozed(context)
                else deliver(context, intent.getLongExtra(EXTRA_DAY, todayEpochDay()), HifzWhen.of(intent.getStringExtra(EXTRA_TIME)))
            ACTION_LATER -> later(context)
            ACTION_PAUSE -> pauseToday(context)
        }
    }
}

class HifzReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(HifzReminder.ACTION_REMIND, HifzReminder.ACTION_LATER, HifzReminder.ACTION_PAUSE)) return
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

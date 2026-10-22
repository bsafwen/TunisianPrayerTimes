package com.tunisianprayertimes.fasting

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
import com.tunisianprayertimes.FastingBlock
import com.tunisianprayertimes.FastingDays
import com.tunisianprayertimes.FastingOccasion
import com.tunisianprayertimes.FastingVerdict
import com.tunisianprayertimes.MainActivity
import com.tunisianprayertimes.MainTabNavigation
import com.tunisianprayertimes.PlaceResolution
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerTimesRepository
import com.tunisianprayertimes.PrayerWakeConfig
import com.tunisianprayertimes.R
import com.tunisianprayertimes.RamadanOverrideChecker
import com.tunisianprayertimes.ScheduleRefreshCoordinator
import com.tunisianprayertimes.WakeAlarmComputer
import com.tunisianprayertimes.WakeRepeatMode
import com.tunisianprayertimes.adhkar.DhikrReminderScheduler
import com.tunisianprayertimes.engine.TimeEvent
import com.tunisianprayertimes.place.PrayerPlace
import com.tunisianprayertimes.quran.hifz.HifzPrayerSpans
import com.tunisianprayertimes.quran.hifz.hifzOutsidePrayers
import com.tunisianprayertimes.ui.FajrAlarmPersistence
import com.tunisianprayertimes.ui.dayMonth
import com.tunisianprayertimes.ui.fajrSuggestionAlarm
import com.tunisianprayertimes.ui.ltr
import com.tunisianprayertimes.wake.PrayerWakeRepository
import com.tunisianprayertimes.wake.WakeAlarmScheduler
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * The sunnah-fast reminder (Monday and Thursday, Arafa, Tasu'a and Ashura, the white days, six days of Shawwal),
 * as the majority of scholars hold these fasts: one notification on the eve, after Maghrib, because the Maliki
 * school intends every fast, a voluntary one too, at night between Maghrib and Fajr (Mukhtasar Khalil; al-Dasuqi
 * 1/520), which suits every school. Off by default; no record is kept of who fasted.
 */
object FastingReminder {
    const val CHANNEL_ID = "fasting"
    const val SUHOOR_ALARM_ID = "fasting_suhoor"
    val SUHOOR_MINUTES = listOf(20, 30, 45, 60)
    private const val PREFS = "fasting_reminder"
    private const val NOTIFICATION_ID = 740201
    private const val ACTION_REMIND = "com.tunisianprayertimes.fasting.REMIND"
    private const val ACTION_PAUSE = "com.tunisianprayertimes.fasting.PAUSE_WEEK"
    private const val ACTION_SUHOOR = "com.tunisianprayertimes.fasting.SUHOOR"
    private const val REPAIR_WORK = "fasting_repair"
    /** After the Maghrib prayer: its silence, or this long after the adhan, whichever ends later. */
    private const val AFTER_MAGHRIB_MINUTES = 30L
    /** From this local hour the reminder comes without a sound. */
    private const val QUIET_HOUR = 23
    /** An own alarm this long before Fajr leaves time to eat: suhoor was some fifty verses before the adhan (Bukhari 1921). */
    private const val SUHOOR_ROOM_MINUTES = 15L
    /** A suhoor ring closer than this is no longer offered. */
    private const val SUHOOR_ACTION_MIN_LEAD_MINUTES = 5L
    private const val NONE = Long.MIN_VALUE

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Refreshes off the caller's thread: it reads prayer days and the alarm store. */
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** One refresh at a time: a reminder that is due is posted once, whoever notices it first. */
    private val lock = Any()

    /** After a change made in the app: a reminder already due then comes without a sound. */
    fun refreshLater(context: Context) {
        val app = context.applicationContext
        background.launch {
            runCatching { refresh(app, fromApp = true) }.onFailure { Log.w("FastingReminder", "Could not refresh", it) }
        }
    }

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean("enabled", false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("enabled", enabled).remove("paused_until_day").apply()
        refreshLater(context)
    }

    /** The fasts a reader can be reminded of; Tasu'a goes with Ashura, as the Prophet meant to fast both (Muslim 1134). */
    enum class Fast(internal val key: String, val label: String) {
        MONDAY("monday", "الاثنين"),
        THURSDAY("thursday", "الخميس"),
        ARAFA("arafa", "يوم عرفة"),
        ASHURA("ashura", "تاسوعاء وعاشوراء"),
        WHITE_DAYS("white_days", "الأيام البيض"),
        SHAWWAL("shawwal", "الستّ من شوال"),
    }

    fun fasts(context: Context): Set<Fast> = Fast.entries.filterTo(mutableSetOf()) { prefs(context).getBoolean(it.key, true) }

    /** One fast always stays on: turning them all off is the switch's job. */
    fun setFast(context: Context, fast: Fast, on: Boolean) {
        if (!on && fasts(context) == setOf(fast)) return
        prefs(context).edit().putBoolean(fast.key, on).apply()
        refreshLater(context)
    }

    /** [occasion]'s own chip is on. */
    private fun occasionChosen(occasion: FastingOccasion?, fasts: Set<Fast>): Boolean = when (occasion) {
        FastingOccasion.ARAFA -> Fast.ARAFA in fasts
        FastingOccasion.TASUA, FastingOccasion.ASHURA -> Fast.ASHURA in fasts
        FastingOccasion.WHITE_DAYS -> Fast.WHITE_DAYS in fasts
        FastingOccasion.SHAWWAL -> Fast.SHAWWAL in fasts
        null -> false
    }

    /** [day] is one of [fasts]: by its Hijri [occasion], or by its weekday. */
    private fun chosen(day: LocalDate, occasion: FastingOccasion?, fasts: Set<Fast>): Boolean =
        occasionChosen(occasion, fasts) || (day.dayOfWeek == DayOfWeek.MONDAY && Fast.MONDAY in fasts) ||
            (day.dayOfWeek == DayOfWeek.THURSDAY && Fast.THURSDAY in fasts)

    fun isChosen(context: Context, eve: Eve): Boolean = chosen(eve.fastDay, eve.occasion, fasts(context))

    /** The first eve day that reminds again after a pause, as an epoch day. */
    private fun pausedUntilDay(context: Context): Long = prefs(context).getLong("paused_until_day", 0L)

    fun isPaused(context: Context, now: Instant = Instant.now()): Boolean =
        now.atZone(zone(context)).toLocalDate().toEpochDay() < pausedUntilDay(context)

    /**
     * A week without reminders, no reason asked: illness, travel or a period are nobody's business. Counted in
     * eves, so the same tap skips the same days whatever the season; reads the prayer days, so not on the main thread.
     */
    fun pauseWeek(context: Context, now: Instant = Instant.now()) {
        val today = now.atZone(zone(context)).toLocalDate()
        // Before Fajr the night still belongs to yesterday's eve.
        val fajr = PrayerTimesRepository.prayerDay(context, today)?.get(TimeEvent.FAJR)?.at
        val from = if (fajr != null && now < fajr) today.minusDays(1) else today
        prefs(context).edit().putLong("paused_until_day", from.plusDays(7).toEpochDay()).apply()
        cancelNotification(context)
        refresh(context, now)
    }

    fun resume(context: Context) {
        prefs(context).edit().remove("paused_until_day").apply()
        refreshLater(context)
    }

    // ---- The days ----

    private fun place(context: Context) = PrayerTimesRepository.place(context)
    fun zone(context: Context): ZoneId = place(context).zone
    private fun abroad(context: Context) = place(context) !is PrayerPlace.Inm

    /** One fast day and its eve: when the reminder comes, that day's Fajr, and whether it comes at all. */
    data class Eve(
        val fastDay: LocalDate,
        val remindAt: Instant?,
        val fajr: Instant?,
        val verdict: FastingVerdict,
        val paused: Boolean,
        /** Arafa, Tasu'a, Ashura, a white day or a day of Shawwal; null for a plain Monday or Thursday. */
        val occasion: FastingOccasion? = null,
        /** The day of the Hijri month, which names a white day or a day of Shawwal. */
        val hijriDay: Int = 0,
    ) {
        val eveDay: LocalDate get() = fastDay.minusDays(1)
        val reminds: Boolean get() = verdict.allowed && !paused && remindAt != null && fajr != null
    }

    fun eve(context: Context, fastDay: LocalDate): Eve {
        val maghrib = PrayerTimesRepository.prayerDay(context, fastDay.minusDays(1))?.get(TimeEvent.MAGHRIB)?.at
        val remindAt = maghrib?.let { at ->
            val adhan = at.toEpochMilli()
            val silenceEnd = HifzPrayerSpans.around(adhan)
                .filter { it.prayer == Prayer.MAGHRIB && adhan >= it.start && adhan < it.end }
                .maxOfOrNull { it.end } ?: adhan
            Instant.ofEpochMilli(hifzOutsidePrayers(maxOf(adhan + AFTER_MAGHRIB_MINUTES * 60_000L, silenceEnd)))
        }
        val fajr = PrayerTimesRepository.prayerDay(context, fastDay)?.get(TimeEvent.FAJR)?.at
        val paused = fastDay.minusDays(1).toEpochDay() < pausedUntilDay(context)
        val occasion = FastingDays.occasion(fastDay, abroad(context))
        val hijriDay = if (occasion == FastingOccasion.WHITE_DAYS || occasion == FastingOccasion.SHAWWAL) FastingDays.hijriDay(fastDay) else 0
        return Eve(fastDay, remindAt, fajr, FastingDays.verdict(fastDay, abroad(context)), paused, occasion, hijriDay)
    }

    /** The coming days of [fasts], from the first whose Fajr has not come yet. */
    fun upcoming(context: Context, now: Instant = Instant.now(), fasts: Set<Fast> = fasts(context), count: Int = 6): List<Eve> {
        if (fasts.isEmpty()) return emptyList()
        val today = now.atZone(zone(context)).toLocalDate()
        // A year ahead: with Arafa and Ashura alone, the next one may be months away.
        val horizon = 400
        val occasions = FastingDays.occasions(today, horizon, abroad(context))
        return generateSequence(today) { it.plusDays(1) }.take(horizon)
            .filter { chosen(it, occasions[it], fasts) }
            .map { eve(context, it) }
            .filter { (it.fajr ?: Instant.MAX) > now }
            .take(count).toList()
    }

    /** The eve going on now, from the reminder's time to Fajr, for either day, when that day may be fasted. */
    fun tonight(context: Context, now: Instant = Instant.now()): Eve? =
        upcoming(context, now, Fast.entries.toSet(), 1).firstOrNull()
            ?.takeIf { it.verdict.allowed && it.remindAt != null && it.fajr != null && now >= it.remindAt }

    /**
     * The phone's clock and the saved place disagree: a traveller who has not set the new place yet. Algeria and
     * Morocco keep Tunisia's offset, so Tunisia is also told apart by the phone's zone: a place in Tunisia with
     * the phone elsewhere, or a place abroad with the phone back home. Two places abroad with one offset are not
     * compared, since zone and country codes disagree in places (Jerusalem, Western Sahara).
     */
    fun placeElsewhere(context: Context, now: Instant = Instant.now()): Boolean {
        val phone = ZoneId.systemDefault()
        val place = place(context)
        if (phone.rules.getOffset(now) != place.zone.rules.getOffset(now)) return true
        val region = PlaceResolution.zoneRegion(phone.id)?.takeIf { it != "001" } ?: return false
        val placeInTunisia = place is PrayerPlace.Inm || (place as? PrayerPlace.Coordinates)?.countryCode == "TN"
        return placeInTunisia != (region == "TN")
    }

    // ---- Words ----

    fun dayName(day: DayOfWeek): String = when (day) {
        DayOfWeek.MONDAY -> "الاثنين"
        DayOfWeek.TUESDAY -> "الثلاثاء"
        DayOfWeek.WEDNESDAY -> "الأربعاء"
        DayOfWeek.THURSDAY -> "الخميس"
        DayOfWeek.FRIDAY -> "الجمعة"
        DayOfWeek.SATURDAY -> "السبت"
        DayOfWeek.SUNDAY -> "الأحد"
    }

    /** «الاثنين», or the occasion: «يوم عرفة», «عاشوراء», «أول الأيام البيض», «الثاني من شوال». */
    fun fastName(eve: Eve): String = when (eve.occasion) {
        FastingOccasion.ARAFA -> "يوم عرفة"
        FastingOccasion.TASUA -> "تاسوعاء"
        FastingOccasion.ASHURA -> "عاشوراء"
        FastingOccasion.WHITE_DAYS -> when (eve.hijriDay) {
            13 -> "أول الأيام البيض"
            14 -> "ثاني الأيام البيض"
            else -> "آخر الأيام البيض"
        }
        FastingOccasion.SHAWWAL -> SHAWWAL_ORDINALS.getOrElse(eve.hijriDay - 2) { "يوم" } + " من شوال"
        null -> dayName(eve.fastDay.dayOfWeek)
    }

    private val SHAWWAL_ORDINALS = listOf("الثاني", "الثالث", "الرابع", "الخامس", "السادس", "السابع", "الثامن")

    private val clock = DateTimeFormatter.ofPattern("HH:mm")
    fun clockOf(context: Context, at: Instant): String = ltr(clock.format(at.atZone(zone(context))))

    /** «الليلة», «مساء الغد», or «مساء الأحد 25 أكتوبر»; after midnight the eve in progress is still «الليلة». */
    fun eveWords(context: Context, eve: Eve, now: Instant): String {
        val today = now.atZone(zone(context)).toLocalDate()
        return when (eve.eveDay) {
            today, today.minusDays(1) -> "الليلة"
            today.plusDays(1) -> "مساء الغد"
            else -> "مساء " + dayName(eve.eveDay.dayOfWeek) + " " + eve.eveDay.dayMonth()
        }
    }

    /** The card's «غدًا الاثنين، ويُستحبّ صيامه», or «اليوم …» once past midnight; Arafa is for whoever is not on Hajj. */
    fun title(context: Context, eve: Eve, now: Instant): String {
        val word = if (now.atZone(zone(context)).toLocalDate() == eve.fastDay) "اليوم" else "غدًا"
        return when (eve.occasion) {
            FastingOccasion.ARAFA -> "$word ${fastName(eve)}، ويُستحبّ صيامه لغير الحاج"
            // «اليوم الثالث» alone reads as "the third day".
            FastingOccasion.SHAWWAL -> "${if (word == "اليوم") "اليوم هو" else word} ${fastName(eve)}، ويُستحبّ صيام ستّ منه"
            else -> "$word ${fastName(eve)}، ويُستحبّ صيامه"
        }
    }

    /** The notification's title, true on both sides of midnight. */
    private fun notificationTitle(eve: Eve): String = when (eve.occasion) {
        FastingOccasion.ARAFA -> "يُستحبّ لغير الحاج صيام يوم عرفة"
        FastingOccasion.SHAWWAL -> "يُستحبّ صيام ستّ من شوال"
        else -> "يُستحبّ صيام ${fastName(eve)}"
    }

    /** Why [eve] gets no reminder. */
    fun blockLine(context: Context, eve: Eve, now: Instant): String? {
        val verdict = eve.verdict
        val block = verdict.block ?: return null
        // The fast is named, the day by its weekday: Arafa itself is never the Eid, the day counted as Arafa may be.
        val about = if (eve.occasion != null) " بصيام ${fastName(eve)}" else ""
        val fast = dayName(eve.fastDay.dayOfWeek)
        val why = when {
            !verdict.margin -> when (block) {
                FastingBlock.RAMADAN -> return "لا نذكّرك في رمضان، فصيامه فرض."
                FastingBlock.EID_FITR -> "ف$fast عيد الفطر ولا يُصام."
                FastingBlock.EID_ADHA -> "ف$fast عيد الأضحى ولا يُصام."
                FastingBlock.TASHRIQ -> "ف$fast من أيام التشريق ولا يُصام."
            }
            verdict.abroad -> when (block) {
                FastingBlock.RAMADAN -> "فقد يكون $fast أول رمضان في البلد الذي أنت فيه."
                FastingBlock.TASHRIQ -> "فقد يكون $fast من أيام التشريق في البلد الذي أنت فيه."
                else -> "فقد يكون $fast يوم عيد في البلد الذي أنت فيه."
            }
            else -> when (block) {
                FastingBlock.RAMADAN -> "فقد يكون $fast أول رمضان، ولم يُعلَن موعده بعد."
                FastingBlock.TASHRIQ -> "فقد يكون $fast من أيام التشريق، ولم يُعلَن موعد العيد بعد."
                else -> "فقد يكون $fast يوم العيد، ولم يُعلَن موعده بعد."
            }
        }
        return "لن نذكّرك ${eveWords(context, eve, now)}$about، $why"
    }

    /** What the card says: the next reminder, and why an earlier eve has none. */
    fun statusLines(context: Context, now: Instant = Instant.now()): List<String> {
        if (!isEnabled(context)) return listOf("نذكّرك بعد صلاة المغرب في الليلة التي قبل يوم الصيام.")
        val eves = upcoming(context, now, count = 12)
        val today = now.atZone(zone(context)).toLocalDate()
        // Held only by a month not announced yet, with days left for the announcement: not a refusal.
        fun pending(eve: Eve) = eve.verdict.margin && !eve.verdict.abroad && eve.eveDay > today.plusDays(1)
        val next = eves.firstOrNull { it.reminds }
        val before = if (next == null) eves else eves.takeWhile { it !== next }
        val lines = mutableListOf<String>()
        // A Hijri-dated fast next to a month not announced yet waits for the announcement, then comes on its
        // right day (Arafa some eight days after the start of Dhul Hijja); a weekday may become the forbidden day.
        val chosenFasts = fasts(context)
        before.firstOrNull { occasionChosen(it.occasion, chosenFasts) && pending(it) }?.let { held ->
            val announcement = when (held.verdict.block) {
                FastingBlock.RAMADAN -> "أول رمضان"
                FastingBlock.EID_FITR -> "عيد الفطر"
                else -> "أول ذي الحجة"
            }
            lines += "سنذكّرك بصيام ${fastName(held)} بعد أن يُعلَن $announcement."
        }
        if (next != null) lines += when {
            next.remindAt != null && now >= next.remindAt -> title(context, next, now) + "."
            eves.first().paused ->
                "أوقفنا التذكير أسبوعًا، وسنذكّرك من جديد ${eveWords(context, next, now)} بعد صلاة المغرب بصيام ${fastName(next)}."
            else -> "نذكّرك ${eveWords(context, next, now)} بعد صلاة المغرب بصيام ${fastName(next)}."
        }
        // Past midnight the blocked day has begun: its evening is over, so there is nothing to explain.
        before.firstOrNull { !it.verdict.allowed && it.fastDay != today && !pending(it) }?.let { blocked ->
            blockLine(context, blocked, now)?.let(lines::add)
        }
        if (lines.isEmpty()) lines += "لن نذكّرك في الأسابيع القادمة."
        if (placeElsewhere(context, now)) lines += "حدّد مكانك الحالي ليصلك التذكير بعد المغرب عندك."
        return lines
    }

    // ---- The notification ----

    private fun channel(context: Context): NotificationManager? {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return null
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "تذكير الصيام", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "نذكّرك بعد صلاة المغرب بصيام التطوع"
        })
        return manager
    }

    private fun broadcast(context: Context, action: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
        context, code, Intent(context, FastingReminderReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** The «عباداتي» tab, opening the card's sheet or the suhoor questions. */
    private fun openWorship(context: Context, action: String, code: Int): PendingIntent = PendingIntent.getActivity(
        context, code,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainTabNavigation.EXTRA_DESTINATION, MainTabNavigation.DESTINATION_WORSHIP)
            .putExtra(MainTabNavigation.EXTRA_WORSHIP_ACTION, action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun cancelNotification(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        prefs(context).edit().remove("posted_fast_day").apply()
    }

    /**
     * Posts the eve's reminder, or, with [suhoorAt], replaces it by the alarm's time. The title names the day
     * without «غدًا», so it stays true past midnight. Text ends on a word, never on a time or a full stop: a phone
     * in another language lays it out left to right.
     */
    fun post(context: Context, eve: Eve, now: Instant = Instant.now(), suhoorAt: Instant? = null, quiet: Boolean = false) {
        val fajr = eve.fajr ?: return
        if (now >= fajr) return
        val manager = channel(context) ?: return
        val local = now.atZone(zone(context))
        val silent = quiet || suhoorAt != null || local.toLocalTime() >= LocalTime.of(QUIET_HOUR, 0) ||
            local.toLocalDate() == eve.fastDay
        val own = if (suhoorAt == null) ownAlarm(context, eve) else null
        val deadline = "إن شئت الصيام فانوِه قبل أذان الفجر في ${clockOf(context, fajr)} صباحًا"
        val text = when {
            suhoorAt != null -> {
                // Moved in the Alarms tab, the alarm may ring at any minute: the count agrees with its noun, or is left out.
                val minutes = Duration.between(suhoorAt, fajr).toMinutes()
                val before = when {
                    minutes >= 11 -> "، قبل أذان الفجر بـ$minutes دقيقة"
                    minutes >= 3 -> "، قبل أذان الفجر بـ$minutes دقائق"
                    else -> ""
                }
                "سيرنّ منبه السحور في ${clockOf(context, suhoorAt)} صباحًا$before"
            }
            own != null -> "$deadline. سيوقظك منبهك في ${clockOf(context, own)} صباحًا"
            else -> deadline
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_adhkar_moon)
            .setContentTitle(notificationTitle(eve))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSubText("تذكير الصيام")
            .setContentIntent(openWorship(context, MainTabNavigation.WORSHIP_ACTION_FASTING, NOTIFICATION_ID))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setTimeoutAfter(Duration.between(now, fajr).toMillis())
            .setOnlyAlertOnce(true)
            .setSilent(silent)
        if (suhoorAt == null) {
            // Someone already woken before Fajr by an alarm of their own gets no second one.
            val options = suhoorOptions(fajr, now)
            if (own == null && options.isNotEmpty()) {
                // Chosen once in the app, with alarm access granted and still in time; from then on one tap here.
                val action = if (suhoorChosen(context) && suhoorMinutes(context) in options &&
                    WakeAlarmScheduler.canScheduleExactAlarms(context)
                ) {
                    broadcast(context, ACTION_SUHOOR, 2)
                } else {
                    openWorship(context, MainTabNavigation.WORSHIP_ACTION_SUHOOR, NOTIFICATION_ID + 1)
                }
                builder.addAction(0, "أيقظني للسحور", action)
            }
            builder.addAction(0, "أوقف التذكير أسبوعًا", broadcast(context, ACTION_PAUSE, 3))
        }
        // What it says is kept, so a later change of alarms can bring it up to date.
        prefs(context).edit().putLong("posted_fast_day", eve.fastDay.toEpochDay())
            .putLong("posted_suhoor_at", suhoorAt?.toEpochMilli() ?: 0L)
            .putLong("posted_own_at", own?.toEpochMilli() ?: 0L)
            .apply()
        manager.notify(NOTIFICATION_ID, builder.build())
    }

    // ---- Scheduling ----

    private fun alarmIntent(context: Context, backup: Boolean): PendingIntent = PendingIntent.getBroadcast(
        context, if (backup) 11 else 10,
        Intent(context, FastingReminderReceiver::class.java).setAction(ACTION_REMIND)
            .setData(Uri.parse(if (backup) "fasting://eve/backup" else "fasting://eve")),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * Posts the eve's reminder once it is due, arms one alarm for the next, withdraws a reminder or a suhoor
     * alarm whose day can no longer be fasted (an Eid announced, a pause), brings a shown reminder up to date
     * with the alarms, and keeps the repair work in step with the switch. [fromApp]: the reader just changed
     * something in the app, so a reminder due already comes without a sound.
     */
    fun refresh(context: Context, now: Instant = Instant.now(), fromApp: Boolean = false) {
        synchronized(lock) { refreshLocked(context.applicationContext, now, fromApp) }
    }

    private fun refreshLocked(app: Context, now: Instant, fromApp: Boolean) {
        val alarms = app.getSystemService(AlarmManager::class.java) ?: return
        val exact = alarmIntent(app, backup = false)
        val backup = alarmIntent(app, backup = true)
        alarms.cancel(exact)
        alarms.cancel(backup)
        withdrawStale(app, now)
        runCatching {
            val work = WorkManager.getInstance(app)
            if (isEnabled(app)) {
                work.enqueueUniquePeriodicWork(REPAIR_WORK, ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<FastingRepairWorker>(12, TimeUnit.HOURS).build())
            } else {
                work.cancelUniqueWork(REPAIR_WORK)
            }
        }.onFailure { Log.w("FastingReminder", "Could not update the repair work", it) }
        if (!isEnabled(app)) return
        val eves = upcoming(app, now, count = 12)
        // Due while its alarm waited (held by the system, a boot, a place set late): still owed until Fajr.
        val due = eves.firstOrNull()?.takeIf { it.reminds && now >= it.remindAt!! && now < it.fajr!! }
        val posted = prefs(app).getLong("posted_fast_day", NONE)
        if (due != null && posted != due.fastDay.toEpochDay() && !placeElsewhere(app, now)) {
            post(app, due, now, quiet = fromApp)
        } else if (due != null && posted == due.fastDay.toEpochDay()) {
            updatePosted(app, due, now)
        }
        val next = eves.firstOrNull { it.reminds && it.remindAt!! > now } ?: return
        val at = next.remindAt!!.toEpochMilli()
        // An exact alarm when allowed, with an inexact copy: revoking the access cancels every exact alarm.
        if (DhikrReminderScheduler.exactAlarmsEnabled(app)) {
            runCatching { alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, exact) }
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, backup)
    }

    private fun withdrawStale(context: Context, now: Instant) {
        val posted = prefs(context).getLong("posted_fast_day", NONE)
        if (posted != NONE) {
            val eve = eve(context, LocalDate.ofEpochDay(posted))
            if (!isEnabled(context) || !eve.reminds || eve.fajr == null || now >= eve.fajr) cancelNotification(context)
        }
        val suhoorDay = prefs(context).getLong("suhoor_fast_day", NONE)
        if (suhoorDay != NONE) {
            val eve = eve(context, LocalDate.ofEpochDay(suhoorDay))
            val alarm = suhoorAlarm(context)?.also { rememberSuhoorRings(context, it) }
            val firstRing = alarm?.let(::firstRing) ?: prefs(context).getLong("suhoor_first_ring_at", 0L)
            when {
                !eve.verdict.allowed || eve.paused -> cancelSuhoor(context)
                eve.fajr != null && now >= eve.fajr -> forgetSuhoor(context)
                // Deleted or turned off in the Alarms tab before its first ring (a strict level rings 10 minutes
                // early): no longer the night's alarm. Made to repeat, it left this id for one of its own.
                firstRing > now.toEpochMilli() && alarm?.enabled != true -> forgetSuhoor(context)
            }
        }
    }

    /**
     * A reminder still on screen whose alarm line no longer holds (the suhoor alarm or the reader's own alarm
     * changed, turned off or deleted in the Alarms tab) is posted again, silently, with what is true now.
     */
    private fun updatePosted(context: Context, eve: Eve, now: Instant) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.activeNotifications.none { it.id == NOTIFICATION_ID }) return
        val suhoor = suhoorAt(context, eve, now)
        val own = if (suhoor == null) ownAlarm(context, eve) else null
        val prefs = prefs(context)
        // An own alarm that has rung is done, not gone: a one-off is turned off after ringing.
        val postedOwn = prefs.getLong("posted_own_at", 0L)
        val ownChanged = postedOwn !in 1..now.toEpochMilli() && (own?.toEpochMilli() ?: 0L) != postedOwn
        if ((suhoor?.toEpochMilli() ?: 0L) != prefs.getLong("posted_suhoor_at", 0L) || ownChanged) {
            post(context, eve, now, suhoorAt = suhoor, quiet = true)
        }
    }

    // ---- Suhoor ----

    fun suhoorMinutes(context: Context): Int = prefs(context).getInt("suhoor_minutes", 30)

    internal fun suhoorLevel(context: Context): FajrAlarmPersistence =
        runCatching { FajrAlarmPersistence.valueOf(prefs(context).getString("suhoor_level", null) ?: "") }
            .getOrDefault(FajrAlarmPersistence.GENTLE)

    /** Asked once; afterwards the notification sets the alarm in one tap. */
    fun suhoorChosen(context: Context): Boolean = prefs(context).getBoolean("suhoor_chosen", false)

    internal fun saveSuhoorChoice(context: Context, minutes: Int, level: FajrAlarmPersistence) {
        prefs(context).edit().putInt("suhoor_minutes", minutes).putString("suhoor_level", level.name)
            .putBoolean("suhoor_chosen", true).apply()
    }

    /** The minutes before [fajr] whose ring is still ahead tonight, with a few minutes to spare. */
    fun suhoorOptions(fajr: Instant, now: Instant = Instant.now()): List<Int> {
        val earliest = now.plus(Duration.ofMinutes(SUHOOR_ACTION_MIN_LEAD_MINUTES))
        return SUHOOR_MINUTES.filter { fajr.minus(Duration.ofMinutes(it.toLong())) > earliest }
    }

    /** The earliest ring of the reader's own alarms in [eve]'s night, from midnight to 15 minutes before Fajr. */
    fun ownAlarm(context: Context, eve: Eve): Instant? {
        val fajr = eve.fajr ?: return null
        val from = eve.fastDay.atStartOfDay(zone(context)).toInstant().toEpochMilli()
        val until = fajr.toEpochMilli() - SUHOOR_ROOM_MINUTES * 60_000L
        // From the night's start, so a ring that already came tonight still counts.
        val start = Calendar.getInstance().apply { timeInMillis = minOf(timeInMillis, from - 1) }
        val days = WakeAlarmScheduler.loadPrayerDayContexts(context, start)
        val alarms = runBlocking { PrayerWakeRepository(context).wakeAlarms.first() }
        // A ring stopped from the Alarms tab still woke the reader; a skipped one did not.
        return alarms.filter { it.enabled && it.id != SUHOOR_ALARM_ID }
            .flatMap { WakeAlarmComputer.compute(start, it.copy(stoppedOccurrenceAtMillis = null), days).allTriggers }
            .map { it.triggerAtMillis }
            .filter { it in from..until }
            .minOrNull()?.let(Instant::ofEpochMilli)
    }

    /**
     * The one-off suhoor alarm for [eve], with the reader's minutes and insistence, named in the Alarms tab.
     * False when those minutes no longer fit before Fajr tonight.
     */
    fun setSuhoor(context: Context, eve: Eve, now: Instant = Instant.now()): Boolean {
        val fajr = eve.fajr ?: return false
        val minutes = suhoorMinutes(context)
        if (minutes !in suhoorOptions(fajr, now)) return false
        val ring = fajr.minus(Duration.ofMinutes(minutes.toLong()))
        val base = fajrSuggestionAlarm(minutes, suhoorLevel(context))
        val config = base.copy(
            id = SUHOOR_ALARM_ID,
            title = "منبه السحور",
            repeatMode = WakeRepeatMode.ONCE,
            mainAlarm = base.mainAlarm.copy(oneOffTriggerAtMillis = ring.toEpochMilli()),
        )
        runBlocking { PrayerWakeRepository(context).saveWakeConfig(config) }
        // Once the alarm exists: a refresh in between would otherwise forget it as removed.
        prefs(context).edit().putLong("suhoor_fast_day", eve.fastDay.toEpochDay()).apply()
        rememberSuhoorRings(context, config)
        runBlocking { ScheduleRefreshCoordinator.syncWake(context) }
        post(context, eve, now, suhoorAt = ring)
        return true
    }

    private fun suhoorAlarm(context: Context) =
        runBlocking { PrayerWakeRepository(context).wakeAlarms.first() }
            .firstOrNull { it.id == SUHOOR_ALARM_ID && it.repeatMode == WakeRepeatMode.ONCE }

    /** Its earliest ring: a pre-alarm before the main one counts. */
    private fun firstRing(alarm: PrayerWakeConfig): Long =
        alarm.mainAlarm.oneOffTriggerAtMillis + minOf(0, alarm.subAlarms.minOfOrNull { it.signedOffsetMinutes } ?: 0) * 60_000L

    /** Kept in step with the Alarms tab, where it may be moved; used once the alarm is gone. */
    private fun rememberSuhoorRings(context: Context, alarm: PrayerWakeConfig) {
        val ring = alarm.mainAlarm.oneOffTriggerAtMillis.takeIf { it > 0 } ?: return
        val prefs = prefs(context)
        if (ring != prefs.getLong("suhoor_ring_at", 0L) || firstRing(alarm) != prefs.getLong("suhoor_first_ring_at", 0L)) {
            prefs.edit().putLong("suhoor_ring_at", ring).putLong("suhoor_first_ring_at", firstRing(alarm)).apply()
        }
    }

    /**
     * When the suhoor alarm set for [eve] rings, or rang: once rung, the wake engine turns it off or removes it.
     * One deleted or turned off before its first ring is forgotten by [withdrawStale].
     */
    fun suhoorAt(context: Context, eve: Eve, now: Instant = Instant.now()): Instant? {
        val prefs = prefs(context)
        if (prefs.getLong("suhoor_fast_day", NONE) != eve.fastDay.toEpochDay()) return null
        val alarm = suhoorAlarm(context)?.takeIf { it.mainAlarm.oneOffTriggerAtMillis > 0 }
        if (alarm != null) {
            rememberSuhoorRings(context, alarm)
            return if (alarm.enabled || firstRing(alarm) <= now.toEpochMilli()) {
                Instant.ofEpochMilli(alarm.mainAlarm.oneOffTriggerAtMillis)
            } else {
                null
            }
        }
        val rang = prefs.getLong("suhoor_first_ring_at", 0L) in 1..now.toEpochMilli()
        return prefs.getLong("suhoor_ring_at", 0L).takeIf { rang && it > 0 }?.let(Instant::ofEpochMilli)
    }

    /** The suhoor alarm for [eve] is on and has not rung yet. */
    fun suhoorPending(context: Context, eve: Eve, now: Instant = Instant.now()): Boolean {
        if (prefs(context).getLong("suhoor_fast_day", NONE) != eve.fastDay.toEpochDay()) return false
        val alarm = suhoorAlarm(context)?.takeIf { it.enabled } ?: return false
        return firstRing(alarm) > now.toEpochMilli()
    }

    private fun forgetSuhoor(context: Context) {
        prefs(context).edit().remove("suhoor_fast_day").remove("suhoor_ring_at").remove("suhoor_first_ring_at").apply()
    }

    private fun cancelSuhoor(context: Context) {
        forgetSuhoor(context)
        runBlocking {
            val repository = PrayerWakeRepository(context)
            if (repository.wakeAlarms.first().any { it.id == SUHOOR_ALARM_ID }) {
                repository.deleteWakeAlarm(SUHOOR_ALARM_ID)
                ScheduleRefreshCoordinator.syncWake(context)
            }
        }
    }

    internal fun onAction(context: Context, action: String?) {
        when (action) {
            ACTION_REMIND -> refresh(context)
            ACTION_PAUSE -> pauseWeek(context)
            ACTION_SUHOOR -> upcoming(context, count = 1).firstOrNull()?.takeIf { it.reminds }?.let { eve ->
                // Tapped too late for the chosen minutes: the reminder comes back with what still fits.
                if (!setSuhoor(context, eve)) post(context, eve, quiet = true)
            }
        }
    }

    internal fun refreshRepair(context: Context) {
        RamadanOverrideChecker.startPollingIfNeeded()
        refresh(context)
    }
}

class FastingReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                FastingReminder.onAction(app, intent.action)
            } catch (error: Exception) {
                Log.w("FastingReminder", "Could not handle ${intent.action}", error)
            } finally {
                result.finish()
            }
        }
    }
}

/** Twice a day: a reminder an OEM held back is still shown before Fajr, and the next one is armed again. */
class FastingRepairWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        FastingReminder.refreshRepair(applicationContext)
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }
}

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
import android.graphics.Typeface
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.tunisianprayertimes.MainActivity
import com.tunisianprayertimes.MainTabNavigation
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerTime
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.R
import com.tunisianprayertimes.adhkar.DhikrReminderScheduler
import com.tunisianprayertimes.adhkar.DhikrTime
import com.tunisianprayertimes.adhkar.DhikrTimeKind
import com.tunisianprayertimes.quran.QuranRepository
import com.tunisianprayertimes.quran.audio.QuranAudioController
import com.tunisianprayertimes.quran.audio.QuranPlaybackService
import com.tunisianprayertimes.ui.resolvePrayerTimelineWindow
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
 * Two kinds of reminders. One optional notification a day, by default 45 minutes after sunrise, with that
 * day's checks: collapsed it names them, expanded it asks each check's question, never its answer, which the review
 * hides until «أظهر»; nothing is posted on a day with no check, and it goes once no check is left that day.
 * And, for the points the reader chose so, reminders for a few days after prayers, once the prayer's silence
 * ends, or at clock times: with the right wording only, «استمع» and «حفظتها».
 */
object HifzReminder {
    /** The morning reminder of the day's checks. */
    const val CHANNEL_ID = "hifz_daily"
    private const val NOTIFICATION_ID = 740_101
    /** The reminders of the points' plans. */
    const val REPEAT_CHANNEL_ID = "hifz_repeat"
    private const val REPEAT_NOTIFICATION_ID = 740_103
    private const val PREFS = "quran_hifz"
    internal const val ACTION_REMIND = "com.tunisianprayertimes.hifz.REMIND"
    internal const val ACTION_REPEAT = "com.tunisianprayertimes.hifz.REPEAT"
    internal const val ACTION_LEARNED = "com.tunisianprayertimes.hifz.LEARNED"
    private const val EXTRA_DAY = "com.tunisianprayertimes.hifz.extra.DAY"
    private const val EXTRA_TIME = "com.tunisianprayertimes.hifz.extra.TIME"
    private const val EXTRA_IDS = "com.tunisianprayertimes.hifz.extra.IDS"
    /** Later than this, a plan's reminder is dropped: the next one is near. */
    private const val REPEAT_STALE_MS = 3 * 60 * 60_000L
    private val laterDate = DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ar-TN-u-nu-latn"))

    /** Arms the next reminders, or cancels them. Call off the main thread. */
    fun refresh(context: Context, now: Long = System.currentTimeMillis()) {
        val app = context.applicationContext
        val alarms = app.getSystemService(AlarmManager::class.java) ?: return
        HifzRepository.load(app)
        val settings = HifzSettings.read(app)
        // The morning reminder goes once its checks are answered, from the review or elsewhere.
        if (HifzRepository.points.value.none { it.isDue(todayEpochDay()) }) clearDigest(app)
        val daily = remindIntent(app)
        alarms.cancel(daily)
        if (settings.reminder && HifzRepository.points.value.isNotEmpty()) nextTime(app, settings, now)?.let { at ->
            // Inexact is enough for a daily reminder, and needs no exact-alarm permission.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, daily)
        }
        armRepeat(app, alarms, now)
    }

    /** Takes the morning reminder away: its review was opened, or nothing is due any more. */
    fun clearDigest(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private fun nextTime(context: Context, settings: HifzSettings, now: Long): Long? {
        val lastDay = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("reminded_day", -1L)
        val today = LocalDate.now()
        return (0L..2L).firstNotNullOfOrNull { offset ->
            val date = today.plusDays(offset)
            if (date.toEpochDay() == lastDay) return@firstNotNullOfOrNull null
            DhikrReminderScheduler.resolveTime(context, DhikrTime(DhikrTimeKind.SHURUK, offsetMinutes = settings.reminderAfterSunriseMin), date)
                ?.takeIf { it > now + 30_000L }
        }
    }

    /** One alarm for the next reminder of the plans, whichever points it brings back. */
    private fun armRepeat(context: Context, alarms: AlarmManager, now: Long) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Never the reminder just shown again, should its alarm have come a little early; but one due in a few
        // seconds is still to come, even when a refresh runs just before it.
        val shown = prefs.getLong("repeat_shown_at", 0L)
        val points = HifzRepository.points.value
        // A reminder whose time came while its alarm waited, as an inexact one may for up to an hour, is still
        // owed: a refresh then shows it at once rather than moving on to the next prayer.
        val owed = prefs.getString("repeat_armed", null)?.let(::decodeSlot)?.takeIf {
            it.at in (shown + 1)..now && now - it.at < REPEAT_STALE_MS && points.repeatingAt(it.day, it.time).isNotEmpty()
        }
        val next = owed ?: points.nextRepeat(epochDay(now), maxOf(now, shown)) { day, time -> slotAt(context, day, time) }
        if (next == null) {
            alarms.cancel(repeatIntent(context, null))
            prefs.edit().remove("repeat_armed").apply()
            return
        }
        prefs.edit().putString("repeat_armed", "${next.day}|${next.time.key}|${next.at}").apply()
        val pending = repeatIntent(context, next)
        // After a prayer means after it, and a time is a time: exact when the system allows it, otherwise as close as it lets.
        val at = maxOf(next.at, now)
        if (DhikrReminderScheduler.exactAlarmsEnabled(context)) {
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
                return
            } catch (_: SecurityException) {
            }
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
    }

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
     * When the reminder after [prayer] on [day] comes: once the prayer's silence ends when the app silences
     * it, otherwise 20 minutes after the adhan; never sooner than 10 minutes after it. On Friday, Dhuhr is
     * the Jumu'a. Null when that day's prayer times are not known.
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
        val zone = ZoneId.systemDefault()
        var adhan = DhikrReminderScheduler.resolveTime(context, DhikrTime(kind), date) ?: return null
        val friday = prayer == Prayer.DHUHR && date.dayOfWeek == DayOfWeek.FRIDAY
        val silenced = if (friday) Prayer.JOMOAA else prayer
        val jumuahHour = PrefsManager.getJomoaaTimeHour(context)
        if (friday && jumuahHour in 0..23) {
            adhan = date.atTime(jumuahHour, PrefsManager.getJomoaaTimeMinute(context).coerceIn(0, 59)).atZone(zone).toInstant().toEpochMilli()
        }
        val silenceEnd = if (PrefsManager.isEnabled(context) && PrefsManager.isPrayerSilenceEnabled(context, silenced)) {
            val time = Instant.ofEpochMilli(adhan).atZone(zone)
            val window = resolvePrayerTimelineWindow(PrayerTime(silenced, time.hour, time.minute), PrefsManager.getConfig(context, silenced))
            date.atStartOfDay(zone).toInstant().toEpochMilli() + window.endMinutes * 60_000L
        } else null
        return maxOf(adhan + 10 * 60_000L, silenceEnd?.plus(2 * 60_000L) ?: (adhan + 20 * 60_000L))
    }

    /** Where a plan chosen now starts: its first reminder, today or in the next days. */
    fun firstRepeatSlot(context: Context, repeat: HifzRepeat, now: Long = System.currentTimeMillis()): HifzSlot? =
        if (repeat.none) null else firstRepeat(repeat, epochDay(now), now + 60_000L) { day, time -> slotAt(context, day, time) }

    /** The last day of a plan of [days] days that starts at [first]. */
    fun repeatUntil(first: HifzSlot?, days: Int, now: Long = System.currentTimeMillis()): Long =
        (first?.day ?: epochDay(now)) + days - 1

    /**
     * «أول تذكير اليوم بعد العصر، ثم بعد كل صلاة لمدة 3 أيام. وتبدأ مراجعته من الغد في تذكير الصباح، وتتباعد كلما
     * أجبت «أتقنتها».». Call off the main thread.
     */
    fun planSentence(context: Context, repeat: HifzRepeat, days: Int, now: Long = System.currentTimeMillis()): String {
        if (repeat.none) return dailySentence(context)
        val often = "${repeatPhrase(repeat)} ${daysPhrase(days)}"
        // The checks start the next day, beside the plan; «من الغد» since a full day's review may move one later.
        val checks = if (HifzSettings.read(context).reminder) "وتبدأ مراجعاته من الغد في تذكير الصباح، وتتباعد كلما أجبت «أتقنتها»."
            else "وتبدأ مراجعاته من الغد في «مراجعة اليوم»، وتتباعد كلما أجبت «أتقنتها»."
        val first = firstRepeatSlot(context, repeat, now) ?: return "يذكّرك به $often. $checks"
        return "أول تذكير ${slotLabel(first, epochDay(now))}، ثم $often. $checks"
    }

    /** What a recorded point's reminders are now, for its fix sheet. Call off the main thread. */
    fun pointSentence(context: Context, point: HifzPoint, now: Long = System.currentTimeMillis()): String {
        val today = epochDay(now)
        if (!point.repeatsOn(today)) return dailySentence(context, point.dueDay - today)
        val next = listOf(point).nextRepeat(today, now + 30_000L) { day, time -> slotAt(context, day, time) }
        val until = "${repeatPhrase(point.repeat)} حتى ${dayLabel(point.repeatUntil!!, today)}"
        return if (next == null) "يذكّرك به $until." else "يذكّرك به $until. التذكير التالي ${slotLabel(next, today)}."
    }

    private fun dailySentence(context: Context, daysToCheck: Long = 1L): String {
        val settings = HifzSettings.read(context)
        val day = when {
            daysToCheck <= 0L -> "اليوم"
            daysToCheck == 1L -> "غدًا"
            else -> "يوم مراجعته"
        }
        return if (settings.reminder) "يظهر في تذكير الصباح $day، بعد الشروق بـ ${settings.reminderAfterSunriseMin} دقيقة، ثم تتباعد مراجعاته كلما أجبت «أتقنتها»."
        else "التذكير اليومي متوقف في الإعدادات: تجده في المراجعة $day."
    }

    /** «بعد كل صلاة», «بعد الفجر والظهر والعشاء», «بعد الفجر وفي الساعة 13:00 و21:30». */
    fun repeatPhrase(repeat: HifzRepeat): String {
        when (HifzRepeatPreset.of(repeat)) {
            HifzRepeatPreset.ChecksOnly -> return "في المراجعات فقط"
            HifzRepeatPreset.Once -> return "مرة في اليوم بعد الفجر"
            else -> Unit
        }
        val prayers = HifzPrayers.filter { it in repeat.prayers }.map { prayerName(it, null) }
        val after = when {
            prayers.size == HifzPrayers.size -> "بعد كل صلاة"
            prayers.isNotEmpty() -> "بعد " + prayers.joinToString(" و")
            else -> null
        }
        val at = repeat.minutes.sorted().takeIf { it.isNotEmpty() }?.joinToString(" و", prefix = "في الساعة ") { clock(it) }
        return listOfNotNull(after, at).joinToString(" و")
    }

    /** For a list row: [repeatPhrase] while short, otherwise how many times a day, «4 مرات في اليوم». */
    fun repeatShortPhrase(repeat: HifzRepeat): String =
        if (HifzRepeatPreset.of(repeat) != null || repeat.perDay <= 2) repeatPhrase(repeat) else countPhrase(repeat.perDay)

    /** «مرة في اليوم», «مرتين في اليوم», «4 مرات في اليوم». */
    fun countPhrase(count: Int): String = when {
        count == 1 -> "مرة في اليوم"
        count == 2 -> "مرتين في اليوم"
        count % 100 in 3..10 -> "$count مرات في اليوم"
        else -> "$count مرة في اليوم"
    }

    /** «لمدة 3 أيام», «لمدة أسبوع», «لمدة يومين». */
    fun daysPhrase(days: Int): String = "لمدة ${daysLabel(days, oblique = true)}"

    /**
     * «3 أيام», «أسبوع», «يومان», «15 يومًا»; [oblique] after a preposition or a verb, where the dual is
     * «يومين», «أسبوعين».
     */
    fun daysLabel(days: Int, oblique: Boolean = false): String = when {
        days == 1 -> "يوم واحد"
        days == 2 -> if (oblique) "يومين" else "يومان"
        days == 7 -> "أسبوع"
        days == 14 -> if (oblique) "أسبوعين" else "أسبوعان"
        days % 100 in 3..10 -> "$days أيام"
        else -> "$days يومًا"
    }

    /** «08:00», «21:30»: a minute of the day, by the 24-hour clock. */
    fun clock(minute: Int): String = "${(minute / 60).toString().padStart(2, '0')}:${(minute % 60).toString().padStart(2, '0')}"

    /** «بعد الظهر», «بعد الجمعة», «الساعة 21:30». */
    fun timeLabel(time: HifzWhen, day: Long?): String = when (time) {
        is HifzWhen.After -> "بعد ${prayerName(time.prayer, day)}"
        is HifzWhen.At -> "الساعة ${clock(time.minute)}"
    }

    /** The name of [prayer]: «الفجر» … «العشاء». */
    fun prayerLabel(prayer: Prayer): String = prayerName(prayer, null)

    /** «اليوم بعد العصر», «غدًا بعد الفجر», «الأحد بعد الجمعة», «اليوم الساعة 21:30». */
    fun slotLabel(slot: HifzSlot, today: Long): String = "${dayLabel(slot.day, today)} ${timeLabel(slot.time, slot.day)}"

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
        else -> when (blockedChannel(context, channels)) {
            REPEAT_CHANNEL_ID -> "تذكير المواضع متوقف في إعدادات الإشعارات"
            CHANNEL_ID -> "تذكير الصباح متوقف في إعدادات الإشعارات"
            else -> null
        }
    }

    /** Posts today's checks, if any, then arms tomorrow. */
    internal fun deliver(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = todayEpochDay()
        HifzRepository.load(app)
        val settings = HifzSettings.read(app)
        val due = HifzRepository.points.value.dueToday(today, settings.perDay)
        if (settings.reminder && due.isNotEmpty() && prefs.getLong("reminded_day", -1L) != today) {
            runCatching { post(app, due, settings) }
            prefs.edit().putLong("reminded_day", today).apply()
        }
        refresh(app)
    }

    /** Posts the reminder at [time] on [day], unless it is hours late, then arms the next one. */
    internal fun deliverRepeat(context: Context, day: Long, time: HifzWhen?) {
        val app = context.applicationContext
        HifzRepository.load(app)
        val now = System.currentTimeMillis()
        if (time != null) {
            val due = HifzRepository.points.value.repeatingAt(day, time)
            val at = slotAt(app, day, time)
            // The silence or the city changed since the alarm was set, and the reminder now comes later: wait for it.
            if (at != null && at - now > 60_000L) {
                refresh(app)
                return
            }
            if (due.isNotEmpty() && (at == null || now - at < REPEAT_STALE_MS)) {
                runCatching { postRepeat(app, due, HifzSlot(day, time, at ?: now)) }
            }
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("repeat_shown_at", maxOf(at ?: now, now)).apply()
        }
        refresh(app)
    }

    /** «حفظتها»: the points' plans stop, said in a quiet notification that goes by itself; their spaced checks go on. */
    internal fun learned(context: Context, ids: LongArray) {
        val app = context.applicationContext
        HifzRepository.load(app)
        val points = HifzRepository.points.value.filter { it.id in ids }
        points.forEach { point -> HifzRepository.update(app, point.copy(repeat = HifzRepeat.None, repeatUntil = null)) }
        val manager = app.getSystemService(NotificationManager::class.java)
        val catalog = runCatching { QuranRepository.load(app) }.getOrNull()
        val single = points.singleOrNull()
        val name = single?.let { p -> catalog?.surahs?.getOrNull(p.verse.surah - 1)?.name?.let { "$it ${p.verse.ayah}" } }
        if (manager != null && points.isNotEmpty()) runCatching {
            manager.notify(
                REPEAT_NOTIFICATION_ID,
                Notification.Builder(app, REPEAT_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_tab_quran)
                    // The plan stops; the checks go on, held points' too.
                    .setContentTitle(if (name != null) "أوقفنا تذكير موضع $name" else "أوقفنا تذكير هذه المواضع")
                    .setContentText(
                        when {
                            !HifzSettings.read(app).reminder -> if (name != null) "يبقى في المراجعة يوم موعده" else "تبقى في المراجعة يوم موعدها"
                            name != null -> "يبقى في المراجعة، ويظهر في تذكير الصباح يوم موعده"
                            else -> "تبقى في المراجعة، وتظهر في تذكير الصباح يوم موعدها"
                        },
                    )
                    .setSubText("المراجعة")
                    .setOnlyAlertOnce(true)
                    .setTimeoutAfter(8_000L)
                    .setAutoCancel(true)
                    .build(),
            )
        } else manager?.cancel(REPEAT_NOTIFICATION_ID)
        refresh(app)
    }

    private fun post(context: Context, due: List<HifzPoint>, settings: HifzSettings) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "المراجعة", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "تذكير يومي واحد بمواضعك"
        })
        val catalog = QuranRepository.load(context)
        val name = { n: Int -> catalog.surahs.getOrNull(n - 1)?.name.orEmpty() }
        val title = "اليوم ${pointsLabel(due.size)} للمراجعة"
        val summary = due.joinToString(" · ") { "${name(it.verse.surah)} ${it.verse.ayah} (${it.kind.short})" }
        // What each check will ask, not its answer: read here, it would be no check at all.
        val lines = due.mapNotNull { point ->
            val fix = hifzFix(catalog, point) ?: return@mapNotNull null
            "${name(point.verse.surah)} ${point.verse.ayah} · ${fix.digestPrompt}"
        }
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
            .addAction(Notification.Action.Builder(null, "ابدأ المراجعة", review).build())
        listenIntent(context, NOTIFICATION_ID + 1, hifzListen(catalog, due, settings))?.let { listen ->
            builder.addAction(Notification.Action.Builder(null, "استمع إليها", listen).build())
        }
        manager.notify(NOTIFICATION_ID, builder.build())
    }

    /**
     * The points a plan's reminder brings back, in one notification however many they are: the right
     * wording with the words to mind in bold, never the wrong wording.
     */
    private fun postRepeat(context: Context, due: List<HifzPoint>, slot: HifzSlot) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(REPEAT_CHANNEL_ID, "تذكير بمواضعك", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "بعد الصلوات أو في الأوقات التي اخترتها، للمواضع التي اخترت أن تُذكَّر بها"
        })
        val catalog = QuranRepository.load(context)
        val name = { verse: com.tunisianprayertimes.quran.QuranVerseReference -> "${catalog.surahs.getOrNull(verse.surah - 1)?.name.orEmpty()} ${verse.ayah}" }
        val fixes = due.mapNotNull { point -> hifzFix(catalog, point) }
        if (fixes.isEmpty()) return
        val single = fixes.singleOrNull()
        val title = if (single != null) "تذكّر · ${name(single.point.verse)}" else "تذكّر ${pointsLabel(fixes.size, accusative = true)}"
        val after = timeLabel(slot.time, slot.day)
        val style = if (single != null) {
            val key = single.point.key.trim()
            Notification.BigTextStyle().setBigContentTitle(title).bigText(
                SpannableStringBuilder(fixLine(single, null)).apply { if (key.isNotEmpty()) append("\nضابطي: ").append(key) },
            )
        } else {
            Notification.InboxStyle().setBigContentTitle(title).also { inbox -> fixes.forEach { inbox.addLine(fixLine(it, name(it.point.verse))) } }
        }
        val open = openIntent(
            context, REPEAT_NOTIFICATION_ID,
            if (single != null) "${MainTabNavigation.QURAN_ACTION_HIFZ_FIX_PREFIX}${single.point.id}"
            else MainTabNavigation.QURAN_ACTION_HIFZ_POINTS_PREFIX + fixes.joinToString(",") { it.point.id.toString() },
        )
        val learned = PendingIntent.getBroadcast(
            context, REPEAT_NOTIFICATION_ID + 2,
            Intent(context, HifzReminderReceiver::class.java).setAction(ACTION_LEARNED).putExtra(EXTRA_IDS, fixes.map { it.point.id }.toLongArray()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(context, REPEAT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tab_quran)
            .setContentTitle(title)
            .setContentText(if (single != null) fixLine(single, null) else fixes.joinToString(" · ") { "${name(it.point.verse)} (${it.point.kind.short})" })
            .setSubText(after)
            .setStyle(style)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
        val settings = HifzSettings.read(context)
        listenIntent(context, REPEAT_NOTIFICATION_ID + 1, hifzListen(catalog, fixes.map { it.point }, settings.copy(drillGap = 0f)))?.let { listen ->
            builder.addAction(Notification.Action.Builder(null, "استمع", listen).build())
        }
        val learnedLabel = when (fixes.size) {
            1 -> "حفظتها"
            2 -> "حفظتهما"
            else -> "حفظتها كلها"
        }
        builder.addAction(Notification.Action.Builder(null, learnedLabel, learned).build())
        manager.notify(REPEAT_NOTIFICATION_ID, builder.build())
    }

    /** «انتبه إلى: …» with the words to mind in bold, after the verse's name when given. */
    private fun fixLine(fix: HifzFix, verseName: String?): CharSequence = SpannableStringBuilder().apply {
        if (verseName != null) append(verseName).append(" · ")
        append(wordingLabel(fix)).append(": ")
        val shown = fix.wordingWords
        shown.forEachIndexed { position, index ->
            if (position > 0) append(if (index > shown[position - 1] + 1) " … " else " ")
            val start = length
            append(fix.words.printed[index])
            if (index in fix.stressed) setSpan(StyleSpan(Typeface.BOLD), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (fix.wording.endsWith("…")) append(" …")
    }

    private fun wordingLabel(fix: HifzFix): String =
        if (fix.point.kind == HifzKind.Skip && fix.point.verse.ayah > 1) "بعد الآية ${fix.point.verse.ayah - 1}" else fix.wordingLabel

    private fun openIntent(context: Context, requestCode: Int, quranAction: String): PendingIntent = PendingIntent.getActivity(
        context, requestCode,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainTabNavigation.EXTRA_DESTINATION, MainTabNavigation.DESTINATION_QURAN)
            .putExtra(MainTabNavigation.EXTRA_QURAN_ACTION, quranAction),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Plays straight from the notification: a tap on an action may start the playback service. */
    private fun listenIntent(context: Context, requestCode: Int, playlist: com.tunisianprayertimes.quran.audio.QuranPlaylist?): PendingIntent? {
        playlist ?: return null
        QuranAudioController.initialize(context)
        return PendingIntent.getForegroundService(
            context, requestCode,
            Intent(context, QuranPlaybackService::class.java)
                .setAction(QuranPlaybackService.ACTION_PLAYLIST)
                .putExtra(QuranPlaybackService.EXTRA_RECITER, QuranAudioController.state.value.reciterId)
                .putExtra(QuranPlaybackService.EXTRA_PLAYLIST, playlist.encode()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** «موضعان اليوم»; after a verb, [accusative]: «تذكّر موضعين». */
    private fun pointsLabel(count: Int, accusative: Boolean = false): String = when {
        count == 1 -> if (accusative) "موضعا واحدا" else "موضع واحد"
        count == 2 -> if (accusative) "موضعين" else "موضعان"
        count % 100 in 3..10 -> "$count مواضع"
        else -> "$count موضعا"
    }

    private fun remindIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, NOTIFICATION_ID,
        Intent(context, HifzReminderReceiver::class.java).setAction(ACTION_REMIND),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** The alarm for a plan's reminder; one at a time, so [slot] only changes its extras. */
    private fun repeatIntent(context: Context, slot: HifzSlot?): PendingIntent = PendingIntent.getBroadcast(
        context, REPEAT_NOTIFICATION_ID,
        Intent(context, HifzReminderReceiver::class.java).setAction(ACTION_REPEAT).apply {
            if (slot != null) putExtra(EXTRA_DAY, slot.day).putExtra(EXTRA_TIME, slot.time.key)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    internal fun receive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_REMIND -> deliver(context)
            ACTION_REPEAT -> deliverRepeat(
                context,
                intent.getLongExtra(EXTRA_DAY, todayEpochDay()),
                HifzWhen.of(intent.getStringExtra(EXTRA_TIME)),
            )
            ACTION_LEARNED -> learned(context, intent.getLongArrayExtra(EXTRA_IDS) ?: LongArray(0))
        }
    }
}

class HifzReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(HifzReminder.ACTION_REMIND, HifzReminder.ACTION_REPEAT, HifzReminder.ACTION_LEARNED)) return
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

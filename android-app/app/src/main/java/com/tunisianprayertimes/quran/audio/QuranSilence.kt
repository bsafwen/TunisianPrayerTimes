package com.tunisianprayertimes.quran.audio

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.SilenceScheduler
import com.tunisianprayertimes.SilenceStatus
import com.tunisianprayertimes.nap.NapPrefs
import com.tunisianprayertimes.ui.PhoneSilenceReason
import com.tunisianprayertimes.ui.PhoneSilenceReasonSource
import com.tunisianprayertimes.ui.resolvePhoneSilenceReason
import com.tunisianprayertimes.wake.WakeAlarmScheduler
import com.tunisianprayertimes.wake.displayName
import java.time.Instant
import java.util.Calendar
import java.util.Locale

/**
 * The phone mutes recitations now: total silence (the app's own at prayer time, its manual one, a silence until an
 * alarm, or the listener's), or Do Not Disturb with media turned off. «Alarms only» lets media through.
 */
internal fun quranPhoneSilenced(context: Context): Boolean = runCatching {
    val notifications = context.getSystemService(NotificationManager::class.java)
    when (notifications.currentInterruptionFilter) {
        NotificationManager.INTERRUPTION_FILTER_NONE -> true
        // Before Android 15 the listener's own media setting cannot be read; their priority mode is then taken as heard.
        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
            notifications.consolidatedNotificationPolicy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_MEDIA == 0
        else -> false
    }
}.getOrDefault(false)

/** The broadcasts that may change [quranPhoneSilenced]: the phone's mode, and its media setting on Android 15+. */
internal fun quranSilenceChanges(): IntentFilter = IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED).apply {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) addAction(NotificationManager.ACTION_CONSOLIDATED_NOTIFICATION_POLICY_CHANGED)
}

/**
 * Who silenced the phone when the recitation stopped for it: [title] says what happened, [action] what to do. The app's
 * own silences speak as «we», so they are never taken for the phone's; the listener's own is named by its setting.
 */
data class QuranSilenceNotice(val title: String, val action: String) {
    /** Both at once, where one line is read alone: the snackbar, the notification. */
    val sentence: String get() = "$title. $action."

    companion object {
        /** Do Not Disturb set on the phone, by the listener or another app; also said when the cause is unknown. */
        val DoNotDisturb = QuranSilenceNotice("الهاتف في وضع عدم الإزعاج", "أوقفه لتسمع التلاوة")
    }
}

/** Who silenced the phone now, as the prayer tab says it: the app's manual silence first, then its prayer one, then the alarm's. */
internal fun quranSilenceNotice(context: Context, nowMillis: Long = System.currentTimeMillis()): QuranSilenceNotice = runCatching {
    // The app's flags without its total silence: the listener changed the phone's mode since, the cause is theirs.
    if (!SilenceStatus.isAppControlledSilenceActive(context)) return@runCatching QuranSilenceNotice.DoNotDisturb
    val manualActive = PrefsManager.isManualSilenceActive(context)
    val autoActive = PrefsManager.isAutoSilenceActive(context)
    val wakeActive = WakeAlarmScheduler.isSilenceUntilAlarmActive(context)
    val reason = resolvePhoneSilenceReason(
        isPhoneSilenced = true,
        autoSilenceActive = autoActive,
        activeAutoSilencePrayer = if (autoActive) SilenceScheduler.currentSilenceWindowPrayer(context) else null,
        manualSilenceActive = manualActive,
        manualSilenceMode = PrefsManager.getManualSilenceMode(context),
        manualTargetPrayer = PrefsManager.getManualSilenceTargetPrayer(context),
        manualSilenceEndsAtMillis = PrefsManager.getManualSilenceEndsAtMillis(context),
        wakeSilenceUntilAlarmActive = wakeActive,
        currentTimeMillis = nowMillis,
    ) ?: return@runCatching QuranSilenceNotice.DoNotDisturb
    // When the silence named really ends: overlapping prayer windows keep it on, as their end alarms do (6.9), and a
    // timed silence ending in a window is followed by the prayer's own, as its rescheduling does.
    val endsAt = when (reason.source) {
        PhoneSilenceReasonSource.AUTO_PRAYER, PhoneSilenceReasonSource.AUTO_PRAYER_UNKNOWN ->
            SilenceScheduler.currentSilenceWindowEnd(context)?.let { prayerSilenceEnd(context, it, resumed = false) }
        PhoneSilenceReasonSource.MANUAL_UNTIL_TIME -> prayerSilenceEnd(context, reason.endsAtMillis, resumed = true)
        else -> null
    }
    // The alarms whose silence follows the one named. A manual silence silences the alarms ringing before its end,
    // which then end nothing: only those ringing after a timed one take it over.
    val alarms = when {
        !wakeActive -> 0
        reason.source == PhoneSilenceReasonSource.MANUAL_UNTIL_TIME -> {
            val rings = WakeAlarmScheduler.silencedAlarmRings(context, nowMillis)
            val unscheduled = (WakeAlarmScheduler.silencedAlarmIds(context).size - rings.size).coerceAtLeast(0)
            rings.count { it > reason.endsAtMillis } + unscheduled
        }
        else -> WakeAlarmScheduler.silencedAlarmIds(context).size.coerceAtLeast(1)
    }
    // The phone's own mode, saved by the first of the app's silences and put back after the last.
    val savedFilter = when {
        manualActive -> PrefsManager.getManualSilencePreviousInterruptionFilter(context)
        autoActive -> PrefsManager.getAutoSilencePreviousInterruptionFilter(context)
        else -> NapPrefs.getPreviousInterruptionFilter(context)
    }
    quranSilenceNotice(
        reason = reason,
        endsAtMillis = endsAt,
        alarms = alarms,
        ownSilenceAfter = filterMutesMedia(context, savedFilter),
    ) { it.displayName(context) }
}.getOrDefault(QuranSilenceNotice.DoNotDisturb)

/**
 * [reason] said to the listener, with what brings the sound back: [endsAtMillis] the end of the app's silence when
 * known; [alarms] the alarms still to ring whose silence follows the one named (0 for none): a prayer silence, or a
 * manual one when it ends or is stopped, hands over to it rather than ending; [ownSilenceAfter] the phone's own mode,
 * put back at the end, muting recitations too.
 */
internal fun quranSilenceNotice(
    reason: PhoneSilenceReason,
    endsAtMillis: Long?,
    alarms: Int,
    ownSilenceAfter: Boolean,
    prayerName: (Prayer) -> String,
): QuranSilenceNotice {
    val wake = reason.source == PhoneSilenceReasonSource.WAKE_ALARM
    val title = when (reason.source) {
        PhoneSilenceReasonSource.AUTO_PRAYER -> "أسكتنا الهاتف لصلاة ${prayerName(requireNotNull(reason.prayer))}"
        PhoneSilenceReasonSource.AUTO_PRAYER_UNKNOWN -> "أسكتنا الهاتف لوقت الصلاة"
        // The listener's own silence: said as theirs.
        PhoneSilenceReasonSource.MANUAL_UNTIL_TIME,
        PhoneSilenceReasonSource.MANUAL_UNTIL_STOPPED,
        PhoneSilenceReasonSource.MANUAL_UNTIL_PRAYER -> "أسكتنا الهاتف كما طلبت"
        PhoneSilenceReasonSource.WAKE_ALARM -> if (alarms > 1) "أسكتنا الهاتف حتى يرنّ آخر منبّهاتك" else "أسكتنا الهاتف حتى يرنّ منبّهك"
        PhoneSilenceReasonSource.EXTERNAL -> return QuranSilenceNotice.DoNotDisturb
    }
    val alarmAfter = when {
        alarms > 1 -> "بعد أن يرنّ آخر منبّهاتك"
        alarms == 1 -> "بعد أن يرنّ منبّهك"
        else -> null
    }
    // When the sound comes back; null while it waits for the listener to stop their own silence.
    val after = when {
        wake -> if (alarms > 1) "بعد أن يرنّ آخرها" else "بعد أن يرنّ"
        reason.source == PhoneSilenceReasonSource.AUTO_PRAYER || reason.source == PhoneSilenceReasonSource.AUTO_PRAYER_UNKNOWN ->
            alarmAfter ?: endsAtMillis?.let { "بعد ${clockOf(it)}" } ?: "بعد الصلاة"
        reason.source == PhoneSilenceReasonSource.MANUAL_UNTIL_TIME -> alarmAfter ?: endsAtMillis?.let { "بعد ${clockOf(it)}" }
        // A silence until a prayer may be followed by the prayer's own: no time to wait for.
        else -> null
    }
    // Their own silence stopped, an alarm still to ring keeps the phone silent until it rings.
    val handOver = alarmAfter.takeIf { after == null }
    val action = when {
        handOver != null && ownSilenceAfter -> "أوقف الإسكات، ثم أوقف وضع عدم الإزعاج $handOver"
        handOver != null -> "أوقف الإسكات، ثم شغّل التلاوة $handOver"
        after == null && ownSilenceAfter -> "أوقف الإسكات ثم وضع عدم الإزعاج لتسمع التلاوة"
        after == null -> "أوقف الإسكات لتسمع التلاوة"
        ownSilenceAfter -> "$after، أوقف وضع عدم الإزعاج لتسمع التلاوة"
        else -> "شغّل التلاوة $after"
    }
    return QuranSilenceNotice(title, action)
}

/**
 * The end of the silence holding at [fromMillis] once the prayer windows open then carry it on, each to its own end.
 * [resumed]: the silence ending then is another (a timed one), which a window brings back only when the listener has
 * not stopped it, as the rescheduling does; from then on, as at a window's own end, every window open keeps it.
 */
private fun prayerSilenceEnd(context: Context, fromMillis: Long, resumed: Boolean): Long {
    if (!prayerSilencesRun(context)) return fromMillis
    val planner = SilenceScheduler.planner(context)
    var end = fromMillis
    // Windows last at most a day and a half: a few links cover any chain.
    repeat(8) { link ->
        val open = planner.containing(Instant.ofEpochMilli(end))
        if (resumed && link == 0 && open.all { SilenceScheduler.isDismissed(context, it.prayer, it.prayerDay) }) return end
        val next = open.maxOfOrNull { it.end.toEpochMilli() }
        if (next == null || next <= end) return end
        end = next
    }
    return end
}

/** The app's prayer silences are armed: switched on, with Do Not Disturb access and exact alarms, as their scheduling needs. */
private fun prayerSilencesRun(context: Context): Boolean = PrefsManager.isEnabled(context) &&
    context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted &&
    (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms())

/** The phone's own [filter] mutes recitations, as [quranPhoneSilenced] judges the mode in effect. */
private fun filterMutesMedia(context: Context, filter: Int): Boolean = runCatching {
    when (filter) {
        NotificationManager.INTERRUPTION_FILTER_NONE -> true
        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
            context.getSystemService(NotificationManager::class.java).notificationPolicy.priorityCategories and
            NotificationManager.Policy.PRIORITY_CATEGORY_MEDIA == 0
        else -> false
    }
}.getOrDefault(false)

/** «13:20», Western digits whatever the locale. */
private fun clockOf(millis: Long): String {
    val calendar = Calendar.getInstance().apply { timeInMillis = millis }
    return String.format(Locale.US, "%02d:%02d", calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE))
}

/**
 * [state] with its silence said again, or null when that changes nothing: cleared once the phone is heard, resolved
 * anew while it stays silent, since the app hands a silence from one cause to another without any broadcast.
 */
internal fun quranSilenceRefreshed(context: Context, state: QuranPlaybackState): QuranPlaybackState? {
    if (state.interruption != QuranInterruption.Silence) return null
    if (!quranPhoneSilenced(context)) return state.copy(interruption = null, silence = null)
    val notice = quranSilenceNotice(context)
    return if (notice == state.silence) null else state.copy(silence = notice)
}

/**
 * Why the recitation stopped, in one line: the player's heading, the hifz strip's, the lock screen's; null while it
 * plays or when nothing stopped it.
 */
fun quranStopReason(state: QuranPlaybackState): String? =
    state.interruption?.takeIf { !state.playing }?.let { quranInterruptionText(it, state.silence) }

fun quranInterruptionText(reason: QuranInterruption, silence: QuranSilenceNotice?): String = when (reason) {
    QuranInterruption.Alarm -> "توقّفت التلاوة حين رنّ المنبّه"
    QuranInterruption.OtherAudio -> "توقّفت التلاوة حين بدأ صوت آخر"
    // True whether it stopped or never started: who silenced the phone.
    QuranInterruption.Silence -> (silence ?: QuranSilenceNotice.DoNotDisturb).title
}

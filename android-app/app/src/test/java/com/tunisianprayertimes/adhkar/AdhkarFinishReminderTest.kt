package com.tunisianprayertimes.adhkar

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.ui.defaultDhikrReminder
import com.tunisianprayertimes.ui.morningCollectionPreset
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

/**
 * The follow-up notifications of a gentle or balanced reminder: where the engine puts them, what a late or held
 * delivery does to the ones behind it, where an evening period stops notifying, and what the notification says.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AdhkarFinishReminderTest {
    private lateinit var context: Context
    private lateinit var repo: DhikrRepository
    private lateinit var zone: TimeZone
    private val friday = LocalDate.of(2026, 9, 25)
    private val minute = 60_000L
    private val prefs get() = context.getSharedPreferences("adhkar_schedule_v2", 0)

    @Before fun setup() {
        zone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Africa/Tunis"))
        context = ApplicationProvider.getApplicationContext()
        listOf("adhkar_reminders", "adhkar_schedule", "adhkar_schedule_v2", "prayer_silence_prefs", "wake_alarm_scheduler")
            .forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
        DhikrRepository.clearMemoryCache()
        DhikrReadingPresence.occurrenceId = null
        repo = DhikrRepository(context)
    }
    @After fun teardown() {
        TimeZone.setDefault(zone); DhikrReadingPresence.occurrenceId = null; DhikrRepository.clearMemoryCache()
    }

    private fun daily(start: Int, end: Int, cadence: DhikrCadence = DhikrCadence.GENTLE) = DhikrReminder(
        dhikrId = DhikrCatalog.SALAWAT_ID, targetCount = 100, cadence = cadence,
        start = DhikrTime(minuteOfDay = start), end = DhikrTime(minuteOfDay = end))
    private fun evening(cadence: DhikrCadence = DhikrCadence.GENTLE) = DhikrReminder(
        dhikrId = "evening_kingdom", collection = DhikrCategory.EVENING, targetCount = 1, cadence = cadence,
        start = DhikrTime(DhikrTimeKind.ASR), end = DhikrTime(DhikrTimeKind.FAJR), endNextDay = true)
    private fun window(rule: DhikrReminder, date: LocalDate = friday) =
        requireNotNull(DhikrReminderScheduler.resolveWindow(context, rule, date))
    private fun eventOf(id: String): String? = prefs.getString("event:$id", null)
    private fun deliver(value: String, at: Long) = DhikrReminderScheduler.receive(
        context, Intent().setAction(DhikrReminderScheduler.ACTION_REMIND).putExtra("event", value), at)
    private fun done(key: String): JSONObject? = prefs.getString("done:$key", null)?.let(::JSONObject)
    private fun notifications() = context.getSystemService(NotificationManager::class.java).activeNotifications
    private fun arm(rule: DhikrReminder, window: DhikrWindow) {
        repo.save(rule)
        DhikrReminderScheduler.refresh(context, rearm = true, nowMillis = window.startMillis - 1)
    }
    private fun holdNudgesUntil(end: Long): NotificationManager {
        val manager = context.getSystemService(NotificationManager::class.java)
        Shadows.shadowOf(manager).setNotificationPolicyAccessGranted(true)
        PrefsManager.markManualSilenceActive(context, AudioManager.RINGER_MODE_NORMAL, NotificationManager.INTERRUPTION_FILTER_ALL)
        PrefsManager.setManualSilenceEndsAtMillis(context, end)
        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        return manager
    }
    private fun releaseHold(manager: NotificationManager) {
        PrefsManager.clearManualSilenceState(context)
        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
    }
    private fun at(hour: Int, minuteOfHour: Int = 0): Long =
        friday.atTime(hour, minuteOfHour).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    // ---- the engine ----

    private fun oldTimes(cadence: DhikrCadence, start: Long, end: Long): List<Long> {
        val duration = end - start
        val unit = 15 * minute
        val count = minOf(if (cadence == DhikrCadence.GENTLE) 3 else 5, ((duration - 1) / unit + 1).toInt())
        return (0 until count).map { start + it * maxOf(unit, duration / count) }.filter { it < end }
    }
    @Test fun gentleAndBalancedEndThirtyMinutesBeforeTheEndOnALongPeriod() {
        for (cadence in listOf(DhikrCadence.GENTLE, DhikrCadence.BALANCED)) {
            val rule = daily(480, 540, cadence)
            for (minutes in 1..907) for (extra in longArrayOf(0, 1, 17, 29_999, 59_999)) {
                val start = at(8); val end = start + minutes * minute + extra
                val window = DhikrWindow(start, end, "k", friday)
                val times = dhikrNudgeTimes(rule, window)
                val label = "$cadence $minutes min +$extra ms: $times"
                val cap = if (cadence == DhikrCadence.GENTLE) 3 else 5
                assertTrue(label, times.isNotEmpty() && times.size <= cap)
                assertEquals(label, start, times.first())
                assertTrue(label, times.all { it in start until end })
                times.zipWithNext().forEach { (a, b) -> assertTrue(label, b - a >= 15 * minute) }
                val old = oldTimes(cadence, start, end)
                assertEquals(label, old.size, times.size)
                val duration = end - start
                val count = times.size
                val widened = count > 1 && (duration - LAST_NUDGE_LEAD_MILLIS) / (count - 1) > duration / count &&
                    (duration - LAST_NUDGE_LEAD_MILLIS) / (count - 1) >= 15 * minute
                if (widened) {
                    // The last notification lands 30 minutes before the end (a few milliseconds of integer division aside).
                    assertTrue(label, end - times.last() in LAST_NUDGE_LEAD_MILLIS until LAST_NUDGE_LEAD_MILLIS + count)
                } else assertEquals(label, old, times)
            }
        }
    }
    @Test fun aPeriodOfNinetyMinutesOrLessKeepsTodaysSchedule() {
        val rule = daily(480, 540)
        for (minutes in 1..90) {
            val start = at(8); val end = start + minutes * minute
            assertEquals("$minutes min", oldTimes(DhikrCadence.GENTLE, start, end), dhikrNudgeTimes(rule, DhikrWindow(start, end, "k", friday)))
        }
        // The shortest morning of the year in Tunis, 87 minutes: 0, 29 and 58 minutes, 29 minutes before the end.
        val start = at(8)
        assertEquals(listOf(0L, 29 * minute, 58 * minute), dhikrNudgeTimes(rule, DhikrWindow(start, start + 87 * minute, "k", friday)).map { it - start })
        // Beyond 90 minutes the last one comes 30 minutes before the end and the middle one halfway.
        assertEquals(listOf(0L, 90 * minute + 30_000, 181 * minute), dhikrNudgeTimes(rule, DhikrWindow(start, start + 211 * minute, "k", friday)).map { it - start })
    }
    @Test fun severalPeriodsNeverDivideByZeroAndKeepTheGaps() {
        val lengths = listOf(15, 20, 30, 40, 45, 60, 90, 100, 120, 211, 400)
        for (cadence in listOf(DhikrCadence.GENTLE, DhikrCadence.BALANCED)) {
            val rule = daily(480, 540, cadence)
            for (count in 2..3) {
                var combos = listOf(emptyList<Int>())
                repeat(count) { combos = combos.flatMap { c -> lengths.map { c + it } } }
                for (combo in combos) {
                    var cursor = at(6)
                    val windows = combo.map { length ->
                        DhikrWindow(cursor, cursor + length * minute, "k", friday).also { cursor += (length + 5) * minute }
                    }
                    val times = dhikrNudgeTimes(rule, windows)
                    val label = "$cadence $combo: $times"
                    assertEquals(label, times.sorted(), times)
                    windows.forEach { w ->
                        val inside = times.filter { it in w.startMillis until w.endMillis }
                        assertTrue(label, inside.isNotEmpty())
                        inside.zipWithNext().forEach { (a, b) -> assertTrue(label, b - a >= 15 * minute) }
                    }
                }
            }
        }
    }

    // ---- delivery ----

    @Test fun aHeldFirstNotificationLeavesTheLaterOnesAhead() {
        val rule = daily(480, 660); val window = window(rule); arm(rule, window)
        val first = eventOf(rule.id)!!
        val manager = holdNudgesUntil(window.startMillis + 60 * minute)
        deliver(first, window.startMillis)
        assertEquals(0, notifications().size)
        releaseHold(manager)
        val release = window.startMillis + 60 * minute
        DhikrReminderScheduler.refresh(context, nowMillis = release)
        deliver(eventOf(rule.id)!!, release)
        val record = done(JSONObject(first).getString("eventId"))!!
        assertEquals("shown", record.getString("outcome"))
        assertTrue(record.getBoolean("late"))
        assertEquals(window.startMillis, record.getLong("slot"))
        assertEquals(1, notifications().size)
        // Three hours: 0, 75 and 150 minutes. The middle one is still ahead.
        assertEquals(window.startMillis + 75 * minute, JSONObject(eventOf(rule.id)!!).getLong("at"))
    }
    @Test fun aLateDeliveryNeverLeavesTwoAlertsCloserThanTheMinimumGap() {
        // 93 minutes (a December morning): 0, 31.5 and 63 minutes; the first is held to +60 by a silence.
        val rule = daily(480, 573); val window = window(rule); arm(rule, window)
        val first = eventOf(rule.id)!!
        val manager = holdNudgesUntil(window.startMillis + 60 * minute)
        deliver(first, window.startMillis)
        releaseHold(manager)
        val release = window.startMillis + 60 * minute
        DhikrReminderScheduler.refresh(context, nowMillis = release)
        deliver(eventOf(rule.id)!!, release)
        val id = window.progressKey
        val firstId = JSONObject(first).getString("eventId")
        assertEquals("shown", done(firstId)!!.getString("outcome"))
        for (slot in 1..2) {
            val merged = done("$id:$slot")!!
            assertEquals("coalesced", merged.getString("outcome"))
            assertEquals(firstId, merged.getString("by"))
        }
        // Nothing of this period is left to arm.
        assertNotEquals(id, JSONObject(eventOf(rule.id)!!).getString("occurrence"))
        assertEquals(1, notifications().size)
    }
    @Test fun aDeliveryThatIsOnlyAFewSecondsLateMergesNothing() {
        // 40 minutes: 0, 15 and 30 minutes, the closest the engine allows; each one arrives a second late.
        val rule = daily(480, 520); val window = window(rule); arm(rule, window)
        repeat(3) {
            val pending = eventOf(rule.id)!!
            deliver(pending, JSONObject(pending).getLong("at") + 1_000)
        }
        val shown = (0..2).count { done("${window.progressKey}:$it")?.getString("outcome") == "shown" }
        assertEquals(3, shown)
    }
    @Test fun theAppsOwnSpacingBetweenTwoRemindersIsNotLateness() {
        val first = daily(480, 520); val second = daily(480, 520).copy(dhikrId = "salah_istighfar")
        repo.save(first); repo.save(second)
        val window = window(first)
        DhikrReminderScheduler.refresh(context, rearm = true, nowMillis = window.startMillis - 1)
        deliver(eventOf(first.id)!!, window.startMillis)
        deliver(eventOf(second.id)!!, window.startMillis + 1)
        val deferred = eventOf(second.id)!!
        assertEquals(window.startMillis + 2 * minute, JSONObject(deferred).getLong("at"))
        deliver(deferred, window.startMillis + 2 * minute + 1_000)
        val key = window(second).progressKey
        assertEquals("shown", done("$key:0")!!.getString("outcome"))
        assertEquals("spacing", done("$key:0")!!.getString("was"))
        // The 15-minute slot behind it is still ahead.
        assertNull(done("$key:1"))
        assertEquals(window.startMillis + 15 * minute, JSONObject(eventOf(second.id)!!).getLong("at"))
    }
    @Test fun aLateDeliveryDoesNotSwallowTheNextPeriodsFirstNotification() {
        // Two periods five minutes apart: 08:00-10:00 gets two slots (08:00, 09:30), 10:05-12:00 one.
        val rule = daily(480, 600).copy(extraIntervals = listOf(DhikrInterval(DhikrTime(minuteOfDay = 605), DhikrTime(minuteOfDay = 720), false)))
        val window = window(rule); arm(rule, window)
        deliver(eventOf(rule.id)!!, window.startMillis)
        val middle = eventOf(rule.id)!!
        assertEquals(at(9, 30), JSONObject(middle).getLong("at"))
        // It arrives 25 minutes late: ten minutes before the next period begins.
        deliver(middle, at(9, 55))
        val id = window.progressKey
        assertEquals("shown", done("$id:1")!!.getString("outcome"))
        assertNull(done("$id:2"))
        assertEquals(at(10, 5), JSONObject(eventOf(rule.id)!!).getLong("at"))
    }
    @Test fun aPostWithOnlyAMomentLeftIsSkippedAndTheReadingStaysOpen() {
        val rule = daily(480, 540); val window = window(rule); arm(rule, window)
        val pending = eventOf(rule.id)!!
        deliver(pending, window.endMillis - 90_000)
        assertEquals(0, notifications().size)
        assertEquals("too_late", done(JSONObject(pending).getString("eventId"))!!.getString("outcome"))
        assertEquals(DhikrOccurrenceStatus.OPEN, repo.state.value.occurrences.getValue(window.progressKey).status)
    }
    @Test fun anExplicitStepStillPostsOnTimeWithAMomentLeft() {
        // 47 minutes with a 45-minute step: the second notification is due two minutes before the end, as asked.
        val rule = daily(480, 527, DhikrCadence.CUSTOM).copy(intervalMinutes = 45)
        val window = window(rule); arm(rule, window)
        deliver(eventOf(rule.id)!!, window.startMillis)
        val second = eventOf(rule.id)!!
        assertEquals(window.startMillis + 45 * minute, JSONObject(second).getLong("at"))
        deliver(second, window.startMillis + 45 * minute)
        assertEquals("shown", done("${window.progressKey}:1")!!.getString("outcome"))
    }
    @Test fun threeRemindersOnTheSameSlotAreAllShownAndNoneIsMerged() {
        val rules = listOf(daily(480, 540), daily(480, 540).copy(dhikrId = "salah_istighfar"),
            daily(480, 540).copy(dhikrId = "ayat_kursi", targetCount = 3))
        rules.forEach { repo.save(it) }
        val window = window(rules.first())
        DhikrReminderScheduler.refresh(context, rearm = true, nowMillis = window.startMillis - 1)
        // Each waits its two-minute turn behind the one before; the wait is the app's spacing, not lateness.
        var now = window.startMillis
        repeat(6) {
            rules.forEach { rule ->
                val pending = eventOf(rule.id)?.let(::JSONObject)
                if (pending != null && pending.getLong("at") <= now + 1_000 &&
                    pending.getString("occurrence") == window(rule).progressKey) deliver(pending.toString(), now + 1_000)
            }
            now += 2 * minute
        }
        rules.forEach { rule ->
            assertEquals(rule.dhikrId, "shown", done("${window(rule).progressKey}:0")!!.getString("outcome"))
        }
    }
    @Test fun aVeryShortPeriodStillGetsItsFirstNotification() {
        val rule = daily(480, 482); val window = window(rule); arm(rule, window)
        deliver(eventOf(rule.id)!!, window.startMillis)
        assertEquals(1, notifications().size)
    }
    @Test fun theRecordsSayWhatTheRingerWas() {
        val audio = context.getSystemService(AudioManager::class.java)
        val rule = daily(480, 520); val window = window(rule); arm(rule, window)
        audio.ringerMode = AudioManager.RINGER_MODE_SILENT
        val silent = eventOf(rule.id)!!
        deliver(silent, window.startMillis)
        assertEquals("silent", done(JSONObject(silent).getString("eventId"))!!.getString("ringer"))
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        val normal = eventOf(rule.id)!!
        deliver(normal, JSONObject(normal).getLong("at"))
        assertFalse(done(JSONObject(normal).getString("eventId"))!!.has("ringer"))
    }
    @Test fun aQuietReminderNeverBlamesTheRinger() {
        val audio = context.getSystemService(AudioManager::class.java)
        val rule = daily(480, 520).copy(vibrate = false); val window = window(rule); arm(rule, window)
        audio.ringerMode = AudioManager.RINGER_MODE_SILENT
        val pending = eventOf(rule.id)!!
        deliver(pending, window.startMillis)
        assertFalse(done(JSONObject(pending).getString("eventId"))!!.has("ringer"))
    }
    @Test fun finishingTheReadingStillEndsEveryNotification() {
        val rule = daily(480, 660); val window = window(rule); arm(rule, window)
        deliver(eventOf(rule.id)!!, window.startMillis)
        val session = repo.openSession(listOf(rule.dhikrId), occurrenceId = window.progressKey, now = window.startMillis + 1)
        repeat(100) { repo.count(session, 1, window.startMillis + 2) }
        assertEquals(DhikrOccurrenceStatus.COMPLETED, repo.state.value.occurrences.getValue(window.progressKey).status)
        assertNotEquals(window.progressKey, eventOf(rule.id)?.let { JSONObject(it).getString("occurrence") })
    }

    // ---- the evening period that runs through the night ----

    @Test fun anEveningPeriodThroughTheNightStopsNotifyingAtIsha() {
        val isha = DhikrReminderScheduler.resolveTime(context, DhikrTime(DhikrTimeKind.ISHA), friday)!!
        val nextFajr = DhikrReminderScheduler.resolveTime(context, DhikrTime(DhikrTimeKind.FAJR), friday.plusDays(1))!!
        for (cadence in listOf(DhikrCadence.GENTLE, DhikrCadence.BALANCED)) {
            val rule = evening(cadence)
            assertNull(DhikrReminderScheduler.validate(context, rule, friday))
            val window = window(rule)
            // Only the notifications stop at Isha: the period, the occurrence and the reading keep the whole span.
            assertEquals(isha, window.notificationEndMillis)
            assertEquals(nextFajr, window.endMillis)
            assertEquals(nextFajr, window.progressEndMillis)
            val times = dhikrNudgeTimes(rule, window)
            assertTrue(times.size >= 2 && times.all { it < isha })
            assertTrue(isha - times.last() in LAST_NUDGE_LEAD_MILLIS until LAST_NUDGE_LEAD_MILLIS + times.size)
        }
        // An explicit step is the user's: it runs to the end of the period.
        for (explicit in listOf(evening(DhikrCadence.HOURLY), evening(DhikrCadence.CUSTOM).copy(intervalMinutes = 45))) {
            val window = window(explicit)
            assertEquals(nextFajr, window.endMillis)
            assertEquals(nextFajr, window.notificationEndMillis)
        }
    }
    @Test fun anEveningPeriodThroughTheNightStaysTheCurrentOneUntilFajr() {
        val rule = evening(); val window = window(rule); arm(rule, window)
        val isha = window.notificationEndMillis
        // An hour after Isha the notifications are over, but tonight's period is still the one being read.
        val later = DhikrReminderScheduler.currentOrNextWindow(context, rule, isha + 60 * minute)!!
        assertEquals(window.progressKey, later.progressKey)
        assertEquals(window.endMillis, later.endMillis)
        assertEquals(isha, later.notificationEndMillis)
    }
    @Test fun anEveningPeriodThatIsNotOvernightKeepsItsEnd() {
        val maghrib = DhikrReminderScheduler.resolveTime(context, DhikrTime(DhikrTimeKind.MAGHRIB), friday)!!
        val preset = com.tunisianprayertimes.ui.eveningCollectionPreset()
        assertEquals(maghrib, window(preset).endMillis)
        // Starting within an hour of Isha, or ending within an hour after it, keeps the period whole.
        val lateStart = evening().copy(start = DhikrTime(DhikrTimeKind.ISHA, offsetMinutes = -30))
        assertEquals(DhikrReminderScheduler.resolveTime(context, DhikrTime(DhikrTimeKind.FAJR), friday.plusDays(1))!!, window(lateStart).endMillis)
        val shortEnd = evening().copy(end = DhikrTime(DhikrTimeKind.ISHA, offsetMinutes = 30), endNextDay = false)
        assertEquals(DhikrReminderScheduler.resolveTime(context, DhikrTime(DhikrTimeKind.ISHA), friday)!! + 30 * minute, window(shortEnd).endMillis)
        // A single dhikr in the evening hours is not an evening collection.
        assertEquals(at(23, 30), window(daily(1200, 1410)).endMillis)
    }
    @Test fun noNotificationOfACappedEveningPeriodIsPostedAfterIsha() {
        val rule = evening(); val window = window(rule); arm(rule, window)
        val isha = window.notificationEndMillis
        // A replay long after Isha (the phone was off) finds nothing to post.
        DhikrReminderScheduler.refresh(context, rearm = true, nowMillis = isha + 3 * 60 * minute)
        val pending = eventOf(rule.id)
        assertTrue(pending == null || JSONObject(pending).getString("occurrence") != window.progressKey)
        assertEquals(0, notifications().size)
    }

    // ---- what the notification says ----

    @Test fun theMorningPresetRunsFromFajrToDhuhr() {
        val preset = morningCollectionPreset()
        assertEquals(DhikrTimeKind.FAJR, preset.start.kind)
        assertEquals(DhikrTimeKind.DHUHR, preset.end.kind)
        val drafted = defaultDhikrReminder("morning_kingdom", DhikrCategory.MORNING)
        assertEquals(DhikrTimeKind.DHUHR, drafted.end.kind)
        assertNull(DhikrReminderScheduler.validate(context, preset, friday))
        // The default silence leaves the morning three notifications: after Fajr, in the morning, before Dhuhr.
        val times = dhikrNudgeTimes(preset, window(preset))
        assertEquals(3, times.size)
        assertEquals(LAST_NUDGE_LEAD_MILLIS, window(preset).endMillis - times.last())
    }
    @Test fun aGentleCollectionNotificationSaysWhenThePeriodEnds() {
        val rule = morningCollectionPreset(); val window = window(rule); arm(rule, window)
        deliver(eventOf(rule.id)!!, window.startMillis)
        val text = notifications().single().notification.extras.getCharSequence(Notification.EXTRA_TEXT)!!.toString()
        assertEquals(DhikrReminderScheduler.periodEndText(context, window), text)
        // The text ends on a word, never on the clock, so a phone laid out left to right does not move the time.
        assertTrue(text.last().isLetter())
        assertTrue(text.startsWith("تنتهي فترة التذكير"))
    }
    @Test fun anExplicitStepKeepsTheNotificationWithoutABody() {
        val rule = morningCollectionPreset().copy(cadence = DhikrCadence.CUSTOM, intervalMinutes = 60)
        val window = window(rule); arm(rule, window)
        deliver(eventOf(rule.id)!!, window.startMillis)
        assertNull(notifications().single().notification.extras.getCharSequence(Notification.EXTRA_TEXT))
    }
    @Test fun thePeriodEndIsAClockTimeFollowedByTheRightPartOfTheDay() {
        fun text(hour: Int, minuteOfHour: Int) =
            DhikrReminderScheduler.periodEndText(context, DhikrWindow(at(4), at(hour, minuteOfHour), "k", friday))
        assertTrue(text(6, 21).contains("06:21") && text(6, 21).endsWith("صباحًا"))
        assertTrue(text(12, 28).contains("12:28") && text(12, 28).endsWith("ظهرًا"))
        assertTrue(text(17, 42).contains("17:42") && text(17, 42).endsWith("مساءً"))
        assertTrue(text(2, 10).contains("02:10") && text(2, 10).endsWith("ليلًا"))
        val isha = DhikrReminderScheduler.resolveTime(context, DhikrTime(DhikrTimeKind.ISHA), friday)!!
        assertEquals("تنتهي فترة التذكير عند أذان العشاء",
            DhikrReminderScheduler.periodEndText(context, DhikrWindow(at(17), isha, "k", friday)))
        // A period that runs through the night says the notifications stop at Isha, not that the period ends there.
        val capped = window(evening())
        assertEquals("تتوقف الإشعارات عند أذان العشاء", DhikrReminderScheduler.periodEndText(context, capped))
    }
}

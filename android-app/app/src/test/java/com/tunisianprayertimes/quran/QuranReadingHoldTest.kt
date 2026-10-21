package com.tunisianprayertimes.quran

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.adhkar.DhikrCadence
import com.tunisianprayertimes.adhkar.DhikrCatalog
import com.tunisianprayertimes.adhkar.DhikrOccurrenceStatus
import com.tunisianprayertimes.adhkar.DhikrReadingPresence
import com.tunisianprayertimes.adhkar.DhikrReminder
import com.tunisianprayertimes.adhkar.DhikrReminderScheduler
import com.tunisianprayertimes.adhkar.DhikrRepository
import com.tunisianprayertimes.adhkar.DhikrTime
import com.tunisianprayertimes.quran.hifz.HifzGrade
import com.tunisianprayertimes.quran.hifz.HifzKind
import com.tunisianprayertimes.quran.hifz.HifzMistake
import com.tunisianprayertimes.quran.hifz.HifzPoint
import com.tunisianprayertimes.quran.hifz.HifzReminder
import com.tunisianprayertimes.quran.hifz.HifzRepository
import com.tunisianprayertimes.quran.hifz.HifzSettings
import com.tunisianprayertimes.quran.hifz.HifzTimes
import com.tunisianprayertimes.quran.hifz.HifzWhen
import com.tunisianprayertimes.quran.hifz.graded
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.TimeZone

/**
 * The app's own reminders wait while the Qur'an is read in the app and come once the reading ends: an adhkar nudge on
 * the path a silence holds it on, once, inside its period and while its count is not done; the reminder of «المراجعة»
 * still owed, asking only what is still due.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class QuranReadingHoldTest {
    private val minute = 60_000L
    private lateinit var context: Context
    private lateinit var repo: DhikrRepository
    private lateinit var zone: TimeZone
    private val friday = LocalDate.of(2026, 9, 25)

    @Before fun setup() {
        zone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Africa/Tunis"))
        context = ApplicationProvider.getApplicationContext()
        listOf("adhkar_reminders", "adhkar_schedule", "adhkar_schedule_v2", "prayer_silence_prefs", "wake_alarm_scheduler", "quran_hifz")
            .forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
        DhikrRepository.clearMemoryCache()
        DhikrReadingPresence.occurrenceId = null
        QuranReading.end(context, 0L)
        repo = DhikrRepository(context)
    }
    @After fun teardown() {
        QuranReading.end(context, 0L)
        TimeZone.setDefault(zone)
        DhikrRepository.clearMemoryCache()
    }

    /** Friday 08:00 to 18:00, a goal of [target]. */
    private fun rule(target: Int = 1) = DhikrReminder(dhikrId = DhikrCatalog.SALAWAT_ID, targetCount = target,
        daysOfWeek = setOf(5), start = DhikrTime(minuteOfDay = 480), end = DhikrTime(minuteOfDay = 1080))
    private fun window(rule: DhikrReminder) = requireNotNull(DhikrReminderScheduler.resolveWindow(context, rule, friday))
    private val schedule get() = context.getSharedPreferences("adhkar_schedule_v2", 0)
    private fun event(rule: DhikrReminder) = JSONObject(schedule.getString("event:" + rule.id, null)!!)
    private fun deliver(event: JSONObject, at: Long) = DhikrReminderScheduler.receive(context,
        Intent().setAction(DhikrReminderScheduler.ACTION_REMIND).putExtra("event", event.toString()), at)
    private fun outcome(event: JSONObject): String? =
        schedule.getString("done:" + event.getString("eventId"), null)?.let { JSONObject(it).getString("outcome") }
    /** Nudges shown: each replaces the last in the shade, so they are counted where the scheduler records them. */
    private fun shown() = schedule.all.count { (key, value) ->
        key.startsWith("done:") && value is String && JSONObject(value).optString("outcome") == "shown"
    }
    /** Ends the reading at [at] and brings what it held, as the reader's closing does. */
    private fun leaveReader(at: Long) {
        QuranReading.end(context, at)
        QuranReading.release(context, at)
    }

    @Test fun aNudgeWaitsWhileTheQuranIsRead_andComesOnceWhenTheReaderIsLeft() {
        val rule = rule(); repo.save(rule)
        val window = window(rule)
        DhikrReminderScheduler.refresh(context, rearm = true, nowMillis = window.startMillis - 1)
        val due = event(rule)
        // Read since a minute before it: the reading ends 9 minutes after it unless more is done.
        QuranReading.opened(context, window.startMillis - minute)
        deliver(due, window.startMillis)
        val held = event(rule)
        assertEquals(0, shown())
        assertEquals("silence", held.getString("deferredFor"))
        assertEquals(window.startMillis + 9 * minute, held.getLong("at"))
        assertNull(outcome(held))
        // Left 3 minutes in: the same nudge comes then.
        val left = window.startMillis + 3 * minute
        leaveReader(left)
        val released = event(rule)
        assertEquals(held.getString("eventId"), released.getString("eventId"))
        assertEquals(left, released.getLong("at"))
        deliver(released, left)
        assertEquals(1, shown())
        assertEquals(1, context.getSystemService(NotificationManager::class.java).activeNotifications.size)
        // Its alarm come again, or the reading's own end, brings nothing more.
        deliver(released, left + 1)
        deliver(held, window.startMillis + 9 * minute)
        assertEquals(1, shown())
    }

    @Test fun aReaderLeftOpenWithNothingDone_holdsANudgeNoLongerThanTenMinutes() {
        val rule = rule(); repo.save(rule)
        val window = window(rule)
        DhikrReminderScheduler.refresh(context, rearm = true, nowMillis = window.startMillis - 1)
        // The last touch 4 minutes before the nudge: it comes 6 minutes after its time, the reader still open.
        QuranReading.opened(context, window.startMillis - 4 * minute)
        deliver(event(rule), window.startMillis)
        val held = event(rule)
        assertEquals(window.startMillis + 6 * minute, held.getLong("at"))
        deliver(held, held.getLong("at"))
        assertEquals("shown", outcome(held))
    }

    @Test fun tenMinutesWithNothingDone_isNoReading() {
        val rule = rule(); repo.save(rule)
        val window = window(rule)
        DhikrReminderScheduler.refresh(context, rearm = true, nowMillis = window.startMillis - 1)
        val due = event(rule)
        QuranReading.opened(context, window.startMillis - 10 * minute)
        deliver(due, window.startMillis)
        assertEquals("shown", outcome(due))
    }

    @Test fun whatIsDoneInTheReader_keepsTheNudgeWaiting() {
        val rule = rule(); repo.save(rule)
        val window = window(rule)
        DhikrReminderScheduler.refresh(context, rearm = true, nowMillis = window.startMillis - 1)
        QuranReading.opened(context, window.startMillis - minute)
        deliver(event(rule), window.startMillis)
        // A page turned 8 minutes in: at the reading's first end it waits again, 10 minutes from that page.
        QuranReading.active(context, window.startMillis + 8 * minute)
        deliver(event(rule), window.startMillis + 9 * minute)
        val again = event(rule)
        assertEquals(0, shown())
        assertEquals(window.startMillis + 18 * minute, again.getLong("at"))
        deliver(again, again.getLong("at"))
        assertEquals(1, shown())
    }

    @Test fun aLongReadingBringsOneNudge_notTheSeriesItHeld() {
        val rule = rule(100).copy(cadence = DhikrCadence.CUSTOM, intervalMinutes = 15); repo.save(rule)
        val window = window(rule)
        DhikrReminderScheduler.refresh(context, rearm = true, nowMillis = window.startMillis - 1)
        QuranReading.opened(context, window.startMillis - minute)
        // Two hours of reading, a page every 5 minutes: each alarm meanwhile comes at its time and waits again.
        var now = window.startMillis
        while (now < window.startMillis + 120 * minute) {
            event(rule).takeIf { it.getLong("at") <= now }?.let { deliver(it, it.getLong("at")) }
            QuranReading.active(context, now)
            now += 5 * minute
        }
        assertEquals(0, shown())
        leaveReader(now)
        val released = event(rule)
        assertEquals(now, released.getLong("at"))
        deliver(released, now)
        assertEquals(1, shown())
        // The 8 slots it passed are not brought one by one: the next nudge is the next slot's.
        assertTrue(event(rule).getLong("at") > now)
        assertEquals(1, shown())
    }

    @Test fun aNudgeHeldToTheEndOfItsPeriod_isDropped() {
        val rule = rule().copy(end = DhikrTime(minuteOfDay = 540)); repo.save(rule)
        val window = window(rule)
        DhikrReminderScheduler.refresh(context, rearm = true, nowMillis = window.startMillis - 1)
        val due = event(rule)
        QuranReading.opened(context, window.startMillis - minute)
        var now = window.startMillis
        while (now <= window.endMillis + 5 * minute) {
            event(rule).takeIf { it.getLong("at") <= now }?.let { deliver(it, it.getLong("at")) }
            QuranReading.active(context, now)
            now += 5 * minute
        }
        assertEquals("silenced", outcome(due))
        leaveReader(now)
        assertNotEquals(due.getString("occurrence"), event(rule).getString("occurrence"))
        assertEquals(0, shown())
    }

    @Test fun aGoalReachedWhileTheQuranIsRead_bringsNothingAfter() {
        val rule = rule(); repo.save(rule)
        val window = window(rule)
        DhikrReminderScheduler.refresh(context, rearm = true, nowMillis = window.startMillis - 1)
        QuranReading.opened(context, window.startMillis - minute)
        deliver(event(rule), window.startMillis)
        val occurrence = repo.ensureOccurrence(rule, window)
        val session = repo.openSession(listOf(rule.dhikrId), occurrenceId = occurrence.id, now = window.startMillis + 2 * minute)
        repo.count(session, 1, window.startMillis + 2 * minute)
        assertEquals(DhikrOccurrenceStatus.COMPLETED, repo.state.value.occurrences.getValue(occurrence.id).status)
        leaveReader(window.startMillis + 5 * minute)
        schedule.getString("event:" + rule.id, null)?.let { assertNotEquals(occurrence.id, JSONObject(it).getString("occurrence")) }
        assertEquals(0, shown())
    }

    @Test fun aNudgeHeldBeforeItsTime_keepsItsTimeWhenTheReadingEndsFirst() {
        val rule = rule(100).copy(cadence = DhikrCadence.CUSTOM, intervalMinutes = 15); repo.save(rule)
        val window = window(rule)
        val nudge = window.startMillis + 15 * minute
        // The 08:15 nudge planned during a reading that ends 3 minutes after it.
        QuranReading.opened(context, nudge - 7 * minute)
        DhikrReminderScheduler.refresh(context, rearm = true, nowMillis = nudge - 5 * minute)
        val held = event(rule)
        assertEquals("silence", held.getString("deferredFor"))
        assertEquals(nudge + 3 * minute, held.getLong("at"))
        // Left 3 minutes before it: it comes at 08:15, not now.
        leaveReader(nudge - 3 * minute)
        val released = event(rule)
        assertEquals(nudge, released.getLong("at"))
        assertFalse(released.has("deferredFor"))
        deliver(released, nudge)
        assertEquals("shown", outcome(released))
    }

    @Test fun aSilenceThatOutlastsTheReading_stillHoldsTheNudge() {
        val rule = rule(); repo.save(rule)
        val window = window(rule)
        DhikrReminderScheduler.refresh(context, rearm = true, nowMillis = window.startMillis - 1)
        val notifications = context.getSystemService(NotificationManager::class.java)
        Shadows.shadowOf(notifications).setNotificationPolicyAccessGranted(true)
        val silenceEnd = window.startMillis + 20 * minute
        PrefsManager.markManualSilenceActive(context, AudioManager.RINGER_MODE_NORMAL, NotificationManager.INTERRUPTION_FILTER_ALL)
        PrefsManager.setManualSilenceEndsAtMillis(context, silenceEnd)
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        QuranReading.opened(context, window.startMillis - minute)
        // Held to the later end of the two: the silence's.
        deliver(event(rule), window.startMillis)
        assertEquals(silenceEnd, event(rule).getLong("at"))
        // The reader left: the silence still holds it.
        leaveReader(window.startMillis + 3 * minute)
        assertEquals(silenceEnd, event(rule).getLong("at"))
        assertEquals("silence", event(rule).getString("deferredFor"))
        PrefsManager.clearManualSilenceState(context)
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        deliver(event(rule), silenceEnd)
        assertEquals(1, shown())
    }

    /** A slip two hours old, asked at every reminder: due at a reminder at this very minute, which is armed. */
    private class Review(val context: Context, id: Long) {
        val clock: ZonedDateTime = ZonedDateTime.now()
        val day = clock.toLocalDate().toEpochDay()
        val time = HifzWhen.At(clock.hour * 60 + clock.minute)
        val prefs = context.getSharedPreferences("quran_hifz", Context.MODE_PRIVATE)
        val slip: HifzPoint
        init {
            HifzSettings.write(context, HifzSettings(times = HifzTimes(minutes = setOf(time.minute))))
            val at = HifzReminder.slotAt(context, day, time)!!
            slip = HifzPoint(id = id, verse = QuranVerseReference(2, 3), mistakes = listOf(HifzMistake(HifzKind.Word)),
                dueDay = day, createdAt = at - 120 * 60_000L, lastSlipAt = at - 120 * 60_000L, fresh = 3)
            HifzRepository.restore(context, slip)
            prefs.edit().putString("armed", "$day|${time.key}|$at").putLong("shown_at", at - 60 * 60_000L).commit()
        }
        fun deliver() = HifzReminder.deliver(context, day, time)
        val owed get() = prefs.getString("armed", null).orEmpty().startsWith("$day|${time.key}|")
        /** When the reminder's alarm is set for. */
        @Suppress("DEPRECATION")
        val alarmAt get() = Shadows.shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms
            .single { Shadows.shadowOf(it.operation).savedIntent.action == HifzReminder.ACTION_REMIND }.triggerAtTime
        val posted get() = Shadows.shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.size
        fun close() {
            HifzRepository.delete(context, slip.id)
            HifzReminder.clearReminder(context)
            prefs.edit().clear().commit()
        }
    }

    @Test @Config(sdk = [35]) fun theReviewReminderWaitsWhileTheQuranIsRead_andComesWhenTheReaderIsLeft() {
        Shadows.shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val review = Review(context, 626_262L)
        try {
            QuranReading.opened(context)
            review.deliver()
            assertEquals(0, review.posted)
            // Still owed, its alarm moved to the reading's end.
            assertTrue(review.owed)
            assertTrue(review.alarmAt >= System.currentTimeMillis() + 9 * minute)
            // The reader left: armed for now, and its alarm asks it.
            leaveReader(System.currentTimeMillis())
            assertTrue(review.owed)
            assertTrue(review.alarmAt <= System.currentTimeMillis() + 5_000L)
            review.deliver()
            assertEquals(1, review.posted)
        } finally {
            review.close()
        }
    }

    @Test @Config(sdk = [35]) fun aPointAnsweredWhileTheQuranIsRead_isNotAskedWhenTheReadingEnds() {
        Shadows.shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val review = Review(context, 636_363L)
        try {
            QuranReading.opened(context)
            review.deliver()
            assertEquals(0, review.posted)
            // Answered in the review meanwhile: its next question is a while away.
            HifzRepository.update(context, review.slip.graded(HifzGrade.Held, review.day, System.currentTimeMillis()))
            leaveReader(System.currentTimeMillis())
            assertFalse(review.owed)
            review.deliver()
            assertEquals(0, review.posted)
        } finally {
            review.close()
        }
    }
}

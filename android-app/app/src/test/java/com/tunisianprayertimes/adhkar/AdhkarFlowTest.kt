package com.tunisianprayertimes.adhkar

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.ui.fridayDhikrPreset
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.*
import java.util.TimeZone
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AdhkarFlowTest {
    private lateinit var context: Context
    private lateinit var repo: DhikrRepository
    private lateinit var zone: TimeZone
    private val friday = LocalDate.of(2026, 9, 25)
    @Before fun setup() {
        zone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Africa/Tunis"))
        context = ApplicationProvider.getApplicationContext()
        listOf("adhkar_reminders", "adhkar_schedule", "adhkar_schedule_v2").forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
        DhikrRepository.clearMemoryCache()
        DhikrReadingPresence.occurrenceId = null
        repo = DhikrRepository(context)
    }
    @After fun teardown() { TimeZone.setDefault(zone); DhikrReadingPresence.occurrenceId = null; DhikrRepository.clearMemoryCache() }
    private fun rule(target: Int = 100) = DhikrReminder(dhikrId = DhikrCatalog.SALAWAT_ID, targetCount = target,
        daysOfWeek = setOf(5), start = DhikrTime(minuteOfDay = 480), end = DhikrTime(minuteOfDay = 1080))
    private fun window(rule: DhikrReminder, date: LocalDate = friday) = requireNotNull(DhikrReminderScheduler.resolveWindow(context, rule, date))
    private fun event(): String = context.getSharedPreferences("adhkar_schedule_v2", 0).all.entries.first { it.key.startsWith("event:") }.value as String
    private fun deliver(value: String, at: Long) = DhikrReminderScheduler.receive(context, Intent().setAction(DhikrReminderScheduler.ACTION_REMIND).putExtra("event", value), at)

    @Test fun readingIsExplicitAtomicAndUndoCannotGoNegative() {
        val id = repo.openSession(listOf("salah_istighfar", "salah_salam"), DhikrCategory.SALAH)
        assertEquals(0, repo.state.value.sessions.getValue(id).counts["salah_istighfar"] ?: 0)
        repo.count(id, -1)
        repeat(3) { repo.count(id, 1) }
        repo.move(id, 1)
        assertEquals("salah_salam", repo.state.value.sessions.getValue(id).itemId)
        repo.move(id, -1)
        assertEquals(3, repo.state.value.sessions.getValue(id).counts["salah_istighfar"])
        DhikrRepository.clearMemoryCache()
        val restored = DhikrRepository(context)
        assertEquals(id, restored.state.value.lastSessionId)
        assertEquals(3, restored.state.value.sessions.getValue(id).counts["salah_istighfar"])
    }
    @Test fun independentReadingDoesNotCompleteAnyScheduledGoal() {
        val rule = rule(3); repo.save(rule)
        val occurrence = repo.ensureOccurrence(rule, window(rule))
        val independent = repo.openSession(listOf(rule.dhikrId))
        repo.count(independent, 1)
        assertEquals(0, repo.state.value.occurrences.getValue(occurrence.id).count)
        val scheduled = repo.openSession(listOf(rule.dhikrId), occurrenceId = occurrence.id)
        repeat(3) { repo.count(scheduled, 1, occurrence.startMillis + 1) }
        assertEquals(DhikrOccurrenceStatus.COMPLETED, repo.state.value.occurrences.getValue(occurrence.id).status)
        val nextWeek = repo.ensureOccurrence(rule, window(rule, friday.plusWeeks(1)))
        assertEquals(0, nextWeek.count)
    }
    @Test fun targetAndScheduleEditsPreserveHistoryAndDeleteCanBeUndone() {
        val rule = rule(100); repo.save(rule)
        val occurrence = repo.ensureOccurrence(rule, window(rule))
        val session = repo.openSession(listOf(rule.dhikrId), occurrenceId = occurrence.id)
        repeat(5) { repo.count(session, 1, occurrence.startMillis + 1) }
        repo.save(rule.copy(targetCount = 2), occurrence.startMillis + 2)
        assertEquals(100, repo.state.value.occurrences.getValue(occurrence.id).target)
        assertEquals(5, repo.state.value.occurrences.getValue(occurrence.id).count)
        val edited = repo.state.value.reminders.single()
        assertEquals(2, edited.revision)
        assertTrue(edited.notBeforeMillis >= occurrence.endMillis)
        repo.delete(rule.id)
        assertEquals(5, repo.state.value.sessions.getValue(session).counts[rule.dhikrId])
        repo.restore(edited)
        assertEquals(edited, repo.state.value.reminders.single())
    }
    @Test fun legacyCountersMigrateWithoutResetAndNewSessionIsExplicit() {
        context.getSharedPreferences("adhkar_reminders", 0).edit().putString("progress", """{"reading|salah_istighfar":2}""").commit()
        val first = repo.openSession(listOf("salah_istighfar"))
        assertEquals(2, repo.state.value.sessions.getValue(first).counts["salah_istighfar"])
        assertEquals(first, repo.openSession(listOf("salah_istighfar")))
        val fresh = repo.openSession(listOf("salah_istighfar"), fresh = true)
        assertNotEquals(first, fresh)
        assertEquals(0, repo.state.value.sessions.getValue(fresh).counts["salah_istighfar"] ?: 0)
    }
    @Test fun arabicSearchAndFavouritePreserveExactText() {
        assertEquals(normalizeDhikrSearch("أَذْكَارُ الْمَسَاءِ"), normalizeDhikrSearch("اذكار المساء"))
        val text = DhikrCatalog.find("salah_istighfar")!!.text
        repo.toggleFavourite("salah_istighfar")
        DhikrRepository.clearMemoryCache()
        assertTrue("salah_istighfar" in DhikrRepository(context).state.value.favourites)
        assertEquals(text, DhikrCatalog.find("salah_istighfar")!!.text)
    }
    @Test fun fridayPresetHasRealMaghribAndThreeNudgesIndependentOfTarget() {
        val preset = fridayDhikrPreset()
        assertEquals(setOf(5), preset.daysOfWeek)
        assertEquals(100, preset.targetCount)
        assertEquals(480, preset.start.minuteOfDay)
        assertEquals(DhikrTimeKind.MAGHRIB, preset.end.kind)
        val window = window(preset)
        val actual = DhikrReminderScheduler.resolveTime(context, DhikrTime(DhikrTimeKind.MAGHRIB), friday)
        assertEquals(actual, window.endMillis)
        val next = window(preset, friday.plusWeeks(1))
        assertEquals(DhikrReminderScheduler.resolveTime(context, DhikrTime(DhikrTimeKind.MAGHRIB), friday.plusWeeks(1)), next.endMillis)
        assertEquals(3, dhikrNudgeTimes(preset, window).size)
        assertEquals(dhikrNudgeTimes(preset, window), dhikrNudgeTimes(preset.copy(targetCount = 500), window))
        assertTrue(dhikrNudgeTimes(preset, window).all { it in window.startMillis until window.endMillis })
    }
    @Test fun overnightRequiresConsentAndUsesStartDayForOccurrence() {
        val invalid = rule().copy(start = DhikrTime(minuteOfDay = 22 * 60), end = DhikrTime(minuteOfDay = 120))
        assertNotNull(DhikrReminderScheduler.validate(context, invalid))
        val explicit = invalid.copy(endNextDay = true)
        assertNull(DhikrReminderScheduler.validate(context, explicit))
        val window = window(explicit)
        assertEquals(friday, window.date)
        assertEquals(friday.plusDays(1), Instant.ofEpochMilli(window.endMillis).atZone(ZoneId.systemDefault()).toLocalDate())
        assertNull(DhikrReminderScheduler.resolveWindow(context, explicit, friday.plusDays(1)))
    }
    @Test fun notificationDoesNotCountAndColdRefreshDoesNotDuplicateDelivery() {
        val rule = rule(); repo.save(rule)
        val window = window(rule)
        DhikrReminderScheduler.refresh(context, rearm = true, nowMillis = window.startMillis - 1)
        val event = event()
        DhikrReminderScheduler.refresh(context, nowMillis = window.startMillis)
        deliver(event, window.startMillis)
        deliver(event, window.startMillis)
        val notifications = context.getSystemService(NotificationManager::class.java).activeNotifications
        assertEquals(1, notifications.size)
        assertEquals(0, repo.state.value.occurrences.getValue(window.progressKey).count)
        assertEquals(listOf("متابعة الذكر", "بعد ٣٠ دقيقة"), notifications.single().notification.actions.map { it.title.toString() })
        val open = Shadows.shadowOf(notifications.single().notification.contentIntent).savedIntent
        assertEquals(window.progressKey, open.getStringExtra(DhikrReminderScheduler.EXTRA_OCCURRENCE_ID))
    }
    @Test fun completionSkipAndDisableRejectAlreadyDispatchedAlarms() {
        val rule = rule(1); repo.save(rule)
        val window = window(rule)
        DhikrReminderScheduler.refresh(context, rearm = true, nowMillis = window.startMillis - 1)
        val event = event()
        val occurrence = repo.ensureOccurrence(rule, window)
        val session = repo.openSession(listOf(rule.dhikrId), occurrenceId = occurrence.id)
        repo.count(session, 1, window.startMillis)
        deliver(event, window.startMillis)
        assertEquals(0, context.getSystemService(NotificationManager::class.java).activeNotifications.size)
        repo.count(session, -1, window.startMillis + 1)
        repo.skip(occurrence.id)
        deliver(event, window.startMillis + 2)
        repo.setEnabled(rule.id, false)
        deliver(event, window.startMillis + 3)
        assertEquals(0, context.getSystemService(NotificationManager::class.java).activeNotifications.size)
    }
    @Test fun expiredOrActivelyReadOccurrenceIsNotNudged() {
        val rule = rule(); repo.save(rule)
        val window = window(rule)
        DhikrReminderScheduler.refresh(context, rearm = true, nowMillis = window.startMillis - 1)
        val event = event()
        DhikrReadingPresence.occurrenceId = window.progressKey
        deliver(event, window.startMillis)
        DhikrReadingPresence.occurrenceId = null
        deliver(event, window.endMillis + 1)
        assertEquals(0, context.getSystemService(NotificationManager::class.java).activeNotifications.size)
    }
    @Test fun snoozeIsOccurrenceSpecificAndCannotCrossEnd() {
        val rule = rule(); repo.save(rule)
        val window = window(rule)
        val occurrence = repo.ensureOccurrence(rule, window)
        assertTrue(repo.snooze(occurrence.id, window.startMillis))
        assertEquals(window.startMillis + 30 * 60_000, repo.state.value.occurrences.getValue(occurrence.id).snoozedUntilMillis)
        assertFalse(repo.snooze(occurrence.id, window.endMillis - 10 * 60_000))
        assertEquals(0, repo.state.value.occurrences.getValue(occurrence.id).count)
    }
    @Test fun daylightSavingWindowUsesLocalInstants() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"))
        val rule = rule().copy(daysOfWeek = setOf(7), start = DhikrTime(minuteOfDay = 60), end = DhikrTime(minuteOfDay = 240))
        val spring = window(rule, LocalDate.of(2026, 3, 29))
        val autumn = window(rule, LocalDate.of(2026, 10, 25))
        assertEquals(2 * 60 * 60_000L, spring.endMillis - spring.startMillis)
        assertEquals(4 * 60 * 60_000L, autumn.endMillis - autumn.startMillis)
    }
    @Test @Config(sdk = [33]) fun permissionAndChannelRestrictionsKeepReadingAvailable() {
        val rule = rule(); repo.save(rule)
        val window = window(rule)
        DhikrReminderScheduler.refresh(context, rearm = true, nowMillis = window.startMillis - 1)
        val blockedEvent = event()
        Shadows.shadowOf(context as Application).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(DhikrReminderScheduler.notificationsEnabled(context))
        deliver(blockedEvent, window.startMillis)
        assertEquals(0, context.getSystemService(NotificationManager::class.java).activeNotifications.size)
        assertTrue(repo.state.value.reminders.single().enabled)
        val session = repo.openSession(listOf(rule.dhikrId), occurrenceId = window.progressKey)
        repo.count(session, 1, window.startMillis + 1)
        assertEquals(1, repo.state.value.occurrences.getValue(window.progressKey).count)
        Shadows.shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.deleteNotificationChannel(DhikrReminderScheduler.CHANNEL_ID)
        manager.createNotificationChannel(android.app.NotificationChannel(DhikrReminderScheduler.CHANNEL_ID, "Blocked", NotificationManager.IMPORTANCE_NONE))
        assertFalse(DhikrReminderScheduler.notificationsEnabled(context))
    }
    @Test fun delayedDeliveryDoesNotReplayMissedNudges() {
        val rule = rule().copy(cadence = DhikrCadence.HOURLY); repo.save(rule)
        val window = window(rule)
        DhikrReminderScheduler.refresh(context, rearm = true, nowMillis = window.startMillis - 1)
        val delayed = event()
        val actual = window.startMillis + 4 * 60 * 60_000 + 1
        deliver(delayed, actual)
        deliver(delayed, actual + 1)
        assertEquals(1, context.getSystemService(NotificationManager::class.java).activeNotifications.size)
        assertTrue(JSONObject(event()).getLong("at") > actual)
        assertEquals(0, repo.state.value.occurrences.getValue(window.progressKey).count)
    }
}

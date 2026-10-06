package com.tunisianprayertimes.adhkar

import android.app.Application
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.PrayerTimesRepository
import com.tunisianprayertimes.SilenceStatus
import com.tunisianprayertimes.ui.fridayDhikrPreset
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
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
        listOf("adhkar_reminders", "adhkar_schedule", "adhkar_schedule_v2", "prayer_silence_prefs", "wake_alarm_scheduler")
            .forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
        DhikrRepository.clearMemoryCache()
        DhikrReadingPresence.occurrenceId = null
        repo = DhikrRepository(context)
    }
    @After fun teardown() { TimeZone.setDefault(zone); DhikrReadingPresence.occurrenceId = null; DhikrRepository.clearMemoryCache() }
    private fun rule(target: Int = 100) = DhikrReminder(dhikrId = DhikrCatalog.SALAWAT_ID, targetCount = target,
        daysOfWeek = setOf(5), start = DhikrTime(minuteOfDay = 480), end = DhikrTime(minuteOfDay = 1080))
    private fun window(rule: DhikrReminder, date: LocalDate = friday) = requireNotNull(DhikrReminderScheduler.resolveWindow(context, rule, date))
    private fun splitRule(target: Int = 3) = rule(target).copy(end = DhikrTime(minuteOfDay = 600),
        extraIntervals = listOf(DhikrInterval(DhikrTime(minuteOfDay = 960), DhikrTime(minuteOfDay = 1080), false)))
    private fun splitWindows(rule: DhikrReminder) = DhikrReminderScheduler.resolveWindows(context, rule, friday)
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
    @Test fun morningCollectionResetsAtNextFajrAndSurvivesRestartBeforeThen() {
        val items = listOf("sayyid_istighfar", "ayat_kursi")
        val firstFajr = DhikrReminderScheduler.resolveTime(context, DhikrTime(DhikrTimeKind.FAJR), friday)!!
        val nextFajr = DhikrReminderScheduler.resolveTime(context, DhikrTime(DhikrTimeKind.FAJR), friday.plusDays(1))!!
        val first = repo.openSession(items, DhikrCategory.MORNING, now = firstFajr,
            collectionReading = true)
        repo.count(first, 1, firstFajr + 1)
        repo.skipItem(first, firstFajr + 2)
        repo.skipItem(first, firstFajr + 3)
        DhikrRepository.clearMemoryCache()
        val restored = DhikrRepository(context)

        val beforeNextFajr = restored.openSession(items, DhikrCategory.MORNING,
            now = nextFajr - 1, collectionReading = true)
        assertEquals(first, beforeNextFajr)
        assertEquals(1, restored.state.value.sessions.getValue(first).counts[items.first()])
        assertTrue(items.last() in restored.state.value.sessions.getValue(first).skippedIds)

        val next = restored.openSession(items, DhikrCategory.MORNING, now = nextFajr,
            collectionReading = true)
        assertNotEquals(first, next)
        assertTrue(restored.state.value.sessions.getValue(next).counts.isEmpty())
        assertTrue(restored.state.value.sessions.getValue(next).skippedIds.isEmpty())
        assertEquals(0, restored.state.value.sessions.getValue(next).index)
    }
    @Test fun eveningCollectionResetsAtAsrAndKeepsProgressAcrossListEdits() {
        val items = listOf("sayyid_istighfar", "ayat_kursi")
        val firstAsr = DhikrReminderScheduler.resolveTime(context, DhikrTime(DhikrTimeKind.ASR), friday)!!
        val nextAsr = DhikrReminderScheduler.resolveTime(context, DhikrTime(DhikrTimeKind.ASR), friday.plusDays(1))!!
        val first = repo.openSession(items, DhikrCategory.EVENING, now = firstAsr,
            collectionReading = true)
        repo.count(first, 1, firstAsr + 1)
        val reordered = repo.openSession(items.reversed(), DhikrCategory.EVENING,
            now = firstAsr + 2, collectionReading = true)
        assertEquals(first, reordered)
        assertEquals(1, repo.state.value.sessions.getValue(first).counts[items.first()])
        val expandedItems = items.reversed() + "salah_salam"
        assertEquals(first, repo.openSession(expandedItems, DhikrCategory.EVENING,
            now = firstAsr + 3, collectionReading = true))
        assertEquals(1, repo.state.value.sessions.getValue(first).counts[items.first()])
        val beforeNextAsr = repo.openSession(expandedItems, DhikrCategory.EVENING,
            now = nextAsr - 1, collectionReading = true)
        assertEquals(first, beforeNextAsr)
        val next = repo.openSession(expandedItems, DhikrCategory.EVENING,
            now = nextAsr, collectionReading = true)
        assertNotEquals(first, next)
        assertTrue(repo.state.value.sessions.getValue(next).counts.isEmpty())
        val newReading = repo.openSession(expandedItems, DhikrCategory.EVENING, fresh = true,
            now = nextAsr + 1, collectionReading = true)
        assertNotEquals(next, newReading)
        assertEquals(newReading, repo.openSession(expandedItems, DhikrCategory.EVENING,
            now = nextAsr + 1, collectionReading = true))
    }
    @Test fun fullCollectionAndIndividualDhikrUseSeparateCounters() {
        val items = listOf("sayyid_istighfar", "ayat_kursi")
        val fajr = DhikrReminderScheduler.resolveTime(context, DhikrTime(DhikrTimeKind.FAJR), friday)!!
        val collection = repo.openSession(items, DhikrCategory.MORNING, now = fajr,
            collectionReading = true)
        repo.count(collection, 1, fajr + 1)
        val individual = repo.openSession(listOf(items.first()), DhikrCategory.MORNING, now = fajr + 2)
        assertNotEquals(collection, individual)
        assertEquals(0, repo.state.value.sessions.getValue(individual).counts[items.first()] ?: 0)
    }
    @Test fun scheduledCollectionGetsFreshCountersForEachWindow() {
        val items = listOf("sayyid_istighfar", "ayat_kursi")
        val rule = rule(1).copy(dhikrId = items.first(), collection = DhikrCategory.MORNING,
            daysOfWeek = (1..7).toSet())
        repo.save(rule)
        val firstWindow = window(rule)
        val secondWindow = window(rule, friday.plusDays(1))
        val firstOccurrence = repo.ensureOccurrence(rule, firstWindow)
        val secondOccurrence = repo.ensureOccurrence(rule, secondWindow)
        val first = repo.openSession(items, DhikrCategory.MORNING, firstOccurrence.id,
            now = firstWindow.startMillis, collectionReading = true)
        repo.count(first, 1, firstWindow.startMillis + 1)
        val second = repo.openSession(items, DhikrCategory.MORNING, secondOccurrence.id,
            now = secondWindow.startMillis, collectionReading = true)
        assertNotEquals(first, second)
        assertTrue(repo.state.value.sessions.getValue(second).counts.isEmpty())
        assertTrue(repo.state.value.sessions.getValue(second).skippedIds.isEmpty())
        assertEquals(0, repo.state.value.occurrences.getValue(secondOccurrence.id).count)
    }
    @Test fun morningReadingFromReminderIsDoneOnTheAdhkarTab() {
        val items = listOf("sayyid_istighfar", "ayat_kursi")
        val rule = rule(1).copy(dhikrId = items.first(), collection = DhikrCategory.MORNING)
        repo.save(rule)
        val window = window(rule)
        val occurrence = repo.ensureOccurrence(rule, window)
        val start = window.startMillis
        val fromReminder = repo.openSession(items, DhikrCategory.MORNING, occurrence.id, now = start,
            collectionReading = true)
        repo.count(fromReminder, 1, start + 1)
        repo.move(fromReminder, 1)
        repo.count(fromReminder, 1, start + 2)
        assertEquals(DhikrOccurrenceStatus.COMPLETED, repo.state.value.occurrences.getValue(occurrence.id).status)

        // The tab's morning card and the reminder scheduler both see this occasion as done.
        val cardKey = collectionReadingPeriodKey(context, DhikrCategory.MORNING, null, start + 3)
        assertTrue(repo.state.value.isCollectionPeriodComplete(DhikrCategory.MORNING, cardKey))
        assertTrue(DhikrReminderScheduler.isCollectionReadingDone(context, repo.state.value, rule, window))

        // Reading again from the card, even after the reminder window, keeps the counters.
        val fromCard = repo.openSession(items, DhikrCategory.MORNING, now = window.endMillis + 1,
            collectionReading = true)
        assertNotEquals(fromReminder, fromCard)
        assertEquals(mapOf(items[0] to 1, items[1] to 1), repo.state.value.sessions.getValue(fromCard).counts)
        assertTrue(repo.state.value.isCollectionPeriodComplete(DhikrCategory.MORNING, cardKey))

        // New Session still starts the occasion over.
        val fresh = repo.openSession(items, DhikrCategory.MORNING, fresh = true, now = window.endMillis + 2,
            collectionReading = true)
        assertTrue(repo.state.value.sessions.getValue(fresh).counts.isEmpty())
        assertFalse(repo.state.value.isCollectionPeriodComplete(DhikrCategory.MORNING, cardKey))
    }
    @Test fun morningProgressCarriesBetweenTheTabAndTheReminder() {
        val items = listOf("sayyid_istighfar", "ayat_kursi")
        val rule = rule(1).copy(dhikrId = items.first(), collection = DhikrCategory.MORNING)
        repo.save(rule)
        val window = window(rule)
        val occurrence = repo.ensureOccurrence(rule, window)
        val start = window.startMillis
        val fromReminder = repo.openSession(items, DhikrCategory.MORNING, occurrence.id, now = start,
            collectionReading = true)
        repo.count(fromReminder, 1, start + 1)

        val fromCard = repo.openSession(items, DhikrCategory.MORNING, now = start + 2, collectionReading = true)
        assertEquals(1, repo.state.value.sessions.getValue(fromCard).counts[items.first()])
        repo.move(fromCard, 1)
        repo.count(fromCard, 1, start + 3)

        // Tapping the reminder again picks up the tab's newer progress and completes its goal.
        assertEquals(fromReminder, repo.openSession(items, DhikrCategory.MORNING, occurrence.id, now = start + 4,
            collectionReading = true))
        assertEquals(mapOf(items[0] to 1, items[1] to 1), repo.state.value.sessions.getValue(fromReminder).counts)
        assertEquals(DhikrOccurrenceStatus.COMPLETED, repo.state.value.occurrences.getValue(occurrence.id).status)
    }
    @Test fun arabicSearchAndFavouritePreserveExactText() {
        assertEquals(normalizeDhikrSearch("أَذْكَارُ الْمَسَاءِ"), normalizeDhikrSearch("اذكار المساء"))
        val text = DhikrCatalog.find("salah_istighfar")!!.text
        repo.toggleFavourite("salah_istighfar")
        DhikrRepository.clearMemoryCache()
        assertTrue("salah_istighfar" in DhikrRepository(context).state.value.favourites)
        assertEquals(text, DhikrCatalog.find("salah_istighfar")!!.text)
    }
    @Test fun personalDhikrSupportsReadingRemindersEditAndDeleteUndo() {
        val saved = repo.saveCustom(DhikrEntry(id = "", title = "وردي", text = "سُبْحَانَ اللَّهِ وَبِحَمْدِهِ",
            reference = "ملاحظة شخصية", defaultCount = 10, categories = emptySet()))
        assertTrue(saved.id.startsWith("personal_"))
        assertTrue(saved.custom)
        assertEquals(saved, repo.state.value.findDhikr(saved.id))
        assertTrue(runCatching { repo.openSession(listOf("missing_dhikr")) }.isFailure)
        val session = repo.openSession(listOf(saved.id))
        repo.count(session, 1)
        assertEquals(10, repo.state.value.target(repo.state.value.sessions.getValue(session)))
        val reminder = DhikrReminder(dhikrId = saved.id, targetCount = 10, daysOfWeek = setOf(5),
            start = DhikrTime(minuteOfDay = 480), end = DhikrTime(minuteOfDay = 1080))
        assertNull(DhikrReminderScheduler.validate(context, reminder))
        repo.save(reminder)
        val edited = repo.saveCustom(saved.copy(title = "وردي المحدث", defaultCount = 5))
        assertEquals(saved.id, edited.id)
        assertEquals(listOf("وردي المحدث"), repo.state.value.customEntries.map { it.title })
        DhikrRepository.clearMemoryCache()
        val restored = DhikrRepository(context)
        assertEquals("وردي المحدث", restored.state.value.customEntries.single().title)
        assertEquals(5, restored.state.value.target(restored.state.value.sessions.getValue(session)))
        val removal = restored.deleteCustom(saved.id)
        assertNotNull(removal)
        assertTrue(restored.state.value.customEntries.isEmpty())
        assertTrue(restored.state.value.reminders.isEmpty())
        assertNull(restored.state.value.sessions[session])
        assertNotNull(DhikrReminderScheduler.validate(context, reminder))
        restored.restoreCustom(removal!!)
        assertEquals("وردي المحدث", restored.state.value.customEntries.single().title)
        assertEquals(1, restored.state.value.reminders.size)
        assertNotNull(restored.state.value.sessions[session])
        assertNull(DhikrReminderScheduler.validate(context, reminder))
    }
    @Test fun collectionMembershipIsEditableAndPersisted() {
        val custom = repo.saveCustom(DhikrEntry(id = "", title = "دعاء المساء", text = "دُعَاء",
            reference = "", defaultCount = 1, categories = emptySet()))
        val removedId = "evening_kingdom"
        repo.setCollectionMembership(DhikrCategory.EVENING, removedId, false)
        repo.setCollectionMembership(DhikrCategory.EVENING, custom.id, true)
        repo.setCollectionMembership(DhikrCategory.EVENING, "salah_istighfar", true)
        DhikrRepository.clearMemoryCache()
        val restored = DhikrRepository(context)
        val evening = restored.state.value.collectionEntries(DhikrCategory.EVENING)
        assertFalse(evening.any { it.id == removedId })
        assertTrue(evening.any { it.id == custom.id })
        assertTrue(evening.any { it.id == "salah_istighfar" })
        assertTrue(restored.state.value.isInCollection(DhikrCatalog.find("sayyid_istighfar")!!, DhikrCategory.MORNING))
        assertFalse(restored.state.value.isInCollection(custom, DhikrCategory.MORNING))
        val session = restored.openSession(evening.map { it.id }, DhikrCategory.EVENING)
        assertEquals(evening.map { it.id }, restored.state.value.sessions.getValue(session).itemIds)
        restored.setCollectionMembership(DhikrCategory.EVENING, removedId, true)
        restored.setCollectionMembership(DhikrCategory.EVENING, custom.id, false)
        val reverted = restored.state.value.collectionEntries(DhikrCategory.EVENING)
        assertTrue(reverted.any { it.id == removedId })
        assertFalse(reverted.any { it.id == custom.id })
    }
    @Test fun readerCanSkipOrRemoveItemsFromTheList() {
        val custom = repo.saveCustom(DhikrEntry(id = "", title = "دعاء المساء", text = "دُعَاء",
            reference = "", defaultCount = 10, categories = emptySet()))
        repo.setCollectionMembership(DhikrCategory.EVENING, custom.id, true)
        val evening = repo.state.value.collectionEntries(DhikrCategory.EVENING).map { it.id }
        val sessionId = repo.openSession(evening, DhikrCategory.EVENING)
        repo.skipItem(sessionId)
        var session = repo.state.value.sessions.getValue(sessionId)
        assertEquals(1, session.index)
        assertTrue(evening[0] in session.skippedIds)
        repo.move(sessionId, -1)
        repo.move(sessionId, 1)
        assertEquals(1, repo.state.value.sessions.getValue(sessionId).index)
        while (repo.state.value.sessions.getValue(sessionId).itemId != custom.id) repo.skipItem(sessionId)
        repo.removeItem(sessionId)
        session = repo.state.value.sessions.getValue(sessionId)
        assertFalse(custom.id in session.itemIds)
        assertFalse(repo.state.value.isInCollection(custom, DhikrCategory.EVENING))
        DhikrRepository.clearMemoryCache()
        assertTrue(custom.id !in DhikrRepository(context).state.value.sessions.getValue(sessionId).itemIds)
        val single = repo.openSession(listOf("salah_istighfar"))
        repo.removeItem(single)
        assertNull(repo.state.value.sessions[single])
    }
    @Test fun readerNavigationIsUnconstrainedAndWrapsAround() {
        val id = repo.openSession(listOf("salah_istighfar", "salah_salam", DhikrCatalog.SALAH_HUNDRED_ID))
        repo.move(id, 1)
        assertEquals(1, repo.state.value.sessions.getValue(id).index)
        repo.move(id, -1)
        repo.move(id, -1)
        assertEquals(2, repo.state.value.sessions.getValue(id).index)
        repo.move(id, 1)
        assertEquals(0, repo.state.value.sessions.getValue(id).index)
        repo.move(id, 0)
        assertEquals(0, repo.state.value.sessions.getValue(id).index)
    }
    @Test fun customIntervalDrivesNudgeFrequency() {
        val rule = rule(100).copy(cadence = DhikrCadence.CUSTOM, intervalMinutes = 15,
            start = DhikrTime(minuteOfDay = 480), end = DhikrTime(minuteOfDay = 600))
        val window = window(rule)
        val times = dhikrNudgeTimes(rule, window)
        assertEquals(8, times.size)
        assertEquals(window.startMillis, times.first())
        assertEquals(window.startMillis + 105 * 60_000L, times.last())
        assertNull(DhikrReminderScheduler.validate(context, rule))
        assertNotNull(DhikrReminderScheduler.validate(context, rule.copy(intervalMinutes = 5)))
        val restored = dhikrReminderFromJson(rule.copy(intervalMinutes = 5).toJson())
        assertEquals(15, restored.intervalMinutes)
        assertNull(DhikrReminderScheduler.validate(context, restored))
    }
    @Test fun splitDailyWindowsShareOneGoalButNeverNudgeInTheGap() {
        val rule = splitRule()
        assertNull(DhikrReminderScheduler.validate(context, rule))
        val windows = splitWindows(rule)
        assertEquals(2, windows.size)
        assertEquals(windows[0].progressKey, windows[1].progressKey)
        val times = dhikrNudgeTimes(rule, windows)
        assertTrue(times.isNotEmpty())
        assertTrue("Gentle cadence is capped across the day", times.size <= 3)
        assertTrue(times.all { time -> windows.any { time in it.startMillis until it.endMillis } })
        assertTrue(times.none { it in windows[0].endMillis until windows[1].startMillis })

        repo.save(rule)
        val gapNudge = DhikrReminderScheduler.nextNudge(context, rule, windows[0].endMillis + 60_000L)
        assertTrue(gapNudge != null && gapNudge in windows[1].startMillis until windows[1].endMillis)
        val morning = repo.ensureOccurrence(rule, windows[0])
        val afternoon = repo.ensureOccurrence(rule, windows[1])
        assertEquals(morning.id, afternoon.id)
        val session = repo.openSession(listOf(rule.dhikrId), occurrenceId = morning.id, now = windows[0].startMillis)
        repo.count(session, 1, windows[0].startMillis + 1)
        repo.count(session, 1, windows[0].endMillis + 60_000L)
        assertEquals("Reading remains available between notification intervals", 2,
            repo.state.value.occurrences.getValue(morning.id).count)
        repo.count(session, 1, windows[1].startMillis + 1)
        val completed = repo.state.value.occurrences.getValue(morning.id)
        assertEquals(3, completed.count)
        assertEquals(DhikrOccurrenceStatus.COMPLETED, completed.status)
    }
    @Test fun splitDailyWindowEventsHaveDistinctIdsAndBothCanDeliver() {
        val rule = splitRule(100).copy(cadence = DhikrCadence.HOURLY)
        repo.save(rule)
        val windows = splitWindows(rule)
        val prefs = context.getSharedPreferences("adhkar_schedule_v2", 0)
        DhikrReminderScheduler.refresh(context, rearm = true, nowMillis = windows[0].startMillis - 1)
        val morning = JSONObject(prefs.getString("event:" + rule.id, null)!!)
        assertEquals(windows[0].progressKey, morning.getString("occurrence"))
        assertTrue(morning.getLong("at") in windows[0].startMillis until windows[0].endMillis)
        deliver(morning.toString(), morning.getLong("at"))
        assertEquals("shown", JSONObject(prefs.getString("done:" + morning.getString("eventId"), "{}")!!).getString("outcome"))

        DhikrReminderScheduler.refresh(context, rearm = true, nowMillis = windows[1].startMillis - 1)
        val afternoon = JSONObject(prefs.getString("event:" + rule.id, null)!!)
        assertEquals(windows[1].progressKey, afternoon.getString("occurrence"))
        assertNotEquals(morning.getString("eventId"), afternoon.getString("eventId"))
        assertTrue(afternoon.getLong("at") in windows[1].startMillis until windows[1].endMillis)
        deliver(afternoon.toString(), afternoon.getLong("at"))
        assertEquals("shown", JSONObject(prefs.getString("done:" + afternoon.getString("eventId"), "{}")!!).getString("outcome"))
    }
    @Test fun completingMorningGoalSuppressesLaterIntervalNudges() {
        val rule = splitRule(1)
        repo.save(rule)
        val windows = splitWindows(rule)
        DhikrReminderScheduler.refresh(context, rearm = true, nowMillis = windows[0].startMillis - 1)
        val occurrence = repo.ensureOccurrence(rule, windows[0])
        val session = repo.openSession(listOf(rule.dhikrId), occurrenceId = occurrence.id, now = windows[0].startMillis)
        repo.count(session, 1, windows[0].startMillis + 1)
        DhikrReminderScheduler.refresh(context, nowMillis = windows[1].startMillis - 1)
        assertEquals(DhikrOccurrenceStatus.COMPLETED, repo.state.value.occurrences.getValue(occurrence.id).status)
        val pending = context.getSharedPreferences("adhkar_schedule_v2", 0).getString("event:" + rule.id, null)
        if (pending != null) assertNotEquals(occurrence.id, JSONObject(pending).getString("occurrence"))
        val next = DhikrReminderScheduler.nextNudge(context, rule, windows[1].startMillis - 1)
        assertTrue(next == null || next >= windows[1].endMillis)
    }
    @Test fun skippingSplitReminderForTodaySuppressesSecondIntervalButNotNextWeek() {
        val rule = splitRule()
        repo.save(rule)
        val windows = splitWindows(rule)
        val gap = windows[0].endMillis + 60_000L
        val prefs = context.getSharedPreferences("adhkar_schedule_v2", 0)
        DhikrReminderScheduler.refresh(context, rearm = true, nowMillis = gap)
        val pendingAfternoon = JSONObject(prefs.getString("event:" + rule.id, null)!!)
        assertEquals(windows[0].progressKey, pendingAfternoon.getString("occurrence"))
        assertTrue(pendingAfternoon.getLong("at") in windows[1].startMillis until windows[1].endMillis)

        repo.skip(windows[0].progressKey, gap)
        DhikrReminderScheduler.refresh(context, rearm = true, nowMillis = gap)
        assertEquals(DhikrOccurrenceStatus.SKIPPED,
            repo.state.value.occurrences.getValue(windows[0].progressKey).status)
        val nextWeek = window(rule, friday.plusWeeks(1))
        assertEquals(nextWeek.startMillis, DhikrReminderScheduler.nextNudge(context, rule, gap))
        assertEquals(nextWeek.progressKey,
            JSONObject(prefs.getString("event:" + rule.id, null)!!).getString("occurrence"))

        deliver(pendingAfternoon.toString(), windows[1].startMillis)
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
    }
    @Test fun overlappingDailyIntervalsAreRejectedAndLegacyJsonHasOneInterval() {
        val overlap = splitRule().copy(extraIntervals = listOf(DhikrInterval(
            DhikrTime(minuteOfDay = 540), DhikrTime(minuteOfDay = 660), false)))
        assertNotNull(DhikrReminderScheduler.validate(context, overlap))

        val legacyJson = rule().toJson().apply { remove("extraIntervals") }
        val restored = dhikrReminderFromJson(legacyJson)
        assertTrue(restored.extraIntervals.isEmpty())
        assertEquals(1, DhikrReminderScheduler.resolveWindows(context, restored, friday).size)
    }
    @Test fun gentleCadenceCoversEachOfFourIntervals() {
        val rule = splitRule().copy(end = DhikrTime(minuteOfDay = 540), extraIntervals = listOf(
            DhikrInterval(DhikrTime(minuteOfDay = 600), DhikrTime(minuteOfDay = 660), false),
            DhikrInterval(DhikrTime(minuteOfDay = 720), DhikrTime(minuteOfDay = 780), false),
            DhikrInterval(DhikrTime(minuteOfDay = 840), DhikrTime(minuteOfDay = 900), false)))
        assertNull(DhikrReminderScheduler.validate(context, rule))
        val windows = splitWindows(rule)
        assertEquals(4, windows.size)
        val times = dhikrNudgeTimes(rule, windows)
        assertTrue(times.size >= windows.size)
        assertTrue(windows.all { window -> times.any { it in window.startMillis until window.endMillis } })
    }
    @Test fun reversedIntervalOrderIsStillADuplicateReminder() {
        val saved = splitRule()
        repo.save(saved)
        val reversed = saved.copy(id = "reversed_interval_rule", start = saved.extraIntervals.single().start,
            end = saved.extraIntervals.single().end, endNextDay = saved.extraIntervals.single().endNextDay,
            extraIntervals = listOf(DhikrInterval(saved.start, saved.end, saved.endNextDay)))
        val error = DhikrReminderScheduler.validate(context, reversed)
        assertTrue(error?.contains("يوجد تذكير") == true)
    }
    @Test fun duplicateIgnoresTheClockMinuteLeftBehindAPrayerTime() {
        val saved = rule().copy(start = DhikrTime(DhikrTimeKind.ASR), end = DhikrTime(DhikrTimeKind.MAGHRIB))
        repo.save(saved)
        // The editor keeps the minute of a fixed time that was then switched to a prayer.
        val again = saved.copy(id = "other", start = DhikrTime(DhikrTimeKind.ASR, minuteOfDay = 540))
        assertTrue(DhikrReminderScheduler.validate(context, again)?.contains("يوجد تذكير") == true)
        assertNull(DhikrReminderScheduler.validate(context, again.copy(start = DhikrTime(DhikrTimeKind.ASR, offsetMinutes = 5))))
    }
    @Test fun validationLooksAYearAheadWhenAPeriodFollowsAPrayer() {
        val summer = LocalDate.of(2026, 6, 5)
        // Sunset is after 18:00 in June and before it in winter.
        val seasonal = rule().copy(start = DhikrTime(minuteOfDay = 18 * 60), end = DhikrTime(DhikrTimeKind.MAGHRIB))
        assertNotNull(DhikrReminderScheduler.resolveWindow(context, seasonal, summer))
        val later = DhikrReminderScheduler.validate(context, seasonal, summer)
        assertTrue(later?.startsWith("ابتداءً من") == true)
        // An overnight period would not fix times that only fail in winter, so that hint is left out.
        assertTrue(later!!.endsWith("اختر أوقاتًا تصلح طوال السنة.") && "اليوم التالي" !in later)
        // In December the same period is already wrong, so the error carries no date.
        val winter = DhikrReminderScheduler.validate(context, seasonal, LocalDate.of(2026, 12, 4))
        assertTrue(winter != null && !winter.startsWith("ابتداءً من"))
        assertNull(DhikrReminderScheduler.validate(context, rule().copy(end = DhikrTime(DhikrTimeKind.MAGHRIB)), summer))
    }
    @Test fun collectionGoalWaitsForEveryItemAndSkipAllStopsNudges() {
        val rule = rule(1).copy(dhikrId = "sayyid_istighfar", collection = DhikrCategory.MORNING)
        repo.save(rule)
        val window = window(rule)
        val occurrence = repo.ensureOccurrence(rule, window)
        val items = listOf("sayyid_istighfar", "ayat_kursi")
        val session = repo.openSession(items, DhikrCategory.MORNING, occurrence.id, now = window.startMillis)

        repo.count(session, 1, window.startMillis + 1)
        assertEquals(DhikrOccurrenceStatus.OPEN, repo.state.value.occurrences.getValue(occurrence.id).status)
        assertEquals(0, repo.state.value.occurrences.getValue(occurrence.id).count)
        repo.move(session, 1)
        repo.count(session, 1, window.startMillis + 2)
        assertEquals(DhikrOccurrenceStatus.COMPLETED, repo.state.value.occurrences.getValue(occurrence.id).status)
        assertEquals(1, repo.state.value.occurrences.getValue(occurrence.id).count)
        repo.count(session, -1, window.startMillis + 3)
        assertEquals(DhikrOccurrenceStatus.OPEN, repo.state.value.occurrences.getValue(occurrence.id).status)
        repo.skipItem(session, window.startMillis + 4)
        assertEquals(DhikrOccurrenceStatus.COMPLETED, repo.state.value.occurrences.getValue(occurrence.id).status)

        val next = repo.ensureOccurrence(rule, window(rule, friday.plusWeeks(1)))
        val skipped = repo.openSession(items, DhikrCategory.MORNING, next.id, now = next.startMillis)
        repo.skipItem(skipped, next.startMillis + 1)
        assertEquals(DhikrOccurrenceStatus.OPEN, repo.state.value.occurrences.getValue(next.id).status)
        repo.skipItem(skipped, next.startMillis + 2)
        assertEquals(DhikrOccurrenceStatus.SKIPPED, repo.state.value.occurrences.getValue(next.id).status)
        assertTrue(DhikrReminderScheduler.nextNudge(context, rule, next.startMillis + 3)!! >= next.endMillis)
    }
    @Test fun collectionMembershipKeepsOneSessionAndReopensExpandedGoal() {
        val rule = rule(1).copy(dhikrId = "sayyid_istighfar", collection = DhikrCategory.MORNING)
        repo.save(rule)
        val occurrence = repo.ensureOccurrence(rule, window(rule))
        val start = occurrence.startMillis
        val items = listOf("sayyid_istighfar", "ayat_kursi")
        val session = repo.openSession(items, DhikrCategory.MORNING, occurrence.id, now = start)
        repo.count(session, 1, start + 1)
        val reopened = repo.openSession(items.reversed(), DhikrCategory.MORNING, occurrence.id, now = start + 2)
        assertEquals(session, reopened)
        assertEquals(1, repo.state.value.sessions.getValue(session).counts["sayyid_istighfar"])
        assertEquals(1, repo.state.value.sessions.values.count { it.occurrenceId == occurrence.id })
        repo.move(session, -1)
        repo.count(session, 1, start + 3)
        assertEquals(DhikrOccurrenceStatus.COMPLETED, repo.state.value.occurrences.getValue(occurrence.id).status)
        repo.addToCollectionSession(session, "salah_salam", start + 4)
        assertEquals(DhikrOccurrenceStatus.OPEN, repo.state.value.occurrences.getValue(occurrence.id).status)
        repo.move(session, 1)
        repo.move(session, 1)
        repo.count(session, 1, start + 5)
        assertEquals(DhikrOccurrenceStatus.COMPLETED, repo.state.value.occurrences.getValue(occurrence.id).status)
        repo.removeItem(session, start + 6)
        assertEquals(DhikrOccurrenceStatus.COMPLETED, repo.state.value.occurrences.getValue(occurrence.id).status)
    }
    @Test fun collectionMembershipDialogReconcilesTheActiveReminderWithoutReopeningReader() {
        repo.state.value.collectionEntries(DhikrCategory.PRAYER).map { it.id }.forEach {
            repo.setCollectionMembership(DhikrCategory.PRAYER, it, false)
        }
        val first = repo.saveCustom(DhikrEntry(id = "personal_first", title = "First", text = "First text",
            reference = "", defaultCount = 1, categories = emptySet()))
        val second = repo.saveCustom(DhikrEntry(id = "personal_second", title = "Second", text = "Second text",
            reference = "", defaultCount = 1, categories = emptySet()))
        repo.setCollectionMembership(DhikrCategory.PRAYER, first.id, true)
        val rule = rule(1).copy(dhikrId = first.id, collection = DhikrCategory.PRAYER)
        repo.save(rule)
        val window = window(rule)
        val occurrence = repo.ensureOccurrence(rule, window)
        val session = repo.openSession(listOf(first.id), DhikrCategory.PRAYER, occurrence.id, now = window.startMillis)
        repo.count(session, 1, window.startMillis + 1)
        assertEquals(DhikrOccurrenceStatus.COMPLETED, repo.state.value.occurrences.getValue(occurrence.id).status)

        repo.setCollectionMembership(DhikrCategory.PRAYER, second.id, true, window.startMillis + 2)
        assertEquals(listOf(first.id, second.id), repo.state.value.sessions.getValue(session).itemIds)
        assertEquals(1, repo.state.value.sessions.getValue(session).counts[first.id])
        assertEquals(DhikrOccurrenceStatus.OPEN, repo.state.value.occurrences.getValue(occurrence.id).status)
        DhikrReminderScheduler.refresh(context, nowMillis = window.startMillis + 2)
        val prefs = context.getSharedPreferences("adhkar_schedule_v2", 0)
        assertEquals(occurrence.id, JSONObject(prefs.getString("event:" + rule.id, null)!!).getString("occurrence"))

        repo.setCollectionMembership(DhikrCategory.PRAYER, second.id, false, window.startMillis + 3)
        assertEquals(listOf(first.id), repo.state.value.sessions.getValue(session).itemIds)
        assertEquals(DhikrOccurrenceStatus.COMPLETED, repo.state.value.occurrences.getValue(occurrence.id).status)
        DhikrReminderScheduler.refresh(context, nowMillis = window.startMillis + 3)
        assertNotEquals(occurrence.id, JSONObject(prefs.getString("event:" + rule.id, null)!!).getString("occurrence"))
    }
    @Test fun editingPersonalDhikrCountReconcilesAnActiveCollectionReminder() {
        val entry = repo.saveCustom(DhikrEntry(id = "personal_goal", title = "Goal", text = "Goal text",
            reference = "", defaultCount = 1, categories = emptySet()))
        repo.setCollectionMembership(DhikrCategory.PRAYER, entry.id, true)
        val rule = rule(1).copy(dhikrId = entry.id, collection = DhikrCategory.PRAYER)
        repo.save(rule)
        val window = window(rule)
        val occurrence = repo.ensureOccurrence(rule, window)
        val session = repo.openSession(listOf(entry.id), DhikrCategory.PRAYER, occurrence.id, now = window.startMillis)
        repo.count(session, 1, window.startMillis + 1)
        assertEquals(DhikrOccurrenceStatus.COMPLETED, repo.state.value.occurrences.getValue(occurrence.id).status)

        repo.saveCustom(entry.copy(defaultCount = 2), window.startMillis + 2)
        assertEquals(1, repo.state.value.sessions.getValue(session).counts[entry.id])
        assertEquals(DhikrOccurrenceStatus.OPEN, repo.state.value.occurrences.getValue(occurrence.id).status)
        DhikrReminderScheduler.refresh(context, nowMillis = window.startMillis + 2)
        val prefs = context.getSharedPreferences("adhkar_schedule_v2", 0)
        assertEquals(occurrence.id, JSONObject(prefs.getString("event:" + rule.id, null)!!).getString("occurrence"))

        repo.saveCustom(entry, window.startMillis + 3)
        assertEquals(DhikrOccurrenceStatus.COMPLETED, repo.state.value.occurrences.getValue(occurrence.id).status)
        DhikrReminderScheduler.refresh(context, nowMillis = window.startMillis + 3)
        assertNotEquals(occurrence.id, JSONObject(prefs.getString("event:" + rule.id, null)!!).getString("occurrence"))
    }
    @Test fun completedAndExpiredGoalsCannotBeSkippedButUpcomingCan() {
        val rule = rule(1); repo.save(rule)
        val completed = repo.ensureOccurrence(rule, window(rule))
        val session = repo.openSession(listOf(rule.dhikrId), occurrenceId = completed.id, now = completed.startMillis)
        repo.count(session, 1, completed.startMillis + 1)
        repo.skip(completed.id, completed.startMillis + 2)
        assertEquals(DhikrOccurrenceStatus.COMPLETED, repo.state.value.occurrences.getValue(completed.id).status)
        val expired = repo.ensureOccurrence(rule, window(rule, friday.plusWeeks(1)))
        repo.skip(expired.id, expired.endMillis)
        assertEquals(DhikrOccurrenceStatus.OPEN, repo.state.value.occurrences.getValue(expired.id).status)
        repo.skip(expired.id, expired.startMillis - 1)
        assertEquals(DhikrOccurrenceStatus.SKIPPED, repo.state.value.occurrences.getValue(expired.id).status)
    }
    @Test fun editedOrDisabledRuleCannotChangeOldLinkedProgress() {
        val rule = rule(3); repo.save(rule)
        val occurrence = repo.ensureOccurrence(rule, window(rule))
        val session = repo.openSession(listOf(rule.dhikrId), occurrenceId = occurrence.id, now = occurrence.startMillis)
        repo.setEnabled(rule.id, false)
        repo.count(session, 1, occurrence.startMillis + 1)
        assertEquals(0, repo.state.value.occurrences.getValue(occurrence.id).count)
        repo.setEnabled(rule.id, true)
        repo.save(rule.copy(targetCount = 5), occurrence.startMillis + 2)
        repo.count(session, 1, occurrence.startMillis + 3)
        assertEquals(0, repo.state.value.occurrences.getValue(occurrence.id).count)
    }
    @Test fun validationAndResolutionAgreeOnTomorrowStart() {
        val rule = rule().copy(start = DhikrTime(DhikrTimeKind.ISHA),
            end = DhikrTime(DhikrTimeKind.ISHA, offsetMinutes = 1), endNextDay = true)
        assertNotNull(DhikrReminderScheduler.validate(context, rule))
        assertNull(DhikrReminderScheduler.resolveWindow(context, rule, friday))
    }
    @Test fun nextNudgeReportsUpcomingReminder() {
        val rule = rule(1).copy(cadence = DhikrCadence.CUSTOM, intervalMinutes = 30,
            start = DhikrTime(minuteOfDay = 480), end = DhikrTime(minuteOfDay = 1080))
        repo.save(rule)
        val window = window(rule)
        DhikrReminderScheduler.refresh(context, rearm = true, nowMillis = window.startMillis - 1)
        assertEquals(window.startMillis, DhikrReminderScheduler.nextNudge(context, rule, window.startMillis - 1))
        deliver(event(), window.startMillis)
        assertEquals(window.startMillis + 30 * 60_000L, DhikrReminderScheduler.nextNudge(context, rule, window.startMillis + 1))
    }
    @Test fun consumedNudgeRecordsArePruned() {
        val rule = rule(1); repo.save(rule)
        val window = window(rule)
        DhikrReminderScheduler.refresh(context, rearm = true, nowMillis = window.startMillis - 1)
        deliver(event(), window.startMillis)
        val prefs = context.getSharedPreferences("adhkar_schedule_v2", 0)
        val doneKey = prefs.all.keys.first { it.startsWith("done:") }
        val stale = JSONObject(prefs.getString(doneKey, "{}")!!).put("actual", window.startMillis - 8L * 24 * 60 * 60_000)
        prefs.edit().putString(doneKey, stale.toString()).commit()
        DhikrReminderScheduler.refresh(context, nowMillis = window.startMillis + 1)
        assertFalse(prefs.contains(doneKey))
    }
    @Test fun maintenanceRefreshExpiresOldHistoryButKeepsWhatTheScreenShows() {
        val rule = rule(3); repo.save(rule)
        val old = window(rule)
        val oldOccurrence = repo.ensureOccurrence(rule, old)
        val oldLinked = repo.openSession(listOf(rule.dhikrId), occurrenceId = oldOccurrence.id, now = old.startMillis)
        repo.count(oldLinked, 1, old.startMillis + 1)
        val oldSingle = repo.openSession(listOf("salah_istighfar"), now = old.startMillis)
        val items = listOf("sayyid_istighfar", "ayat_kursi")
        fun fajr(date: LocalDate) = DhikrReminderScheduler.resolveTime(context, DhikrTime(DhikrTimeKind.FAJR), date)!!
        val olderMorning = repo.openSession(items, DhikrCategory.MORNING, now = fajr(friday), collectionReading = true)
        val latestMorning = repo.openSession(items, DhikrCategory.MORNING, now = fajr(friday.plusDays(1)),
            collectionReading = true)
        val last = repo.openSession(listOf("salah_salam"), now = fajr(friday.plusDays(1)) + 1)
        val next = window(rule, friday.plusWeeks(2))
        val stored = { context.getSharedPreferences("adhkar_reminders", 0).getString("state_v2", null)!! }
        val sizeBefore = stored().length

        // Count taps refresh without rearm and must not pay for pruning.
        DhikrReminderScheduler.refresh(context, nowMillis = next.startMillis - 1)
        assertTrue(oldOccurrence.id in repo.state.value.occurrences)
        assertTrue(oldSingle in repo.state.value.sessions)

        DhikrReminderScheduler.refresh(context, rearm = true, nowMillis = next.startMillis - 1)
        DhikrRepository.clearMemoryCache()
        val pruned = DhikrRepository(context).state.value
        assertFalse(oldOccurrence.id in pruned.occurrences)
        assertFalse(oldLinked in pruned.sessions)
        assertFalse(oldSingle in pruned.sessions)
        assertFalse(olderMorning in pruned.sessions)
        // The latest morning reading decides the hero card; the last session reopens the reader.
        assertTrue(latestMorning in pruned.sessions)
        assertEquals(last, pruned.lastSessionId)
        assertTrue(last in pruned.sessions)
        assertTrue(next.progressKey in pruned.occurrences)
        assertTrue(stored().length < sizeBefore)
    }
    @Test fun expiredHistoryStillReferencedIsKept() {
        val day = 24 * 60 * 60_000L
        val now = 100 * day
        fun occurrence(id: String, end: Long, snooze: Long = 0) = DhikrOccurrence(id, "rule", 1, "2026-01-01",
            DhikrCatalog.SALAWAT_ID, 3, end - day / 2, end, snoozedUntilMillis = snooze)
        fun session(id: String, updated: Long, occurrenceId: String? = null, periodKey: String? = null) =
            DhikrSession(id, listOf("salah_istighfar"), category = periodKey?.let { DhikrCategory.SALAH },
                occurrenceId = occurrenceId, updatedAtMillis = updated, collectionPeriodKey = periodKey)
        val state = DhikrState(
            occurrences = listOf(occurrence("expired", now - 8 * day), occurrence("recent", now - 6 * day),
                occurrence("notified", now - 30 * day), occurrence("snoozed", now - 30 * day, snooze = now + 1),
                occurrence("resumed", now - 30 * day)).associateBy { it.id },
            sessions = listOf(session("stale", now - 8 * day), session("fresh", now - 6 * day),
                session("last", now - 30 * day, occurrenceId = "resumed"),
                session("notifiedReading", now - 30 * day, occurrenceId = "notified"),
                session("expiredReading", now - 30 * day, occurrenceId = "expired"),
                session("salah", now - 60 * day, periodKey = "manual:SALAH"),
                session("olderSalah", now - 90 * day, periodKey = "manual:SALAH")).associateBy { it.id },
            lastSessionId = "last",
        )
        val pruned = state.withoutExpiredHistory(now, protectedOccurrenceIds = setOf("notified"))
        assertEquals(setOf("recent", "notified", "snoozed", "resumed"), pruned.occurrences.keys)
        assertEquals(setOf("fresh", "last", "notifiedReading", "salah"), pruned.sessions.keys)
        // Nothing left to expire: the same instance comes back, so the repository skips the write.
        assertSame(pruned, pruned.withoutExpiredHistory(now, protectedOccurrenceIds = setOf("notified")))
    }
    @Test fun catalogEntriesAreUniqueAndExplained() {
        val entries = DhikrCatalog.entries
        assertEquals(entries.size, entries.map { it.id }.toSet().size)
        assertTrue(entries.all { it.text.isNotBlank() && it.reference.isNotBlank() && it.defaultCount >= 1 })
        assertTrue(entries.all { it.categories.isNotEmpty() })
        assertTrue(entries.filter { it.explanation.isBlank() }.map { it.id }.toString(), entries.all { it.explanation.isNotBlank() })
        assertTrue(entries.any { DhikrCategory.PRAYER in it.categories })
        assertTrue(entries.any { it.id == "ayat_kursi" && DhikrCategory.MORNING in it.categories })
    }
    @Test fun everyDhikrShowsItsSourceHadithUnlessNoneIsQuotable() {
        val ids = DhikrCatalog.entries.map { it.id }.toSet()
        assertEquals(emptySet<String>(), DhikrNarrations.byId.keys - ids)
        // Not on sunnah.com (Ahmad, al-Nasa'i's al-Kubra, al-Hakim) or not a hadith (al-Hasan's athar).
        assertEquals(setOf("fitrah_islam", "fitrah_islam_evening", "ya_hayyu", "hamm_quran", "newborn"),
            DhikrCatalog.entries.filter { it.narration.isBlank() }.map { it.id }.toSet())
        DhikrCatalog.entries.filter { it.narration.isNotBlank() }.forEach { entry ->
            assertTrue(entry.id, entry.narration.contains("\n— "))
            assertEquals(entry.id, entry.narration.count { it == '«' }, entry.narration.count { it == '»' })
        }
        assertTrue(DhikrCatalog.find(DhikrCatalog.SALAWAT_ID)!!.narration.endsWith("— صحيح البخاري 6357"))
    }
    @Test fun eachCollectionHasAnExplicitReadingOrderMatchingItsMembers() {
        DhikrCategory.entries.forEach { category ->
            val order = DhikrCatalog.collectionOrder.getValue(category)
            assertEquals(category.name, order.size, order.distinct().size)
            assertEquals(category.name, DhikrCatalog.entries.filter { category in it.categories }.map { it.id }.toSet(), order.toSet())
            assertEquals(category.name, order, DhikrState().collectionEntries(category).map { it.id })
        }
        // The evening list must not carry morning wording, and bedtime ends with the supplication said last.
        val morningOnly = listOf("هَذَا الْيَوْمِ", "أَصْبَحَ بِي", "أَصْبَحْتُ", "أَصْبَحْنَا عَلَى", "أَصْبَحْنَا وَأَصْبَحَ")
        assertTrue(DhikrState().collectionEntries(DhikrCategory.EVENING).none { entry -> morningOnly.any { it in entry.text } })
        assertEquals("sleep_submission", DhikrState().collectionEntries(DhikrCategory.SLEEP).last().id)
        assertEquals(1, DhikrState().collectionEntries(DhikrCategory.SALAH).first { it.id == "surah_ikhlas" }.defaultCount)
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
        assertEquals(listOf(DhikrReminderScheduler.COUNT_ACTION_TITLE, "تم"), notifications.single().notification.actions.map { it.title.toString() })
        val open = Shadows.shadowOf(notifications.single().notification.contentIntent).savedIntent
        assertEquals(window.progressKey, open.getStringExtra(DhikrReminderScheduler.EXTRA_OCCURRENCE_ID))
    }
    @Test fun earlyBroadcastKeepsItsNudgeUntilTheIntendedTime() {
        val rule = rule(); repo.save(rule)
        val window = window(rule)
        DhikrReminderScheduler.refresh(context, rearm = true, nowMillis = window.startMillis - 1)
        val scheduled = event()
        val prefs = context.getSharedPreferences("adhkar_schedule_v2", 0)

        deliver(scheduled, window.startMillis - 80_000L)
        assertEquals(scheduled, prefs.getString("event:" + rule.id, null))
        assertFalse(prefs.contains("done:" + JSONObject(scheduled).getString("eventId")))
        assertEquals(0, context.getSystemService(NotificationManager::class.java).activeNotifications.size)

        deliver(scheduled, window.startMillis)
        assertEquals(1, context.getSystemService(NotificationManager::class.java).activeNotifications.size)
        assertEquals("shown", JSONObject(prefs.getString("done:" + JSONObject(scheduled).getString("eventId"), "{}")!!).getString("outcome"))
    }
    @Test fun reminderVibrationSettingSelectsTheRightChannel() {
        val rule = rule().copy(vibrate = false); repo.save(rule)
        val window = window(rule)
        DhikrReminderScheduler.refresh(context, rearm = true, nowMillis = window.startMillis - 1)
        deliver(event(), window.startMillis)
        val manager = context.getSystemService(NotificationManager::class.java)
        assertEquals(DhikrReminderScheduler.QUIET_CHANNEL_ID, manager.activeNotifications.single().notification.channelId)
        assertFalse(manager.getNotificationChannel(DhikrReminderScheduler.QUIET_CHANNEL_ID).shouldVibrate())
        assertTrue(manager.getNotificationChannel(DhikrReminderScheduler.CHANNEL_ID).shouldVibrate())
    }
    @Test fun nearbyRemindersAreDeferredInsteadOfDiscarded() {
        val first = rule(); val second = rule().copy(dhikrId = "salah_istighfar")
        repo.save(first); repo.save(second)
        val window = window(first)
        DhikrReminderScheduler.refresh(context, rearm = true, nowMillis = window.startMillis - 1)
        val prefs = context.getSharedPreferences("adhkar_schedule_v2", 0)
        val firstEvent = prefs.getString("event:" + first.id, null)!!
        val secondEvent = prefs.getString("event:" + second.id, null)!!

        deliver(firstEvent, window.startMillis)
        deliver(secondEvent, window.startMillis + 1)
        val deferred = prefs.getString("event:" + second.id, null)!!
        assertEquals(window.startMillis + 2 * 60_000L, JSONObject(deferred).getLong("at"))
        assertFalse(prefs.contains("done:" + JSONObject(secondEvent).getString("eventId")))
        // The saved rule carries its creation time, which is part of the scheduled event's signature.
        assertEquals(window.startMillis + 2 * 60_000L,
            DhikrReminderScheduler.nextNudge(context, repo.state.value.reminders.first { it.id == second.id }, window.startMillis + 1))

        deliver(deferred, window.startMillis + 2 * 60_000L)
        assertEquals(2, context.getSystemService(NotificationManager::class.java).activeNotifications.size)
    }
    @Test fun newRuleSavedMidWindowDoesNotReplayAnEarlierNudge() {
        val draft = rule().copy(cadence = DhikrCadence.ONCE)
        val window = window(draft)
        val savedAt = window.startMillis + 60 * 60_000L
        repo.save(draft, savedAt)
        val saved = repo.state.value.reminders.single()
        assertEquals(savedAt, saved.createdAtMillis)
        val next = DhikrReminderScheduler.nextNudge(context, saved, savedAt)
        assertTrue(next == null || next >= window.endMillis)
        // A rule that already existed still recovers its one missed nudge.
        assertEquals(savedAt, DhikrReminderScheduler.nextNudge(context, saved.copy(createdAtMillis = 0), savedAt))
    }
    @Test fun editorPreviewOfARuleMatchesWhatSavingStores() {
        val draft = rule().copy(cadence = DhikrCadence.ONCE)
        val now = window(draft).startMillis + 60 * 60_000L
        val preview = DhikrReminderScheduler.storedForm(context, repo.state.value, draft, now)
        repo.save(draft, now)
        assertEquals(repo.state.value.reminders.single(), preview)
        assertEquals(DhikrReminderScheduler.nextNudge(context, repo.state.value.reminders.single(), now),
            DhikrReminderScheduler.nextNudge(context, preview, now))
        // Untouched, a saved rule previews as itself; an edit previews as the revision saving would create.
        assertEquals(preview, DhikrReminderScheduler.storedForm(context, repo.state.value, preview, now + 1))
        val edited = DhikrReminderScheduler.storedForm(context, repo.state.value, preview.copy(targetCount = 5), now + 1)
        repo.save(preview.copy(targetCount = 5), now + 1)
        assertEquals(repo.state.value.reminders.single(), edited)
    }
    @Test fun collectionReminderDuplicateIgnoresTheRepresentativeDhikr() {
        val saved = DhikrReminder(dhikrId = "morning_kingdom", collection = DhikrCategory.MORNING, targetCount = 1,
            start = DhikrTime(minuteOfDay = 480), end = DhikrTime(minuteOfDay = 540))
        repo.save(saved)
        assertNotNull(DhikrReminderScheduler.validate(context, saved.copy(id = "other", dhikrId = "ayat_kursi")))
        assertNull(DhikrReminderScheduler.validate(context, saved.copy(id = "other", dhikrId = "ayat_kursi", daysOfWeek = setOf(1))))
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
    @Test fun snoozeIsOccurrenceSpecificAndStopsAtWindowEnd() {
        val rule = rule(); repo.save(rule)
        val window = window(rule)
        val occurrence = repo.ensureOccurrence(rule, window)
        assertTrue(repo.snooze(occurrence.id, window.startMillis))
        assertEquals(window.startMillis + 30 * 60_000, repo.state.value.occurrences.getValue(occurrence.id).snoozedUntilMillis)
        assertFalse(repo.snooze(occurrence.id, window.endMillis - 10 * 60_000))
        assertEquals(window.startMillis + 30 * 60_000, repo.state.value.occurrences.getValue(occurrence.id).snoozedUntilMillis)
        assertFalse(repo.snooze(occurrence.id, window.endMillis - 1))
        assertEquals(0, repo.state.value.occurrences.getValue(occurrence.id).count)
    }
    @Test fun notificationCounterActionRaisesTheCountWithoutOpeningTheApp() {
        val rule = rule().copy(targetCount = 2); repo.save(rule)
        val window = window(rule)
        DhikrReminderScheduler.refresh(context, rearm = true, nowMillis = window.startMillis - 1)
        deliver(event(), window.startMillis)
        val manager = context.getSystemService(NotificationManager::class.java)
        fun count() = DhikrReminderScheduler.receive(context, Intent().setAction(DhikrReminderScheduler.ACTION_COUNT)
            .putExtra("occurrence", window.progressKey), window.startMillis + 1)
        count()
        assertEquals(1, repo.state.value.occurrences.getValue(window.progressKey).count)
        assertEquals(1, manager.activeNotifications.size)
        count()
        assertEquals(2, repo.state.value.occurrences.getValue(window.progressKey).count)
        assertEquals(DhikrOccurrenceStatus.COMPLETED, repo.state.value.occurrences.getValue(window.progressKey).status)
        assertEquals(0, manager.activeNotifications.size)
    }
    @Test fun counterActionIsOfferedOnlyWhereCountingMakesSense() {
        fun titles(rule: DhikrReminder): List<String> {
            repo.save(rule)
            val window = window(rule)
            DhikrReminderScheduler.refresh(context, rearm = true, nowMillis = window.startMillis - 1)
            deliver(event(), window.startMillis)
            return context.getSystemService(NotificationManager::class.java).activeNotifications.single()
                .notification.actions.map { it.title.toString() }
        }
        // A goal of one is finished with "تم", so there is nothing to count.
        assertFalse(DhikrReminderScheduler.COUNT_ACTION_TITLE in titles(rule(1)))
    }
    @Test fun collectionReminderHasNoCounterAction() {
        val items = DhikrCatalog.entries.filter { DhikrCategory.MORNING in it.categories }.map { it.id }
        val rule = rule(1).copy(dhikrId = items.first(), collection = DhikrCategory.MORNING)
        repo.save(rule)
        val window = window(rule)
        DhikrReminderScheduler.refresh(context, rearm = true, nowMillis = window.startMillis - 1)
        deliver(event(), window.startMillis)
        val titles = context.getSystemService(NotificationManager::class.java).activeNotifications.single()
            .notification.actions.map { it.title.toString() }
        assertFalse(DhikrReminderScheduler.COUNT_ACTION_TITLE in titles)
        assertTrue("متابعة الذكر" in titles)
    }
    @Test fun nearEndNotificationDoesNotOfferAnUndeliverableSnooze() {
        val rule = rule().copy(end = DhikrTime(minuteOfDay = 540))
        repo.save(rule)
        val window = window(rule)
        DhikrReminderScheduler.refresh(context, rearm = true, nowMillis = window.startMillis - 1)
        deliver(event(), window.endMillis - 10 * 60_000L)
        val notification = context.getSystemService(NotificationManager::class.java).activeNotifications.single().notification
        assertEquals(listOf(DhikrReminderScheduler.COUNT_ACTION_TITLE, "تم"), notification.actions.map { it.title.toString() })

        DhikrReminderScheduler.receive(context, Intent().setAction(DhikrReminderScheduler.ACTION_SNOOZE)
            .putExtra("occurrence", window.progressKey), window.endMillis - 5 * 60_000L)
        assertEquals(0L, repo.state.value.occurrences.getValue(window.progressKey).snoozedUntilMillis)
    }
    @Test fun notificationSnoozeReplacesCurrentNudgeAndRejectsStaleDelivery() {
        val rule = rule(1).copy(start = DhikrTime(minuteOfDay = 480), end = DhikrTime(minuteOfDay = 540))
        repo.save(rule)
        val window = window(rule)
        DhikrReminderScheduler.refresh(context, rearm = true, nowMillis = window.startMillis - 1)
        val original = event()
        deliver(original, window.startMillis)
        val manager = context.getSystemService(NotificationManager::class.java)
        val snooze = legacySnoozeIntent(manager.activeNotifications.single().tag)
        val clickedAt = window.startMillis + 10 * 60_000L
        DhikrReminderScheduler.receive(context, snooze, clickedAt)

        assertEquals(0, manager.activeNotifications.size)
        val pending = event()
        assertTrue(JSONObject(pending).getString("eventId").contains(":snooze:"))
        assertEquals(clickedAt + 30 * 60_000L, JSONObject(pending).getLong("at"))
        deliver(original, clickedAt + 1)
        assertEquals(0, manager.activeNotifications.size)
        deliver(pending, clickedAt + 30 * 60_000L)
        deliver(pending, clickedAt + 30 * 60_000L)
        assertEquals(1, manager.activeNotifications.size)
    }
    @Test fun daylightSavingWindowUsesLocalInstants() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"))
        val rule = rule().copy(daysOfWeek = setOf(7), start = DhikrTime(minuteOfDay = 60), end = DhikrTime(minuteOfDay = 240))
        val spring = window(rule, LocalDate.of(2026, 3, 29))
        val autumn = window(rule, LocalDate.of(2026, 10, 25))
        assertEquals(2 * 60 * 60_000L, spring.endMillis - spring.startMillis)
        assertEquals(4 * 60 * 60_000L, autumn.endMillis - autumn.startMillis)
    }
    @Test fun prayerRelativeReminderUsesComputedTimesForFutureYears() {
        val delegation = PrefsManager.getDelegationId(context)
        for (date in listOf(LocalDate.of(2027, 1, 1), LocalDate.of(2028, 2, 29))) {
            val expected = PrayerTimesRepository.loadDayPrayerTimes(context, delegation, date.year, date.monthValue, date.dayOfMonth)!!
            val fajr = DhikrReminderScheduler.resolveTime(context, DhikrTime(DhikrTimeKind.FAJR), date)!!
            val localFajr = Instant.ofEpochMilli(fajr).atZone(ZoneId.systemDefault()).toLocalTime()
            assertEquals(expected.fajr.hour, localFajr.hour)
            assertEquals(expected.fajr.minute, localFajr.minute)
        }
        val rule = rule().copy(start = DhikrTime(DhikrTimeKind.FAJR), end = DhikrTime(DhikrTimeKind.MAGHRIB))
        assertNotNull(DhikrReminderScheduler.resolveWindow(context, rule, LocalDate.of(2027, 1, 1)))
    }
    @Test fun prayerRelativeReminderReusesLastSupportedYearBeyondIt() {
        val delegation = PrefsManager.getDelegationId(context)
        val lastYear = PrayerTimesRepository.SUPPORTED_YEARS.last
        val source = PrayerTimesRepository.loadDayPrayerTimes(context, delegation, lastYear, 1, 1)!!
        val fajr = DhikrReminderScheduler.resolveTime(context, DhikrTime(DhikrTimeKind.FAJR), LocalDate.of(lastYear + 1, 1, 1))!!
        val localFajr = Instant.ofEpochMilli(fajr).atZone(ZoneId.systemDefault()).toLocalTime()
        assertEquals(source.fajr.hour, localFajr.hour)
        assertEquals(source.fajr.minute, localFajr.minute)

        // The first leap day past a non-leap last year falls back to its February 28.
        val leapYear = (lastYear + 1..lastYear + 8).first { Year.isLeap(it.toLong()) }
        assertFalse(Year.isLeap(lastYear.toLong()))
        val leapDay = DhikrReminderScheduler.resolveTime(context, DhikrTime(DhikrTimeKind.FAJR), LocalDate.of(leapYear, 2, 29))!!
        val feb28 = PrayerTimesRepository.loadDayPrayerTimes(context, delegation, lastYear, 2, 28)!!
        val localLeapFajr = Instant.ofEpochMilli(leapDay).atZone(ZoneId.systemDefault()).toLocalTime()
        assertEquals(feb28.fajr.hour, localLeapFajr.hour)
        assertEquals(feb28.fajr.minute, localLeapFajr.minute)
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
    @Test @Config(sdk = [33]) fun blockedNotificationRetriesAndReleasesWhenPermissionReturns() {
        val rule = rule(); repo.save(rule)
        val window = window(rule)
        DhikrReminderScheduler.refresh(context, rearm = true, nowMillis = window.startMillis - 1)
        val original = event()
        val eventId = JSONObject(original).getString("eventId")
        val app = context as Application
        Shadows.shadowOf(app).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)

        deliver(original, window.startMillis)
        val prefs = context.getSharedPreferences("adhkar_schedule_v2", 0)
        val deferred = JSONObject(prefs.getString("event:" + rule.id, null)!!)
        assertEquals(window.startMillis + 15 * 60_000L, deferred.getLong("at"))
        assertEquals(eventId, deferred.getString("eventId"))
        assertFalse(prefs.contains("done:" + eventId))
        assertNull(DhikrReminderScheduler.nextNudge(context, rule, window.startMillis + 1))

        Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        DhikrReminderScheduler.refresh(context, nowMillis = window.startMillis + 1)
        val released = prefs.getString("event:" + rule.id, null)!!
        assertEquals(window.startMillis + 1, JSONObject(released).getLong("at"))
        assertEquals(eventId, JSONObject(released).getString("eventId"))
        deliver(released, window.startMillis + 1)
        assertEquals(1, context.getSystemService(NotificationManager::class.java).activeNotifications.size)
    }
    @Test fun emptyCollectionSuspendsAndMembershipRestoresReminder() {
        val members = repo.state.value.collectionEntries(DhikrCategory.MORNING).map { it.id }
        val rule = rule(1).copy(dhikrId = members.first(), collection = DhikrCategory.MORNING)
        repo.save(rule)
        val window = window(rule)
        DhikrReminderScheduler.refresh(context, rearm = true, nowMillis = window.startMillis - 1)
        val prefs = context.getSharedPreferences("adhkar_schedule_v2", 0)
        assertNotNull(prefs.getString("event:" + rule.id, null))

        members.forEach { repo.setCollectionMembership(DhikrCategory.MORNING, it, false) }
        DhikrReminderScheduler.refresh(context, nowMillis = window.startMillis - 1)
        assertTrue(repo.state.value.collectionEntries(DhikrCategory.MORNING).isEmpty())
        assertNull(prefs.getString("event:" + rule.id, null))
        assertNull(DhikrReminderScheduler.nextNudge(context, rule, window.startMillis - 1))

        repo.setCollectionMembership(DhikrCategory.MORNING, members.first(), true)
        DhikrReminderScheduler.refresh(context, nowMillis = window.startMillis - 1)
        assertNotNull(prefs.getString("event:" + rule.id, null))
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
    @Test fun nudgeIsDeferredDuringAppSilenceAndDeliveredAfterItEnds() {
        val rule = rule(1); repo.save(rule)
        val window = window(rule)
        DhikrReminderScheduler.refresh(context, rearm = true, nowMillis = window.startMillis - 1)
        val event = event()
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        Shadows.shadowOf(notificationManager).setNotificationPolicyAccessGranted(true)
        val silenceEnd = window.startMillis + 20 * 60_000
        PrefsManager.markManualSilenceActive(context, AudioManager.RINGER_MODE_NORMAL, NotificationManager.INTERRUPTION_FILTER_ALL)
        PrefsManager.setManualSilenceEndsAtMillis(context, silenceEnd)
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        assertTrue(SilenceStatus.isAppControlledSilenceActive(context))

        deliver(event, window.startMillis)
        assertEquals(0, notificationManager.activeNotifications.size)
        val deferred = context.getSharedPreferences("adhkar_schedule_v2", 0).getString("event:" + rule.id, null)!!
        assertEquals(silenceEnd, JSONObject(deferred).getLong("at"))

        PrefsManager.clearManualSilenceState(context)
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        deliver(deferred, silenceEnd)
        assertEquals(1, notificationManager.activeNotifications.size)
        assertEquals(0, repo.state.value.occurrences.getValue(window.progressKey).count)
    }
    @Test fun silenceReleaseMovesDeferredReminderForward() {
        val rule = rule(1); repo.save(rule)
        val window = window(rule)
        DhikrReminderScheduler.refresh(context, rearm = true, nowMillis = window.startMillis - 1)
        val original = event()
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        Shadows.shadowOf(notificationManager).setNotificationPolicyAccessGranted(true)
        PrefsManager.markManualSilenceActive(context, AudioManager.RINGER_MODE_NORMAL, NotificationManager.INTERRUPTION_FILTER_ALL)
        PrefsManager.setManualSilenceEndsAtMillis(context, window.startMillis + 20 * 60_000)
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        deliver(original, window.startMillis)

        val prefs = context.getSharedPreferences("adhkar_schedule_v2", 0)
        val deferred = JSONObject(prefs.getString("event:" + rule.id, null)!!)
        assertEquals("silence", deferred.getString("deferredFor"))
        PrefsManager.clearManualSilenceState(context)
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        DhikrReminderScheduler.refresh(context, nowMillis = window.startMillis + 1)
        val released = prefs.getString("event:" + rule.id, null)!!
        assertEquals(window.startMillis + 1, JSONObject(released).getLong("at"))
        assertEquals(deferred.getString("eventId"), JSONObject(released).getString("eventId"))
        deliver(released, window.startMillis + 1)
        assertEquals(1, notificationManager.activeNotifications.size)
    }
    @Test fun nudgeCoveredEntirelyBySilenceIsNotDelivered() {
        val rule = rule(1); repo.save(rule)
        val window = window(rule)
        DhikrReminderScheduler.refresh(context, rearm = true, nowMillis = window.startMillis - 1)
        val event = event()
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        Shadows.shadowOf(notificationManager).setNotificationPolicyAccessGranted(true)
        PrefsManager.markManualSilenceActive(context, AudioManager.RINGER_MODE_NORMAL, NotificationManager.INTERRUPTION_FILTER_ALL)
        PrefsManager.setManualSilenceEndsAtMillis(context, window.endMillis + 60_000)
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)

        deliver(event, window.startMillis)
        assertEquals(0, notificationManager.activeNotifications.size)
        val prefs = context.getSharedPreferences("adhkar_schedule_v2", 0)
        val deferred = prefs.getString("event:" + rule.id, null)!!
        assertEquals(window.endMillis - 1, JSONObject(deferred).getLong("at"))
        assertFalse(prefs.contains("done:" + JSONObject(event).getString("eventId")))
        deliver(deferred, window.endMillis - 1)
        assertTrue(prefs.contains("done:" + JSONObject(event).getString("eventId")))
        assertEquals(0, notificationManager.activeNotifications.size)
        assertEquals(0, repo.state.value.occurrences.getValue(window.progressKey).count)
    }
    @Test fun plannedSilenceEndingAfterWindowReleasesNudgeWhenStoppedEarly() {
        val rule = rule(1).copy(start = DhikrTime(minuteOfDay = 480), end = DhikrTime(minuteOfDay = 540))
        repo.save(rule)
        val window = window(rule)
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        Shadows.shadowOf(notificationManager).setNotificationPolicyAccessGranted(true)
        PrefsManager.markManualSilenceActive(context, AudioManager.RINGER_MODE_NORMAL, NotificationManager.INTERRUPTION_FILTER_ALL)
        PrefsManager.setManualSilenceEndsAtMillis(context, window.endMillis + 30 * 60_000)
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        DhikrReminderScheduler.refresh(context, rearm = true, nowMillis = window.startMillis - 1)

        val prefs = context.getSharedPreferences("adhkar_schedule_v2", 0)
        val deferred = JSONObject(prefs.getString("event:" + rule.id, null)!!)
        assertEquals(window.endMillis - 1, deferred.getLong("at"))
        assertEquals("silence", deferred.getString("deferredFor"))
        val releasedAt = window.startMillis + 50 * 60_000L
        PrefsManager.clearManualSilenceState(context)
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        DhikrReminderScheduler.refresh(context, nowMillis = releasedAt)
        val released = prefs.getString("event:" + rule.id, null)!!
        assertEquals(releasedAt, JSONObject(released).getLong("at"))
        assertEquals(deferred.getString("eventId"), JSONObject(released).getString("eventId"))
        deliver(released, releasedAt)
        assertEquals(1, notificationManager.activeNotifications.size)
    }
    @Test fun lateFinalNudgeIsDeliveredOnceWithoutReplayingEarlierSlots() {
        val rule = rule(1).copy(start = DhikrTime(minuteOfDay = 480), end = DhikrTime(minuteOfDay = 540))
        repo.save(rule)
        val window = window(rule)
        DhikrReminderScheduler.refresh(context, rearm = true, nowMillis = window.startMillis - 1)
        val original = event()
        val late = window.startMillis + 50 * 60_000L
        deliver(original, late)
        val prefs = context.getSharedPreferences("adhkar_schedule_v2", 0)
        assertNotEquals(window.progressKey, JSONObject(prefs.getString("event:" + rule.id, null)!!).getString("occurrence"))
        assertEquals(1, context.getSystemService(NotificationManager::class.java).activeNotifications.size)
        DhikrReminderScheduler.refresh(context, rearm = true, nowMillis = late + 1)
        assertNotEquals(window.progressKey, JSONObject(prefs.getString("event:" + rule.id, null)!!).getString("occurrence"))
    }
    @Test @Config(sdk = [31]) fun exactReminderHasIndependentInexactBackup() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val rule = rule(1); repo.save(rule)
        val window = window(rule)
        DhikrReminderScheduler.refresh(context, rearm = true, nowMillis = window.startMillis - 1)
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val alarms = Shadows.shadowOf(alarmManager).scheduledAlarms
        val exact = alarms.single { Shadows.shadowOf(it.operation).savedIntent.data.toString().contains("/v3/") }
        val backup = alarms.single { Shadows.shadowOf(it.operation).savedIntent.data.toString().contains("/v3-backup/") }
        assertEquals(window.startMillis, exact.triggerAtTime)
        assertEquals(window.startMillis, backup.triggerAtTime)

        // Model the platform canceling exact alarms when access is revoked.
        alarmManager.cancel(requireNotNull(exact.operation))
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        DhikrReminderScheduler.receive(context, Shadows.shadowOf(backup.operation).savedIntent, window.startMillis)
        assertEquals(1, context.getSystemService(NotificationManager::class.java).activeNotifications.size)
        val oldEvent = JSONObject(Shadows.shadowOf(backup.operation).savedIntent.getStringExtra("event")!!)
        assertTrue(context.getSharedPreferences("adhkar_schedule_v2", 0).contains("done:" + oldEvent.getString("eventId")))
    }

    // ---- Second review of the add-reminder flow ----
    private val tuesday = LocalDate.of(2026, 10, 6)
    private fun at(date: LocalDate, hour: Int, minute: Int = 0): Long =
        date.atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun daily(start: Int, end: Int, cadence: DhikrCadence = DhikrCadence.HOURLY) = DhikrReminder(
        dhikrId = DhikrCatalog.SALAWAT_ID, targetCount = 100, cadence = cadence,
        start = DhikrTime(minuteOfDay = start), end = DhikrTime(minuteOfDay = end))
    private fun twoPeriods() = daily(480, 600).copy(
        extraIntervals = listOf(DhikrInterval(DhikrTime(minuteOfDay = 960), DhikrTime(minuteOfDay = 1080), false)))
    private fun eventOf(id: String): String? = context.getSharedPreferences("adhkar_schedule_v2", 0).getString("event:$id", null)
    private fun stored(id: String) = repo.state.value.reminders.first { it.id == id }
    private fun notifications() = context.getSystemService(NotificationManager::class.java).activeNotifications
    /** What the snooze button on a notification from an older build sends. */
    private fun legacySnoozeIntent(occurrenceId: String) = Intent(context, DhikrReminderReceiver::class.java)
        .setAction(DhikrReminderScheduler.ACTION_SNOOZE).putExtra("occurrence", occurrenceId)

    @Test fun samePrayerOnTheNextDayIsAValidPeriod() {
        val thursdayNight = DhikrReminder(dhikrId = DhikrCatalog.SALAWAT_ID, targetCount = 100, daysOfWeek = setOf(4),
            start = DhikrTime(DhikrTimeKind.MAGHRIB), end = DhikrTime(DhikrTimeKind.MAGHRIB), endNextDay = true)
        val today = LocalDate.of(2026, 10, 1)
        // The prayer is a minute later some days, so the period runs a minute over 24 hours;
        // it still ends when its next occurrence begins.
        assertNull(DhikrReminderScheduler.validate(context, thursdayNight, today))
        assertNull(DhikrReminderScheduler.validate(context, thursdayNight.copy(daysOfWeek = (1..7).toSet(),
            start = DhikrTime(DhikrTimeKind.FAJR), end = DhikrTime(DhikrTimeKind.FAJR)), today))
        assertNotNull(DhikrReminderScheduler.validate(context,
            thursdayNight.copy(end = DhikrTime(DhikrTimeKind.MAGHRIB, offsetMinutes = 5)), today))
    }
    @Test fun nextDayTickOnAPeriodThatAlreadyEndsLaterPointsAtTheTick() {
        val rule = DhikrReminder(dhikrId = DhikrCatalog.SALAWAT_ID, targetCount = 100,
            start = DhikrTime(DhikrTimeKind.ISHA), end = DhikrTime(DhikrTimeKind.ISHA, offsetMinutes = 300))
        val today = LocalDate.of(2026, 10, 1)
        assertNull(DhikrReminderScheduler.validate(context, rule, today))
        val error = DhikrReminderScheduler.validate(context, rule.copy(endNextDay = true), today)
        assertTrue(error, error != null && "أزل" in error && "تنتهي في اليوم التالي" in error)
    }
    @Test fun periodErrorsNameThePeriodAtFault() {
        // The night period ends at 07:30, after the early period starts again the next morning.
        val night = daily(22 * 60, 7 * 60 + 30).copy(endNextDay = true,
            extraIntervals = listOf(DhikrInterval(DhikrTime(minuteOfDay = 360), DhikrTime(minuteOfDay = 390), false)))
        assertEquals("الفترة 1: تنتهي بعد بدء الفترة 2 في اليوم التالي. اختر وقت نهاية أبكر.",
            DhikrReminderScheduler.periodError(context, night))
        val overlapping = daily(480, 600).copy(
            extraIntervals = listOf(DhikrInterval(DhikrTime(minuteOfDay = 540), DhikrTime(minuteOfDay = 660), false)))
        assertEquals("الفترتان 1 و2 متداخلتان. غيّر وقت بداية إحداهما أو نهايتها.",
            DhikrReminderScheduler.periodError(context, overlapping))
        val backwards = daily(480, 600).copy(
            extraIntervals = listOf(DhikrInterval(DhikrTime(minuteOfDay = 900), DhikrTime(minuteOfDay = 840), false)))
        assertTrue(DhikrReminderScheduler.periodError(context, backwards)!!.startsWith("الفترة 2: يجب أن يكون وقت النهاية"))
        // A single period needs no name.
        assertTrue(DhikrReminderScheduler.periodError(context, daily(600, 540))!!.startsWith("يجب أن يكون وقت النهاية"))
    }
    @Test fun conflictOfTheChosenWeekdaysIsNotCalledSeasonal() {
        // Fine on a day whose next day is not selected, never on one followed by a selected day.
        val clocks = daily(360, 420).copy(daysOfWeek = (1..5).toSet(), extraIntervals = listOf(
            DhikrInterval(DhikrTime(minuteOfDay = 22 * 60), DhikrTime(minuteOfDay = 390), true)))
        val clockError = DhikrReminderScheduler.validate(context, clocks, friday)
        assertTrue(clockError, clockError != null && "ابتداءً من" !in clockError && "طوال السنة" !in clockError)
        val prayers = DhikrReminder(dhikrId = DhikrCatalog.SALAWAT_ID, targetCount = 100, daysOfWeek = setOf(4, 6, 7),
            start = DhikrTime(DhikrTimeKind.FAJR), end = DhikrTime(DhikrTimeKind.SHURUK), extraIntervals = listOf(
                DhikrInterval(DhikrTime(DhikrTimeKind.ISHA), DhikrTime(DhikrTimeKind.SHURUK), true)))
        val prayerError = DhikrReminderScheduler.validate(context, prayers, LocalDate.of(2026, 10, 1))
        assertTrue(prayerError, prayerError != null && "ابتداءً من" !in prayerError && prayerError.startsWith("الفترة 2: "))
    }
    @Test fun savingTheSameNewRuleTwiceIsNotAnEdit() {
        val draft = daily(480, 1200)
        val now = at(tuesday, 10)
        repo.save(draft, now)
        repo.save(draft, now + 1)   // a second tap before the list caught up
        val saved = repo.state.value.reminders.single()
        assertEquals(1, saved.revision)
        assertEquals(0L, saved.notBeforeMillis)
        assertEquals(now, saved.createdAtMillis)
        assertEquals(tuesday, DhikrReminderScheduler.currentOrNextWindow(context, saved, now + 1)?.date)
    }
    @Test fun vibrationChangeKeepsTodaysPeriodItsCountAndItsNudges() {
        val rule = daily(480, 1200)
        repo.save(rule, at(tuesday.minusDays(1), 12))
        DhikrReminderScheduler.refresh(context, rearm = true, nowMillis = at(tuesday, 7))
        for (hour in 8..10) deliver(eventOf(rule.id)!!, at(tuesday, hour))
        val now = at(tuesday, 10, 1)
        val window = DhikrReminderScheduler.currentOrNextWindow(context, stored(rule.id), now)!!
        val session = repo.openSession(listOf(rule.dhikrId), occurrenceId = window.progressKey, now = now)
        repeat(40) { repo.count(session, 1, now + it) }
        val editAt = at(tuesday, 10, 5)
        val nudgeBefore = DhikrReminderScheduler.nextNudge(context, stored(rule.id), editAt)
        repo.save(stored(rule.id).copy(vibrate = false), editAt)
        val after = stored(rule.id)
        assertFalse(after.vibrate)
        assertEquals(1, after.revision)
        val windowAfter = DhikrReminderScheduler.currentOrNextWindow(context, after, editAt)!!
        assertEquals(tuesday, windowAfter.date)
        assertEquals(40, repo.state.value.occurrences.getValue(windowAfter.progressKey).count)
        assertEquals(nudgeBefore, DhikrReminderScheduler.nextNudge(context, after, editAt))
    }
    @Test fun unreadMinuteAndIntervalAreNotEdits() {
        val rule = daily(480, 1200, DhikrCadence.GENTLE).copy(end = DhikrTime(DhikrTimeKind.MAGHRIB))
        repo.save(rule, at(tuesday.minusDays(1), 12))
        // What the form holds after «وقت ثابت» 20:00 and back to «المغرب», and after «كل 30 دقيقة» then «خفيف».
        val sameSchedule = stored(rule.id).copy(end = DhikrTime(DhikrTimeKind.MAGHRIB, minuteOfDay = 1200), intervalMinutes = 30)
        assertFalse(repo.state.value.changesSchedule(sameSchedule))
        repo.save(sameSchedule, at(tuesday, 10))
        assertEquals(1, stored(rule.id).revision)
        assertTrue(repo.state.value.changesSchedule(sameSchedule.copy(cadence = DhikrCadence.CUSTOM)))
    }
    @Test fun editStopsOnlyTheRunningPeriodAndWhatWasReadStillCounts() {
        val rule = twoPeriods()   // 08:00–10:00 and 16:00–18:00
        repo.save(rule, at(tuesday.minusDays(1), 12))
        val now = at(tuesday, 9)
        DhikrReminderScheduler.refresh(context, rearm = true, nowMillis = now)
        val window = DhikrReminderScheduler.currentOrNextWindow(context, stored(rule.id), now)!!
        val session = repo.openSession(listOf(rule.dhikrId), occurrenceId = window.progressKey, now = now)
        repeat(40) { repo.count(session, 1, now + it) }
        repo.save(stored(rule.id).copy(targetCount = 50), at(tuesday, 9, 5))
        val edited = stored(rule.id)
        assertEquals(2, edited.revision)
        assertEquals(at(tuesday, 10), edited.notBeforeMillis)
        val later = DhikrReminderScheduler.resolveWindows(context, edited, tuesday)
        assertEquals(listOf(at(tuesday, 16)), later.map { it.startMillis })
        assertEquals(40, repo.ensureOccurrence(edited, later.single()).count)
    }
    @Test fun editBetweenTwoPeriodsKeepsTheLaterOne() {
        val rule = twoPeriods()
        repo.save(rule, at(tuesday.minusDays(1), 12))
        DhikrReminderScheduler.refresh(context, rearm = true, nowMillis = at(tuesday, 9))
        repo.save(stored(rule.id).copy(targetCount = 50), at(tuesday, 12))
        assertEquals(listOf(at(tuesday, 16)),
            DhikrReminderScheduler.resolveWindows(context, stored(rule.id), tuesday).map { it.startMillis })
    }
    @Test fun editedReminderKeepsItsPlaceAndUndoDeleteRestoresIt() {
        val rules = listOf(daily(480, 540), daily(600, 660), daily(720, 780))
        rules.forEach { repo.save(it) }
        val order = rules.map { it.id }
        repo.save(stored(order[0]).copy(targetCount = 5))
        assertEquals(order, repo.state.value.reminders.map { it.id })
        val middle = stored(order[1])
        val index = repo.delete(middle.id)
        assertEquals(1, index)
        repo.restore(middle, index)
        assertEquals(order, repo.state.value.reminders.map { it.id })
    }
    @Test fun deletingAPersonalDhikrKeepsTheCollectionReminderItStoodFor() {
        val personal = repo.saveCustom(DhikrEntry(id = "", title = "وردي", text = "سُبْحَانَ اللَّهِ وَبِحَمْدِهِ",
            reference = "", defaultCount = 3, categories = emptySet(), custom = true))
        repo.setCollectionMembership(DhikrCategory.MORNING, personal.id, true)
        val collection = com.tunisianprayertimes.ui.morningCollectionPreset().copy(dhikrId = personal.id)
        repo.save(collection)
        val own = DhikrReminder(dhikrId = personal.id, targetCount = 3, start = DhikrTime(minuteOfDay = 480), end = DhikrTime(minuteOfDay = 540))
        repo.save(own)
        val removal = repo.deleteCustom(personal.id)!!
        assertEquals(listOf(own.id), removal.reminders.map { it.id })
        val left = repo.state.value.reminders.single()
        assertEquals(DhikrCategory.MORNING, left.collection)
        assertNotNull(DhikrCatalog.find(left.dhikrId))
        assertNotNull(DhikrReminderScheduler.currentOrNextWindow(context, left))
        DhikrRepository.clearMemoryCache()
        assertEquals(collection.id, DhikrRepository(context).state.value.reminders.single().id)
    }
    @Test fun switchingBackOnDoesNotReplayANudgeThatFellWhileOff() {
        val rule = daily(480, 1200, DhikrCadence.ONCE)
        repo.save(rule, at(tuesday.minusDays(1), 12))
        DhikrReminderScheduler.refresh(context, rearm = true, nowMillis = at(tuesday, 7))
        repo.setEnabled(rule.id, false, at(tuesday, 7, 1))
        val on = at(tuesday, 15)
        repo.setEnabled(rule.id, true, on)
        DhikrReminderScheduler.refresh(context, nowMillis = on)
        assertEquals(at(tuesday.plusDays(1), 8), DhikrReminderScheduler.nextNudge(context, stored(rule.id), on))
        eventOf(rule.id)?.takeIf { JSONObject(it).getLong("at") <= on }?.let { deliver(it, on) }
        assertTrue(notifications().isEmpty())
    }
    @Test fun nudgePostponedBehindAnotherReminderSurvivesARefresh() {
        val first = daily(600, 720, DhikrCadence.CUSTOM).copy(intervalMinutes = 15)
        val second = first.copy(id = java.util.UUID.randomUUID().toString(), dhikrId = "salah_istighfar")
        repo.save(first, at(tuesday.minusDays(1), 12)); repo.save(second, at(tuesday.minusDays(1), 12))
        val start = at(tuesday, 10)
        DhikrReminderScheduler.refresh(context, rearm = true, nowMillis = start - 3 * 60_000L)
        deliver(eventOf(first.id)!!, start)
        deliver(eventOf(second.id)!!, start + 1_000L)
        val postponed = eventOf(second.id)!!
        assertEquals(start + 2 * 60_000L, JSONObject(postponed).getLong("at"))
        // Opening the app, counting or answering the first notification all refresh the schedule.
        DhikrReminderScheduler.refresh(context, rearm = true, nowMillis = start + 30_000L)
        assertEquals(postponed, eventOf(second.id))
        assertEquals(start + 2 * 60_000L, DhikrReminderScheduler.nextNudge(context, stored(second.id), start + 30_000L))
        deliver(postponed, start + 2 * 60_000L)
        assertEquals(2, notifications().size)
    }
    @Test fun snoozeNearTheEndIsShortenedAndALastMinuteTapPutsTheNotificationAway() {
        val rule = daily(480, 540, DhikrCadence.ONCE)
        repo.save(rule, at(tuesday.minusDays(1), 12))
        val window = window(stored(rule.id), tuesday)
        DhikrReminderScheduler.refresh(context, rearm = true, nowMillis = window.startMillis - 1)
        deliver(eventOf(rule.id)!!, window.startMillis)
        val snooze = legacySnoozeIntent(window.progressKey)
        // Too late to come back: the tap is not ignored, the notification goes.
        DhikrReminderScheduler.receive(context, snooze, at(tuesday, 8, 52))
        assertEquals(0L, repo.state.value.occurrences.getValue(window.progressKey).snoozedUntilMillis)
        assertTrue(notifications().isEmpty())
        // Twenty minutes before the end the snooze still fits, five minutes short of it.
        assertTrue(repo.snooze(window.progressKey, at(tuesday, 8, 40)))
        assertEquals(at(tuesday, 8, 55), repo.state.value.occurrences.getValue(window.progressKey).snoozedUntilMillis)
    }
    @Test fun collectionPeriodOpeningBeforeItsPrayerBelongsToThatDay() {
        val day = LocalDate.of(2026, 10, 5); val next = day.plusDays(1)
        repo.save(com.tunisianprayertimes.ui.eveningCollectionPreset().copy(start = DhikrTime(minuteOfDay = 15 * 60)),
            at(day.minusDays(1), 12))
        val rule = repo.state.value.reminders.single()
        val items = repo.state.value.collectionEntries(DhikrCategory.EVENING).map { it.id }
        // Day one: the whole list is read after Asr, from the reminder.
        var tick = DhikrReminderScheduler.resolveTime(context, DhikrTime(DhikrTimeKind.ASR), day)!! + 10 * 60_000L
        DhikrReminderScheduler.refresh(context, nowMillis = tick)
        val read = repo.ensureOccurrence(rule, window(rule, day))
        val session = repo.openSession(items, DhikrCategory.EVENING, read.id, now = tick, collectionReading = true)
        items.forEach { _ ->
            repeat(repo.state.value.target(repo.state.value.sessions.getValue(session))) { repo.count(session, 1, ++tick) }
            repo.move(session, 1)
        }
        assertEquals(DhikrOccurrenceStatus.COMPLETED, repo.state.value.occurrences.getValue(read.id).status)
        // Day two starts at 15:00, before Asr: it is a new occasion, not yesterday's finished one.
        assertTrue(DhikrReminderScheduler.resolveTime(context, DhikrTime(DhikrTimeKind.ASR), next)!! > at(next, 15))
        DhikrReminderScheduler.refresh(context, rearm = true, nowMillis = at(next, 14))
        assertEquals(at(next, 15), JSONObject(eventOf(rule.id)!!).getLong("at"))
        assertEquals(at(next, 15), DhikrReminderScheduler.nextNudge(context, rule, at(next, 14)))
        val occurrence = repo.ensureOccurrence(rule, window(rule, next))
        val reopened = repo.openSession(items, DhikrCategory.EVENING, occurrence.id, now = at(next, 15, 5), collectionReading = true)
        assertTrue(repo.state.value.sessions.getValue(reopened).counts.values.all { it == 0 })
        assertEquals(DhikrOccurrenceStatus.OPEN, repo.state.value.occurrences.getValue(occurrence.id).status)
    }
    @Test fun searchMatchesAcrossPunctuationVerseMarksAndPauseSigns() {
        fun find(query: String) = normalizeDhikrSearch(query).let { wanted ->
            repo.state.value.allEntries.filter { wanted in normalizeDhikrSearch(dhikrSearchText(it)) }.map { it.id } }
        assertTrue("kalimatan_khafifatan" in find("سبحان الله وبحمده سبحان الله العظيم"))
        assertTrue("surah_ikhlas" in find("قل هو الله أحد الله الصمد"))
        assertTrue("ayat_kursi" in find("ولا نوم له ما في السماوات"))
        // The occasion a dhikr belongs to finds it too, as in the library.
        assertTrue(find("الصباح").isNotEmpty())
    }
    @Test fun nextNudgePreviewDoesNotNeedNotificationAccess() {
        val rule = daily(480, 1200)
        Shadows.shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        val now = at(tuesday, 7)
        val stored = DhikrReminderScheduler.storedForm(context, repo.state.value, rule, now)
        assertNull(DhikrReminderScheduler.nextNudge(context, stored, now))
        assertEquals(at(tuesday, 8), DhikrReminderScheduler.nextNudge(context, stored, now, ignoreNotificationAccess = true))
    }
    @Test fun schedulePreviewResolvesBeforeADhikrIsChosen() {
        val blank = DhikrReminder(dhikrId = "", targetCount = 1)
        assertNull(DhikrReminderScheduler.currentOrNextWindow(context, blank, at(tuesday, 7)))
        assertEquals(at(tuesday, 8),
            DhikrReminderScheduler.currentOrNextWindow(context, blank, at(tuesday, 7), scheduleOnly = true)?.startMillis)
    }
    @Test fun editAfterTheDayWasCompletedDoesNotReopenIt() {
        val rule = twoPeriods().copy(targetCount = 3)
        repo.save(rule, at(tuesday.minusDays(1), 12))
        val now = at(tuesday, 9)
        DhikrReminderScheduler.refresh(context, rearm = true, nowMillis = now)
        val window = DhikrReminderScheduler.currentOrNextWindow(context, stored(rule.id), now)!!
        val session = repo.openSession(listOf(rule.dhikrId), occurrenceId = window.progressKey, now = now)
        repeat(3) { repo.count(session, 1, now + it) }
        assertEquals(DhikrOccurrenceStatus.COMPLETED, repo.state.value.occurrences.getValue(window.progressKey).status)
        repo.save(stored(rule.id).copy(cadence = DhikrCadence.ONCE), at(tuesday, 9, 5))
        // The afternoon period is not handed to the new revision with a goal back at zero.
        assertTrue(DhikrReminderScheduler.resolveWindows(context, stored(rule.id), tuesday).isEmpty())
        assertEquals(at(tuesday.plusDays(1), 8), DhikrReminderScheduler.nextNudge(context, stored(rule.id), at(tuesday, 9, 5)))
    }
    @Test fun editOfASwitchedOffReminderTakesEffectAtOnce() {
        val rule = daily(480, 1200)
        repo.save(rule, at(tuesday.minusDays(1), 12))
        repo.setEnabled(rule.id, false, at(tuesday.minusDays(1), 13))
        val now = at(tuesday, 10)
        repo.save(stored(rule.id).copy(enabled = true, start = DhikrTime(minuteOfDay = 660)), now)
        // Nothing was running, so there was no period to wait out.
        assertEquals(now, stored(rule.id).notBeforeMillis)
        assertEquals(at(tuesday, 11), DhikrReminderScheduler.currentOrNextWindow(context, stored(rule.id), now)?.startMillis)
    }
    @Test fun entryStandingForACollectionIsNotAnEdit() {
        val rule = com.tunisianprayertimes.ui.morningCollectionPreset()
        repo.save(rule, at(tuesday.minusDays(1), 12))
        assertFalse(repo.state.value.changesSchedule(stored(rule.id).copy(dhikrId = "ayat_kursi")))
        assertTrue(repo.state.value.changesSchedule(stored(rule.id).copy(collection = DhikrCategory.EVENING)))
    }
    @Test fun driftThatBitesWithinDaysIsStillCalledSeasonal() {
        val today = LocalDate.of(2026, 10, 2)
        val asr = Instant.ofEpochMilli(DhikrReminderScheduler.resolveTime(context, DhikrTime(DhikrTimeKind.ASR), today)!!)
            .atZone(ZoneId.systemDefault()).toLocalTime()
        // Valid today by four minutes; Asr comes earlier every day at this time of year.
        val rule = DhikrReminder(dhikrId = DhikrCatalog.SALAWAT_ID, targetCount = 100,
            start = DhikrTime(minuteOfDay = asr.hour * 60 + asr.minute - 4), end = DhikrTime(DhikrTimeKind.ASR))
        val error = DhikrReminderScheduler.validate(context, rule, today)
        assertTrue(error, error != null && error.startsWith("ابتداءً من") && "مواقيت الصلاة تتغيّر" in error)
    }
}

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
        assertEquals(listOf("متابعة الذكر", "تأجيل"), notifications.single().notification.actions.map { it.title.toString() })
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
        assertEquals(window.startMillis + 2 * 60_000L,
            DhikrReminderScheduler.nextNudge(context, second, window.startMillis + 1))

        deliver(deferred, window.startMillis + 2 * 60_000L)
        assertEquals(2, context.getSystemService(NotificationManager::class.java).activeNotifications.size)
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
    @Test fun nearEndNotificationDoesNotOfferAnUndeliverableSnooze() {
        val rule = rule().copy(end = DhikrTime(minuteOfDay = 540))
        repo.save(rule)
        val window = window(rule)
        DhikrReminderScheduler.refresh(context, rearm = true, nowMillis = window.startMillis - 1)
        deliver(event(), window.endMillis - 10 * 60_000L)
        val notification = context.getSystemService(NotificationManager::class.java).activeNotifications.single().notification
        assertEquals(listOf("متابعة الذكر"), notification.actions.map { it.title.toString() })

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
        val snooze = Shadows.shadowOf(manager.activeNotifications.single().notification.actions[1].actionIntent).savedIntent
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
    @Test fun prayerRelativeReminderUsesBundledYearFallbackAfter2026() {
        val delegation = PrefsManager.getDelegationId(context)
        val source = PrayerTimesRepository.loadDayPrayerTimes(context, delegation, 2026, 1, 1)!!
        val nextYear = LocalDate.of(2027, 1, 1)
        val fajr = DhikrReminderScheduler.resolveTime(context, DhikrTime(DhikrTimeKind.FAJR), nextYear)!!
        val localFajr = Instant.ofEpochMilli(fajr).atZone(ZoneId.systemDefault()).toLocalTime()
        assertEquals(source.fajr.hour, localFajr.hour)
        assertEquals(source.fajr.minute, localFajr.minute)
        val rule = rule().copy(start = DhikrTime(DhikrTimeKind.FAJR), end = DhikrTime(DhikrTimeKind.MAGHRIB))
        assertNotNull(DhikrReminderScheduler.resolveWindow(context, rule, nextYear))

        val leapDay = DhikrReminderScheduler.resolveTime(context, DhikrTime(DhikrTimeKind.FAJR), LocalDate.of(2028, 2, 29))!!
        val feb28 = PrayerTimesRepository.loadDayPrayerTimes(context, delegation, 2026, 2, 28)!!
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
}

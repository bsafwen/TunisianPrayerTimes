package com.tunisianprayertimes

import com.tunisianprayertimes.RamadanOverrideChecker.RamadanOverride
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Exercises the production cache with no real clock, preferences, or HTTP requests. */
class OfficialIslamicDatesTest {
    private val ramadan = LocalDate.of(2026, 2, 19)
    private val fitr = ramadan.plusDays(29)
    private val early = "2026-02-01T20:00:00Z"
    private val announced = "2026-02-18T20:00:00Z"
    private val later = "2026-03-19T20:00:00Z"

    private fun announcement(
        start: LocalDate? = ramadan,
        eid: LocalDate? = null,
        updated: String? = announced,
    ) = RamadanOverride(1447, start, eid, null, updated)

    private class Fixture {
        val saved = mutableMapOf<Int, String>()
        val reads = mutableMapOf<Int, Int>()
        val writes = mutableListOf<Int>()
        val published = mutableListOf<RamadanOverride>()
        val requests = AtomicInteger()
        val clock = AtomicLong()
        var legacy: String? = null
        var fetch: (Int) -> RamadanOverride? = { null }
        var storageFails = false

        fun advanceSeconds(seconds: Long) { clock.addAndGet(TimeUnit.SECONDS.toNanos(seconds)) }

        fun newStore() = OfficialIslamicDateStore(
            readSavedYear = { year ->
                reads[year] = (reads[year] ?: 0) + 1
                if (storageFails) throw IOException("unavailable preferences")
                saved[year]
            },
            writeSavedYear = { year, json ->
                if (storageFails) throw IOException("unavailable preferences")
                writes += year
                saved[year] = json
            },
            readLegacy = { legacy },
            fetchYear = { year -> requests.incrementAndGet(); fetch(year) },
            nanoTime = clock::get,
            currentHijriYear = { 1448 },
            onRecord = { published += it },
            legacyOverride = { null },
        )
    }

    @Test
    fun freshnessIsComparedPerEventRatherThanUsingTheFilesNewestTimestamp() {
        val fixture = Fixture()
        val store = fixture.newStore()
        store.record(announcement(eid = fitr).copy(eidFitrUpdated = early))

        val result = store.record(announcement(start = ramadan.minusDays(1), eid = fitr.plusDays(1), updated = later)
            .copy(ramadanStartUpdated = early))

        assertEquals(ramadan, result.ramadanStart)
        assertEquals(announced, result.ramadanStartUpdated)
        assertEquals(fitr.plusDays(1), result.eidFitrDate)
        assertEquals(later, result.eidFitrUpdated)
    }

    @Test
    fun partialUpdatePreservesMissingEventsAndTheirOriginalProvenance() {
        val store = Fixture().newStore()
        store.record(announcement(updated = null))
        val result = store.record(announcement(start = null, eid = fitr, updated = later))

        assertEquals(ramadan, result.ramadanStart)
        assertNull(result.ramadanStartUpdated)
        assertEquals(fitr, result.eidFitrDate)
        assertEquals(later, result.eidFitrUpdated)
        assertEquals(later, result.lastUpdated)
    }

    @Test
    fun undatedOrMalformedTimestampCannotReplaceAnAnnouncementWithKnownFreshness() {
        for (unknown in listOf(null, "not-a-timestamp")) {
            val store = Fixture().newStore()
            store.record(announcement())
            val result = store.record(announcement(start = ramadan.minusDays(1), updated = unknown))
            assertEquals(ramadan, result.ramadanStart, "incoming timestamp=$unknown")
            assertEquals(announced, result.ramadanStartUpdated)
        }
    }

    @Test
    fun datedAnnouncementCanCorrectAnOlderCacheWithUnknownProvenance() {
        val store = Fixture().newStore()
        store.record(announcement(start = ramadan.minusDays(1), updated = null))
        val result = store.record(announcement())
        assertEquals(ramadan, result.ramadanStart)
        assertEquals(announced, result.ramadanStartUpdated)
    }

    @Test
    fun timestampsCompareInstantsEvenWhenTheirTimeZoneSpellingDiffers() {
        val store = Fixture().newStore()
        store.record(announcement(updated = "2026-02-18T23:00:00+03:00"))
        val result = store.record(announcement(start = ramadan.plusDays(1), updated = "2026-02-18T20:01:00Z"))
        assertEquals(ramadan.plusDays(1), result.ramadanStart)
    }

    @Test
    fun importingAnOlderBundleLoadsAndPreservesTheSavedCorrectionFirst() {
        val fixture = Fixture()
        fixture.saved[1447] = RamadanOverrideChecker.toJson(announcement())
        val store = fixture.newStore()
        store.importJson(RamadanOverrideChecker.toJson(announcement(start = ramadan.minusDays(1), eid = fitr, updated = early)))

        val result = assertNotNull(store.cachedForYear(1447))
        assertEquals(ramadan, result.ramadanStart)
        assertEquals(announced, result.ramadanStartUpdated)
        assertEquals(fitr, result.eidFitrDate, "A previously missing event can still be imported")
        assertEquals(0, fixture.requests.get())
    }

    @Test
    fun partialUpdateAndRestartPreserveUnknownPerEventTimestamp() {
        val fixture = Fixture()
        val firstProcess = fixture.newStore()
        firstProcess.record(announcement(updated = null))
        firstProcess.record(announcement(start = null, eid = fitr, updated = later))

        val restarted = fixture.newStore()
        restarted.loadCachedYear(1447)
        val restored = assertNotNull(restarted.cachedForYear(1447))
        assertNull(restored.ramadanStartUpdated)
        assertEquals(later, restored.eidFitrUpdated)
        assertEquals(ramadan, restored.ramadanStart)
        assertEquals(fitr, restored.eidFitrDate)

        // March's Fitr update must not make an unknown Ramadan date appear newer than February's correction.
        restarted.record(announcement(start = ramadan.plusDays(1), updated = announced))
        assertEquals(ramadan.plusDays(1), restarted.cachedForYear(1447)?.ramadanStart)
        assertEquals(fitr, restarted.cachedForYear(1447)?.eidFitrDate)
        assertEquals(0, fixture.requests.get())
    }

    @Test
    fun legacySingleYearCacheMigratesWithoutOverwritingNewerPerYearCorrections() {
        val fixture = Fixture()
        fixture.legacy = """{
            "hijriYear":1447, "ramadanStart":"${ramadan.minusDays(1)}",
            "eidFitrDate":"$fitr", "lastUpdated":"$early"
        }"""
        fixture.saved[1447] = RamadanOverrideChecker.toJson(announcement())
        val store = fixture.newStore()
        store.loadCachedDates()

        val migrated = assertNotNull(store.cachedForYear(1447))
        assertEquals(ramadan, migrated.ramadanStart)
        assertEquals(announced, migrated.ramadanStartUpdated)
        assertEquals(fitr, migrated.eidFitrDate)
        assertEquals(early, migrated.eidFitrUpdated, "Old files inherit their original global freshness")
        val restarted = fixture.newStore()
        restarted.loadCachedYear(1447)
        assertEquals(migrated, restarted.cachedForYear(1447))
        assertEquals(0, fixture.requests.get())
    }

    @Test
    fun legacyMigrationRetainsAnExplicitUnknownTimestamp() {
        val fixture = Fixture()
        fixture.legacy = """{
            "hijriYear":1447, "ramadanStart":"$ramadan", "lastUpdated":"$later",
            "ramadanStartUpdated":null
        }"""
        fixture.newStore().loadCachedDates()
        val restarted = fixture.newStore()
        restarted.loadCachedYear(1447)
        assertNull(assertNotNull(restarted.cachedForYear(1447)).ramadanStartUpdated)
    }

    @Test
    fun cachedReadsAndCalendarFormattingNeverFetchAndLoadEachSavedYearOnce() {
        val fixture = Fixture()
        fixture.saved[1447] = RamadanOverrideChecker.toJson(announcement())
        val store = fixture.newStore()
        repeat(3) {
            store.loadCachedYear(1447)
            assertEquals(ramadan, store.cachedForYear(1447)?.ramadanStart)
            assertEquals(ramadan, store.calendar().month(1447, 9).start)
        }
        assertEquals(1, fixture.reads[1447])
        assertEquals(0, fixture.requests.get())
    }

    @Test
    fun corruptOrWrongYearStorageCannotLeakIntoTheRequestedYear() {
        for (json in listOf("broken JSON", RamadanOverrideChecker.toJson(announcement().copy(hijriYear = 1446)))) {
            val fixture = Fixture()
            fixture.saved[1447] = json
            val store = fixture.newStore()
            store.loadCachedYear(1447)
            assertTrue(store.updates.value.isEmpty())
            assertEquals(0, fixture.requests.get())
        }
    }

    @Test
    fun unsupportedYearsDoNotReadStorageFetchOrPublish() {
        val fixture = Fixture()
        val store = fixture.newStore()
        for (year in listOf(Int.MIN_VALUE, 1299, 1601, Int.MAX_VALUE)) {
            store.loadCachedYear(year)
            store.loadYear(year, refresh = true)
            store.record(announcement().copy(hijriYear = year))
        }
        assertTrue(store.updates.value.isEmpty())
        assertTrue(fixture.reads.isEmpty())
        assertTrue(fixture.published.isEmpty())
        assertEquals(0, fixture.requests.get())
    }

    @Test
    fun unavailableStorageDoesNotPreventAnAnnouncementFromBeingPublished() {
        val fixture = Fixture().apply { storageFails = true }
        val store = fixture.newStore()
        store.importJson(RamadanOverrideChecker.toJson(announcement()))
        assertEquals(ramadan, store.cachedForYear(1447)?.ramadanStart)
        assertEquals(ramadan, fixture.published.last().ramadanStart)
    }

    @Test
    fun calendarSnapshotsChangeWhenCorrectionsChangeButNotOnRepeatedImports() {
        val fixture = Fixture()
        val store = fixture.newStore()
        val estimate = store.calendar()
        val record = announcement()
        store.record(record)
        val corrected = store.calendar()
        assertNotSame(estimate, corrected)
        assertEquals(ramadan, corrected.month(1447, 9).start)
        val writesBefore = fixture.writes.size
        store.record(record)
        assertSame(corrected, store.calendar())
        assertEquals(writesBefore, fixture.writes.size)
    }

    @Test
    fun loadingAnotherYearPreservesTheFirstYearsAnnouncementsAcrossRestart() {
        val fixture = Fixture()
        val store = fixture.newStore()
        store.record(announcement())
        val nextYear = announcement(start = LocalDate.of(2027, 2, 8)).copy(hijriYear = 1448)
        store.record(nextYear)
        val restarted = fixture.newStore()
        restarted.loadCachedDates()
        restarted.loadCachedYear(1447)
        // Missing events have no provenance, even when the input used the data class's timestamp defaults.
        assertEquals(announcement().copy(eidFitrUpdated = null, eidAdhaUpdated = null), restarted.cachedForYear(1447))
        assertEquals(nextYear.copy(eidFitrUpdated = null, eidAdhaUpdated = null), restarted.cachedForYear(1448))
    }

    @Test
    fun successfulFetchIsThrottledForAnHourAndRefreshStillRequiresFiveSeconds() {
        val fixture = Fixture().apply { fetch = { announcement() } }
        val store = fixture.newStore()
        store.loadYear(1447)
        fixture.advanceSeconds(4)
        store.loadYear(1447, refresh = true)
        assertEquals(1, fixture.requests.get())
        fixture.advanceSeconds(1)
        store.loadYear(1447, refresh = true)
        assertEquals(2, fixture.requests.get())
        fixture.advanceSeconds(3599)
        store.loadYear(1447)
        assertEquals(2, fixture.requests.get())
        fixture.advanceSeconds(1)
        store.loadYear(1447)
        assertEquals(3, fixture.requests.get())
    }

    @Test
    fun nullResponseRetriesAfterOneMinuteAndKeepsOfflineCorrectionVisible() {
        val fixture = Fixture()
        fixture.saved[1447] = RamadanOverrideChecker.toJson(announcement())
        val store = fixture.newStore()
        store.loadYear(1447)
        assertEquals(ramadan, store.cachedForYear(1447)?.ramadanStart)
        fixture.advanceSeconds(59)
        store.loadYear(1447)
        assertEquals(1, fixture.requests.get())
        fixture.advanceSeconds(1)
        fixture.fetch = { announcement(eid = fitr, updated = later) }
        store.loadYear(1447)
        assertEquals(2, fixture.requests.get())
        assertEquals(fitr, store.cachedForYear(1447)?.eidFitrDate)
    }

    @Test
    fun failedRefreshAfterSuccessUsesTheShortFailureRetryInterval() {
        val fixture = Fixture().apply { fetch = { announcement() } }
        val store = fixture.newStore()
        store.loadYear(1447)
        fixture.advanceSeconds(5)
        fixture.fetch = { throw IOException("offline") }
        store.loadYear(1447, refresh = true)
        fixture.advanceSeconds(59)
        store.loadYear(1447)
        assertEquals(2, fixture.requests.get())
        fixture.advanceSeconds(1)
        fixture.fetch = { announcement(eid = fitr, updated = later) }
        store.loadYear(1447)
        assertEquals(3, fixture.requests.get())
        assertEquals(fitr, store.cachedForYear(1447)?.eidFitrDate)
    }

    @Test
    fun reconnectCanRetryAFailedRequestAfterFiveSeconds() {
        val fixture = Fixture()
        val store = fixture.newStore()
        store.loadYear(1447)
        fixture.advanceSeconds(4)
        store.loadYear(1447, refresh = true)
        assertEquals(1, fixture.requests.get())
        fixture.advanceSeconds(1)
        fixture.fetch = { announcement() }
        store.loadYear(1447, refresh = true)
        assertEquals(2, fixture.requests.get())
        assertEquals(ramadan, store.cachedForYear(1447)?.ramadanStart)
    }

    @Test
    fun concurrentRequestsForTheSameYearAreDeduplicatedWhileOtherYearsCanLoad() {
        val fixture = Fixture()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        fixture.fetch = { year ->
            if (year == 1447) {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "Test did not release the blocked fetch" }
                announcement()
            } else announcement().copy(hijriYear = year)
        }
        val store = fixture.newStore()
        val worker = thread(isDaemon = true) {
            try { store.loadYear(1447) } catch (error: Throwable) { failure.set(error) }
        }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            fixture.advanceSeconds(3600)
            store.loadYear(1447)
            store.loadYear(1447, refresh = true)
            assertEquals(1, fixture.requests.get())
            store.loadYear(1448)
            assertEquals(2, fixture.requests.get())
            assertNotNull(store.cachedForYear(1448))
        } finally {
            release.countDown()
            worker.join(5000)
        }
        assertFalse(worker.isAlive)
        assertNull(failure.get())
        assertEquals(ramadan, store.cachedForYear(1447)?.ramadanStart)
    }

    @Test
    fun exceptionReleasesTheInFlightSlotAndDoesNotEraseSavedDates() {
        val fixture = Fixture()
        val store = fixture.newStore()
        store.record(announcement())
        fixture.fetch = { throw IOException("connection reset") }
        store.loadYear(1447)
        assertEquals(ramadan, store.cachedForYear(1447)?.ramadanStart)
        fixture.advanceSeconds(60)
        fixture.fetch = { announcement(start = null, eid = fitr, updated = later) }
        store.loadYear(1447)
        assertEquals(2, fixture.requests.get())
        assertEquals(ramadan, store.cachedForYear(1447)?.ramadanStart)
        assertEquals(fitr, store.cachedForYear(1447)?.eidFitrDate)
    }
}

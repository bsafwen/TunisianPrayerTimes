package com.tunisianprayertimes.tv.ui

import com.tunisianprayertimes.time.ClockGuard
import com.tunisianprayertimes.time.ClockSource
import com.tunisianprayertimes.time.ClockStore
import com.tunisianprayertimes.time.ClockTrust
import com.tunisianprayertimes.time.DeviceMark
import com.tunisianprayertimes.tv.ui.clock.ClockView
import com.tunisianprayertimes.tv.ui.clock.OkBurst
import com.tunisianprayertimes.tv.ui.clock.SteppedTime
import com.tunisianprayertimes.tv.ui.clock.candidateLabel
import com.tunisianprayertimes.tv.ui.clock.clockRows
import com.tunisianprayertimes.tv.ui.clock.clockStatus
import com.tunisianprayertimes.tv.ui.clock.clockZoneNote
import com.tunisianprayertimes.tv.ui.clock.stepTime
import com.tunisianprayertimes.tv.ui.kiosk.HealthLevel
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The clock page's logic: what it offers, and the rows of the kiosk page and the phone. */
class ClockPageTest {

    private class MemoryStore : ClockStore {
        override var lastKnownGoodMillis = 0L
        override var correctionMillis = 0L
        override var confirmedBy: String? = null
        override var deviceMark: DeviceMark? = null
        override var foreignZoneSeen = false
    }

    private fun view(system: String, zone: String, store: ClockStore = MemoryStore()): ClockView {
        val guard = ClockGuard(store, { Instant.parse(system) }, { 1_000L }, { ZoneId.of(zone) })
        return ClockView.of(guard, guard.read(), "device")
    }

    private fun time(text: String) = LocalDateTime.parse(text)

    @Test
    fun aBoxOnShanghaiTimeOffersBothReadingsOfItsClock() {
        val view = view("2026-10-01T04:00:00Z", "Asia/Shanghai")
        assertEquals(ClockTrust.UNVERIFIED, view.trust)
        assertTrue(view.zoneDiffers)
        // The screen's time (the instant in Tunisia), then the device clock as it reads.
        assertEquals(listOf(LocalDateTime.parse("2026-10-01T05:00:00"), LocalDateTime.parse("2026-10-01T12:00:00")), view.candidates)
        assertEquals(TvStrings.CLOCK_CANDIDATE_SCREEN, candidateLabel(0))
        assertEquals(TvStrings.CLOCK_CANDIDATE_DEVICE, candidateLabel(1))
        assertTrue(view.confirmable)
    }

    @Test
    fun aBoxOnParisTimeInWinterIsToldToSetTheZoneFirst() {
        // Paris agrees with Tunisia in winter, but not in summer: the clock stays unconfirmed, and the
        // page says why, though today's offsets are the same.
        val view = view("2026-12-01T11:00:00Z", "Europe/Paris")
        assertEquals(ClockTrust.UNVERIFIED, view.trust)
        assertFalse(view.zoneDiffers)
        assertTrue(view.zoneFirst)
        // Algiers keeps Tunisia's time all year: nothing to set.
        assertFalse(view("2026-12-01T11:00:00Z", "Africa/Algiers").zoneFirst)
    }

    @Test
    fun aClockResetTo2015CannotBeConfirmedOnlySet() {
        val view = view("2015-01-01T00:03:00Z", "Africa/Tunis")
        assertEquals(ClockTrust.IMPLAUSIBLE, view.trust)
        assertFalse(view.confirmable)
        // The steppers start at the floor, not years back.
        assertFalse(view.suggested.isBefore(LocalDateTime.parse("2026-09-01T01:00:00")))
    }

    @Test
    fun theBlockingPageOnlyOffersToConfirmTheDeviceTimeItShows() {
        // A box reset by a power cut, with a correction from before the cut still in its memory.
        val store = MemoryStore().apply {
            lastKnownGoodMillis = Instant.parse("2026-10-03T17:00:00Z").toEpochMilli()
            correctionMillis = Duration.ofDays(-2).toMillis()
            confirmedBy = ClockSource.ADMIN.name
        }
        val view = view("2026-10-01T11:00:00Z", "Africa/Tunis", store)
        assertEquals(ClockTrust.IMPLAUSIBLE, view.trust)
        // «هذا الوقت صحيح» is written with the device's own time, not the old correction's.
        assertTrue(view.confirmable)
        assertEquals(time("2026-10-01T12:00:00"), view.now)
    }

    @Test
    fun eachStepperMovesItsOwnFieldOnly() {
        // 2 Oct 23:00 set to 04:30 the same day: the hour and the minute never carry into the date.
        val late = time("2026-10-02T23:00:42")
        val early = stepTime(stepTime(late, ChronoField.HOUR_OF_DAY, 5), ChronoField.MINUTE_OF_HOUR, 30)
        assertEquals(time("2026-10-02T04:30:42"), early)
        assertEquals(time("2026-10-02T23:59:42"), stepTime(late, ChronoField.MINUTE_OF_HOUR, -1))
        assertEquals(time("2026-10-02T00:00:42"), stepTime(late, ChronoField.HOUR_OF_DAY, 1))
        // The day wraps within its own month, whatever its length.
        assertEquals(time("2026-10-01T23:00:42"), stepTime(time("2026-10-31T23:00:42"), ChronoField.DAY_OF_MONTH, 1))
        assertEquals(time("2026-02-28T12:00:00"), stepTime(time("2026-02-01T12:00:00"), ChronoField.DAY_OF_MONTH, -1))
        // The month has the year on its row: December goes on to January of the next year.
        assertEquals(time("2027-01-02T23:00:42"), stepTime(late.withMonth(12), ChronoField.MONTH_OF_YEAR, 1))
    }

    @Test
    fun theSteppersTimeRunsOnByItself() {
        // Opened at 23:30 (10 s into the boot), the hour stepped to 04: a minute later it is 04:31.
        val set = SteppedTime(time("2026-10-02T23:30:00"), since = 10_000).step(ChronoField.HOUR_OF_DAY, 5, elapsed = 10_000)
        assertEquals(time("2026-10-02T04:30:00"), set.at(10_000))
        assertEquals(time("2026-10-02T04:31:00"), set.at(70_000))
        // A step later keeps the time that ran on; nothing else moves it (a correction of the screen's time does not).
        assertEquals(time("2026-10-02T04:32:30"), set.step(ChronoField.MINUTE_OF_HOUR, 1, elapsed = 100_000).at(100_000))
    }

    @Test
    fun theQuestionIgnoresTheOkPressesThatOpenedIt() {
        // Settings open on the fifth quick OK; the question takes their place and more presses follow.
        val burst = OkBurst(shownAt = 10_000)
        for (at in listOf(10_120L, 10_300, 10_450, 10_600)) {
            assertTrue(burst.ignores(down = true, at = at))
            assertTrue(burst.ignores(down = false, at = at + 80))
        }
        // The admin reads, then presses on purpose: that one counts, and every one after it.
        assertFalse(burst.ignores(down = true, at = 12_000))
        assertFalse(burst.ignores(down = false, at = 12_090))
        assertFalse(burst.ignores(down = true, at = 12_200))
    }

    @Test
    fun aHeldOkIsNotAnAnswerEitherWhenReleased() {
        val burst = OkBurst(shownAt = 10_000)
        assertTrue(burst.ignores(down = true, at = 10_050))
        for (repeat in 1..40) assertTrue(burst.ignores(down = true, at = 10_450 + repeat * 50L))
        assertTrue(burst.ignores(down = false, at = 13_000))
        // A press after the quiet moment is the first answer.
        assertFalse(burst.ignores(down = true, at = 13_000 + OkBurst.QUIET_MILLIS))
    }

    @Test
    fun aConfirmedClockHasNothingToConfirm() {
        val view = view("2026-10-01T11:00:00Z", "Africa/Tunis")
        assertEquals(ClockTrust.TRUSTED, view.trust)
        assertFalse(view.confirmable)
        assertEquals(listOf(LocalDateTime.parse("2026-10-01T12:00:00")), view.candidates)
    }

    @Test
    fun theStatusSaysHowTheTimeWasConfirmed() {
        assertEquals("مؤكَّدة (من الإنترنت)", clockStatus(ClockTrust.TRUSTED, ClockSource.NETWORK))
        assertEquals("مؤكَّدة (من الهاتف)", clockStatus(ClockTrust.TRUSTED, ClockSource.PHONE))
        assertEquals(TvStrings.CLOCK_UNCONFIRMED, clockStatus(ClockTrust.UNVERIFIED, null))
        assertEquals(TvStrings.CLOCK_WRONG_TITLE, clockStatus(ClockTrust.IMPLAUSIBLE, null))
    }

    @Test
    fun theKioskRowsWarnOfAnUnconfirmedClockAndExplainTheZone() {
        val shanghai = ZoneId.of("Asia/Shanghai")
        // Unconfirmed, the zone may be what is wrong: nothing says it does not matter.
        val unverified = clockRows(ClockTrust.UNVERIFIED, null, shanghai, zoneDiffers = true)
        assertEquals(listOf(HealthLevel.WARNING), unverified.map { it.level })
        assertEquals(TvStrings.CLOCK_ROW_UNVERIFIED_FIX, unverified[0].fix)
        assertEquals(null, clockZoneNote(ClockTrust.UNVERIFIED, shanghai, zoneDiffers = true))
        assertEquals(listOf(HealthLevel.BAD), clockRows(ClockTrust.IMPLAUSIBLE, null, shanghai, zoneDiffers = true).map { it.level })

        val confirmed = clockRows(ClockTrust.TRUSTED, ClockSource.ADMIN, shanghai, zoneDiffers = true)
        assertEquals(listOf(HealthLevel.GOOD, HealthLevel.INFO), confirmed.map { it.level })
        // The zone id is Latin inside an Arabic sentence: a right-to-left mark keeps the colon after it.
        assertTrue(confirmed[1].text.contains("Asia/Shanghai" + Char(0x200F) + ":"))
        assertEquals(confirmed[1].text, clockZoneNote(ClockTrust.TRUSTED, shanghai, zoneDiffers = true))

        val trusted = clockRows(ClockTrust.TRUSTED, ClockSource.ZONE, ZoneId.of("Africa/Tunis"), zoneDiffers = false)
        assertEquals(listOf(HealthLevel.GOOD), trusted.map { it.level })
    }
}

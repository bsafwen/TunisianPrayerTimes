package com.tunisianprayertimes.tv.ui

import com.tunisianprayertimes.time.ClockGuard
import com.tunisianprayertimes.time.ClockSource
import com.tunisianprayertimes.time.ClockStore
import com.tunisianprayertimes.time.ClockTrust
import com.tunisianprayertimes.tv.ui.clock.ClockView
import com.tunisianprayertimes.tv.ui.clock.candidateLabel
import com.tunisianprayertimes.tv.ui.clock.clockRows
import com.tunisianprayertimes.tv.ui.clock.clockStatus
import com.tunisianprayertimes.tv.ui.kiosk.HealthLevel
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
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
        override var confirmedAtDeviceMillis = 0L
    }

    private fun view(system: String, zone: String): ClockView {
        val guard = ClockGuard(MemoryStore(), { Instant.parse(system) }, { 1_000L }, { ZoneId.of(zone) })
        return ClockView.of(guard, guard.read(), "device")
    }

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
    fun aClockResetTo2015CannotBeConfirmedOnlySet() {
        val view = view("2015-01-01T00:03:00Z", "Africa/Tunis")
        assertEquals(ClockTrust.IMPLAUSIBLE, view.trust)
        assertFalse(view.confirmable)
        // The steppers start at the floor, not years back.
        assertFalse(view.suggested.isBefore(LocalDateTime.parse("2026-09-01T01:00:00")))
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
        val unverified = clockRows(ClockTrust.UNVERIFIED, null, shanghai, zoneDiffers = true)
        assertEquals(listOf(HealthLevel.WARNING, HealthLevel.INFO), unverified.map { it.level })
        assertEquals(TvStrings.CLOCK_ROW_UNVERIFIED_FIX, unverified[0].fix)
        assertTrue(unverified[1].text.contains("Asia/Shanghai"))
        // The zone id is Latin inside an Arabic sentence: a right-to-left mark keeps the colon after it.
        assertTrue(unverified[1].text.contains("Asia/Shanghai" + Char(0x200F) + ":"))

        val trusted = clockRows(ClockTrust.TRUSTED, ClockSource.ZONE, ZoneId.of("Africa/Tunis"), zoneDiffers = false)
        assertEquals(listOf(HealthLevel.GOOD), trusted.map { it.level })
        assertEquals(HealthLevel.BAD, clockRows(ClockTrust.IMPLAUSIBLE, null, shanghai, zoneDiffers = true).first().level)
    }
}

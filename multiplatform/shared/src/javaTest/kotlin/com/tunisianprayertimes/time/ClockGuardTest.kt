package com.tunisianprayertimes.time

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class ClockGuardTest {

    private class MemoryStore : ClockStore {
        override var lastKnownGoodMillis = 0L
        override var correctionMillis = 0L
        override var correctionElapsedMillis = 0L
    }

    private val store = MemoryStore()
    private var system: Instant = Instant.parse("2026-10-01T11:00:00Z") // 12:00 in Tunis
    private var elapsed = 3_600_000L
    private var zone: ZoneId = TunisTime.ZONE
    private val guard = ClockGuard(store, { system }, { elapsed }, { zone })

    @Test
    fun aNormalClockIsTrustedAndReadInTunisTime() {
        assertEquals(ClockReading(LocalDateTime.parse("2026-10-01T12:00:00"), ClockTrust.TRUSTED), guard.read())
        assertEquals(system.toEpochMilli(), store.lastKnownGoodMillis)
    }

    @Test
    fun aClockResetByAPowerCutIsNotTrusted() {
        for (reset in listOf("1970-01-01T00:00:00Z", "2015-01-01T00:00:00Z", "2026-08-31T23:00:00Z")) {
            system = Instant.parse(reset)
            assertEquals(ClockTrust.IMPLAUSIBLE, guard.read().trust, reset)
        }
        assertEquals(0L, store.lastKnownGoodMillis, "a wrong clock never becomes the reference")
    }

    @Test
    fun aJumpBackBehindTheLastGoodTimeIsNotTrusted() {
        guard.read()
        // Rebooted without a clock battery: the box restarted at its firmware date, days earlier.
        system = Instant.parse("2026-09-25T08:00:00Z")
        elapsed = 5_000
        assertEquals(ClockTrust.IMPLAUSIBLE, guard.read().trust)
        // The admin checks the wall clock and confirms: it is the new reference.
        guard.confirm()
        assertEquals(ClockTrust.TRUSTED, guard.read().trust)
    }

    @Test
    fun smallCorrectionsAreNormal() {
        guard.read()
        system = system.minusSeconds(30 * 60) // network time pulled the clock back half an hour
        assertEquals(ClockTrust.TRUSTED, guard.read().trust)
    }

    @Test
    fun aDeviceInAnotherZoneStillShowsTunisTime() {
        zone = ZoneId.of("Europe/Paris") // summer: UTC+2 while Tunisia is UTC+1
        val reading = guard.read()
        assertEquals(ClockTrust.WRONG_ZONE, reading.trust)
        assertEquals(LocalDateTime.parse("2026-10-01T12:00:00"), reading.now)
        system = Instant.parse("2026-12-01T11:00:00Z") // winter: both UTC+1, nothing to warn about
        assertEquals(ClockTrust.TRUSTED, guard.read().trust)
    }

    @Test
    fun theAdminCanSetTheTimeInTheAppUntilTheNextReboot() {
        system = Instant.parse("2015-01-01T00:00:00Z")
        assertEquals(ClockTrust.IMPLAUSIBLE, guard.read().trust)

        guard.setTime(LocalDateTime.parse("2026-10-01T12:00:00"))
        assertEquals(ClockReading(LocalDateTime.parse("2026-10-01T12:00:00"), ClockTrust.TRUSTED), guard.read())
        system = system.plusSeconds(90)
        elapsed += 90_000
        assertEquals(LocalDateTime.parse("2026-10-01T12:01:30"), guard.read().now)

        elapsed = 10_000 // rebooted: the device clock is back at 2015 and the correction no longer holds
        system = Instant.parse("2015-01-01T00:00:10Z")
        assertEquals(ClockTrust.IMPLAUSIBLE, guard.read().trust)
        assertEquals(0L, store.correctionMillis)
    }

    @Test
    fun aCorrectionStopsOnceTheDeviceClockIsFixed() {
        system = Instant.parse("2015-01-01T00:00:00Z")
        guard.setTime(LocalDateTime.parse("2026-10-01T12:00:00"))
        assertEquals(ClockTrust.TRUSTED, guard.read().trust)
        // Network time comes back: the device clock is right again and the correction must not be added to it.
        system = Instant.parse("2026-10-01T11:05:00Z")
        assertEquals(ClockReading(LocalDateTime.parse("2026-10-01T12:05:00"), ClockTrust.TRUSTED), guard.read())
        assertEquals(0L, store.correctionMillis)
    }

    @Test
    fun settingTheTimeByHandStartsNearTheRealTime() {
        guard.read() // last good: 2026-10-01 12:00 in Tunis
        system = Instant.parse("1970-01-01T00:00:00Z")
        assertEquals(LocalDateTime.parse("2026-10-01T12:00:00"), guard.suggestedTime())
        store.lastKnownGoodMillis = 0
        assertEquals(LocalDateTime.parse("2026-09-01T01:00:00"), guard.suggestedTime()) // the floor, in Tunis time
    }

    @Test
    fun beyondTheFormulaRangeIsNotTrusted() {
        system = Instant.parse("2101-06-01T00:00:00Z")
        assertEquals(ClockTrust.IMPLAUSIBLE, guard.read().trust)
    }
}

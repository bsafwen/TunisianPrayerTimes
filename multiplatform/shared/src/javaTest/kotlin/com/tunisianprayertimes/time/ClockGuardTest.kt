package com.tunisianprayertimes.time

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClockGuardTest {

    private class MemoryStore : ClockStore {
        override var lastKnownGoodMillis = 0L
        override var correctionMillis = 0L
        override var confirmedBy: String? = null
        override var confirmedAtDeviceMillis = 0L
    }

    private val store = MemoryStore()
    private var system: Instant = Instant.parse("2026-10-01T11:00:00Z") // 12:00 in Tunis
    private var elapsed = 3_600_000L
    private var zone: ZoneId = TunisTime.ZONE
    private var guard = ClockGuard(store, { system }, { elapsed }, { zone })

    private fun time(text: String) = LocalDateTime.parse(text)

    /** The app restarts (a reboot, or the process started again): a new guard on the same memory. */
    private fun restart(elapsedAfterBoot: Long = 20_000) {
        elapsed = elapsedAfterBoot
        guard = ClockGuard(store, { system }, { elapsed }, { zone })
    }

    /** Time passes normally: the device clock and the time since boot move together. */
    private fun pass(seconds: Long) {
        system = system.plusSeconds(seconds)
        elapsed += seconds * 1000
    }

    @Test
    fun aClockInTunisiasZoneIsTrusted() {
        assertEquals(ClockReading(time("2026-10-01T12:00:00"), ClockTrust.TRUSTED, ClockSource.ZONE), guard.read())
        assertEquals(system.toEpochMilli(), store.lastKnownGoodMillis)
        // Another zone that reads Tunisia's time now (Algiers) is as good.
        zone = ZoneId.of("Africa/Algiers")
        assertEquals(ClockTrust.TRUSTED, guard.read().trust)
    }

    @Test
    fun aClockResetByAPowerCutIsNotTrusted() {
        for (reset in listOf("1970-01-01T00:00:00Z", "2015-01-01T00:00:00Z", "2026-08-31T23:00:00Z")) {
            system = Instant.parse(reset)
            restart()
            assertEquals(ClockTrust.IMPLAUSIBLE, guard.read().trust, reset)
        }
        assertEquals(0L, store.lastKnownGoodMillis, "a wrong clock never becomes the reference")
    }

    @Test
    fun aRebootBackBehindTheLastGoodTimeIsNotTrusted() {
        guard.read()
        // Rebooted without a clock battery: the box restarted at its firmware date, days earlier.
        system = Instant.parse("2026-09-25T08:00:00Z")
        restart(5_000)
        assertEquals(ClockTrust.IMPLAUSIBLE, guard.read().trust)
        // The admin checks their watch and confirms the time shown: it is the new reference.
        assertTrue(guard.accept(time("2026-10-01T12:30:00")))
        assertEquals(ClockReading(time("2026-10-01T12:30:00"), ClockTrust.TRUSTED, ClockSource.ADMIN), guard.read())
    }

    @Test
    fun theClockSetWhileTheAppRunsIsTheNewReference() {
        guard.read()
        pass(10)
        system = system.minusSeconds(3 * 3600) // someone sets the device clock back three hours in the system settings
        assertEquals(ClockTrust.TRUSTED, guard.read().trust, "a fix, not a power cut")
        assertEquals(time("2026-10-01T09:00:10"), guard.read().now)
    }

    @Test
    fun aClockFromTheNetworkInAForeignZoneShowsTunisiasTimeUntilChecked() {
        zone = ZoneId.of("Asia/Shanghai") // UTC+8, the instant is right (network time)
        val reading = guard.read()
        assertEquals(ClockReading(time("2026-10-01T12:00:00"), ClockTrust.UNVERIFIED), reading)
        // The admin is offered the converted time first, and the device's own clock second.
        assertEquals(listOf(time("2026-10-01T12:00:00"), time("2026-10-01T19:00:00")), guard.candidates())
        // Once online, the network confirms it: nothing moves.
        guard.networkTime(system)
        assertEquals(ClockReading(time("2026-10-01T12:00:00"), ClockTrust.TRUSTED, ClockSource.NETWORK), guard.read())
        assertEquals(0L, store.correctionMillis)
    }

    @Test
    fun aClockSetByHandOnGmtIsCorrectedByTheAdminAndStaysCorrected() {
        // The box is on GMT; its owner set it to 12:00 on their watch, so the instant says 13:00 in Tunis.
        zone = ZoneId.of("GMT")
        system = Instant.parse("2026-10-01T12:00:00Z")
        assertEquals(ClockReading(time("2026-10-01T13:00:00"), ClockTrust.UNVERIFIED), guard.read())
        val (fromInstant, fromDeviceClock) = guard.candidates()
        assertEquals(time("2026-10-01T13:00:00"), fromInstant)
        assertEquals(time("2026-10-01T12:00:00"), fromDeviceClock)
        // The admin picks the device's clock: every prayer moves back to the right hour.
        assertTrue(guard.accept(fromDeviceClock))
        assertEquals(ClockReading(time("2026-10-01T12:00:00"), ClockTrust.TRUSTED, ClockSource.ADMIN), guard.read())
        // A reboot on a box with a clock battery: the error is still there, and so is the answer.
        pass(600)
        restart()
        assertEquals(ClockReading(time("2026-10-01T12:10:00"), ClockTrust.TRUSTED, ClockSource.ADMIN), guard.read())
    }

    @Test
    fun aClockSetByHandOnShanghaiTimeIsSevenHoursOffUntilAnswered() {
        zone = ZoneId.of("Asia/Shanghai")
        system = Instant.parse("2026-10-01T04:00:00Z") // 12:00 on the owner's watch, read as Shanghai time
        assertEquals(time("2026-10-01T05:00:00"), guard.read().now)
        assertEquals(listOf(time("2026-10-01T05:00:00"), time("2026-10-01T12:00:00")), guard.candidates())
        guard.accept(time("2026-10-01T12:00:00"))
        assertEquals(time("2026-10-01T12:00:00"), guard.read().now)
    }

    @Test
    fun changingTheDeviceClockDropsTheAnswer() {
        zone = ZoneId.of("GMT")
        system = Instant.parse("2026-10-01T12:00:00Z")
        guard.read()
        guard.accept(time("2026-10-01T12:00:00"))
        pass(60)
        guard.read()
        // The admin now fixes the device clock itself, one hour back: the old correction must not apply on top.
        system = system.minusSeconds(3600)
        val reading = guard.read()
        assertEquals(ClockTrust.UNVERIFIED, reading.trust)
        assertEquals(time("2026-10-01T12:01:00"), reading.now)
        assertEquals(0L, store.correctionMillis)
    }

    @Test
    fun theSystemBroadcastDropsTheAnswerWhenTheAppWasNotRunning() {
        zone = ZoneId.of("GMT")
        guard.accept(time("2026-10-01T12:00:00"))
        ClockGuard.systemClockChanged(store, Instant.parse("2026-10-01T11:00:00Z"))
        restart()
        assertEquals(ClockTrust.UNVERIFIED, guard.read().trust)
        assertEquals(0L, store.correctionMillis)
    }

    @Test
    fun aPowerCutAfterAnAnswerAsksAgain() {
        zone = ZoneId.of("GMT")
        system = Instant.parse("2026-10-01T12:00:00Z")
        guard.accept(time("2026-10-01T12:00:00"))
        // No clock battery: the box comes back at a firmware date after the floor, before the answer.
        system = Instant.parse("2026-09-20T00:00:00Z")
        restart()
        assertEquals(ClockTrust.IMPLAUSIBLE, guard.read().trust)
        assertEquals(null, store.confirmedBy)
    }

    @Test
    fun theNetworkCorrectsAWrongClockEvenInTunisiasZone() {
        // In Tunisia's zone but set an hour behind by hand: the zone alone cannot see it, the network can.
        system = Instant.parse("2026-10-01T10:00:00Z")
        assertEquals(time("2026-10-01T11:00:00"), guard.read().now)
        guard.networkTime(Instant.parse("2026-10-01T11:00:00Z"))
        assertEquals(ClockReading(time("2026-10-01T12:00:00"), ClockTrust.TRUSTED, ClockSource.NETWORK), guard.read())
        // Seconds of difference later confirm it again without moving it.
        val correction = store.correctionMillis
        guard.networkTime(system.plusMillis(correction).plusSeconds(20))
        assertEquals(correction, store.correctionMillis)
    }

    @Test
    fun theNetworkOverridesAnAdminsWrongAnswer() {
        zone = ZoneId.of("GMT")
        guard.accept(time("2026-10-01T15:00:00")) // a mistake: three hours ahead
        guard.networkTime(system)
        assertEquals(ClockReading(time("2026-10-01T12:00:00"), ClockTrust.TRUSTED, ClockSource.NETWORK), guard.read())
    }

    @Test
    fun theAdminCannotConfirmAnImpossibleClock() {
        system = Instant.parse("2015-01-01T00:00:00Z")
        assertEquals(ClockTrust.IMPLAUSIBLE, guard.read().trust)
        assertFalse(guard.confirm(), "a 2015 clock cannot be right")
        assertFalse(guard.accept(time("2015-06-01T12:00:00")))
        assertTrue(guard.accept(time("2026-10-01T12:00:00")))
        assertEquals(ClockReading(time("2026-10-01T12:00:00"), ClockTrust.TRUSTED, ClockSource.ADMIN), guard.read())
        // The suggestion for the stepper never starts before the floor.
        assertTrue(!guard.suggestedTime().isBefore(time("2026-09-01T01:00:00")))
    }

    @Test
    fun thePhoneCanSetTheTime() {
        zone = ZoneId.of("GMT")
        system = Instant.parse("2026-10-01T12:00:00Z")
        assertTrue(guard.acceptInstant(Instant.parse("2026-10-01T11:00:30Z"), ClockSource.PHONE))
        assertEquals(ClockReading(time("2026-10-01T12:00:30"), ClockTrust.TRUSTED, ClockSource.PHONE), guard.read())
    }

    @Test
    fun aZoneWithSummerTimeOnlyDiffersInSummer() {
        zone = ZoneId.of("Europe/Paris")
        assertTrue(guard.zoneDiffers()) // October 1st: UTC+2 against Tunisia's UTC+1
        assertEquals(ClockTrust.UNVERIFIED, guard.read().trust)
        system = Instant.parse("2026-12-01T11:00:00Z")
        restart()
        assertFalse(guard.zoneDiffers())
        assertEquals(ClockTrust.TRUSTED, guard.read().trust)
    }
}

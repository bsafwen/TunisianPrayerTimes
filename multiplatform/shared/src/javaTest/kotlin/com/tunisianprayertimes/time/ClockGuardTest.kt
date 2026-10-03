package com.tunisianprayertimes.time

import java.time.Duration
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
        override var deviceMark: DeviceMark? = null
        override var foreignZoneSeen = false
    }

    private val store = MemoryStore()
    private var system: Instant = Instant.parse("2026-10-01T11:00:00Z") // 12:00 in Tunis
    private var elapsed = 3_600_000L
    private var zone: ZoneId = TunisTime.ZONE
    private var boot = 7
    private var guard = newGuard()

    private fun newGuard() = ClockGuard(store, { system }, { elapsed }, { zone }, { "boot $boot" })

    private fun time(text: String) = LocalDateTime.parse(text)

    /** The box restarts: a new boot, and a new guard on the same memory. */
    private fun restart(elapsedAfterBoot: Long = 20_000) {
        elapsed = elapsedAfterBoot
        boot++
        guard = newGuard()
    }

    /** Time passes normally: the device clock and the time since boot move together. */
    private fun pass(seconds: Long) {
        system = system.plusSeconds(seconds)
        elapsed += seconds * 1000
    }

    /** The date a box without a clock battery starts from after a power cut. */
    private val firmware = Instant.parse("2024-05-01T00:00:00Z")

    /** The power comes back: the clock restarts at the firmware's date, and the app reads it [appAfter] seconds after boot. */
    private fun powerCut(appAfter: Long = 40) {
        system = firmware.plusSeconds(appAfter)
        restart(appAfter * 1000)
    }

    private val second = Duration.ofSeconds(1)

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
        assertTrue(guard.networkTime(system, second, authenticated = true))
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
        ClockGuard.systemClockChanged(store, Instant.parse("2026-10-01T11:00:00Z"), zone)
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
        guard.networkTime(Instant.parse("2026-10-01T11:00:00Z"), second, authenticated = true)
        assertEquals(ClockReading(time("2026-10-01T12:00:00"), ClockTrust.TRUSTED, ClockSource.NETWORK), guard.read())
        // A few seconds of difference later confirm it again without moving it.
        val correction = store.correctionMillis
        guard.networkTime(system.plusMillis(correction).plusSeconds(5), second, authenticated = true)
        assertEquals(correction, store.correctionMillis)
    }

    @Test
    fun theNetworkCorrectsADriftOfUnderTwoMinutes() {
        // Set by hand weeks ago, the box has drifted 1 min 50 s: the network moves it, not only confirms it.
        guard.read()
        val right = system.plusSeconds(110)
        assertTrue(guard.networkTime(right, second, authenticated = true))
        assertEquals(ClockReading(time("2026-10-01T12:01:50"), ClockTrust.TRUSTED, ClockSource.NETWORK), guard.read())
        // A slow answer knows the time less well: a difference within its own margin moves nothing.
        val correction = store.correctionMillis
        assertTrue(guard.networkTime(right.plusSeconds(12), Duration.ofSeconds(15), authenticated = true))
        assertEquals(correction, store.correctionMillis)
    }

    @Test
    fun theNetworkOverridesAnAdminsWrongAnswer() {
        zone = ZoneId.of("GMT")
        guard.accept(time("2026-10-01T15:00:00")) // a mistake: three hours ahead
        guard.networkTime(system, second, authenticated = true)
        assertEquals(ClockReading(time("2026-10-01T12:00:00"), ClockTrust.TRUSTED, ClockSource.NETWORK), guard.read())
    }

    @Test
    fun plainNetworkTimeNeverMovesOrConfirmsAPlausibleClock() {
        // Anyone on the mosque's network can answer NTP, 25 minutes off: not taken.
        zone = ZoneId.of("GMT")
        assertEquals(ClockTrust.UNVERIFIED, guard.read().trust)
        assertFalse(guard.networkTime(system.plusSeconds(25 * 60), second, authenticated = false))
        assertFalse(guard.networkTime(system, second, authenticated = false), "nor a time that agrees")
        assertEquals(ClockReading(time("2026-10-01T12:00:00"), ClockTrust.UNVERIFIED), guard.read())
        assertEquals(0L, store.correctionMillis)
    }

    @Test
    fun plainNetworkTimeSetsAClockThatCannotBeRight() {
        // Years off after a power cut, the box fails every HTTPS check on its dates: the system's network
        // time is all there is, and the wall shows no prayer times until the clock is right.
        guard.read()
        powerCut()
        assertEquals(ClockTrust.IMPLAUSIBLE, guard.read().trust)
        assertTrue(guard.networkTime(Instant.parse("2026-10-02T05:00:00Z"), second, authenticated = false))
        assertEquals(ClockReading(time("2026-10-02T06:00:00"), ClockTrust.TRUSTED, ClockSource.NETWORK), guard.read())
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
    fun aPowerCutAfterTheTimeWasSetSoonAfterBootIsNoticed() {
        guard.read()
        powerCut()
        assertEquals(ClockTrust.IMPLAUSIBLE, guard.read().trust)
        // Two minutes after boot the imam sets the time on the TV, and the box runs half an hour.
        pass(80)
        assertTrue(guard.accept(time("2026-10-01T20:00:00")))
        repeat(30) { pass(60); guard.read() }
        assertEquals(ClockReading(time("2026-10-01T20:30:00"), ClockTrust.TRUSTED, ClockSource.ADMIN), guard.read())
        // Another cut: the old correction would bring back 19:58 as confirmed. It was made on the old clock.
        powerCut()
        assertEquals(ClockTrust.IMPLAUSIBLE, guard.read().trust)
        assertEquals(null, store.confirmedBy)
        assertEquals(0L, store.correctionMillis)
    }

    @Test
    fun aPowerCutIsNoticedHoweverLateTheAppStartsAfterIt() {
        guard.read()
        powerCut()
        pass(80)
        guard.accept(time("2026-10-01T20:00:00"))
        repeat(30) { pass(60); guard.read() }
        // After the next cut the app is opened by hand an hour and a half after the box started: its clock
        // is past where it was, but the boot started before that.
        powerCut(appAfter = 90 * 60)
        assertEquals(ClockTrust.IMPLAUSIBLE, guard.read().trust)
        assertEquals(null, store.confirmedBy)
    }

    @Test
    fun theClockBatterysDriftAcrossARebootIsNotAReset() {
        // A box on GMT with a clock battery, answered by the admin, then on day and night for a month.
        zone = ZoneId.of("GMT")
        system = Instant.parse("2026-10-01T12:00:00Z")
        guard.accept(time("2026-10-01T12:00:00"))
        repeat(30 * 24) { pass(3600); guard.read() }
        // A quick restart: the system clock comes back from the clock battery's, two minutes behind after a month.
        system = system.minusSeconds(120 - 20)
        restart(20_000)
        assertEquals(ClockReading(time("2026-10-31T11:58:20"), ClockTrust.TRUSTED, ClockSource.ADMIN), guard.read())
    }

    @Test
    fun aRestartOfTheSystemAloneIsNotAReset() {
        // A box without a clock battery, set on the TV after a cut, runs through the evening.
        guard.read()
        powerCut()
        pass(80)
        guard.accept(time("2026-10-01T20:00:00"))
        repeat(3 * 60) { pass(60); guard.read() }
        // The system's server crashes and restarts, not the kernel: the same boot, its clock and time since boot go on.
        pass(30)
        guard = newGuard()
        assertEquals(ClockReading(time("2026-10-01T23:00:30"), ClockTrust.TRUSTED, ClockSource.ADMIN), guard.read())
    }

    @Test
    fun aClockSetBackOnPurposeIsAFixNotAReset() {
        zone = ZoneId.of("GMT")
        system = Instant.parse("2026-10-01T13:00:00Z")
        guard.read()
        // With the display off, the admin sets the box's clock two hours back in its settings.
        system = system.minusSeconds(2 * 3600)
        ClockGuard.systemClockChanged(store, system, zone)
        restart()
        assertEquals(ClockTrust.UNVERIFIED, guard.read().trust)
        // Then answers on the TV: after a reboot of a box with a clock battery, the answer still holds.
        assertTrue(guard.accept(time("2026-10-01T12:00:00")))
        pass(600)
        restart()
        assertEquals(ClockReading(time("2026-10-01T12:10:00"), ClockTrust.TRUSTED, ClockSource.ADMIN), guard.read())
    }

    @Test
    fun aConfirmationThatMakesTheTimeImpossibleIsDropped() {
        // Whatever the marks missed: a confirmed correction that now puts the time two days behind the last good time.
        store.lastKnownGoodMillis = Instant.parse("2026-10-03T17:00:00Z").toEpochMilli()
        store.confirmedBy = ClockSource.ADMIN.name
        store.correctionMillis = Duration.ofDays(-2).toMillis()
        val reading = guard.read()
        assertEquals(ClockTrust.IMPLAUSIBLE, reading.trust)
        assertEquals(null, store.confirmedBy)
        // The screen's time is the device clock again, and «هذا الوقت صحيح» confirms exactly that.
        assertEquals(time("2026-10-01T12:00:00"), reading.now)
        assertTrue(guard.confirm())
        assertEquals(ClockReading(time("2026-10-01T12:00:00"), ClockTrust.TRUSTED, ClockSource.ADMIN), guard.read())
    }

    @Test
    fun aZoneWithSummerTimeIsNotTrustedEvenWhenItAgrees() {
        // Early October, a box on Paris time (UTC+2 until the end of the month) set by hand to the mosque's 12:00.
        zone = ZoneId.of("Europe/Paris")
        system = Instant.parse("2026-10-01T10:00:00Z")
        assertTrue(guard.zoneDiffers())
        assertEquals(ClockReading(time("2026-10-01T11:00:00"), ClockTrust.UNVERIFIED), guard.read())
        // In December Paris reads Tunisia's time, but the clock is still an hour behind: not confirmed.
        system = Instant.parse("2026-12-01T10:00:00Z")
        restart()
        assertFalse(guard.zoneDiffers())
        assertEquals(ClockTrust.UNVERIFIED, guard.read().trust)
        // Why: Paris does not keep Tunisia's time all year, which the admin pages say.
        assertFalse(guard.zoneKeepsTunisTime())
        zone = ZoneId.of("Africa/Algiers")
        assertTrue(guard.zoneKeepsTunisTime())
    }

    @Test
    fun onlyAZoneThatKeepsTunisiasTimeAllYearVouchesForTheClock() {
        system = Instant.parse("2026-12-01T11:00:00Z")
        fun readOn(id: String) = ClockGuard(MemoryStore(), { system }, { elapsed }, { ZoneId.of(id) }).read()
        for (id in listOf("Africa/Tunis", "Africa/Algiers", "Africa/Lagos", "Etc/GMT-1")) {
            assertEquals(ClockSource.ZONE, readOn(id).source, id)
        }
        // Both read Tunisia's time in December; Casablanca leaves it in Ramadan, Paris in summer.
        for (id in listOf("Africa/Casablanca", "Europe/Paris")) {
            assertEquals(ClockTrust.UNVERIFIED, readOn(id).trust, id)
        }
    }

    @Test
    fun changingOnlyTheZoneDoesNotVouchForAClockSetInAnother() {
        // Set by hand to the owner's 12:00 on Shanghai time: seven hours off.
        zone = ZoneId.of("Asia/Shanghai")
        system = Instant.parse("2026-10-01T04:00:00Z")
        assertEquals(ClockTrust.UNVERIFIED, guard.read().trust)
        // Someone picks Tunisia's zone without setting the time again: still seven hours off, still asked.
        zone = TunisTime.ZONE
        pass(60)
        assertEquals(ClockReading(time("2026-10-01T05:01:00"), ClockTrust.UNVERIFIED), guard.read())
        // Set again on Tunisia's zone, the clock means Tunisia's time.
        system = Instant.parse("2026-10-01T11:01:00Z")
        ClockGuard.systemClockChanged(store, system, zone)
        restart()
        assertEquals(ClockReading(time("2026-10-01T12:01:00"), ClockTrust.TRUSTED, ClockSource.ZONE), guard.read())
    }

    @Test
    fun theDisplayTicksJustAfterEachSecondOfTheScreensTime() {
        fun at(millis: Int) = ClockReading(time("2026-10-01T12:00:00").withNano(millis * 1_000_000), ClockTrust.TRUSTED)
        // A correction ending in .990: the next second is 10 ms away, not where the device's own second falls.
        assertEquals(15L, at(990).millisToNextTick())
        assertEquals(505L, at(500).millisToNextTick())
        assertEquals(1_005L, at(0).millisToNextTick())
    }
}

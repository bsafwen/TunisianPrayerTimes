package com.tunisianprayertimes.tv.kiosk

import com.tunisianprayertimes.time.ClockSource
import java.time.Instant
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockChangeTest {

    private val set = 1_790_686_800_000L

    @After
    fun forget() = OwnClockSet.cancel()

    @Test
    fun theAppsOwnClockChangeIsRecognised() {
        assertTrue("at once", OwnClockSet.matches(set, 5_000, set, 5_000))
        assertTrue("delivered 2 s later", OwnClockSet.matches(set, 5_000, set + 2_000, 7_000))
        assertTrue("a few seconds of slack", OwnClockSet.matches(set, 5_000, set + 6_000, 7_000))
        assertFalse("someone set another time", OwnClockSet.matches(set, 5_000, set + 3_600_000, 7_000))
        assertFalse("too late to be ours", OwnClockSet.matches(set, 5_000, set + 61_000, 66_000))
        assertFalse("before it was set (a reboot since)", OwnClockSet.matches(set, 5_000, set, 4_000))
    }

    @Test
    fun anExpectedChangeIsConsumedOnce() {
        assertFalse("nothing expected", OwnClockSet.consume(set, 5_000))
        OwnClockSet.expect(set, 5_000)
        assertTrue(OwnClockSet.consume(set + 1_000, 6_000))
        assertFalse("a second change is someone else's", OwnClockSet.consume(set + 1_000, 6_000))
        OwnClockSet.expect(set, 5_000)
        assertFalse(OwnClockSet.consume(set - 7 * 3_600_000, 6_000))
        assertFalse("forgotten after a mismatch too", OwnClockSet.consume(set + 1_000, 6_000))
    }

    @Test
    fun theClockEventsSayWhatChanged() {
        val device = Instant.parse("2026-09-29T13:00:05.300Z")
        assertEquals("tunis=2026-09-29T14:00:05 zone=Asia/Shanghai autoTime=off", ClockLog.clockSet(device, ZoneId.of("Asia/Shanghai"), false, own = false))
        assertEquals("tunis=2026-09-29T14:00:05 zone=Africa/Tunis autoTime=? own", ClockLog.clockSet(device, ZoneId.of("Africa/Tunis"), null, own = true))
        assertEquals("zone=Europe/Paris tunisTime=false", ClockLog.zoneSet(ZoneId.of("Europe/Paris"), device))
        assertEquals("zone=Europe/Paris tunisTime=true", ClockLog.zoneSet(ZoneId.of("Europe/Paris"), Instant.parse("2026-12-01T12:00:00Z")))
        assertEquals("source=PHONE moved=+3600s", ClockLog.confirmed(ClockSource.PHONE, 3_600_000))
        assertEquals("source=NETWORK moved=-25200s", ClockLog.confirmed(ClockSource.NETWORK, -25_200_400))
        assertEquals("source=ADMIN", ClockLog.confirmed(ClockSource.ADMIN))
        assertEquals("under a second is no move", "source=NETWORK", ClockLog.confirmed(ClockSource.NETWORK, 800))
    }
}

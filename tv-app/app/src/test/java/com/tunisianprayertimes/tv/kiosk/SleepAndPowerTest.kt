package com.tunisianprayertimes.tv.kiosk

import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepAndPowerTest {

    @Test
    fun aLongSleepIsFoundWithItsWallTimes() {
        val slept = (3 * 60 + 40) * 60_000L
        val before = ClockSample(wallMillis = 1_000_000, elapsedMillis = 500_000, uptimeMillis = 400_000)
        val after = ClockSample(wallMillis = 1_000_000 + slept + 60_000, elapsedMillis = 500_000 + slept + 60_000, uptimeMillis = 460_000)
        assertEquals(SleepGap(1_000_000, 1_000_000 + slept + 60_000, slept), SleepGapDetector.compare(before, after))
    }

    @Test
    fun shortPausesAndRebootsAreNotSleeps() {
        val before = ClockSample(0, 100_000, 100_000)
        assertNull(SleepGapDetector.compare(before, ClockSample(90_000, 190_000, 160_000)))
        assertNull(SleepGapDetector.compare(before, ClockSample(90_000, 5_000, 5_000)))
    }

    private fun reader(attentive: () -> Long?, stayOn: () -> Int?) = object : PowerSettingsReader {
        override fun attentiveTimeoutMillis() = attentive()
        override fun stayOnWhilePluggedIn() = stayOn()
    }

    @Test
    fun powerSettingsAreJudgedAndUnreadableOnesAreUnknown() {
        val saver = PowerSettingsProbe.probe(reader({ 1_800_000L }, { 0 }))
        assertEquals(PowerLevel.WARNING, saver.attentiveTimeout)
        assertEquals(PowerLevel.WARNING, saver.stayAwake)
        assertEquals(PowerLevel.OK, PowerSettingsProbe.probe(reader({ -1L }, { 7 })).attentiveTimeout)
        assertEquals(PowerLevel.OK, PowerSettingsProbe.probe(reader({ 0L }, { 7 })).stayAwake)
        val refused = PowerSettingsProbe.probe(reader({ throw SecurityException() }, { null }))
        assertEquals(PowerLevel.UNKNOWN, refused.attentiveTimeout)
        assertEquals(PowerLevel.UNKNOWN, refused.stayAwake)
    }

    @Test
    fun anUnreadableEnergySaverWarnsWhereTheBoxMayHaveOne() {
        assertEquals(PowerLevel.WARNING, PowerSettingsProbe.probe(reader({ null }, { 7 }), energySaver = true).attentiveTimeout)
        assertEquals(PowerLevel.UNKNOWN, PowerSettingsProbe.probe(reader({ null }, { 7 }), energySaver = false).attentiveTimeout)
        assertEquals(PowerLevel.OK, PowerSettingsProbe.probe(reader({ -1L }, { 7 }), energySaver = true).attentiveTimeout)
    }

    @Test
    fun aSleepGapIsLoggedAsItsRawTimes() {
        val gap = SleepGap(1_000L, 3_601_000L, 3_600_000L)
        assertEquals(gap, SleepGap.parse(gap.detail))
        assertNull(SleepGap.parse("09-29 03:00 → 09-29 04:00 (60 min)"))
        assertNull(SleepGap.parse("1 2"))
    }

    @Test
    fun theScreenDriftsSlowlyAndStaysClose() {
        val week = 7L * 24 * 60
        val start = 29_000_000L
        for (minute in start until start + week) {
            val (x, y) = PixelShift.offsetAt(minute)
            assertTrue(hypot(x, y) <= 8f)
        }
        // The same offset through a 6-minute slot, a new one at the next slot.
        val slotStart = start - start % PixelShift.SLOT_MINUTES
        assertEquals(PixelShift.offsetAt(slotStart), PixelShift.offsetAt(slotStart + 5))
        assertNotEquals(PixelShift.offsetAt(slotStart), PixelShift.offsetAt(slotStart + 6))
        // All eight positions within 48 minutes.
        assertEquals(8, (0L until 48).map { PixelShift.offsetAt(slotStart + it) }.toSet().size)
    }
}

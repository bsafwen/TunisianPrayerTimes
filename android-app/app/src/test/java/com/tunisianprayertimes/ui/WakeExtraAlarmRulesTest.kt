package com.tunisianprayertimes.ui

import com.tunisianprayertimes.OffsetDirection
import com.tunisianprayertimes.PrayerWakeSubAlarm
import org.junit.Assert.assertEquals
import org.junit.Test

class WakeExtraAlarmRulesTest {
    private fun alarm(minutes: Int, direction: OffsetDirection = OffsetDirection.BEFORE) =
        PrayerWakeSubAlarm(id = "$direction-$minutes", minutesOffset = minutes, direction = direction)

    @Test
    fun newAlarm_startsFiveMinutesFromTheMainAlarm() {
        assertEquals(5, nextExtraAlarmOffsetMinutes(emptyList(), OffsetDirection.BEFORE))
        assertEquals(5, nextExtraAlarmOffsetMinutes(emptyList(), OffsetDirection.AFTER))
    }

    @Test
    fun newAlarm_ignoresTheOtherSide() {
        val existing = listOf(alarm(5, OffsetDirection.AFTER), alarm(10, OffsetDirection.AFTER))
        assertEquals(5, nextExtraAlarmOffsetMinutes(existing, OffsetDirection.BEFORE))
    }

    @Test
    fun newAlarm_takesTheNearestFreeFiveMinuteSlot() {
        assertEquals(10, nextExtraAlarmOffsetMinutes(listOf(alarm(5)), OffsetDirection.BEFORE))
        assertEquals(5, nextExtraAlarmOffsetMinutes(listOf(alarm(10)), OffsetDirection.BEFORE))
        assertEquals(15, nextExtraAlarmOffsetMinutes(listOf(alarm(5), alarm(10)), OffsetDirection.BEFORE))
        // An alarm dragged off the grid doesn't block the slot next to it.
        assertEquals(5, nextExtraAlarmOffsetMinutes(listOf(alarm(7)), OffsetDirection.BEFORE))
    }

    @Test
    fun timelineSpan_isTheSmallestStandardSpanThatShowsEveryAlarm() {
        assertEquals(20, extraAlarmTimelineSpanMinutes(emptyList()))
        assertEquals(20, extraAlarmTimelineSpanMinutes(listOf(alarm(5), alarm(20, OffsetDirection.AFTER))))
        assertEquals(30, extraAlarmTimelineSpanMinutes(listOf(alarm(21))))
        assertEquals(60, extraAlarmTimelineSpanMinutes(listOf(alarm(5), alarm(46, OffsetDirection.AFTER))))
        assertEquals(180, extraAlarmTimelineSpanMinutes(listOf(alarm(121))))
        assertEquals(180, extraAlarmTimelineSpanMinutes(listOf(alarm(180))))
    }

    @Test
    fun dragStep_isOneMinuteWhileZoomedIn() {
        // 176dp of track around the main alarm on a 360dp phone: 88dp per side.
        assertEquals(1, extraAlarmDragStepMinutes(88f / 20))
        assertEquals(1, extraAlarmDragStepMinutes(88f / 30))
        assertEquals(1, extraAlarmDragStepMinutes(2f))
    }

    @Test
    fun dragStep_coarsensOnceAMinuteIsTooNarrowToAimAt() {
        assertEquals(5, extraAlarmDragStepMinutes(88f / 45))
        assertEquals(5, extraAlarmDragStepMinutes(88f / 60))
        assertEquals(5, extraAlarmDragStepMinutes(88f / 180))
        assertEquals(10, extraAlarmDragStepMinutes(0.3f))
        assertEquals(15, extraAlarmDragStepMinutes(0.15f))
        assertEquals(15, extraAlarmDragStepMinutes(0.01f))
    }

    @Test
    fun snap_staysOnItsSideOfTheMainAlarmAndInsideTheSpan() {
        assertEquals(1, snapExtraAlarmOffset(rawMinutes = -4f, stepMinutes = 1, spanMinutes = 20))
        assertEquals(1, snapExtraAlarmOffset(rawMinutes = 0.2f, stepMinutes = 1, spanMinutes = 20))
        assertEquals(7, snapExtraAlarmOffset(rawMinutes = 7.4f, stepMinutes = 1, spanMinutes = 20))
        assertEquals(20, snapExtraAlarmOffset(rawMinutes = 26f, stepMinutes = 1, spanMinutes = 20))
    }

    @Test
    fun snap_followsTheCoarseGrid() {
        assertEquals(5, snapExtraAlarmOffset(rawMinutes = 1f, stepMinutes = 5, spanMinutes = 60))
        assertEquals(35, snapExtraAlarmOffset(rawMinutes = 37f, stepMinutes = 5, spanMinutes = 60))
        assertEquals(40, snapExtraAlarmOffset(rawMinutes = 38f, stepMinutes = 5, spanMinutes = 60))
        // A span grown minute by minute isn't on the grid; its end is still reachable.
        assertEquals(47, snapExtraAlarmOffset(rawMinutes = 49f, stepMinutes = 5, spanMinutes = 47))
    }

    @Test
    fun edgeHold_startsWithSingleMinutesThenSpeedsUp() {
        val offsets = generateSequence(20 to 0) { (minutes, tick) ->
            nextExtraAlarmEdgeOffset(minutes, tick) to tick + 1
        }.map { (minutes, _) -> minutes }.take(22).toList()

        assertEquals(
            listOf(20, 21, 22, 23, 24, 25, 30, 35, 40, 45, 50, 55, 60, 65, 75, 90, 105, 120, 135, 150, 165, 180),
            offsets,
        )
    }

    @Test
    fun edgeHold_stopsAtTheLargestOffset() {
        assertEquals(180, nextExtraAlarmEdgeOffset(minutes = 180, tick = 0))
        assertEquals(180, nextExtraAlarmEdgeOffset(minutes = 170, tick = 20))
    }

    @Test
    fun edgeHold_neverMovesLessThanTheDragStepOfItsSpan() {
        assertEquals(65, nextExtraAlarmEdgeOffset(minutes = 60, tick = 0, minStepMinutes = 5))
        assertEquals(50, nextExtraAlarmEdgeOffset(minutes = 47, tick = 0, minStepMinutes = 5))
    }
}

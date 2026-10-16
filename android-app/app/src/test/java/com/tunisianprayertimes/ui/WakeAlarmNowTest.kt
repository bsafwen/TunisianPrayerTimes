package com.tunisianprayertimes.ui

import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.WakeAlarmComputer
import com.tunisianprayertimes.WakeMainAlarmMode
import com.tunisianprayertimes.WakePlaybackOptions
import com.tunisianprayertimes.wake.WakeAlarmScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeAlarmNowTest {
    private val minute = 60_000L

    // Today's occurrence: main 04:55, extras 04:50, 05:00, 05:07 (the owner's Fajr alarm), as minutes from midnight.
    private val todayMain = 295 * minute
    private val tomorrowMain = todayMain + 24 * 60 * minute

    private fun trigger(atMillis: Long, occurrenceAtMillis: Long, isExtra: Boolean) =
        WakeAlarmComputer.ScheduledWakeTrigger(
            alarmId = "fajr",
            triggerAtMillis = atMillis,
            prayer = Prayer.FAJR,
            effectivePrayer = Prayer.FAJR,
            mainAlarmMode = WakeMainAlarmMode.FIXED_TIME,
            isSubAlarm = isExtra,
            subAlarmId = if (isExtra) "extra" else null,
            signedOffsetMinutes = ((atMillis - occurrenceAtMillis) / minute).toInt(),
            playback = WakePlaybackOptions(),
            occurrenceAtMillis = occurrenceAtMillis,
        )

    @Test
    fun noOccurrenceInProgress_showsTheNextRing() {
        val next = trigger(tomorrowMain - 5 * minute, tomorrowMain, isExtra = true)

        val now = wakeAlarmNow(next, running = null)!!

        assertFalse(now.inProgress)
        assertEquals(tomorrowMain, now.occurrenceAtMillis)
        assertEquals(WakeAlarmFocus.RING, now.focus)
        assertEquals(WakeAlarmScheduler.Ring(tomorrowMain - 5 * minute, isExtra = true), now.highlightedRing)
    }

    @Test
    fun checkPendingAfterTheLastRing_staysOnTodayAndLeadsWithTheCheck() {
        // 05:10: every ring stopped at 05:09:46, the check is due at 05:14:46, the clock's next ring is tomorrow.
        val checkAt = todayMain + 19 * minute + 46_000L
        val now = wakeAlarmNow(
            nextTrigger = trigger(tomorrowMain - 5 * minute, tomorrowMain, isExtra = true),
            running = WakeAlarmScheduler.RunningOccurrence(
                occurrenceAtMillis = todayMain,
                awakeCheckAtMillis = checkAt,
                awakeCheckFromMillis = checkAt - 5 * minute,
            ),
        )!!

        assertTrue(now.inProgress)
        assertEquals(todayMain, now.occurrenceAtMillis)
        assertNull(now.nextRing)
        assertEquals(WakeAlarmFocus.AWAKE_CHECK, now.focus)
        assertNull(now.highlightedRing)
        assertEquals(checkAt, now.focusAtMillis(todayMain + 15 * minute))
    }

    @Test
    fun lateCheck_keepsItsTimeAndComesFirst() {
        val checkAt = todayMain + 20 * minute
        val now = wakeAlarmNow(
            nextTrigger = null,
            running = WakeAlarmScheduler.RunningOccurrence(occurrenceAtMillis = todayMain, awakeCheckAtMillis = checkAt),
        )!!

        val clock = checkAt + 90_000L
        assertEquals(WakeAlarmFocus.AWAKE_CHECK, now.focus)
        assertEquals(clock, now.focusAtMillis(clock))
    }

    @Test
    fun ringGoing_outranksAPendingCheckAndOtherAlarms() {
        val clock = todayMain + 2 * minute
        val now = wakeAlarmNow(
            nextTrigger = trigger(todayMain + 5 * minute, todayMain, isExtra = true),
            running = WakeAlarmScheduler.RunningOccurrence(
                occurrenceAtMillis = todayMain,
                ringing = WakeAlarmScheduler.Ring(todayMain - 5 * minute, isExtra = true),
                awakeCheckAtMillis = clock + minute,
            ),
        )!!

        assertEquals(WakeAlarmFocus.RINGING, now.focus)
        assertEquals(WakeAlarmScheduler.Ring(todayMain - 5 * minute, isExtra = true), now.highlightedRing)
        assertTrue(now.focusAtMillis(clock) < clock)
    }

    @Test
    fun ringWaitingBehindAnotherAlarm_comesAfterTheRingGoing() {
        val clock = todayMain
        val waiting = wakeAlarmNow(
            nextTrigger = null,
            running = WakeAlarmScheduler.RunningOccurrence(
                occurrenceAtMillis = todayMain,
                ringing = WakeAlarmScheduler.Ring(todayMain, isExtra = false),
                ringWaiting = true,
            ),
        )!!
        val going = wakeAlarmNow(
            nextTrigger = null,
            running = WakeAlarmScheduler.RunningOccurrence(
                occurrenceAtMillis = todayMain,
                ringing = WakeAlarmScheduler.Ring(todayMain, isExtra = false),
            ),
        )!!

        assertEquals(WakeAlarmFocus.RINGING, waiting.focus)
        assertTrue(going.focusAtMillis(clock) < waiting.focusAtMillis(clock))
    }

    @Test
    fun lateRing_isTheNextRingOfTheOccurrence() {
        // 05:08: the 05:07 extra has not been delivered yet; the clock's next ring is tomorrow.
        val late = WakeAlarmScheduler.Ring(todayMain + 12 * minute, isExtra = true)
        val now = wakeAlarmNow(
            nextTrigger = trigger(tomorrowMain - 5 * minute, tomorrowMain, isExtra = true),
            running = WakeAlarmScheduler.RunningOccurrence(occurrenceAtMillis = todayMain, dueRing = late),
        )!!

        val clock = todayMain + 13 * minute
        assertEquals(late, now.nextRing)
        assertEquals(WakeAlarmFocus.RING, now.focus)
        assertEquals(clock, now.focusAtMillis(clock))
    }

    @Test
    fun editMidOccurrence_keepsPastRingsAndTakesTheNewOnesAhead() {
        val now = WakeAlarmNow(
            occurrenceAtMillis = todayMain,
            nextRing = null,
            inProgress = true,
            recordedRingOffsets = listOf(-5, 0, 5, 12),
        )

        // At 05:01 the alarm now has extras -5 and +3 only: 05:00 rang, 05:07 will not, nothing new lies ahead.
        assertEquals(listOf(-5, 0, 5), now.occurrenceRingOffsets(listOf(-5, 0, 3), todayMain + 6 * minute))
        // Not in progress: the alarm's own rings.
        assertEquals(listOf(-5, 0, 3), now.copy(inProgress = false).occurrenceRingOffsets(listOf(-5, 0, 3), 0L))
    }

    @Test
    fun timelineWithACheck_marksEveryRingRungAndTheCheckNext() {
        val checkAt = todayMain + 19 * minute + 46_000L
        val stops = wakeTimelineStops(
            offsets = listOf(-5, 0, 5, 12),
            occurrenceAtMillis = todayMain,
            highlightedAtMillis = null,
            awakeCheck = WakeTimelineAwakeCheck(checkAt, fromMillis = checkAt - 5 * minute, running = true, isNext = true),
        )

        assertEquals(5, stops.size)
        assertTrue(stops.dropLast(1).all { stop -> stop.state == WakeStopState.RUNG && !stop.isAwakeCheck })
        val check = stops.last()
        assertTrue(check.isAwakeCheck)
        assertFalse(check.isMain)
        assertTrue(check.ongoing)
        assertEquals(WakeStopState.NEXT, check.state)
        assertEquals(checkAt - 5 * minute, check.progressFromMillis)
    }

    @Test
    fun timelineWithARingGoing_marksItOngoingAndTheQueuedOnesUpcoming() {
        val stops = wakeTimelineStops(
            offsets = listOf(-5, 0, 5, 12),
            occurrenceAtMillis = todayMain,
            highlightedAtMillis = todayMain,
            highlightedOngoing = true,
        )

        assertEquals(
            listOf(WakeStopState.RUNG, WakeStopState.NEXT, WakeStopState.UPCOMING, WakeStopState.UPCOMING),
            stops.map { stop -> stop.state },
        )
        assertEquals(listOf(false, true, false, false), stops.map { stop -> stop.ongoing })
    }

    @Test
    fun clockCountdown_agreesWithTheTimeShown() {
        // Due at 05:14:46, shown as 05:14: four minutes after 05:10, not five.
        assertEquals(4 * minute, wakeClockCountdownMillis(todayMain + 19 * minute + 46_000L, todayMain + 15 * minute))
        // A ring on a whole minute counts as before: 04:55 is three minutes after 04:52:30.
        assertEquals(3 * minute, wakeClockCountdownMillis(todayMain, todayMain - 2 * minute - 30_000L))
        // Within the same minute it is less than a minute away.
        assertEquals(0L, wakeClockCountdownMillis(todayMain + 46_000L, todayMain + 10_000L))
    }
}

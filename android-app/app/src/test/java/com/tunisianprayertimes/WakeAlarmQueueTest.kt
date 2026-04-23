package com.tunisianprayertimes

import android.os.Bundle
import com.tunisianprayertimes.wake.WakeAlarmQueue
import com.tunisianprayertimes.wake.WakeTriggerPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exhaustive tests for [WakeAlarmQueue] — the core queuing engine that
 * manages alarm payloads when multiple alarms overlap.
 *
 * Coverage targets every branch in:
 * - [WakeAlarmQueue.handleIncoming]
 * - [WakeAlarmQueue.advance]
 * - [WakeAlarmQueue.handleDismiss]
 * - [WakeAlarmQueue.saveToBundle] / [WakeAlarmQueue.restoreFromBundle]
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class WakeAlarmQueueTest {

    private lateinit var queue: WakeAlarmQueue

    @Before
    fun setUp() {
        queue = WakeAlarmQueue()
    }

    // ──────────────────────────────────────────────────────────────
    // Helpers — minimal payloads for test clarity
    // ──────────────────────────────────────────────────────────────

    private fun payload(
        eventId: String,
        wakeUpCheckEnabled: Boolean = true,
    ) = WakeTriggerPayload(
        eventId = eventId,
        prayer = Prayer.FAJR,
        effectivePrayer = Prayer.FAJR,
        hour = 5,
        minute = 0,
        ringtone = RingtonePreset.DEFAULT_ALARM,
        vibrationOnly = false,
        wakeUpCheckEnabled = wakeUpCheckEnabled,
        progressiveVolume = false,
        snoreTrackingEnabled = false,
        awakeCheckEnabled = false,
        isSubAlarm = false,
    )

    // ──────────────────────────────────────────────────────────────
    // Initial state
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `initial state has null current and empty queue`() {
        assertNull(queue.current)
        assertEquals(0, queue.pendingCount)
    }

    // ──────────────────────────────────────────────────────────────
    // handleIncoming — no current payload
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `incoming payload becomes current when queue is empty`() {
        val p = payload("main")
        assertTrue(queue.handleIncoming(p))
        assertEquals("main", queue.current?.eventId)
        assertEquals(0, queue.pendingCount)
    }

    // ──────────────────────────────────────────────────────────────
    // handleIncoming — current has NO challenge (wakeUpCheckEnabled=false)
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `incoming replaces current when current has no challenge`() {
        queue.handleIncoming(payload("old", wakeUpCheckEnabled = false))
        val newP = payload("new")
        assertTrue(queue.handleIncoming(newP))
        assertEquals("new", queue.current?.eventId)
        assertEquals(0, queue.pendingCount)
    }

    @Test
    fun `incoming replaces current even with same eventId when no challenge`() {
        queue.handleIncoming(payload("same", wakeUpCheckEnabled = false))
        assertTrue(queue.handleIncoming(payload("same")))
        assertEquals("same", queue.current?.eventId)
    }

    // ──────────────────────────────────────────────────────────────
    // handleIncoming — current HAS challenge (wakeUpCheckEnabled=true)
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `incoming with different eventId is queued when challenge active`() {
        queue.handleIncoming(payload("main", wakeUpCheckEnabled = true))
        assertTrue(queue.handleIncoming(payload("sub1")))
        assertEquals("main", queue.current?.eventId)
        assertEquals(1, queue.pendingCount)
    }

    @Test
    fun `duplicate of current eventId is rejected when challenge active`() {
        queue.handleIncoming(payload("main", wakeUpCheckEnabled = true))
        assertFalse(queue.handleIncoming(payload("main")))
        assertEquals(0, queue.pendingCount)
    }

    @Test
    fun `duplicate of queued eventId is rejected`() {
        queue.handleIncoming(payload("main", wakeUpCheckEnabled = true))
        queue.handleIncoming(payload("sub1"))
        assertFalse(queue.handleIncoming(payload("sub1")))
        assertEquals(1, queue.pendingCount)
    }

    @Test
    fun `triple delivery of same main alarm is rejected both times`() {
        queue.handleIncoming(payload("main", wakeUpCheckEnabled = true))
        assertFalse(queue.handleIncoming(payload("main")))
        assertFalse(queue.handleIncoming(payload("main")))
        assertEquals(0, queue.pendingCount)
    }

    @Test
    fun `multiple different sub-alarms are queued in order`() {
        queue.handleIncoming(payload("main", wakeUpCheckEnabled = true))
        assertTrue(queue.handleIncoming(payload("sub1")))
        assertTrue(queue.handleIncoming(payload("sub2")))
        assertTrue(queue.handleIncoming(payload("sub3")))
        assertEquals(3, queue.pendingCount)
        assertEquals(listOf("sub1", "sub2", "sub3"), queue.pendingEventIds())
    }

    // ──────────────────────────────────────────────────────────────
    // advance
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `advance returns false when queue is empty`() {
        assertFalse(queue.advance())
    }

    @Test
    fun `advance returns false when only current exists`() {
        queue.handleIncoming(payload("main"))
        assertFalse(queue.advance())
        assertEquals("main", queue.current?.eventId)
    }

    @Test
    fun `advance promotes first queued to current`() {
        queue.handleIncoming(payload("main", wakeUpCheckEnabled = true))
        queue.handleIncoming(payload("sub1"))
        queue.handleIncoming(payload("sub2"))

        assertTrue(queue.advance())
        assertEquals("sub1", queue.current?.eventId)
        assertEquals(1, queue.pendingCount)
    }

    @Test
    fun `advance through entire queue in FIFO order`() {
        queue.handleIncoming(payload("main", wakeUpCheckEnabled = true))
        queue.handleIncoming(payload("sub1", wakeUpCheckEnabled = true))
        queue.handleIncoming(payload("sub2", wakeUpCheckEnabled = true))
        queue.handleIncoming(payload("sub3"))

        assertTrue(queue.advance())
        assertEquals("sub1", queue.current?.eventId)
        assertTrue(queue.advance())
        assertEquals("sub2", queue.current?.eventId)
        assertTrue(queue.advance())
        assertEquals("sub3", queue.current?.eventId)
        assertFalse(queue.advance())
        assertEquals("sub3", queue.current?.eventId)
    }

    @Test
    fun `advance empties queue and current remains as last promoted`() {
        queue.handleIncoming(payload("main", wakeUpCheckEnabled = true))
        queue.handleIncoming(payload("sub1"))
        queue.advance()
        assertEquals(0, queue.pendingCount)
        assertEquals("sub1", queue.current?.eventId)
    }

    // ──────────────────────────────────────────────────────────────
    // handleDismiss
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `dismiss with null eventId advances queue`() {
        queue.handleIncoming(payload("main", wakeUpCheckEnabled = true))
        queue.handleIncoming(payload("sub1"))
        assertTrue(queue.handleDismiss(null))
        assertEquals("sub1", queue.current?.eventId)
    }

    @Test
    fun `dismiss with matching eventId advances queue`() {
        queue.handleIncoming(payload("main", wakeUpCheckEnabled = true))
        queue.handleIncoming(payload("sub1"))
        assertTrue(queue.handleDismiss("main"))
        assertEquals("sub1", queue.current?.eventId)
    }

    @Test
    fun `dismiss with non-matching eventId does not advance`() {
        queue.handleIncoming(payload("main", wakeUpCheckEnabled = true))
        queue.handleIncoming(payload("sub1"))
        assertTrue(queue.handleDismiss("unknown"))
        // Still on main, didn't advance
        assertEquals("main", queue.current?.eventId)
        assertEquals(1, queue.pendingCount)
    }

    @Test
    fun `dismiss returns false when queue is empty and eventId matches`() {
        queue.handleIncoming(payload("main"))
        assertFalse(queue.handleDismiss("main"))
    }

    @Test
    fun `dismiss returns false when queue is empty and eventId is null`() {
        queue.handleIncoming(payload("main"))
        assertFalse(queue.handleDismiss(null))
    }

    @Test
    fun `dismiss with null current returns false`() {
        assertFalse(queue.handleDismiss(null))
    }

    @Test
    fun `dismiss with null current and specific eventId returns true`() {
        // eventId doesn't match null current, so we don't try to advance
        assertTrue(queue.handleDismiss("unknown"))
    }

    @Test
    fun `sequential dismissals drain queue correctly`() {
        queue.handleIncoming(payload("main", wakeUpCheckEnabled = true))
        queue.handleIncoming(payload("sub1", wakeUpCheckEnabled = true))
        queue.handleIncoming(payload("sub2"))

        assertTrue(queue.handleDismiss("main"))
        assertEquals("sub1", queue.current?.eventId)
        assertTrue(queue.handleDismiss("sub1"))
        assertEquals("sub2", queue.current?.eventId)
        assertFalse(queue.handleDismiss("sub2"))
    }

    // ──────────────────────────────────────────────────────────────
    // onStop callback simulation (advance on stop)
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `onStop with queued alarm keeps service alive`() {
        queue.handleIncoming(payload("main", wakeUpCheckEnabled = true))
        queue.handleIncoming(payload("sub1"))
        val shouldKeepAlive = queue.advance()
        assertTrue(shouldKeepAlive)
        assertEquals("sub1", queue.current?.eventId)
    }

    @Test
    fun `onStop with empty queue signals finish`() {
        queue.handleIncoming(payload("main"))
        val shouldKeepAlive = queue.advance()
        assertFalse(shouldKeepAlive)
    }

    // ──────────────────────────────────────────────────────────────
    // Interaction: handleIncoming AFTER advance
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `can queue new alarm after advancing`() {
        queue.handleIncoming(payload("main", wakeUpCheckEnabled = true))
        queue.handleIncoming(payload("sub1", wakeUpCheckEnabled = true))
        queue.advance()
        // Now sub1 is current with challenge active
        assertTrue(queue.handleIncoming(payload("sub2")))
        assertEquals(1, queue.pendingCount)
    }

    @Test
    fun `previously current eventId can be re-queued after advancing`() {
        queue.handleIncoming(payload("main", wakeUpCheckEnabled = true))
        queue.handleIncoming(payload("sub1", wakeUpCheckEnabled = true))
        queue.advance()
        // "main" was evicted from current; now sub1 is current
        // A fresh delivery of "main" should be accepted
        assertTrue(queue.handleIncoming(payload("main")))
        assertEquals(listOf("main"), queue.pendingEventIds())
    }

    // ──────────────────────────────────────────────────────────────
    // Mixed challenge/no-challenge payloads
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `no-challenge payload replaced by challenge payload`() {
        queue.handleIncoming(payload("nocheck", wakeUpCheckEnabled = false))
        assertTrue(queue.handleIncoming(payload("withcheck", wakeUpCheckEnabled = true)))
        assertEquals("withcheck", queue.current?.eventId)
        assertEquals(0, queue.pendingCount)
    }

    @Test
    fun `no-challenge current is always replaced regardless of eventId`() {
        queue.handleIncoming(payload("a", wakeUpCheckEnabled = false))
        assertTrue(queue.handleIncoming(payload("b", wakeUpCheckEnabled = false)))
        assertEquals("b", queue.current?.eventId)
        assertTrue(queue.handleIncoming(payload("c")))
        assertEquals("c", queue.current?.eventId)
    }

    @Test
    fun `queued no-challenge alarm is replaced when it becomes current`() {
        // Main has challenge, sub has no challenge
        val main = payload("main", wakeUpCheckEnabled = true)
        val sub = payload("sub", wakeUpCheckEnabled = false)
        queue.handleIncoming(main)
        queue.handleIncoming(sub)
        queue.advance()
        assertEquals("sub", queue.current?.eventId)
        // Since sub has no challenge, a new incoming should replace it
        assertTrue(queue.handleIncoming(payload("new")))
        assertEquals("new", queue.current?.eventId)
    }

    // ──────────────────────────────────────────────────────────────
    // Real-world scenario: main alarm double-delivery bug
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `fullScreenIntent + startActivity double delivery is deduped`() {
        // Simulates: fullScreenIntent fires -> onCreate sets payload
        val main = payload("wake:main:fajr_1", wakeUpCheckEnabled = true)
        queue.handleIncoming(main) // from fullScreenIntent/onCreate
        assertEquals("wake:main:fajr_1", queue.current?.eventId)

        // Then startActivity fires -> onNewIntent with same payload
        assertFalse(queue.handleIncoming(payload("wake:main:fajr_1")))
        assertEquals(0, queue.pendingCount)

        // Sub-alarm arrives later
        assertTrue(queue.handleIncoming(payload("wake:sub:fajr_1:sub1")))
        assertEquals(1, queue.pendingCount)

        // User solves main -> advances to sub
        assertTrue(queue.advance())
        assertEquals("wake:sub:fajr_1:sub1", queue.current?.eventId)

        // User solves sub -> no more
        assertFalse(queue.advance())
    }

    // ──────────────────────────────────────────────────────────────
    // Real-world scenario: HOME button bypass (activity destruction)
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `save and restore preserves current and pending payloads`() {
        val main = payload("main", wakeUpCheckEnabled = true)
        val sub1 = payload("sub1")
        val sub2 = payload("sub2")
        queue.handleIncoming(main)
        queue.handleIncoming(sub1)
        queue.handleIncoming(sub2)

        // Simulate activity destruction
        val bundle = Bundle()
        queue.saveToBundle(bundle)

        // Simulate activity re-creation
        val restored = WakeAlarmQueue()
        restored.restoreFromBundle(bundle)

        assertEquals("main", restored.current?.eventId)
        assertTrue(restored.current!!.wakeUpCheckEnabled)
        assertEquals(2, restored.pendingCount)
        assertEquals(listOf("sub1", "sub2"), restored.pendingEventIds())
    }

    @Test
    fun `restore with empty bundle leaves queue empty`() {
        val restored = WakeAlarmQueue()
        restored.restoreFromBundle(Bundle())
        assertNull(restored.current)
        assertEquals(0, restored.pendingCount)
    }

    @Test
    fun `save empty queue produces restorable bundle`() {
        val bundle = Bundle()
        queue.saveToBundle(bundle)
        val restored = WakeAlarmQueue()
        restored.restoreFromBundle(bundle)
        assertNull(restored.current)
        assertEquals(0, restored.pendingCount)
    }

    @Test
    fun `restore then handleIncoming deduplicates intent payload`() {
        // Scenario: activity has main+sub queued, gets destroyed.
        // System recreates with the sub-alarm intent still set.
        val main = payload("main", wakeUpCheckEnabled = true)
        val sub = payload("sub")
        queue.handleIncoming(main)
        queue.handleIncoming(sub)

        val bundle = Bundle()
        queue.saveToBundle(bundle)

        val restored = WakeAlarmQueue()
        restored.restoreFromBundle(bundle)
        // Now the intent delivers "sub" again (setIntent from onNewIntent)
        assertFalse(restored.handleIncoming(payload("sub")))
        assertEquals(1, restored.pendingCount) // still just 1
    }

    @Test
    fun `restore then handleIncoming with new sub-alarm adds to queue`() {
        val main = payload("main", wakeUpCheckEnabled = true)
        queue.handleIncoming(main)

        val bundle = Bundle()
        queue.saveToBundle(bundle)

        val restored = WakeAlarmQueue()
        restored.restoreFromBundle(bundle)
        assertTrue(restored.handleIncoming(payload("newsub")))
        assertEquals(1, restored.pendingCount)
    }

    @Test
    fun `full HOME bypass scenario plays out correctly`() {
        // 1. Main alarm with hamster challenge starts
        queue.handleIncoming(payload("main", wakeUpCheckEnabled = true))
        // 2. Sub-alarm with gyroscope arrives, queued
        queue.handleIncoming(payload("sub", wakeUpCheckEnabled = true))
        assertEquals(1, queue.pendingCount)

        // 3. User presses HOME -> activity destroyed -> save
        val bundle = Bundle()
        queue.saveToBundle(bundle)

        // 4. System recreates activity with sub intent
        val restored = WakeAlarmQueue()
        restored.restoreFromBundle(bundle)
        // Intent still carries "sub" payload
        assertFalse(restored.handleIncoming(payload("sub")))

        // 5. Main challenge is still current
        assertEquals("main", restored.current?.eventId)
        assertEquals(1, restored.pendingCount)

        // 6. User solves main -> sub appears
        assertTrue(restored.advance())
        assertEquals("sub", restored.current?.eventId)

        // 7. User solves sub -> done
        assertFalse(restored.advance())
    }

    // ──────────────────────────────────────────────────────────────
    // Edge cases
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `advance does not change current when queue is empty`() {
        queue.handleIncoming(payload("main"))
        queue.advance() // returns false
        assertEquals("main", queue.current?.eventId)
    }

    @Test
    fun `handleIncoming on pristine queue works`() {
        assertTrue(queue.handleIncoming(payload("first")))
        assertEquals("first", queue.current?.eventId)
    }

    @Test
    fun `pendingEventIds is empty initially`() {
        assertEquals(emptyList<String>(), queue.pendingEventIds())
    }

    @Test
    fun `pendingEventIds reflects additions and removals`() {
        queue.handleIncoming(payload("main", wakeUpCheckEnabled = true))
        queue.handleIncoming(payload("a"))
        queue.handleIncoming(payload("b"))
        assertEquals(listOf("a", "b"), queue.pendingEventIds())

        queue.advance()
        assertEquals(listOf("b"), queue.pendingEventIds())

        queue.advance()
        assertEquals(emptyList<String>(), queue.pendingEventIds())
    }

    @Test
    fun `five alarms queued and drained`() {
        queue.handleIncoming(payload("m", wakeUpCheckEnabled = true))
        for (i in 1..5) queue.handleIncoming(payload("s$i", wakeUpCheckEnabled = true))
        assertEquals(5, queue.pendingCount)

        for (i in 1..5) {
            assertTrue(queue.advance())
            assertEquals("s$i", queue.current?.eventId)
        }
        assertFalse(queue.advance())
    }

    @Test
    fun `interleaved incoming and advance operations`() {
        queue.handleIncoming(payload("main", wakeUpCheckEnabled = true))
        queue.handleIncoming(payload("sub1", wakeUpCheckEnabled = true))

        // Advance to sub1
        assertTrue(queue.advance())
        assertEquals("sub1", queue.current?.eventId)

        // New sub2 arrives while solving sub1
        assertTrue(queue.handleIncoming(payload("sub2", wakeUpCheckEnabled = true)))
        assertEquals(1, queue.pendingCount)

        // Advance to sub2
        assertTrue(queue.advance())
        assertEquals("sub2", queue.current?.eventId)

        // sub3 arrives
        assertTrue(queue.handleIncoming(payload("sub3")))

        // Advance to sub3
        assertTrue(queue.advance())
        assertEquals("sub3", queue.current?.eventId)

        // Done
        assertFalse(queue.advance())
    }

    @Test
    fun `dismiss for wrong eventId with pending queue does not drain`() {
        queue.handleIncoming(payload("main", wakeUpCheckEnabled = true))
        queue.handleIncoming(payload("sub1"))
        // Dismiss for an unrelated eventId
        assertTrue(queue.handleDismiss("unrelated"))
        assertEquals("main", queue.current?.eventId)
        assertEquals(1, queue.pendingCount)
    }

    @Test
    fun `save and restore with only current and no pending`() {
        queue.handleIncoming(payload("lonely", wakeUpCheckEnabled = true))
        val bundle = Bundle()
        queue.saveToBundle(bundle)

        val restored = WakeAlarmQueue()
        restored.restoreFromBundle(bundle)
        assertEquals("lonely", restored.current?.eventId)
        assertEquals(0, restored.pendingCount)
    }

    @Test
    fun `handleIncoming after full drain resets to new current`() {
        queue.handleIncoming(payload("main", wakeUpCheckEnabled = true))
        queue.handleIncoming(payload("sub1", wakeUpCheckEnabled = false))
        queue.advance()
        // sub1 is current with no challenge, queue empty
        queue.advance() // returns false, queue empty
        // Now a completely new alarm arrives — sub1 has no challenge so it's replaced
        assertTrue(queue.handleIncoming(payload("new_main")))
        assertEquals("new_main", queue.current?.eventId)
        assertEquals(0, queue.pendingCount)
    }

    @Test
    fun `current payload properties are fully preserved through queue`() {
        val main = WakeTriggerPayload(
            eventId = "wake:main:fajr_1",
            prayer = Prayer.FAJR,
            effectivePrayer = Prayer.FAJR,
            mainAlarmMode = WakeMainAlarmMode.PRAYER_RELATIVE,
            hour = 5,
            minute = 30,
            ringtone = RingtonePreset.ADHAN_ISLAM_SOBHI,
            vibrationOnly = true,
            wakeUpCheckEnabled = true,
            wakeUpCheckType = WakeUpCheckType.WHACK_A_MOLE,
            wakeUpCheckDifficulty = MathDifficulty.HARD,
            whackAMoleKillTarget = 15,
            progressiveVolume = true,
            snoreTrackingEnabled = true,
            awakeCheckEnabled = true,
            awakeCheckDelayMinutes = 10,
            wakeUpCheckSteps = listOf(
                WakeUpCheckStep(WakeUpCheckType.MATH, MathDifficulty.EASY),
                WakeUpCheckStep(WakeUpCheckType.GYROSCOPE_MAZE, MathDifficulty.HARD),
            ),
            isSubAlarm = false,
        )
        queue.handleIncoming(main)
        val current = queue.current!!
        assertEquals(Prayer.FAJR, current.prayer)
        assertEquals(5, current.hour)
        assertEquals(30, current.minute)
        assertEquals(RingtonePreset.ADHAN_ISLAM_SOBHI, current.ringtone)
        assertTrue(current.vibrationOnly)
        assertTrue(current.wakeUpCheckEnabled)
        assertEquals(WakeUpCheckType.WHACK_A_MOLE, current.wakeUpCheckType)
        assertEquals(MathDifficulty.HARD, current.wakeUpCheckDifficulty)
        assertEquals(15, current.whackAMoleKillTarget)
        assertTrue(current.progressiveVolume)
        assertEquals(2, current.wakeUpCheckSteps.size)
    }

    @Test
    fun `save and restore preserves all payload fields`() {
        val sub = WakeTriggerPayload(
            eventId = "wake:sub:fajr_1:sub1",
            prayer = Prayer.FAJR,
            effectivePrayer = Prayer.FAJR,
            hour = 5,
            minute = 40,
            ringtone = RingtonePreset.ADHAN_NASSER_AL_QATAMI,
            vibrationOnly = false,
            wakeUpCheckEnabled = true,
            wakeUpCheckType = WakeUpCheckType.GYROSCOPE_MAZE,
            wakeUpCheckDifficulty = MathDifficulty.INTERMEDIATE,
            progressiveVolume = false,
            snoreTrackingEnabled = false,
            awakeCheckEnabled = true,
            awakeCheckDelayMinutes = 5,
            wakeUpCheckSteps = listOf(
                WakeUpCheckStep(WakeUpCheckType.GYROSCOPE_MAZE, MathDifficulty.INTERMEDIATE),
            ),
            isSubAlarm = true,
            subAlarmId = "sub1",
            offsetMinutes = 10,
            offsetDirection = OffsetDirection.AFTER,
        )
        queue.handleIncoming(payload("main", wakeUpCheckEnabled = true))
        queue.handleIncoming(sub)

        val bundle = Bundle()
        queue.saveToBundle(bundle)

        val restored = WakeAlarmQueue()
        restored.restoreFromBundle(bundle)

        restored.advance()
        val r = restored.current!!
        assertEquals("wake:sub:fajr_1:sub1", r.eventId)
        assertEquals(Prayer.FAJR, r.prayer)
        assertEquals(5, r.hour)
        assertEquals(40, r.minute)
        assertEquals(RingtonePreset.ADHAN_NASSER_AL_QATAMI, r.ringtone)
        assertTrue(r.wakeUpCheckEnabled)
        assertEquals(WakeUpCheckType.GYROSCOPE_MAZE, r.wakeUpCheckType)
        assertEquals(MathDifficulty.INTERMEDIATE, r.wakeUpCheckDifficulty)
        assertTrue(r.isSubAlarm)
        assertEquals("sub1", r.subAlarmId)
        assertEquals(10, r.offsetMinutes)
        assertEquals(OffsetDirection.AFTER, r.offsetDirection)
        assertEquals(1, r.wakeUpCheckSteps.size)
        assertEquals(WakeUpCheckType.GYROSCOPE_MAZE, r.wakeUpCheckSteps[0].type)
    }

    // ──────────────────────────────────────────────────────────────
    // Stress / ordering guarantees
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `FIFO ordering preserved across save-restore cycle`() {
        queue.handleIncoming(payload("main", wakeUpCheckEnabled = true))
        for (i in 1..10) queue.handleIncoming(payload("s$i", wakeUpCheckEnabled = true))

        val bundle = Bundle()
        queue.saveToBundle(bundle)

        val restored = WakeAlarmQueue()
        restored.restoreFromBundle(bundle)
        assertEquals("main", restored.current?.eventId)
        for (i in 1..10) {
            assertTrue(restored.advance())
            assertEquals("s$i", restored.current?.eventId)
        }
        assertFalse(restored.advance())
    }
}

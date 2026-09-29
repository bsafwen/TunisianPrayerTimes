package com.tunisianprayertimes.tv.kiosk

import com.tunisianprayertimes.tv.kiosk.AdminEntryDetector.Action.DOWN
import com.tunisianprayertimes.tv.kiosk.AdminEntryDetector.Action.UP
import com.tunisianprayertimes.tv.kiosk.AdminEntryDetector.Key
import com.tunisianprayertimes.tv.kiosk.AdminEntryDetector.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminEntryDetectorTest {

    private val detector = AdminEntryDetector()

    @Test
    fun holdingOkWithRepeatsOpensAtThreeSeconds() {
        assertEquals(Result.PASS, detector.onKey(Key.OK, DOWN, 0))
        assertEquals(Result.PASS, detector.onKey(Key.OK, DOWN, 2_900))
        assertEquals(Result.OPEN_ADMIN, detector.onKey(Key.OK, DOWN, 3_000))
        assertEquals(Result.CONSUME, detector.onKey(Key.OK, DOWN, 3_100))
        assertEquals(Result.CONSUME, detector.onKey(Key.OK, UP, 3_200))
    }

    @Test
    fun remotesWithoutRepeatsOpenOnALateRelease() {
        detector.onKey(Key.OK, DOWN, 0)
        assertEquals(Result.OPEN_ADMIN, detector.onKey(Key.OK, UP, 3_050))
        detector.onKey(Key.OK, DOWN, 10_000)
        assertEquals(Result.PASS, detector.onKey(Key.OK, UP, 12_900))
    }

    @Test
    fun fiveQuickOksOpenButSlowOnesDoNot() {
        val quick = (0 until 5).map { i ->
            detector.onKey(Key.OK, DOWN, i * 600L)
            detector.onKey(Key.OK, UP, i * 600L + 50)
        }
        assertEquals(List(4) { Result.PASS } + Result.OPEN_ADMIN, quick)
        val slow = (0 until 5).map { i ->
            detector.onKey(Key.OK, DOWN, 100_000 + i * 900L)
            detector.onKey(Key.OK, UP, 100_000 + i * 900L + 50)
        }
        assertTrue(slow.all { it == Result.PASS })
    }

    @Test
    fun menuKeysOpenAndOtherKeysPass() {
        assertEquals(Result.CONSUME, detector.onKey(Key.MENU, DOWN, 0))
        assertEquals(Result.OPEN_ADMIN, detector.onKey(Key.MENU, UP, 10))
        assertEquals(Result.PASS, detector.onKey(Key.OTHER, DOWN, 20))
        assertEquals(Result.PASS, detector.onKey(Key.OTHER, UP, 30))
    }
}

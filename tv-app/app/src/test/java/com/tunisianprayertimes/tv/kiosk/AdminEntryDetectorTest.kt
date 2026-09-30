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
    fun aPressWhoseEndWentElsewhereIsForgotten() {
        // OK went down on the wall, then the prayer began and its release never came here.
        detector.onKey(Key.OK, DOWN, 0)
        detector.reset()
        // Minutes later a short press is only a press, not the end of a 3-second hold.
        assertEquals(Result.PASS, detector.onKey(Key.OK, DOWN, 600_000))
        assertEquals(Result.PASS, detector.onKey(Key.OK, UP, 600_050))
        // Nor is a tap the fifth after four made before the keys went elsewhere.
        repeat(4) { i ->
            detector.onKey(Key.OK, DOWN, 610_000 + i * 300L)
            detector.onKey(Key.OK, UP, 610_000 + i * 300L + 50)
        }
        detector.reset()
        assertEquals(Result.PASS, detector.onKey(Key.OK, DOWN, 611_500))
        assertEquals(Result.PASS, detector.onKey(Key.OK, UP, 611_550))
    }

    @Test
    fun menuKeysOpenAndOtherKeysPass() {
        assertEquals(Result.CONSUME, detector.onKey(Key.MENU, DOWN, 0))
        assertEquals(Result.OPEN_ADMIN, detector.onKey(Key.MENU, UP, 10))
        assertEquals(Result.PASS, detector.onKey(Key.OTHER, DOWN, 20))
        assertEquals(Result.PASS, detector.onKey(Key.OTHER, UP, 30))
    }
}

package com.tunisianprayertimes.quran

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The reading's clock alone: how long it holds the reminders back, and when its end is written again. */
class QuranReadingTest {
    private val minute = 60_000L
    private val touched = 1_000 * minute

    @Test
    fun aReadingEndsTenMinutesAfterTheLastThingDoneInIt() {
        val until = quranReadingRenewed(0L, touched)!!
        assertEquals(touched + 10 * minute, until)
        assertEquals(until, quranReadingHolds(until, touched + 10 * minute - 1))
        assertNull(quranReadingHolds(until, touched + 10 * minute))
    }

    @Test
    fun aTouchWithinAMinuteWritesNothing_aLaterOneMovesTheEnd() {
        val until = touched + 10 * minute
        assertNull(quranReadingRenewed(until, touched + minute - 1))
        assertEquals(touched + 11 * minute, quranReadingRenewed(until, touched + minute))
    }

    @Test
    fun anEndKeptBeforeTheClockWasPutBack_holdsNothingAndIsWrittenAgain() {
        val kept = touched + 40 * minute
        assertNull(quranReadingHolds(kept, touched))
        assertEquals(touched + 10 * minute, quranReadingRenewed(kept, touched))
    }
}

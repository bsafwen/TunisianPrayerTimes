package com.tunisianprayertimes.ui

import com.tunisianprayertimes.quran.audio.QuranAyahTiming
import com.tunisianprayertimes.quran.audio.QuranSurahRecording
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuranVerseStepUiTest {
    // Three verses of 10 s each after a 2 s opening; each verse ends where the next begins.
    private val recording = QuranSurahRecording(
        number = 1, assetPath = "", durationMs = 34_000L,
        timings = listOf(QuranAyahTiming(1, 2_000L, 12_000L), QuranAyahTiming(2, 12_000L, 22_000L), QuranAyahTiming(3, 22_000L, 32_000L)),
    )

    @Test
    fun forward_goesToTheNextVerseStart() {
        assertEquals(2, quranVerseStep(recording, 5_000L, forward = true, start = 0L, end = 34_000L)?.ayah)
        // Exactly at a verse start, forward is the verse after it.
        assertEquals(3, quranVerseStep(recording, 12_000L, forward = true, start = 0L, end = 34_000L)?.ayah)
        assertNull(quranVerseStep(recording, 25_000L, forward = true, start = 0L, end = 34_000L))
    }

    @Test
    fun forwardDuringThePauseAfterAVerse_isTheVerseRightAfterIt() {
        // The last poll before the pause left the position just short of verse 2's start.
        assertEquals(2, quranVerseStep(recording, 11_960L, forward = true, start = 0L, end = 34_000L, pausedAfter = 1)?.ayah)
        // Back in the same pause hears verse 1 again.
        assertEquals(1, quranVerseStep(recording, 11_960L, forward = false, start = 0L, end = 34_000L, pausedAfter = 1)?.ayah)
    }

    @Test
    fun back_restartsTheVerseUnderWay_orGoesToTheOneBeforeInItsFirstMoments() {
        assertEquals(2, quranVerseStep(recording, 18_000L, forward = false, start = 0L, end = 34_000L)?.ayah)
        assertEquals(1, quranVerseStep(recording, 12_800L, forward = false, start = 0L, end = 34_000L)?.ayah)
    }

    @Test
    fun steps_stayInsideARepeatedPassage() {
        assertNull(quranVerseStep(recording, 15_000L, forward = true, start = 12_000L, end = 22_000L))
        assertEquals(2, quranVerseStep(recording, 15_000L, forward = false, start = 12_000L, end = 22_000L)?.ayah)
    }
}

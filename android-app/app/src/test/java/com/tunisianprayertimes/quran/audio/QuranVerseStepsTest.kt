package com.tunisianprayertimes.quran.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class QuranVerseStepsTest {
    // Verses 1..4 from 10 s to 50 s, with a silence between verses 2 and 3.
    private val track = QuranSurahRecording(
        2, "quran/audio/test/2.mp3", 60_000L,
        listOf(
            QuranAyahTiming(1, 10_000L, 20_000L),
            QuranAyahTiming(2, 20_000L, 30_000L),
            QuranAyahTiming(3, 31_000L, 40_000L),
            QuranAyahTiming(4, 40_000L, 50_000L),
        ),
    )

    @Test
    fun previous_earlyInAVerse_goesToTheVerseBefore() {
        assertEquals(QuranPreviousTarget.Verse(2), track.previousTarget(31_000L + 1_000L))
    }

    @Test
    fun previous_laterInAVerse_hearsTheSameVerseAgain() {
        assertEquals(QuranPreviousTarget.Verse(3), track.previousTarget(31_000L + 1_501L))
    }

    @Test
    fun previous_inTheFirstVerse_goesToTheChapterBefore() {
        assertEquals(QuranPreviousTarget.PreviousChapter, track.previousTarget(10_500L))
        assertEquals(QuranPreviousTarget.PreviousChapter, track.previousTarget(2_000L))
    }

    @Test
    fun previous_inTheSilenceBetweenVerses_hearsTheVerseJustEnded() {
        assertEquals(QuranPreviousTarget.Verse(2), track.previousTarget(30_500L))
    }

    @Test
    fun previous_duringAPauseToRepeat_hearsThePausedVerse() {
        assertEquals(QuranPreviousTarget.Verse(4), track.previousTarget(50_000L, pausedAfter = 4))
    }

    @Test
    fun verseEnd_repeatsEachVerseTheChosenNumberOfTimes() {
        assertEquals(QuranVerseEnd.Again(2), quranVerseEnd(3, 1, false, 0f, 10_000L, 1f))
        assertEquals(QuranVerseEnd.Again(3), quranVerseEnd(3, 2, false, 0f, 10_000L, 1f))
        assertEquals(QuranVerseEnd.Continue, quranVerseEnd(3, 3, false, 0f, 10_000L, 1f))
    }

    @Test
    fun verseEnd_endlessVerseRepeatNeverMovesOn() {
        assertEquals(QuranVerseEnd.Again(51), quranVerseEnd(0, 50, false, 1f, 10_000L, 1f))
    }

    @Test
    fun verseEnd_sleepWaitsForTheLastRecitationOfTheVerse() {
        assertEquals(QuranVerseEnd.Again(2), quranVerseEnd(2, 1, true, 0f, 10_000L, 1f))
        assertEquals(QuranVerseEnd.Sleep, quranVerseEnd(2, 2, true, 1f, 10_000L, 1f))
        // A verse repeated until the listener moves on still stops for the timer.
        assertEquals(QuranVerseEnd.Sleep, quranVerseEnd(0, 7, true, 0f, 10_000L, 1f))
    }

    @Test
    fun verseEnd_stopBeforeAPoint_comesWhenTheVerseIsLeft_neverInAnEndlessRepetition() {
        // Before the silence to repeat: the stop is the reader's turn.
        assertEquals(QuranVerseEnd.Sleep, quranVerseEnd(1, 1, false, 1f, 10_000L, 1f, stopBefore = true))
        assertEquals(QuranVerseEnd.Again(2), quranVerseEnd(3, 1, false, 0f, 10_000L, 1f, stopBefore = true))
        assertEquals(QuranVerseEnd.Sleep, quranVerseEnd(3, 3, false, 0f, 10_000L, 1f, stopBefore = true))
        // Repeated until the listener moves on: the verse is not left, so no stop.
        assertEquals(QuranVerseEnd.Again(8), quranVerseEnd(0, 7, false, 0f, 10_000L, 1f, stopBefore = true))
    }

    @Test
    fun verseEnd_pauseIsMeasuredInTheVerseLengthAtTheListeningSpeed() {
        assertEquals(QuranVerseEnd.Pause(10_000L), quranVerseEnd(1, 1, false, 1f, 10_000L, 1f))
        assertEquals(QuranVerseEnd.Pause(5_000L), quranVerseEnd(1, 1, false, 0.5f, 10_000L, 1f))
        assertEquals(QuranVerseEnd.Pause(10_000L), quranVerseEnd(1, 1, false, 2f, 10_000L, 2f))
        // Never shorter than half a second.
        assertEquals(QuranVerseEnd.Pause(500L), quranVerseEnd(1, 1, false, 0.5f, 400L, 1f))
    }

    @Test
    fun verseEnd_withoutAnyOption_goesOn() {
        assertEquals(QuranVerseEnd.Continue, quranVerseEnd(1, 1, false, 0f, 10_000L, 1f))
    }
}

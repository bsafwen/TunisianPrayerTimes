package com.tunisianprayertimes.quran.audio

import com.tunisianprayertimes.quran.QuranVerseReference
import com.tunisianprayertimes.quran.hifz.HifzFreshGapMs
import com.tunisianprayertimes.quran.hifz.HifzLearning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuranLearningExposureTrackerTest {
    private val verse = QuranVerseReference(2, 67)
    private val listening = QuranPlaybackState(
        surah = verse.surah, ayah = verse.ayah, playing = true,
        playlist = QuranPlaylist("Listening", listOf(QuranRepeatRange(verse, verse)), listOf("2:67")),
    )
    private val tracker = QuranLearningExposureTracker()

    private fun heard(
        at: Long,
        state: QuranPlaybackState = listening,
        prepared: Boolean = true,
        seeking: Boolean = false,
        playerIsPlaying: Boolean = true,
        day: Long = 100,
    ) = tracker.heard(state, prepared, seeking, playerIsPlaying, day, at)

    @Test
    fun theSameVerseResumingAfterALongPauseRefreshesTheSeenTimeUntilTheClipFinishes() {
        val started = 1_000_000L
        var ledger = HifzLearning(100).expose(setOf(heard(started)!!), started)
        val resumed = started + HifzFreshGapMs + 60_000L
        assertNull(heard(resumed - 1, listening.copy(playing = false), playerIsPlaying = false))
        ledger = ledger.expose(setOf(heard(resumed)!!), resumed)
        assertNull(heard(resumed + 5_000L, listening.copy(playing = false, playlist = null), playerIsPlaying = false))
        assertEquals(resumed, ledger.seen[verse])
    }

    @Test
    fun continuousPlaybackRefreshesTheVerseOncePerMinute() {
        assertEquals(verse, heard(1_000L))
        for (at in 1_001L until 61_000L step 100L) assertNull(heard(at))
        assertEquals(verse, heard(61_000L))
        assertNull(heard(61_001L))
        assertEquals(verse, heard(121_000L))
    }

    @Test
    fun silentSeekLoadingAndDownloadStatesNeverRecordOrConsumeTheNextAudibleExposure() {
        val silentStates = listOf(
            listening.copy(playing = false),
            listening.copy(loading = true),
            listening.copy(notDownloaded = true),
            listening.copy(gapAfter = verse.ayah),
            listening.copy(playlist = null),
            listening.copy(ayah = null),
        )
        for (state in silentStates) assertNull(heard(1_000L, state))
        assertNull(heard(1_000L, prepared = false))
        assertNull(heard(1_000L, seeking = true))
        // The published flag can still say playing while the MediaPlayer has just paused.
        assertNull(heard(1_000L, playerIsPlaying = false))
        assertEquals(verse, heard(1_000L))
    }

    @Test
    fun shortRepeatedPassesAreDebouncedAndALaterReplayRefreshes() {
        assertEquals(verse, heard(1_000L))
        assertNull(heard(10_000L, seeking = true, playerIsPlaying = false))
        assertNull(heard(10_100L))
        assertNull(heard(61_000L, seeking = true, playerIsPlaying = false))
        assertEquals(verse, heard(61_100L))
    }

    @Test
    fun aDifferentVerseAndMidnightAreRecordedImmediately() {
        assertEquals(verse, heard(1_000L))
        assertEquals(QuranVerseReference(2, 68), heard(2_000L, listening.copy(ayah = 68)))
        assertEquals(QuranVerseReference(2, 68), heard(3_000L, listening.copy(ayah = 68), day = 101))
    }
}

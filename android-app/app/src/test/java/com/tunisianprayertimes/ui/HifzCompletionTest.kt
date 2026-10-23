package com.tunisianprayertimes.ui

import com.tunisianprayertimes.quran.QuranVerseReference
import com.tunisianprayertimes.quran.audio.QuranPlaybackState
import com.tunisianprayertimes.quran.audio.QuranPlaylist
import com.tunisianprayertimes.quran.audio.QuranRepeatRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HifzCompletionTest {
    private val verse = QuranVerseReference(67, 2)
    private val request = QuranPlaylist("تدريب", listOf(QuranRepeatRange(verse, verse, 1)), listOf("الملك 2"), requestId = "current")

    @Test fun completionFromTheBackgroundNeedsNoUiObservationOfPlaybackStarting() {
        assertEquals(42L, hifzCompletedDrillId(42, request, QuranPlaybackState(finishedPlaylist = request), false))
    }

    @Test fun anOldIdenticalDrillCannotCompleteTheNewRequest() {
        assertNull(hifzCompletedDrillId(42, request, QuranPlaybackState(finishedPlaylist = request.copy(requestId = "previous")), false))
        assertNull(hifzCompletedDrillId(null, request, QuranPlaybackState(finishedPlaylist = request), false))
    }

    @Test fun pauseCancelDownloadAndFailureDoNotMeanCompletion() {
        assertNull(hifzCompletedDrillId(42, request, QuranPlaybackState(playlist = request, playing = false), false))
        assertNull(hifzCompletedDrillId(42, request, QuranPlaybackState(), false))
        assertNull(hifzCompletedDrillId(42, request, QuranPlaybackState(finishedPlaylist = request), true))
        assertNull(hifzCompletedDrillId(42, request, QuranPlaybackState(finishedPlaylist = request, error = "failed"), false))
    }
}

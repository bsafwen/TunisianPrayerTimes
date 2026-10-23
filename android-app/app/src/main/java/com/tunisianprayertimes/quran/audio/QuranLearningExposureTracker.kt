package com.tunisianprayertimes.quran.audio

import com.tunisianprayertimes.quran.QuranVerseReference

/**
 * A point's audible answer refreshes its recall wait even when the reader is backgrounded. Position polling and
 * repeated passes consult the ledger at most once a minute for the same verse; a different verse/day is immediate.
 * The ledger also debounces with its other writers, including the reader, before persisting an exposure.
 */
internal class QuranLearningExposureTracker {
    private var lastDay: Long? = null
    private var lastVerse: QuranVerseReference? = null
    private var lastElapsedMs = 0L

    fun heard(
        state: QuranPlaybackState,
        prepared: Boolean,
        seeking: Boolean,
        playerIsPlaying: Boolean,
        today: Long,
        elapsedMs: Long,
    ): QuranVerseReference? {
        if (state.playlist == null || !prepared || seeking || !playerIsPlaying || !state.playing ||
            state.loading || state.notDownloaded || state.gapAfter != null) return null
        val surah = state.surah?.takeIf { it in 1..114 } ?: return null
        val ayah = state.ayah?.takeIf { it in 1..286 } ?: return null
        val verse = QuranVerseReference(surah, ayah)
        if (today == lastDay && verse == lastVerse && elapsedMs >= lastElapsedMs &&
            elapsedMs - lastElapsedMs < 60_000L) return null
        lastDay = today
        lastVerse = verse
        lastElapsedMs = elapsedMs
        return verse
    }
}

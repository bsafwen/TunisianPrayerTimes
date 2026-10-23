package com.tunisianprayertimes.ui

import com.tunisianprayertimes.quran.audio.QuranPlaybackState
import com.tunisianprayertimes.quran.audio.QuranPlaylist

/** Completion belongs to this requested drill, even if its start and finish happened while the reader was backgrounded. */
internal fun hifzCompletedDrillId(pointId: Long?, requested: QuranPlaylist?, playback: QuranPlaybackState, downloading: Boolean): Long? =
    pointId?.takeIf {
        requested?.requestId != null && playback.finishedPlaylist == requested &&
            !playback.playing && !playback.loading && playback.error == null && !downloading
    }

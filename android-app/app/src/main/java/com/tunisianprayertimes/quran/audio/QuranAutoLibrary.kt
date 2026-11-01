package com.tunisianprayertimes.quran.audio

import android.content.Context
import com.tunisianprayertimes.quran.QuranRepository
import com.tunisianprayertimes.quran.QuranSurah
import com.tunisianprayertimes.quran.assets.QuranAssets
import com.tunisianprayertimes.quran.assets.QuranPackState

internal data class QuranAutoLibrary(
    val surahs: List<QuranSurah>,
    val tracks: Map<String, List<QuranSurahRecording>>,
    val ready: Set<QuranAutoBrowse.Selection>,
    val favorites: List<QuranAutoBrowse.Selection>,
    val recent: List<QuranAutoPlace>,
    val resume: QuranAutoPlace?,
    val packStates: Map<QuranAutoBrowse.Selection, QuranPackState> = emptyMap(),
) {
    fun track(selection: QuranAutoBrowse.Selection) = tracks[selection.reciterId]?.getOrNull(selection.surah - 1)
    fun name(selection: QuranAutoBrowse.Selection) = surahs.getOrNull(selection.surah - 1)?.name.orEmpty()

    fun queue(selection: QuranAutoBrowse.Selection, source: QuranAutoSource): QuranAutoQueue {
        val items = when (source) {
            QuranAutoSource.Catalogue -> surahs.map { QuranAutoBrowse.Selection(selection.reciterId, it.number) }
            QuranAutoSource.Downloaded -> surahs.map { QuranAutoBrowse.Selection(selection.reciterId, it.number) }.filter { it in ready }
            QuranAutoSource.Favorites -> favorites.filter { it in ready }
            QuranAutoSource.Recent -> recent.map { it.selection }.filter { it in ready }
        }.let { if (selection in it) it else listOf(selection) }
        return QuranAutoQueue(items, items.indexOf(selection), source)
    }

    companion object {
        /** Disk reads stay off the service's main thread. Resolve actual files, including partial debug bundles. */
        fun load(context: Context): QuranAutoLibrary {
            val tracks = QuranAudioController.reciters.associate { it.id to QuranRecitationTimings.load(context, it) }
            val layout = QuranAssets.layout(context)
            val states = QuranAssets.source(context)?.states?.value.orEmpty()
            val packStates = buildMap {
                tracks.forEach { (reciter, recordings) -> recordings.forEach { track ->
                    layout?.packOf(track.assetPath)?.name?.let(states::get)?.let {
                        put(QuranAutoBrowse.Selection(reciter, track.number), it)
                    }
                } }
            }
            val ready = buildSet {
                tracks.forEach { (reciter, recordings) ->
                    recordings.forEach { recording ->
                        if (QuranAssets.resolve(context, recording.assetPath) != null) add(QuranAutoBrowse.Selection(reciter, recording.number))
                    }
                }
            }
            return QuranAutoLibrary(QuranRepository.load(context).surahs, tracks, ready,
                QuranAutoStore.favorites(context), QuranAutoStore.recent(context), QuranAutoStore.lastPlace(context), packStates)
        }
    }
}

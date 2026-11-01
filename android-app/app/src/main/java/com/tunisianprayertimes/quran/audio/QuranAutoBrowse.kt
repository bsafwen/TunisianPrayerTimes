package com.tunisianprayertimes.quran.audio

import android.content.Context
import android.content.ContentResolver
import android.media.MediaDescription
import android.media.browse.MediaBrowser
import android.net.Uri
import com.tunisianprayertimes.R

/** Stable media IDs and car-safe browse items for the existing Quran playback service. */
internal object QuranAutoBrowse {
    const val ROOT_ID = "quran/root"
    const val RESUME_PARENT_ID = "quran/resume"
    const val RESUME_PLAY_ID = "quran/resume/play"
    const val RECITERS_PARENT_ID = "quran/reciters"

    private const val RECITER_PREFIX = "quran/reciter/"
    private val SurahId = Regex("^quran/reciter/([a-z0-9-]+)/surah/([1-9][0-9]{0,2})$")

    data class Selection(val reciterId: String, val surah: Int)

    fun reciterParentId(reciterId: String): String = "$RECITER_PREFIX$reciterId"

    fun surahMediaId(reciterId: String, surah: Int): String =
        "${reciterParentId(reciterId)}/surah/$surah"

    fun reciterFromParent(mediaId: String): String? = mediaId
        .takeIf { it.startsWith(RECITER_PREFIX) && '/' !in it.removePrefix(RECITER_PREFIX) }
        ?.removePrefix(RECITER_PREFIX)
        ?.takeIf { id -> QuranAudioController.reciters.any { it.id == id } }

    fun selectionFromMedia(mediaId: String): Selection? {
        val match = SurahId.matchEntire(mediaId) ?: return null
        val reciterId = match.groupValues[1]
        val surah = match.groupValues[2].toIntOrNull()?.takeIf { it in 1..114 } ?: return null
        if (QuranAudioController.reciters.none { it.id == reciterId }) return null
        return Selection(reciterId, surah)
    }

    fun rootItems(context: Context, includeResume: Boolean): List<MediaBrowser.MediaItem> = buildList {
        if (includeResume) {
            add(browsable(context, RESUME_PARENT_ID, "متابعة الاستماع", "من حيث توقفت"))
        }
        add(browsable(context, RECITERS_PARENT_ID, "القراء", "اختر القارئ ثم السورة"))
    }

    fun resumeItem(context: Context, reciter: QuranReciter, surahName: String): MediaBrowser.MediaItem =
        playable(
            context,
            RESUME_PLAY_ID,
            "سورة $surahName",
            reciter.name,
        )

    fun reciterItems(context: Context): List<MediaBrowser.MediaItem> = QuranAudioController.reciters.map { reciter ->
        browsable(context, reciterParentId(reciter.id), reciter.name, reciter.riwaya)
    }

    fun surahItems(
        context: Context,
        reciter: QuranReciter,
        surahs: List<com.tunisianprayertimes.quran.QuranSurah>,
    ): List<MediaBrowser.MediaItem> = surahs.map { surah ->
        playable(
            context,
            surahMediaId(reciter.id, surah.number),
            "سورة ${surah.name}",
            reciter.name,
        )
    }

    private fun browsable(context: Context, id: String, title: String, subtitle: String): MediaBrowser.MediaItem =
        MediaBrowser.MediaItem(description(context, id, title, subtitle), MediaBrowser.MediaItem.FLAG_BROWSABLE)

    private fun playable(context: Context, id: String, title: String, subtitle: String): MediaBrowser.MediaItem =
        MediaBrowser.MediaItem(description(context, id, title, subtitle), MediaBrowser.MediaItem.FLAG_PLAYABLE)

    private fun description(context: Context, id: String, title: String, subtitle: String): MediaDescription =
        MediaDescription.Builder()
            .setMediaId(id)
            .setTitle(title)
            .setSubtitle(subtitle)
            .setIconUri(iconUri(context))
            .build()

    fun iconUri(context: Context): Uri {
        val resource = R.drawable.ic_quran_auto_artwork
        return Uri.Builder().scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
            .authority(context.packageName)
            .appendPath(context.resources.getResourceTypeName(resource))
            .appendPath(context.resources.getResourceEntryName(resource))
            .build()
    }
}

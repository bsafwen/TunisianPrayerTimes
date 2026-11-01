package com.tunisianprayertimes.quran.audio

import android.content.ContentResolver
import android.content.Context
import android.media.MediaDescription
import android.media.browse.MediaBrowser
import android.net.Uri
import android.os.Bundle
import com.tunisianprayertimes.R
import com.tunisianprayertimes.quran.assets.QuranPackStatus
import java.util.Locale

/** Stable browsing IDs. Queue context is explicit, so Next follows the list the driver chose. */
internal object QuranAutoBrowse {
    const val ROOT_ID = "quran/root"
    const val HOME_PARENT_ID = "quran/home"
    const val DOWNLOADED_PARENT_ID = "quran/downloaded"
    const val FAVORITES_PARENT_ID = "quran/favorites"
    const val RESUME_PARENT_ID = "quran/resume"
    const val RESUME_PLAY_ID = "quran/resume/play"
    const val RECITERS_PARENT_ID = "quran/reciters"
    const val DOWNLOAD_STATUS = "android.media.extra.DOWNLOAD_STATUS"
    private const val GROUP_TITLE = "android.media.browse.CONTENT_STYLE_GROUP_TITLE_HINT"
    private const val RECITER_PREFIX = "quran/reciter/"
    private val SurahId = Regex("^quran/reciter/([a-z0-9-]+)/surah/([1-9][0-9]{0,2})$")
    private val ListId = Regex("^quran/list/(downloaded|favorites|recent)/([a-z0-9-]+)/surah/([1-9][0-9]{0,2})$")

    data class Selection(val reciterId: String, val surah: Int)
    data class Request(val selection: Selection, val source: QuranAutoSource)

    fun reciterParentId(reciterId: String) = "$RECITER_PREFIX$reciterId"
    fun surahMediaId(reciterId: String, surah: Int) = "${reciterParentId(reciterId)}/surah/$surah"
    fun mediaId(selection: Selection, source: QuranAutoSource): String = if (source == QuranAutoSource.Catalogue)
        surahMediaId(selection.reciterId, selection.surah)
        else "quran/list/${source.id}/${selection.reciterId}/surah/${selection.surah}"

    fun reciterFromParent(mediaId: String): String? = mediaId
        .takeIf { it.startsWith(RECITER_PREFIX) && '/' !in it.removePrefix(RECITER_PREFIX) }
        ?.removePrefix(RECITER_PREFIX)?.takeIf { id -> QuranAudioController.reciters.any { it.id == id } }

    fun request(mediaId: String): Request? {
        val canonical = SurahId.matchEntire(mediaId)
        val listed = ListId.matchEntire(mediaId)
        val reciter = canonical?.groupValues?.get(1) ?: listed?.groupValues?.get(2) ?: return null
        val number = (canonical?.groupValues?.get(2) ?: listed?.groupValues?.get(3))?.toIntOrNull() ?: return null
        if (number !in 1..114 || QuranAudioController.reciters.none { it.id == reciter }) return null
        val source = listed?.groupValues?.get(1)?.let { id -> QuranAutoSource.entries.first { it.id == id } }
            ?: QuranAutoSource.Catalogue
        return Request(Selection(reciter, number), source)
    }

    fun selectionFromMedia(mediaId: String): Selection? = request(mediaId)?.selection

    @Suppress("UNUSED_PARAMETER")
    fun rootItems(context: Context, includeResume: Boolean = false): List<MediaBrowser.MediaItem> = listOf(
        browsable(context, HOME_PARENT_ID, context.getString(R.string.quran_auto_home), context.getString(R.string.quran_auto_home_hint), R.drawable.ic_play_arrow),
        browsable(context, DOWNLOADED_PARENT_ID, context.getString(R.string.quran_auto_downloaded), context.getString(R.string.quran_auto_downloaded_hint), R.drawable.ic_quran_download),
        browsable(context, FAVORITES_PARENT_ID, context.getString(R.string.quran_auto_favorites), context.getString(R.string.quran_auto_favorites_hint), R.drawable.ic_quran_auto_favorite),
        browsable(context, RECITERS_PARENT_ID, context.getString(R.string.quran_auto_reciters), context.getString(R.string.quran_auto_catalogue_hint)),
    )

    fun homeItems(context: Context, library: QuranAutoLibrary): List<MediaBrowser.MediaItem> = buildList {
        add(browsable(context, QuranAutoReview.PARENT_ID, context.getString(R.string.quran_auto_review),
            context.getString(R.string.quran_auto_review_hint), R.drawable.ic_quran_auto_artwork))
        val resume = library.resume?.takeIf { it.selection in library.ready }
        if (resume != null) add(listeningItem(context, resume.selection, library, QuranAutoSource.Downloaded, resume = resume))
        val recent = library.recent.filter { it.selection in library.ready && it.selection != resume?.selection }.take(3)
        recent.forEach { add(listeningItem(context, it.selection, library, QuranAutoSource.Recent,
            group = context.getString(R.string.quran_auto_recent))) }
        if (size == 1) library.ready.sortedWith(compareBy<Selection> { it.reciterId }.thenBy { it.surah }).take(5)
            .forEach { add(listeningItem(context, it, library, QuranAutoSource.Downloaded)) }
    }

    fun downloadedItems(context: Context, library: QuranAutoLibrary): List<MediaBrowser.MediaItem> =
        QuranAudioController.reciters.flatMap { reciter -> library.surahs.map { Selection(reciter.id, it.number) }
            .filter { it in library.ready }.map { listeningItem(context, it, library, QuranAutoSource.Downloaded,
                group = reciter.name) } }

    fun favoriteItems(context: Context, library: QuranAutoLibrary): List<MediaBrowser.MediaItem> = library.favorites.map {
        listeningItem(context, it, library, QuranAutoSource.Favorites)
    }

    fun resumeItems(context: Context, library: QuranAutoLibrary): List<MediaBrowser.MediaItem> = library.resume
        ?.takeIf { it.selection in library.ready }?.let {
            listOf(listeningItem(context, it.selection, library, QuranAutoSource.Downloaded, resume = it))
        }.orEmpty()

    fun reciterItems(context: Context): List<MediaBrowser.MediaItem> = QuranAudioController.reciters.map {
        browsable(context, reciterParentId(it.id), it.name, it.riwaya)
    }

    fun surahItems(context: Context, reciter: QuranReciter,
        surahs: List<com.tunisianprayertimes.quran.QuranSurah>, library: QuranAutoLibrary? = null,
    ): List<MediaBrowser.MediaItem> = surahs.map { surah ->
        val selection = Selection(reciter.id, surah.number)
        if (library != null) listeningItem(context, selection, library, QuranAutoSource.Catalogue, showReciter = false)
        else playable(context, surahMediaId(reciter.id, surah.number), "${surah.number}. ${surah.name}", "")
    }

    private fun listeningItem(context: Context, selection: Selection, library: QuranAutoLibrary,
        source: QuranAutoSource, resume: QuranAutoPlace? = null, group: String? = null, showReciter: Boolean = true,
    ): MediaBrowser.MediaItem {
        val ready = selection in library.ready
        val pack = library.packStates[selection]
        val availability = when {
            ready -> R.string.quran_auto_ready
            pack?.status == QuranPackStatus.WaitingForWifi -> R.string.quran_auto_waiting_wifi
            pack?.status == QuranPackStatus.NeedsConfirmation -> R.string.quran_auto_confirm_download
            pack?.active == true -> R.string.quran_auto_downloading
            else -> R.string.quran_auto_missing
        }
        val track = library.track(selection)
        val verse = resume?.ayah ?: resume?.let { track?.ayahAt(it.positionMs) }
        val name = library.name(selection)
        val title = if (resume != null) context.getString(R.string.quran_auto_resume_title, name) +
            (verse?.let { " · الآية $it" } ?: "") else "${selection.surah}. $name"
        val subtitle = listOfNotNull(
            QuranAudioController.reciter(selection.reciterId).shortName.takeIf { showReciter },
            context.getString(availability),
            (resume?.positionMs ?: track?.durationMs)?.let(::duration),
        ).joinToString(" · ")
        val extras = Bundle().apply {
            putLong(DOWNLOAD_STATUS, if (ready) 2L else if (pack?.active == true) 1L else 0L)
            group?.let { putString(GROUP_TITLE, it) }
        }
        return playable(context, if (resume != null) RESUME_PLAY_ID else mediaId(selection, source), title, subtitle, extras)
    }

    private fun browsable(context: Context, id: String, title: String, subtitle: String, icon: Int = R.drawable.ic_quran_headphones) =
        MediaBrowser.MediaItem(description(context, id, title, subtitle, null, icon), MediaBrowser.MediaItem.FLAG_BROWSABLE)
    private fun playable(context: Context, id: String, title: String, subtitle: String, extras: Bundle? = null) =
        MediaBrowser.MediaItem(description(context, id, title, subtitle, extras), MediaBrowser.MediaItem.FLAG_PLAYABLE)
    private fun description(context: Context, id: String, title: String, subtitle: String, extras: Bundle?, icon: Int = R.drawable.ic_quran_auto_artwork) =
        MediaDescription.Builder().setMediaId(id).setTitle(title).setSubtitle(subtitle).setExtras(extras)
            .setIconUri(iconUri(context, icon)).build()

    fun duration(ms: Long): String {
        val seconds = ms.coerceAtLeast(0) / 1000
        return if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
            else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
    }

    fun iconUri(context: Context, resource: Int = R.drawable.ic_quran_auto_artwork): Uri {
        return Uri.Builder().scheme(ContentResolver.SCHEME_ANDROID_RESOURCE).authority(context.packageName)
            .appendPath(context.resources.getResourceTypeName(resource)).appendPath(context.resources.getResourceEntryName(resource)).build()
    }
}

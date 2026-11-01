package com.tunisianprayertimes.quran.audio

import android.app.Application
import android.content.Context
import android.media.browse.MediaBrowser
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class QuranAutoBrowseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun rootChildrenStayBrowsableForCarTabs() {
        val items = QuranAutoBrowse.rootItems(context, includeResume = true)

        assertEquals(listOf(QuranAutoBrowse.HOME_PARENT_ID, QuranAutoBrowse.DOWNLOADED_PARENT_ID,
            QuranAutoBrowse.FAVORITES_PARENT_ID, QuranAutoBrowse.RECITERS_PARENT_ID), items.map { it.mediaId })
        assertEquals(items.map { it.mediaId }, QuranAutoBrowse.rootItems(context).map { it.mediaId })
        assertTrue(items.all { it.flags and MediaBrowser.MediaItem.FLAG_BROWSABLE != 0 })
        assertTrue(items.none { it.flags and MediaBrowser.MediaItem.FLAG_PLAYABLE != 0 })
    }

    @Test
    fun reciterAndSurahItemsUseStablePlayableIds() {
        val reciter = QuranAudioController.reciter(QuranAudioController.DEFAULT_RECITER_ID)
        val reciterItems = QuranAutoBrowse.reciterItems(context)
        val surahItems = QuranAutoBrowse.surahItems(
            context,
            reciter,
            listOf(com.tunisianprayertimes.quran.QuranSurah(number = 1, name = "الفاتحة", page = 1, verseCount = 7)),
        )

        assertEquals(QuranAudioController.reciters.size, reciterItems.size)
        assertTrue(reciterItems.all { it.flags and MediaBrowser.MediaItem.FLAG_BROWSABLE != 0 })
        assertEquals(QuranAutoBrowse.surahMediaId(reciter.id, 1), surahItems.single().mediaId)
        assertTrue(surahItems.single().flags and MediaBrowser.MediaItem.FLAG_PLAYABLE != 0)
    }

    @Test
    fun surahSelectionRoundTripsThroughMediaId() {
        val expected = QuranAutoBrowse.Selection(QuranAudioController.DEFAULT_RECITER_ID, 67)

        assertEquals(expected, QuranAutoBrowse.selectionFromMedia(QuranAutoBrowse.surahMediaId(expected.reciterId, expected.surah)))
    }

    @Test
    fun malformedOrUnknownMediaIdsAreRejected() {
        val invalid = listOf(
            "",
            QuranAutoBrowse.RESUME_PLAY_ID,
            "quran/reciter/unknown/surah/1",
            "quran/reciter/hosary-qaloun/surah/0",
            "quran/reciter/hosary-qaloun/surah/115",
            "quran/reciter/hosary-qaloun/surah/1/extra",
            "quran/reciter/../surah/1",
        )

        invalid.forEach { assertNull(it, QuranAutoBrowse.selectionFromMedia(it)) }
        assertNull(QuranAutoBrowse.reciterFromParent("quran/reciter/hosary-qaloun/surah/1"))
        assertNull(QuranAutoBrowse.reciterFromParent("quran/reciter/unknown"))
    }
    @Test fun downloadedRowsArePlayableReadyItemsAndCatalogueDisclosesMissingAudio() {
        val library = fixture()
        val ready = QuranAutoBrowse.downloadedItems(context, library)
        assertEquals(listOf(1, 67), ready.map { QuranAutoBrowse.request(it.mediaId!!)!!.selection.surah })
        assertTrue(ready.all { it.description.extras!!.getLong(QuranAutoBrowse.DOWNLOAD_STATUS) == 2L })
        assertTrue(ready.all { QuranAutoBrowse.request(it.mediaId!!)!!.source == QuranAutoSource.Downloaded })
        val catalogue = QuranAutoBrowse.surahItems(context, QuranAudioController.reciters.first(), library.surahs, library)
        assertEquals(0L, catalogue[1].description.extras!!.getLong(QuranAutoBrowse.DOWNLOAD_STATUS))
        assertTrue(catalogue[1].description.subtitle.toString().contains("تحتاج تنزيلًا"))
    }

    @Test fun resumeShowsTheActualVerseAndSavedPosition() {
        val resume = QuranAutoBrowse.homeItems(context, fixture()).first { it.mediaId == QuranAutoBrowse.RESUME_PLAY_ID }
        assertEquals(QuranAutoBrowse.RESUME_PLAY_ID, resume.mediaId)
        assertTrue(resume.description.title.toString().contains("الآية 2"))
        assertTrue(resume.description.subtitle.toString().contains("0:12"))
    }

    @Test fun queueFollowsItsSourceAndNeverSilentlySkipsMissingCatalogueChapters() {
        val library = fixture()
        val first = QuranAutoBrowse.Selection("hosary-qaloun", 1)
        val downloaded = library.queue(first, QuranAutoSource.Downloaded)
        assertEquals(67, downloaded.next()!!.current.surah)
        assertNull(downloaded.previous())
        assertNull(downloaded.next()!!.next())
        assertEquals(2, library.queue(first, QuranAutoSource.Catalogue).next()!!.current.surah)
        val stale = library.queue(first, QuranAutoSource.Favorites)
        assertEquals(first, stale.current)
        assertEquals(listOf(first), stale.items)
    }

    @Test fun queuedDownloadsAndWifiWaitAreVisibleWithoutBeingMarkedReady() {
        val selection = QuranAutoBrowse.Selection("hosary-qaloun", 2)
        val library = fixture().copy(packStates = mapOf(selection to com.tunisianprayertimes.quran.assets.QuranPackState(
            com.tunisianprayertimes.quran.assets.QuranPackStatus.WaitingForWifi)))
        val row = QuranAutoBrowse.surahItems(context, QuranAudioController.reciters.first(), library.surahs, library)[1]
        assertEquals(1L, row.description.extras!!.getLong(QuranAutoBrowse.DOWNLOAD_STATUS))
        assertTrue(row.description.subtitle.toString().contains("Wi-Fi"))
        assertTrue(QuranAutoBrowse.downloadedItems(context, library).none { QuranAutoBrowse.request(it.mediaId!!)!!.selection == selection })
    }

    private fun fixture(): QuranAutoLibrary {
        val ready = setOf(QuranAutoBrowse.Selection("hosary-qaloun", 1), QuranAutoBrowse.Selection("hosary-qaloun", 67))
        return QuranAutoLibrary(listOf(com.tunisianprayertimes.quran.QuranSurah(1, "الفاتحة", 1),
            com.tunisianprayertimes.quran.QuranSurah(2, "البقرة", 2), com.tunisianprayertimes.quran.QuranSurah(67, "الملك", 562)),
            emptyMap(), ready, listOf(QuranAutoBrowse.Selection("hosary-qaloun", 67)), emptyList(),
            QuranAutoPlace(ready.first(), positionMs = 12_000L, ayah = 2))
    }

}

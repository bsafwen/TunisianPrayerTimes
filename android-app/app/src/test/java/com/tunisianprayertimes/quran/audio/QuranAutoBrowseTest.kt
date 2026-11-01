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

        assertEquals(listOf(QuranAutoBrowse.RESUME_PARENT_ID, QuranAutoBrowse.RECITERS_PARENT_ID), items.map { it.mediaId })
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
}

package com.tunisianprayertimes.quran.audio

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class QuranAutoStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    @Before fun clearCarPreferences() {
        context.getSharedPreferences("quran_auto", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(QuranPlaybackService.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun incompleteOrMissingSelectionsCannotReplaceASuccessfulListeningPlace() {
        val good = QuranPlaybackState(surah = 67, positionMs = 12_000, ayah = 2)
        QuranAutoStore.remember(context, good)
        listOf(good.copy(surah = 2, loading = true), good.copy(surah = 2, notDownloaded = true),
            good.copy(surah = 2, error = "missing")).forEach { QuranAutoStore.remember(context, it) }
        assertEquals(67, QuranAutoStore.lastPlace(context)!!.selection.surah)
        assertEquals(12_000L, QuranAutoStore.lastPlace(context)!!.positionMs)
        assertEquals(1, QuranAutoStore.recent(context).size)
    }

    @Test fun carSettingsDoNotChangePhonePracticeSettings() {
        val before = QuranAudioController.listening.value
        QuranAutoStore.setSettings(context, QuranAutoSettings(repeatSurah = true, continueQueue = false))
        assertEquals(before, QuranAudioController.listening.value)
        val effective = QuranAutoStore.settings(context).listening()
        assertEquals(0, effective.surahTimes)
        assertEquals(1, effective.verseTimes)
        assertEquals(0f, effective.gapFactor)
        assertFalse(effective.continueAfterSurah)
    }

    @Test fun savedQueueRetainsItsExactOrderAndIndex() {
        val items = listOf(QuranAutoBrowse.Selection("hosary-qaloun", 67), QuranAutoBrowse.Selection("ayoub-qaloun", 1))
        val queue = QuranAutoQueue(items, 1, QuranAutoSource.Favorites)
        QuranAutoStore.saveQueue(context, queue)
        assertEquals(queue, QuranAutoStore.queue(context))
        assertEquals(items[0], QuranAutoStore.queue(context)!!.previous()!!.current)
    }

    @Test fun favoritesArePerRecordingAndCanBeRemoved() {
        val first = QuranAutoBrowse.Selection("hosary-qaloun", 67)
        val other = first.copy(reciterId = "ayoub-qaloun")
        QuranAutoStore.toggleFavorite(context, first)
        QuranAutoStore.toggleFavorite(context, other)
        QuranAutoStore.toggleFavorite(context, first)
        assertEquals(listOf(other), QuranAutoStore.favorites(context))
    }
}

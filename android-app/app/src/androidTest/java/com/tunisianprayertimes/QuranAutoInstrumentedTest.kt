package com.tunisianprayertimes

import android.content.ComponentName
import android.content.Context
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tunisianprayertimes.quran.audio.QuranAudioController
import com.tunisianprayertimes.quran.audio.QuranAutoBrowse
import com.tunisianprayertimes.quran.audio.QuranAutoBrowserService
import com.tunisianprayertimes.quran.audio.QuranAutoStore
import com.tunisianprayertimes.quran.assets.QuranAssets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuranAutoInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext

    @Test fun androidAutoCanBrowseRecitersAndEverySurah() = withBrowser { browser ->
        val root = awaitChildren(browser, QuranAutoBrowse.ROOT_ID)
        assertEquals(listOf(QuranAutoBrowse.HOME_PARENT_ID, QuranAutoBrowse.DOWNLOADED_PARENT_ID,
            QuranAutoBrowse.FAVORITES_PARENT_ID, QuranAutoBrowse.RECITERS_PARENT_ID), root.map { it.mediaId })
        assertTrue(root.all { it.flags and MediaBrowser.MediaItem.FLAG_PLAYABLE == 0 })
        val reciters = awaitChildren(browser, QuranAutoBrowse.RECITERS_PARENT_ID)
        assertEquals(3, reciters.size)
        assertTrue(reciters.all { it.flags and MediaBrowser.MediaItem.FLAG_BROWSABLE != 0 })
        val surahs = awaitChildren(browser, QuranAutoBrowse.reciterParentId("hosary-qaloun"))
        assertEquals(114, surahs.size)
        assertEquals("quran/reciter/hosary-qaloun/surah/1", surahs.first().mediaId)
        assertEquals("quran/reciter/hosary-qaloun/surah/114", surahs.last().mediaId)
        assertTrue(surahs.all { it.flags and MediaBrowser.MediaItem.FLAG_PLAYABLE != 0 })
        val downloaded = awaitChildren(browser, QuranAutoBrowse.DOWNLOADED_PARENT_ID)
        assertTrue(downloaded.all { it.description.extras!!.getLong(QuranAutoBrowse.DOWNLOAD_STATUS) == 2L })
        assertTrue(downloaded.all { QuranAutoBrowse.request(it.mediaId!!)!!.source.id == "downloaded" })
    }

    @Test fun voicePlaybackSelectsTheRequestedReciterAndSurah() = withBrowser { browser ->
        val controller = MediaController(context, browser.sessionToken)
        val initial = requireNotNull(controller.playbackState)
        assertTrue(initial.actions and PlaybackState.ACTION_PLAY_FROM_SEARCH != 0L)
        val before = QuranAudioController.state.value
        val available = QuranAssets.resolve(context, "quran/audio/hosary/067.mp3") != null
        accepted(controller) { controller.transportControls.playFromSearch("سورة الملك بصوت الحصري", null) }
        val deadline = SystemClock.elapsedRealtime() + 15_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            val state = QuranAudioController.state.value
            if (!state.loading && (state.error != null || state.surah == 67 && (state.playing || state.interruption != null))) break
            Thread.sleep(30L)
        }
        try {
            val state = QuranAudioController.state.value
            assertFalse(state.loading)
            if (available) {
                assertEquals(67, state.surah)
                assertEquals("hosary-qaloun", state.reciterId)
                assertNull(state.error)
                assertTrue(state.playing || state.interruption != null)
            } else {
                assertEquals(before.surah, state.surah)
                assertEquals(PlaybackState.STATE_ERROR, controller.playbackState!!.state)
                assertTrue(controller.playbackState!!.errorMessage.toString().contains("آمنًا"))
            }
        } finally { controller.transportControls.pause() }
    }

    /** Run with two verified offline recordings in the preview APK, or downloaded on the device. */
    @Test fun carQueueAndRewindUseWholeSurahsAndRepeatLeavesPhoneSettingsAlone() = withBrowser { browser ->
        requirePreviewAudio()
        val controller = MediaController(context, browser.sessionToken)
        val phone = QuranAudioController.listening.value
        val beforeCar = QuranAutoStore.settings(context)
        try {
            accepted(controller) { controller.transportControls.playFromMediaId("quran/list/downloaded/hosary-qaloun/surah/1", null) }
            await { QuranAudioController.state.value.surah == 1 && QuranAudioController.state.value.playing && !QuranAudioController.state.value.loading }
            accepted(controller) { controller.transportControls.skipToNext() }
            await { QuranAudioController.state.value.surah == 67 && QuranAudioController.state.value.playing && !QuranAudioController.state.value.loading }
            assertTrue(controller.queue!!.size >= 2)
            assertEquals("quran/list/downloaded/hosary-qaloun/surah/67", controller.queue!![1].description.mediaId)
            controller.transportControls.seekTo(20_000L)
            await { QuranAudioController.state.value.positionMs in 19_900L..23_000L && QuranAudioController.state.value.playing && !QuranAudioController.state.value.loading }
            controller.transportControls.rewind()
            await { QuranAudioController.state.value.positionMs in 9_500L..14_000L }
            controller.transportControls.sendCustomAction("com.tunisianprayertimes.quran.TOGGLE_REPEAT", null)
            await { QuranAutoStore.settings(context).repeatSurah != beforeCar.repeatSurah }
            assertEquals(phone, QuranAudioController.listening.value)
            accepted(controller) { controller.transportControls.skipToPrevious() }
            await { QuranAudioController.state.value.surah == 1 && QuranAudioController.state.value.playing && !QuranAudioController.state.value.loading }
        } finally {
            controller.transportControls.pause()
            QuranAutoStore.setSettings(context, beforeCar)
        }
    }

    @Test fun missingSelectionUnknownVoiceAndStopKeepTheSuccessfulListeningPlace() = withBrowser { browser ->
        requirePreviewAudio()
        val controller = MediaController(context, browser.sessionToken)
        try {
            accepted(controller) { controller.transportControls.playFromMediaId("quran/list/downloaded/hosary-qaloun/surah/67", null) }
            await { QuranAudioController.state.value.surah == 67 && QuranAudioController.state.value.playing && !QuranAudioController.state.value.loading }
            controller.transportControls.seekTo(25_000L)
            await { QuranAudioController.state.value.positionMs in 24_900L..28_000L && QuranAudioController.state.value.playing && !QuranAudioController.state.value.loading }
            controller.transportControls.pause()
            await { !QuranAudioController.state.value.playing }
            await { !QuranAudioController.state.value.playing && !QuranAudioController.state.value.loading &&
                QuranAutoStore.lastPlace(context)?.positionMs == QuranAudioController.state.value.positionMs }
            val saved = QuranAutoStore.lastPlace(context)!!
            accepted(controller) { controller.transportControls.playFromMediaId(QuranAutoBrowse.surahMediaId("hosary-qaloun", 2), null) }
            await { QuranAudioController.state.value.error != null }
            assertEquals(67, QuranAudioController.state.value.surah)
            assertTrue(kotlin.math.abs(saved.positionMs - QuranAutoStore.lastPlace(context)!!.positionMs) < 500L)
            controller.transportControls.playFromSearch("طلب غير معروف", null)
            await { QuranAudioController.state.value.error?.contains("لم نعثر") == true }
            assertEquals(saved.selection, QuranAutoStore.lastPlace(context)!!.selection)
            controller.transportControls.stop()
            await { !QuranAudioController.state.value.playing }
            assertEquals(saved.selection, QuranAutoStore.lastPlace(context)!!.selection)
            assertTrue(kotlin.math.abs(saved.positionMs - QuranAutoStore.lastPlace(context)!!.positionMs) < 500L)
            accepted(controller) { controller.transportControls.play() }
            await { QuranAudioController.state.value.surah == 67 && !QuranAudioController.state.value.loading && QuranAudioController.state.value.error == null }
            assertTrue(QuranAudioController.state.value.positionMs >= saved.positionMs - 500L)
        } finally { controller.transportControls.pause() }
    }

    @Test fun downloadedQueueAdvancesOnCompletionAndStopsAtItsEnd() = withBrowser { browser ->
        requirePreviewAudio()
        val controller = MediaController(context, browser.sessionToken)
        val settings = QuranAutoStore.settings(context)
        QuranAutoStore.setSettings(context, settings.copy(repeatSurah = false, continueQueue = true))
        try {
            accepted(controller) { controller.transportControls.playFromMediaId("quran/list/downloaded/hosary-qaloun/surah/1", null) }
            await { QuranAudioController.state.value.surah == 1 && QuranAudioController.state.value.playing }
            controller.transportControls.seekTo(QuranAudioController.state.value.durationMs - 1_100L)
            await { QuranAudioController.state.value.surah == 67 && QuranAudioController.state.value.playing }
            assertEquals(0L, controller.playbackState!!.actions and PlaybackState.ACTION_SKIP_TO_NEXT)
            controller.transportControls.seekTo(QuranAudioController.state.value.durationMs - 1_100L)
            await { !QuranAudioController.state.value.playing && controller.metadata?.description?.subtitle?.contains("انتهت قائمة") == true }
            assertEquals(67, QuranAudioController.state.value.surah)
            assertEquals(PlaybackState.STATE_PAUSED, controller.playbackState!!.state)
            val endPosition = QuranAudioController.state.value.positionMs
            controller.transportControls.skipToNext()
            Thread.sleep(400L)
            assertFalse(QuranAudioController.state.value.playing)
            assertEquals(endPosition, QuranAudioController.state.value.positionMs)
        } finally {
            controller.transportControls.pause()
            QuranAutoStore.setSettings(context, settings)
        }
    }

    private fun accepted(controller: MediaController, action: () -> Unit) {
        val accepted = CountDownLatch(1)
        val callback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                if (state?.state == PlaybackState.STATE_CONNECTING) accepted.countDown()
            }
        }
        instrumentation.runOnMainSync { controller.registerCallback(callback, Handler(Looper.getMainLooper())) }
        try {
            action()
            assertTrue("Car command was not accepted", accepted.await(10, TimeUnit.SECONDS))
        } finally { instrumentation.runOnMainSync { controller.unregisterCallback(callback) } }
    }

    private fun requirePreviewAudio() {
        org.junit.Assume.assumeTrue("Offline preview recordings 001 and 067 are required",
            QuranAssets.resolve(context, "quran/audio/hosary/001.mp3") != null &&
                QuranAssets.resolve(context, "quran/audio/hosary/067.mp3") != null)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000L
        while (!condition() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(30L)
        assertTrue("Timed out: " + QuranAudioController.state.value, condition())
    }

    private fun withBrowser(block: (MediaBrowser) -> Unit) {
        val connected = CountDownLatch(1)
        val connectionError = AtomicReference<String>()
        lateinit var browser: MediaBrowser
        instrumentation.runOnMainSync {
            browser = MediaBrowser(context, ComponentName(context, QuranAutoBrowserService::class.java),
                object : MediaBrowser.ConnectionCallback() {
                    override fun onConnected() { connected.countDown() }
                    override fun onConnectionFailed() { connectionError.set("connection failed"); connected.countDown() }
                }, null)
            browser.connect()
        }
        try {
            assertTrue("Media browser did not connect", connected.await(15, TimeUnit.SECONDS))
            assertNull(connectionError.get())
            assertNotNull(browser.sessionToken)
            block(browser)
        } finally { instrumentation.runOnMainSync { browser.disconnect() } }
    }

    private fun awaitChildren(browser: MediaBrowser, parentId: String): List<MediaBrowser.MediaItem> {
        val loaded = CountDownLatch(1)
        val items = AtomicReference<List<MediaBrowser.MediaItem>>()
        instrumentation.runOnMainSync {
            browser.subscribe(parentId, object : MediaBrowser.SubscriptionCallback() {
                override fun onChildrenLoaded(parentId: String, children: List<MediaBrowser.MediaItem>) {
                    items.set(children)
                    loaded.countDown()
                }
            })
        }
        assertTrue("No children loaded for $parentId", loaded.await(15, TimeUnit.SECONDS))
        return items.get()
    }
}

package com.tunisianprayertimes

import android.content.ComponentName
import android.content.Context
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tunisianprayertimes.quran.audio.QuranAudioController
import com.tunisianprayertimes.quran.audio.QuranAutoBrowse
import com.tunisianprayertimes.quran.audio.QuranAutoBrowserService
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
        assertTrue(root.any { it.mediaId == QuranAutoBrowse.RECITERS_PARENT_ID })
        assertTrue(root.all { it.flags and MediaBrowser.MediaItem.FLAG_PLAYABLE == 0 })
        val reciters = awaitChildren(browser, QuranAutoBrowse.RECITERS_PARENT_ID)
        assertEquals(3, reciters.size)
        assertTrue(reciters.all { it.flags and MediaBrowser.MediaItem.FLAG_BROWSABLE != 0 })
        val surahs = awaitChildren(browser, QuranAutoBrowse.reciterParentId("hosary-qaloun"))
        assertEquals(114, surahs.size)
        assertEquals("quran/reciter/hosary-qaloun/surah/1", surahs.first().mediaId)
        assertEquals("quran/reciter/hosary-qaloun/surah/114", surahs.last().mediaId)
        assertTrue(surahs.all { it.flags and MediaBrowser.MediaItem.FLAG_PLAYABLE != 0 })
    }

    @Test fun voicePlaybackSelectsTheRequestedReciterAndSurah() = withBrowser { browser ->
        val controller = MediaController(context, browser.sessionToken)
        val initial = requireNotNull(controller.playbackState)
        assertTrue(initial.actions and PlaybackState.ACTION_PLAY_FROM_SEARCH != 0L)
        controller.transportControls.playFromSearch("سورة الملك بصوت الحصري", null)
        val deadline = SystemClock.elapsedRealtime() + 15_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            val state = QuranAudioController.state.value
            if (state.surah == 67 && !state.loading &&
                (state.playing || state.notDownloaded || state.interruption != null || state.error != null)) break
            Thread.sleep(30L)
        }
        try {
            val state = QuranAudioController.state.value
            assertEquals(67, state.surah)
            assertEquals("hosary-qaloun", state.reciterId)
            assertFalse(state.loading)
            assertNull(state.error)
            if (state.notDownloaded) {
                val playback = requireNotNull(controller.playbackState)
                assertEquals(PlaybackState.STATE_ERROR, playback.state)
                assertTrue(playback.errorMessage.toString().contains("آمنًا"))
            } else {
                assertTrue(state.playing || state.interruption != null)
            }
        } finally { controller.transportControls.pause() }
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

package com.tunisianprayertimes.quran.audio

import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.R
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicReference
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class QuranAutoSessionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun startWithAColdController() {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context)
        context.getSharedPreferences("quran_auto", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(QuranPlaybackService.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        QuranAudioController.publish(QuranPlaybackState())
        listOf("initialized", "listeningRead").forEach { name ->
            QuranAudioController::class.java.getDeclaredField(name).apply { isAccessible = true }.setBoolean(null, false)
        }
    }

    @Test fun onlyTheBrowserIsExportedAndExplicitCommandsCannotEraseTheSavedPlace() {
        val manager = context.packageManager
        assertFalse(manager.getServiceInfo(ComponentName(context, QuranPlaybackService::class.java), 0).exported)
        assertTrue(manager.getServiceInfo(ComponentName(context, QuranAutoBrowserService::class.java), 0).exported)
        val saved = context.getSharedPreferences(QuranPlaybackService.PREFERENCES, Context.MODE_PRIVATE)
        saved.edit().putInt(QuranPlaybackService.EXTRA_SURAH, 67).commit()
        val playback = Robolectric.buildService(QuranPlaybackService::class.java).create()
        shadowOf(context as Application).setComponentNameAndServiceForBindService(
            ComponentName(context, QuranPlaybackService::class.java), playback.get().onBind(Intent()),
        )
        val lifecycle = Robolectric.buildService(QuranAutoBrowserService::class.java).create()
        try {
            lifecycle.get().onStartCommand(Intent().setAction(QuranPlaybackService.ACTION_STOP), 0, 1)
            assertEquals(67, saved.getInt(QuranPlaybackService.EXTRA_SURAH, 0))
        } finally { lifecycle.destroy(); playback.destroy() }
    }

    @Test fun coldBindingRestoresPausedMetadataAndPositionWithoutAutoplayOrANotification() {
        context.getSharedPreferences(QuranPlaybackService.PREFERENCES, Context.MODE_PRIVATE).edit()
            .putInt(QuranPlaybackService.EXTRA_SURAH, 67)
            .putString(QuranPlaybackService.EXTRA_RECITER, "hosary-qaloun")
            .putLong(QuranPlaybackService.EXTRA_POSITION, 12_000L).commit()
        val lifecycle = Robolectric.buildService(QuranPlaybackService::class.java).create()
        try {
            val session = mediaSession(lifecycle.get())
            assertNotNull(playbackState(session))
            val metadata = captureMetadata(session)
            await { QuranAudioController.state.value.surah == 67 && playbackState(session)?.state == PlaybackState.STATE_PAUSED }
            // A warm timing cache can restore before the IPC capture is installed. Request its current metadata too.
            invoke(lifecycle.get(), "updateMetadata", false)
            assertEquals(QuranAutoBrowse.surahMediaId("hosary-qaloun", 67), metadata.get().getString(MediaMetadata.METADATA_KEY_MEDIA_ID))
            assertEquals(PlaybackState.STATE_PAUSED, playbackState(session)?.state)
            assertEquals(12_000L, playbackState(session)?.position)
            assertTrue(metadata.get().getString(MediaMetadata.METADATA_KEY_TITLE).contains("الملك"))
            assertFalse(QuranAudioController.state.value.playing)
            assertTrue(shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
        } finally { lifecycle.destroy() }
    }

    @Test fun displaySubtitleCarriesTheInterruptionReasonAndVoicePlaybackIsAdvertised() {
        val lifecycle = Robolectric.buildService(QuranPlaybackService::class.java).create()
        try {
            val service = lifecycle.get()
            val session = mediaSession(service)
            assertTrue(playbackState(session)!!.actions and PlaybackState.ACTION_PLAY_FROM_SEARCH != 0L)
            val metadata = captureMetadata(session)
            val notice = QuranSilenceNotice.DoNotDisturb
            QuranAudioController.publish(QuranPlaybackState(surah = 67, interruption = QuranInterruption.Silence, silence = notice))
            QuranPlaybackService::class.java.getDeclaredMethod("updateMetadata", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(service, false)
            assertEquals(notice.title, metadata.get().description.subtitle.toString())
            assertTrue(context.getString(R.string.quran_auto_download_required).contains("آمنًا"))
        } finally { lifecycle.destroy() }
    }

    @Test fun unavailableCarSelectionPreservesTheSuccessfulPlaceAndOffersResume() {
        val saved = context.getSharedPreferences(QuranPlaybackService.PREFERENCES, Context.MODE_PRIVATE)
        saved.edit().putInt(QuranPlaybackService.EXTRA_SURAH, 67)
            .putString(QuranPlaybackService.EXTRA_RECITER, "hosary-qaloun")
            .putLong(QuranPlaybackService.EXTRA_POSITION, 12_000L).commit()
        val lifecycle = Robolectric.buildService(QuranPlaybackService::class.java).create()
        try {
            val service = lifecycle.get()
            val session = mediaSession(service)
            await { playbackState(session)?.state == PlaybackState.STATE_PAUSED }
            (service.onBind(Intent()) as QuranPlaybackService.PlaybackBinder).attachCar()
            invoke(service, "playAutoMedia", QuranAutoBrowse.surahMediaId("hosary-qaloun", 2))
            await { QuranAudioController.state.value.error != null }
            assertTrue(QuranAudioController.state.value.error + org.robolectric.shadows.ShadowLog.getLogs().filter { it.tag == "QuranAuto" }
                .joinToString { it.msg + it.throwable?.stackTraceToString().orEmpty() }, QuranAudioController.state.value.error!!.contains("تحتاج تنزيلًا"))
            assertEquals(67, QuranAudioController.state.value.surah)
            assertEquals(12_000L, QuranAudioController.state.value.positionMs)
            assertEquals(67, saved.getInt(QuranPlaybackService.EXTRA_SURAH, 0))
            assertEquals(12_000L, saved.getLong(QuranPlaybackService.EXTRA_POSITION, 0))
            assertEquals(67, QuranAutoStore.lastPlace(context)!!.selection.surah)
            assertTrue(playbackState(session)!!.customActions.any { it.name.toString().contains("الاستماع السابق") })
            assertEquals(PlaybackState.STATE_ERROR, playbackState(session)!!.state)
        } finally { lifecycle.destroy() }
    }

    @Test fun carStopKeepsTheSharedResumePlace() {
        val saved = context.getSharedPreferences(QuranPlaybackService.PREFERENCES, Context.MODE_PRIVATE)
        saved.edit().putInt(QuranPlaybackService.EXTRA_SURAH, 67)
            .putLong(QuranPlaybackService.EXTRA_POSITION, 12_000L).commit()
        val lifecycle = Robolectric.buildService(QuranPlaybackService::class.java).create()
        try {
            val service = lifecycle.get()
            await { playbackState(mediaSession(service))?.state == PlaybackState.STATE_PAUSED }
            invoke(service, "stopAuto")
            assertEquals(67, saved.getInt(QuranPlaybackService.EXTRA_SURAH, 0))
            assertEquals(12_000L, saved.getLong(QuranPlaybackService.EXTRA_POSITION, 0))
            assertEquals(67, QuranAudioController.state.value.surah)
            assertFalse(QuranAudioController.state.value.playing)
        } finally { lifecycle.destroy() }
    }

    @Test fun stoppingAPhoneDrillFromTheCarClearsItsPrivatePlaybackContext() {
        val lifecycle = Robolectric.buildService(QuranPlaybackService::class.java).create()
        try {
            val service = lifecycle.get()
            val range = QuranRepeatRange(com.tunisianprayertimes.quran.QuranVerseReference(2, 1),
                com.tunisianprayertimes.quran.QuranVerseReference(2, 1), 1)
            val list = QuranPlaylist("تدريب", listOf(range), listOf("البقرة"))
            val returnType = QuranPlaybackService::class.java.declaredClasses.first { it.simpleName == "QuranReturnPlace" }
            val back = returnType.declaredConstructors.first().apply { isAccessible = true }
                .newInstance("hosary-qaloun", 67, 12_000L, null)
            fun field(name: String) = QuranPlaybackService::class.java.getDeclaredField(name).apply { isAccessible = true }
            field("playlist").set(service, list)
            field("returnPlace").set(service, back)
            field("repeat").set(service, range)
            QuranAudioController.publish(QuranPlaybackState(surah = 2, playlist = list, repeat = range))
            invoke(service, "stopAuto")
            assertEquals(67, QuranAudioController.state.value.surah)
            assertEquals(12_000L, QuranAudioController.state.value.positionMs)
            assertNull(QuranAudioController.state.value.playlist)
            assertNull(field("playlist").get(service))
            assertNull(field("returnPlace").get(service))
            assertNull(field("repeat").get(service))
        } finally { lifecycle.destroy() }
    }

    @Test fun nativeCarRepeatNeverChangesPhonePracticePreferences() {
        val before = QuranAudioController.listening.value
        QuranAudioController.updateListening(context) { it.copy(verseTimes = 3, surahTimes = 4, gapFactor = 2f) }
        val phone = QuranAudioController.listening.value
        val lifecycle = Robolectric.buildService(QuranPlaybackService::class.java).create()
        try {
            val service = lifecycle.get()
            (service.onBind(Intent()) as QuranPlaybackService.PlaybackBinder).attachCar()
            invoke(service, "toggleSurahRepeat")
            assertTrue(QuranAutoStore.settings(context).repeatSurah)
            assertEquals(phone, QuranAudioController.listening.value)
            invoke(service, "toggleSurahRepeat")
            assertFalse(QuranAutoStore.settings(context).repeatSurah)
            assertEquals(phone, QuranAudioController.listening.value)
        } finally { lifecycle.destroy(); QuranAudioController.updateListening(context) { before } }
    }

    private fun invoke(service: QuranPlaybackService, name: String, vararg values: Any) {
        val types = values.map { if (it is Boolean) Boolean::class.javaPrimitiveType!! else it.javaClass }.toTypedArray()
        QuranPlaybackService::class.java.getDeclaredMethod(name, *types).apply { isAccessible = true }.invoke(service, *values)
    }

    private fun mediaSession(service: QuranPlaybackService): MediaSession =
        QuranPlaybackService::class.java.getDeclaredField("session").apply { isAccessible = true }.get(service) as MediaSession

    // Robolectric's controller shadow isn't connected to the platform session. Inspect the real session's state
    // and capture its outgoing metadata IPC instead of mistaking a shadow's null value for an app result.
    private fun playbackState(session: MediaSession): PlaybackState? =
        MediaSession::class.java.getDeclaredField("mPlaybackState").apply { isAccessible = true }.get(session) as PlaybackState?

    private fun captureMetadata(session: MediaSession): AtomicReference<MediaMetadata> {
        val captured = AtomicReference<MediaMetadata>()
        val contract = Class.forName("android.media.session.ISession")
        val proxy = Proxy.newProxyInstance(contract.classLoader, arrayOf(contract)) { _, method, args ->
            when (method.name) {
                "setMetadata" -> { captured.set(args!![0] as MediaMetadata); null }
                "asBinder" -> Binder()
                else -> null
            }
        }
        MediaSession::class.java.getDeclaredField("mBinder").apply { isAccessible = true }.set(session, proxy)
        return captured
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10L)
        }
        assertTrue("Media session did not finish restoring", condition())
    }
}

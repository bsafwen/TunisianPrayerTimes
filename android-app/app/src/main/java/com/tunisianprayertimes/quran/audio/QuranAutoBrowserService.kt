package com.tunisianprayertimes.quran.audio

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.media.browse.MediaBrowser
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.service.media.MediaBrowserService
import android.util.Log
import com.tunisianprayertimes.quran.assets.QuranAssets
import com.tunisianprayertimes.quran.hifz.HifzSessions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Public browsing endpoint. App commands remain on the non-exported playback service. */
class QuranAutoBrowserService : MediaBrowserService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var bound = false
    private var playback: QuranPlaybackService.PlaybackBinder? = null
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            playback = binder as QuranPlaybackService.PlaybackBinder
            playback?.attachCar()
            setSessionToken(binder.sessionToken)
        }

        override fun onServiceDisconnected(name: ComponentName) { playback = null }
    }

    override fun onCreate() {
        super.onCreate()
        bound = bindService(Intent(this, QuranPlaybackService::class.java), connection, BIND_AUTO_CREATE)
        scope.launch {
            QuranAudioController.state.map { it.reciterId to it.surah }.distinctUntilChanged().collect {
                refreshLibrary()
            }
        }
        scope.launch { QuranAutoStore.changes.collect { refreshLibrary() } }
        scope.launch {
            val sessions = HifzSessions.get(this@QuranAutoBrowserService)
            sessions.initialize()
            sessions.changes.collect { refreshLibrary() }
        }
        scope.launch {
            val source = withContext(Dispatchers.IO) { QuranAssets.source(applicationContext) }
            source?.states?.collect { refreshLibrary() }
        }
    }

    // An exported browser can receive explicit start intents, but they never become playback commands.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? {
        if (!isAllowedBrowser(clientPackageName, clientUid)) {
            Log.w("QuranAuto", "Rejected media browser $clientPackageName/$clientUid")
            return null
        }
        return BrowserRoot(QuranAutoBrowse.ROOT_ID, null)
    }

    private fun isAllowedBrowser(clientPackageName: String, clientUid: Int): Boolean {
        if (clientUid == Process.myUid() || clientUid == Process.SYSTEM_UID) return true
        if (packageManager.getPackagesForUid(clientUid)?.contains(clientPackageName) != true) return false
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) return true
        if (clientPackageName in RELEASE_BROWSER_PACKAGES) return true
        return runCatching {
            packageManager.getApplicationInfo(clientPackageName, 0).flags and
                (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        }.getOrDefault(false)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowser.MediaItem>>) {
        if (parentId == QuranAutoBrowse.ROOT_ID) {
            result.sendResult(QuranAutoBrowse.rootItems(this).toMutableList())
            return
        }
        result.detach()
        scope.launch {
            val items = try {
                val sessions = HifzSessions.get(this@QuranAutoBrowserService)
                sessions.initialize()
                if (parentId == QuranAutoReview.PARENT_ID) QuranAutoReview.choices(this@QuranAutoBrowserService, sessions)
                else withContext(Dispatchers.IO) { loadChildren(parentId) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e("QuranAuto", "Cannot load browser node $parentId", error)
                emptyList()
            }
            result.sendResult(items.toMutableList())
        }
    }

    private fun loadChildren(parentId: String): List<MediaBrowser.MediaItem> {
        if (parentId == QuranAutoBrowse.RECITERS_PARENT_ID) return QuranAutoBrowse.reciterItems(this)
        val reciter = QuranAutoBrowse.reciterFromParent(parentId)
        if (reciter == null && parentId !in setOf(QuranAutoBrowse.HOME_PARENT_ID, QuranAutoBrowse.DOWNLOADED_PARENT_ID,
                QuranAutoBrowse.FAVORITES_PARENT_ID, QuranAutoBrowse.RESUME_PARENT_ID)) return emptyList()
        val library = QuranAutoLibrary.load(this)
        playback?.let { binder -> scope.launch { binder.libraryReady(library.ready.isNotEmpty()) } }
        return when (parentId) {
            QuranAutoBrowse.HOME_PARENT_ID -> QuranAutoBrowse.homeItems(this, library)
            QuranAutoBrowse.DOWNLOADED_PARENT_ID -> QuranAutoBrowse.downloadedItems(this, library)
            QuranAutoBrowse.FAVORITES_PARENT_ID -> QuranAutoBrowse.favoriteItems(this, library)
            QuranAutoBrowse.RESUME_PARENT_ID -> QuranAutoBrowse.resumeItems(this, library)
            else -> QuranAutoBrowse.surahItems(this, QuranAudioController.reciter(requireNotNull(reciter)), library.surahs, library)
        }
    }

    private fun refreshLibrary() {
        listOf(QuranAutoBrowse.ROOT_ID, QuranAutoBrowse.HOME_PARENT_ID, QuranAutoBrowse.DOWNLOADED_PARENT_ID,
            QuranAutoBrowse.FAVORITES_PARENT_ID, QuranAutoBrowse.RESUME_PARENT_ID, QuranAutoReview.PARENT_ID).forEach(::notifyChildrenChanged)
        QuranAudioController.reciters.forEach { notifyChildrenChanged(QuranAutoBrowse.reciterParentId(it.id)) }
    }

    override fun onDestroy() {
        playback?.detachCar()
        playback = null
        if (bound) unbindService(connection)
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        val RELEASE_BROWSER_PACKAGES = setOf(
            "com.google.android.projection.gearhead",
            "com.google.android.gms",
            "com.google.android.googlequicksearchbox",
            "com.google.android.apps.googleassistant",
        )
    }
}

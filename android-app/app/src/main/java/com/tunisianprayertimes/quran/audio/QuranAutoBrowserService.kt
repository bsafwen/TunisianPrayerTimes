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
import com.tunisianprayertimes.quran.QuranRepository
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
    private val preferences by lazy { getSharedPreferences(QuranPlaybackService.PREFERENCES, MODE_PRIVATE) }
    private var bound = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            setSessionToken((binder as QuranPlaybackService.PlaybackBinder).sessionToken)
        }

        override fun onServiceDisconnected(name: ComponentName) = Unit
    }

    override fun onCreate() {
        super.onCreate()
        bound = bindService(Intent(this, QuranPlaybackService::class.java), connection, BIND_AUTO_CREATE)
        scope.launch {
            QuranAudioController.state.map { it.reciterId to it.surah }.distinctUntilChanged().collect {
                notifyChildrenChanged(QuranAutoBrowse.ROOT_ID)
                notifyChildrenChanged(QuranAutoBrowse.RESUME_PARENT_ID)
            }
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
            result.sendResult(QuranAutoBrowse.rootItems(this, hasResume()).toMutableList())
            return
        }
        result.detach()
        scope.launch {
            val items = try {
                withContext(Dispatchers.IO) { loadChildren(parentId) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e("QuranAuto", "Cannot load browser node $parentId", error)
                emptyList()
            }
            result.sendResult(items.toMutableList())
        }
    }

    private fun hasResume(): Boolean = QuranAudioController.state.value.surah != null ||
        preferences.getInt(QuranPlaybackService.EXTRA_SURAH, 0) in 1..114

    private fun loadChildren(parentId: String): List<MediaBrowser.MediaItem> = when (parentId) {
        QuranAutoBrowse.RESUME_PARENT_ID -> resumeItems()
        QuranAutoBrowse.RECITERS_PARENT_ID -> QuranAutoBrowse.reciterItems(this)
        else -> QuranAutoBrowse.reciterFromParent(parentId)?.let { reciterId ->
            QuranAutoBrowse.surahItems(this, QuranAudioController.reciter(reciterId), QuranRepository.load(this).surahs)
        }.orEmpty()
    }

    private fun resumeItems(): List<MediaBrowser.MediaItem> {
        val current = QuranAudioController.state.value
        val surah = current.surah ?: preferences.getInt(QuranPlaybackService.EXTRA_SURAH, 0).takeIf { it in 1..114 }
            ?: return emptyList()
        val reciterId = if (current.surah != null) current.reciterId
            else preferences.getString(QuranPlaybackService.EXTRA_RECITER, null)
        val reciter = QuranAudioController.reciters.firstOrNull { it.id == reciterId }
            ?: QuranAudioController.reciter(QuranAudioController.DEFAULT_RECITER_ID)
        val name = QuranRepository.load(this).surahs.getOrNull(surah - 1)?.name ?: return emptyList()
        return listOf(QuranAutoBrowse.resumeItem(this, reciter, name))
    }

    override fun onDestroy() {
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

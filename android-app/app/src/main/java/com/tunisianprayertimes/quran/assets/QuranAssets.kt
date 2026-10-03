package com.tunisianprayertimes.quran.assets

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Finds the Quran's large files wherever this install keeps them: inside the app when the build
 * bundles them, otherwise in packs from Google Play (Play installs) or from the quran-cdn Worker.
 */
object QuranAssets {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var source: QuranPackSource? = null
    private val bundledFolders = ConcurrentHashMap<String, Set<String>>()

    /** Null when this build bundles every Quran file. IO thread. */
    fun layout(context: Context): QuranPackLayout? = QuranPackLayout.load(context.applicationContext)

    /** Null when this build bundles every Quran file. IO thread, the first time. */
    fun source(context: Context): QuranPackSource? {
        val layout = layout(context) ?: return null
        return source ?: synchronized(this) {
            source ?: createSource(context.applicationContext, layout).also { source = it }
        }
    }

    /** Where [assetPath] can be read on this device, or null until its pack is downloaded. IO thread. */
    fun resolve(context: Context, assetPath: String): QuranAssetSource? {
        if (isBundled(context, assetPath)) return QuranAssetSource.Bundled(assetPath)
        val pack = layout(context)?.packOf(assetPath) ?: return null
        val source = source(context) ?: return null
        val folder = source.directory(pack) ?: return null
        val file = File(folder, assetPath)
        if (file.isFile) return QuranAssetSource.Downloaded(file)
        // A pack on the device without one of its files is damaged: drop it, so that it reads as
        // missing and the next request downloads it again instead of finding nothing forever.
        Log.w(TAG, "${pack.name} lacks $assetPath; removing it so it is downloaded again")
        source.remove(listOf(pack))
        return null
    }

    /** Whether [assetPath] can come from a download at all; false means no build part carries it. IO thread. */
    fun downloadable(context: Context, assetPath: String): Boolean = layout(context)?.packOf(assetPath) != null

    /** Packs whose every file this build carries itself; they never need a download. IO thread. */
    fun bundledPacks(context: Context): Set<String> =
        layout(context)?.packs.orEmpty().filter { pack -> pack.files.all { isBundled(context, it.path) } }.map { it.name }.toSet()

    private fun isBundled(context: Context, assetPath: String): Boolean {
        val folder = assetPath.substringBeforeLast('/', "")
        val names = bundledFolders.getOrPut(folder) {
            runCatching { context.assets.list(folder)?.toSet() }.getOrNull().orEmpty()
        }
        return assetPath.substringAfterLast('/') in names
    }

    internal fun cdnRoot(context: Context): File = File(context.noBackupFilesDir, "quran-packs")

    private fun createSource(context: Context, layout: QuranPackLayout): QuranPackSource {
        val cdn = lazy { CdnQuranPackSource(context, layout, scope) }
        if (!installedByPlay(context)) return cdn.value
        return try {
            PlayOrCdnPackSource(layout, cdn, QuranPackInstaller(cdnRoot(context)), scope) { onUnavailable ->
                PlayQuranPackSource(layout, AssetPackManagerFactory.getInstance(context), scope, onUnavailable)
            }
        } catch (error: Exception) {
            Log.w(TAG, "Play Asset Delivery is unavailable; using the quran-cdn Worker", error)
            cdn.value
        }
    }

    /** Play delivers packs to installs it made, and to bundletool's local testing. */
    internal fun installedByPlay(context: Context): Boolean {
        val installer = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(context.packageName)
            }
        }.getOrNull()
        if (installer == PLAY_STORE) return true
        val metadata = runCatching {
            context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData
        }.getOrNull()
        return metadata?.containsKey(LOCAL_TESTING) == true
    }

    private const val TAG = "QuranPacks"
    private const val PLAY_STORE = "com.android.vending"
    // Added to the manifest by bundletool build-apks --local-testing.
    private const val LOCAL_TESTING = "local_testing_dir"
}

/**
 * Play Asset Delivery, until Play reports it can never serve this install; then the quran-cdn
 * Worker. Packs a launch downloaded from the Worker stay usable when a later launch uses Play.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private class PlayOrCdnPackSource(
    private val layout: QuranPackLayout,
    private val cdn: Lazy<QuranPackSource>,
    /** Finds packs from the Worker without starting its downloads. */
    private val cdnInstaller: QuranPackInstaller,
    private val scope: CoroutineScope,
    createPlay: (onUnavailable: () -> Unit) -> QuranPackSource,
) : QuranPackSource {
    private val usePlay = MutableStateFlow(true)
    private val fromCdn = MutableStateFlow<Set<String>>(emptySet())
    // What Play was asked for and whether mobile data was allowed; the Worker takes over these requests.
    private val askedOfPlay = ConcurrentHashMap<String, Boolean>()
    private val play = createPlay(::switchToCdn)

    init {
        scanCdn()
    }

    override val states: StateFlow<Map<String, QuranPackState>> = usePlay.flatMapLatest { viaPlay ->
        if (viaPlay) {
            combine(play.states, fromCdn) { fromPlay, worker ->
                fromPlay + worker.filter { fromPlay[it]?.available != true }.associateWith { QuranPackState.Available }
            }
        } else combine(play.states, cdn.value.states) { fromPlay, fromWorker -> fromWorker + fromPlay.filterValues { it.available } }
    }.stateIn(scope, SharingStarted.Eagerly, layout.packs.associate { it.name to QuranPackState.Missing })

    private val active: QuranPackSource get() = if (usePlay.value) play else cdn.value

    override fun directory(pack: QuranPack): File? =
        play.directory(pack) ?: cdnInstaller.installedDirectory(pack).takeIf { it.isDirectory }

    override fun fetch(packs: List<QuranPack>, allowMetered: Boolean) {
        if (!usePlay.value) return cdn.value.fetch(packs, allowMetered)
        packs.forEach { pack -> askedOfPlay.merge(pack.name, allowMetered, Boolean::or) }
        play.fetch(packs, allowMetered)
    }

    override fun confirmMobileData(packs: List<QuranPack>, launcher: ActivityResultLauncher<IntentSenderRequest>) =
        active.confirmMobileData(packs, launcher)

    override fun remove(packs: List<QuranPack>) {
        packs.forEach { askedOfPlay.remove(it.name) }
        play.remove(packs)
        if (!usePlay.value) cdn.value.remove(packs)
        else scope.launch(Dispatchers.IO) {
            packs.forEach(cdnInstaller::remove)
            scanCdn()
        }
    }

    override fun refresh() {
        active.refresh()
        scanCdn()
    }

    private fun scanCdn() {
        scope.launch(Dispatchers.IO) { fromCdn.value = layout.packs.filter(cdnInstaller::isInstalled).map { it.name }.toSet() }
    }

    /** Play will never deliver to this install: the Worker does, starting with what Play was asked for. */
    private fun switchToCdn() {
        if (!usePlay.compareAndSet(true, false)) return
        val asked = askedOfPlay.toMap()
        askedOfPlay.clear()
        val delivered = play.states.value.filterValues { it.available }.keys
        layout.packs.filter { it.name in asked && it.name !in delivered }
            .groupBy { asked.getValue(it.name) }
            .forEach { (allowMetered, packs) -> cdn.value.fetch(packs, allowMetered) }
    }
}

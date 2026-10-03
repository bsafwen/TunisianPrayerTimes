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
        val file = source(context)?.directory(pack)?.let { File(it, assetPath) }?.takeIf { it.isFile } ?: return null
        return QuranAssetSource.Downloaded(file)
    }

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
            PlayOrCdnPackSource(layout, cdn, scope) { onUnavailable ->
                PlayQuranPackSource(layout, AssetPackManagerFactory.getInstance(context), onUnavailable)
            }
        } catch (error: Exception) {
            Log.w("QuranPacks", "Play Asset Delivery is unavailable; using the quran-cdn Worker", error)
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

    private const val PLAY_STORE = "com.android.vending"
    // Added to the manifest by bundletool build-apks --local-testing.
    private const val LOCAL_TESTING = "local_testing_dir"
}

/** Play Asset Delivery, until Play reports it can never serve this install; then the quran-cdn Worker. */
@OptIn(ExperimentalCoroutinesApi::class)
private class PlayOrCdnPackSource(
    layout: QuranPackLayout,
    private val cdn: Lazy<QuranPackSource>,
    scope: CoroutineScope,
    createPlay: (onUnavailable: () -> Unit) -> QuranPackSource,
) : QuranPackSource {
    private val usePlay = MutableStateFlow(true)
    private val play = createPlay { usePlay.value = false }

    override val states: StateFlow<Map<String, QuranPackState>> = usePlay.flatMapLatest { viaPlay ->
        // Packs Play already delivered stay usable after the switch.
        if (viaPlay) play.states
        else combine(play.states, cdn.value.states) { fromPlay, fromCdn -> fromCdn + fromPlay.filterValues { it.available } }
    }.stateIn(scope, SharingStarted.Eagerly, layout.packs.associate { it.name to QuranPackState.Missing })

    private val active: QuranPackSource get() = if (usePlay.value) play else cdn.value

    override fun directory(pack: QuranPack): File? =
        play.directory(pack) ?: if (usePlay.value) null else cdn.value.directory(pack)

    override fun fetch(packs: List<QuranPack>, allowMetered: Boolean) = active.fetch(packs, allowMetered)

    override fun confirmMobileData(packs: List<QuranPack>, launcher: ActivityResultLauncher<IntentSenderRequest>) =
        active.confirmMobileData(packs, launcher)

    override fun remove(packs: List<QuranPack>) {
        play.remove(packs)
        if (!usePlay.value) cdn.value.remove(packs)
    }

    override fun refresh() = active.refresh()
}

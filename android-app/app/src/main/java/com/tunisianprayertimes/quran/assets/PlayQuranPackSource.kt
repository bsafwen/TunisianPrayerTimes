package com.tunisianprayertimes.quran.assets

import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.android.play.core.assetpacks.AssetPackException
import com.google.android.play.core.assetpacks.AssetPackManager
import com.google.android.play.core.assetpacks.AssetPackState
import com.google.android.play.core.assetpacks.AssetPackStateUpdateListener
import com.google.android.play.core.assetpacks.model.AssetPackErrorCode
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Packs delivered by Google Play (Play Asset Delivery), for installs that came from Play. */
internal class PlayQuranPackSource(
    private val layout: QuranPackLayout,
    private val manager: AssetPackManager,
    private val scope: CoroutineScope,
    /** Play cannot serve this install at all; the caller switches to the quran-cdn Worker. */
    private val onUnavailable: () -> Unit,
) : QuranPackSource {
    private val mutableStates = MutableStateFlow<Map<String, QuranPackState>>(emptyMap())
    override val states: StateFlow<Map<String, QuranPackState>> = mutableStates.asStateFlow()
    // Locations change when Play updates the app, so they are only kept until a file is missing.
    private val locations = ConcurrentHashMap<String, File>()

    private val listener = AssetPackStateUpdateListener { state -> publish(state) }

    init {
        manager.registerListener(listener)
        refresh()
    }

    override fun directory(pack: QuranPack): File? {
        locations[pack.name]?.let { cached ->
            if (cached.isDirectory) return cached
            locations.remove(pack.name)
        }
        val location = runCatching { manager.getPackLocation(pack.name) }.getOrNull() ?: return null
        val folder = location.assetsPath()?.let(::File)?.takeIf { it.isDirectory } ?: return null
        locations[pack.name] = folder
        return folder
    }

    override fun fetch(packs: List<QuranPack>, allowMetered: Boolean) {
        val names = packs.map { it.name }.filter { mutableStates.value[it]?.available != true && !onDevice(it) }
        if (names.isEmpty()) return
        // Play decides about mobile data itself: above 200 MB it waits for Wi-Fi or for consent.
        mutableStates.update { states -> states + names.associateWith { QuranPackState(QuranPackStatus.Pending) } }
        manager.fetch(names)
            .addOnSuccessListener { result -> result.packStates().values.forEach(::publish) }
            .addOnFailureListener { error -> fail(names, error) }
    }

    override fun confirmMobileData(packs: List<QuranPack>, launcher: ActivityResultLauncher<IntentSenderRequest>) {
        runCatching { manager.showConfirmationDialog(launcher) }
            .onFailure { Log.w(TAG, "Play could not ask to use mobile data", it) }
    }

    override fun remove(packs: List<QuranPack>) {
        packs.forEach { pack ->
            locations.remove(pack.name)
            manager.removePack(pack.name).addOnCompleteListener {
                mutableStates.update { states -> states + (pack.name to QuranPackState.Missing) }
            }
        }
    }

    override fun refresh() {
        locations.clear()
        scope.launch(Dispatchers.IO) {
            // What is on the device counts at once, without waiting for Play, which may be offline.
            val present = runCatching { manager.packLocations }.getOrNull().orEmpty().mapNotNull { (name, location) ->
                location.assetsPath()?.let(::File)?.takeIf { it.isDirectory }?.let { name to it }
            }.toMap()
            locations.putAll(present)
            mutableStates.update { states -> states + present.keys.associateWith { QuranPackState.Available } }
            requestStates()
        }
    }

    private fun requestStates() {
        val names = layout.packs.map { it.name }
        manager.getPackStates(names)
            .addOnSuccessListener { result -> result.packStates().values.forEach(::publish) }
            .addOnFailureListener { error ->
                Log.w(TAG, "Could not read Quran pack states", error)
                if (playUnavailable(errorCode(error))) onUnavailable()
            }
    }

    private fun publish(state: AssetPackState) {
        if (playUnavailable(state.errorCode())) onUnavailable()
        val reported = playPackState(state.status(), state.errorCode(), state.bytesDownloaded(), state.totalBytesToDownload())
        // A pack whose files are here stays usable whatever an answer from Play says; only remove() drops it.
        val mapped = if (reported.status == QuranPackStatus.Missing && onDevice(state.name())) QuranPackState.Available else reported
        if (!mapped.available) locations.remove(state.name())
        mutableStates.update { states -> states + (state.name() to mapped) }
    }

    private fun fail(names: List<String>, error: Exception) {
        Log.w(TAG, "Play could not fetch Quran packs $names", error)
        val code = errorCode(error)
        if (playUnavailable(code)) onUnavailable()
        val failed = QuranPackState(QuranPackStatus.Failed, problem = playProblem(code))
        mutableStates.update { states -> states + names.associateWith { failed } }
    }

    private fun onDevice(name: String): Boolean = locations[name]?.isDirectory == true

    private fun errorCode(error: Exception): Int = (error as? AssetPackException)?.errorCode ?: AssetPackErrorCode.INTERNAL_ERROR

    private companion object {
        const val TAG = "QuranPacks"
    }
}

internal fun playPackState(status: Int, errorCode: Int, downloaded: Long, total: Long): QuranPackState =
    when (status) {
        AssetPackStatus.COMPLETED -> QuranPackState(QuranPackStatus.Available, total, total)
        AssetPackStatus.PENDING -> QuranPackState(QuranPackStatus.Pending, downloaded, total)
        AssetPackStatus.DOWNLOADING -> QuranPackState(QuranPackStatus.Downloading, downloaded, total)
        // Downloaded; Play is unpacking it on the device.
        AssetPackStatus.TRANSFERRING -> QuranPackState(QuranPackStatus.Transferring, total, total)
        AssetPackStatus.WAITING_FOR_WIFI -> QuranPackState(QuranPackStatus.WaitingForWifi, downloaded, total)
        AssetPackStatus.REQUIRES_USER_CONFIRMATION -> QuranPackState(QuranPackStatus.NeedsConfirmation, downloaded, total)
        AssetPackStatus.FAILED -> QuranPackState(QuranPackStatus.Failed, downloaded, total, playProblem(errorCode))
        // NOT_INSTALLED, CANCELED and UNKNOWN: nothing on the device and nothing under way.
        else -> QuranPackState(QuranPackStatus.Missing, 0L, total)
    }

internal fun playProblem(errorCode: Int): QuranDownloadProblem = when (errorCode) {
    AssetPackErrorCode.NETWORK_ERROR -> QuranDownloadProblem.Network
    AssetPackErrorCode.INSUFFICIENT_STORAGE -> QuranDownloadProblem.Storage
    AssetPackErrorCode.APP_UNAVAILABLE, AssetPackErrorCode.PACK_UNAVAILABLE, AssetPackErrorCode.API_NOT_AVAILABLE,
    AssetPackErrorCode.APP_NOT_OWNED, AssetPackErrorCode.UNRECOGNIZED_INSTALLATION,
    AssetPackErrorCode.ACCESS_DENIED, AssetPackErrorCode.DOWNLOAD_NOT_FOUND, AssetPackErrorCode.INVALID_REQUEST,
    -> QuranDownloadProblem.Store
    else -> QuranDownloadProblem.Other
}

/** Errors meaning Play will never deliver packs to this install; the quran-cdn Worker can. */
internal fun playUnavailable(errorCode: Int): Boolean = errorCode == AssetPackErrorCode.API_NOT_AVAILABLE ||
    errorCode == AssetPackErrorCode.APP_UNAVAILABLE || errorCode == AssetPackErrorCode.UNRECOGNIZED_INSTALLATION

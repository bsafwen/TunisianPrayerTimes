package com.tunisianprayertimes.quran.assets

import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import kotlinx.coroutines.flow.StateFlow
import java.io.File

enum class QuranPackStatus {
    Available,
    Missing,
    Pending,
    Downloading,
    /** Downloaded; being unpacked or verified on the device. */
    Transferring,
    /** Waits for an unmetered network, or for the user to allow mobile data. */
    WaitingForWifi,
    /** Google Play needs the user to confirm before it downloads. */
    NeedsConfirmation,
    Failed,
}

enum class QuranDownloadProblem { Network, Storage, Store, Integrity, Other }

data class QuranPackState(
    val status: QuranPackStatus,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val problem: QuranDownloadProblem? = null,
) {
    val available: Boolean get() = status == QuranPackStatus.Available

    /** A download was asked for and has not ended. */
    val active: Boolean get() = status == QuranPackStatus.Pending || status == QuranPackStatus.Downloading ||
        status == QuranPackStatus.Transferring || status == QuranPackStatus.WaitingForWifi ||
        status == QuranPackStatus.NeedsConfirmation

    companion object {
        val Missing = QuranPackState(QuranPackStatus.Missing)
        val Available = QuranPackState(QuranPackStatus.Available)
    }
}

/** Where the packs of this install come from: Play Asset Delivery, or the quran-cdn Worker. */
interface QuranPackSource {
    /** Every pack's latest known state by name; a pack absent from the map is missing. */
    val states: StateFlow<Map<String, QuranPackState>>

    /** The folder whose relative paths are the pack's asset paths, or null when it is not on this device. IO thread. */
    fun directory(pack: QuranPack): File?

    /**
     * Starts or resumes downloading [packs]. Only while the app is in the foreground: Play refuses
     * background requests. [allowMetered] lets the download use mobile data without waiting for Wi-Fi.
     */
    fun fetch(packs: List<QuranPack>, allowMetered: Boolean)

    /** Asks to download [packs] over mobile data after all; Play shows its own confirmation. */
    fun confirmMobileData(packs: List<QuranPack>, launcher: ActivityResultLauncher<IntentSenderRequest>)

    fun remove(packs: List<QuranPack>)

    /** Re-reads what is on the device, e.g. when the reader comes back into view. */
    fun refresh()
}

package com.tunisianprayertimes.quran.assets

import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.workDataOf
import com.google.android.play.core.assetpacks.model.AssetPackErrorCode
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class QuranPackStatesTest {
    @Test
    fun playStatesMapToWhatTheReaderShows() {
        assertEquals(QuranPackState(QuranPackStatus.Available, 50, 50), playPackState(AssetPackStatus.COMPLETED, 0, 50, 50))
        assertEquals(QuranPackState(QuranPackStatus.Downloading, 20, 50), playPackState(AssetPackStatus.DOWNLOADING, 0, 20, 50))
        // Play has the whole pack; it is only unpacked now, so progress stays at 100%.
        assertEquals(QuranPackState(QuranPackStatus.Transferring, 50, 50), playPackState(AssetPackStatus.TRANSFERRING, 0, 50, 50))
        assertEquals(QuranPackStatus.WaitingForWifi, playPackState(AssetPackStatus.WAITING_FOR_WIFI, 0, 0, 300).status)
        assertEquals(QuranPackStatus.NeedsConfirmation, playPackState(AssetPackStatus.REQUIRES_USER_CONFIRMATION, 0, 0, 300).status)
        for (absent in listOf(AssetPackStatus.NOT_INSTALLED, AssetPackStatus.CANCELED, AssetPackStatus.UNKNOWN)) {
            assertEquals(QuranPackStatus.Missing, playPackState(absent, 0, 0, 50).status)
        }
        val failed = playPackState(AssetPackStatus.FAILED, AssetPackErrorCode.INSUFFICIENT_STORAGE, 10, 50)
        assertEquals(QuranPackState(QuranPackStatus.Failed, 10, 50, QuranDownloadProblem.Storage), failed)
    }

    @Test
    fun playErrorsSayWhatTheListenerCanDo() {
        assertEquals(QuranDownloadProblem.Network, playProblem(AssetPackErrorCode.NETWORK_ERROR))
        assertEquals(QuranDownloadProblem.Storage, playProblem(AssetPackErrorCode.INSUFFICIENT_STORAGE))
        assertEquals(QuranDownloadProblem.Store, playProblem(AssetPackErrorCode.APP_NOT_OWNED))
        assertEquals(QuranDownloadProblem.Other, playProblem(AssetPackErrorCode.INTERNAL_ERROR))
        // Only errors that Play will never get past send the install to the quran-cdn Worker.
        assertTrue(playUnavailable(AssetPackErrorCode.API_NOT_AVAILABLE))
        assertTrue(playUnavailable(AssetPackErrorCode.UNRECOGNIZED_INSTALLATION))
        assertFalse(playUnavailable(AssetPackErrorCode.NETWORK_ERROR))
        assertFalse(playUnavailable(AssetPackErrorCode.NO_ERROR))
    }

    private fun work(state: WorkInfo.State, network: NetworkType = NetworkType.CONNECTED, progress: Map<String, Any> = emptyMap(), output: Map<String, Any> = emptyMap()) =
        WorkInfo(
            UUID.randomUUID(), state, setOf("quran-pack"),
            workDataOf(*output.toList().toTypedArray()), workDataOf(*progress.toList().toTypedArray()),
            0, 0, Constraints.Builder().setRequiredNetworkType(network).build(),
        )

    @Test
    fun downloadWorkMapsToWhatTheReaderShows() {
        assertEquals(QuranPackState.Available, cdnPackState(installed = true, work = work(WorkInfo.State.FAILED), archiveBytes = 90, metered = true))
        assertEquals(QuranPackState(QuranPackStatus.Missing, 0, 90), cdnPackState(false, null, 90, false))
        assertEquals(QuranPackState(QuranPackStatus.Downloading, 30, 90),
            cdnPackState(false, work(WorkInfo.State.RUNNING, progress = mapOf(QuranPackDownloadWorker.KEY_BYTES to 30L)), 90, false))
        assertEquals(QuranPackStatus.Transferring, cdnPackState(false, work(WorkInfo.State.RUNNING,
            progress = mapOf(QuranPackDownloadWorker.KEY_PHASE to QuranPackDownloadWorker.PHASE_INSTALL)), 90, false).status)
        // Waiting for Wi-Fi only while on a metered network; otherwise it is about to start.
        assertEquals(QuranPackStatus.WaitingForWifi, cdnPackState(false, work(WorkInfo.State.ENQUEUED, NetworkType.UNMETERED), 90, metered = true).status)
        assertEquals(QuranPackStatus.Pending, cdnPackState(false, work(WorkInfo.State.ENQUEUED, NetworkType.UNMETERED), 90, metered = false).status)
        assertEquals(QuranPackStatus.Pending, cdnPackState(false, work(WorkInfo.State.ENQUEUED), 90, metered = true).status)
        assertEquals(QuranPackState(QuranPackStatus.Failed, 0, 90, QuranDownloadProblem.Integrity),
            cdnPackState(false, work(WorkInfo.State.FAILED, output = mapOf(QuranPackDownloadWorker.KEY_PROBLEM to "Integrity")), 90, false))
        // Finished work whose folder is gone was deleted since.
        assertEquals(QuranPackStatus.Missing, cdnPackState(false, work(WorkInfo.State.SUCCEEDED), 90, false).status)
        assertEquals(QuranPackStatus.Missing, cdnPackState(false, work(WorkInfo.State.CANCELLED), 90, false).status)
    }
}

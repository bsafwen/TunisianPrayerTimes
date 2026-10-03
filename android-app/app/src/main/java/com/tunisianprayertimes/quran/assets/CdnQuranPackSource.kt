package com.tunisianprayertimes.quran.assets

import android.content.Context
import android.net.ConnectivityManager
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/** Packs downloaded from the quran-cdn Worker, for installs that did not come from Google Play. */
internal class CdnQuranPackSource(
    private val context: Context,
    private val layout: QuranPackLayout,
    private val scope: CoroutineScope,
) : QuranPackSource {
    private val installer = QuranPackInstaller(QuranAssets.cdnRoot(context))
    private val workManager = WorkManager.getInstance(context)
    // Bumped when files change outside WorkManager, e.g. after remove().
    private val rescans = MutableStateFlow(0)

    override val states: StateFlow<Map<String, QuranPackState>> =
        combine(listOf(rescans) + layout.packs.map { workManager.getWorkInfosForUniqueWorkFlow(workName(it)) }) { values ->
            val metered = isMetered()
            layout.packs.withIndex().associate { (index, pack) ->
                @Suppress("UNCHECKED_CAST")
                val work = (values[index + 1] as List<WorkInfo>).lastOrNull()
                pack.name to cdnPackState(installer.isInstalled(pack), work, pack.archive.bytes, metered)
            }
        }.flowOn(Dispatchers.IO).stateIn(scope, SharingStarted.Eagerly, emptyMap())

    init {
        scope.launch(Dispatchers.IO) {
            installer.removeStale(layout)
            rescans.value++
        }
    }

    override fun directory(pack: QuranPack): File? = installer.installedDirectory(pack).takeIf { it.isDirectory }

    override fun fetch(packs: List<QuranPack>, allowMetered: Boolean) {
        packs.forEach { pack ->
            val current = states.value[pack.name]
            if (current?.available == true) return@forEach
            val request = OneTimeWorkRequestBuilder<QuranPackDownloadWorker>()
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED)
                    .build())
                .setInputData(workDataOf(QuranPackDownloadWorker.KEY_PACK to pack.name))
                .addTag(TAG)
                .build()
            // Allowing mobile data replaces a download that waits for Wi-Fi; otherwise a running one goes on.
            val policy = if (allowMetered && current?.status == QuranPackStatus.WaitingForWifi) ExistingWorkPolicy.REPLACE
                else ExistingWorkPolicy.KEEP
            workManager.enqueueUniqueWork(workName(pack), policy, request)
        }
    }

    override fun confirmMobileData(packs: List<QuranPack>, launcher: ActivityResultLauncher<IntentSenderRequest>) =
        fetch(packs, allowMetered = true)

    override fun remove(packs: List<QuranPack>) {
        packs.forEach { workManager.cancelUniqueWork(workName(it)) }
        scope.launch(Dispatchers.IO) {
            packs.forEach(installer::remove)
            rescans.value++
        }
    }

    override fun refresh() {
        rescans.value++
    }

    private fun isMetered(): Boolean =
        context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true

    private companion object {
        const val TAG = "quran-pack"
        fun workName(pack: QuranPack) = "quran-pack-${pack.name}"
    }
}

/** What the reader shows for one pack, from its folder on disk and its latest download work. */
internal fun cdnPackState(installed: Boolean, work: WorkInfo?, archiveBytes: Long, metered: Boolean): QuranPackState {
    if (installed) return QuranPackState.Available
    val bytes = work?.progress?.getLong(QuranPackDownloadWorker.KEY_BYTES, 0L) ?: 0L
    return when (work?.state) {
        WorkInfo.State.RUNNING ->
            if (work.progress.getString(QuranPackDownloadWorker.KEY_PHASE) == QuranPackDownloadWorker.PHASE_INSTALL) {
                QuranPackState(QuranPackStatus.Transferring, archiveBytes, archiveBytes)
            } else QuranPackState(QuranPackStatus.Downloading, bytes, archiveBytes)
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
            if (work.constraints.requiredNetworkType == NetworkType.UNMETERED && metered) {
                QuranPackState(QuranPackStatus.WaitingForWifi, 0L, archiveBytes)
            } else QuranPackState(QuranPackStatus.Pending, 0L, archiveBytes)
        WorkInfo.State.FAILED -> QuranPackState(
            QuranPackStatus.Failed, 0L, archiveBytes,
            work.outputData.getString(QuranPackDownloadWorker.KEY_PROBLEM)
                ?.let { name -> QuranDownloadProblem.entries.firstOrNull { it.name == name } } ?: QuranDownloadProblem.Other,
        )
        // Succeeded work whose folder is gone was removed; cancelled work was removed or replaced.
        else -> QuranPackState(QuranPackStatus.Missing, 0L, archiveBytes)
    }
}

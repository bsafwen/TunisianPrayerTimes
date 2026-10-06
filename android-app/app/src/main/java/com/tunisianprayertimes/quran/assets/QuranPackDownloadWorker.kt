package com.tunisianprayertimes.quran.assets

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.tunisianprayertimes.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/** Downloads one pack from the quran-cdn Worker, for installs that did not come from Google Play. */
class QuranPackDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val name = inputData.getString(KEY_PACK) ?: return Result.failure()
        val layout = withContext(Dispatchers.IO) { QuranPackLayout.load(applicationContext) } ?: return Result.failure()
        val pack = layout.packs.firstOrNull { it.name == name } ?: return Result.failure()
        val installer = QuranPackInstaller(QuranAssets.cdnRoot(applicationContext))
        if (withContext(Dispatchers.IO) { installer.isInstalled(pack) }) return Result.success()
        // Longer than WorkManager's ten minutes on a slow connection: keep it in the foreground when allowed.
        // Android refuses that to work started in the background; the download then goes on without a
        // notification, since an ongoing one of its own would outlive the work.
        val foreground = try {
            setForeground(foregroundInfo(pack, 0L))
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Downloading ${pack.name} without a foreground notification", error)
            false
        }
        val downloaded = AtomicLong(0L)
        return try {
            coroutineScope {
                val reporter = launch {
                    var reported = -1L
                    while (isActive) {
                        val bytes = downloaded.get()
                        if (bytes != reported) {
                            reported = bytes
                            setProgress(workDataOf(KEY_BYTES to bytes, KEY_PHASE to PHASE_DOWNLOAD))
                            if (foreground) notify(pack, bytes)
                        }
                        delay(PROGRESS_INTERVAL_MS)
                    }
                }
                // Interruptible: cancelling the work stops the blocking download between buffers.
                val archive = runInterruptible(Dispatchers.IO) {
                    installer.download(layout.archiveUrl(pack), pack) { downloaded.set(it) }
                }
                reporter.cancel()
                setProgress(workDataOf(KEY_BYTES to pack.archive.bytes, KEY_PHASE to PHASE_INSTALL))
                runInterruptible(Dispatchers.IO) { installer.install(archive, pack) }
            }
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: QuranPackException) {
            Log.w(TAG, "Could not download ${pack.name}", error)
            if (error.problem == QuranDownloadProblem.Network && runAttemptCount < NETWORK_RETRIES) Result.retry()
            else Result.failure(workDataOf(KEY_PROBLEM to error.problem.name))
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val name = inputData.getString(KEY_PACK).orEmpty()
        val pack = QuranPackLayout.load(applicationContext)?.packs?.firstOrNull { it.name == name }
        return foregroundInfo(pack, 0L)
    }

    private fun notify(pack: QuranPack, bytes: Long) {
        runCatching {
            applicationContext.getSystemService(NotificationManager::class.java).notify(notificationId(pack.name), notification(pack, bytes))
        }
    }

    private fun foregroundInfo(pack: QuranPack?, bytes: Long): ForegroundInfo {
        val id = notificationId(pack?.name.orEmpty())
        val notification = notification(pack, bytes)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(id, notification)
    }

    private fun notification(pack: QuranPack?, bytes: Long): Notification {
        val notifications = applicationContext.getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL_ID, "تنزيل القرآن", NotificationManager.IMPORTANCE_LOW).apply {
            description = "تنزيل صفحات المصحف والتلاوات"
            setSound(null, null)
            enableVibration(false)
        })
        val total = pack?.archive?.bytes ?: 0L
        val percent = if (total > 0L) (bytes * 100 / total).toInt().coerceIn(0, 100) else 0
        return Notification.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tab_quran)
            .setContentTitle(if (pack?.kind == QuranPack.Kind.Pages) "تنزيل صفحات المصحف" else "تنزيل التلاوة")
            .setProgress(100, percent, total == 0L)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .build()
    }

    companion object {
        const val KEY_PACK = "pack"
        const val KEY_BYTES = "bytes"
        const val KEY_PHASE = "phase"
        const val KEY_PROBLEM = "problem"
        const val PHASE_DOWNLOAD = "download"
        const val PHASE_INSTALL = "install"
        private const val TAG = "QuranPacks"
        private const val CHANNEL_ID = "quran_downloads"
        private const val NETWORK_RETRIES = 2
        private const val PROGRESS_INTERVAL_MS = 500L

        private fun notificationId(pack: String) = 741_000 + (pack.hashCode() and 0xFFF)
    }
}

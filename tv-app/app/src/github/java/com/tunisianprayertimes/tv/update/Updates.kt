package com.tunisianprayertimes.tv.update

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.tunisianprayertimes.tv.MainActivity
import com.tunisianprayertimes.tv.kiosk.CrashLoopGuard
import com.tunisianprayertimes.tv.kiosk.EventLog
import com.tunisianprayertimes.tv.kiosk.KioskStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** The GitHub build: updates itself from the TV releases on GitHub. */
object Updates {
    fun create(context: Context, log: EventLog, refusal: () -> String? = { null }): AppUpdater = GithubUpdater(context, log, refusal)

    /**
     * The app keeps crashing: looks for a fixed release outside the display, in a job that waits for
     * the network and may run for minutes (a download on a slow link). One at a time.
     */
    fun scheduleRescue(context: Context) {
        runCatching {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (scheduler.getPendingJob(RESCUE_JOB_ID) != null) return
            scheduler.schedule(
                JobInfo.Builder(RESCUE_JOB_ID, ComponentName(context, UpdateRescueJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .build(),
            )
        }.onFailure { Log.w(GithubUpdater.TAG, "rescue job", it) }
    }

    private const val RESCUE_JOB_ID = 7301
}

/** Runs [GithubUpdater.rescue] for [Updates.scheduleRescue]. */
class UpdateRescueJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        work = scope.launch {
            val store = KioskStore(this@UpdateRescueJob)
            // The job may start long after the watchdog asked for it, and a download takes minutes: a display
            // that works again gets its update the normal way, never over a prayer.
            val refusal = {
                val broken = CrashLoopGuard().needsRescue(store.crashes, SystemClock.elapsedRealtime(), store.packageReplacedAt, MainActivity.inFront)
                if (broken) null else "الشاشة تعمل من جديد: يُثبَّت التحديث في موعده"
            }
            if (refusal() == null) {
                runCatching { GithubUpdater(this@UpdateRescueJob, store.eventLog, refusal).rescue(online = true) }
                    .onFailure { Log.w(GithubUpdater.TAG, "rescue", it) }
            }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        return false
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

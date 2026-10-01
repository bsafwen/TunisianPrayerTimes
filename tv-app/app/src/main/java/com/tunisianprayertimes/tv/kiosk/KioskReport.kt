package com.tunisianprayertimes.tv.kiosk

import android.content.Context
import android.os.SystemClock
import com.tunisianprayertimes.tv.BuildConfig

/** Everything the kiosk page shows, read once when it opens. */
data class KioskReport(
    val packageName: String,
    val versionName: String,
    val autoStart: AutoStart,
    val canDrawOverlays: Boolean,
    val homeModeEnabled: Boolean,
    val isDefaultHome: Boolean,
    val isDeviceOwner: Boolean,
    /** The quick-start service: in this build (GitHub), on, and whether the app may turn it on itself. */
    val quickStartAvailable: Boolean = false,
    val quickStartEnabled: Boolean = false,
    /** Bound by the system right now (on but not running: the box does not run it). */
    val quickStartRunning: Boolean = false,
    val canWriteSecureSettings: Boolean = false,
    /** Fire TV's own sleep timer (0 = never), null elsewhere. */
    val fireTvSleepMillis: Long? = null,
    val lastBootTiming: EventEntry? = null,
    /** The other build (Play or GitHub) is installed too: each would bring its own display back over the other's. */
    val otherBuildInstalled: Boolean = false,
    val power: PowerStatus,
    val safeMode: Boolean,
    val uptimeMillis: Long,
    val lastAutoStart: EventEntry?,
    val lastCrash: EventEntry?,
    val sleepGaps: List<EventEntry>,
    val events: List<EventEntry>,
) {
    companion object {
        fun collect(context: Context, store: KioskStore, safeMode: Boolean, processStartElapsed: Long): KioskReport {
            // One read of the log for the whole page: the rows below are all taken from it.
            val log = LogView(store.eventLog.recent(Int.MAX_VALUE), System.currentTimeMillis())
            val autoStart = KioskController.autoStart(context)
            return KioskReport(
                packageName = context.packageName,
                versionName = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty(),
                autoStart = autoStart,
                canDrawOverlays = KioskController.canDrawOverlays(context),
                homeModeEnabled = KioskController.isHomeModeEnabled(context),
                isDefaultHome = KioskController.isDefaultHome(context),
                isDeviceOwner = KioskController.isDeviceOwner(context),
                quickStartAvailable = KioskAccessibility.isAvailable(context),
                quickStartEnabled = KioskAccessibility.isEnabled(context),
                quickStartRunning = KioskAccessibility.serviceConnected,
                canWriteSecureSettings = KioskAccessibility.canWriteSecureSettings(context),
                fireTvSleepMillis = if (autoStart.fireTv) KioskAccessibility.fireTvSleepMillis(context) else null,
                lastBootTiming = log.lastBootTiming,
                otherBuildInstalled = runCatching { context.packageManager.getPackageInfo(BuildConfig.OTHER_BUILD, 0) }.isSuccess,
                power = KioskController.power(context),
                safeMode = safeMode,
                uptimeMillis = SystemClock.elapsedRealtime() - processStartElapsed,
                lastAutoStart = log.lastAutoStart,
                lastCrash = log.lastCrash,
                sleepGaps = log.sleepGaps,
                events = log.events,
            )
        }
    }
}

/**
 * What the page takes from the log ([all], newest first). A crash or a sleep older than a week no
 * longer warns: it stays in the event list only, so an old problem does not hide a new one.
 */
internal class LogView(all: List<EventEntry>, private val nowMillis: Long) {
    private fun isRecent(entry: EventEntry) = nowMillis - entry.atMillis < PROBLEM_SHOWN_MILLIS

    val events = all.take(EVENTS_SHOWN)
    val lastBootTiming = all.firstOrNull { it.type == KioskEvent.BOOT_TIMING }
    val lastAutoStart = all.firstOrNull { it.type == KioskEvent.AUTOSTART_OK || it.type == KioskEvent.AUTOSTART_BLOCKED }
    val lastCrash = all.firstOrNull { it.type == KioskEvent.CRASH }?.takeIf(::isRecent)
    val sleepGaps = events.filter { it.type == KioskEvent.SLEEP_GAP && isRecent(it) }.take(3)

    private companion object {
        const val EVENTS_SHOWN = 50
        const val PROBLEM_SHOWN_MILLIS = 7 * 24 * 3_600_000L
    }
}

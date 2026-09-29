package com.tunisianprayertimes.tv.kiosk

import android.content.Context
import android.os.SystemClock

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
            val events = store.eventLog.recent(50)
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
                lastBootTiming = store.eventLog.last(KioskEvent.BOOT_TIMING),
                power = KioskController.power(context),
                safeMode = safeMode,
                uptimeMillis = SystemClock.elapsedRealtime() - processStartElapsed,
                lastAutoStart = store.eventLog.last(KioskEvent.AUTOSTART_OK, KioskEvent.AUTOSTART_BLOCKED),
                lastCrash = store.eventLog.last(KioskEvent.CRASH),
                sleepGaps = events.filter { it.type == KioskEvent.SLEEP_GAP }.take(3),
                events = events,
            )
        }
    }
}

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
            return KioskReport(
                packageName = context.packageName,
                versionName = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty(),
                autoStart = KioskController.autoStart(context),
                canDrawOverlays = KioskController.canDrawOverlays(context),
                homeModeEnabled = KioskController.isHomeModeEnabled(context),
                isDefaultHome = KioskController.isDefaultHome(context),
                isDeviceOwner = KioskController.isDeviceOwner(context),
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

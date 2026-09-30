package com.tunisianprayertimes.tv.kiosk

import android.app.admin.DeviceAdminReceiver
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import com.tunisianprayertimes.tv.MainActivity
import com.tunisianprayertimes.tv.data.PrefsManager
import com.tunisianprayertimes.tv.update.Updates

/** The watchdog tick and the after-boot check, both from AlarmManager. Not exported. */
class KioskAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = KioskStore(context)
        val now = SystemClock.elapsedRealtime()
        when (intent.action) {
            KioskController.ACTION_WATCHDOG -> {
                val autoStart = KioskController.autoStart(context)
                val guard = CrashLoopGuard()
                val crashes = store.crashes
                // A release that crashes at start never lives long enough to update itself: look from here.
                if (guard.needsRescue(crashes, now, store.packageReplacedAt, MainActivity.inFront)) Updates.scheduleRescue(context)
                if (guard.isGivingUp(crashes, now)) return
                val interactive = runCatching { context.getSystemService(PowerManager::class.java).isInteractive }.getOrDefault(true)
                if (ForegroundWatchdogPolicy.shouldRefront(now, store.state, autoStart, PrefsManager(context).isSetupDone, MainActivity.inFront, interactive)) {
                    store.update { it.copy(refrontedAt = now) }
                    val started = KioskController.bringToFront(context)
                    store.eventLog.append(KioskEvent.WATCHDOG_REFRONT, "${autoStart.tier} started=$started")
                }
            }
            // Early after boot the box's home screen gets no grace: the display goes back on top. So does
            // the end of the admin's time in the system's pages, before a prayer.
            KioskController.ACTION_BOOT_REFRONT -> refront(context, store, now, "boot")
            KioskController.ACTION_AWAY_END -> refront(context, store, now, "away")
            KioskController.ACTION_AUTOSTART_CHECK -> {
                val resumed = store.state.resumedAt
                val tier = KioskController.autoStart(context).tier
                val detail = "$tier quickStart=${KioskAccessibility.state(context)} boot=${BootTiming.bootCount(context)}"
                if (resumed != null) {
                    store.eventLog.append(KioskEvent.AUTOSTART_OK, detail)
                } else {
                    store.eventLog.append(KioskEvent.AUTOSTART_BLOCKED, detail)
                }
            }
        }
    }
}

/** The end of the admin's away window, from the app's own timer (the alarm is its fallback). */
internal fun refrontAfterAway(context: Context) = refront(context, KioskStore(context), SystemClock.elapsedRealtime(), "away")

/** The display back on top, when it is off the screen and no away window of the admin holds it. */
private fun refront(context: Context, store: KioskStore, now: Long, why: String) {
    val autoStart = KioskController.autoStart(context)
    val away = store.state.adminAwayUntil
    if (!MainActivity.inFront && autoStart.canBringToFront && PrefsManager(context).isSetupDone && (away == null || now >= away)) {
        val started = KioskController.bringToFront(context)
        store.eventLog.append(KioskEvent.WATCHDOG_REFRONT, "$why ${autoStart.tier} started=$started")
    }
}

/**
 * Lets the app pin itself with lock task on a box an installer provisioned as device owner:
 * adb shell dpm set-device-owner <package>/com.tunisianprayertimes.tv.kiosk.TvDeviceAdminReceiver
 */
class TvDeviceAdminReceiver : DeviceAdminReceiver()

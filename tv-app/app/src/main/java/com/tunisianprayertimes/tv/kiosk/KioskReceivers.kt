package com.tunisianprayertimes.tv.kiosk

import android.app.admin.DeviceAdminReceiver
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.tunisianprayertimes.tv.MainActivity
import com.tunisianprayertimes.tv.data.PrefsManager

/** The watchdog tick and the after-boot check, both from AlarmManager. Not exported. */
class KioskAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = KioskStore(context)
        val now = SystemClock.elapsedRealtime()
        when (intent.action) {
            KioskController.ACTION_WATCHDOG -> {
                val autoStart = KioskController.autoStart(context)
                if (CrashLoopGuard().isGivingUp(store.crashes, now)) return
                if (ForegroundWatchdogPolicy.shouldRefront(now, store.state, autoStart, PrefsManager(context).isSetupDone, MainActivity.inFront)) {
                    store.update { it.copy(refrontedAt = now) }
                    val started = KioskController.bringToFront(context)
                    store.eventLog.append(KioskEvent.WATCHDOG_REFRONT, "${autoStart.tier} started=$started")
                }
            }
            KioskController.ACTION_BOOT_REFRONT -> {
                // Early after boot the box's home screen gets no grace: the display goes back on top.
                val autoStart = KioskController.autoStart(context)
                val away = store.state.adminAwayUntil
                if (!MainActivity.inFront && autoStart.canBringToFront && PrefsManager(context).isSetupDone && (away == null || now >= away)) {
                    val started = KioskController.bringToFront(context)
                    store.eventLog.append(KioskEvent.WATCHDOG_REFRONT, "boot ${autoStart.tier} started=$started")
                }
            }
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

/**
 * Lets the app pin itself with lock task on a box an installer provisioned as device owner:
 * adb shell dpm set-device-owner <package>/com.tunisianprayertimes.tv.kiosk.TvDeviceAdminReceiver
 */
class TvDeviceAdminReceiver : DeviceAdminReceiver()

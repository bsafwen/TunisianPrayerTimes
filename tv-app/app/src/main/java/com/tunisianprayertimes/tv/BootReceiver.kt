package com.tunisianprayertimes.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.tunisianprayertimes.tv.kiosk.AutoStartTier
import com.tunisianprayertimes.tv.kiosk.KioskController
import com.tunisianprayertimes.tv.kiosk.KioskEvent
import com.tunisianprayertimes.tv.kiosk.KioskStore

/**
 * Brings the display up after a boot (some boxes send the QUICKBOOT variants) or an app update.
 * As the home app the system starts it anyway; otherwise the start works only where the box allows
 * background starts, so a check 90 s later records whether the display really appeared.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in ACTIONS) return
        // The QUICKBOOT actions are not protected: any app could send them. Only a real boot counts.
        if (action in QUICKBOOT && SystemClock.elapsedRealtime() > QUICKBOOT_WINDOW_MILLIS) return
        val store = KioskStore(context)
        val autoStart = KioskController.autoStart(context)
        if (action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            store.eventLog.append(KioskEvent.BOOT, "${autoStart.tier}")
            KioskController.scheduleAutoStartCheck(context)
        }
        KioskController.armWatchdog(context)
        if (autoStart.tier != AutoStartTier.HOME) KioskController.bringToFront(context)
    }

    private companion object {
        val QUICKBOOT = setOf("android.intent.action.QUICKBOOT_POWERON", "com.htc.intent.action.QUICKBOOT_POWERON")
        val ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED) + QUICKBOOT
        const val QUICKBOOT_WINDOW_MILLIS = 10 * 60_000L
    }
}

package com.tunisianprayertimes.tv

import android.app.Application
import com.tunisianprayertimes.platform.SharedAndroid
import com.tunisianprayertimes.tv.kiosk.KioskController
import com.tunisianprayertimes.tv.kiosk.KioskCrashHandler
import com.tunisianprayertimes.tv.kiosk.KioskStore

/**
 * Installs the crash recovery first, so even a failing start is recorded and restarted, then starts
 * the shared module (preferences, official Ramadan/Eid dates, prayer-time and location loaders).
 */
class TvApplication : Application() {

    val kiosk: KioskStore by lazy { KioskStore(this) }

    override fun onCreate() {
        super.onCreate()
        KioskCrashHandler.install(this, kiosk)
        SharedAndroid.init(this)
        KioskController.armWatchdog(this)
    }
}

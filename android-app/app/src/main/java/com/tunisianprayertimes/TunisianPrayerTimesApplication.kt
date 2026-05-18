package com.tunisianprayertimes

import android.app.Application
import com.tunisianprayertimes.platform.GouvernoratLoader
import com.tunisianprayertimes.platform.PrayerDataLoader
import com.tunisianprayertimes.platform.Preferences
import com.tunisianprayertimes.platform.SilenceController
import com.tunisianprayertimes.platform.TimerScheduler

class TunisianPrayerTimesApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Preferences.init(this)
        PrayerDataLoader.init(this)
        GouvernoratLoader.init(this)
        SilenceController.init(this)
        TimerScheduler.init(this)
    }
}
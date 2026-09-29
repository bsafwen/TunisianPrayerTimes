package com.tunisianprayertimes.tv

import android.app.Application
import com.tunisianprayertimes.RamadanOverrideChecker
import com.tunisianprayertimes.platform.GouvernoratLoader
import com.tunisianprayertimes.platform.PrayerDataLoader
import com.tunisianprayertimes.platform.Preferences

/** Initializes the shared module's Android singletons before any screen uses them. */
class TvApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Preferences.init(this)
        PrayerDataLoader.init(this)
        GouvernoratLoader.init(this)
        // Read the saved Ramadan/Eid dates before anything asks whether today is Ramadan.
        RamadanOverrideChecker.loadCachedOverrideIfNeeded()
    }
}

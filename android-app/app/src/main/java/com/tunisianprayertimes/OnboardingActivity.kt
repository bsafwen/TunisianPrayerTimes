package com.tunisianprayertimes

import android.content.Context
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import com.tunisianprayertimes.ui.OnboardingScreen
import com.tunisianprayertimes.ui.theme.TunisianPrayerTimesTheme
import java.util.Locale

class OnboardingActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        val locale = Locale.forLanguageTag("ar-TN-u-nu-latn")
        Locale.setDefault(locale)
        val config = newBase.resources.configuration.apply { setLocale(locale) }
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AnalyticsTracker.installRamadanOverrideReporter(this)
        setContent {
            TunisianPrayerTimesTheme {
                OnboardingScreen(onFinish = {
                    PrefsManager.markFirstLaunchDone(this@OnboardingActivity)
                    finish()
                })
            }
        }
    }
}

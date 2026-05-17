package com.tunisianprayertimes

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import com.tunisianprayertimes.ui.MainScreen
import com.tunisianprayertimes.ui.theme.TunisianPrayerTimesTheme
import com.tunisianprayertimes.wake.WakeAlarmQueueHolder
import com.tunisianprayertimes.wake.WakeAlertActivity
import java.util.Locale

class MainActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        val locale = Locale.forLanguageTag("ar-TN-u-nu-latn")
        Locale.setDefault(locale)
        val config = newBase.resources.configuration.apply { setLocale(locale) }
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AnalyticsTracker.installRamadanOverrideReporter(this)

        // On first launch, show onboarding tutorial
        if (PrefsManager.isFirstLaunch(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }

        setContent {
            TunisianPrayerTimesTheme {
                MainScreen(activity = this)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (WakeAlarmQueueHolder.queue.current != null) {
            startActivity(
                Intent(this, WakeAlertActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
    }
}

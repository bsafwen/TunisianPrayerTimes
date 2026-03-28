package com.tunisianprayertimes

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import com.tunisianprayertimes.ui.MainScreen
import com.tunisianprayertimes.ui.theme.TunisianPrayerTimesTheme

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // On first launch, show onboarding tutorial
        if (PrefsManager.isFirstLaunch(this)) {
            PrefsManager.markFirstLaunchDone(this)
            startActivity(Intent(this, OnboardingActivity::class.java))
        }

        setContent {
            TunisianPrayerTimesTheme {
                MainScreen(activity = this)
            }
        }
    }
}

package com.tunisianprayertimes

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import com.tunisianprayertimes.ui.OnboardingScreen
import com.tunisianprayertimes.ui.theme.TunisianPrayerTimesTheme

class OnboardingActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TunisianPrayerTimesTheme {
                OnboardingScreen(onFinish = { finish() })
            }
        }
    }
}

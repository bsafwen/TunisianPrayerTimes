package com.tunisianprayertimes

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tunisianprayertimes.ui.MainScreen
import com.tunisianprayertimes.ui.theme.TunisianPrayerTimesTheme
import com.tunisianprayertimes.wake.WakeAlarmQueueHolder
import com.tunisianprayertimes.wake.WakeAlertActivity
import java.util.Locale

object MainTabNavigation {
    const val EXTRA_DESTINATION = "com.tunisianprayertimes.extra.MAIN_DESTINATION"
    const val DESTINATION_PRAYERS = "prayers"
    const val DESTINATION_ALARMS = "alarms"
    const val DESTINATION_QIBLA = "qibla"
}

class MainActivity : AppCompatActivity() {

    private data class MainTabRequest(
        val destination: String?,
        val sequence: Int,
    )

    private var tabRequestSequence = 0
    private var tabRequest by mutableStateOf(MainTabRequest(destination = null, sequence = 0))

    override fun attachBaseContext(newBase: Context) {
        val locale = Locale.forLanguageTag("ar-TN-u-nu-latn")
        Locale.setDefault(locale)
        val config = newBase.resources.configuration.apply { setLocale(locale) }
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyTabRequest(intent)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        AnalyticsTracker.installRamadanOverrideReporter(this)

        // On first launch, show onboarding tutorial
        if (PrefsManager.isFirstLaunch(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }

        setContent {
            TunisianPrayerTimesTheme {
                MainScreen(
                    activity = this,
                    requestedDestination = tabRequest.destination,
                    requestedDestinationSequence = tabRequest.sequence,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyTabRequest(intent)
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

    private fun applyTabRequest(intent: Intent?) {
        val destination = intent?.getStringExtra(MainTabNavigation.EXTRA_DESTINATION) ?: return
        tabRequestSequence += 1
        tabRequest = MainTabRequest(destination = destination, sequence = tabRequestSequence)
    }
}

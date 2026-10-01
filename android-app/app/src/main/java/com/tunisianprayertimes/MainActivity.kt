package com.tunisianprayertimes

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.VisibleForTesting
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
    const val DESTINATION_ADHKAR = "adhkar"
    const val EXTRA_DHIKR_REMINDER_ID = "com.tunisianprayertimes.extra.DHIKR_REMINDER_ID"
}

/**
 * Tab requests from launch and notification intents, numbered so each is handled once.
 *
 * The numbering outlives the activity. After a re-creation (process death, "Don't keep
 * activities") the Adhkar tab restores the last request it handled, so a count restarted
 * at zero would make the next reminder tap look already handled and open only the tab.
 */
internal class MainTabRequests {
    data class Request(
        val destination: String?,
        val sequence: Int,
        val dhikrReminderId: String? = null,
        val dhikrOccurrenceId: String? = null,
    )

    private var sequence = 0
    var current by mutableStateOf(Request(destination = null, sequence = 0))
        private set

    fun onCreate(intent: Intent?, savedInstanceState: Bundle?) {
        if (savedInstanceState == null) {
            accept(intent)
            return
        }
        // A re-created activity keeps its original launch intent; replaying it would force
        // that tab again or reopen an old reminder. New taps arrive through onNewIntent.
        // Restore the last request under its own number but without its tab: the Adhkar
        // tab skips it if handled and retries it if the re-creation interrupted it.
        sequence = savedInstanceState.getInt(STATE_SEQUENCE)
        current = Request(
            destination = null,
            sequence = sequence,
            dhikrReminderId = savedInstanceState.getString(STATE_DHIKR_REMINDER_ID),
            dhikrOccurrenceId = savedInstanceState.getString(STATE_DHIKR_OCCURRENCE_ID),
        )
    }

    fun onNewIntent(intent: Intent) = accept(intent)

    fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_SEQUENCE, sequence)
        outState.putString(STATE_DHIKR_REMINDER_ID, current.dhikrReminderId)
        outState.putString(STATE_DHIKR_OCCURRENCE_ID, current.dhikrOccurrenceId)
    }

    private fun accept(intent: Intent?) {
        // Reopening from Recents relaunches with the task's original intent, not a new tap.
        if (intent == null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val destination = intent.getStringExtra(MainTabNavigation.EXTRA_DESTINATION) ?: return
        sequence += 1
        current = Request(
            destination = destination,
            sequence = sequence,
            dhikrReminderId = intent.getStringExtra(MainTabNavigation.EXTRA_DHIKR_REMINDER_ID),
            dhikrOccurrenceId = intent.getStringExtra(com.tunisianprayertimes.adhkar.DhikrReminderScheduler.EXTRA_OCCURRENCE_ID),
        )
    }

    private companion object {
        const val STATE_SEQUENCE = "com.tunisianprayertimes.state.TAB_REQUEST_SEQUENCE"
        const val STATE_DHIKR_REMINDER_ID = "com.tunisianprayertimes.state.TAB_REQUEST_DHIKR_REMINDER_ID"
        const val STATE_DHIKR_OCCURRENCE_ID = "com.tunisianprayertimes.state.TAB_REQUEST_DHIKR_OCCURRENCE_ID"
    }
}

class MainActivity : AppCompatActivity() {

    @VisibleForTesting
    internal val tabRequests = MainTabRequests()

    override fun attachBaseContext(newBase: Context) {
        val locale = Locale.forLanguageTag("ar-TN-u-nu-latn")
        Locale.setDefault(locale)
        val config = newBase.resources.configuration.apply { setLocale(locale) }
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tabRequests.onCreate(intent, savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        AnalyticsTracker.installRamadanOverrideReporter(this)

        // On first launch, show onboarding tutorial
        if (PrefsManager.isFirstLaunch(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }

        setContent {
            val tabRequest = tabRequests.current
            TunisianPrayerTimesTheme {
                MainScreen(
                    activity = this,
                    requestedDestination = tabRequest.destination,
                    requestedDestinationSequence = tabRequest.sequence,
                    requestedDhikrReminderId = tabRequest.dhikrReminderId,
                    requestedDhikrOccurrenceId = tabRequest.dhikrOccurrenceId,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        tabRequests.onNewIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        tabRequests.onSaveInstanceState(outState)
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

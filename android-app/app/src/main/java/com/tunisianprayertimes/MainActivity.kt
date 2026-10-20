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
    const val DESTINATION_QURAN = "quran"
    const val EXTRA_DHIKR_REMINDER_ID = "com.tunisianprayertimes.extra.DHIKR_REMINDER_ID"
    /** What the Quran tab opens: «المراجعة» or its day's review. */
    const val EXTRA_QURAN_ACTION = "com.tunisianprayertimes.extra.QURAN_ACTION"
    const val QURAN_ACTION_HIFZ = "hifz"
    const val QURAN_ACTION_HIFZ_REVIEW = "hifz_review"
    const val QURAN_ACTION_HIFZ_RECORD = "hifz_record"
    /** What the prayers tab opens: the city picker, from the travel notification (6.8). */
    const val EXTRA_PRAYERS_ACTION = "com.tunisianprayertimes.extra.PRAYERS_ACTION"
    const val PRAYERS_ACTION_CHOOSE_PLACE = "choose_place"
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
        val quranAction: String? = null,
        val prayersAction: String? = null,
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
            // The Quran tab skips a hifz request it already handled and retries one a re-creation interrupted.
            quranAction = savedInstanceState.getString(STATE_QURAN_ACTION),
        )
    }

    fun onNewIntent(intent: Intent) = accept(intent)

    fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_SEQUENCE, sequence)
        outState.putString(STATE_DHIKR_REMINDER_ID, current.dhikrReminderId)
        outState.putString(STATE_DHIKR_OCCURRENCE_ID, current.dhikrOccurrenceId)
        outState.putString(STATE_QURAN_ACTION, current.quranAction)
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
            quranAction = intent.getStringExtra(MainTabNavigation.EXTRA_QURAN_ACTION),
            prayersAction = intent.getStringExtra(MainTabNavigation.EXTRA_PRAYERS_ACTION),
        )
    }

    private companion object {
        const val STATE_SEQUENCE = "com.tunisianprayertimes.state.TAB_REQUEST_SEQUENCE"
        const val STATE_DHIKR_REMINDER_ID = "com.tunisianprayertimes.state.TAB_REQUEST_DHIKR_REMINDER_ID"
        const val STATE_DHIKR_OCCURRENCE_ID = "com.tunisianprayertimes.state.TAB_REQUEST_DHIKR_OCCURRENCE_ID"
        const val STATE_QURAN_ACTION = "com.tunisianprayertimes.state.TAB_REQUEST_QURAN_ACTION"
    }
}

/**
 * The launcher icon, tapped while the app's task began with another intent (a notification tap, a shell start).
 * Android does not bring that task back as it was: it puts a fresh activity, open on the prayers tab, over the one
 * the user left. That fresh one closes at once, and the task shows as it was left.
 */
internal fun isLauncherTapOverLeftTask(intent: Intent?, isTaskRoot: Boolean, savedInstanceState: Bundle?): Boolean =
    savedInstanceState == null && !isTaskRoot && intent != null &&
        intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER) &&
        // The launcher shortcuts carry a destination: theirs is a request to follow.
        !intent.hasExtra(MainTabNavigation.EXTRA_DESTINATION)

class MainActivity : AppCompatActivity() {

    @VisibleForTesting
    internal val tabRequests = MainTabRequests()

    override fun attachBaseContext(newBase: Context) {
        val locale = Locale.forLanguageTag("ar-TN-u-nu-latn")
        Locale.setDefault(locale)
        val config = newBase.resources.configuration.apply { setLocale(locale) }
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    /**
     * «سجّل موضعًا» from the launcher: a slip made in prayer or with a teacher is recorded at once; «مراجعة اليوم»: the
     * day's checks, without going through the reader. Dynamic, because a static shortcut cannot follow the debug
     * build's package suffix.
     */
    private fun publishShortcuts() {
        runCatching {
            val manager = getSystemService(android.content.pm.ShortcutManager::class.java) ?: return
            // The launcher's own action and category, so a running task is reused and the request
            // arrives through onNewIntent instead of stacking a second activity.
            fun intent(action: String) = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setClass(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainTabNavigation.EXTRA_DESTINATION, MainTabNavigation.DESTINATION_QURAN)
                .putExtra(MainTabNavigation.EXTRA_QURAN_ACTION, action)
            val icon = android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_tab_quran)
            manager.dynamicShortcuts = listOf(
                android.content.pm.ShortcutInfo.Builder(this, "hifz_record")
                    .setShortLabel("سجّل موضعًا")
                    .setLongLabel("سجّل موضعًا للمراجعة")
                    .setIcon(icon)
                    .setIntent(intent(MainTabNavigation.QURAN_ACTION_HIFZ_RECORD))
                    .setRank(0)
                    .build(),
                // With nothing due, it opens «المراجعة», which says when the next check comes.
                android.content.pm.ShortcutInfo.Builder(this, "hifz_review")
                    .setShortLabel("مراجعة اليوم")
                    .setLongLabel("ابدأ مراجعة اليوم")
                    .setIcon(icon)
                    .setIntent(intent(MainTabNavigation.QURAN_ACTION_HIFZ_REVIEW))
                    .setRank(1)
                    .build(),
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isLauncherTapOverLeftTask(intent, isTaskRoot, savedInstanceState)) {
            finish()
            return
        }
        tabRequests.onCreate(intent, savedInstanceState)
        publishShortcuts()
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
                    requestedQuranAction = tabRequest.quranAction,
                    requestedPrayersAction = tabRequest.prayersAction,
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

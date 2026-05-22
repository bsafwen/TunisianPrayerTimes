package com.tunisianprayertimes

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tunisianprayertimes.testing.MainScreenRobot
import com.tunisianprayertimes.wake.PrayerWakeRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

@RunWith(AndroidJUnit4::class)
class WakeAlarmFunctionalInstrumentedTest {
    private val alarmId = "functional_alarm_fajr"
    private val seededAlarm = PrayerWakeConfig(
        id = alarmId,
        prayer = Prayer.FAJR,
        enabled = true,
        mainAlarm = WakeMainAlarmConfig(
            mode = WakeMainAlarmMode.FIXED_TIME,
            fixedTime = ClockTime(hour = 5, minute = 30),
        ),
        playback = WakePlaybackOptions(awakeCheckEnabled = true),
    )

    private val seedRule = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val repository = PrayerWakeRepository(context)
                context.getSharedPreferences("prayer_silence_prefs", android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("first_launch_done", true)
                    .commit()
                runBlocking { repository.replaceWakeConfigs(listOf(seededAlarm)) }
                try {
                    base.evaluate()
                } finally {
                    runBlocking { repository.clearAllWakeConfigs() }
                }
            }
        }
    }

    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(seedRule).around(composeRule)

    @Test
    fun seededWakeAlarmCanBeDisabledThroughStableAlarmScreenContract() {
        MainScreenRobot(composeRule)
            .openAlarms()
            .assertAlarmListVisible()
            .assertAlarmRowVisible(alarmId)
            .assertAlarmEnabled(alarmId)
            .toggleAlarm(alarmId)
            .assertAlarmDisabled(alarmId)

        val storedAlarm = runBlocking {
            PrayerWakeRepository(composeRule.activity).getWakeAlarm(alarmId)
        }

        assertFalse("Disabling the alarm in UI should persist to the wake repository", storedAlarm?.enabled ?: true)
    }

    @Test
    fun quickAddActionsAreReachableThroughStableAlarmScreenContract() {
        MainScreenRobot(composeRule)
            .openAlarms()
            .assertAlarmRowVisible(alarmId)
            .openQuickAdd()
            .assertQuickAddActionsVisible()
    }
}
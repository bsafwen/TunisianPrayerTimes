package com.tunisianprayertimes

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tunisianprayertimes.testing.MainScreenRobot
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

@RunWith(AndroidJUnit4::class)
class WakeAlarmFunctionalInstrumentedTest {
    private val firstLaunchRule = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                context.getSharedPreferences("prayer_silence_prefs", android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("first_launch_done", true)
                    .commit()
                base.evaluate()
            }
        }
    }

    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(firstLaunchRule).around(composeRule)

    @Test
    fun alarmTabOpensThroughStableMainNavigationContract() {
        MainScreenRobot(composeRule)
            .openAlarms()
    }

    @Test
    fun emptyAlarmPresetActionsAreReachableThroughStableContract() {
        MainScreenRobot(composeRule)
            .openAlarms()
            .assertPresetActionsVisible()
    }
}
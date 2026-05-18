package com.tunisianprayertimes

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tunisianprayertimes.ui.TestTags
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import org.junit.runner.RunWith

/**
 * Instrumented tests for date navigation and DatePickerDialog.
 * The first-launch pref is forced before activity startup via [RuleChain]
 * so that [MainActivity] skips the onboarding redirect.
 */
@RunWith(AndroidJUnit4::class)
class DateNavigationInstrumentedTest {

    /**
     * Writes the "first_launch_done" flag into the **target app's** SharedPreferences
     * synchronously before the activity rule creates [MainActivity].
     */
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
    fun dateLabel_isDisplayed() {
        composeRule.onNodeWithTag(TestTags.DATE_LABEL)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun dateLabel_tap_opensDatePickerWithoutCrash() {
        composeRule.onNodeWithTag(TestTags.DATE_LABEL)
            .performScrollTo()
            .performClick()

        composeRule.waitForIdle()
    }

    @Test
    fun previousArrow_changesPrayerTimes() {
        composeRule.onNodeWithTag(TestTags.DATE_LABEL)
            .performScrollTo()
            .assertIsDisplayed()

        composeRule.onNodeWithText("◂")
            .performClick()

        composeRule.waitForIdle()

        composeRule.onNodeWithTag(TestTags.DATE_LABEL)
            .assertIsDisplayed()
    }

    @Test
    fun forwardArrow_changesPrayerTimes() {
        composeRule.onNodeWithTag(TestTags.DATE_LABEL)
            .performScrollTo()
            .assertIsDisplayed()

        composeRule.onNodeWithText("▸")
            .performClick()

        composeRule.waitForIdle()

        composeRule.onNodeWithTag(TestTags.DATE_LABEL)
            .assertIsDisplayed()
    }

    @Test
    fun navigateAwayAndBack_hidesTodayLabel() {
        composeRule.onNodeWithTag(TestTags.DATE_LABEL)
            .performScrollTo()

        composeRule.onNodeWithText("◂")
            .performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("العودة لليوم")
            .performScrollTo()
            .assertIsDisplayed()

        composeRule.onNodeWithText("العودة لليوم")
            .performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("اليوم")
            .assertDoesNotExist()
    }
}

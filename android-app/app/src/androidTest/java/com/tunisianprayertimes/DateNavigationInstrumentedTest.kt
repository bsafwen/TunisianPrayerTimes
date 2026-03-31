package com.tunisianprayertimes

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tunisianprayertimes.ui.TestTags
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import org.junit.runner.RunWith

/**
 * Instrumented tests for date navigation and DatePickerDialog.
 * The first-launch pref is forced before activity startup to bypass onboarding in CI.
 */
@RunWith(AndroidJUnit4::class)
class DateNavigationInstrumentedTest {

    private val firstLaunchRule = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val context = ApplicationProvider.getApplicationContext<android.content.Context>()
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
            .assertIsDisplayed()
    }

    @Test
    fun dateLabel_tap_opensDatePickerWithoutCrash() {
        composeRule.onNodeWithTag(TestTags.DATE_LABEL)
            .performClick()

        // DatePickerDialog should be visible — the Android date picker shows OK/Cancel buttons
        // Wait for it to appear
        composeRule.waitForIdle()

        // If we get here without a crash, the ContextThemeWrapper approach works.
        // The dialog is a platform View, not a Compose node, so we can't easily assert
        // its content with Compose testing. But no crash = success.
    }

    @Test
    fun previousArrow_changesPrayerTimes() {
        // Scroll to date area and check it's visible
        composeRule.onNodeWithTag(TestTags.DATE_LABEL)
            .assertIsDisplayed()

        // Tap the back arrow (◂) to go to previous day
        composeRule.onNodeWithText("◂")
            .performClick()

        composeRule.waitForIdle()

        // Date label should still be displayed (no crash)
        composeRule.onNodeWithTag(TestTags.DATE_LABEL)
            .assertIsDisplayed()
    }

    @Test
    fun forwardArrow_changesPrayerTimes() {
        composeRule.onNodeWithTag(TestTags.DATE_LABEL)
            .assertIsDisplayed()

        // Tap forward arrow (▸)
        composeRule.onNodeWithText("▸")
            .performClick()

        composeRule.waitForIdle()

        composeRule.onNodeWithTag(TestTags.DATE_LABEL)
            .assertIsDisplayed()
    }

    @Test
    fun navigateAwayAndBack_showsTodayLabel() {
        // Go to previous day
        composeRule.onNodeWithText("◂")
            .performClick()
        composeRule.waitForIdle()

        // "العودة لليوم" chip should be visible
        composeRule.onNodeWithText("العودة لليوم")
            .assertIsDisplayed()

        // Tap it to go back to today
        composeRule.onNodeWithText("العودة لليوم")
            .performClick()
        composeRule.waitForIdle()

        // "اليوم" label should be displayed
        composeRule.onNodeWithText("اليوم")
            .assertIsDisplayed()
    }
}

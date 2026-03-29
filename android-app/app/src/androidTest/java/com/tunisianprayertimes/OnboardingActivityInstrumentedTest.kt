package com.tunisianprayertimes

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tunisianprayertimes.ui.TestTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for the OnboardingActivity's 6-step flow.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingActivityInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<OnboardingActivity>()

    private fun navigateToStep(step: Int) {
        // Step 4 (permissions) blocks Next if permissions not granted
        repeat(step) {
            composeRule.onNodeWithTag(TestTags.ONBOARDING_NEXT).performClick()
            composeRule.waitForIdle()
        }
    }

    // --- Step 0: Initial state ---

    @Test
    fun initialState_nextButtonIsDisplayed() {
        composeRule.onNodeWithTag(TestTags.ONBOARDING_NEXT).assertIsDisplayed()
    }

    @Test
    fun initialState_prevButtonIsHidden() {
        composeRule.onNodeWithTag(TestTags.ONBOARDING_PREV).assertDoesNotExist()
    }

    @Test
    fun initialState_startButtonIsHidden() {
        composeRule.onNodeWithTag(TestTags.ONBOARDING_START).assertDoesNotExist()
    }

    // --- Step 0 → Step 1 ---

    @Test
    fun clickNext_showsPrevButton() {
        composeRule.onNodeWithTag(TestTags.ONBOARDING_NEXT).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TestTags.ONBOARDING_PREV).assertIsDisplayed()
    }

    @Test
    fun clickNext_nextButtonStillVisible() {
        composeRule.onNodeWithTag(TestTags.ONBOARDING_NEXT).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TestTags.ONBOARDING_NEXT).assertIsDisplayed()
    }

    // --- Step 1 → Step 0 (back) ---

    @Test
    fun clickNextThenPrev_prevButtonHiddenAgain() {
        composeRule.onNodeWithTag(TestTags.ONBOARDING_NEXT).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TestTags.ONBOARDING_PREV).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TestTags.ONBOARDING_PREV).assertDoesNotExist()
    }

    // --- Step 4: Permissions ---

    @Test
    fun step4_nextButtonIsDisabledWithoutPermissions() {
        navigateToStep(4)
        // On a fresh test device without DND permission, Next should be disabled
        composeRule.onNodeWithTag(TestTags.ONBOARDING_NEXT).assertIsNotEnabled()
    }

    // --- Arabic text tests ---

    @Test
    fun nextButtonTextIsArabic() {
        composeRule.onNodeWithText("التالي").assertIsDisplayed()
    }

    @Test
    fun step1_prevButtonTextIsArabic() {
        composeRule.onNodeWithTag(TestTags.ONBOARDING_NEXT).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("السابق").assertIsDisplayed()
    }
}

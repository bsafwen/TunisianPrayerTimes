package com.tunisianprayertimes

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tunisianprayertimes.ui.TestTags
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI integration tests for the overlap validation warning.
 *
 * Modifies duration values through the Compose UI (text input) to trigger
 * or clear overlap warnings.
 */
@RunWith(AndroidJUnit4::class)
class OverlapWarningInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setup() {
        PrefsManager.markFirstLaunchDone(composeRule.activity)
    }

    @Test
    fun defaultDurations_noOverlapWarningDisplayed() {
        composeRule.waitForIdle()

        assert(composeRule.onAllNodesWithTag(TestTags.OVERLAP_WARNING).fetchSemanticsNodes().isEmpty()) {
            "Expected no overlap warning with default durations"
        }
    }

    @Test
    fun excessiveDuration_showsOverlapWarning() {
        // Find Maghrib's duration input by its test tag and type a huge value
        // Maghrib → Isha gap is ~90min, so 999 will definitely overlap
        val maghribDuration = composeRule.onNodeWithTag(TestTags.durationInput(Prayer.MAGHRIB.name))

        maghribDuration
            .performScrollTo()
            .performTextClearance()
        maghribDuration
            .performTextInput("999")

        composeRule.waitForIdle()

        composeRule.onNodeWithTag(TestTags.OVERLAP_WARNING)
            .performScrollTo()
            .assertIsDisplayed()
    }
}

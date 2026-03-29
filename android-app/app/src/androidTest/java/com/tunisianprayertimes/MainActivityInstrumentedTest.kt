package com.tunisianprayertimes

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tunisianprayertimes.ui.TestTags
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for the main screen.
 * Tests UI presence, Arabic localisation, interactions, and the core silence feature.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setup() {
        // Mark first launch done so onboarding doesn't interfere
        PrefsManager.markFirstLaunchDone(composeRule.activity)
    }

    // --- UI presence tests ---

    @Test
    fun statusCardIsDisplayed() {
        composeRule.onNodeWithTag(TestTags.STATUS_CARD).assertIsDisplayed()
    }

    @Test
    fun autoSilenceSwitchIsDisplayed() {
        composeRule.onNodeWithTag(TestTags.AUTO_SILENCE_SWITCH)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun manualSilenceButtonIsDisplayed() {
        composeRule.onNodeWithTag(TestTags.MANUAL_SILENCE_BUTTON)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun infoTextIsDisplayed() {
        composeRule.onNodeWithTag(TestTags.INFO_TEXT)
            .performScrollTo()
            .assertIsDisplayed()
    }

    // --- Arabic localisation tests ---

    @Test
    fun subtitleIsInArabic() {
        composeRule.onNodeWithText("مواقيت الصلاة في تونس").assertIsDisplayed()
    }

    @Test
    fun autoSilenceLabelIsInArabic() {
        composeRule.onNodeWithText("الإسكات التلقائي أثناء الصلاة")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun prayerNamesAreInArabic() {
        val arabicNames = listOf("الفجر", "الظهر", "العصر", "المغرب", "العشاء")
        for (name in arabicNames) {
            composeRule.onNodeWithText(name, substring = true).assertExists()
        }
    }

    // --- Interaction tests ---

    @Test
    fun autoSilenceToggleIsClickable() {
        composeRule.onNodeWithTag(TestTags.AUTO_SILENCE_SWITCH)
            .performScrollTo()
            .assertIsEnabled()
    }

    @Test
    fun silenceButtonIsClickable() {
        composeRule.onNodeWithTag(TestTags.MANUAL_SILENCE_BUTTON)
            .performScrollTo()
            .assertIsEnabled()
    }

    // --- Core feature: manual silence must not be undone by the resume LaunchedEffect ---

    @Test
    fun manualSilenceButton_doesNotGetUndoneByScheduleAll() {
        val context = composeRule.activity
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Skip if DND not granted on this device — can't test silencing without it
        if (!notificationManager.isNotificationPolicyAccessGranted) return

        // Ensure auto-silence is enabled (so scheduleAll would fire on refreshTick)
        PrefsManager.setEnabled(context, true)

        // Start in normal mode
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL

        // Click the manual silence button
        composeRule.onNodeWithTag(TestTags.MANUAL_SILENCE_BUTTON)
            .performScrollTo()
            .performClick()

        // Wait for all Compose effects and recompositions to settle
        composeRule.waitForIdle()

        // The phone MUST still be in silent mode — scheduleAll must NOT have undone this
        val ringerMode = audioManager.ringerMode
        assert(ringerMode == AudioManager.RINGER_MODE_SILENT) {
            "Expected RINGER_MODE_SILENT (0) but got $ringerMode. " +
                    "Manual silence was undone — likely by scheduleAll triggered via refreshTick."
        }

        // Cleanup: restore normal mode
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
    }

    @Test
    fun manualSilenceButton_togglesSilentAndBack() {
        val context = composeRule.activity
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        if (!notificationManager.isNotificationPolicyAccessGranted) return

        // Start normal
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL

        val button = composeRule.onNodeWithTag(TestTags.MANUAL_SILENCE_BUTTON)

        // Click to silence
        button.performScrollTo().performClick()
        composeRule.waitForIdle()

        assert(audioManager.ringerMode == AudioManager.RINGER_MODE_SILENT) {
            "Phone should be SILENT after first click"
        }

        // Click to unsilence
        button.performScrollTo().performClick()
        composeRule.waitForIdle()

        assert(audioManager.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
            "Phone should be NORMAL after second click"
        }
    }
}

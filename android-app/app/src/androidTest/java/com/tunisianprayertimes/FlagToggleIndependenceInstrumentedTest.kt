package com.tunisianprayertimes

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tunisianprayertimes.ui.TestTags
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * Regression tests covering the bug where toggling one preference flag would visually
 * "snap" another flag's switch to a stale value, because the switch's checked state
 * was being read directly from PrefsManager on every recomposition instead of being
 * backed by remembered Compose state.
 *
 * Specifically: AutoLocationCard previously read `PrefsManager.isAutoLocationUpdateEnabled()`
 * on each composition. Toggling the auto-silence or call-end-vibration switches would
 * trigger recomposition and update AutoLocation's UI to whatever value happened to be
 * persisted at that moment — making the user's earlier tap appear to take effect only
 * after touching an unrelated control.
 */
@RunWith(AndroidJUnit4::class)
class FlagToggleIndependenceInstrumentedTest {

    private val prefsRule = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
                PrefsManager.markFirstLaunchDone(ctx)
                PrefsManager.setEnabled(ctx, true)
                PrefsManager.setCallEndVibrationEnabled(ctx, true)
                PrefsManager.setAutoLocationUpdateEnabled(ctx, true)
                base.evaluate()
            }
        }
    }

    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(prefsRule).around(composeRule)

    private fun SemanticsNodeInteraction.performToggle() {
        performScrollTo()
        performSemanticsAction(SemanticsActions.OnClick)
    }

    @Test
    fun togglingAutoLocation_persistsAndUpdatesUiImmediately() {
        val ctx = composeRule.activity
        val switch = composeRule.onNodeWithTag(TestTags.AUTO_LOCATION_SWITCH)

        switch.performScrollTo().assertIsOn()

        switch.performToggle()
        composeRule.waitForIdle()

        switch.assertIsOff()
        assert(!PrefsManager.isAutoLocationUpdateEnabled(ctx)) {
            "Auto-location pref should be false after toggling switch off"
        }

        switch.performToggle()
        composeRule.waitForIdle()

        switch.assertIsOn()
        assert(PrefsManager.isAutoLocationUpdateEnabled(ctx)) {
            "Auto-location pref should be true after toggling switch on"
        }
    }

    @Test
    fun togglingCallEndVibration_persistsAndUpdatesUiImmediately() {
        val ctx = composeRule.activity
        val switch = composeRule.onNodeWithTag(TestTags.CALL_END_VIBRATION_SWITCH)

        switch.performScrollTo().assertIsOn()

        switch.performToggle()
        composeRule.waitForIdle()

        switch.assertIsOff()
        assert(!PrefsManager.isCallEndVibrationEnabled(ctx)) {
            "Call-end-vibration pref should be false after toggling switch off"
        }
    }

    /**
     * Regression: toggling auto-silence must not cause the auto-location switch to
     * change visually or in storage.
     */
    @Test
    fun togglingAutoSilence_doesNotAffectAutoLocationSwitch() {
        val ctx = composeRule.activity

        // Turn auto-location OFF first
        composeRule.onNodeWithTag(TestTags.AUTO_LOCATION_SWITCH)
            .performToggle()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TestTags.AUTO_LOCATION_SWITCH).assertIsOff()
        assert(!PrefsManager.isAutoLocationUpdateEnabled(ctx))

        // Toggle auto-silence (off then on) — must NOT flip auto-location back on
        val autoSilence = composeRule.onNodeWithTag(TestTags.AUTO_SILENCE_SWITCH)
        autoSilence.performToggle()
        composeRule.waitForIdle()
        autoSilence.performToggle()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(TestTags.AUTO_LOCATION_SWITCH).assertIsOff()
        assert(!PrefsManager.isAutoLocationUpdateEnabled(ctx)) {
            "Toggling auto-silence must not change auto-location pref"
        }
    }

    /**
     * Regression: toggling call-end-vibration must not affect the auto-location switch.
     */
    @Test
    fun togglingCallEndVibration_doesNotAffectAutoLocationSwitch() {
        val ctx = composeRule.activity

        composeRule.onNodeWithTag(TestTags.AUTO_LOCATION_SWITCH)
            .performToggle()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TestTags.AUTO_LOCATION_SWITCH).assertIsOff()

        val callVib = composeRule.onNodeWithTag(TestTags.CALL_END_VIBRATION_SWITCH)
        callVib.performToggle()
        composeRule.waitForIdle()
        callVib.performToggle()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(TestTags.AUTO_LOCATION_SWITCH).assertIsOff()
        assert(!PrefsManager.isAutoLocationUpdateEnabled(ctx))
    }

    /**
     * Regression: toggling auto-location must not affect the other two flags.
     */
    @Test
    fun togglingAutoLocation_doesNotAffectOtherSwitches() {
        val ctx = composeRule.activity

        composeRule.onNodeWithTag(TestTags.AUTO_LOCATION_SWITCH)
            .performToggle()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(TestTags.AUTO_SILENCE_SWITCH)
            .performScrollTo()
            .assertIsOn()
        composeRule.onNodeWithTag(TestTags.CALL_END_VIBRATION_SWITCH)
            .performScrollTo()
            .assertIsOn()
        assert(PrefsManager.isEnabled(ctx))
        assert(PrefsManager.isCallEndVibrationEnabled(ctx))
    }
}

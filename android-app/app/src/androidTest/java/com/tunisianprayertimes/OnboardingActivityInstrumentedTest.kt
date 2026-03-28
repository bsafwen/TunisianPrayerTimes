package com.tunisianprayertimes

import android.view.View
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matchers.not
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UI tests for the OnboardingActivity's 6-step flow.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingActivityInstrumentedTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(OnboardingActivity::class.java)

    @Before
    fun setUp() {
        Intents.init()
    }

    @After
    fun tearDown() {
        Intents.release()
    }

    // --- Step 0: Initial state ---

    @Test
    fun initialState_viewFlipperIsDisplayed() {
        onView(withId(R.id.viewFlipper))
            .check(matches(isDisplayed()))
    }

    @Test
    fun initialState_nextButtonIsDisplayed() {
        onView(withId(R.id.btnNext))
            .check(matches(isDisplayed()))
    }

    @Test
    fun initialState_prevButtonIsHidden() {
        onView(withId(R.id.btnPrev))
            .check(matches(not(isDisplayed())))
    }

    @Test
    fun initialState_startButtonIsHidden() {
        onView(withId(R.id.btnStart))
            .check(matches(not(isDisplayed())))
    }

    @Test
    fun initialState_progressBarsAreDisplayed() {
        onView(withId(R.id.progressContainer))
            .check(matches(isDisplayed()))
    }

    // --- Navigation: Step 0 → 1 ---

    @Test
    fun clickNext_showsPrevButton() {
        onView(withId(R.id.btnNext)).perform(click())
        onView(withId(R.id.btnPrev))
            .check(matches(isDisplayed()))
    }

    @Test
    fun clickNext_nextButtonStillVisible() {
        onView(withId(R.id.btnNext)).perform(click())
        onView(withId(R.id.btnNext))
            .check(matches(isDisplayed()))
    }

    // --- Navigation: Step 1 → 0 (go back) ---

    @Test
    fun clickNextThenPrev_prevButtonHiddenAgain() {
        onView(withId(R.id.btnNext)).perform(click())
        onView(withId(R.id.btnPrev)).perform(click())
        onView(withId(R.id.btnPrev))
            .check(matches(not(isDisplayed())))
    }

    // --- Navigate to last step (step 5) ---

    private fun navigateToStep(step: Int) {
        // Step 4 (permissions) blocks navigation if permissions not granted.
        // We can only navigate up to step 4 without granted permissions.
        repeat(step) {
            onView(withId(R.id.btnNext)).perform(click())
        }
    }

    @Test
    fun step2_demoElementsExist() {
        navigateToStep(2)
        onView(withId(R.id.delayRowBefore))
            .check(matches(isDisplayed()))
    }

    @Test
    fun step3_demoElementsExist() {
        navigateToStep(3)
        onView(withId(R.id.rowBefore))
            .check(matches(isDisplayed()))
    }

    // --- Permission step (step 4) ---

    @Test
    fun step4_permissionButtonsAreDisplayed() {
        navigateToStep(4)
        onView(withId(R.id.btnGrantDnd))
            .check(matches(isDisplayed()))
        onView(withId(R.id.btnGrantAlarm))
            .check(matches(isDisplayed()))
        onView(withId(R.id.btnGrantBattery))
            .check(matches(isDisplayed()))
    }

    @Test
    fun step4_dndButtonLaunchesSettings() {
        navigateToStep(4)
        onView(withId(R.id.btnGrantDnd)).perform(click())
        Intents.intended(hasAction(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
    }

    // --- Nav buttons container ---

    @Test
    fun navButtonsContainer_isAlwaysDisplayed() {
        onView(withId(R.id.navButtons))
            .check(matches(isDisplayed()))
        onView(withId(R.id.btnNext)).perform(click())
        onView(withId(R.id.navButtons))
            .check(matches(isDisplayed()))
    }
}

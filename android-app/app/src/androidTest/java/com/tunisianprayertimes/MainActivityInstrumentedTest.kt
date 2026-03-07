package com.tunisianprayertimes

import android.view.View
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matchers.allOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests that run on a real device or emulator.
 * Tests basic UI presence, interaction, and Arabic-only localisation.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityInstrumentedTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    // --- UI presence tests ---

    @Test
    fun titleIsDisplayed() {
        onView(withId(R.id.tvTitle))
            .check(matches(isDisplayed()))
    }

    @Test
    fun subtitleIsDisplayed() {
        onView(withId(R.id.tvSubtitle))
            .check(matches(isDisplayed()))
    }

    @Test
    fun statusCardIsDisplayed() {
        onView(withId(R.id.cardStatus))
            .check(matches(isDisplayed()))
    }

    @Test
    fun autoSilenceToggleIsDisplayed() {
        onView(withId(R.id.switchAutoSilence))
            .check(matches(isDisplayed()))
    }

    @Test
    fun delegationPickerIsDisplayed() {
        onView(withId(R.id.actvDelegation))
            .check(matches(isDisplayed()))
    }

    @Test
    fun prayerSettingsCardIsDisplayed() {
        onView(withId(R.id.cardPrayers))
            .check(matches(isDisplayed()))
    }

    @Test
    fun prayerRowsContainerIsDisplayed() {
        onView(withId(R.id.prayerRowsContainer))
            .check(matches(isDisplayed()))
    }

    @Test
    fun silenceButtonIsDisplayed() {
        onView(withId(R.id.btnToggleSilence))
            .check(matches(isDisplayed()))
    }

    @Test
    fun infoTextIsDisplayed() {
        onView(withId(R.id.tvInfo))
            .check(matches(isDisplayed()))
    }

    // --- Arabic localisation tests ---

    @Test
    fun title_isInArabic() {
        onView(withId(R.id.tvTitle))
            .check(matches(withText("أوقات الصلاة تونس")))
    }

    @Test
    fun subtitle_isInArabic() {
        onView(withId(R.id.tvSubtitle))
            .check(matches(withText("مواقيت الصلاة في تونس")))
    }

    @Test
    fun autoSilenceLabel_isInArabic() {
        onView(withId(R.id.switchAutoSilence))
            .check(matches(withText("الإسكات التلقائي أثناء الصلاة")))
    }

    // --- Interaction tests ---

    @Test
    fun autoSilenceToggle_isClickable() {
        onView(withId(R.id.switchAutoSilence))
            .check(matches(isClickable()))
    }

    @Test
    fun silenceButton_isClickable() {
        onView(withId(R.id.btnToggleSilence))
            .check(matches(isClickable()))
    }

    @Test
    fun prayerRows_hasFiveChildren() {
        activityRule.scenario.onActivity { activity ->
            val container = activity.findViewById<android.view.ViewGroup>(R.id.prayerRowsContainer)
            assert(container.childCount == 5) {
                "Expected 5 prayer rows, got ${container.childCount}"
            }
        }
    }

    @Test
    fun prayerRows_showCorrectArabicNames() {
        val arabicNames = listOf("الفجر", "الظهر", "العصر", "المغرب", "العشاء")
        for (name in arabicNames) {
            onView(allOf(withText(name), isDisplayed()))
                .check(matches(isDisplayed()))
        }
    }

    @Test
    fun statusText_isDisplayedWithContent() {
        onView(withId(R.id.tvStatus))
            .check(matches(isDisplayed()))
        // Status should have text (one of the status strings)
        onView(withId(R.id.tvStatus))
            .check(matches(withText(org.hamcrest.Matchers.not(""))))
    }
}

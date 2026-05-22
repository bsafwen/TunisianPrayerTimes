package com.tunisianprayertimes.testing

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import com.tunisianprayertimes.ui.TestTags

private const val TAG_WAIT_TIMEOUT_MILLIS = 15_000L

class MainScreenRobot(private val composeRule: ComposeTestRule) {
    fun openAlarms(): AlarmScreenRobot {
        composeRule.waitUntilTagExists(TestTags.MAIN_TAB_ALARMS)
        composeRule.onNodeWithTag(TestTags.MAIN_TAB_ALARMS).performClick()
        composeRule.waitForIdle()
        return AlarmScreenRobot(composeRule)
    }
}

class AlarmScreenRobot(private val composeRule: ComposeTestRule) {
    fun assertAlarmListVisible(): AlarmScreenRobot = apply {
        composeRule.waitUntilTagExists(TestTags.WAKE_ALARM_LIST)
        composeRule.onNodeWithTag(TestTags.WAKE_ALARM_LIST)
            .performScrollTo()
            .assertIsDisplayed()
    }

    fun assertAlarmRowVisible(alarmId: String): AlarmScreenRobot = apply {
        val tag = TestTags.wakeAlarmRow(alarmId)
        composeRule.waitUntilTagExists(tag)
        composeRule.onNodeWithTag(tag)
            .performScrollTo()
            .assertIsDisplayed()
    }

    fun assertAlarmEnabled(alarmId: String): AlarmScreenRobot = apply {
        val tag = TestTags.wakeAlarmEnabledSwitch(alarmId)
        composeRule.waitUntilTagExists(tag)
        composeRule.onNodeWithTag(tag)
            .performScrollTo()
            .assertIsOn()
    }

    fun assertAlarmDisabled(alarmId: String): AlarmScreenRobot = apply {
        val tag = TestTags.wakeAlarmEnabledSwitch(alarmId)
        composeRule.waitUntilTagExists(tag)
        composeRule.onNodeWithTag(tag)
            .performScrollTo()
            .assertIsOff()
    }

    fun toggleAlarm(alarmId: String): AlarmScreenRobot = apply {
        val tag = TestTags.wakeAlarmEnabledSwitch(alarmId)
        composeRule.waitUntilTagExists(tag)
        composeRule.onNodeWithTag(tag)
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
    }

    fun openQuickAdd(): AlarmScreenRobot = apply {
        composeRule.waitUntilTagExists(TestTags.WAKE_ALARM_ADD_BUTTON)
        composeRule.onNodeWithTag(TestTags.WAKE_ALARM_ADD_BUTTON)
            .performClick()
        composeRule.waitForIdle()
    }

    fun assertQuickAddActionsVisible(): AlarmScreenRobot = apply {
        composeRule.waitUntilTagExists(TestTags.WAKE_QUICK_ADD_SHEET)
        composeRule.onNodeWithTag(TestTags.WAKE_QUICK_ADD_SHEET).assertIsDisplayed()
        composeRule.waitUntilTagExists(TestTags.WAKE_QUICK_PRESET_PRAYER_RELATIVE)
        composeRule.onNodeWithTag(TestTags.WAKE_QUICK_PRESET_PRAYER_RELATIVE).assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.WAKE_QUICK_PRESET_FIXED_TIME).assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.WAKE_QUICK_PRESET_TIMER).assertIsDisplayed()
    }
}

private fun ComposeTestRule.waitUntilTagExists(tag: String) {
    waitUntil(timeoutMillis = TAG_WAIT_TIMEOUT_MILLIS) {
        try {
            onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        } catch (_: IllegalStateException) {
            false
        }
    }
}
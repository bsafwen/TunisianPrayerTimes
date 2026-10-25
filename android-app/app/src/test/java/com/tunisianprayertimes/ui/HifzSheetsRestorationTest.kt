package com.tunisianprayertimes.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.tunisianprayertimes.quran.hifz.HifzCheck
import com.tunisianprayertimes.quran.hifz.HifzGrade
import com.tunisianprayertimes.quran.hifz.HifzPace
import com.tunisianprayertimes.quran.hifz.HifzPlan
import com.tunisianprayertimes.quran.hifz.HifzSettings
import com.tunisianprayertimes.quran.hifz.HifzTimes
import com.tunisianprayertimes.quran.hifz.HifzTimesPreset
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ar-rTN-w360dp-h800dp-xhdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HifzSheetsRestorationTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun reminderPaceAndPresetStayDraftsAcrossRestorationUntilSaved() {
        var saved: HifzPlan? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            AdhkarTheme {
                HifzPlanDialog(
                    auto = 3, pace = HifzPace.Auto, times = null, settings = HifzSettings(),
                    onChange = { saved = it }, onDismiss = {},
                )
            }
        }
        compose.onNodeWithTag("hifz_plan_own_times").performScrollTo().performClick()
        compose.onNodeWithTag("hifz_times_once").performScrollTo().performClick()
        compose.onNodeWithTag("hifz_plan_once_per_day").performScrollTo().performClick()
        compose.onNodeWithTag("hifz_plan_intense").performScrollTo().performClick()
        assertNull("Choosing a draft must not save it", saved)

        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("hifz_times_once").assertIsSelected()
        compose.onNodeWithTag("hifz_plan_once_per_day").assertIsOn()
        compose.onNodeWithTag("hifz_plan_intense").assertIsSelected()
        assertNull("Restoration must not silently commit the draft", saved)
        compose.onNodeWithTag("hifz_plan_done").performClick()
        assertEquals(HifzPlan(HifzPace.Intense, HifzTimesPreset.Once.times), saved)
    }

    @Test
    fun customTimeDeletionIsExplicitAccessibleAndItsDraftSurvivesRestoration() {
        var saved: HifzPlan? = null
        val original = HifzTimes(minutes = setOf(490, 1290))
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalDensity provides Density(density.density, 1.5f),
            ) {
                AdhkarTheme {
                    HifzPlanDialog(
                        auto = 3, pace = HifzPace.Auto, times = original, settings = HifzSettings(),
                        onChange = { saved = it }, onDismiss = {},
                    )
                }
            }
        }
        compose.onNodeWithTag("hifz_times_custom").performScrollTo().performClick()
        val time = compose.onNodeWithTag("hifz_times_time_490").performScrollTo()
        time.assertContentDescriptionEquals("تعديل وقت التذكير الساعة 08:10")
        val bounds = time.getUnclippedBoundsInRoot()
        assertTrue("The clock edit target must remain at least 48dp", bounds.bottom - bounds.top >= 48.dp)
        time.performClick()
        assertNull("Opening the editor must not remove or save the time", saved)
        val remove = compose.onNodeWithTag("hifz_time_remove")
        remove.assertContentDescriptionEquals("حذف وقت التذكير الساعة 08:10")
        val removeBounds = remove.getUnclippedBoundsInRoot()
        assertTrue("The explicit delete target must remain at least 48dp", removeBounds.bottom - removeBounds.top >= 48.dp)
        remove.performClick()
        compose.onNodeWithTag("hifz_times_time_490").assertDoesNotExist()
        assertNull("Deleting from the draft still waits for Save", saved)

        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("hifz_times_time_490").assertDoesNotExist()
        compose.onNodeWithTag("hifz_times_time_1290").assertExists()
        compose.onNodeWithTag("hifz_plan_done").performClick()
        assertEquals(HifzPlan(HifzPace.Auto, HifzTimes(minutes = setOf(1290)), oncePerDay = false), saved)
    }

    @Test
    fun changingOnlyRecallCountKeepsGlobalScheduleInherited() {
        var saved: HifzPlan? = null
        compose.setContent {
            AdhkarTheme {
                HifzPlanDialog(
                    auto = 3, pace = HifzPace.Auto, times = null, oncePerDay = true,
                    settings = HifzSettings(times = HifzTimes(minutes = setOf(1290))),
                    onChange = { saved = it }, onDismiss = {},
                )
            }
        }
        compose.onNodeWithTag("hifz_plan_follow_settings").assertIsSelected()
        compose.onNodeWithTag("hifz_plan_intense").performScrollTo().performClick()
        compose.onNodeWithTag("hifz_plan_done").performClick()
        assertEquals(HifzPlan(HifzPace.Intense, null, oncePerDay = true), saved)
    }

    @Test
    fun followingSettingsDoesNotChangeIndependentOnceDailyFrequency() {
        var saved: HifzPlan? = null
        compose.setContent {
            AdhkarTheme {
                HifzPlanDialog(
                    auto = 3, pace = HifzPace.Auto, times = HifzTimes(minutes = setOf(1290)), oncePerDay = true,
                    settings = HifzSettings(times = HifzTimesPreset.Prayers.times),
                    onChange = { saved = it }, onDismiss = {},
                )
            }
        }
        compose.onNodeWithTag("hifz_plan_follow_settings").performScrollTo().performClick()
        compose.onNodeWithTag("hifz_plan_once_per_day").assertIsOn()
        compose.onNodeWithTag("hifz_plan_done").performClick()
        assertEquals(HifzPlan(HifzPace.Auto, null, oncePerDay = true), saved)
    }

    @Test
    fun settingsExposeSharedBudgetQuietHoursAndDailyGoal() {
        val settings = mutableStateOf(HifzSettings())
        compose.setContent {
            AdhkarTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    HifzReminderSettingsControls(settings.value) { settings.value = it }
                }
            }
        }
        compose.onNodeWithTag("hifz_daily_alert_limit_more").performScrollTo().performClick()
        assertEquals(4, settings.value.dailyAlertLimit)
        compose.onNodeWithTag("hifz_quiet_hours").performScrollTo().performClick()
        compose.onNodeWithTag("hifz_quiet_start").assertExists()
        compose.onNodeWithTag("hifz_quiet_end").assertExists()
        compose.onNodeWithTag("hifz_daily_goal_more").performScrollTo().performClick()
        assertEquals(11, settings.value.dailyGoal)
        compose.runOnIdle { settings.value = settings.value.copy(reminder = false) }
        compose.onNodeWithTag("hifz_daily_alert_limit_more").assertDoesNotExist()
        compose.onNodeWithTag("hifz_daily_goal_more").assertExists()
    }

    @Test
    fun laterReminderCopyDoesNotGuessWhichPrayerPrecedesClockTime() {
        val copy = hifzLaterReminders(HifzSettings(times = HifzTimesPreset.Once.times.copy(minutes = setOf(5 * 60))))
        assertFalse(copy.contains("بعد الفجر"))
        assertFalse(copy.contains("05:00"))
        assertTrue(copy.contains("أول موعد متاح"))
        assertTrue(copy.contains("أوقات الإعدادات"))
    }

    @Test
    fun historyDistinguishesSameDayChecksAndDoesNotInventMissingTimes() {
        val date = LocalDate.of(2026, 10, 23)
        val zone = ZoneId.of("Africa/Tunis")
        fun check(hour: Int) = HifzCheck(
            day = date.toEpochDay(), grade = HifzGrade.Held,
            at = date.atTime(hour, 5).atZone(zone).toInstant().toEpochMilli(),
        )
        val morning = hifzHistoryWhen(check(8), zone)
        val noon = hifzHistoryWhen(check(12), zone)
        val evening = hifzHistoryWhen(check(20), zone)
        assertTrue(morning.endsWith("08:05"))
        assertTrue(noon.endsWith("12:05"))
        assertTrue(evening.endsWith("20:05"))
        assertEquals(morning.substringBefore('،'), evening.substringBefore('،'))
        assertFalse(hifzHistoryWhen(check(8).copy(at = 0L), zone).contains("الساعة"))
    }
}

package com.tunisianprayertimes.bugreport

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerSilenceConfig
import com.tunisianprayertimes.PrayerTime
import com.tunisianprayertimes.R
import com.tunisianprayertimes.SilenceMode
import com.tunisianprayertimes.ui.PrayerSilenceRangeSlider
import com.tunisianprayertimes.ui.PrayerTimelineScale
import com.tunisianprayertimes.ui.PrayerTimelineWindow
import com.tunisianprayertimes.ui.SharedSilenceRuleDraft
import com.tunisianprayertimes.ui.PrayerTimelineAnchor
import com.tunisianprayertimes.ui.SharedSilenceSettingsSheet
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Prayer-silence UI bugs from BUG_REPORT.md: H4, L15 and L30. L15 and L30 are in the shared
 * silence sheet, which nothing in the app opens today, so they are rendered directly here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ar-rTN-w360dp-h800dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SilenceUiBugsTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun showSharedSheetWithStartBeforeAdhan(minutesBefore: Int) {
        compose.setContent {
            SharedSilenceSettingsSheet(
                prayerNames = listOf(Prayer.DHUHR to "الظهر"),
                anchors = mapOf(Prayer.DHUHR to PrayerTimelineAnchor.demo(Prayer.DHUHR, 12, 0)),
                initialDraft = SharedSilenceRuleDraft(startOffsetMinutes = -minutesBefore, endOffsetMinutes = 30),
                onDismiss = {},
                onApply = { _, _ -> },
            )
        }
    }

    private fun startMinutesField() =
        compose.onAllNodesWithContentDescription(context.getString(R.string.prayer_editor_minutes_unit))[0]

    /** L30: in the shared sheet, "+" lowers the shown minutes when the start is before adhan. */
    @Test
    fun L30_sharedSheetPlusIncreasesTheShownMinutesBeforeAdhan() {
        showSharedSheetWithStartBeforeAdhan(10)
        startMinutesField().assert(hasText("10"))

        compose.onAllNodesWithContentDescription(context.getString(R.string.prayer_silence_increase_minute))[0].performClick()

        startMinutesField().assert(hasText("11"))
    }

    /** L15: clearing the shared sheet's minute field refills it with "0". */
    @Test
    fun L15_clearedMinuteFieldStaysEmpty() {
        showSharedSheetWithStartBeforeAdhan(10)

        startMinutesField().performTextClearance()

        startMinutesField().assert(!hasText("0"))
    }

    /** H4: after the slider has had a touch, a tap on the track commits through an older composition's callback. */
    @Test
    fun H4_trackTapUsesTheLatestCommitCallback() {
        var generation by mutableIntStateOf(1)
        val commits = mutableListOf<Int>()
        val anchor = PrayerTimelineAnchor.demo(Prayer.DHUHR, 12, 0)
        // Both configs resolve to the same 12:00-13:00 window; only the end rule differs.
        val adhanRelativeEnd = PrayerSilenceConfig(endOffsetMinutes = 60)
        val fixedEnd = PrayerSilenceConfig(mode = SilenceMode.FIXED_TIME, fixedHour = 13, fixedMinute = 0)
        val window = PrayerTimelineWindow(12 * 60, 13 * 60)
        val scale = PrayerTimelineScale(-30, 120)
        compose.setContent {
            val current = generation
            PrayerSilenceRangeSlider(
                prayer = Prayer.DHUHR,
                prayerName = "الظهر",
                anchor = anchor,
                config = if (current == 1) adhanRelativeEnd else fixedEnd,
                scale = scale,
                window = window,
                enabled = true,
                onPreviewWindow = {},
                onCommitWindow = { commits += current },
                onEditEndpoint = {},
            )
        }
        val handles = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .fetchSemanticsNodes()
        assertEquals("Precondition: two handles", 2, handles.size)
        val first = handles[0].boundsInRoot.center
        val second = handles[1].boundsInRoot.center
        val slider = compose.onNodeWithTag("prayer_silence_slider_DHUHR")
        val sliderBounds = slider.fetchSemanticsNode().boundsInRoot

        // The user taps a handle to open the endpoint editor, as in the app...
        slider.performTouchInput { click(Offset(first.x - sliderBounds.left, first.y - sliderBounds.top)) }
        compose.waitForIdle()
        // ...the editor changes the end rule, which leaves the window and the scale unchanged...
        compose.runOnIdle { generation = 2 }
        compose.waitForIdle()
        // ...and the user then taps the track.
        slider.performTouchInput {
            click(Offset((first.x + second.x) / 2f - sliderBounds.left, first.y - sliderBounds.top))
        }
        compose.waitForIdle()

        assertEquals("The tap must commit through the current callback", listOf(2), commits)
    }
}

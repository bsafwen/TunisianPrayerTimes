package com.tunisianprayertimes.wake

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.inspector.WindowInspector
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.RingtonePreset
import com.tunisianprayertimes.ui.theme.TunisianPrayerTimesTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ar-rTN-w320dp-h640dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WakeMotivationUiTest {
    @get:Rule val compose = createComposeRule()
    private var stopped = false

    private fun payload(challenge: Boolean = false) = WakeTriggerPayload(
        eventId = wakeMainEventId("motivation-fixture"), prayer = Prayer.FAJR, effectivePrayer = Prayer.FAJR,
        hour = 5, minute = 0, ringtone = RingtonePreset.DEFAULT_ALARM, vibrationOnly = false,
        wakeUpCheckEnabled = challenge, progressiveVolume = false, snoreTrackingEnabled = false,
        awakeCheckEnabled = false, isSubAlarm = false,
        wakeUpCheckChallenge = if (challenge) WakeUpCheckChallenge(3, 4, "+", 7) else null,
    )

    private fun show(id: String, fontScale: Float = 1f, challenge: Boolean = false, short: Boolean = false) {
        RuntimeEnvironment.setFontScale(fontScale)
        val message = WakeMotivations.all.single { it.reviewId == id }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                TunisianPrayerTimesTheme {
                    Box(
                        (if (short) Modifier.fillMaxWidth().height(400.dp) else Modifier.fillMaxSize())
                            .testTag("motivation_fixture"),
                    ) {
                        WakeAlertScreen(payload(challenge), 0, message) { completed -> stopped = completed }
                    }
                }
            }
        }
    }

    private fun stopStaysInsideTheViewport() {
        val viewport = compose.onNodeWithTag("motivation_fixture").getUnclippedBoundsInRoot()
        val stop = compose.onNodeWithTag("wake_alarm_stop").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(stop.top >= viewport.top)
        assertTrue(stop.bottom <= viewport.bottom)
        assertTrue(stop.left >= viewport.left && stop.right <= viewport.right)
        assertTrue(stop.bottom - stop.top >= 48.dp)
    }

    private fun savePreview(name: String) {
        compose.waitForIdle()
        // Robolectric cannot dispatch captureToImage's hardware redraw; draw the actual window
        // synchronously with native graphics, as the app's other layout previews do.
        val image = compose.runOnIdle {
            val view = WindowInspector.getGlobalWindowViews().last { it.isAttachedToWindow && it.width > 0 && it.height > 0 }
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        val file = File("build/reports/wake-motivation/$name.png")
        requireNotNull(file.parentFile).mkdirs()
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    @Test
    fun ringingScreenShowsTheMessageAndAttributionWithAnAccessibleStopButton() {
        show("1")
        compose.onNodeWithText("اللهم بارك لأمتي في بكورها").assertIsDisplayed()
        compose.onNodeWithTag("wake_motivation_source").assertIsDisplayed()
        stopStaysInsideTheViewport()
        savePreview("morning")
        compose.onNodeWithTag("wake_alarm_stop").assertIsEnabled().performClick()
        assertTrue(stopped)
    }

    @Test
    fun longestMessageAtDoubleSizeTextKeepsEveryCharacterAndItsSourceReachable() {
        show("H4", fontScale = 2f)
        stopStaysInsideTheViewport()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("wake_motivation_text").performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            it(layouts)
        }
        assertTrue(layouts.isNotEmpty())
        layouts.forEach { layout ->
            assertFalse(layout.didOverflowHeight)
            assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1))
            repeat(layout.lineCount) { assertFalse(layout.isLineEllipsized(it)) }
        }
        savePreview("long-quote-font-200")
        compose.onNodeWithTag("wake_motivation_source").performScrollTo().assertIsDisplayed()
        stopStaysInsideTheViewport()
        savePreview("long-quote-source-font-200")
    }

    @Test
    fun aMessageDoesNotBypassTheWakeUpChallenge() {
        show("H3", challenge = true)
        compose.onNodeWithTag("wake_alarm_stop").assertIsNotEnabled()
        val answer = compose.onNode(hasSetTextAction()).performScrollTo()
        answer.performTextReplacement("6")
        compose.onNodeWithTag("wake_alarm_stop").assertIsNotEnabled()
        answer.performTextReplacement("7")
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithText("الراحة لا تُنال بالراحة").assertIsDisplayed()
        compose.onNodeWithTag("wake_motivation_source").assertIsDisplayed()
        stopStaysInsideTheViewport()
        compose.onNodeWithTag("wake_alarm_stop").assertIsEnabled().performClick()
        assertTrue(stopped)
    }

    @Test
    fun shortViewportAndLargeTextKeepTheChallengeAndStopUsable() {
        show("H4", fontScale = 2f, challenge = true, short = true)
        stopStaysInsideTheViewport()
        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("7")
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithTag("wake_alarm_stop").assertIsEnabled()
        compose.onNodeWithTag("wake_motivation_source").performScrollTo().assertIsDisplayed()
        stopStaysInsideTheViewport()
        savePreview("short-screen-font-200")
        compose.onNodeWithTag("wake_alarm_stop").performClick()
        assertTrue(stopped)
    }

    @Test
    fun restorationKeepsTheSameOccurrenceButDoesNotResurrectAPreviousAlarmsMessage() {
        val restoration = StateRestorationTester(compose)
        var fixture = payload().copy(triggerAtMillis = 1_000L, occurrenceAtMillis = 1_000L)
        var resolutions = 0
        restoration.setContent {
            val message = rememberWakeMotivation(fixture) { _, _ ->
                resolutions++
                WakeMotivations.all.single { it.reviewId == if (resolutions == 1) "H3" else "H8" }
            }
            Text(message?.reviewId.orEmpty())
        }
        compose.onNodeWithText("H3").assertIsDisplayed()
        assertEquals(1, resolutions)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("H3").assertIsDisplayed()
        assertEquals(1, resolutions)

        // This plain external value changes while the old composition is gone, as when the queue
        // advances before Android restores the activity; it deliberately does not recompose it.
        fixture = fixture.copy(triggerAtMillis = 2_000L, occurrenceAtMillis = 2_000L)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("H8").assertIsDisplayed()
        assertEquals(2, resolutions)
    }
}

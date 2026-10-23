package com.tunisianprayertimes.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.inspector.WindowInspector
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.InmEvent
import com.tunisianprayertimes.InmLocation
import com.tunisianprayertimes.InmPrayerFormula
import com.tunisianprayertimes.R
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.floor
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
class PrayerTimeMethodExplorerTest {
    @get:Rule val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val date = LocalDate.of(2026, 10, 22)
    private val location = InmLocation(latitude = 36.8, longitude = 10.18, elevationM = 10.0)
    private val explanation = InmPrayerFormula.explain(location, date.year, date.monthValue, date.dayOfMonth)

    @Composable
    private fun ExplorerScreen(fontScale: Float = 1f) {
        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, fontScale),
            LocalLayoutDirection provides LayoutDirection.Rtl,
        ) {
            AdhkarTheme {
                Box(Modifier.fillMaxSize().background(Color.White).testTag("explorer_preview_screen")) {
                    Column(Modifier.width(360.dp).verticalScroll(rememberScrollState()).padding(16.dp)) {
                        PrayerTimeMethodExplorer(location, date, explanation)
                    }
                }
            }
        }
    }

    private fun select(event: InmEvent) = compose.onNodeWithTag("prayer_method_event_${event.name}")
        .performScrollTo().performClick().assertIsSelected()

    @Test
    fun everySelectorReplacesTheExplanationAndKeepsOnlyOneSelection() {
        compose.setContent { ExplorerScreen() }
        val descriptions = mapOf(
            InmEvent.FAJR to R.string.prayer_explorer_fajr,
            InmEvent.SUNRISE to R.string.prayer_explorer_sunrise,
            InmEvent.DHUHR to R.string.prayer_explorer_dhuhr,
            InmEvent.ASR to R.string.prayer_explorer_asr,
            InmEvent.MAGHRIB to R.string.prayer_explorer_maghrib,
            InmEvent.ISHA to R.string.prayer_explorer_isha,
        )
        compose.onNodeWithTag("prayer_method_event_FAJR").assertIsSelected()
        descriptions.forEach { (event, resource) ->
            select(event)
            compose.onNodeWithTag("prayer_method_event_explanation").assertTextEquals(context.getString(resource))
            InmEvent.entries.filter { it != event }.forEach { other ->
                compose.onNodeWithTag("prayer_method_event_${other.name}").assertIsNotSelected()
            }
        }
    }

    @Test
    fun noonAndSunsetMarkersExplainTheInstantBeforeThePrayerOffset() {
        compose.setContent { ExplorerScreen() }
        select(InmEvent.DHUHR)
        val noon = seconds(explanation.solarNoonMinutes)
        val dhuhr = explanation.events.first { it.event == InmEvent.DHUHR }
        compose.onNodeWithText(context.getString(R.string.prayer_explorer_marker_time, context.getString(R.string.prayer_explorer_noon), noon))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.prayer_explorer_dhuhr_addition)).assertExists()
        compose.onNodeWithTag("prayer_method_event_DHUHR").assertTextContains(clock(dhuhr.shownMinutes))
        assertTrue(dhuhr.exactMinutes > explanation.solarNoonMinutes)

        select(InmEvent.MAGHRIB)
        val maghrib = explanation.events.first { it.event == InmEvent.MAGHRIB }
        val sunset = seconds(maghrib.exactMinutes - 2)
        compose.onNodeWithText(context.getString(R.string.prayer_explorer_marker_time, context.getString(R.string.prayer_explorer_sunset), sunset))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.prayer_explorer_maghrib_addition)).assertExists()
    }

    @Test
    fun selectedPrayerSurvivesSavedStateRestoration() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { ExplorerScreen() }
        select(InmEvent.ISHA)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("prayer_method_event_ISHA").assertIsSelected()
        compose.onNodeWithTag("prayer_method_event_explanation").assertTextEquals(context.getString(R.string.prayer_explorer_isha))
    }

    @Test
    fun narrowPhoneWithDoubleSizeTextKeepsEverySelectorReachable() {
        compose.setContent { ExplorerScreen(fontScale = 2f) }
        val viewport = compose.onNodeWithTag("explorer_preview_screen").getUnclippedBoundsInRoot()
        InmEvent.entries.forEach { event ->
            val chip = select(event).assertIsDisplayed()
            val bounds = chip.getUnclippedBoundsInRoot()
            assertTrue("${event.name} must fit horizontally", bounds.left >= viewport.left && bounds.right <= viewport.right)
            assertTrue("${event.name} must keep a 48dp touch target", bounds.bottom - bounds.top >= 48.dp)
        }
        compose.onNodeWithTag("prayer_method_event_explanation").performScrollTo().assertIsDisplayed()
        savePreview("explorer-large-text", "explorer_preview_screen")
    }

    @Test
    fun renderFajrAndAsrForVisualReview() {
        compose.setContent { ExplorerScreen() }
        savePreview("explorer-fajr", "explorer_preview_screen")
        select(InmEvent.ASR)
        savePreview("explorer-asr-chart", "explorer_preview_screen")
        compose.onNodeWithText(context.getString(R.string.prayer_explorer_shadow_key)).performScrollTo().assertIsDisplayed()
        savePreview("explorer-asr-shadow", "explorer_preview_screen")
    }

    @Test
    fun renderDialogAndReachExplorerFromItsIntroduction() {
        compose.setContent {
            AdhkarTheme {
                PrayerTimeMethodDialog(
                    delegationId = 615,
                    selectedDate = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithTag(TestTags.PRAYER_TIME_METHOD_DIALOG).assertIsDisplayed()
        savePreview("dialog-introduction", TestTags.PRAYER_TIME_METHOD_DIALOG)
        compose.onNodeWithTag("prayer_method_event_FAJR").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("prayer_method_event_explanation").performScrollTo().assertIsDisplayed()
        savePreview("dialog-explorer", TestTags.PRAYER_TIME_METHOD_DIALOG)
        select(InmEvent.ASR)
        compose.onNodeWithText(context.getString(R.string.prayer_explorer_shadow_key)).performScrollTo().assertIsDisplayed()
        savePreview("dialog-asr", TestTags.PRAYER_TIME_METHOD_DIALOG)
        compose.onNodeWithText(context.getString(R.string.prayer_method_guide_rounding_example)).performScrollTo().assertIsDisplayed()
        savePreview("dialog-rounding", TestTags.PRAYER_TIME_METHOD_DIALOG)
        compose.onNodeWithTag("prayer_method_seasons").performScrollTo().performClick()
        val seasonCaptionPrefix = context.getString(R.string.prayer_method_course_caption, "", "").substringBefore('\u2066')
        compose.onNodeWithText(seasonCaptionPrefix, substring = true).performScrollTo().assertIsDisplayed()
        savePreview("dialog-seasons", TestTags.PRAYER_TIME_METHOD_DIALOG)
        compose.onNodeWithTag("prayer_method_details").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.prayer_method_guide_details_intro)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.prayer_method_sun_jd_name)).assertExists()
        savePreview("dialog-details", TestTags.PRAYER_TIME_METHOD_DIALOG)
    }

    private fun savePreview(name: String, tag: String) {
        compose.waitForIdle()
        compose.onNodeWithTag(tag).assertIsDisplayed()
        // captureToImage waits for a hardware-window redraw, which Robolectric does not dispatch.
        // Draw the actual Compose window synchronously through Robolectric's native graphics instead.
        val bitmap = compose.runOnIdle {
            val view = WindowInspector.getGlobalWindowViews().last { it.isAttachedToWindow && it.width > 0 && it.height > 0 }
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        val output = File("build/outputs/prayer-method-preview/$name.png")
        requireNotNull(output.parentFile).mkdirs()
        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private fun clock(minutes: Int): String = String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60)

    private fun seconds(minutes: Double): String {
        val seconds = floor(minutes * 60).toInt()
        return String.format(Locale.US, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    }
}

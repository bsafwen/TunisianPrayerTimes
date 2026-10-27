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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.R
import com.tunisianprayertimes.worship.NightPrayerWindow
import com.tunisianprayertimes.worship.VoluntaryPrayerTimes
import com.tunisianprayertimes.worship.VoluntaryPrayerWindow
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
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

/** Fixed-clock real compositions; source links are inspected but never opened. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ar-rTN-w320dp-h640dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VoluntaryPrayerUiTest {
    @get:Rule val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val zone = ZoneId.of("Africa/Tunis")
    private val date = LocalDate.of(2026, 10, 26)
    private val duha = VoluntaryPrayerWindow(
        start = at("2026-10-26T07:00"), end = at("2026-10-26T11:20"), isEstimated = true,
    )
    private val night = NightPrayerWindow(
        eveningDate = date, sunset = at("2026-10-26T18:00"), isha = at("2026-10-26T19:30"),
        fajr = at("2026-10-27T05:00"), lastThirdStart = at("2026-10-27T01:20"),
        lastThirdPrayerStart = at("2026-10-27T01:20"), isEstimated = false,
    )
    private val schedule = VoluntaryPrayerTimes(date, duha, night)
    private val morning = at("2026-10-26T08:00")

    @Composable
    private fun CardsScreen(
        times: VoluntaryPrayerTimes? = schedule,
        now: Instant = morning,
        displayZone: ZoneId = zone,
        loading: Boolean = false,
        fontScale: Float = 1f,
    ) {
        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, fontScale),
            LocalLayoutDirection provides LayoutDirection.Rtl,
        ) {
            AdhkarTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFFF7F8F6)).testTag("voluntary_prayer_screen")) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        VoluntaryPrayerCards(times = times, now = now, displayZone = displayZone, loading = loading)
                    }
                }
            }
        }
    }

    @Test
    fun duhaDetailsExplainEstimatedSolarBoundariesAndCanBeDismissed() {
        compose.setContent { CardsScreen() }
        compose.onNodeWithText(text(R.string.worship_duha_subtitle)).assertIsDisplayed()
        savePreview("duha-normal", "voluntary_prayer_screen")
        openDetails("worship_duha_details")
        compose.onNodeWithText(text(R.string.worship_duha_explanation)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.worship_duha_calculation))
            .assertTextContains("بعد الشروق بعشرين دقيقة", substring = true)
            .assertTextContains("قبل الزوال الشمسي بعشرين دقيقة", substring = true)
            .assertTextContains("ولا نعتمد موعد الظهر", substring = true)
            .assertTextContains("ليست تحديدًا دقيقًا", substring = true)
        savePreview("duha-explanation", "worship_time_explanation")
        compose.onNodeWithText(text(R.string.worship_source_prayer_times)).performScrollTo().assertIsDisplayed()
        closeDetails()
        compose.onNodeWithTag("worship_duha_details").assertExists()
    }

    @Test
    fun nightDetailsExposeBothSourcesAndThePrayerPrerequisite() {
        compose.setContent { CardsScreen() }
        openDetails("worship_night_details")
        compose.onNodeWithText(text(R.string.worship_night_explanation))
            .assertTextContains("بعد أداء صلاة العشاء", substring = true)
            .assertTextContains("لا يعني أنك أدّيت الصلاة", substring = true)
        compose.onNodeWithText(text(R.string.worship_night_calculation))
            .assertTextContains("من موعد المغرب إلى الفجر الذي يليه", substring = true)
        compose.onNodeWithText(text(R.string.worship_source_thirds)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.worship_source_prayer_times)).performScrollTo().assertIsDisplayed()
        savePreview("night-sources", "worship_time_explanation")
        closeDetails()
    }

    @Test
    fun missingTimesSayUnavailableAndLoadingDoesNotInventAClock() {
        val loading = mutableStateOf(true)
        compose.setContent { CardsScreen(times = null, loading = loading.value) }
        compose.onAllNodesWithText(text(R.string.worship_times_loading)).assertCountEquals(2)
        compose.onNodeWithText("07:00").assertDoesNotExist()
        compose.runOnIdle { loading.value = false }
        compose.onNodeWithText(text(R.string.worship_duha_unavailable)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.worship_night_unavailable)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.worship_times_loading)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.worship_last_third)).assertDoesNotExist()
        openDetails("worship_night_details")
        compose.onNodeWithText(text(R.string.worship_night_calculation)).assertExists()
        closeDetails()
    }

    @Test
    fun exactStartAndEndInstantsUpdateBothStatusesWithoutClaimingWorshipCompletion() {
        val now = mutableStateOf(duha.start.minusSeconds(1))
        compose.setContent { CardsScreen(now = now.value) }
        compose.onNodeWithText(text(R.string.worship_duha_in, "00:01")).assertExists()
        compose.runOnIdle { now.value = duha.start }
        compose.onNodeWithText(text(R.string.worship_duha_active, "04:20")).assertExists()
        compose.runOnIdle { now.value = duha.end }
        compose.onNodeWithText(text(R.string.worship_duha_ended)).assertExists()
        compose.onNodeWithText(text(R.string.worship_duha_active, "00:00")).assertDoesNotExist()

        compose.runOnIdle { now.value = night.lastThirdPrayerStart.minusSeconds(1) }
        compose.onNodeWithText(text(R.string.worship_third_in, "00:01")).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.worship_current_night)).assertExists()
        compose.runOnIdle { now.value = night.lastThirdPrayerStart }
        compose.onNodeWithText(text(R.string.worship_third_active, "03:40")).assertExists()
        compose.runOnIdle { now.value = night.fajr }
        compose.onNodeWithText(text(R.string.worship_night_ended)).assertExists()
    }

    @Test
    fun countdownRoundsEveryPositiveRemainderUpAndClampsElapsedTimes() {
        assertEquals("00:00", worshipCountdown(morning, morning.minusNanos(1)))
        assertEquals("00:00", worshipCountdown(morning, morning))
        assertEquals("00:01", worshipCountdown(morning, morning.plusNanos(1)))
        assertEquals("00:02", worshipCountdown(morning, morning.plusSeconds(61)))
    }

    @Test
    fun afterMidnightIshaKeepsItsDateAndAllDatesFollowTheDisplayedZone() {
        val lateNight = night.copy(isha = at("2026-10-27T00:30"))
        val displayedZone = mutableStateOf(zone)
        compose.setContent {
            CardsScreen(times = schedule.copy(night = lateNight), now = at("2026-10-27T00:15"), displayZone = displayedZone.value)
        }
        val nightCard = hasAnyAncestor(hasTestTag("worship_night_card"))
        compose.onNode(hasText("00:30") and nightCard).performScrollTo().assertIsDisplayed()
        compose.onAllNodes(hasText(dayLabel(date.plusDays(1))) and nightCard).assertCountEquals(4)
        compose.onAllNodes(hasText(dayLabel(date)) and nightCard).assertCountEquals(0)

        compose.runOnIdle { displayedZone.value = ZoneId.of("America/Los_Angeles") }
        compose.onNodeWithText(text(R.string.worship_times_clock, "America/Los_Angeles")).assertExists()
        compose.onNode(hasText("16:30") and nightCard).assertExists()
        compose.onAllNodes(hasText(dayLabel(date)) and nightCard).assertCountEquals(4)
        compose.onAllNodes(hasText(dayLabel(date.plusDays(1))) and nightCard).assertCountEquals(0)
    }

    @Test
    fun narrowRtlAtOneAndAHalfSizeTextKeepsCardsAndSheetControlsReachable() {
        checkLargeFont(fontScale = 1.5f, suffix = "150")
    }

    @Test
    fun narrowRtlAtDoubleSizeTextKeepsCardsAndSheetControlsReachable() {
        checkLargeFont(fontScale = 2f, suffix = "200")
    }

    private fun checkLargeFont(fontScale: Float, suffix: String) {
        // A modal has its own Android window, so a composition-only density override does not
        // exercise its font setting. Set the platform configuration before either window exists.
        RuntimeEnvironment.setFontScale(fontScale)
        compose.setContent { CardsScreen(fontScale = fontScale) }
        val viewport = compose.onNodeWithTag("voluntary_prayer_screen").getUnclippedBoundsInRoot()
        assertEquals("Exercise a real 320dp phone viewport", 320.dp, viewport.right - viewport.left)
        listOf("worship_duha_details", "worship_night_details").forEach { tag ->
            val button = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertIsEnabled()
            savePreview("$tag-font-$suffix", "voluntary_prayer_screen")
            assertCompleteControl(button, viewport.left.value, viewport.right.value)
            button.performClick()
            compose.onNodeWithTag("worship_time_explanation").assertIsDisplayed()
            val explanationId = if (tag == "worship_duha_details") R.string.worship_duha_explanation else R.string.worship_night_explanation
            val explanationLayouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(text(explanationId)).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(explanationLayouts) }
            assertTrue("The modal explanation has a measured layout", explanationLayouts.isNotEmpty())
            explanationLayouts.forEach {
                assertEquals("The separate modal window uses the requested font scale", fontScale, it.layoutInput.density.fontScale, 0f)
            }
            val source = compose.onNodeWithText(text(R.string.worship_source_prayer_times)).performScrollTo().assertIsDisplayed()
            savePreview("$tag-source-font-$suffix", "worship_time_explanation")
            assertCompleteControl(source, viewport.left.value, viewport.right.value)
            val close = compose.onNodeWithTag("worship_time_close").performScrollTo().assertIsDisplayed()
            assertCompleteControl(close, viewport.left.value, viewport.right.value)
            savePreview("$tag-sheet-font-$suffix", "worship_time_explanation")
            close.performClick()
            compose.onNodeWithTag("worship_time_explanation").assertDoesNotExist()
        }
    }

    private fun assertCompleteControl(node: SemanticsNodeInteraction, left: Float, right: Float) {
        val bounds = node.getUnclippedBoundsInRoot()
        assertTrue("Control stays inside the left edge", bounds.left.value >= left - 1f)
        assertTrue("Control stays inside the right edge", bounds.right.value <= right + 1f)
        assertTrue("Control keeps a 48dp touch target", bounds.bottom - bounds.top >= 48.dp)
        // The mdpi fixture makes these pixel bounds directly comparable to dp. A partially
        // visible node can pass assertIsDisplayed, so check its entire target after scrolling.
        val visible = node.fetchSemanticsNode().boundsInRoot
        assertTrue("No ancestor clips the control horizontally", visible.width + 1f >= (bounds.right - bounds.left).value)
        assertTrue("No ancestor clips the control vertically", visible.height + 1f >= (bounds.bottom - bounds.top).value)
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Control text has a layout", layouts.isNotEmpty())
        layouts.forEach { layout ->
            val diagnostic = "text=${layout.layoutInput.text}, size=${layout.size}, paragraphWidth=${layout.multiParagraph.width}, " +
                "constraints=${layout.layoutInput.constraints}, alignment=${layout.layoutInput.style.textAlign}, " +
                "fontScale=${layout.layoutInput.density.fontScale}, control=$bounds, visible=$visible, lines=" +
                (0 until layout.lineCount).joinToString { "${layout.getLineLeft(it)}..${layout.getLineRight(it)}" }
            assertFalse("Control text must grow to fit vertically", layout.didOverflowHeight)
            // Compose 1.8's Text semantics re-layout at the parent's max width while keeping
            // the original tight Text size. Compare glyph bounds after undoing that alignment
            // offset; didOverflowWidth compares these mismatched widths and reports false clips.
            val extraWidth = (layout.multiParagraph.width - layout.size.width).coerceAtLeast(0f)
            for (line in 0 until layout.lineCount) {
                assertFalse("Control text must not be ellipsized", layout.isLineEllipsized(line))
                val rtl = layout.getParagraphDirection(layout.getLineStart(line)) == ResolvedTextDirection.Rtl
                val horizontalOffset = when (layout.layoutInput.style.textAlign) {
                    TextAlign.Center -> extraWidth / 2f
                    TextAlign.Left -> 0f
                    TextAlign.Right -> extraWidth
                    TextAlign.End -> if (rtl) 0f else extraWidth
                    else -> if (rtl) extraWidth else 0f
                }
                val glyphLeft = layout.getLineLeft(line) - horizontalOffset
                val glyphRight = layout.getLineRight(line) - horizontalOffset
                assertTrue("Control glyphs stay inside the left edge: $diagnostic", glyphLeft >= -1f)
                assertTrue("Control glyphs stay inside the right edge: $diagnostic", glyphRight <= layout.size.width + 1f)
            }
            assertEquals("Every character remains in the layout", layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1))
        }
    }

    private fun openDetails(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.onNodeWithTag("worship_time_explanation").assertIsDisplayed()
    }

    private fun closeDetails() {
        compose.onNodeWithTag("worship_time_close").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("worship_time_explanation").assertDoesNotExist()
    }

    private fun savePreview(name: String, tag: String) {
        compose.waitForIdle()
        compose.onNodeWithTag(tag).assertIsDisplayed()
        // Robolectric cannot dispatch captureToImage's hardware redraw. Draw the actual window
        // synchronously through native graphics, including a modal's window when it is open.
        val bitmap = compose.runOnIdle {
            val view = WindowInspector.getGlobalWindowViews().last { it.isAttachedToWindow && it.width > 0 && it.height > 0 }
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        val output = File("build/outputs/voluntary-prayer-preview/$name.png")
        requireNotNull(output.parentFile).mkdirs()
        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    private fun text(id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun at(value: String): Instant = java.time.LocalDateTime.parse(value).atZone(zone).toInstant()

    private fun dayLabel(value: LocalDate): String = value.format(
        DateTimeFormatter.ofPattern("EEE، d MMM", Locale.forLanguageTag("ar-TN-u-nu-latn")),
    )
}

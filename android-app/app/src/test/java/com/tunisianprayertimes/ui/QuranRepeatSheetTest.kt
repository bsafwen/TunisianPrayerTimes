package com.tunisianprayertimes.ui

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.tunisianprayertimes.quran.QuranCatalog
import com.tunisianprayertimes.quran.QuranPage
import com.tunisianprayertimes.quran.QuranSearchEntry
import com.tunisianprayertimes.quran.QuranSurah
import com.tunisianprayertimes.quran.QuranVerseReference
import com.tunisianprayertimes.quran.audio.QuranRepeatRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ar-rTN-w360dp-h800dp-mdpi", application = Application::class)
class QuranRepeatSheetTest {

    @get:Rule
    val compose = createComposeRule()

    // Three short chapters over two pages; the second page starts inside chapter 2.
    private val catalog = QuranCatalog(
        pages = listOf(QuranPage(1, "quran/pages/1.webp", listOf("الأولى", "الثانية")), QuranPage(2, "quran/pages/2.webp", listOf("الثانية", "الثالثة"))),
        surahs = listOf(QuranSurah(1, "الأولى", 1, 7), QuranSurah(2, "الثانية", 1, 6), QuranSurah(3, "الثالثة", 2, 4)),
        entries = (1..7).map { entry(1, it, 1) } + (1..3).map { entry(2, it, 1) } + (4..6).map { entry(2, it, 2) } +
            (1..4).map { entry(3, it, 2) },
    )
    private var started: QuranRepeatRange? = null

    // Controls are pressed through their click action: pointer input did not reach the sheet's
    // own window in this harness.

    @Test
    fun pressedVerse_opensAsThatVerseRepeatedWithoutEnd() {
        open(QuranVerseReference(2, 2))

        compose.onNodeWithTag("quran_repeat_from_surah").assertTextContains("الثانية", substring = true)
        compose.onNodeWithTag("quran_repeat_from_ayah").assertTextContains("2")
        compose.onNodeWithTag("quran_repeat_to_surah").assertTextContains("الثانية", substring = true)
        compose.onNodeWithTag("quran_repeat_to_ayah").assertTextContains("2")
        compose.onNodeWithTag("quran_repeat_endless").assertIsSelected()
        // No count to edit while the repetition has no end.
        compose.onNodeWithTag("quran_repeat_times").assertDoesNotExist()

        compose.onNodeWithTag("quran_repeat_start").performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(QuranRepeatRange(QuranVerseReference(2, 2), QuranVerseReference(2, 2), null), started)
    }

    @Test
    fun presetCount_andALaterEnd_areReported() {
        open(QuranVerseReference(1, 3))

        compose.onNodeWithTag("quran_repeat_to_ayah").performTextReplacement("6")
        compose.onNodeWithTag("quran_repeat_times_5").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("quran_repeat_endless").assertIsNotSelected()
        compose.onNodeWithTag("quran_repeat_times_5").assertIsSelected()
        compose.onNodeWithTag("quran_repeat_more").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        // Six is no preset: only the count field shows it.
        compose.onNodeWithTag("quran_repeat_times_5").assertIsNotSelected()
        compose.onNodeWithTag("quran_repeat_times").assertTextContains("6")

        compose.onNodeWithTag("quran_repeat_start").performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(QuranRepeatRange(QuranVerseReference(1, 3), QuranVerseReference(1, 6), 6), started)
    }

    @Test
    fun endBeforeTheStart_orAVerseTheChapterLacks_cannotStart() {
        open(QuranVerseReference(2, 4))

        compose.onNodeWithTag("quran_repeat_to_ayah").performTextReplacement("3")
        compose.onNodeWithText("نهاية المقطع تسبق بدايته.").assertExists()
        compose.onNodeWithTag("quran_repeat_start").assertIsNotEnabled()

        // Chapter 2 has six verses.
        compose.onNodeWithTag("quran_repeat_to_ayah").performTextReplacement("7")
        compose.onNodeWithText("نهاية المقطع تسبق بدايته.").assertDoesNotExist()
        compose.onNodeWithTag("quran_repeat_start").assertIsNotEnabled()

        compose.onNodeWithTag("quran_repeat_to_ayah").performTextReplacement("6")
        compose.onNodeWithTag("quran_repeat_start").assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(QuranRepeatRange(QuranVerseReference(2, 4), QuranVerseReference(2, 6), null), started)
    }

    @Test
    fun countOutsideOneTo999_cannotStart() {
        open(QuranVerseReference(1, 1))

        compose.onNodeWithTag("quran_repeat_times_3").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("quran_repeat_times").performTextReplacement("0")
        compose.onNodeWithTag("quran_repeat_start").assertIsNotEnabled()
        compose.onNodeWithTag("quran_repeat_times").performTextReplacement("")
        compose.onNodeWithTag("quran_repeat_start").assertIsNotEnabled()
        // Back to an endless repetition: the unfinished count no longer matters.
        compose.onNodeWithTag("quran_repeat_endless").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("quran_repeat_start").assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)

        assertNull(started?.times)
    }

    @Test
    fun shortcuts_extendTheEndToThePageOrTheChapter() {
        open(QuranVerseReference(1, 5))

        // Page 1 ends on verse 3 of chapter 2.
        compose.onNodeWithTag("quran_repeat_to_page_end").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("quran_repeat_to_surah").assertTextContains("الثانية", substring = true)
        compose.onNodeWithTag("quran_repeat_to_ayah").assertTextContains("3")

        compose.onNodeWithTag("quran_repeat_to_surah_end").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("quran_repeat_to_surah").assertTextContains("الأولى", substring = true)
        compose.onNodeWithTag("quran_repeat_to_ayah").assertTextContains("7")

        compose.onNodeWithTag("quran_repeat_start").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(QuranRepeatRange(QuranVerseReference(1, 5), QuranVerseReference(1, 7), null), started)
    }

    @Test
    fun editingARunningRepetition_opensOnItsRangeAndCount() {
        open(QuranRepeatRange(QuranVerseReference(2, 5), QuranVerseReference(3, 2), 10))

        compose.onNodeWithTag("quran_repeat_from_surah").assertTextContains("الثانية", substring = true)
        compose.onNodeWithTag("quran_repeat_to_surah").assertTextContains("الثالثة", substring = true)
        compose.onNodeWithTag("quran_repeat_times_10").assertIsSelected()
        compose.onNodeWithTag("quran_repeat_times").assertTextContains("10")
    }

    @Test
    fun arabicCounts_agreeWithTheirNumber() {
        assertEquals("مرة واحدة", quranRepeatTimesLabel(1))
        assertEquals("مرتان", quranRepeatTimesLabel(2))
        assertEquals("3 مرات", quranRepeatTimesLabel(3))
        assertEquals("10 مرات", quranRepeatTimesLabel(10))
        assertEquals("11 مرة", quranRepeatTimesLabel(11))
        assertEquals("100 مرة", quranRepeatTimesLabel(100))
        assertEquals("103 مرات", quranRepeatTimesLabel(103))
    }

    @Test
    fun repeatedVerses_areNamedInWords() {
        val one = QuranRepeatRange(QuranVerseReference(2, 5), QuranVerseReference(2, 5))
        assertEquals("سورة الثانية · الآية 5", quranRepeatRangeLabel(catalog, one))
        assertEquals(
            "سورة الثانية · الآيات من 2 إلى 5",
            quranRepeatRangeLabel(catalog, QuranRepeatRange(QuranVerseReference(2, 2), QuranVerseReference(2, 5))),
        )
        assertEquals(
            "من سورة الأولى، الآية 6 إلى سورة الثالثة، الآية 1",
            quranRepeatRangeLabel(catalog, QuranRepeatRange(QuranVerseReference(1, 6), QuranVerseReference(3, 1))),
        )
        assertEquals("تكرار بلا توقف · المرة 4", quranRepeatStatus(one, 4))
        assertEquals("تكرار · المرة 2 من 5", quranRepeatStatus(one.copy(times = 5), 2))
    }

    private fun open(verse: QuranVerseReference) = open(QuranRepeatRange(verse, verse))

    private fun open(range: QuranRepeatRange) {
        compose.setContent {
            AdhkarTheme { QuranRepeatSheet(catalog, range, onStart = { started = it }, onDismiss = {}) }
        }
        compose.waitForIdle()
    }

    private fun entry(surah: Int, ayah: Int, page: Int) = QuranSearchEntry(surah, ayah, "نص $surah $ayah", page)
}

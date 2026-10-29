package com.tunisianprayertimes.ui

import android.app.Application
import android.graphics.Typeface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.height
import com.tunisianprayertimes.quran.QuranFontBook
import com.tunisianprayertimes.quran.QuranFontPage
import com.tunisianprayertimes.quran.QuranFontUnit
import com.tunisianprayertimes.quran.QuranHighlightRect
import com.tunisianprayertimes.quran.QuranReflowPlaced
import com.tunisianprayertimes.quran.QuranUnitKind
import com.tunisianprayertimes.quran.QuranVerseReference
import com.tunisianprayertimes.quran.quranReflow
import com.tunisianprayertimes.quran.rowVerses
import com.tunisianprayertimes.quran.span
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/** Page 603 in large text on a 360dp phone at 2x: «مراجعة»'s taps and strokes on the rows, and the rows read in view. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ar-rTN-w360dp-h800dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuranReflowViewTest {
    @get:Rule val rule = createComposeRule()

    private fun resource(name: String) = checkNotNull(javaClass.classLoader?.getResourceAsStream(name)).use { it.readBytes() }

    private val page = QuranFontPage.parse(resource("quran-font/page_603.bin"))
    private val book = QuranFontBook.parse(resource("quran-font/plates.json").decodeToString())
    // The book's fonts are not among the test resources: the default typeface stands in. It draws none of the glyphs,
    // which neither the rows' layout nor their touch needs.
    private val art = QuranPageArt.Font(page, QuranBookArt(book, book.fonts.map { Typeface.DEFAULT }))
    private val falaq = (1..5).map { QuranVerseReference(113, it) }.toSet()

    // The view's own measures on this screen (mdpi: a dp is a pixel).
    private val magnification = 2f
    private val k = 360f / page.width * magnification
    private val margin = 14f
    private val rowPad = 5f
    private val reflow = quranReflow(page, (360f - 2 * margin) / k)
    private fun rowHeight(i: Int) = reflow.rows[i].let { ((it.bottom - it.top) * k + 2 * rowPad).roundToInt() }
    private fun rowTop(i: Int) = (0 until i).sumOf { rowHeight(it) }
    private var viewHeight = 0f
    private fun inView(i: Int) = rowTop(i) + rowHeight(i) - rowPad <= viewHeight

    private fun verseOf(p: QuranReflowPlaced) = page.units[p.unit].let { QuranVerseReference(it.surah, it.ayah) }
    private fun kindOf(p: QuranReflowPlaced) = page.units[p.unit].kind
    private fun at(row: Int, p: QuranReflowPlaced) =
        Offset(margin + p.span(page).let { (it.start + it.endInclusive) / 2f } * k, rowTop(row) + rowHeight(row) / 2f)
    private fun first(which: (QuranReflowPlaced) -> Boolean) = reflow.rows.indices.filter(::inView)
        .firstNotNullOf { r -> reflow.rows[r].placed.firstOrNull(which)?.let { r to it } }

    private val erased = mutableListOf<Set<QuranVerseReference>>()
    private val taps = mutableListOf<QuranVerseReference?>()
    private val read = mutableListOf<Set<QuranVerseReference>>()
    private var swipes = 0

    private fun around(units: List<QuranFontUnit>) = units.map { u ->
        QuranHighlightRect(u.left / page.width.toFloat(), u.top / page.height.toFloat(), u.right / page.width.toFloat(), u.bottom / page.height.toFloat())
    }

    /**
     * The view on a clock that moves only when told: the eraser's pieces fade on the system's clock, which the test's
     * frames do not move, and would ask for frames without end.
     */
    private fun show(hidden: Set<QuranVerseReference> = emptySet(), erasing: Boolean = false, counting: Boolean = false) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                QuranReflowView(
                    art = art, label = "603", active = true, magnification = magnification,
                    highlightRects = emptyList(), foundRects = emptyList(), hifzRects = emptyList(), selectedRects = emptyList(),
                    bringIntoView = emptyList(), topInsetPx = { 0 }, bottomInsetPx = { 0 },
                    hiddenRects = around(page.units.filter { it.kind == QuranUnitKind.Word && QuranVerseReference(it.surah, it.ayah) in hidden }),
                    erasing = erasing,
                    onErased = { erased += it },
                    onSwipePages = { _, _ -> swipes++ },
                    counting = counting,
                    onRead = { read += it },
                    onTap = { taps += it },
                    onHeading = {},
                )
            }
        }
        rule.mainClock.advanceTimeByFrame()
        viewHeight = rule.onRoot().getUnclippedBoundsInRoot().height.value
    }

    @Test
    fun aTapOnACoveredWordErasesThereAndShowsItsVerse() {
        show(hidden = falaq, erasing = true)
        val (row, word) = first { kindOf(it) == QuranUnitKind.Word && verseOf(it) in falaq }
        rule.onRoot().performTouchInput { click(at(row, word)) }
        assertTrue(verseOf(word) in erased.single())
        assertTrue(taps.isEmpty())
    }

    @Test
    fun aTapOnAVersesNumberOpensIt() {
        show(hidden = falaq, erasing = true)
        val (row, number) = first { kindOf(it) == QuranUnitKind.Rosette && verseOf(it) in falaq }
        rule.onRoot().performTouchInput { click(at(row, number)) }
        assertEquals(listOf(verseOf(number)), taps)
        assertTrue(erased.isEmpty())
    }

    @Test
    fun outOfReviewATapOnAWordChoosesItsVerse() {
        show()
        val (row, word) = first { kindOf(it) == QuranUnitKind.Word }
        rule.onRoot().performTouchInput { click(at(row, word)) }
        assertEquals(listOf(verseOf(word)), taps)
        assertTrue(erased.isEmpty())
    }

    @Test
    fun aSlowStrokeErasesAndAFastOneTurnsThePage() {
        show(hidden = falaq, erasing = true)
        // A covered word with covered text to its left on its row: the stroke goes along it, as one reads.
        val (row, word) = first { p -> kindOf(p) == QuranUnitKind.Word && verseOf(p) in falaq && p.span(page).start * k > 80f }
        val from = at(row, word)
        rule.onRoot().performTouchInput { swipe(from, from - Offset(40f, 0f), 600) }
        assertTrue(erased.flatten().contains(verseOf(word)))
        assertEquals(0, swipes)
        rule.onRoot().performTouchInput { swipe(from, from - Offset(120f, 0f), 60) }
        assertEquals(1, swipes)
    }

    @Test
    fun theVersesReadAreThoseWhoseRowsStayedInView() {
        show(counting = true)
        rule.mainClock.advanceTimeBy(19_000)
        assertTrue(read.isEmpty())
        rule.mainClock.advanceTimeBy(1_500)
        val rowVerses = reflow.rowVerses(page)
        val all = rowVerses.flatten().toSet()
        val expected = all.filter { v -> rowVerses.indices.filter { v in rowVerses[it] }.all(::inView) }.toSet()
        assertTrue("the page is longer than the screen", expected.isNotEmpty() && expected.size < all.size)
        assertEquals(expected, read.flatten().toSet())
    }

    @Test
    fun coveredVersesAreNotRead() {
        val ikhlas = (1..4).map { QuranVerseReference(112, it) }.toSet()
        show(hidden = ikhlas, counting = true)
        rule.mainClock.advanceTimeBy(21_000)
        assertTrue("al-Ikhlas is in view", reflow.rows.indices.filter(::inView).any { r -> reflow.rowVerses(page)[r].any { it in ikhlas } })
        assertTrue(read.isNotEmpty())
        assertTrue(read.flatten().none { it in ikhlas })
    }
}

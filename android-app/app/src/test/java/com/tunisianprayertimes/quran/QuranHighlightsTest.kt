package com.tunisianprayertimes.quran

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuranHighlightsTest {
    // Two text lines of a page: verse 1 fills the first line's right part, verse 2 its left part and the second line.
    private val first = QuranVerseReference(2, 1)
    private val second = QuranVerseReference(2, 2)
    private val highlights = QuranHighlights(mapOf(
        7 to mapOf(
            first to listOf(QuranHighlightRect(.50f, .100f, .90f, .160f)),
            second to listOf(QuranHighlightRect(.10f, .100f, .50f, .160f), QuranHighlightRect(.10f, .164f, .90f, .224f)),
        ),
    ))

    @Test
    fun touchInsideAVerse_selectsIt() {
        assertEquals(first, highlights.verseAt(7, .70f, .130f))
        assertEquals(second, highlights.verseAt(7, .30f, .130f))
        assertEquals(second, highlights.verseAt(7, .70f, .200f))
    }

    @Test
    fun touchBetweenTwoLines_selectsTheNearestVerse() {
        // The 0.004-high gap under the first line: nearer to the line above, then to the one below.
        assertEquals(first, highlights.verseAt(7, .70f, .161f))
        assertEquals(second, highlights.verseAt(7, .70f, .1635f))
    }

    @Test
    fun touchJustOutsideTheText_stillSelectsTheLine() {
        assertEquals(first, highlights.verseAt(7, .905f, .130f))
        assertEquals(second, highlights.verseAt(7, .50f, .230f))
    }

    @Test
    fun marginsHeadingsAndOtherPages_selectNothing() {
        assertNull(highlights.verseAt(7, .97f, .130f))
        assertNull(highlights.verseAt(7, .50f, .50f))
        assertNull(highlights.verseAt(7, .50f, .05f))
        // Appendix pages carry no verse geometry.
        assertNull(highlights.verseAt(610, .50f, .130f))
    }
}

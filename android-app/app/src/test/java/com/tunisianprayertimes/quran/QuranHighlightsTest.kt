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

    @Test
    fun lineAt_boundsTheLineUnderThePoint() {
        // Either verse on the first line gives its height; the second line has its own.
        assertEquals(.100f, highlights.lineAt(7, .70f, .130f)?.top)
        assertEquals(.160f, highlights.lineAt(7, .30f, .130f)?.bottom)
        assertEquals(.164f, highlights.lineAt(7, .70f, .200f)?.top)
    }

    @Test
    fun covers_meetHalfwayBetweenLines_andLeaveAFarSideAlone() {
        // Verse 2 on both lines: its first line grows down to the middle of the gap, its second up to it.
        val grown = highlights.covers(7, highlights.rectangles(7, 2, 2))
        assertEquals(.100f, grown[0].top)
        assertEquals(.162f, grown[0].bottom, 1e-6f)
        assertEquals(.162f, grown[1].top, 1e-6f)
        assertEquals(.224f, grown[1].bottom)
        // Verse 1 on the first line: no line above it, the line below in its column.
        val same = highlights.covers(7, highlights.rectangles(7, 2, 1)).single()
        assertEquals(.100f, same.top)
        assertEquals(.162f, same.bottom, 1e-6f)
    }

    @Test
    fun covers_meetHalfwayBesideTheNextVerseOnTheLine_butNotAcrossAWideSpace() {
        // On one line: verse 3 on the right, verse 4 0.006 to its left, verse 5 0.05 further on.
        val line = QuranHighlights(mapOf(
            9 to mapOf(
                QuranVerseReference(2, 3) to listOf(QuranHighlightRect(.506f, .100f, .90f, .160f)),
                QuranVerseReference(2, 4) to listOf(QuranHighlightRect(.30f, .100f, .50f, .160f)),
                QuranVerseReference(2, 5) to listOf(QuranHighlightRect(.10f, .100f, .25f, .160f)),
            ),
        ))
        val right = line.covers(9, line.rectangles(9, 2, 3)).single()
        assertEquals(.503f, right.left, 1e-6f)
        assertEquals(.90f, right.right)
        val middle = line.covers(9, line.rectangles(9, 2, 4)).single()
        assertEquals(.503f, middle.right, 1e-6f)
        assertEquals(.30f, middle.left)
    }

    @Test
    fun lineAt_betweenTwoLines_takesTheNearest() {
        assertEquals(.100f, highlights.lineAt(7, .70f, .161f)?.top)
        assertEquals(.164f, highlights.lineAt(7, .70f, .1635f)?.top)
    }

    @Test
    fun lineAt_findsALineExactlyWhereVerseAtFindsAVerse() {
        for (x in listOf(.905f, .911f, .913f, .93f, .97f, .50f)) for (y in listOf(.05f, .130f, .161f, .230f, .237f, .50f)) {
            assertEquals("($x, $y)", highlights.verseAt(7, x, y) != null, highlights.lineAt(7, x, y) != null)
        }
        assertNull(highlights.lineAt(610, .50f, .130f))
    }
}

package com.tunisianprayertimes.quran

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun headingAt_findsTheFrame_notTheBasmalaUnderIt_norAnotherPage() {
        // Surah 3's frame above its basmala line, on page 7 under the two lines of surah 2.
        val page = QuranHighlights(
            mapOf(7 to mapOf(QuranVerseReference(3, 1) to listOf(QuranHighlightRect(.10f, .430f, .90f, .490f)))),
            mapOf(7 to mapOf(3 to QuranHighlightRect(.10f, .240f, .90f, .300f))),
        )
        assertEquals(3, page.headingAt(7, .50f, .270f))
        assertEquals(3, page.headingAt(7, .10f, .240f))
        // The basmala line, between the frame and verse 1, and the margin beside the frame: paper.
        assertNull(page.headingAt(7, .50f, .340f))
        assertNull(page.headingAt(7, .95f, .270f))
        assertNull(page.headingAt(8, .50f, .270f))
        assertEquals(setOf(3), page.headingsOn(7))
        assertNull(page.heading(8, 3))
    }

    @Test
    fun headingsAsset_oneFramePerSurah_onItsFirstVersesPage_aboveIt_andClearOfEveryVerse() {
        val pages = org.json.JSONObject(java.io.File("src/main/assets/quran/highlights.json").readText()).getJSONArray("pages")
        val verses = List(pages.length()) { pages.getJSONObject(it) }.associate { page ->
            val list = page.getJSONArray("verses")
            page.getInt("page") to List(list.length()) { list.getJSONObject(it) }.associate { verse ->
                val boxes = verse.getJSONArray("rects")
                QuranVerseReference(verse.getInt("surah"), verse.getInt("ayah")) to List(boxes.length()) { k ->
                    val r = boxes.getJSONArray(k)
                    QuranHighlightRect(r.getDouble(0).toFloat(), r.getDouble(1).toFloat(), r.getDouble(2).toFloat(), r.getDouble(3).toFloat())
                }
            }
        }
        val headings = org.json.JSONObject(java.io.File("src/main/assets/quran/headings.json").readText()).getJSONArray("headings")
        val surahs = (0 until headings.length()).map { i ->
            val heading = headings.getJSONObject(i)
            val surah = heading.getInt("surah")
            val page = heading.getInt("page")
            val box = heading.getJSONArray("rect")
            val frame = QuranHighlightRect(box.getDouble(0).toFloat(), box.getDouble(1).toFloat(), box.getDouble(2).toFloat(), box.getDouble(3).toFloat())
            val first = verses.getValue(page)[QuranVerseReference(surah, 1)]
            assertTrue("surah $surah: verse 1 on page $page", first != null)
            assertTrue("surah $surah: above verse 1", frame.bottom <= first!!.minOf { it.top })
            // About a text line tall: the frame alone, without the basmala line under it.
            assertTrue("surah $surah: one line tall", frame.bottom - frame.top in .055f..0.075f)
            verses.getValue(page).values.flatten().forEach { r ->
                assertTrue("surah $surah: clear of $r", r.bottom <= frame.top || r.top >= frame.bottom || r.right <= frame.left || r.left >= frame.right)
            }
            surah
        }
        assertEquals((1..114).toList(), surahs.sorted())
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

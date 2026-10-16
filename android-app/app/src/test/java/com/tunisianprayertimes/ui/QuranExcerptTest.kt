package com.tunisianprayertimes.ui

import com.tunisianprayertimes.quran.QuranCatalog
import com.tunisianprayertimes.quran.QuranHighlightRect
import com.tunisianprayertimes.quran.QuranHighlights
import com.tunisianprayertimes.quran.QuranPage
import com.tunisianprayertimes.quran.QuranSearchEntry
import com.tunisianprayertimes.quran.QuranSurah
import com.tunisianprayertimes.quran.QuranVerseReference
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The bands of the mushaf the hifz sheets show, cut from the bundled index and verse places. */
class QuranExcerptTest {
    private val catalog: QuranCatalog by lazy {
        val source = JSONObject(File("src/main/assets/quran/index.json").readText())
        val chapters = source.getJSONArray("surahs")
        val surahs = List(chapters.length()) { i ->
            val chapter = chapters.getJSONObject(i)
            QuranSurah(chapter.getInt("number"), chapter.getString("name"), chapter.getInt("page"), chapter.optInt("verseCount"))
        }
        val rows = source.getJSONArray("entries")
        val entries = List(rows.length()) { i ->
            val entry = rows.getJSONObject(i)
            QuranSearchEntry(
                surah = entry.getInt("surah"), ayah = entry.getInt("ayah"), text = entry.getString("text"), page = entry.getInt("page"),
                isFirstFragment = entry.getBoolean("isFirstFragment"), isLastFragment = entry.getBoolean("isLastFragment"),
            )
        }
        QuranCatalog((1..603).map { QuranPage(it, "", emptyList()) }, surahs, entries)
    }

    private val highlights: QuranHighlights by lazy {
        val pages = JSONObject(File("src/main/assets/quran/highlights.json").readText()).getJSONArray("pages")
        QuranHighlights(List(pages.length()) { i -> pages.getJSONObject(i) }.associate { page ->
            val verses = page.getJSONArray("verses")
            page.getInt("page") to List(verses.length()) { j -> verses.getJSONObject(j) }.associate { verse ->
                val boxes = verse.getJSONArray("rects")
                QuranVerseReference(verse.getInt("surah"), verse.getInt("ayah")) to List(boxes.length()) { k ->
                    val r = boxes.getJSONArray(k)
                    QuranHighlightRect(r.getDouble(0).toFloat(), r.getDouble(1).toFloat(), r.getDouble(2).toFloat(), r.getDouble(3).toFloat())
                }
            }
        })
    }

    private fun verse(surah: Int, ayah: Int) = QuranVerseReference(surah, ayah)
    private fun rects(page: Int, v: QuranVerseReference) = highlights.rectangles(page, v.surah, v.ayah)

    @Test
    fun aVerseWithTheEndOfTheOneBefore_isOneBandOnItsPage() {
        // Fussilat 36, the verse of the report: the end of 35 is on its page, just above it.
        val asked = verse(41, 36)
        val page = catalog.pagesForVerse(41, 36).single()
        val bands = quranExcerpts(highlights, catalog, asked, linesBefore = 1)
        val band = bands.single()
        assertEquals(page, band.page)
        assertEquals(rects(page, asked), band.marked)
        assertTrue("no grey and no cover here", band.muted.isEmpty() && band.covered.isEmpty())
        val endOfBefore = rects(page, verse(41, 35)).maxBy { it.top }
        assertTrue("the band shows the last line of 35", band.top <= endOfBefore.top && band.bottom >= rects(page, asked).maxOf { it.bottom })
    }

    @Test
    fun aVerseThatOpensItsPage_hasTheEndOfTheOneBeforeOnThePageBefore() {
        val asked = catalog.verses.first { v ->
            v.ayah > 1 && v.pages.size == 1 && catalog.pagesForVerse(v.surah, v.ayah - 1).lastOrNull() == v.pages.first() - 1
        }.reference
        val page = catalog.pagesForVerse(asked.surah, asked.ayah).single()
        val bands = quranExcerpts(highlights, catalog, asked, linesBefore = 1)
        assertEquals(listOf(page - 1, page), bands.map { it.page })
        assertTrue("the verse before is only context", bands[0].marked.isEmpty())
        assertEquals(rects(page, asked), bands[1].marked)
    }

    @Test
    fun aQuestionCoversTheVerseAndWhatFollowsOnItsLines_neverTheVerseBefore() {
        val asked = verse(41, 36)
        val page = catalog.pagesForVerse(41, 36).single()
        val band = quranExcerpts(highlights, catalog, asked, linesBefore = 1, cover = true).single()
        assertTrue(band.covered.containsAll(rects(page, asked)))
        assertTrue(rects(page, verse(41, 35)).none { it in band.covered })
        assertTrue("only what the band shows", band.covered.all { it.bottom > band.top && it.top < band.bottom })
    }

    @Test
    fun aLookAlikeIsGreyNotViolet() {
        val other = verse(2, 58)
        val band = quranExcerpts(highlights, catalog, other, mark = QuranExcerptMark.Other).first()
        assertTrue(band.marked.isEmpty())
        assertEquals(rects(band.page, other), band.muted)
    }

    @Test
    fun aSkipShowsTheWayThroughToWhereItLanded() {
        // The skipped verse between the end of the one before and the first line of where the recitation went.
        val skipped = verse(41, 36)
        val landed = verse(41, 37)
        val bands = quranExcerpts(highlights, catalog, skipped, linesBefore = 1, linesAfter = 1, after = landed)
        val landedPage = catalog.pagesForVerse(41, 37).first()
        val last = bands.last()
        assertEquals(landedPage, last.page)
        assertTrue(last.bottom >= rects(landedPage, landed).minBy { it.top }.bottom)
    }

    @Test
    fun theUnknownVerseHasNoBand() {
        assertTrue(quranExcerpts(highlights, catalog, verse(41, 999)).isEmpty())
    }

    @Test
    fun placeOnThePage() {
        assertEquals("أعلى الصفحة", quranExcerptPlace(QuranExcerpt(1, 0.05f, 0.2f)))
        assertEquals("وسط الصفحة", quranExcerptPlace(QuranExcerpt(1, 0.4f, 0.55f)))
        assertEquals("أسفل الصفحة", quranExcerptPlace(QuranExcerpt(1, 0.8f, 0.95f)))
        assertNotNull(quranExcerptPlace(QuranExcerpt(1, 0f, 1f)))
    }
}

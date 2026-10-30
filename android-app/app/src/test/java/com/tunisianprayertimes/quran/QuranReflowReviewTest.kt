package com.tunisianprayertimes.quran

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** «مراجعة» and hifz on page 603's rows in large text: what a cover hides, what a stroke shows, what counts as read. */
class QuranReflowReviewTest {
    private fun resource(name: String) = checkNotNull(javaClass.classLoader?.getResourceAsStream(name)).use { it.readBytes() }

    private val page = QuranFontPage.parse(
        resource("quran-font/qbp3/page_603.bin"), QuranFontBook.parse(resource("quran-font/qbp3/plates.json").decodeToString()),
    )
    private val reflow = quranReflow(page, page.width / 2.4f)
    private val falaq = (1..5).map { QuranVerseReference(113, it) }.toSet()

    private fun verseOf(p: QuranReflowPlaced) = page.units[p.unit].let { QuranVerseReference(it.surah, it.ayah) }
    private fun kindOf(p: QuranReflowPlaced) = page.units[p.unit].kind
    private fun middle(p: QuranReflowPlaced) = p.span(page).let { (it.start + it.endInclusive) / 2f }

    @Test
    fun coversHideTheWordsOfCoveredVersesAndLeaveTheirNumbers() {
        for (row in reflow.rows) {
            val covers = row.covers(page, falaq, emptySet())
            for (p in row.placed) {
                val under = covers.any { middle(p) in it.left..it.right }
                if (kindOf(p) == QuranUnitKind.Word) assertEquals("word ${p.unit}", verseOf(p) in falaq, under)
                else assertFalse("${kindOf(p)} ${p.unit} covered", under)
            }
        }
        assertTrue(reflow.rows.any { it.covers(page, falaq, emptySet()).isNotEmpty() })
        assertTrue(reflow.rows.all { it.covers(page, emptySet(), emptySet()).isEmpty() })
    }

    @Test
    fun theVerseAskedHasCoversOfItsOwn() {
        val asked = setOf(QuranVerseReference(113, 2))
        for (row in reflow.rows) for (cover in row.covers(page, falaq, asked)) {
            val words = row.placed.filter { kindOf(it) == QuranUnitKind.Word && middle(it) in cover.left..cover.right }
            assertTrue(words.isNotEmpty())
            assertTrue(words.all { (verseOf(it) in asked) == cover.asked })
        }
        assertTrue(reflow.rows.any { row -> row.covers(page, falaq, asked).any { it.asked } })
    }

    @Test
    fun aStrokeShowsTheVersesOfTheWordsUnderIt() {
        for (row in reflow.rows) {
            for (p in row.placed) if (kindOf(p) == QuranUnitKind.Word) {
                assertTrue(verseOf(p) in row.versesUnder(page, middle(p) - 0.5f, middle(p) + 0.5f))
            }
            val words = row.placed.filter { kindOf(it) == QuranUnitKind.Word }.map { verseOf(it) }.toSet()
            assertEquals(words, row.versesUnder(page, -1f, page.width.toFloat()))
        }
    }

    @Test
    fun aVersesNumberOpensIt() {
        val slop = 14f
        for (row in reflow.rows) for (p in row.placed) {
            val x = middle(p)
            when (kindOf(p)) {
                QuranUnitKind.Rosette -> assertEquals(verseOf(p), row.numberAt(page, x, slop))
                QuranUnitKind.Word -> if (row.placed.none { kindOf(it) == QuranUnitKind.Rosette && it.distanceTo(page, x) <= slop }) {
                    assertNull(row.numberAt(page, x, slop))
                }
                else -> Unit
            }
        }
    }

    @Test
    fun aVerseIsReadOnceEachOfItsRowsHasBeenInViewLongEnough() {
        val rowVerses = reflow.rowVerses(page)
        val seen = QuranRowsSeen(reflow.rows.size, 20_000L)
        val rowsOf = rowVerses.withIndex().flatMap { (r, verses) -> verses.map { it to r } }.groupBy({ it.first }, { it.second })
        val (verse, rows) = rowsOf.entries.first { it.value.size > 1 }
        seen.shown(rows.dropLast(1), 20_000L)
        assertFalse(verse in seen.read(rowVerses))
        seen.shown(listOf(rows.last()), 19_750L)
        assertFalse(verse in seen.read(rowVerses))
        seen.shown(listOf(rows.last()), 250L)
        assertTrue(verse in seen.read(rowVerses))
        // Given once a visit.
        seen.shown(rows, 60_000L)
        assertFalse(verse in seen.read(rowVerses))
    }

    @Test
    fun timeInViewAddsUp() {
        val rowVerses = reflow.rowVerses(page)
        val seen = QuranRowsSeen(reflow.rows.size, 20_000L)
        repeat(79) { seen.shown(reflow.rows.indices, 250L) }
        assertTrue(seen.read(rowVerses).isEmpty())
        seen.shown(reflow.rows.indices, 250L)
        assertEquals(rowVerses.flatten().toSet(), seen.read(rowVerses))
    }

    @Test
    fun coveredWordsAreNeverRead() {
        val rowVerses = reflow.rowVerses(page, falaq)
        assertTrue(rowVerses.none { verses -> verses.any { it in falaq } })
        val seen = QuranRowsSeen(reflow.rows.size, 20_000L)
        seen.shown(reflow.rows.indices, 20_000L)
        val read = seen.read(rowVerses)
        assertTrue(read.isNotEmpty() && read.none { it in falaq })
    }

    @Test
    fun theRowsOfAPlaceOnThePage() {
        for ((r, row) in reflow.rows.withIndex()) {
            val u = page.units[row.placed.first().unit]
            val around = QuranHighlightRect(
                u.left / page.width.toFloat(), u.top / page.height.toFloat(), u.right / page.width.toFloat(), u.bottom / page.height.toFloat(),
            )
            assertTrue(r in reflow.rowsIn(page, listOf(around)))
        }
        assertTrue(reflow.rowsIn(page, emptyList()).isEmpty())
    }
}

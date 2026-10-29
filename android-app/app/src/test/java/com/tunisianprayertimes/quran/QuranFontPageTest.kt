package com.tunisianprayertimes.quran

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Page 603 in the book's type: the end of al-Masad, then al-Ikhlas, al-Falaq and an-Nas, each with its heading and basmala. */
class QuranFontPageTest {
    private fun resource(name: String) = checkNotNull(javaClass.classLoader?.getResourceAsStream(name)).use { it.readBytes() }

    private val page = QuranFontPage.parse(resource("quran-font/page_603.bin"))
    private val book = QuranFontBook.parse(resource("quran-font/plates.json").decodeToString())

    @Test
    fun readsThePageAndItsUnits() {
        assertEquals(603, page.page)
        assertEquals(1717 to 2662, page.width to page.height)
        assertEquals(9, page.baselines.size)
        val kinds = page.units.groupingBy { it.kind }.eachCount()
        assertEquals(3, kinds[QuranUnitKind.Heading])
        assertEquals(3, kinds[QuranUnitKind.Basmala])
        assertEquals(15, kinds[QuranUnitKind.Rosette])
        assertTrue(page.units.all { !it.draw.isEmpty })
    }

    @Test
    fun drawsFromTheBooksFontsAndPlates() {
        val draws = page.units.map { it.draw } + page.furniture
        for (d in draws) {
            assertTrue(d.fonts.all { it in book.fonts.indices })
            assertTrue(d.codePoints.all { it in 0xF0000..0xFFFFD })
            assertTrue(d.plates.all { it.plate in book.plates.indices })
        }
        // The ornaments the print set from one plate: the frame, each heading's band, the toned verse rosettes (a few
        // of this page's are printed solid black, and are their own glyphs).
        assertEquals(1, page.furniture.plates.size)
        assertTrue(page.units.filter { it.kind == QuranUnitKind.Heading }.all { it.draw.plates.size == 1 })
        assertTrue(page.units.filter { it.kind == QuranUnitKind.Rosette }.count { it.draw.plates.size == 1 } >= 10)
    }

    @Test
    fun wordsKeepTheirVerses() {
        val ikhlas = page.units.filter { it.kind == QuranUnitKind.Word && it.surah == 112 }
        assertEquals((1..4).toList(), ikhlas.map { it.ayah }.distinct())
        assertTrue(page.units.filter { it.kind == QuranUnitKind.Word }.all { it.text.isNotEmpty() })
    }

    @Test
    fun reflowPlacesEveryUnitOnceInOrder() {
        val width = page.width / 2.4f
        val reflow = quranReflow(page, width)
        val placed = reflow.rows.flatMap { row -> row.placed.map { it.unit } }
        assertEquals(page.units.indices.toList(), placed)
        for (row in reflow.rows) for (p in row.placed) {
            val u = page.units[p.unit]
            val right = u.right + p.dx
            val left = u.right + p.dx - (u.right - u.left) * p.scale
            assertTrue("unit ${p.unit} leaves its row", left >= -1f && right <= width + 1f)
        }
        assertTrue(reflow.rows.zipWithNext().all { (a, b) -> b.top >= a.bottom - 0.5f })
    }

    @Test
    fun headingsAndBasmalasHaveRowsOfTheirOwn() {
        val reflow = quranReflow(page, page.width / 2.4f)
        for (row in reflow.rows) {
            val kinds = row.placed.map { page.units[it.unit].kind }
            if (QuranUnitKind.Heading in kinds || QuranUnitKind.Basmala in kinds) assertEquals(1, kinds.size)
        }
    }
}

package com.tunisianprayertimes.quran

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The book's 'QBP3' layouts decode to exactly what the same pages hold as 'QBP1' (fixtures from the book build's
 * book_small, 2026-10-30: pages 1 and 2 with their own frames, 3, 333, 590 and 603).
 */
class QuranLayoutDecoderTest {
    private fun resource(name: String) = checkNotNull(javaClass.classLoader?.getResourceAsStream(name)).use { it.readBytes() }

    private val book = QuranFontBook.parse(resource("quran-font/qbp3/plates.json").decodeToString())

    private fun assertSameDraw(where: String, a: QuranFontDraw, b: QuranFontDraw) {
        assertArrayEquals(where, a.fonts, b.fonts)
        assertArrayEquals(where, a.codePoints, b.codePoints)
        assertArrayEquals(where, a.xs, b.xs, 0f)
        assertArrayEquals(where, a.ys, b.ys, 0f)
        assertArrayEquals(where, a.colours, b.colours)
        assertEquals(where, a.plates.map { it.plate }, b.plates.map { it.plate })
        for ((pa, pb) in a.plates.zip(b.plates)) assertArrayEquals(where, pa.matrix, pb.matrix, 0f)
    }

    @Test
    fun decodesAsTheSamePagesInQbp1() {
        for (number in listOf(1, 2, 3, 333, 590, 603)) {
            val name = "quran-font/qbp3/page_%03d".format(number)
            val coded = resource("$name.bin")
            assertEquals('3'.code.toByte(), coded[3])
            val a = QuranFontPage.parse(resource("$name.qbp1.bin"))
            val b = QuranFontPage.parse(coded, book)
            assertEquals(number, b.page)
            assertEquals(a.page to (a.width to a.height), b.page to (b.width to b.height))
            assertEquals(a.upm to a.unitsPerPixel, b.upm to b.unitsPerPixel)
            assertArrayEquals(a.baselines, b.baselines)
            assertSameDraw("page $number furniture", a.furniture, b.furniture)
            assertEquals(a.units.size, b.units.size)
            for ((i, pair) in a.units.zip(b.units).withIndex()) {
                val (ua, ub) = pair
                val where = "page $number unit $i"
                assertEquals(where, listOf(ua.kind, ua.surah, ua.ayah, ua.token, ua.line, ua.tied, ua.text),
                    listOf(ub.kind, ub.surah, ub.ayah, ub.token, ub.line, ub.tied, ub.text))
                assertEquals(where, listOf(ua.left, ua.top, ua.right, ua.bottom), listOf(ub.left, ub.top, ub.right, ub.bottom))
                assertSameDraw(where, ua.draw, ub.draw)
            }
        }
    }

    @Test(expected = IllegalStateException::class)
    fun aQbp3PageNeedsItsBooksTables() {
        QuranFontPage.parse(resource("quran-font/qbp3/page_603.bin"))
    }
}

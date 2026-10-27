package com.tunisianprayertimes.quran

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Page 603 as redrawn: the end of al-Masad, then al-Ikhlas, al-Falaq and an-Nas, each with its heading and basmala. */
class QuranVectorPageTest {
    private val page = QuranVectorPage.read(
        checkNotNull(javaClass.classLoader?.getResourceAsStream("quran-vector/page_603.qvp")).use { it.readBytes() },
    )

    @Test
    fun readsThePageAndItsUnits() {
        assertEquals(603, page.page)
        assertEquals(1717 to 2662, page.width to page.height)
        assertEquals(9, page.baselines.size)
        val kinds = page.units.groupingBy { it.kind }.eachCount()
        assertEquals(3, kinds[QuranVectorUnitKind.Heading])
        assertEquals(3, kinds[QuranVectorUnitKind.Basmala])
        assertEquals(15, kinds[QuranVectorUnitKind.Rosette])
        assertTrue(page.units.all { it.pieces.isNotEmpty() })
        // Every piece is a word's, a ring's, a heading's or furniture, and only one of them.
        val owned = page.units.flatMap { it.pieces.toList() } + page.furniture.toList()
        assertEquals(owned.size, owned.toSet().size)
    }

    @Test
    fun everyLayerHoldsItsSegmentsPoints() {
        for (piece in page.pieces) for (layer in piece.layers) {
            assertEquals(layer.segments.sum(), layer.kinds.size)
            assertEquals(layer.kinds.sumOf { if (it) 3 else 2 } * 2, layer.points.size)
            assertEquals(layer.segments.size * 2, layer.starts.size)
        }
    }

    @Test
    fun theOutlinesStayInsideTheirPiece() {
        // Points on the outline only: a cubic's two control points may lie off the shape.
        val slack = 2f
        for (piece in page.pieces) for (layer in piece.layers) {
            if (layer.clip != null) continue
            var p = 0
            for (cubic in layer.kinds) {
                val onCurve = if (cubic) listOf(p + 2) else listOf(p, p + 1)
                for (k in onCurve) {
                    val x = layer.points[2 * k] / QuranVectorPage.STEPS
                    val y = layer.points[2 * k + 1] / QuranVectorPage.STEPS
                    assertTrue(x >= piece.left - slack && x <= piece.right + slack && y >= piece.top - slack && y <= piece.bottom + slack)
                }
                p += if (cubic) 3 else 2
            }
        }
    }

    @Test
    fun reflowPlacesEveryUnitOnceInOrder() {
        val width = page.width / 2.4f
        val reflow = quranReflow(page, width)
        val placed = reflow.rows.flatMap { row -> row.placed.map { it.unit } }
        val expected = page.units.indices.filter { page.units[it].pieces.isNotEmpty() }
        assertEquals(expected, placed)
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
            if (QuranVectorUnitKind.Heading in kinds || QuranVectorUnitKind.Basmala in kinds) assertEquals(1, kinds.size)
        }
    }
}

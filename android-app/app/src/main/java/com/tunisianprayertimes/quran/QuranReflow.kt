package com.tunisianprayertimes.quran

/**
 * A redrawn page's words rewrapped into narrower lines for large text: whole words in reading order, each row filled
 * from the right and justified by its spaces, every word drawn from the print's own outlines. Nothing is ever cut: a
 * word is one unit, and two words whose ink touches on the print stay on one row.
 *
 * Measures are in scan pixels, the page's own; the caller scales them to the screen.
 */
internal class QuranReflow(val rows: List<QuranReflowRow>, val height: Float)

/** One row: its units from right to left, [baseline] down from the top of the layout. */
internal class QuranReflowRow(val baseline: Float, val top: Float, val bottom: Float, val placed: List<QuranReflowPlaced>)

/**
 * A unit drawn where the print has it, shrunk by [scale] about its own top right corner (when it is wider than a row:
 * a surah heading on a narrow screen), then moved by ([dx], [dy]): a point p of it lands at
 * corner + (dx, dy) + scale × (p − corner), corner being (right, top).
 */
internal class QuranReflowPlaced(val unit: Int, val dx: Float, val dy: Float, val scale: Float = 1f)

/**
 * [width]: the row's width; [gap]: the space between two words, as printed; [lineGap]: the space between rows beyond
 * their ink. A row is justified when its spaces need not grow past [maxStretch] times [gap]; otherwise, and on the
 * last row of a run (before a heading or at the page's end), its words keep [gap] and start at the right.
 */
internal fun quranReflow(
    page: QuranVectorPage,
    width: Float,
    gap: Float = 18f,
    lineGap: Float = 26f,
    maxStretch: Float = 3f,
): QuranReflow {
    val units = page.units.withIndex().filter { (_, u) -> u.pieces.isNotEmpty() }
    // Clusters: a unit with the units tied to it, which never part.
    val clusters = mutableListOf<MutableList<Int>>()
    var open = false
    for ((i, u) in units) {
        val block = u.kind == QuranVectorUnitKind.Heading || u.kind == QuranVectorUnitKind.Basmala
        if (open && !block) clusters.last().add(i) else clusters.add(mutableListOf(i))
        open = u.tied && !block
    }
    fun unitWidth(i: Int) = page.units[i].let { (it.right - it.left).toFloat() }
    fun clusterWidth(c: List<Int>): Float {
        val us = c.map { page.units[it] }
        return (us.maxOf { it.right } - us.minOf { it.left }).toFloat()
    }
    fun isBlock(c: List<Int>) = page.units[c.first()].kind.let { it == QuranVectorUnitKind.Heading || it == QuranVectorUnitKind.Basmala }
    fun baselineOf(i: Int): Float {
        val u = page.units[i]
        return if (u.line >= 0 && u.line < page.baselines.size) page.baselines[u.line].toFloat() else u.bottom.toFloat()
    }

    val rows = mutableListOf<QuranReflowRow>()
    var y = 0f
    fun emit(row: List<List<Int>>, justify: Boolean) {
        if (row.isEmpty()) return
        val all = row.flatten()
        if (row.size == 1 && isBlock(row[0])) {
            // A heading or a basmala on its own, centred, shrunk to the row if wider.
            val i = row[0][0]
            val u = page.units[i]
            val w = unitWidth(i)
            val scale = minOf(1f, width / w)
            val top = y + lineGap / 2
            val x = (width - w * scale) / 2f
            val placed = QuranReflowPlaced(i, x + w * scale - u.right, top - u.top, scale)
            val bottom = top + (u.bottom - u.top) * scale
            rows += QuranReflowRow(bottom, top, bottom, listOf(placed))
            y = bottom + lineGap / 2
            return
        }
        val widths = row.map { clusterWidth(it) }
        val total = widths.sum()
        val scale = if (row.size == 1 && total > width) width / total else 1f
        var space = gap
        if (justify && row.size > 1) {
            val even = (width - total) / (row.size - 1)
            if (even <= gap * maxStretch) space = maxOf(gap, even)
        }
        val ascent = all.maxOf { baselineOf(it) - page.units[it].top } * scale
        val descent = all.maxOf { page.units[it].bottom - baselineOf(it) } * scale
        val baseline = y + lineGap / 2 + ascent
        var x = width
        val placed = mutableListOf<QuranReflowPlaced>()
        row.forEachIndexed { k, c ->
            val right = c.maxOf { page.units[it].right }.toFloat()
            for (i in c) {
                // Units of one cluster keep their places relative to each other, as printed. Shrunk about its top
                // right corner: its right edge lands where the cluster's puts it, its baseline on the row's.
                val u = page.units[i]
                val dx = x - (right - u.right) * scale - u.right
                val dy = baseline - u.top - (baselineOf(i) - u.top) * scale
                placed += QuranReflowPlaced(i, dx, dy, scale)
            }
            x -= widths[k] * scale + space
        }
        rows += QuranReflowRow(baseline, baseline - ascent, baseline + descent, placed)
        y = baseline + descent + lineGap / 2
    }

    var row = mutableListOf<List<Int>>()
    var used = 0f
    for (c in clusters) {
        if (isBlock(c)) {
            emit(row, justify = false)
            row = mutableListOf()
            used = 0f
            emit(listOf(c), justify = false)
            continue
        }
        val w = clusterWidth(c)
        if (row.isNotEmpty() && used + gap + w > width) {
            emit(row, justify = true)
            row = mutableListOf()
            used = 0f
        }
        used += (if (row.isEmpty()) 0f else gap) + w
        row.add(c)
    }
    emit(row, justify = false)
    return QuranReflow(rows, y)
}

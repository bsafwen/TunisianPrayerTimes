package com.tunisianprayertimes.quran

/**
 * A page's words rewrapped into narrower lines for large text: whole words in reading order, each row filled from the
 * right and justified by its spaces, every word drawn from its glyphs in the page's font ([QuranFontPage]). Nothing is
 * ever cut: a word is one glyph, and two words whose ink touches on the print stay on one row.
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
    page: QuranFontPage,
    width: Float,
    gap: Float = 18f,
    lineGap: Float = 26f,
    maxStretch: Float = 3f,
): QuranReflow {
    val units = page.units.withIndex().filter { (_, u) -> !u.draw.isEmpty }
    // Clusters: a unit with the units tied to it, which never part. A rosette, and the sajda sign after it, end their
    // verse: they never start a row, staying with what precedes them on their printed line.
    val clusters = mutableListOf<MutableList<Int>>()
    var open = false
    for ((i, u) in units) {
        val block = u.kind == QuranUnitKind.Heading || u.kind == QuranUnitKind.Basmala
        val ends = (u.kind == QuranUnitKind.Rosette || u.kind == QuranUnitKind.Sajda) && clusters.isNotEmpty() &&
            page.units[clusters.last().last()].let { it.line == u.line && it.kind != QuranUnitKind.Heading && it.kind != QuranUnitKind.Basmala }
        if ((open || ends) && !block) clusters.last().add(i) else clusters.add(mutableListOf(i))
        open = u.tied && !block
    }
    fun unitWidth(i: Int) = page.units[i].let { (it.right - it.left).toFloat() }
    fun clusterWidth(c: List<Int>): Float {
        val us = c.map { page.units[it] }
        return (us.maxOf { it.right } - us.minOf { it.left }).toFloat()
    }
    fun isBlock(c: List<Int>) = page.units[c.first()].kind.let { it == QuranUnitKind.Heading || it == QuranUnitKind.Basmala }
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

/** Where a placed unit's ink lies across its row: from its left edge to its right. */
internal fun QuranReflowPlaced.span(page: QuranFontPage): ClosedFloatingPointRange<Float> {
    val u = page.units[unit]
    val right = u.right + dx
    return right - (u.right - u.left) * scale..right
}

/** How far [x] across the row is from a placed unit's ink: 0 inside it. */
internal fun QuranReflowPlaced.distanceTo(page: QuranFontPage, x: Float): Float =
    span(page).let { maxOf(it.start - x, 0f, x - it.endInclusive) }

private fun QuranFontUnit.verse() = QuranVerseReference(surah, ayah)

/** A verse's end, which «مراجعة» leaves in view: its number, a rosette, and the sajda sign after it. */
private val QuranFontUnit.isNumber get() = kind == QuranUnitKind.Rosette || kind == QuranUnitKind.Sajda

/**
 * «مراجعة»: a cover on a row, from [left] to [right] across it, over side-by-side words of covered verses; [asked]: of
 * the verse the review asks, in the points' colour.
 */
internal class QuranRowCover(val left: Float, val right: Float, val asked: Boolean)

/**
 * The row's covers: one over each run of its words of [hidden] verses, a little past their ink ([pad]) but never more
 * than halfway to the next unit, which stays in view; [asked] verses' words make runs of their own. The numbers that
 * end the verses are never covered.
 */
internal fun QuranReflowRow.covers(
    page: QuranFontPage,
    hidden: Set<QuranVerseReference>,
    asked: Set<QuranVerseReference>,
    pad: Float = 8f,
): List<QuranRowCover> {
    if (hidden.isEmpty()) return emptyList()
    fun covered(k: Int) = page.units[placed[k].unit].let { it.kind == QuranUnitKind.Word && it.verse() in hidden }
    fun askedAt(k: Int) = page.units[placed[k].unit].verse() in asked
    val covers = mutableListOf<QuranRowCover>()
    var k = 0
    while (k < placed.size) {
        if (!covered(k)) { k++; continue }
        // Units are placed from the right: a run goes leftwards from k to end.
        var end = k
        while (end + 1 < placed.size && covered(end + 1) && askedAt(end + 1) == askedAt(k)) end++
        val spans = (k..end).map { placed[it].span(page) }
        val right = spans.maxOf { it.endInclusive }
        val left = spans.minOf { it.start }
        val toRight = placed.getOrNull(k - 1)?.span(page)?.start?.let { (it + right) / 2f } ?: Float.MAX_VALUE
        val toLeft = placed.getOrNull(end + 1)?.span(page)?.endInclusive?.let { (it + left) / 2f } ?: -Float.MAX_VALUE
        covers += QuranRowCover(maxOf(left - pad, minOf(left, toLeft)), minOf(right + pad, maxOf(right, toRight)), askedAt(k))
        k = end + 1
    }
    return covers
}

/** The verses whose words a piece of the row from [left] to [right] goes over. */
internal fun QuranReflowRow.versesUnder(page: QuranFontPage, left: Float, right: Float): Set<QuranVerseReference> =
    placed.filter { p -> page.units[p.unit].kind == QuranUnitKind.Word && p.span(page).let { it.start < right && it.endInclusive > left } }
        .mapTo(HashSet()) { page.units[it.unit].verse() }

/** The verse whose number is at [x] across the row, within [slop]: its rosette, or the sajda sign after it. */
internal fun QuranReflowRow.numberAt(page: QuranFontPage, x: Float, slop: Float): QuranVerseReference? =
    placed.filter { page.units[it.unit].isNumber }.minByOrNull { it.distanceTo(page, x) }
        ?.takeIf { it.distanceTo(page, x) <= slop }?.let { page.units[it.unit].verse() }

/** A verse's text within [reach] of [x] across the row: a word or a verse's number, where the eraser erases. */
internal fun QuranReflowRow.hasTextNear(page: QuranFontPage, x: Float, reach: Float): Boolean =
    placed.any { p -> page.units[p.unit].let { it.kind == QuranUnitKind.Word || it.isNumber } && p.distanceTo(page, x) <= reach }

/** The rows holding a unit whose middle lies in one of [rects], each coordinate of the page from 0 to 1, in order. */
internal fun QuranReflow.rowsIn(page: QuranFontPage, rects: List<QuranHighlightRect>): List<Int> =
    if (rects.isEmpty()) emptyList()
    else rows.indices.filter { r ->
        rows[r].placed.any { p ->
            val u = page.units[p.unit]
            val x = (u.left + u.right) / 2f / page.width
            val y = (u.top + u.bottom) / 2f / page.height
            rects.any { x in it.left..it.right && y in it.top..it.bottom }
        }
    }

/** The verses whose words each row holds, words of [hidden] verses left out: what a reader reads on it. */
internal fun QuranReflow.rowVerses(page: QuranFontPage, hidden: Set<QuranVerseReference> = emptySet()): List<Set<QuranVerseReference>> =
    rows.map { row ->
        row.placed.map { page.units[it.unit] }.filter { it.kind == QuranUnitKind.Word }.mapTo(HashSet()) { it.verse() } - hidden
    }

/**
 * Large text: the verses a page has kept in view long enough to count as read. Each row adds up its time in view
 * ([shown]); a verse is read once every row holding its words has been in view [seenMs] in all, and is given once
 * ([read]). A row in view a moment, the page scrolled past, counts for nothing.
 */
internal class QuranRowsSeen(rows: Int, private val seenMs: Long) {
    private val shownMs = LongArray(rows)
    private val given = HashSet<QuranVerseReference>()

    /** [rows] were in view [ms] more. */
    fun shown(rows: Iterable<Int>, ms: Long) {
        for (r in rows) if (r in shownMs.indices) shownMs[r] += ms
    }

    /** The verses read and not given before, by the verses on each row ([rowVerses]). */
    fun read(rowVerses: List<Set<QuranVerseReference>>): Set<QuranVerseReference> {
        val rowsOf = HashMap<QuranVerseReference, MutableList<Int>>()
        rowVerses.forEachIndexed { r, verses -> verses.forEach { rowsOf.getOrPut(it) { mutableListOf() } += r } }
        val read = rowsOf.filter { (verse, rows) -> verse !in given && rows.all { shownMs.getOrElse(it) { 0L } >= seenMs } }.keys
        given += read
        return read
    }
}

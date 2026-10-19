package com.tunisianprayertimes.quran

import android.content.Context
import org.json.JSONObject

/** Coordinates are relative to the original supplied scan, before fit/zoom/pan. */
data class QuranHighlightRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

class QuranHighlights internal constructor(
    private val pages: Map<Int, Map<QuranVerseReference, List<QuranHighlightRect>>>,
) {
    fun rectangles(page: Int, surah: Int?, ayah: Int?): List<QuranHighlightRect> =
        if (surah == null || ayah == null) emptyList()
        else pages[page]?.get(QuranVerseReference(surah, ayah)).orEmpty()

    /**
     * The verse touched at a point of the original scan. A touch in the thin space between two
     * lines or two verses selects the nearest one; page margins and chapter headings select nothing.
     */
    fun verseAt(page: Int, x: Float, y: Float): QuranVerseReference? {
        var nearest: QuranVerseReference? = null
        var nearestDistance = TOUCH_SLOP
        pages[page]?.forEach { (reference, rects) ->
            rects.forEach { rect ->
                val dx = maxOf(rect.left - x, 0f, x - rect.right)
                val dy = maxOf(rect.top - y, 0f, y - rect.bottom)
                if (dx == 0f && dy == 0f) return reference
                val distance = maxOf(dx, dy)
                if (distance < nearestDistance) {
                    nearestDistance = distance
                    nearest = reference
                }
            }
        }
        return nearest
    }

    /**
     * The text line at a point of the original scan, as the verse rectangle on it nearest the point: its top
     * and bottom bound that line. The line holding the point comes first, then the nearest one, as near as
     * [verseAt] allows. Null in margins away from the text and in chapter headings.
     */
    fun lineAt(page: Int, x: Float, y: Float): QuranHighlightRect? {
        var nearest: QuranHighlightRect? = null
        var nearestDy = TOUCH_SLOP
        var nearestDx = TOUCH_SLOP
        pages[page]?.values?.forEach { rects ->
            rects.forEach { rect ->
                val dx = maxOf(rect.left - x, 0f, x - rect.right)
                val dy = maxOf(rect.top - y, 0f, y - rect.bottom)
                if (dx < TOUCH_SLOP && dy < TOUCH_SLOP && (dy < nearestDy || (dy == nearestDy && dx <= nearestDx))) {
                    nearestDy = dy
                    nearestDx = dx
                    nearest = rect
                }
            }
        }
        return nearest
    }

    /**
     * [rects], covers on [page], each grown to halfway to the next line box above and below it on the same column: the
     * marks over and under a covered line, which sit between the boxes, are covered with it, and covered lines meet with
     * no seam. A side with no line within a line's height (a margin, a chapter heading) is left as it is.
     */
    fun covers(page: Int, rects: List<QuranHighlightRect>): List<QuranHighlightRect> {
        if (rects.isEmpty()) return rects
        val lines = pages[page]?.values?.flatten().orEmpty()
        return rects.map { r ->
            val height = r.bottom - r.top
            val column = lines.filter { it.left < r.right && it.right > r.left }
            val above = column.filter { it.bottom <= r.top + EDGE }.maxOfOrNull { it.bottom }?.takeIf { r.top - it < height }
            val below = column.filter { it.top >= r.bottom - EDGE }.minOfOrNull { it.top }?.takeIf { it - r.bottom < height }
            QuranHighlightRect(
                r.left, above?.let { (it + r.top) / 2f } ?: r.top,
                r.right, below?.let { (it + r.bottom) / 2f } ?: r.bottom,
            )
        }
    }

    private companion object {
        /** Boxes of one line may differ by this much at their edges. */
        const val EDGE = 0.002f
        /** About a fifth of a text line, in the normalized scan coordinates. */
        const val TOUCH_SLOP = 0.012f
    }
}

object QuranHighlightRepository {
    @Volatile private var cached: QuranHighlights? = null

    /** What this process has already read, without waiting for anything. */
    fun loaded(): QuranHighlights? = cached

    /** Call on IO, alongside loading the Quran text. */
    fun load(context: Context): QuranHighlights = cached ?: synchronized(this) {
        cached ?: read(context).also { cached = it }
    }

    private fun read(context: Context): QuranHighlights {
        val source = JSONObject(context.assets.open("quran/highlights.json").bufferedReader().use { it.readText() })
        val pages = source.getJSONArray("pages")
        val result = buildMap {
            for (i in 0 until pages.length()) {
                val page = pages.getJSONObject(i)
                val verses = page.getJSONArray("verses")
                put(page.getInt("page"), buildMap {
                    for (j in 0 until verses.length()) {
                        val verse = verses.getJSONObject(j)
                        val boxes = verse.getJSONArray("rects")
                        val rects = List(boxes.length()) { k ->
                            val rect = boxes.getJSONArray(k)
                            QuranHighlightRect(rect.getDouble(0).toFloat(), rect.getDouble(1).toFloat(), rect.getDouble(2).toFloat(), rect.getDouble(3).toFloat()).also {
                                require(it.left >= 0 && it.top >= 0 && it.right <= 1 && it.bottom <= 1 && it.left < it.right && it.top < it.bottom)
                            }
                        }
                        put(QuranVerseReference(verse.getInt("surah"), verse.getInt("ayah")), rects)
                    }
                })
            }
        }
        require(result.keys == (1..603).toSet()) { "Incomplete Quran highlight pages" }
        return QuranHighlights(result)
    }
}

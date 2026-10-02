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

    private companion object {
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

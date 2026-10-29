package com.tunisianprayertimes.quran

import org.json.JSONArray
import org.json.JSONObject

/**
 * A mushaf page set in the print's own type, as the King Fahd Complex's page fonts are made, but for the whole book at
 * once (scripts/mushaf_typeset, the book build): every ink piece of the page is a glyph of the book's fonts drawn
 * where the print has it, each a drawing the print set many times (its outlines the print's own ink), in the piece's
 * own colour. The ornaments the print set from one plate (the verse rosette, the sajda sign, the surah heading's band,
 * the frame of each side of the book) are drawn from that plate ([QuranFontBook.plates]) through their own placement;
 * what a plate leaves out (a surah's name, a page number) is the page's own glyphs.
 *
 * Coordinates are the scan's pixels, those of [QuranHighlightRect] once divided by [width] and [height], so everything
 * drawn over the scan lands on the font's page unchanged. A glyph drawn at a text size of [upm] / [unitsPerPixel] scan
 * pixels is the print's own size.
 */
class QuranFontPage internal constructor(
    val page: Int,
    val width: Int,
    val height: Int,
    val upm: Int,
    val unitsPerPixel: Int,
    /** Each printed line's baseline, in scan pixels: the words of [QuranFontUnit.line] sit on it. */
    val baselines: IntArray,
    /** In reading order: headings and basmalas before the lines under them. */
    val units: List<QuranFontUnit>,
    /** The frame and the margins. */
    val furniture: QuranFontDraw,
) {
    /** The text size, in scan pixels, at which a glyph is the print's own size. */
    val textSize: Float get() = upm.toFloat() / unitsPerPixel

    companion object {
        /** A page's layout file (the book build's book_pack.py, 'QBP1'), as [QuranPageReader] reads it. */
        fun parse(bytes: ByteArray): QuranFontPage {
            val r = QuranPageReader(bytes)
            val page = r.varint()
            val width = r.varint()
            val height = r.varint()
            val upm = r.varint()
            val unitsPerPixel = r.varint()
            val baselines = IntArray(r.varint()) { r.varint() }
            val colours = IntArray(r.varint()) { (0xFF shl 24) or (r.byte() shl 16) or (r.byte() shl 8) or r.byte() }
            val furniture = r.draw(0, 0, colours)
            val units = List(r.varint()) { r.unit(colours) }
            check(r.atEnd) { "page $page: bytes left after its units" }
            return QuranFontPage(page, width, height, upm, unitsPerPixel, baselines, units, furniture)
        }
    }
}

/**
 * The book's page layout file: 'QBP1', then unsigned LEB128 numbers ("zigzag" for signed ones). The header (page, width,
 * height, upm, units per pixel), the baselines, the colours (R G B bytes), the furniture's draw from the page's corner,
 * then the units: kind byte, surah, ayah, token, line + 1, tied byte, the ink box grown to whole pixels (left, top,
 * width, height), the text in UTF-8 (byte count first) and the unit's draw from its box's corner. A draw is its plates
 * (number, then a b c d e f as float32 little-endian) and its glyphs (font × 65536 + code point − U+F0000, colour
 * number, then the origin's x and y in 1/32 px, zigzag).
 */
private class QuranPageReader(private val bytes: ByteArray) {
    private var at = 4

    init {
        check(bytes.size >= 4 && bytes[0] == 'Q'.code.toByte() && bytes[1] == 'B'.code.toByte() &&
            bytes[2] == 'P'.code.toByte() && bytes[3] == '1'.code.toByte()) { "not a book page layout" }
    }

    val atEnd: Boolean get() = at == bytes.size

    fun byte(): Int = bytes[at++].toInt() and 0xFF

    fun varint(): Int {
        var value = 0
        var shift = 0
        while (true) {
            val b = byte()
            value = value or ((b and 0x7F) shl shift)
            if (b < 0x80) return value
            shift += 7
        }
    }

    private fun zigzag(): Int = varint().let { (it ushr 1) xor -(it and 1) }

    private fun float(): Float = java.lang.Float.intBitsToFloat(byte() or (byte() shl 8) or (byte() shl 16) or (byte() shl 24))

    fun unit(colours: IntArray): QuranFontUnit {
        val kind = when (byte()) {
            0 -> QuranUnitKind.Word
            1 -> QuranUnitKind.Rosette
            2 -> QuranUnitKind.Sajda
            3 -> QuranUnitKind.HizbRing
            4 -> QuranUnitKind.Heading
            else -> QuranUnitKind.Basmala
        }
        val surah = varint()
        val ayah = varint()
        val token = varint()
        val line = varint() - 1
        val tied = byte() != 0
        val left = varint()
        val top = varint()
        val right = left + varint()
        val bottom = top + varint()
        val length = varint()
        val text = String(bytes, at, length, Charsets.UTF_8).also { at += length }
        return QuranFontUnit(kind, surah, ayah, token, line, tied, left, top, right, bottom, draw(left, top, colours), text)
    }

    fun draw(originX: Int, originY: Int, colours: IntArray): QuranFontDraw {
        val plates = List(varint()) { QuranPlateDraw(varint(), FloatArray(6) { float() }) }
        val n = varint()
        val fonts = IntArray(n)
        val codePoints = IntArray(n)
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        val glyphColours = IntArray(n)
        for (k in 0 until n) {
            val glyph = varint()
            fonts[k] = glyph ushr 16
            codePoints[k] = 0xF0000 + (glyph and 0xFFFF)
            glyphColours[k] = colours[varint()]
            xs[k] = originX + zigzag() / 32f
            ys[k] = originY + zigzag() / 32f
        }
        return QuranFontDraw(fonts, codePoints, xs, ys, glyphColours, plates)
    }
}

/** Sajda: the sign the print sets after a sajda verse's rosette. */
enum class QuranUnitKind { Word, Rosette, Sajda, HizbRing, Heading, Basmala }

/** One printed unit of a page and the glyphs that draw it. */
class QuranFontUnit internal constructor(
    val kind: QuranUnitKind,
    val surah: Int,
    /** The verse; 0 for a basmala and a heading. */
    val ayah: Int,
    /** The word's place among its verse's words, as index.json numbers them. */
    val token: Int,
    /** The printed line, from 0; -1 for a heading and its basmala. */
    val line: Int,
    /** Its last letters touch the next word's on the print: the two stay on one line. */
    val tied: Boolean,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val draw: QuranFontDraw,
    /** The word as the app's mushaf text writes it; empty for a ring or a heading. */
    val text: String,
)

/**
 * Glyphs, each in one of the book's fonts ([fonts], an index into [QuranFontBook.fonts]) on its own origin (scan
 * pixels) in its colour (opaque ARGB), lightest first; [plates] are drawn before them.
 */
class QuranFontDraw internal constructor(
    val fonts: IntArray,
    val codePoints: IntArray,
    val xs: FloatArray,
    val ys: FloatArray,
    val colours: IntArray,
    val plates: List<QuranPlateDraw>,
) {
    /** Each glyph's text: one character of the supplementary private use area, as a surrogate pair. */
    val chars: List<CharArray> = codePoints.map { Character.toChars(it) }

    val isEmpty: Boolean get() = codePoints.isEmpty() && plates.isEmpty()
}

/**
 * A plate of the book ([QuranFontBook.plates]) drawn through its placement here, [matrix] = (a, b, c, d, e, f): a point
 * (x, y) of the plate lands at (a x + b y + c, d x + e y + f) of the page.
 */
class QuranPlateDraw internal constructor(val plate: Int, val matrix: FloatArray)

/** The book's fonts (file names beside its layout) and its plates, each drawn in its own coordinates. */
class QuranFontBook internal constructor(
    val fonts: List<String>,
    val upm: Int,
    val unitsPerPixel: Int,
    val plates: List<QuranFontDraw>,
) {
    companion object {
        fun parse(json: String): QuranFontBook {
            val o = JSONObject(json)
            val fonts = o.getJSONArray("fonts")
            val plates = o.getJSONArray("plates")
            val byId = (0 until plates.length()).map { plates.getJSONObject(it) }.sortedBy { it.getInt("id") }
            return QuranFontBook(
                fonts = List(fonts.length()) { fonts.getString(it) },
                upm = o.getInt("upm"),
                unitsPerPixel = o.getInt("unitsPerPixel"),
                plates = byId.map { draw(it) },
            )
        }
    }
}

/** A plate's draw from its "glyphs" ([font, code point, x, y, "RRGGBB"]) and "plates" ([plate, a, b, c, d, e, f]). */
private fun draw(o: JSONObject): QuranFontDraw {
    val glyphs = o.optJSONArray("glyphs") ?: JSONArray()
    val plates = o.optJSONArray("plates") ?: JSONArray()
    val n = glyphs.length()
    val fonts = IntArray(n)
    val codePoints = IntArray(n)
    val xs = FloatArray(n)
    val ys = FloatArray(n)
    val colours = IntArray(n)
    for (k in 0 until n) {
        val g = glyphs.getJSONArray(k)
        fonts[k] = g.getInt(0)
        codePoints[k] = g.getInt(1)
        xs[k] = g.getDouble(2).toFloat()
        ys[k] = g.getDouble(3).toFloat()
        colours[k] = (0xFF shl 24) or g.getString(4).toInt(16)
    }
    return QuranFontDraw(
        fonts, codePoints, xs, ys, colours,
        List(plates.length()) { i ->
            val p = plates.getJSONArray(i)
            QuranPlateDraw(p.getInt(0), FloatArray(6) { p.getDouble(it + 1).toFloat() })
        },
    )
}

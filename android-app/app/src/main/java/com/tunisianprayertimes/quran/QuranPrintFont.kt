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
        /**
         * A page's layout file (the book build's book_pack.py): 'QBP3', range-coded, read by [QuranLayoutDecoder] with
         * the [book]'s shared tables; or the earlier 'QBP1', read by [QuranPageReader].
         */
        fun parse(bytes: ByteArray, book: QuranFontBook? = null): QuranFontPage {
            if (bytes.size >= 4 && bytes[3] == '3'.code.toByte()) {
                val tables = checkNotNull(book?.layout) { "a QBP3 page needs its book's layout tables" }
                return QuranLayoutDecoder(bytes, tables).page(book.upm, book.unitsPerPixel)
            }
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

/**
 * The book's fonts (file names beside its layout) and its plates, each drawn in its own coordinates; the tables its
 * 'QBP3' pages are coded with, when they are.
 */
class QuranFontBook internal constructor(
    val fonts: List<String>,
    val upm: Int,
    val unitsPerPixel: Int,
    val plates: List<QuranFontDraw>,
    internal val layout: QuranLayoutTables? = null,
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
                layout = o.optJSONObject("layout")?.let { QuranLayoutTables.parse(it) },
            )
        }
    }
}

/**
 * What every 'QBP3' page of a book shares (plates.json "layout"): the colours other than black, the most drawn first
 * ([colours], opaque ARGB), and the medians a unit's box is coded against, per kind: its top above its line's baseline
 * ([up]) and its bottom below it ([down]); and per plate, the x and y of a plate only moved into place, in thousandths
 * of a pixel from its unit's corner ([plateX], [plateY]).
 */
internal class QuranLayoutTables(
    val colours: IntArray,
    val up: IntArray,
    val down: IntArray,
    val plateX: Map<Int, Int>,
    val plateY: Map<Int, Int>,
) {
    companion object {
        fun parse(o: JSONObject): QuranLayoutTables {
            val colours = o.getJSONArray("colours")
            val up = o.getJSONArray("up")
            val down = o.getJSONArray("down")
            fun map(m: JSONObject): Map<Int, Int> = m.keys().asSequence().associate { it.toInt() to m.getInt(it) }
            return QuranLayoutTables(
                colours = IntArray(colours.length()) { (0xFF shl 24) or colours.getString(it).toInt(16) },
                up = IntArray(up.length()) { up.getInt(it) },
                down = IntArray(down.length()) { down.getInt(it) },
                plateX = map(o.getJSONObject("plateX")),
                plateY = map(o.getJSONObject("plateY")),
            )
        }
    }
}

/**
 * A 'QBP3' page layout: the data of a 'QBP1' one, every field range-coded (LZMA's binary coder, 11-bit probabilities)
 * from what the decoder already knows: each model starts at even odds on every page and learns as the page is read.
 * The book build's exp/qbp3_book.py writes it and its decode() is this decoder, step for step, with the same model
 * names; the layout's bytes follow the 4-byte magic. Values decode exactly as 'QBP1' stores them: origins in 1/32 px
 * from their draw's corner, plate matrices as float32, colours from the book's [QuranLayoutTables].
 */
internal class QuranLayoutDecoder(private val d: ByteArray, private val tables: QuranLayoutTables) {
    private var at = 4 + 5
    private var range = 0xFFFFFFFFL
    private var code = ((d[5].toLong() and 0xFF) shl 24) or ((d[6].toLong() and 0xFF) shl 16) or
        ((d[7].toLong() and 0xFF) shl 8) or (d[8].toLong() and 0xFF)
    private val models = HashMap<String, IntArray>()

    private fun probabilities(key: String, size: Int) = models.getOrPut(key) { IntArray(size) { ONE shr 1 } }

    private fun next(): Long = (if (at < d.size) d[at].toLong() and 0xFF else 0L).also { at++ }

    private fun normalize() {
        while (range < TOP) {
            range = (range shl 8) and M32
            code = ((code shl 8) or next()) and M32
        }
    }

    private fun bit(probs: IntArray, i: Int): Int {
        val p = probs[i]
        val bound = (range ushr BITS) * p
        val b: Int
        if (code < bound) {
            range = bound
            probs[i] = p + ((ONE - p) shr MOVE)
            b = 0
        } else {
            code -= bound
            range -= bound
            probs[i] = p - (p shr MOVE)
            b = 1
        }
        normalize()
        return b
    }

    private fun direct(bits: Int): Long {
        var v = 0L
        repeat(bits) {
            range = range ushr 1
            if (code >= range) {
                code -= range
                v = (v shl 1) or 1
            } else {
                v = v shl 1
            }
            normalize()
        }
        return v
    }

    private fun tree(key: String, bits: Int): Int {
        val probs = probabilities(key, 1 shl bits)
        var m = 1
        repeat(bits) { m = (m shl 1) or bit(probs, m) }
        return m - (1 shl bits)
    }

    /** Its bit length (a tree of its own), the two bits under the top one modelled per length, the rest direct. */
    private fun uint(key: String): Long {
        val n = tree("$key:n", 6)
        if (n < 2) return n.toLong()
        val modelled = minOf(2, n - 1)
        val probs = probabilities("$key:h", 64 shl 2)
        var m = 1
        repeat(modelled) { m = (m shl 1) or bit(probs, (n shl 2) + m) }
        val rest = n - 1 - modelled
        return if (rest > 0) (m.toLong() shl rest) or direct(rest) else m.toLong()
    }

    private fun int(key: String): Int = uint(key).toInt()

    private fun sint(key: String): Int = uint(key).let { ((it ushr 1) xor -(it and 1)).toInt() }

    private fun flag(key: String): Boolean = bit(probabilities(key, 1), 0) == 1

    /** A value in 0..[span]: its top bits (6 at most) a tree per bit length of [span], the rest direct. */
    private fun within(key: String, span: Int): Int {
        val bits = 32 - Integer.numberOfLeadingZeros(span)
        val top = minOf(bits, 6)
        val v = tree("$key:$bits", top)
        return if (bits > top) ((v.toLong() shl (bits - top)) or direct(bits - top)).toInt() else v
    }

    private fun plate(left: Int, top: Int): QuranPlateDraw {
        val number = int("pnum")
        val matrix = when (tree("pmode", 2)) {
            0 -> {
                val kx = sint("ptx|$number") + 1000 * left + tables.plateX.getValue(number)
                val ky = sint("pty|$number") + 1000 * top + tables.plateY.getValue(number)
                floatArrayOf(1f, 0f, (kx / 1000.0).toFloat(), 0f, 1f, (ky / 1000.0).toFloat())
            }
            2 -> FloatArray(6) { (sint("pm|$it") / 1_000_000.0).toFloat() }
            else -> FloatArray(6) { java.lang.Float.intBitsToFloat(direct(32).toInt()) }
        }
        return QuranPlateDraw(number, matrix)
    }

    private fun lightness(argb: Int): Int = ((argb shr 16) and 0xFF) + ((argb shr 8) and 0xFF) + (argb and 0xFF)

    private fun glyphs(unit: Boolean, kind: String, originX: Int, originY: Int, w: Int, h: Int, plates: List<QuranPlateDraw>): QuranFontDraw {
        val n = int("n|$kind")
        val colours = IntArray(n) { BLACK }
        if (n > 0) {
            val k = int("k")
            for (i in 0 until k) colours[i] = tables.colours[int("col")]
        }
        val fonts = IntArray(n)
        val codePoints = IntArray(n)
        var previous = 0
        for (i in 0 until n) {
            val g = if (i > 0 && lightness(colours[i]) == lightness(colours[i - 1])) previous + int("gd") else int("g0")
            fonts[i] = g ushr 16
            codePoints[i] = 0xF0000 + (g and 0xFFFF)
            previous = g
        }
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        val seenX = IntArray(n)
        val seenY = IntArray(n)
        var seen = 0
        for (i in 0 until n) {
            val x: Int
            val y: Int
            if (seen > 0 && flag("same|${minOf(seen, 3)}")) {
                val back = seen - 1 - int("bref")
                x = seenX[back]
                y = seenY[back]
            } else {
                if (unit) {
                    val grid = flag("grid")
                    val step = if (grid) 8 else 1
                    val g = if (grid) 1 else 0
                    x = within("x|$g", w * 32 / step) * step
                    y = within("y|$g", h * 32 / step) * step
                } else {
                    x = sint("fx")
                    y = sint("fy")
                }
                seenX[seen] = x
                seenY[seen] = y
                seen++
            }
            xs[i] = originX + x / 32f
            ys[i] = originY + y / 32f
        }
        return QuranFontDraw(fonts, codePoints, xs, ys, colours, plates)
    }

    fun page(upm: Int, unitsPerPixel: Int): QuranFontPage {
        val page = int("page")
        val width = int("w")
        val height = int("h")
        val baselines = IntArray(int("nb"))
        for (i in baselines.indices) baselines[i] = int("b") + (if (i > 0) baselines[i - 1] else 0)
        val framePlates = List(int("fpc")) { plate(0, 0) }
        val furniture = glyphs(false, "f", 0, 0, 0, 0, framePlates)
        val units = ArrayList<QuranFontUnit>()
        var surah = 0
        var ayah = 0
        var token = -1
        var line = 0
        var left = 0
        var kindCode = 6
        repeat(int("nu")) { index ->
            val kind = tree("kind|$kindCode", 3)
            val lc = tree("lc|$kind", 2)
            val vc = tree("vc|$kind", 2)
            var hasPlate = kind in 1..4
            var tied = false
            if (flag("ext|$kind")) {
                hasPlate = flag("hasplate")
                tied = flag("tied")
            }
            line = when (lc) {
                0 -> line
                1 -> line + 1
                2 -> -1
                else -> int("line") - 1
            }
            when (vc) {
                0 -> token += 1
                1 -> { ayah += 1; token = 0 }
                2 -> token = 0
                else -> { surah = int("surah"); ayah = int("ayah"); token = int("token") }
            }
            val top: Int
            val bottom: Int
            if (line in baselines.indices) {
                top = baselines[line] - tables.up[kind] - sint("up|$kind")
                bottom = baselines[line] + tables.down[kind] + sint("down|$kind")
            } else {
                top = int("top")
                bottom = top + int("height")
            }
            val right = if (lc == 0 && index > 0) left - sint("gap") else int("right")
            val width = int("width|$kind")
            left = right - width
            val text = ByteArray(int("tlen|$kind"))
            var previousByte = 0
            for (i in text.indices) {
                previousByte = tree("tb|${if (previousByte >= 0xD8) previousByte else 0}", 8)
                text[i] = previousByte.toByte()
            }
            val plates = if (hasPlate) listOf(plate(left, top)) else emptyList()
            val draw = glyphs(true, kind.toString(), left, top, width, bottom - top, plates)
            units += QuranFontUnit(
                kind = when (kind) {
                    0 -> QuranUnitKind.Word
                    1 -> QuranUnitKind.Rosette
                    2 -> QuranUnitKind.Sajda
                    3 -> QuranUnitKind.HizbRing
                    4 -> QuranUnitKind.Heading
                    else -> QuranUnitKind.Basmala
                },
                surah = surah, ayah = ayah, token = token, line = line, tied = tied,
                left = left, top = top, right = right, bottom = bottom, draw = draw,
                text = String(text, Charsets.UTF_8),
            )
            kindCode = kind
        }
        return QuranFontPage(page, width, height, upm, unitsPerPixel, baselines, units, furniture)
    }

    private companion object {
        const val TOP = 1L shl 24
        const val BITS = 11
        const val ONE = 1 shl BITS
        const val MOVE = 5
        const val M32 = 0xFFFFFFFFL
        const val BLACK = 0xFF shl 24
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

package com.tunisianprayertimes.quran

/**
 * The book's earlier page layout, 'QBP1', kept for the tests alone: the app reads only 'QBP3' ([QuranFontPage.parse]),
 * and [QuranLayoutDecoderTest] checks it against the same pages in this plain form (the book build's book_pack.py
 * still writes it without BOOK_LAYOUT=qbp3).
 *
 * 'QBP1', then unsigned LEB128 numbers ("zigzag" for signed ones). The header (page, width, height, upm, units per
 * pixel), the baselines, the colours (R G B bytes), the furniture's draw from the page's corner, then the units: kind
 * byte, surah, ayah, token, line + 1, tied byte, the ink box grown to whole pixels (left, top, width, height), the text
 * in UTF-8 (byte count first) and the unit's draw from its box's corner. A draw is its plates (number, then a b c d e f
 * as float32 little-endian) and its glyphs (font × 65536 + code point − U+F0000, colour number, then the origin's x and
 * y in 1/32 px, zigzag).
 */
internal fun quranQbp1Page(bytes: ByteArray): QuranFontPage {
    val r = Qbp1Reader(bytes)
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

private class Qbp1Reader(private val bytes: ByteArray) {
    private var at = 4

    init {
        check(bytes.size >= 4 && bytes[0] == 'Q'.code.toByte() && bytes[1] == 'B'.code.toByte() &&
            bytes[2] == 'P'.code.toByte() && bytes[3] == '1'.code.toByte()) { "not a QBP1 page layout" }
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

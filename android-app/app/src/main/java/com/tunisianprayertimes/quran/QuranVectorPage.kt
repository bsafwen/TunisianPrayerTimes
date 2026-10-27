package com.tunisianprayertimes.quran

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A mushaf page redrawn from the print: the scan's own ink traced into outlines, one layer per printed colour, never a
 * stroke or a mark cut (scripts/mushaf_typeset). Coordinates are the scan's pixels, those of [QuranHighlightRect] once
 * divided by [width] and [height], so every overlay drawn over the scan lands on it unchanged.
 *
 * The pieces are grouped into the print's words, verse rosettes, hizb rings, surah headings and basmalas ([units]),
 * each whole; the rest (frame, page header, margins) is [furniture]. The file format is the one pack.py writes
 * ('QVP2'), read here without Android so it can be checked on the JVM.
 */
class QuranVectorPage internal constructor(
    val page: Int,
    val width: Int,
    val height: Int,
    val pieces: List<QuranVectorPiece>,
    val units: List<QuranVectorUnit>,
    val furniture: IntArray,
    /** Each printed line's baseline, in scan pixels: the words of [QuranVectorUnit.line] sit on it. */
    val baselines: IntArray,
) {
    companion object {
        /** Coordinates in the file are in steps of 1/[STEPS] scan pixel. */
        const val STEPS = 4f

        fun read(bytes: ByteArray): QuranVectorPage = Reader(bytes).page()
    }
}

/** One connected piece of ink: a letter group, a mark, a dot, a ring; or a tile of the frame drawn clipped. */
class QuranVectorPiece internal constructor(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val layers: List<QuranVectorLayer>,
)

/**
 * One printed colour of a piece: closed contours filled with the even-odd rule. [kinds] says per segment whether it
 * is a cubic (three points) or a corner (two points, two straight lines); [points] holds every point after each
 * contour's start, x then y, in 1/[QuranVectorPage.STEPS] pixels.
 */
class QuranVectorLayer internal constructor(
    /** The print's colour for this layer, opaque ARGB. */
    val colour: Int,
    /** An ornament tile is drawn clipped to this box (left, top, right, bottom); null for the text. */
    val clip: IntArray?,
    val segments: IntArray,
    val starts: IntArray,
    val kinds: BooleanArray,
    val points: IntArray,
)

enum class QuranVectorUnitKind { Word, Rosette, HizbRing, Heading, Basmala }

/** A word of the print, a ring or a heading, and the pieces that make it. */
class QuranVectorUnit internal constructor(
    val kind: QuranVectorUnitKind,
    val surah: Int,
    /** The verse; 0 for a basmala and a heading. */
    val ayah: Int,
    /** The word's place among its verse's words, as index.json numbers them. */
    val token: Int,
    /** The printed line, from 0; -1 for a heading and its basmala. */
    val line: Int,
    val pieces: IntArray,
    /** Its last letters touch the next word's on the print: the two stay on one line. */
    val tied: Boolean,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

private class Reader(bytes: ByteArray) {
    private val b: ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    private fun u8() = b.get().toInt() and 0xFF
    private fun u16() = b.getShort().toInt() and 0xFFFF
    private fun i8() = b.get().toInt()

    private class Directory(val bounds: IntArray, val layers: List<Triple<Int, Int, IntArray>>)

    fun page(): QuranVectorPage {
        val magic = ByteArray(4).also { b.get(it) }
        require(String(magic, Charsets.US_ASCII) == "QVP2") { "Not a redrawn mushaf page" }
        val page = u16()
        val width = u16()
        val height = u16()
        val colours = IntArray(u16()) { (0xFF shl 24) or (u8() shl 16) or (u8() shl 8) or u8() }
        val clips = List(u16()) { IntArray(4) { u16() } }
        val directory = List(u16()) {
            val bounds = IntArray(4) { u16() }
            val layers = List(u8()) {
                val colour = u16()
                val clip = u16()
                Triple(colour, clip, IntArray(u16()) { u16() })
            }
            Directory(bounds, layers)
        }
        val starts = varints(stream())
        val kinds = stream()
        val dx = varints(stream())
        val dy = varints(stream())
        var si = 0
        var ki = 0
        var pi = 0
        var lastX = 0
        var lastY = 0
        val pieces = directory.map { d ->
            QuranVectorPiece(d.bounds[0], d.bounds[1], d.bounds[2], d.bounds[3], d.layers.map { (colour, clip, segments) ->
                val layerStarts = IntArray(segments.size * 2)
                val layerKinds = BooleanArray(segments.sum())
                var count = 0
                for (k in layerKinds.indices) {
                    val bit = ki + k
                    layerKinds[k] = (kinds[bit shr 3].toInt() shr (bit and 7)) and 1 == 1
                    count += if (layerKinds[k]) 3 else 2
                }
                val points = IntArray(count * 2)
                var p = 0
                var seg = 0
                for ((c, n) in segments.withIndex()) {
                    lastX += starts[si++]
                    lastY += starts[si++]
                    layerStarts[c * 2] = lastX
                    layerStarts[c * 2 + 1] = lastY
                    var x = lastX
                    var y = lastY
                    repeat(n) {
                        repeat(if (layerKinds[seg]) 3 else 2) {
                            x += dx[pi]
                            y += dy[pi]
                            pi++
                            points[p++] = x
                            points[p++] = y
                        }
                        seg++
                    }
                }
                ki += layerKinds.size
                QuranVectorLayer(colours[colour], clips.getOrNull(clip), segments, layerStarts, layerKinds, points)
            })
        }
        val units = List(u16()) {
            val kind = QuranVectorUnitKind.entries[u8()]
            val surah = u8()
            val ayah = u16()
            val token = u8()
            val line = i8()
            u8() // the item's place on its line; the order of the units already says it
            val tied = u8() == 1
            val own = IntArray(u16()) { u16() }
            var l = Int.MAX_VALUE
            var t = Int.MAX_VALUE
            var r = Int.MIN_VALUE
            var bt = Int.MIN_VALUE
            for (i in own) {
                val piece = pieces[i]
                l = minOf(l, piece.left); t = minOf(t, piece.top); r = maxOf(r, piece.right); bt = maxOf(bt, piece.bottom)
            }
            if (own.isEmpty()) { l = 0; t = 0; r = 0; bt = 0 }
            QuranVectorUnit(kind, surah, ayah, token, line, own, tied, l, t, r, bt)
        }
        val furniture = IntArray(u16()) { u16() }
        val baselines = IntArray(u8()) { u16() }
        check(!b.hasRemaining()) { "Trailing bytes in page $page" }
        return QuranVectorPage(page, width, height, pieces, units, furniture, baselines)
    }

    private fun stream(): ByteArray = ByteArray(b.getInt()).also { b.get(it) }

    private fun varints(bytes: ByteArray): IntArray {
        val out = IntArray(bytes.size)
        var n = 0
        var z = 0
        var shift = 0
        for (byte in bytes) {
            val v = byte.toInt() and 0xFF
            z = z or ((v and 0x7F) shl shift)
            if (v and 0x80 != 0) { shift += 7; continue }
            out[n++] = (z ushr 1) xor -(z and 1)
            z = 0
            shift = 0
        }
        return out.copyOf(n)
    }
}

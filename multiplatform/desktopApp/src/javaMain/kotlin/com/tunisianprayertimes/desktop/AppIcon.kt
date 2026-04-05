package com.tunisianprayertimes.desktop

import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.GeneralPath
import java.awt.geom.Path2D
import java.awt.image.BufferedImage

/**
 * Generates the app icon programmatically — matches the Android ic_launcher mosque design.
 * Teal background, white mosque (dome + minarets), gold crescent + star.
 */
object AppIcon {

    private val TEAL = Color(27, 94, 32)          // matches HeaderStart
    private val TEAL_DARK = Color(0, 77, 64)      // gradient end
    private val WHITE = Color(255, 255, 255)
    private val WHITE_DIM = Color(255, 255, 255, 216) // 0.85 alpha
    private val GOLD = Color(212, 175, 55)

    /**
     * Create the app icon at the given size (square).
     */
    fun create(size: Int): BufferedImage {
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)

        val s = size.toFloat() // scale factor relative to 108 (Android viewport)
        fun x(v: Float) = (v / 108f * s)
        fun y(v: Float) = (v / 108f * s)

        // Background — rounded rect with slight gradient
        g.color = TEAL
        g.fillRoundRect(0, 0, size, size, (size * 0.18f).toInt(), (size * 0.18f).toInt())

        // Dome (white)
        g.color = WHITE
        val dome = GeneralPath()
        dome.moveTo(x(40f).toDouble(), y(72f).toDouble())
        dome.quadTo(x(40f).toDouble(), y(38f).toDouble(), x(54f).toDouble(), y(30f).toDouble())
        dome.quadTo(x(68f).toDouble(), y(38f).toDouble(), x(68f).toDouble(), y(72f).toDouble())
        dome.closePath()
        g.fill(dome)

        // Left minaret
        g.color = WHITE_DIM
        val leftMin = GeneralPath()
        leftMin.moveTo(x(30f).toDouble(), y(72f).toDouble())
        leftMin.lineTo(x(30f).toDouble(), y(48f).toDouble())
        leftMin.quadTo(x(30f).toDouble(), y(42f).toDouble(), x(33f).toDouble(), y(42f).toDouble())
        leftMin.quadTo(x(36f).toDouble(), y(42f).toDouble(), x(36f).toDouble(), y(48f).toDouble())
        leftMin.lineTo(x(36f).toDouble(), y(72f).toDouble())
        leftMin.closePath()
        g.fill(leftMin)

        // Right minaret
        val rightMin = GeneralPath()
        rightMin.moveTo(x(72f).toDouble(), y(72f).toDouble())
        rightMin.lineTo(x(72f).toDouble(), y(48f).toDouble())
        rightMin.quadTo(x(72f).toDouble(), y(42f).toDouble(), x(75f).toDouble(), y(42f).toDouble())
        rightMin.quadTo(x(78f).toDouble(), y(42f).toDouble(), x(78f).toDouble(), y(48f).toDouble())
        rightMin.lineTo(x(78f).toDouble(), y(72f).toDouble())
        rightMin.closePath()
        g.fill(rightMin)

        // Crescent (gold) — outer circle minus inner circle
        g.color = GOLD
        val crescentOuter = Ellipse2D.Float(x(46f), y(12f), x(16f), y(16f))
        val crescentInner = Ellipse2D.Float(x(50f), y(10f), x(12f), y(12f))
        val crescentPath = java.awt.geom.Area(crescentOuter)
        crescentPath.subtract(java.awt.geom.Area(crescentInner))
        g.fill(crescentPath)

        // Star (gold) — 5-pointed star
        g.color = GOLD
        val starPath = createStar(
            cx = x(66f).toDouble(), cy = y(24f).toDouble(),
            outerR = x(5f).toDouble(), innerR = x(2f).toDouble(),
            points = 5
        )
        g.fill(starPath)

        // Base line
        g.color = WHITE_DIM
        g.stroke = BasicStroke(x(1.5f))
        g.drawLine(x(26f).toInt(), y(72f).toInt(), x(82f).toInt(), y(72f).toInt())

        g.dispose()
        return img
    }

    /**
     * Create a small tray icon (16x16).
     */
    fun createTrayIcon(): BufferedImage = create(16)

    private fun createStar(cx: Double, cy: Double, outerR: Double, innerR: Double, points: Int): Path2D {
        val path = GeneralPath()
        val angleStep = Math.PI / points
        for (i in 0 until points * 2) {
            val r = if (i % 2 == 0) outerR else innerR
            val angle = -Math.PI / 2 + i * angleStep
            val px = cx + r * Math.cos(angle)
            val py = cy + r * Math.sin(angle)
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.closePath()
        return path
    }
}

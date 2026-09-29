package com.tunisianprayertimes.tv.ui.display

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import com.tunisianprayertimes.tv.ui.theme.Midad

/** The weather as a line drawing, in the header beside the temperature. */
enum class WeatherIcon { SUN, MOON, PARTLY_CLOUDY, CLOUD, FOG, RAIN, SNOW, THUNDER }

/**
 * The drawing for a WMO weather code (Open-Meteo), grouped as OpenMeteo.symbol groups them; null
 * for a code it does not know, and the header then shows the temperature alone.
 */
fun weatherIcon(code: Int, isDay: Boolean): WeatherIcon? = when (code) {
    0, 1 -> if (isDay) WeatherIcon.SUN else WeatherIcon.MOON
    2 -> if (isDay) WeatherIcon.PARTLY_CLOUDY else WeatherIcon.CLOUD
    3 -> WeatherIcon.CLOUD
    45, 48 -> WeatherIcon.FOG
    in 51..67, in 80..82 -> WeatherIcon.RAIN
    in 71..77, 85, 86 -> WeatherIcon.SNOW
    in 95..99 -> WeatherIcon.THUNDER
    else -> null
}

/**
 * [icon] in a 24-unit square, stroked like the mockup's sun: one line weight, round ends, never a
 * colour emoji (they render differently on every box, and in colour). Silver, not gold: gold is the
 * next prayer's alone.
 */
@Composable
fun WeatherGlyph(icon: WeatherIcon, size: Dp, modifier: Modifier = Modifier, color: Color = Midad.Silver) {
    val path = GLYPHS.getValue(icon)
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 24f
        scale(s, s, pivot = Offset.Zero) {
            drawPath(path, color, style = Stroke(width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

private const val CLOUD = "M6.5 18H17.5A3.5 3.5 0 0 0 17.9 11A5.5 5.5 0 0 0 7.3 11.5A3.3 3.3 0 0 0 6.5 18Z"
/** The cloud raised, to leave room for what falls from it. */
private const val HIGH_CLOUD = "M6.5 15H17.5A3.5 3.5 0 0 0 17.9 8A5.5 5.5 0 0 0 7.3 8.5A3.3 3.3 0 0 0 6.5 15Z"

/** Parsed on first draw, so the icon mapping above stays usable where Android's Path is not (unit tests). */
private val GLYPHS: Map<WeatherIcon, Path> by lazy {
    mapOf(
        WeatherIcon.SUN to "M16.2 12A4.2 4.2 0 1 1 7.8 12A4.2 4.2 0 1 1 16.2 12Z" +
            "M12 2V4.6M12 19.4V22M2 12H4.6M19.4 12H22M4.9 4.9L6.7 6.7M17.3 17.3L19.1 19.1M4.9 19.1L6.7 17.3M17.3 6.7L19.1 4.9",
        WeatherIcon.MOON to "M20 14.6A8.4 8.4 0 1 1 9.4 4A6.7 6.7 0 0 0 20 14.6Z",
        WeatherIcon.PARTLY_CLOUDY to "M11.6 8.2A3 3 0 1 0 6.2 10.4M8.6 3V4.4M3.2 8.4H4.6M4.8 4.6L5.8 5.6M12.4 4.6L11.4 5.6" +
            "M9.5 20H18.5A3 3 0 0 0 18.8 14A4.7 4.7 0 0 0 9.9 14.3A2.9 2.9 0 0 0 9.5 20Z",
        WeatherIcon.CLOUD to CLOUD,
        WeatherIcon.FOG to "M4 8H20M3 12H21M5 16H19M8 20H16",
        WeatherIcon.RAIN to "$HIGH_CLOUD M8.5 18L7.5 20.5M12.5 18L11.5 20.5M16.5 18L15.5 20.5",
        WeatherIcon.SNOW to "$HIGH_CLOUD M8 19V19.1M12 20.5V20.6M16 19V19.1",
        WeatherIcon.THUNDER to "$HIGH_CLOUD M12.8 16L10.8 19.2H13.4L11.6 22.5",
    ).mapValues { (_, data) -> PathParser().parsePathString(data).toPath() }
}

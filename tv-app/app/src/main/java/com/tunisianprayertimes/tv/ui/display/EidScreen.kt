package com.tunisianprayertimes.tv.ui.display

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.mosque.DayBanner
import com.tunisianprayertimes.mosque.DisplayTexts
import com.tunisianprayertimes.tv.ui.TvStrings
import com.tunisianprayertimes.tv.ui.theme.Amiri
import com.tunisianprayertimes.tv.ui.theme.Kufi
import com.tunisianprayertimes.tv.ui.theme.LocalDisplayTheme
import com.tunisianprayertimes.tv.ui.theme.Midad
import com.tunisianprayertimes.tv.ui.theme.SkyPhase
import com.tunisianprayertimes.tv.ui.theme.lineBox
import com.tunisianprayertimes.tv.ui.theme.midadStyle
import java.time.LocalDateTime

/**
 * The morning of Eid ([EidMorning.isShown]), the one festive screen of the year: a sunrise sky, a band
 * of Qallaline tiles above and below, the greeting, both dates, and the Eid prayer's time until it
 * begins ([banner]'s prayerAt is null from then on), then [nextPrayerLine]. [hijriLabel] is today's, "1 شوال 1448 هـ".
 * On the «مداد» theme the sky gives way to the plain ground; the tiles stay.
 */
@Composable
fun EidScreen(banner: DayBanner.Eid, now: LocalDateTime, mosqueName: String, hijriLabel: String, nextPrayerLine: String?) {
    val background = if (LocalDisplayTheme.current.sky) Modifier.background(SunriseSky) else Modifier.background(Midad.Ground)
    Column(Modifier.fillMaxSize().then(background)) {
        QallalineBand(shifted = false)
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 20.dp, bottom = 15.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MosqueClockRow(mosqueName, now, nameSize = 22.sp)
            val title = midadStyle(115.sp, FontWeight.SemiBold, family = Kufi, lineHeight = 1.1f)
            Text(TvStrings.EID_MUBARAK, style = title, maxLines = 1, modifier = Modifier.padding(top = 30.dp).lineBox(title))
            Text(
                "${TvStrings.gregorianDate(now.toLocalDate())} · $hijriLabel",
                style = midadStyle(20.sp, color = DateOnSunrise),
                modifier = Modifier.padding(top = 5.dp),
            )
            Text(
                DisplayTexts.EID_GREETING.text,
                style = midadStyle(28.sp, family = Amiri, lineHeight = 1.5f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 11.dp),
            )
            val at = banner.prayerAt
            if (at != null) {
                Row(
                    Modifier.padding(top = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${TvStrings.PRAYER_OF} ${TvStrings.prayerName(banner.prayer)}", style = midadStyle(30.sp))
                    val time = midadStyle(75.sp, FontWeight.Bold, Midad.Gold, lineHeight = 1f)
                    Text(TvStrings.hm(at.toLocalTime()), style = time, maxLines = 1, modifier = Modifier.lineBox(time))
                }
            } else if (nextPrayerLine != null) {
                // Once the Eid prayer has begun, the next daily prayer, so the wall still tells the time of Dhuhr.
                Text(nextPrayerLine, style = midadStyle(26.sp, color = DateOnSunrise), modifier = Modifier.padding(top = 40.dp))
            }
        }
        QallalineBand(shifted = true)
    }
}

/** The sunrise sky, full height: rose toward the lower third, then a deeper violet at the bottom. */
private val SunriseSky = Brush.verticalGradient(
    0f to SkyPhase.SUNRISE.colors.zenith,
    760f / 1080f to SkyPhase.SUNRISE.colors.horizon,
    1f to Color(0xFF2A2238),
)

/** The dates, a little rosier than the ivory, on the sunrise. */
private val DateOnSunrise = Color(0xFFD6CEDA)

/**
 * The glazed tiles of Qallaline, the Tunis potters' quarter: green, yellow, blue and cream, each tile
 * four squares as one turn of the board's conic gradient. On this screen only, and only in a band.
 */
private val QALLALINE_TOP_RIGHT = Color(0xFF56B58F)
private val QALLALINE_BOTTOM_RIGHT = Color(0xFFE4B84E)
private val QALLALINE_BOTTOM_LEFT = Color(0xFF8AA6E6)
private val QALLALINE_TOP_LEFT = Color(0xFFF2EADB)
private const val QALLALINE_ALPHA = 0.85f

/**
 * A 28 dp band of tiles; the lower one [shifted] by half a tile, so the two bands do not mirror each
 * other. The squares are laid opaque over full-width rows and the band is faded as one layer, so the
 * sky never shows through a seam between two squares.
 */
@Composable
private fun QallalineBand(shifted: Boolean) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .graphicsLayer {
                alpha = QALLALINE_ALPHA
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithCache {
                val tile = size.height
                val half = tile / 2f
                onDrawBehind {
                    drawRect(QALLALINE_BOTTOM_LEFT)
                    drawRect(QALLALINE_TOP_LEFT, size = Size(size.width, half))
                    var x = if (shifted) half - tile else 0f
                    while (x < size.width) {
                        drawRect(QALLALINE_TOP_RIGHT, Offset(x + half, 0f), Size(half, half))
                        drawRect(QALLALINE_BOTTOM_RIGHT, Offset(x + half, half), Size(half, half))
                        x += tile
                    }
                }
            },
    )
}

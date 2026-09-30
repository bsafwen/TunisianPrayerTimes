package com.tunisianprayertimes.tv.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.mosque.FlowPhase
import com.tunisianprayertimes.tv.ui.theme.Midad
import com.tunisianprayertimes.tv.ui.theme.midadStyle

/**
 * Where a notice goes: over the bottom of the wall, in the thin bar at the top of the admin pages, in
 * the wall's own top bar (lower, where an overscanning TV still shows it, in the top mark's place), or
 * held until later.
 */
enum class NoticePlace { BOTTOM, TOP, WALL_TOP, HELD }

/**
 * Notices (a USB key, the clock, the Back hint) never land on the prayer's texts: over the khutba and
 * the salah nothing shows; over the adhan replies, the iqamah countdown and the adhkar after the prayer
 * (their count and source sit at the bottom) a notice waits for the wall, and only the copy's notice
 * and its outcome ([copying]) show, in the wall's top bar, so the admin does not pull the key and hears
 * at once how the copy ended. On the admin pages and the onboarding they go in the top bar, clear of
 * the keys and hints.
 */
object NoticePlacement {

    fun of(phase: FlowPhase, adhkarOnWall: Boolean, onDisplay: Boolean, inSettings: Boolean, copying: Boolean): NoticePlace = when {
        inSettings || !onDisplay -> NoticePlace.TOP
        phase == FlowPhase.KHUTBA || phase == FlowPhase.SALAH -> NoticePlace.HELD
        phase == FlowPhase.ADHAN || phase == FlowPhase.IQAMAH_COUNTDOWN || adhkarOnWall ->
            if (copying) NoticePlace.WALL_TOP else NoticePlace.HELD
        else -> NoticePlace.BOTTOM
    }
}

/**
 * A short message over the bottom of the screen (a USB key, the clock): a dark card with a hairline
 * edge, big enough to be read from the back of the hall, as wide as its words and no wider. [atTop]
 * on the admin pages: a thin bar in the top margin, clear of their keys and hints at the bottom. On
 * the wall ([onWall]) the bar sits as low as [TopMark], which it replaces, so a TV that hides 5% of the
 * picture still shows it.
 */
@Composable
fun ScreenNotice(message: String, atTop: Boolean = false, onWall: Boolean = false) {
    if (atTop) {
        val top = if (onWall) WALL_TOP_INSET else 2.dp
        Box(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = top), contentAlignment = Alignment.TopCenter) {
            Text(
                message,
                style = midadStyle(13.sp).rtl(),
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier
                    .widthIn(max = 864.dp)
                    .background(Midad.Surface, BAR_SHAPE)
                    .border(1.dp, Midad.Keyline, BAR_SHAPE)
                    .padding(horizontal = 16.dp, vertical = 3.dp),
            )
        }
        return
    }
    Box(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp), contentAlignment = Alignment.BottomCenter) {
        Text(
            message,
            style = midadStyle(18.sp, lineHeight = 1.5f).rtl(),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .widthIn(max = 760.dp)
                .background(Midad.Surface, NOTICE_SHAPE)
                .border(1.dp, Midad.Keyline, NOTICE_SHAPE)
                .padding(horizontal = 24.dp, vertical = 14.dp),
        )
    }
}

/**
 * A discreet line at the top of the display, e.g. while someone manages the screen from a phone: in
 * the band above the screens' 27 dp margin, but low enough for a TV that hides 5% of the picture.
 */
@Composable
fun TopMark(message: String) {
    Box(Modifier.fillMaxSize().padding(top = WALL_TOP_INSET), contentAlignment = Alignment.TopCenter) {
        Text(message, style = midadStyle(12.sp, color = Midad.Dim).rtl())
    }
}

/** Inside the title-safe band of a TV that hides 5% of the picture. */
private val WALL_TOP_INSET = 13.dp
private val NOTICE_SHAPE = RoundedCornerShape(14.dp)
private val BAR_SHAPE = RoundedCornerShape(8.dp)

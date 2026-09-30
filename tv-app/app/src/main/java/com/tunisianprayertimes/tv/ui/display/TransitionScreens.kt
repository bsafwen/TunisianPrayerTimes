package com.tunisianprayertimes.tv.ui.display

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.mosque.AdhanReply
import com.tunisianprayertimes.mosque.MosqueAdhkar
import com.tunisianprayertimes.mosque.MosqueSchedule
import com.tunisianprayertimes.mosque.PrayerEvent
import com.tunisianprayertimes.tv.ui.TvStrings
import com.tunisianprayertimes.tv.ui.common.Digits
import com.tunisianprayertimes.tv.ui.theme.Amiri
import com.tunisianprayertimes.tv.ui.theme.Dots
import com.tunisianprayertimes.tv.ui.theme.Kufi
import com.tunisianprayertimes.tv.ui.theme.Midad
import com.tunisianprayertimes.tv.ui.theme.PhoneOffGlyph
import com.tunisianprayertimes.tv.ui.theme.SkyColors
import com.tunisianprayertimes.tv.ui.theme.lineBox
import com.tunisianprayertimes.tv.ui.theme.midadStyle
import com.tunisianprayertimes.tv.ui.theme.mihrab
import com.tunisianprayertimes.tv.ui.theme.skyBackground
import java.time.LocalDateTime
import kotlin.math.roundToInt

/*
 * The screens of the prayer itself: the adhan in its mihrab, the countdown to the iqamah, the black
 * screen of the prayer and the quiet one of the khutba. The prayer flow picks one from the clock.
 */

/** The mosque's name on the sky of the adhan: a cooler ivory than the text on the ground. */
private val NameOnSky = Color(0xFFDDE2EE)

/** The same on the dimmed sky of the countdown. */
private val NameOnDimSky = Color(0xFFC8CEDF)

/**
 * The adhan: the sky of the moment pressed to the top, and in the mihrab the prayer's name, its time,
 * and, for the whole screen at once, what the listener says while the muezzin calls, in order
 * ([MosqueAdhkar.adhanReplies], from the reviewed catalog; Fajr's has two lines more). The wall cannot
 * follow the muezzin, so nothing moves with the call. [sky] is null on the «مداد» theme.
 */
@Composable
fun AdhanScreen(event: PrayerEvent, now: LocalDateTime, mosqueName: String, sky: SkyColors?) {
    Box(Modifier.fillMaxSize().skyBackground(sky, 115.dp, 165.dp)) {
        MosqueClockRow(mosqueName, now, Modifier.padding(top = 27.dp, start = 48.dp, end = 48.dp), nameColor = NameOnSky)
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 75.dp, bottom = 14.dp)
                .width(500.dp)
                .fillMaxHeight()
                .mihrab()
                // Inside the niche's walls, under its keystone, clear of its double line.
                .padding(top = 78.dp, start = 28.dp, end = 28.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(TvStrings.ADHAN_NOW, style = midadStyle(18.sp, color = Midad.Muted))
            // Smaller than on the countdown: the replies below need the height, Fajr's eight lines included.
            val name = midadStyle(72.sp, FontWeight.SemiBold, family = Kufi, lineHeight = 1.05f)
            Text(TvStrings.prayerName(event.prayer), style = name, maxLines = 1, modifier = Modifier.padding(top = 2.dp).lineBox(name))
            val time = midadStyle(30.sp, FontWeight.SemiBold, Midad.Gold, lineHeight = 1f)
            Text(TvStrings.hm(event.adhanAt.toLocalTime()), style = time, maxLines = 1, modifier = Modifier.padding(top = 2.dp).lineBox(time))
            AdhanReplies(MosqueAdhkar.adhanReplies(fajr = event.prayer == Prayer.FAJR), Modifier.padding(top = 10.dp).weight(1f, fill = false))
        }
    }
}

/**
 * The listener's lines in Amiri, on the niche's axis, two by two where a phrase is said twice in a row
 * ([adhanRows]), with no source line: the owner's choice, since no one hadith covers every line (the
 * sources are in docs/adhkar-sources.md). Set at its size and made smaller as a whole if the niche is
 * too small for it, so no line is ever cut or wrapped.
 */
@Composable
private fun AdhanReplies(replies: List<AdhanReply>, modifier: Modifier) {
    ShrinkToFit(modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // As far apart as the ticker's Amiri lines: closer, one line's vowel marks reach the next one's.
            val line = midadStyle(21.sp, family = Amiri, lineHeight = 1.75f)
            adhanRows(replies).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    row.forEach { Text(it, style = line, maxLines = 1, softWrap = false, modifier = Modifier.lineBox(line)) }
                }
            }
        }
    }
}

/**
 * The replies as rows: a phrase said twice in a row shares one, as the muezzin calls it and as «الله أكبر
 * الله أكبر» already reads; more of the same go on two by two, and an odd one stays on its own.
 */
internal fun adhanRows(replies: List<AdhanReply>): List<List<String>> {
    val rows = mutableListOf<MutableList<String>>()
    for (reply in replies) {
        val last = rows.lastOrNull()
        if (last != null && last.size == 1 && last.single() == reply.text) last += reply.text else rows += mutableListOf(reply.text)
    }
    return rows
}

/**
 * [content] measured at its own size, then drawn scaled down (never up) to fit the space it is given,
 * centred at the top: a religious text is shown whole, smaller if need be, never cut.
 */
@Composable
private fun ShrinkToFit(modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val placeable = measurables.single().measure(Constraints())
        val scale = listOf(
            1f,
            if (constraints.hasBoundedWidth) constraints.maxWidth / placeable.width.coerceAtLeast(1).toFloat() else 1f,
            if (constraints.hasBoundedHeight) constraints.maxHeight / placeable.height.coerceAtLeast(1).toFloat() else 1f,
        ).min()
        val width = (placeable.width * scale).roundToInt().coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = (placeable.height * scale).roundToInt().coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            // Scaled from its top centre, over the centre of the box it is given.
            placeable.placeWithLayer((width - placeable.width) / 2, 0) {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0.5f, 0f)
            }
        }
    }
}

/**
 * The wait for the iqamah: one number for the back of the hall, and 24 studs that go out one by one
 * from the adhan to the iqamah. The last minute turns warm and the phones line brightens; nothing
 * blinks. The Eid prayer has no adhan or iqamah: its wait from sunrise counts down to the prayer.
 * [event] is the flow's current one; [sky] is dimmed here, and null on the «مداد» theme.
 */
@Composable
fun IqamahCountdownScreen(event: PrayerEvent, now: LocalDateTime, mosqueName: String, sky: SkyColors?) {
    val remaining = IqamahWait.remainingSeconds(now, event.iqamahAt)
    val litStuds = IqamahWait.litStuds(now, event.adhanAt, event.iqamahAt)
    val eid = event.prayer in MosqueSchedule.EID
    val phones = if (remaining <= SILENCE_PHONES_SECONDS) Midad.Text else Midad.Dim
    Column(
        Modifier
            .fillMaxSize()
            .skyBackground(sky?.dimmed(0.5f), 110.dp, 160.dp)
            .padding(horizontal = 48.dp, vertical = 27.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MosqueClockRow(mosqueName, now, nameColor = NameOnDimSky)
        Row(Modifier.padding(top = 48.dp), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            val label = midadStyle(28.sp, color = Midad.Muted)
            Text(if (eid) TvStrings.PRAYER_OF else TvStrings.IQAMAH_OF, style = label, modifier = Modifier.alignByBaseline())
            Text(
                TvStrings.prayerName(event.prayer),
                style = midadStyle(42.sp, FontWeight.SemiBold, family = Kufi),
                modifier = Modifier.alignByBaseline(),
            )
            Text(TvStrings.AFTER, style = label, modifier = Modifier.alignByBaseline())
        }
        Digits(
            IqamahWait.text(remaining),
            midadStyle(210.sp, FontWeight.Bold, if (remaining <= IqamahWait.ALERT_SECONDS) Midad.Alert else Midad.Text, lineHeight = 0.9f),
            Modifier.padding(top = 3.dp),
            tracking = (-5).dp,
        )
        Dots(
            IqamahWait.STUDS,
            lit = { it < litStuds },
            modifier = Modifier.padding(top = 22.dp),
            size = 9.dp,
            gap = 10.dp,
            off = Midad.Keyline,
            leftToRight = true,
        )
        Text(
            "${if (eid) TvStrings.PRAYER_LABEL else TvStrings.IQAMAH_LABEL} ${TvStrings.hm(event.iqamahAt.toLocalTime())}",
            style = midadStyle(20.sp),
            modifier = Modifier.padding(top = 18.dp),
        )
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PhoneOffGlyph(20.dp, color = phones)
            Text(TvStrings.PHONES_OFF, style = midadStyle(17.sp, color = phones))
        }
    }
}

/** How long before the iqamah the phones line brightens: the congregation is lining up. */
const val SILENCE_PHONES_SECONDS = 90

/**
 * After the iqamah the screen goes fully black for the prayer's duration, so nothing on
 * the wall distracts the congregation. The prayer flow decides when it ends.
 */
@Composable
fun PrayerBlackScreen() {
    Box(Modifier.fillMaxSize().background(Midad.Prayer))
}

/**
 * The Friday sermon, between the Jumu'a adhan and its iqamah: black and one dim line, so nothing on
 * the wall competes with the khatib.
 */
@Composable
fun KhutbaScreen() {
    Box(Modifier.fillMaxSize().background(Midad.Prayer), contentAlignment = Alignment.Center) {
        Text(TvStrings.KHUTBA_SILENCE, style = midadStyle(22.sp, color = Midad.Khutba))
    }
}

/**
 * The mosque's name in Kufi and the clock, across the top of the full-screen states. The name gives
 * way (ellipsized) before the clock does.
 */
@Composable
internal fun MosqueClockRow(
    mosqueName: String,
    now: LocalDateTime,
    modifier: Modifier = Modifier,
    nameColor: Color = Midad.Text,
    nameSize: TextUnit = 20.sp,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 30.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            mosqueName.ifBlank { TvStrings.MOSQUE_DEFAULT },
            style = midadStyle(nameSize, FontWeight.SemiBold, nameColor, Kufi),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).padding(end = 24.dp),
        )
        Digits(TvStrings.hm(now.toLocalTime()), midadStyle(22.sp, FontWeight.SemiBold))
    }
}

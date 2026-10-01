package com.tunisianprayertimes.tv.ui.display

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.tunisianprayertimes.tv.data.Announcement
import com.tunisianprayertimes.tv.ui.TvStrings
import com.tunisianprayertimes.tv.ui.common.Digits
import com.tunisianprayertimes.tv.ui.theme.Dots
import com.tunisianprayertimes.tv.ui.theme.KhatamStar
import com.tunisianprayertimes.tv.ui.theme.Kufi
import com.tunisianprayertimes.tv.ui.theme.MedallionRule
import com.tunisianprayertimes.tv.ui.theme.Midad
import com.tunisianprayertimes.tv.ui.theme.midadStyle
import kotlinx.coroutines.delay
import java.time.LocalDateTime

/**
 * The mosque's announcements, one at a time, on the ground: a written one in a card under the clock,
 * an image whole in a quiet frame with [footer] under it (the next prayer and its iqamah, «العشاء 19:32
 * · الإقامة 19:40», built by the caller). Each stays [displaySeconds] with a gentle fade between them,
 * then after one pass [onDismiss]. The caller keys it on the list, so a new list starts over. [now]
 * ticks the clock above a written announcement: the display's clock, always passed on the wall
 * (null leaves the clock out).
 */
@Composable
fun AnnouncementsSlideshow(
    announcements: List<Announcement>,
    onDismiss: () -> Unit,
    now: LocalDateTime? = null,
    displaySeconds: Int = 15,
    footer: String? = null,
) {
    val dismiss by rememberUpdatedState(onDismiss)
    if (announcements.isEmpty()) {
        LaunchedEffect(Unit) { dismiss() }
        return
    }

    var index by remember { mutableIntStateOf(0) }
    // Starts hidden, so the first announcement fades in like the others.
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(index) {
        visible = true
        delay(displaySeconds * 1000L)
        visible = false
        delay(FADE_MILLIS + PAUSE_MILLIS)
        val next = nextSlide(index, announcements.size)
        if (next == null) dismiss() else index = next
    }

    val clock = now?.let { TvStrings.hm(it.toLocalTime()) }
    Box(Modifier.fillMaxSize().background(Midad.Ground)) {
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(tween(FADE_MILLIS.toInt())),
            exit = fadeOut(tween(FADE_MILLIS.toInt())),
        ) {
            // The caller restarts the slideshow for a new list (key); a shorter list never reads past its end.
            val position = index % announcements.size
            when (val item = announcements[position]) {
                is Announcement.Image -> ImageSlide(item.uri, footer, position, announcements.size)
                is Announcement.Text -> WrittenSlide(item, clock, position, announcements.size)
            }
        }
    }
}

/** The announcement after [index] in a pass over [count], or null once the pass is over. */
internal fun nextSlide(index: Int, count: Int): Int? = (index + 1).takeIf { it < count }

/** How long a pass over [count] announcements of [displaySeconds] each lasts, with the fade and pause after each. */
internal fun passMillis(count: Int, displaySeconds: Int): Long = count * (displaySeconds * 1000L + FADE_MILLIS + PAUSE_MILLIS)

/** How the slideshow shows where it is: nothing for one announcement, dots, or words past [MAX_DOTS]. */
internal enum class PagerKind { NONE, DOTS, WORDS }

internal fun pagerKind(count: Int): PagerKind = when {
    count <= 1 -> PagerKind.NONE
    count <= MAX_DOTS -> PagerKind.DOTS
    else -> PagerKind.WORDS
}

/** The first of [ladder] (largest first) for which [fits] holds, else the last: a long text shrinks to its card. */
internal fun <T> largestFitting(ladder: List<T>, fits: (T) -> Boolean): T = ladder.firstOrNull(fits) ?: ladder.last()

/** The mosque's words in a card, as large as they fit: read from the back of the hall. */
@Composable
private fun WrittenSlide(item: Announcement.Text, clock: String?, position: Int, count: Int) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SlideHeader(clock)
        Spacer(Modifier.height(35.dp))
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            val cardWidth = min(CARD_WIDTH, maxWidth)
            WrittenCard(
                item = item,
                width = cardWidth,
                textWidth = cardWidth - CARD_PADDING_H * 2 - CARD_BORDER * 2,
                textHeight = maxHeight - CARD_PADDING_V * 2 - CARD_BORDER * 2,
            )
        }
        Pager(position, count, Modifier.padding(top = 16.dp))
    }
}

/** «إعلان» with its star on the right, the clock on the left. */
@Composable
private fun SlideHeader(clock: String?) {
    Row(
        Modifier.fillMaxWidth().height(30.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            KhatamStar(14.dp)
            Text(TvStrings.ANNOUNCEMENT_LABEL, style = midadStyle(18.sp, color = Midad.Muted))
        }
        if (clock != null) Digits(clock, midadStyle(22.sp, FontWeight.SemiBold))
    }
}

/**
 * The card: a title in Kufi over a medallion rule when there is one, the text, and its end date.
 * Most announcements are a single line with no title (a .txt file, the settings file): the text then
 * takes the title's place and size. Either way it steps down in size until the card fits in
 * [textWidth] × [textHeight], the room inside the card's padding.
 */
@Composable
private fun WrittenCard(item: Announcement.Text, width: Dp, textWidth: Dp, textHeight: Dp) {
    val hasTitle = item.title.isNotBlank()
    val until = item.until?.let(TvStrings::announcementUntil)
    // Measured as shown: with their phone numbers and dates kept left to right.
    val title = remember(item) { TvStrings.mosqueText(item.title) }
    val content = remember(item) { TvStrings.mosqueText(item.content) }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val bodyStyle = remember(item, textWidth, textHeight, density) {
        with(density) {
            val widthPx = textWidth.roundToPx().coerceAtLeast(0)
            val gap = CARD_GAP.toPx()
            fun heightOf(text: String, style: TextStyle, maxLines: Int = Int.MAX_VALUE) =
                measurer.measure(text, style, maxLines = maxLines, constraints = Constraints(maxWidth = widthPx)).size.height
            var room = textHeight.toPx()
            if (hasTitle) room -= heightOf(title, TITLE_STYLE, maxLines = 2) + gap + RULE_STAR.toPx() + gap
            if (until != null) room -= heightOf(until, UNTIL_STYLE) + gap
            val ladder = if (hasTitle) BODY_SIZES.map(::bodyStyle) else LEAD_SIZES.map(::leadStyle)
            largestFitting(ladder) { heightOf(content, it) <= room }
        }
    }
    Column(
        Modifier
            .width(width)
            .border(CARD_BORDER, Midad.Keyline, CARD_SHAPE)
            .padding(CARD_BORDER)
            .padding(horizontal = CARD_PADDING_H, vertical = CARD_PADDING_V),
        verticalArrangement = Arrangement.spacedBy(CARD_GAP),
    ) {
        if (hasTitle) {
            Text(title, style = TITLE_STYLE, maxLines = 2, overflow = TextOverflow.Ellipsis)
            MedallionRule(260.dp, starSize = RULE_STAR, bothSides = false)
        }
        Text(content, style = bodyStyle, overflow = TextOverflow.Ellipsis)
        if (until != null) Text(until, style = UNTIL_STYLE)
    }
}

/** An image whole in its frame (never cropped), the pager and the next prayer under it. */
@Composable
private fun ImageSlide(uri: Uri, footer: String?, position: Int, count: Int) {
    val context = LocalContext.current
    val painter = rememberAsyncImagePainter(
        model = ImageRequest.Builder(context).data(uri).crossfade(true).build(),
        contentScale = ContentScale.Fit,
    )
    Column(
        Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(15.dp),
    ) {
        Box(
            Modifier
                .size(width = FRAME_WIDTH, height = 405.dp)
                .clip(FRAME_SHAPE)
                .background(Midad.Surface)
                // Drawn over the image, so the frame's edge stays clean where the picture reaches it.
                .border(1.dp, Midad.Keyline, FRAME_SHAPE),
        ) {
            Image(painter = painter, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
        Box(Modifier.width(FRAME_WIDTH)) {
            Pager(position, count, Modifier.align(Alignment.CenterStart))
            if (footer != null) {
                Text(
                    footer,
                    style = midadStyle(16.sp, color = Midad.Muted),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
    }
}

/** Where the slideshow is: a dot per announcement (the first on the right), or «3 من 40» for a long list. */
@Composable
private fun Pager(position: Int, count: Int, modifier: Modifier = Modifier) {
    when (pagerKind(count)) {
        PagerKind.NONE -> Unit
        PagerKind.DOTS -> Dots(count, lit = { it == position }, modifier = modifier)
        PagerKind.WORDS -> Text(TvStrings.progress(position + 1, count), style = midadStyle(14.sp, color = Midad.Muted), modifier = modifier)
    }
}

private fun leadStyle(size: TextUnit) = midadStyle(size, FontWeight.Medium, lineHeight = 1.4f)
private fun bodyStyle(size: TextUnit) = midadStyle(size, lineHeight = 1.5f)

private val TITLE_STYLE = midadStyle(56.sp, FontWeight.SemiBold, family = Kufi, lineHeight = 1.2f)
private val UNTIL_STYLE = midadStyle(18.sp, color = Midad.Muted)

/** A text with no title starts at the title's size; under a title it starts at the mockup's 30 sp. */
private val LEAD_SIZES = listOf(56.sp, 50.sp, 44.sp, 40.sp, 36.sp, 32.sp, 28.sp, 26.sp, 24.sp, 22.sp)
private val BODY_SIZES = listOf(30.sp, 28.sp, 26.sp, 24.sp, 22.sp, 20.sp)

private val CARD_WIDTH = 750.dp
private val CARD_PADDING_H = 45.dp
private val CARD_PADDING_V = 38.dp
private val CARD_BORDER = 1.dp
private val CARD_GAP = 20.dp
private val CARD_SHAPE = RoundedCornerShape(18.dp)
private val RULE_STAR = 17.dp

private val FRAME_WIDTH = 720.dp
private val FRAME_SHAPE = RoundedCornerShape(14.dp)

/** Past this many announcements the dots would crowd the footer: «3 من 40» instead. */
internal const val MAX_DOTS = 20

private const val FADE_MILLIS = 500L
/** The screen stays on the bare ground a moment between two announcements. */
private const val PAUSE_MILLIS = 100L

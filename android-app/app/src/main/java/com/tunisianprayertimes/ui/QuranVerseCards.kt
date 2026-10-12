package com.tunisianprayertimes.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.R
import com.tunisianprayertimes.quran.QuranVerseReference
import com.tunisianprayertimes.ui.theme.*
import kotlinx.coroutines.withContext

/** The colour of a verse with a hifz point to keep in mind: a light tint and a dashed outline. */
internal val QuranHifzViolet = androidx.compose.ui.graphics.Color(0xFF7E57C2)

/** What a selected verse offers. [slipLabel] is «التصحيح» once the verse has a hifz point. */
@Composable
internal fun QuranVerseCard(
    title: String,
    bookmarked: Boolean,
    hasPoint: Boolean,
    onListen: () -> Unit,
    onRepeat: () -> Unit,
    onPoint: () -> Unit,
    onBookmark: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** «مراجعة»: the verse covers itself again after this long; a line along the top counts it down. */
    hidesAfterMs: Long? = null,
) {
    QuranFloatingCard(modifier.testTag("quran_verse_card")) {
        if (hidesAfterMs != null) QuranCountdownLine(title, hidesAfterMs, GreenPrimary.copy(alpha = 0.55f))
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 2.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
            IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                Icon(painterResource(R.drawable.ic_adhkar_close), "إلغاء التحديد", Modifier.size(18.dp), tint = TextMuted)
            }
        }
        val actions: List<@Composable RowScope.() -> Unit> = listOf(
            { CardAction(R.drawable.ic_quran_headphones, "استمع", onListen, Modifier.testTag("quran_verse_listen")) },
            { CardAction(R.drawable.ic_quran_repeat, "كرّر", onRepeat, Modifier.testTag("quran_verse_repeat")) },
            {
                if (hasPoint) CardAction(R.drawable.ic_quran_mark, "التصحيح", onPoint, Modifier.testTag("quran_verse_fix"), tint = QuranHifzViolet,
                    description = "التصحيح: الصواب في هذا الموضع")
                else CardAction(R.drawable.ic_quran_slip, "موضع", onPoint, Modifier.testTag("quran_verse_point"), description = "سجّل موضعا للمراجعة")
            },
            {
                CardAction(
                    if (bookmarked) R.drawable.ic_quran_bookmark_filled else R.drawable.ic_adhkar_bookmark,
                    if (bookmarked) "محفوظة" else "علامة", onBookmark, Modifier.testTag("quran_verse_bookmark"),
                    tint = if (bookmarked) GreenPrimary else GreenPrimaryDark,
                )
            },
            { CardAction(R.drawable.ic_quran_copy, "نسخ", onCopy, Modifier.testTag("quran_verse_copy")) },
            { CardAction(R.drawable.ic_quran_share, "مشاركة", onShare, Modifier.testTag("quran_verse_share")) },
        )
        // One row of six while every label fits whole; with large text, two rows of three, never a label cut short.
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val labelStyle = LocalTextStyle.current.merge(CardActionLabelStyle)
        val widest = remember(measurer, density, labelStyle) {
            CardActionLabels.maxOf { measurer.measure(it, labelStyle).size.width }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 4.dp).padding(bottom = 4.dp)) {
            val oneRow = with(density) { widest.toDp() } + 4.dp <= maxWidth / actions.size
            if (oneRow) Row(Modifier.fillMaxWidth()) { actions.forEach { it() } }
            else Column {
                actions.chunked(3).forEach { row -> Row(Modifier.fillMaxWidth()) { row.forEach { it() } } }
            }
        }
    }
}

@Composable
private fun RowScope.CardAction(
    icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: androidx.compose.ui.graphics.Color = GreenPrimaryDark,
    description: String = label,
) {
    Column(
        modifier.weight(1f).heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp))
            .semantics(mergeDescendants = true) { contentDescription = description }
            .clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(icon), null, Modifier.size(24.dp), tint = tint)
        Text(label, style = LocalTextStyle.current.merge(CardActionLabelStyle), color = tint, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}

/** The actions' labels, as drawn and as measured. */
private val CardActionLabelStyle = TextStyle(fontSize = 12.sp)

/** Every label a verse card's action may show, to measure the widest. */
private val CardActionLabels = listOf("استمع", "كرّر", "التصحيح", "موضع", "محفوظة", "علامة", "نسخ", "مشاركة")

private object QuranRealTime : MotionDurationScale {
    override val scaleFactor: Float get() = 1f
}

/** A full-width line that shrinks toward its start over [durationMs]; what it belongs to hides when it is gone. */
@Composable
internal fun QuranCountdownLine(key: Any, durationMs: Long, color: Color, modifier: Modifier = Modifier) {
    val left = remember(key, durationMs) { Animatable(1f) }
    LaunchedEffect(left) {
        // In real time, as the hide it counts down: never at the system's animation speed.
        withContext(QuranRealTime) { left.animateTo(0f, tween(durationMs.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt(), easing = LinearEasing)) }
    }
    Spacer(
        modifier.fillMaxWidth().height(2.dp).drawBehind {
            // Read while drawing: the line moves without recomposing anything. It keeps the right, where Arabic starts.
            val width = size.width * left.value
            drawRect(color, topLeft = Offset(size.width - width, 0f), size = Size(width, size.height))
        },
    )
}

/** A passage being chosen on the page: its start is set, the end is any verse tapped next. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuranRangeCard(
    title: String,
    endChosen: Boolean,
    times: Int?,
    onTimes: (Int?) -> Unit,
    onToPageEnd: () -> Unit,
    onToSurahEnd: () -> Unit,
    onTyped: () -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var more by remember { mutableStateOf(false) }
    QuranFloatingCard(modifier.testTag("quran_range_card")) {
        Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark, maxLines = 2, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = onCancel, modifier = Modifier.size(40.dp)) {
                    Icon(painterResource(R.drawable.ic_adhkar_close), "إلغاء", Modifier.size(18.dp), tint = TextMuted)
                }
            }
            if (!endChosen) Text("اضغط آية النهاية على الصفحة، ولو بعد تقليب الصفحة", fontSize = 12.sp, color = TextMuted)
            // One scrolling row: on a narrow phone with large text the card must not take half the page.
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(end = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(3, 5, 10, null).forEach { n ->
                    FilterChip(
                        selected = times == n,
                        onClick = { onTimes(n) },
                        label = { Text(if (n == null) "بلا توقف" else "$n مرات") },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = GreenPrimary, selectedLabelColor = androidx.compose.ui.graphics.Color.White),
                        modifier = Modifier.testTag("quran_range_times_${n ?: 0}"),
                    )
                }
            }
            Row(Modifier.padding(end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    AssistChip(onClick = { more = true }, label = { Text("المزيد ▾") })
                    DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                        DropdownMenuItem(text = { Text("إلى نهاية الصفحة") }, onClick = { more = false; onToPageEnd() })
                        DropdownMenuItem(text = { Text("إلى نهاية السورة") }, onClick = { more = false; onToSurahEnd() })
                        DropdownMenuItem(text = { Text("بالأرقام…") }, onClick = { more = false; onTyped() })
                    }
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = onStart, colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary), modifier = Modifier.testTag("quran_range_start")) {
                    Text("ابدأ التكرار")
                }
            }
        }
    }
}

@Composable
internal fun QuranFloatingCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        color = BgCream,
        border = BorderStroke(1.dp, CardBorder),
        shadowElevation = 6.dp,
    ) { Column(content = content) }
}

/** «تكرار من البقرة 19 إلى 21», or «تكرار البقرة 19» while only the start is set. */
internal fun quranRangeTitle(surahName: (Int) -> String, from: QuranVerseReference, to: QuranVerseReference?): String = when {
    to == null || to == from -> "تكرار ${surahName(from.surah)} ${from.ayah}"
    from.surah == to.surah -> "تكرار من ${surahName(from.surah)} ${from.ayah} إلى ${to.ayah}"
    else -> "تكرار من ${surahName(from.surah)} ${from.ayah} إلى ${surahName(to.surah)} ${to.ayah}"
}

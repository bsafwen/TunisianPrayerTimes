package com.tunisianprayertimes.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.R
import com.tunisianprayertimes.quran.QuranPlayAction
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tunisianprayertimes.quran.audio.QuranAudioController
import com.tunisianprayertimes.quran.audio.QuranPlaybackState
import com.tunisianprayertimes.quran.audio.QuranSurahRecording
import com.tunisianprayertimes.ui.theme.*
import java.util.Locale
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Two lines beside the controls, and an optional link on the second one. [announce]: an event TalkBack says as it
 * happens (a stop, an end); the verse moving on is not one, or TalkBack would talk over the recitation at every verse.
 */
@Immutable
internal data class QuranDockText(val title: String, val subtitle: String, val link: String? = null, val announce: Boolean = false)

/** A recitation that cannot play yet, shown in the dock's place with what can be done about it. */
@Immutable
internal data class QuranDockNotice(
    val title: String,
    val subtitle: String,
    val primary: String? = null,
    val secondary: String? = null,
    /** 0 to 1 while downloading; shown around the play slot and along the strip. */
    val progress: Float? = null,
    val busy: Boolean = false,
    /** What TalkBack says of a busy notice, once, as it appears: its percent changes at every step. */
    val spoken: String? = null,
)

/** Where a drag on the dock's strip would land: the verse's start, and the time there. */
@Immutable
internal data class QuranScrub(val ayah: Int, val positionMs: Long, val fraction: Float)

internal val QuranDockHeight = 64.dp
private val StripHeight = 14.dp

/**
 * [playback] with the clock as it is now: the reader hands one whose position stands still past a chapter's opening, so
 * that it does not recompose at every tick; the dock and the player, small, read the clock themselves.
 */
@Composable
internal fun rememberLiveClock(playback: QuranPlaybackState): QuranPlaybackState {
    val live by QuranAudioController.state.collectAsStateWithLifecycle()
    return if (live.surah == playback.surah && live.reciterId == playback.reciterId) {
        playback.copy(positionMs = live.positionMs, gapRemainingMs = live.gapRemainingMs)
    } else playback
}

@Composable
internal fun QuranDock(
    playback: QuranPlaybackState,
    track: QuranSurahRecording?,
    text: QuranDockText,
    notice: QuranDockNotice?,
    playAction: QuranPlayAction,
    /** A recitation is loaded (playing, paused or saved): previous, next and repeat are offered. */
    session: Boolean,
    reciterShortName: String,
    onPlay: () -> Unit,
    onLink: () -> Unit,
    onExpand: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    /** A held previous (negative) or next (positive) button steps this far, every 300 ms. */
    onSkipBy: (Long) -> Unit,
    onHold: (Long?) -> Unit,
    /** What repeats, in a word under the repeat button («السورة», «الآية», «آيات»); null when nothing does. */
    repeatLabel: String?,
    /** Opens «ماذا تريد أن تكرّر؟»; during a drill, ends the drill. */
    onRepeat: () -> Unit,
    onReciter: () -> Unit,
    onNoticePrimary: () -> Unit,
    onNoticeSecondary: () -> Unit,
    onScrub: (QuranScrub?) -> Unit,
    onScrubEnd: (QuranScrub) -> Unit,
    /** A finger is on the dock (true) or has left it: a paused dock stays while it is used. */
    onHeld: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val playback = rememberLiveClock(playback)
    val currentHeld by rememberUpdatedState(onHeld)
    Column(
        modifier.fillMaxWidth().height(QuranDockHeight).background(AdhkarSoftGreen).testTag("quran_dock")
            .pointerInput(Unit) {
                // Watched only, never consumed: the buttons and the strip under the finger work as before.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    currentHeld(true)
                    try {
                        do { val event = awaitPointerEvent(PointerEventPass.Initial) } while (event.changes.any { it.pressed })
                    } finally {
                        currentHeld(false)
                    }
                }
            },
    ) {
        HorizontalDivider(color = CardBorder)
        DockStrip(playback, track, session && notice == null, notice?.progress, onScrub, onScrubEnd, onExpand)
        Row(
            Modifier.fillMaxWidth().weight(1f).padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (notice != null) {
                DockNotice(notice, onNoticePrimary, onNoticeSecondary)
                return@Row
            }
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                    .clickable(onClickLabel = "فتح المشغّل الموسّع", onClick = onExpand)
                    .padding(vertical = 2.dp)
                    .semantics { if (text.announce) liveRegion = LiveRegionMode.Polite },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f, fill = false)) { DockLine(text.title, 14.sp, FontWeight.SemiBold, GreenPrimaryDark) }
                    // The way up to the full player, drawn as what it is rather than a character after the title.
                    if (session) Icon(painterResource(R.drawable.ic_expand_less), null, Modifier.padding(start = 2.dp).size(18.dp), tint = GreenPrimary)
                }
                if (text.link != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    // The link is the action: it keeps its width, the words before it give way.
                    if (text.subtitle.isNotEmpty()) Text(
                        text.subtitle + " · ", Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontSize = 12.sp, color = TextMuted,
                    )
                    Text(
                        text.link,
                        Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onLink).testTag("quran_dock_link"),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimary,
                        textDecoration = TextDecoration.Underline,
                    )
                } else DockLine(
                    text.subtitle, 12.sp,
                    when {
                        playback.gapAfter != null -> FontWeight.Bold
                        repeatLabel != null -> FontWeight.SemiBold
                        else -> FontWeight.Normal
                    },
                    // Your turn, or what repeats: said in green, as the repeat button is drawn.
                    if (playback.gapAfter != null || repeatLabel != null) GreenPrimary else TextMuted,
                )
            }
            if (session) {
                DockSkip(
                    R.drawable.ic_quran_verse_prev, "الآية السابقة", enabled = !playback.loading,
                    onClick = onPrevious, step = -5_000L, onSkipBy = onSkipBy, onHold = onHold,
                    modifier = Modifier.testTag("quran_dock_previous"),
                )
            }
            DockPlay(playback, playAction, notice = null, onPlay, Modifier.testTag("quran_dock_play"))
            if (session) {
                DockSkip(
                    R.drawable.ic_quran_verse_next, "الآية التالية", enabled = !playback.loading,
                    onClick = onNext, step = 5_000L, onSkipBy = onSkipBy, onHold = onHold,
                    modifier = Modifier.testTag("quran_dock_next"),
                )
                DockRepeat(playback, repeatLabel, onRepeat)
            } else {
                Box(Modifier.width(100.dp), contentAlignment = Alignment.Center) {
                    QuranReciterChip(reciterShortName, onReciter)
                }
            }
        }
    }
}

/** One line of the dock that shrinks a little before it is cut, for large fonts on narrow phones. */
@Composable
private fun DockLine(text: String, size: androidx.compose.ui.unit.TextUnit, weight: FontWeight, color: Color) {
    BasicText(
        text,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        style = LocalTextStyle.current.merge(androidx.compose.ui.text.TextStyle(fontSize = size, fontWeight = weight, color = color)),
        autoSize = TextAutoSize.StepBased(minFontSize = size * 0.78f, maxFontSize = size, stepSize = 0.5.sp),
    )
}

@Composable
internal fun QuranReciterChip(name: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = BgCream,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFC9BFAF)),
        modifier = modifier.heightIn(min = 36.dp).semantics { contentDescription = "القارئ: $name، اضغط للتغيير" },
    ) {
        Text(
            "$name ▾", Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark, maxLines = 1,
        )
    }
}

@Composable
private fun RowScope.DockNotice(notice: QuranDockNotice, onPrimary: () -> Unit, onSecondary: () -> Unit) {
    // A download's percent changes at every step: a busy notice is said once, by a text that stands still.
    val spoken = notice.spoken
    Column(
        Modifier.weight(1f).then(
            if (notice.busy && spoken != null) Modifier.clearAndSetSemantics { contentDescription = spoken; liveRegion = LiveRegionMode.Polite }
            else Modifier.semantics(mergeDescendants = true) { if (!notice.busy) liveRegion = LiveRegionMode.Polite },
        ),
    ) {
        Text(notice.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark)
        Text(notice.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = TextMuted)
    }
    if (notice.busy) {
        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
            val progress = notice.progress
            if (progress != null) CircularProgressIndicator(progress = { progress }, Modifier.size(32.dp), color = GreenPrimary, strokeWidth = 3.dp, trackColor = CardBorder)
            else CircularProgressIndicator(Modifier.size(28.dp), color = GreenPrimary, strokeWidth = 3.dp)
        }
    }
    notice.secondary?.let { label ->
        TextButton(onClick = onSecondary, contentPadding = PaddingValues(horizontal = 10.dp), modifier = Modifier.testTag("quran_dock_notice_secondary")) {
            Text(label, fontSize = 13.sp, maxLines = 1)
        }
    }
    notice.primary?.let { label ->
        Button(
            onClick = onPrimary,
            colors = ButtonDefaults.buttonColors(containerColor = GreenPrimary),
            contentPadding = PaddingValues(horizontal = 14.dp),
            modifier = Modifier.heightIn(min = 40.dp).testTag("quran_dock_notice_primary"),
        ) { Text(label, fontSize = 13.sp, maxLines = 1) }
    }
}

/** Play, pause, or a ring counting down the silent pause after a verse. */
@Composable
internal fun DockPlay(
    playback: QuranPlaybackState,
    action: QuranPlayAction,
    notice: QuranDockNotice?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 44.dp,
) {
    val pause = action == QuranPlayAction.Pause
    val description = when {
        playback.gapAfter != null -> "مهلة للترديد، اضغط للإيقاف المؤقّت"
        pause -> "إيقاف مؤقّت"
        else -> "تشغيل"
    }
    Box(
        modifier.size(size + 8.dp).clip(CircleShape)
            .clickable(enabled = !playback.loading && action != QuranPlayAction.None, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        val gap = playback.gapTotalMs.takeIf { playback.gapAfter != null && it > 0L }
        if (gap != null) {
            val left = playback.gapRemainingMs.toFloat() / gap
            Canvas(Modifier.size(size + 8.dp)) {
                val stroke = 3.dp.toPx()
                drawArc(GreenPrimary.copy(alpha = 0.18f), 0f, 360f, false, topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(this.size.width - stroke, this.size.height - stroke), style = Stroke(stroke))
                drawArc(GreenPrimary, -90f, 360f * left, false, topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(this.size.width - stroke, this.size.height - stroke), style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Box(
            Modifier.size(if (gap != null) size - 4.dp else size).clip(CircleShape)
                .background(if (action == QuranPlayAction.None) TextMuted.copy(alpha = 0.4f) else GreenPrimary),
            contentAlignment = Alignment.Center,
        ) {
            if (playback.loading) CircularProgressIndicator(Modifier.size(size * 0.5f), color = Color.White, strokeWidth = 2.dp)
            else Icon(
                painterResource(if (pause) R.drawable.ic_quran_pause else R.drawable.ic_play_arrow), null,
                tint = Color.White, modifier = Modifier.size(size * 0.55f),
            )
        }
    }
}

/**
 * A verse step: a tap moves one verse; held, it moves [step] milliseconds every 300 ms while the
 * recitation goes on, for hearing a phrase of a long verse again.
 */
@Composable
internal fun DockSkip(
    icon: Int,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    step: Long,
    onSkipBy: (Long) -> Unit,
    onHold: (Long?) -> Unit,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 48.dp,
) {
    val currentClick by rememberUpdatedState(onClick)
    val currentSkip by rememberUpdatedState(onSkipBy)
    val currentHold by rememberUpdatedState(onHold)
    val currentEnabled by rememberUpdatedState(enabled)
    val longPress = LocalViewConfiguration.current.longPressTimeoutMillis
    var pressed by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { if (pressed) currentHold(null) } }
    Box(
        modifier.size(size).clip(CircleShape)
            .background(if (pressed) GreenPrimary.copy(alpha = 0.14f) else Color.Transparent)
            .semantics {
                role = Role.Button
                contentDescription = description
                if (enabled) {
                    onClick { currentClick(); true }
                    customActions = listOf(CustomAccessibilityAction(if (step < 0) "رجوع 5${com.tunisianprayertimes.quran.hifz.HifzReminder.NB}ثوانٍ" else "تقديم 5${com.tunisianprayertimes.quran.hifz.HifzReminder.NB}ثوانٍ") { currentSkip(step); true })
                }
            }
            // Keyed on nothing: each skip makes the player load for a moment, which must not end the hold.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    if (!currentEnabled) return@awaitEachGesture
                    pressed = true
                    try {
                        // 1: released, a tap; 0: cancelled; null: still held after the long-press delay.
                        val early = withTimeoutOrNull(longPress) { if (waitForUpOrCancellation() != null) 1 else 0 }
                        if (early != null) {
                            if (early == 1) currentClick()
                            return@awaitEachGesture
                        }
                        var total = step
                        currentSkip(step)
                        currentHold(total)
                        while (true) {
                            val released = withTimeoutOrNull(300L) { waitForUpOrCancellation(); true }
                            if (released != null) break
                            total += step
                            currentSkip(step)
                            currentHold(total)
                        }
                    } finally {
                        pressed = false
                        currentHold(null)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), null, Modifier.size(24.dp), tint = if (enabled) GreenPrimaryDark else TextMuted.copy(alpha = 0.4f))
    }
}

/** What ends a drill or a hifz listening: it stops the sound too. */
internal fun quranPlaylistStopLabel(title: String): String =
    if (title == com.tunisianprayertimes.quran.hifz.HifzDrillTitle) "إيقاف التدريب" else "إيقاف الاستماع"

/**
 * The one repeat control: a tap opens «ماذا تريد أن تكرّر؟», it never starts or ends a repetition by itself. While one
 * runs it is filled, with what repeats written under it. A drill repeats its passages by itself: the button then ends it.
 */
@Composable
internal fun DockRepeat(
    playback: QuranPlaybackState,
    label: String?,
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp = 48.dp,
    /** The word under the tile; the full player writes it under the button itself. */
    showLabel: Boolean = true,
) {
    val drill = playback.playlist
    val active = label != null && drill == null
    Box(
        Modifier.size(size).clip(RoundedCornerShape(14.dp))
            .clickable(
                role = Role.Button,
                onClickLabel = if (drill != null) null else "اختيار ما يتكرّر",
                onClick = onClick,
            )
            .semantics {
                contentDescription = when {
                    drill != null -> quranPlaylistStopLabel(drill.title)
                    label != null -> "التكرار: $label"
                    else -> "كرّر"
                }
            }
            .testTag("quran_dock_repeat"),
        contentAlignment = Alignment.Center,
    ) {
        if (drill != null) {
            Icon(painterResource(R.drawable.ic_adhkar_close), null, Modifier.size(22.dp), tint = GreenPrimaryDark)
            return@Box
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(width = 40.dp, height = if (active && showLabel) 30.dp else 38.dp).clip(RoundedCornerShape(11.dp))
                    .background(if (active) GreenPrimary else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_quran_repeat), null, Modifier.size(22.dp), tint = if (active) Color.White else TextMuted)
            }
            if (showLabel && active && label != null) Text(label, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimary, maxLines = 1)
        }
    }
}

/**
 * The thin line across the top of the dock: the surah's progress, or the repeated passage's. A
 * horizontal drag moves through it verse by verse; an upward swipe opens the expanded player.
 */
@Composable
private fun DockStrip(
    playback: QuranPlaybackState,
    track: QuranSurahRecording?,
    enabled: Boolean,
    downloadProgress: Float?,
    onScrub: (QuranScrub?) -> Unit,
    onScrubEnd: (QuranScrub) -> Unit,
    onExpand: () -> Unit,
) {
    val window = playback.repeatWindow
    val start = window?.startMs ?: 0L
    val end = window?.endMs ?: playback.durationMs
    val length = (end - start).coerceAtLeast(1L)
    val fraction = downloadProgress ?: ((playback.positionMs - start).toFloat() / length).coerceIn(0f, 1f)
    var scrub by remember { mutableStateOf<QuranScrub?>(null) }
    val haptics = LocalHapticFeedback.current
    val currentScrub by rememberUpdatedState(onScrub)
    val currentEnd by rememberUpdatedState(onScrubEnd)
    val currentExpand by rememberUpdatedState(onExpand)
    val currentTrack by rememberUpdatedState(track)
    val currentStart by rememberUpdatedState(start)
    val currentLength by rememberUpdatedState(length)
    fun at(x: Float, width: Int): QuranScrub? {
        val recording = currentTrack ?: return null
        // Right to left: the start of the surah is at the right edge.
        val f = (1f - x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
        val position = (currentStart + (f * currentLength).toLong()).coerceAtMost(currentStart + currentLength - 1L)
        val ayah = recording.ayahAt(position) ?: recording.timings.lastOrNull { it.startMs <= position }?.ayah ?: recording.timings.first().ayah
        val verseStart = recording.startOf(ayah) ?: position
        return QuranScrub(ayah, verseStart, f)
    }
    val shown = scrub?.fraction ?: fraction
    Box(
        Modifier.fillMaxWidth().height(StripHeight)
            .semantics {
                contentDescription = "شريط الآيات"
                if (enabled && track != null) {
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f, steps = 0)
                    setProgress { target ->
                        val recording = currentTrack ?: return@setProgress false
                        // TalkBack's forward and back move a few percent: that is a verse step, not a snap
                        // back to the start of the verse already under way.
                        val step = kotlin.math.abs(target - fraction) <= 0.06f
                        val next = if (step) quranVerseStep(recording, playback.positionMs, target > fraction, start, end, playback.gapAfter, quranPauseBackTo(playback)) else at((1f - target) * 1000f, 1000)
                        next?.let { currentEnd(it) }
                        true
                    }
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var dx = 0f
                    var dy = 0f
                    var dragging = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            val last = scrub
                            scrub = null
                            currentScrub(null)
                            if (dragging && last != null) currentEnd(last)
                            break
                        }
                        val delta = change.positionChange()
                        dx += delta.x
                        dy += delta.y
                        if (!dragging) {
                            if (dy < -viewConfiguration.touchSlop && kotlin.math.abs(dy) > kotlin.math.abs(dx)) {
                                change.consume()
                                currentExpand()
                                break
                            }
                            if (kotlin.math.abs(dx) > viewConfiguration.touchSlop) dragging = true
                        }
                        if (dragging) {
                            change.consume()
                            if (kotlin.math.abs(dy) > 48.dp.toPx()) {
                                scrub = null
                                currentScrub(null)
                                break
                            }
                            val next = at(change.position.x, size.width)
                            if (next != null && next.ayah != scrub?.ayah) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            scrub = next
                            currentScrub(next)
                        }
                    }
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val active = scrub != null
        if (enabled || downloadProgress != null) Canvas(Modifier.fillMaxWidth().height(if (active) 8.dp else 4.dp).padding(horizontal = 12.dp)) {
            val h = size.height
            val radius = androidx.compose.ui.geometry.CornerRadius(h / 2, h / 2)
            drawRoundRect(if (window != null) GoldLight else GreenPrimary.copy(alpha = 0.18f), cornerRadius = radius)
            // The canvas is laid out right to left already; progress grows from the start edge (right).
            val w = size.width * shown
            drawRoundRect(GreenPrimary, topLeft = Offset(size.width - w, 0f), size = Size(w, h), cornerRadius = radius)
            if (active) drawCircle(GreenPrimary, radius = 8.dp.toPx(), center = Offset(size.width - w, h / 2))
        }
    }
}

/**
 * The next verse start, or the start of this verse (the one before within its first 1.5 s), inside
 * [start, end): what TalkBack's forward and back do on a recitation slider. During the pause after a
 * verse, [pausedAfter] is the verse just recited.
 */
internal fun quranVerseStep(
    recording: QuranSurahRecording, position: Long, forward: Boolean, start: Long, end: Long, pausedAfter: Int? = null,
    /** Where back goes during that pause: the verse, or a drill passage's first verse; see [quranPauseBackTo]. */
    pauseBackTo: Int? = pausedAfter,
): QuranScrub? {
    val timings = recording.timings.filter { it.endMs > start && it.startMs < end }
    val target = if (forward) {
        pausedAfter?.let { after -> timings.firstOrNull { it.ayah > after } } ?: timings.firstOrNull { it.startMs > position }
    }
    else {
        // During the pause after a verse, back hears that verse again, however short it is; after a drill's passage, all
        // of it.
        pauseBackTo?.takeIf { pausedAfter != null }?.let { back -> timings.firstOrNull { it.ayah == back } } ?: run {
            val current = timings.lastOrNull { it.startMs <= position }
            if (current != null && position - current.startMs > 1_500L) current else timings.lastOrNull { it.startMs < (current?.startMs ?: position) }
        }
    } ?: return null
    val at = target.startMs.coerceAtLeast(start)
    return QuranScrub(target.ayah, at, ((at - start).toFloat() / (end - start).coerceAtLeast(1L)).coerceIn(0f, 1f))
}

/**
 * The verse a back step during a pause goes to: in a drill, the first verse of the passage the pause follows, which is
 * said joined; otherwise the verse itself.
 */
internal fun quranPauseBackTo(playback: QuranPlaybackState): Int? = playback.gapAfter?.let { after ->
    playback.repeat?.takeIf { playback.playlist != null && it.from.surah == it.to.surah && it.from.surah == playback.surah && it.to.ayah == after }
        ?.from?.ayah ?: after
}

/** «1:05:20» or «4:36», always with the digits 0-9. */
internal fun quranClock(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0L) / 1000L
    return if (seconds >= 3600L) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600L, seconds / 60L % 60L, seconds % 60L)
    else String.format(Locale.ROOT, "%d:%02d", seconds / 60L, seconds % 60L)
}

/** Keeps numbers, signs and slashes left to right inside Arabic text. */
internal fun ltr(value: String): String = "⁦$value⁩"

/** «−0:15» for a held step back, «+0:10» forward. */
internal fun quranSignedClock(deltaMs: Long): String = ltr((if (deltaMs < 0) "−" else "+") + quranClock(kotlin.math.abs(deltaMs)))

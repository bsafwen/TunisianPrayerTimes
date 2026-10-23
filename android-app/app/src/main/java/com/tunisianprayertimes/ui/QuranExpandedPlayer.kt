package com.tunisianprayertimes.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.R
import com.tunisianprayertimes.quran.QuranPlayAction
import com.tunisianprayertimes.quran.QuranVerseReference
import com.tunisianprayertimes.quran.audio.QuranListening
import com.tunisianprayertimes.quran.audio.QuranPlaybackState
import com.tunisianprayertimes.quran.audio.QuranSleep
import com.tunisianprayertimes.quran.audio.QuranSpeeds
import com.tunisianprayertimes.quran.audio.QuranSurahRecording
import com.tunisianprayertimes.quran.quranVerseLabel
import com.tunisianprayertimes.ui.theme.*
import java.util.Locale
import kotlin.math.abs

/** What the expanded player asks the reader to do. */
internal interface QuranPlayerActions {
    fun play()
    fun previous()
    fun next()
    fun skipBy(deltaMs: Long)
    fun hold(deltaMs: Long?)
    fun seekVerse(ayah: Int)
    fun stop()
    /** «ماذا تريد أن تكرّر؟»; during a drill, ends the drill. */
    fun repeat()
    fun collapse()
    fun reciter()
    fun listening(change: (QuranListening) -> QuranListening)
    fun sleep(kind: QuranSleep.Kind?, minutes: Int = 0)
    /** To that second, inside a passage being repeated. */
    fun seekTo(positionMs: Long)
}

/** How long the line lasts while held: a minute, so ten seconds are a sixth of it under the finger. */
private const val QuranFineSeekMs = 60_000L

/** The line held still: where the finger came down ([anchorMs]), the minute shown, and where it would land. */
private data class QuranFineSeek(val anchorMs: Long, val startMs: Long, val endMs: Long, val targetMs: Long)

/**
 * The fine controls, over the bottom of the page without hiding it: the surah's slider landing on verse starts, ±5 s,
 * the repeat button, the reciter, the speed and when listening stops. Everything about repetition is behind the repeat
 * button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuranExpandedPlayer(
    playback: QuranPlaybackState,
    listening: QuranListening,
    track: QuranSurahRecording?,
    surahName: String,
    verseCount: Int?,
    pageLabel: String,
    reciterShortName: String,
    playAction: QuranPlayAction,
    actions: QuranPlayerActions,
    /** The repetition under way, as the dock says it; null when nothing repeats. */
    repeatLine: String?,
    /** What repeats, in a word under the repeat button; null when nothing does. */
    repeatLabel: String?,
    /** Chosen verses repeat: their part of the surah is drawn on the line. */
    showPassage: Boolean,
    modifier: Modifier = Modifier,
    /** Says once that holding the line moves by seconds; [onFineSeekHintSeen] when it is closed or first used. */
    fineSeekHint: Boolean = false,
    onFineSeekHintSeen: () -> Unit = {},
) {
    val playback = rememberLiveClock(playback)
    var dragging by remember { mutableStateOf<Float?>(null) }
    /** Held still, the line is the minute around the finger: what it shows and where the finger would land. */
    var fine by remember { mutableStateOf<QuranFineSeek?>(null) }
    // The gesture outlives recompositions: it reads the playback, the recording and the callbacks as they are now.
    val currentPlayback by rememberUpdatedState(playback)
    val currentTrack by rememberUpdatedState(track)
    val currentActions by rememberUpdatedState(actions)
    val currentHintSeen by rememberUpdatedState(onFineSeekHintSeen)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val haptics = LocalHapticFeedback.current
    fun ayahAt(position: Long): Int? = currentTrack?.let { t -> t.ayahAt(position) ?: t.timings.lastOrNull { it.startMs <= position }?.ayah }
    /** The surah's line picks verses: [f] of the way lands on the start of the verse there. */
    fun seekVerseAt(f: Float) {
        val recording = currentTrack ?: return
        val position = (f * currentPlayback.durationMs.coerceAtLeast(1L)).toLong()
        currentActions.seekVerse(ayahAt(position) ?: recording.timings.first().ayah)
    }
    val duration = playback.durationMs.coerceAtLeast(1L)
    val fraction = dragging ?: (playback.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    val sliderEnabled = playback.surah != null && playback.durationMs > 0L && !playback.loading
    val sliderColors = SliderDefaults.colors(thumbColor = GreenPrimary, activeTrackColor = GreenPrimary, inactiveTrackColor = Color(0xFFC8DCD3))
    // The minute around the finger is gold: the same line, at another scale.
    val lineColors = if (fine != null) SliderDefaults.colors(thumbColor = GreenPrimaryDark, activeTrackColor = Gold, inactiveTrackColor = GoldLight)
        else sliderColors
    val sliderInteractionSource = remember { MutableInteractionSource() }
    val target = dragging?.let { f ->
        track?.let { t ->
            val position = (f * duration).toLong()
            t.ayahAt(position) ?: t.timings.lastOrNull { it.startMs <= position }?.ayah ?: t.timings.first().ayah
        }
    }
    Surface(
        modifier.fillMaxWidth().shadow(12.dp, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).testTag("quran_expanded_player"),
        color = BgCream,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp)) {
            Box(
                Modifier.fillMaxWidth().height(20.dp).pointerInput(Unit) {
                    var total = 0f
                    detectVerticalDragGestures(onDragStart = { total = 0f }, onDragEnd = { if (total > 40.dp.toPx()) actions.collapse() }) { _, amount -> total += amount }
                },
                contentAlignment = Alignment.Center,
            ) { Box(Modifier.size(32.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFFC8DCD3))) }
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val verse = playback.ayah ?: playback.gapAfter
                    Text(
                        if (verse != null && verse > 0) quranVerseLabel(surahName, verse) else "سورة $surahName",
                        fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    // The repetition, as the dock says it; otherwise where the verse is.
                    if (repeatLine != null) Text(
                        repeatLine, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    ) else Text(
                        listOfNotNull(
                            if (verse != null && verseCount != null) "الآية ${ltr("$verse")} من ${ltr("$verseCount")}" else null,
                            pageLabel.ifEmpty { null },
                        ).joinToString(" · "),
                        fontSize = 12.sp, color = TextMuted, maxLines = 1,
                    )
                }
                QuranReciterChip(reciterShortName, actions::reciter)
                IconButton(onClick = actions::collapse, modifier = Modifier.testTag("quran_player_collapse")) {
                    Icon(painterResource(R.drawable.ic_expand_more), "تصغير المشغّل", tint = GreenPrimaryDark)
                }
            }
            Box {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { actions.skipBy(-5_000L) }, enabled = playback.surah != null) {
                        Icon(painterResource(R.drawable.ic_quran_back5), "رجوع 5${com.tunisianprayertimes.quran.hifz.HifzReminder.NB}ثوانٍ", tint = GreenPrimaryDark)
                    }
                    Box(Modifier.weight(1f)) {
                        Slider(
                            value = fine?.let { ((it.targetMs - it.startMs).toFloat() / (it.endMs - it.startMs).coerceAtLeast(1L)).coerceIn(0f, 1f) } ?: fraction,
                            onValueChange = { dragging = it },
                            onValueChangeFinished = {
                                val f = dragging
                                dragging = null
                                if (f != null) seekVerseAt(f)
                            },
                            enabled = sliderEnabled,
                            // TalkBack's forward and back step verse by verse; a slider's own 5% steps would snap
                            // back to the start of the verse under way.
                            modifier = Modifier.fillMaxWidth().testTag("quran_player_slider").clearAndSetSemantics {
                                contentDescription = "شريط الآيات"
                                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f, steps = 0)
                                val recording = track
                                if (recording != null && playback.surah != null && playback.durationMs > 0L) setProgress { goal ->
                                    val step = kotlin.math.abs(goal - fraction) <= 0.06f
                                    val ayah = if (step) quranVerseStep(recording, playback.positionMs, goal > fraction, 0L, duration, playback.gapAfter, quranPauseBackTo(playback))?.ayah
                                        else (goal * duration).toLong().let { p -> recording.ayahAt(p) ?: recording.timings.lastOrNull { it.startMs <= p }?.ayah }
                                    if (ayah != null) actions.seekVerse(ayah)
                                    ayah != null
                                }
                            },
                            colors = lineColors,
                            interactionSource = sliderInteractionSource,
                            thumb = {
                                // Resize only the visible marker; Slider retains its 48.dp touch target.
                                SliderDefaults.Thumb(
                                    interactionSource = sliderInteractionSource,
                                    colors = lineColors,
                                    enabled = sliderEnabled,
                                    thumbSize = DpSize(4.dp, 24.dp),
                                )
                            },
                            track = { sliderState ->
                                SliderDefaults.Track(
                                    sliderState = sliderState,
                                    enabled = sliderEnabled,
                                    colors = lineColors,
                                    // Keep the position marker connected to the timeline, including at the start.
                                    thumbTrackGapSize = 0.dp,
                                    trackInsideCornerSize = 0.dp,
                                )
                            },
                        )
                        // The verses being repeated, framed in gold over the line: where the recitation loops. Drawn after the
                        // slider, whose track would cover it, and through for touches.
                        val window = playback.repeatWindow?.takeIf { showPassage && fine == null }
                        if (window != null) Canvas(Modifier.matchParentSize().padding(horizontal = 2.dp)) {
                            val total = playback.durationMs.coerceAtLeast(1L).toFloat()
                            val from = (window.startMs / total).coerceIn(0f, 1f)
                            val to = (window.endMs / total).coerceIn(0f, 1f)
                            val bandHeight = 22.dp.toPx()
                            // From the right in Arabic, as the slider runs.
                            val left = if (rtl) (1f - to) * size.width else from * size.width
                            val right = if (rtl) (1f - from) * size.width else to * size.width
                            val top = Offset(left, (size.height - bandHeight) / 2f)
                            val band = Size((right - left).coerceAtLeast(3.dp.toPx()), bandHeight)
                            drawRoundRect(Gold.copy(alpha = 0.22f), topLeft = top, size = band, cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()))
                            drawRoundRect(Gold, topLeft = top, size = band, cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()), style = Stroke(2.dp.toPx()))
                        }
                        // The finger is read here, over the slider. A tap or a quick drag picks a verse across the surah, as the
                        // slider did. Held still a moment, the line becomes the minute around the finger, gold, and the drag
                        // moves by seconds; lifting the finger plays from that second.
                        if (sliderEnabled) Box(
                            Modifier.matchParentSize().testTag("quran_player_fine_seek").pointerInput(rtl) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    down.consume()
                                    val now = currentPlayback
                                    val total = now.durationMs.coerceAtLeast(1L)
                                    // The line runs between the marker's half-widths at either end, from the right in Arabic.
                                    val inset = 2.dp.toPx()
                                    fun along(x: Float): Float {
                                        val f = ((x - inset) / (size.width - 2 * inset).coerceAtLeast(1f)).coerceIn(0f, 1f)
                                        return if (rtl) 1f - f else f
                                    }
                                    // Lifted first: a tap. Moved past the touch slop first: a drag. Still at the long-press time:
                                    // the minute around the finger (null).
                                    val outcome = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                        var result = 0
                                        while (result == 0) {
                                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                            result = when {
                                                change == null || !change.pressed -> 1
                                                (change.position - down.position).getDistance() > viewConfiguration.touchSlop -> 2
                                                else -> { change.consume(); 0 }
                                            }
                                        }
                                        result
                                    }
                                    try {
                                        when (outcome) {
                                            1 -> seekVerseAt(along(down.position.x))
                                            2 -> {
                                                dragging = along(down.position.x)
                                                while (true) {
                                                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                                    if (!change.pressed) {
                                                        dragging?.let(::seekVerseAt)
                                                        break
                                                    }
                                                    dragging = along(change.position.x)
                                                    change.consume()
                                                }
                                            }
                                            else -> {
                                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                val pressed = along(down.position.x)
                                                // On the marker, the minute is around the second playing now, not the one the
                                                // pixel stands for: a pixel of a long surah is half a minute.
                                                val onMarker = abs(pressed - now.positionMs.toFloat() / total) * size.width <= 24.dp.toPx()
                                                val anchor = if (onMarker) now.positionMs else (pressed * total).toLong()
                                                val span = minOf(QuranFineSeekMs, total)
                                                // Laid so that the second under the finger stays under it.
                                                val start = (anchor - (pressed * span).toLong()).coerceIn(0L, total - span)
                                                fun targetAt(x: Float): Long {
                                                    val at = start + (along(x) * span).toLong()
                                                    return now.repeatWindow?.clamp(at) ?: at.coerceIn(0L, total - 1L)
                                                }
                                                fine = QuranFineSeek(anchor, start, start + span, now.repeatWindow?.clamp(anchor) ?: anchor)
                                                while (true) {
                                                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                                    if (!change.pressed) {
                                                        fine?.let { currentActions.seekTo(it.targetMs) }
                                                        currentHintSeen()
                                                        break
                                                    }
                                                    fine = fine?.copy(targetMs = targetAt(change.position.x))
                                                    change.consume()
                                                }
                                            }
                                        }
                                    } finally {
                                        dragging = null
                                        fine = null
                                    }
                                }
                            },
                        )
                    }
                    IconButton(onClick = { actions.skipBy(5_000L) }, enabled = playback.surah != null) {
                        Icon(painterResource(R.drawable.ic_quran_forward5), "تقديم 5${com.tunisianprayertimes.quran.hifz.HifzReminder.NB}ثوانٍ", tint = GreenPrimaryDark)
                    }
                }
                val fineNow = fine
                val bubble = when {
                    fineNow != null -> "${quranSignedClock(fineNow.targetMs - fineNow.anchorMs)} · " + (ayahAt(fineNow.targetMs)?.let { "الآية $it" } ?: ltr(quranClock(fineNow.targetMs)))
                    dragging != null && target != null -> "الآية $target · ${ltr(quranClock(track?.startOf(target) ?: 0L))}"
                    else -> null
                }
                if (bubble != null) {
                    Text(
                        bubble,
                        Modifier.align(Alignment.TopCenter).offset(y = (-34).dp)
                            .clip(RoundedCornerShape(8.dp)).background(GreenPrimaryDark).padding(horizontal = 10.dp, vertical = 6.dp),
                        color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ltr(quranClock(fine?.targetMs ?: if (dragging != null) (fraction * duration).toLong() else playback.positionMs)),
                    fontSize = 12.sp, color = TextMuted,
                )
                Text(
                    if (fine != null) "يعرض الشريط الآن دقيقة واحدة" else target?.let { "الآية $it" } ?: playback.verseRound.takeIf { it > 0 }?.let {
                        if (listening.verseTimes == 0) "المرة $it" else "المرة $it من ${listening.verseTimes}"
                    } ?: "",
                    Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 12.sp, color = GreenPrimary,
                )
                Text(ltr(quranClock(playback.durationMs)), fontSize = 12.sp, color = TextMuted)
            }
            // Said once: held, the line moves by seconds.
            if (fineSeekHint) Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp).testTag("quran_player_fine_hint"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("اضغط مطوّلًا على شريط الآيات ثم اسحب لتتقدّم أو ترجع بالثواني.", Modifier.weight(1f), fontSize = 12.sp, color = GreenPrimary)
                IconButton(onClick = onFineSeekHintSeen, modifier = Modifier.size(32.dp)) {
                    Icon(painterResource(R.drawable.ic_adhkar_close), "إخفاء الإرشاد", Modifier.size(16.dp), tint = TextMuted)
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top) {
                LabeledControl("إنهاء") {
                    IconButton(onClick = actions::stop, enabled = playback.surah != null, modifier = Modifier.testTag("quran_player_stop")) {
                        Icon(painterResource(R.drawable.ic_stop), "إنهاء الاستماع", tint = GreenPrimaryDark)
                    }
                }
                LabeledControl("الآية السابقة") {
                    DockSkip(R.drawable.ic_quran_verse_prev, "الآية السابقة", playback.surah != null && !playback.loading,
                        actions::previous, -5_000L, actions::skipBy, actions::hold, size = 52.dp)
                }
                DockPlay(playback, playAction, null, actions::play, Modifier.testTag("quran_player_play"), size = 60.dp)
                LabeledControl("الآية التالية") {
                    DockSkip(R.drawable.ic_quran_verse_next, "الآية التالية", playback.surah != null && !playback.loading,
                        actions::next, 5_000L, actions::skipBy, actions::hold, size = 52.dp)
                }
                // The same control as the dock's: its word says what repeats, a tap opens the choices.
                val drill = playback.playlist
                LabeledControl(
                    // The same words TalkBack says: «إيقاف التدريب», or «إيقاف الاستماع» for points listened to.
                    if (drill != null) quranPlaylistStopLabel(drill.title) else repeatLabel ?: "كرّر",
                    color = if (drill == null && repeatLabel != null) GreenPrimary else GreenPrimaryDark,
                    bold = drill == null && repeatLabel != null,
                ) {
                    Box(Modifier.size(48.dp).testTag("quran_player_repeat"), contentAlignment = Alignment.Center) {
                        DockRepeat(playback, repeatLabel, actions::repeat, size = 48.dp, showLabel = false)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val drill = playback.playlist
                val speed = drill?.speed ?: listening.speed
                PlayerTile("السرعة", quranSpeedLabel(speed), enabled = drill == null, Modifier.weight(1f).testTag("quran_player_speed")) { close ->
                    QuranSpeeds.forEach { v ->
                        DropdownMenuItem(
                            text = { Text(quranSpeedLabel(v), fontWeight = if (v == listening.speed) FontWeight.Bold else FontWeight.Normal) },
                            onClick = { close(); actions.listening { it.copy(speed = v) } },
                        )
                    }
                }
                PlayerTile("يتوقّف الاستماع", quranSleepLabel(listening.sleep), enabled = true, Modifier.weight(1.4f).testTag("quran_player_sleep")) { close ->
                    if (listening.sleep != null) DropdownMenuItem(text = { Text("عندما توقفه") }, onClick = { close(); actions.sleep(null) })
                    DropdownMenuItem(text = { Text("بعد هذه الآية") }, onClick = { close(); actions.sleep(QuranSleep.Kind.Verse) })
                    DropdownMenuItem(text = { Text("في آخر الصفحة") }, onClick = { close(); actions.sleep(QuranSleep.Kind.Page) })
                    listOf(15, 30, 60).forEach { m ->
                        DropdownMenuItem(text = { Text("بعد ${quranMinutesLabel(m)}") }, onClick = { close(); actions.sleep(QuranSleep.Kind.Minutes, m) })
                    }
                }
            }
        }
    }
}

@Composable
private fun LabeledControl(label: String, color: Color = GreenPrimaryDark, bold: Boolean = false, content: @Composable () -> Unit) {
    Column(Modifier.widthIn(min = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        content()
        Text(label, fontSize = 12.sp, color = color, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
    }
}

/** A setting always in view, its name over its value; a tap lists the choices. */
@Composable
private fun PlayerTile(
    title: String,
    value: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    items: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Surface(
            onClick = { open = true },
            enabled = enabled,
            shape = RoundedCornerShape(14.dp),
            color = Color.White,
            border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text(title, fontSize = 11.sp, color = TextMuted, maxLines = 1)
                Text("$value ▾", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) GreenPrimaryDark else TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) { items { open = false } }
    }
}

/** «عادية», or the speed as «1.25×». */
internal fun quranSpeedLabel(speed: Float): String = if (speed == 1f) "عادية" else
    ltr((if (speed == speed.toInt().toFloat()) String.format(Locale.ROOT, "%d", speed.toInt()) else String.format(Locale.ROOT, "%s", speed)) + "×")

/** When listening stops: «عندما توقفه», «بعد 12 دقيقة», «بعد هذه الآية», «في آخر الصفحة». */
internal fun quranSleepLabel(sleep: QuranSleep?, nowElapsed: Long = android.os.SystemClock.elapsedRealtime()): String = when (sleep?.kind) {
    null -> "عندما توقفه"
    QuranSleep.Kind.Verse -> "بعد هذه الآية"
    QuranSleep.Kind.Page -> "في آخر الصفحة"
    QuranSleep.Kind.Surah -> "في آخر السورة"
    QuranSleep.Kind.Minutes -> "بعد ${quranMinutesLabel(((sleep.untilElapsedMs - nowElapsed) / 60_000L + 1).coerceAtLeast(1).toInt())}"
}

/** «19 إلى 21», or «19» for one verse, in the surah the title names; across surahs «البقرة · الآية 283 إلى آل عمران · الآية 9». */
internal fun quranRangeShort(from: QuranVerseReference, to: QuranVerseReference, surahName: (Int) -> String): String = when {
    from == to -> "${from.ayah}"
    from.surah == to.surah -> "${from.ayah} إلى ${to.ayah}"
    else -> "${quranVerseLabel(surahName(from.surah), from.ayah)} إلى ${quranVerseLabel(surahName(to.surah), to.ayah)}"
}

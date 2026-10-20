package com.tunisianprayertimes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
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
import com.tunisianprayertimes.quran.audio.QuranGapChoices
import com.tunisianprayertimes.quran.audio.QuranListening
import com.tunisianprayertimes.quran.audio.QuranPlaybackState
import com.tunisianprayertimes.quran.audio.QuranSleep
import com.tunisianprayertimes.quran.audio.QuranSpeeds
import com.tunisianprayertimes.quran.audio.QuranSurahRecording
import com.tunisianprayertimes.quran.audio.QuranVerseTimesChoices
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
    fun repeat()
    fun collapse()
    fun reciter()
    fun pickRangeOnPage()
    fun typedRange()
    fun cancelRange()
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
 * The fine controls, over the bottom of the page without hiding it: the surah's slider landing on
 * verse starts, ±5 s, stop, the reciter and the memorisation chips.
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
    nextSurahName: String?,
    actions: QuranPlayerActions,
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
                        if (verse != null && verse > 0) "سورة $surahName · الآية $verse" else "سورة $surahName",
                        fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(
                            if (verse != null && verseCount != null) "الآية $verse من $verseCount" else null,
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
                                    val ayah = if (step) quranVerseStep(recording, playback.positionMs, goal > fraction, 0L, duration, playback.gapAfter)?.ayah
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
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                LabeledControl("إيقاف") {
                    IconButton(onClick = actions::stop, enabled = playback.surah != null, modifier = Modifier.testTag("quran_player_stop")) {
                        Icon(painterResource(R.drawable.ic_stop), "إيقاف الاستماع", tint = GreenPrimaryDark)
                    }
                }
                LabeledControl("السابقة") {
                    DockSkip(R.drawable.ic_quran_verse_prev, "الآية السابقة", playback.surah != null && !playback.loading,
                        actions::previous, -5_000L, actions::skipBy, actions::hold, size = 52.dp)
                }
                DockPlay(playback, playAction, null, actions::play, Modifier.testTag("quran_player_play"), size = 60.dp)
                LabeledControl("التالية") {
                    DockSkip(R.drawable.ic_quran_verse_next, "الآية التالية", playback.surah != null && !playback.loading,
                        actions::next, 5_000L, actions::skipBy, actions::hold, size = 52.dp)
                }
                LabeledControl(if (playback.playlist != null) "إنهاء" else "تكرار") {
                    IconButton(onClick = actions::repeat, enabled = playback.surah != null) {
                        val description = playback.playlist?.let { quranPlaylistStopLabel(it.title) } ?: if (playback.repeat != null) "إلغاء التكرار" else "تكرار الآية"
                        Icon(painterResource(R.drawable.ic_quran_repeat), description, tint = if (playback.repeat != null) GreenPrimary else TextMuted)
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val range = playback.repeat
                val drill = playback.playlist
                ChipMenu(
                    when {
                        drill != null -> "${drill.title}: ${playback.playlistIndex + 1} من ${drill.items.size}"
                        range == null -> "تكرار مقطع"
                        else -> "المقطع: ${quranRangeShort(range.from, range.to)} " + (range.times?.let { ltr("×$it") } ?: "∞")
                    },
                    active = range != null,
                ) { close ->
                    DropdownMenuItem(text = { Text("اختيار مقطع على الصفحة") }, onClick = { close(); actions.pickRangeOnPage() })
                    DropdownMenuItem(text = { Text("بالأرقام…") }, onClick = { close(); actions.typedRange() })
                    if (range != null) DropdownMenuItem(text = { Text(if (drill != null) quranPlaylistStopLabel(drill.title) else "إلغاء التكرار") }, onClick = { close(); actions.cancelRange() })
                }
                val drilling = drill != null
                ChipMenu("كل آية ${verseTimesLabel(if (drilling) 1 else listening.verseTimes, short = true)}", active = !drilling && listening.verseTimes != 1, enabled = !drilling) { close ->
                    QuranVerseTimesChoices.forEach { n ->
                        DropdownMenuItem(
                            text = { Text(verseTimesLabel(n, short = true), fontWeight = if (n == listening.verseTimes) FontWeight.Bold else FontWeight.Normal) },
                            onClick = { close(); actions.listening { it.copy(verseTimes = n) } },
                        )
                    }
                }
                val gap = drill?.gapFactor ?: listening.gapFactor
                ChipMenu(if (gap > 0f) "مهلة: ${gapLabel(gap, short = true)}" else gapLabel(gap), active = gap > 0f, enabled = !drilling) { close ->
                    QuranGapChoices.forEach { g ->
                        DropdownMenuItem(
                            text = { Text(gapLabel(g), fontWeight = if (g == listening.gapFactor) FontWeight.Bold else FontWeight.Normal) },
                            onClick = { close(); actions.listening { it.copy(gapFactor = g) } },
                        )
                    }
                    Text("وقت صامت لتردّد الآية بعد القارئ", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontSize = 12.sp, color = TextMuted)
                }
                val speed = drill?.speed ?: listening.speed
                ChipMenu("السرعة ${speedLabel(speed)}", active = speed != 1f, enabled = !drilling) { close ->
                    QuranSpeeds.forEach { v ->
                        DropdownMenuItem(
                            text = { Text(speedLabel(v), fontWeight = if (v == listening.speed) FontWeight.Bold else FontWeight.Normal) },
                            onClick = { close(); actions.listening { it.copy(speed = v) } },
                        )
                    }
                }
                ChipMenu(sleepLabel(listening.sleep), active = listening.sleep != null) { close ->
                    if (listening.sleep != null) DropdownMenuItem(text = { Text("إلغاء المؤقّت") }, onClick = { close(); actions.sleep(null) })
                    DropdownMenuItem(text = { Text("نهاية الآية") }, onClick = { close(); actions.sleep(QuranSleep.Kind.Verse) })
                    DropdownMenuItem(text = { Text("نهاية الصفحة") }, onClick = { close(); actions.sleep(QuranSleep.Kind.Page) })
                    DropdownMenuItem(text = { Text("نهاية السورة") }, onClick = { close(); actions.sleep(QuranSleep.Kind.Surah) })
                    listOf(15, 30, 60).forEach { m ->
                        DropdownMenuItem(text = { Text("بعد ${quranMinutesLabel(m)}") }, onClick = { close(); actions.sleep(QuranSleep.Kind.Minutes, m) })
                    }
                }
                ChipMenu("بعد السورة: ${if (listening.continueAfterSurah) "تابع" else "توقّف"}", active = !listening.continueAfterSurah) { close ->
                    DropdownMenuItem(
                        text = { Text(nextSurahName?.let { "تابع إلى سورة $it" } ?: "تابع") },
                        onClick = { close(); actions.listening { it.copy(continueAfterSurah = true) } },
                    )
                    DropdownMenuItem(text = { Text("توقّف في نهايتها") }, onClick = { close(); actions.listening { it.copy(continueAfterSurah = false) } })
                }
            }
        }
    }
}

@Composable
private fun LabeledControl(label: String, content: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        content()
        Text(label, fontSize = 12.sp, color = GreenPrimaryDark)
    }
}

@Composable
private fun ChipMenu(label: String, active: Boolean, enabled: Boolean = true, items: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = active,
            enabled = enabled,
            onClick = { open = true },
            label = { Text("$label ▾", fontSize = 12.sp, maxLines = 1) },
            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = AdhkarSoftGreen, selectedLabelColor = GreenPrimaryDark),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) { items { open = false } }
    }
}

internal fun verseTimesLabel(times: Int, short: Boolean = false): String = when {
    times == 0 -> "بلا توقّف"
    short -> ltr("×$times")
    else -> "$times"
}

internal fun gapLabel(factor: Float, short: Boolean = false): String = when (factor) {
    0f -> "بلا مهلة"
    0.5f -> if (short) "نصف الآية" else "نصف مدة الآية"
    1f -> if (short) "مدة الآية" else "مدة الآية"
    2f -> if (short) "ضعف الآية" else "ضعف مدة الآية"
    else -> ltr("×$factor")
}

internal fun speedLabel(speed: Float): String =
    ltr((if (speed == speed.toInt().toFloat()) String.format(Locale.ROOT, "%d", speed.toInt()) else String.format(Locale.ROOT, "%s", speed)) + "×")

internal fun sleepLabel(sleep: QuranSleep?, nowElapsed: Long = android.os.SystemClock.elapsedRealtime()): String = when (sleep?.kind) {
    null -> "المؤقّت"
    QuranSleep.Kind.Verse -> "المؤقّت: نهاية الآية"
    QuranSleep.Kind.Page -> "المؤقّت: نهاية الصفحة"
    QuranSleep.Kind.Surah -> "المؤقّت: نهاية السورة"
    QuranSleep.Kind.Minutes -> "المؤقّت: ${((sleep.untilElapsedMs - nowElapsed) / 60_000L + 1).coerceAtLeast(1)}${com.tunisianprayertimes.quran.hifz.HifzReminder.NB}د"
}

/** «19 إلى 21», or «19» for one verse; across chapters «البقرة 283 إلى آل عمران 9» is built by the caller. */
internal fun quranRangeShort(from: QuranVerseReference, to: QuranVerseReference): String = when {
    from == to -> "${from.ayah}"
    from.surah == to.surah -> "${from.ayah} إلى ${to.ayah}"
    else -> "${from.surah}:${from.ayah} إلى ${to.surah}:${to.ayah}"
}

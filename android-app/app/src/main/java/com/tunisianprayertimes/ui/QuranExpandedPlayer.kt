package com.tunisianprayertimes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.input.pointer.pointerInput
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
}

/**
 * The fine controls, over the bottom of the page without hiding it: the surah's slider landing on
 * verse starts, ±5 s, stop, the reciter and the memorisation chips.
 */
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
) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = playback.durationMs.coerceAtLeast(1L)
    val fraction = dragging ?: (playback.positionMs.toFloat() / duration).coerceIn(0f, 1f)
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
                        Icon(painterResource(R.drawable.ic_quran_back5), "رجوع 5 ثوانٍ", tint = GreenPrimaryDark)
                    }
                    Slider(
                        value = fraction,
                        onValueChange = { dragging = it },
                        onValueChangeFinished = {
                            val f = dragging
                            dragging = null
                            val recording = track
                            if (f != null && recording != null) {
                                val position = (f * duration).toLong()
                                val ayah = recording.ayahAt(position) ?: recording.timings.lastOrNull { it.startMs <= position }?.ayah ?: recording.timings.first().ayah
                                actions.seekVerse(ayah)
                            }
                        },
                        enabled = playback.surah != null && playback.durationMs > 0L && !playback.loading,
                        // TalkBack's forward and back step verse by verse; a slider's own 5% steps would snap
                        // back to the start of the verse under way.
                        modifier = Modifier.weight(1f).testTag("quran_player_slider").clearAndSetSemantics {
                            contentDescription = "موضع التلاوة في السورة"
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
                        colors = SliderDefaults.colors(thumbColor = GreenPrimary, activeTrackColor = GreenPrimary, inactiveTrackColor = Color(0xFFC8DCD3)),
                    )
                    IconButton(onClick = { actions.skipBy(5_000L) }, enabled = playback.surah != null) {
                        Icon(painterResource(R.drawable.ic_quran_forward5), "تقديم 5 ثوانٍ", tint = GreenPrimaryDark)
                    }
                }
                if (dragging != null && target != null) {
                    val position = track?.startOf(target) ?: 0L
                    Text(
                        "الآية $target · ${ltr(quranClock(position))}",
                        Modifier.align(Alignment.TopCenter).offset(y = (-34).dp)
                            .clip(RoundedCornerShape(8.dp)).background(GreenPrimaryDark).padding(horizontal = 10.dp, vertical = 6.dp),
                        color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(ltr(quranClock(if (dragging != null) (fraction * duration).toLong() else playback.positionMs)), fontSize = 12.sp, color = TextMuted)
                Text(
                    target?.let { "الآية $it" } ?: playback.verseRound.takeIf { it > 0 }?.let {
                        if (listening.verseTimes == 0) "المرة $it" else "المرة $it من ${listening.verseTimes}"
                    } ?: "",
                    Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 12.sp, color = GreenPrimary,
                )
                Text(ltr(quranClock(playback.durationMs)), fontSize = 12.sp, color = TextMuted)
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                LabeledControl("إيقاف") {
                    IconButton(onClick = actions::stop, enabled = playback.surah != null, modifier = Modifier.testTag("quran_player_stop")) {
                        Icon(painterResource(R.drawable.ic_stop), "إيقاف التلاوة", tint = GreenPrimaryDark)
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
                        range == null -> "المقطع: لا"
                        else -> "المقطع: ${quranRangeShort(range.from, range.to)} " + (range.times?.let { ltr("×$it") } ?: "∞")
                    },
                    active = range != null,
                ) { close ->
                    DropdownMenuItem(text = { Text("اختر مقطعا على الصفحة") }, onClick = { close(); actions.pickRangeOnPage() })
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
                ChipMenu("مهلة: ${gapLabel(gap, short = true)}", active = gap > 0f, enabled = !drilling) { close ->
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
                        DropdownMenuItem(text = { Text("بعد $m دقيقة") }, onClick = { close(); actions.sleep(QuranSleep.Kind.Minutes, m) })
                    }
                }
                ChipMenu("بعد السورة: ${if (listening.continueAfterSurah) "تابِع" else "توقّف"}", active = !listening.continueAfterSurah) { close ->
                    DropdownMenuItem(
                        text = { Text(nextSurahName?.let { "تابِع إلى سورة $it" } ?: "تابِع") },
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
    times == 0 -> "بلا توقف"
    short -> ltr("×$times")
    else -> "$times"
}

internal fun gapLabel(factor: Float, short: Boolean = false): String = when (factor) {
    0f -> "بدون"
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
    QuranSleep.Kind.Minutes -> "المؤقّت: ${((sleep.untilElapsedMs - nowElapsed) / 60_000L + 1).coerceAtLeast(1)} د"
}

/** «19 إلى 21», or «19» for one verse; across chapters «البقرة 283 إلى آل عمران 9» is built by the caller. */
internal fun quranRangeShort(from: QuranVerseReference, to: QuranVerseReference): String = when {
    from == to -> "${from.ayah}"
    from.surah == to.surah -> "${from.ayah} إلى ${to.ayah}"
    else -> "${from.surah}:${from.ayah} إلى ${to.surah}:${to.ayah}"
}

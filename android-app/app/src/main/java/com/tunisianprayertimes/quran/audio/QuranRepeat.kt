package com.tunisianprayertimes.quran.audio

import com.tunisianprayertimes.quran.QuranVerseReference

/**
 * Numbered verses recited again and again, from [from] to [to] inclusive and possibly across chapters.
 * A null [times] repeats until the listener stops it.
 */
data class QuranRepeatRange(
    val from: QuranVerseReference,
    val to: QuranVerseReference,
    val times: Int? = null,
) {
    /** Verse numbers above a chapter's count are rejected with the recording's own boundaries. */
    val isValid: Boolean
        get() = from.surah in 1..114 && to.surah in 1..114 && from.ayah >= 1 && to.ayah >= 1 && from <= to &&
            (times == null || times in 1..MAX_TIMES)

    operator fun contains(verse: QuranVerseReference): Boolean = verse >= from && verse <= to

    companion object {
        const val MAX_TIMES = 999
    }
}

/** The repeated part of one chapter's recording, as [startMs, endMs). */
data class QuranRepeatWindow(val startMs: Long, val endMs: Long) {
    /** The last position a seek may target without leaving the range. */
    fun clamp(positionMs: Long): Long = positionMs.coerceIn(startMs, (endMs - 1L).coerceAtLeast(startMs))
}

/**
 * Null when this chapter lies outside [range] or the recording has no such verse. A later chapter
 * of the range keeps its opening, exactly as continuous recitation plays it.
 */
internal fun QuranSurahRecording.repeatWindow(range: QuranRepeatRange): QuranRepeatWindow? {
    if (number < range.from.surah || number > range.to.surah) return null
    val start = if (number == range.from.surah) startOf(range.from.ayah) ?: return null else 0L
    val end = if (number == range.to.surah) endOf(range.to.ayah) ?: return null else durationMs
    return QuranRepeatWindow(start, end).takeIf { it.endMs > it.startMs }
}

/** What follows one complete pass over the range. */
internal sealed interface QuranRepeatStep {
    /** Recite the range again; [round] is the one-based pass about to start. */
    data class Again(val round: Int) : QuranRepeatStep

    /** Every requested pass was recited. */
    data object Finished : QuranRepeatStep
}

internal fun QuranRepeatRange.afterPass(round: Int): QuranRepeatStep =
    if (times != null && round >= times) QuranRepeatStep.Finished else QuranRepeatStep.Again(round + 1)

/** Saved beside the resume position, so a restarted service continues the same repetition. */
internal fun QuranRepeatRange.encode(round: Int): String =
    listOf(from.surah, from.ayah, to.surah, to.ayah, times ?: 0, round).joinToString(":")

internal fun decodeQuranRepeat(value: String?): Pair<QuranRepeatRange, Int>? {
    val numbers = value?.split(':')?.map { it.toIntOrNull() ?: return null } ?: return null
    if (numbers.size != 6) return null
    val range = QuranRepeatRange(
        QuranVerseReference(numbers[0], numbers[1]),
        QuranVerseReference(numbers[2], numbers[3]),
        numbers[4].takeIf { it != 0 },
    )
    return if (range.isValid && numbers[5] >= 1) range to numbers[5] else null
}

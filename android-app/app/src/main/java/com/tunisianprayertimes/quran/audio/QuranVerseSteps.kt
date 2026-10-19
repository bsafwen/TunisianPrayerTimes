package com.tunisianprayertimes.quran.audio

import kotlin.math.roundToLong

/** Past this much of a verse, «الآية السابقة» starts the same verse again instead of the one before. */
internal const val QuranRestartVerseAfterMs = 1_500L

/** Where «الآية السابقة» goes from [positionMs] in [track]. */
internal sealed interface QuranPreviousTarget {
    /** The start of [ayah] in the same recording: the verse being recited again, or the one before it. */
    data class Verse(val ayah: Int) : QuranPreviousTarget

    /** The last verse of the chapter before. */
    data object PreviousChapter : QuranPreviousTarget
}

internal fun QuranSurahRecording.previousTarget(
    positionMs: Long,
    /** The verse a silent pause follows: going back hears it again. */
    pausedAfter: Int? = null,
): QuranPreviousTarget {
    pausedAfter?.let { return QuranPreviousTarget.Verse(it) }
    val active = ayahAt(positionMs)
    if (active != null) {
        val start = startOf(active) ?: 0L
        if (positionMs - start > QuranRestartVerseAfterMs) return QuranPreviousTarget.Verse(active)
        return if (active > 1) QuranPreviousTarget.Verse(active - 1) else QuranPreviousTarget.PreviousChapter
    }
    // Between verses or in the opening: the last verse that has begun.
    val begun = timings.lastOrNull { it.startMs < positionMs }?.ayah
    return if (begun != null) QuranPreviousTarget.Verse(begun) else QuranPreviousTarget.PreviousChapter
}

/** What happens when a verse has just been recited to its end. */
internal sealed interface QuranVerseEnd {
    /** Recite the same verse again; [round] is the one-based recitation about to start. */
    data class Again(val round: Int) : QuranVerseEnd

    /** The sleep timer ends the recitation here. */
    data object Sleep : QuranVerseEnd

    /** A silent pause of [durationMs] lets the listener repeat the verse before the recitation goes on. */
    data class Pause(val durationMs: Long) : QuranVerseEnd

    data object Continue : QuranVerseEnd
}

/**
 * [verseTimes] 0 repeats each verse until the listener moves on; [round] is the recitation that just
 * ended (1 for the first). The pause is measured in the verse's own length, at the playback speed.
 */
internal fun quranVerseEnd(
    verseTimes: Int,
    round: Int,
    sleepHere: Boolean,
    gapFactor: Float,
    verseLengthMs: Long,
    speed: Float,
): QuranVerseEnd = when {
    // The timer waits for the verse's last counted recitation; an endless repetition stops at this one.
    sleepHere && (verseTimes == 0 || round >= verseTimes) -> QuranVerseEnd.Sleep
    verseTimes == 0 || round < verseTimes -> QuranVerseEnd.Again(round + 1)
    gapFactor > 0f && verseLengthMs > 0L ->
        QuranVerseEnd.Pause((verseLengthMs * gapFactor / speed.coerceAtLeast(0.25f)).roundToLong().coerceAtLeast(500L))
    else -> QuranVerseEnd.Continue
}

/** Choices offered by the player, in the order shown. */
internal val QuranSpeeds = listOf(0.75f, 1f, 1.25f, 1.5f)
internal val QuranVerseTimesChoices = listOf(1, 2, 3, 5, 7, 10, 0)
internal val QuranGapChoices = listOf(0f, 0.5f, 1f, 2f)

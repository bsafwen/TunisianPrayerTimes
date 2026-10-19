package com.tunisianprayertimes.quran.hifz

import android.content.Context
import com.tunisianprayertimes.quran.QuranVerseReference

/**
 * The verses the reader looked at on purpose, for no question to come right after its answer was seen: [seen], when
 * each verse was last shown, heard or corrected, kept across midnight for [HifzFreshGapMs], the while a fresh point waits
 * after it; [exposed], the verses seen today, whose spaced checks wait for the next day.
 */
internal data class HifzLearning(
    val day: Long,
    val exposed: Set<QuranVerseReference> = emptySet(),
    val seen: Map<QuranVerseReference, Long> = emptyMap(),
) {
    fun onDay(today: Long): HifzLearning = if (today == day) this else HifzLearning(today, seen = seen)

    /** A time seen within a minute stays as it was: the eraser's every move is no new write, and a minute is nothing to the gap. */
    fun expose(verses: Set<QuranVerseReference>, at: Long = System.currentTimeMillis()): HifzLearning = copy(
        exposed = exposed + verses,
        seen = seen.filterValues { it > at - HifzFreshGapMs } + verses.associateWith { verse -> seen[verse]?.takeIf { it > at - 60_000L } ?: at },
    )

    /** Merged with what is stored: the playback service records what it plays for a point while the reader's copy is older. */
    fun write(context: Context): HifzLearning = synchronized(storageLock) {
        val stored = readStored(context, day)
        // A delayed write from yesterday must not erase today's listening.
        val merged = if (stored.day > day) stored else copy(
            exposed = exposed + stored.onDay(day).exposed,
            seen = (stored.seen.keys + seen.keys).associateWith { verse -> maxOf(stored.seen[verse] ?: Long.MIN_VALUE, seen[verse] ?: Long.MIN_VALUE) }
                .filterValues { it > System.currentTimeMillis() - HifzFreshGapMs },
        )
        if (merged != stored) persist(context, merged)
        merged
    }

    companion object {
        private const val PREFERENCES = "hifz_learning"
        private val storageLock = Any()

        fun read(context: Context, today: Long): HifzLearning = synchronized(storageLock) {
            readStored(context, today).onDay(today)
        }

        /** Called by playback started for a point (its listening, its drill, its cue), also with the reader backgrounded. */
        fun recordExposure(context: Context, verse: QuranVerseReference, today: Long = todayEpochDay()): HifzLearning = synchronized(storageLock) {
            val stored = readStored(context, today)
            if (stored.day > today || verse.surah !in 1..114 || verse.ayah !in 1..286) return@synchronized stored
            val updated = stored.onDay(today).expose(setOf(verse))
            if (updated != stored) persist(context, updated)
            updated
        }

        /** All readers/writers use the same lock; SharedPreferences.apply updates its in-memory view atomically. */
        private fun persist(context: Context, value: HifzLearning) {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                .putLong("day", value.day)
                .putStringSet("exposed", value.exposed.map { "${it.surah}:${it.ayah}" }.toSet())
                .putStringSet("seen", value.seen.map { (verse, at) -> "${verse.surah}:${verse.ayah}:$at" }.toSet())
                // Training lists that are no more.
                .remove("pending")
                .remove("practised")
                .apply()
        }

        private fun readStored(context: Context, fallbackDay: Long): HifzLearning {
            val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            return HifzLearning(
                day = prefs.getLong("day", fallbackDay),
                exposed = prefs.getStringSet("exposed", emptySet()).orEmpty().mapNotNull { value ->
                    val pieces = value.split(':')
                    val surah = pieces.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                    val ayah = pieces.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                    if (surah !in 1..114 || ayah !in 1..286) null else QuranVerseReference(surah, ayah)
                }.toSet(),
                seen = prefs.getStringSet("seen", emptySet()).orEmpty().mapNotNull { value ->
                    val pieces = value.split(':')
                    val surah = pieces.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                    val ayah = pieces.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                    val at = pieces.getOrNull(2)?.toLongOrNull() ?: return@mapNotNull null
                    if (surah !in 1..114 || ayah !in 1..286) null else QuranVerseReference(surah, ayah) to at
                }.toMap(),
            )
        }
    }
}

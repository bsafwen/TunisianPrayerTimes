package com.tunisianprayertimes.quran.hifz

import android.content.Context
import com.tunisianprayertimes.quran.QuranVerseReference

/**
 * Practice is separate from spaced checks: leaving a screen must not lose unfinished work. [seen]: when each verse was
 * last shown, heard or corrected, for the fresh points, asked a while after their answer was seen; kept across
 * midnight, and only as long as that while.
 */
internal data class HifzLearning(
    val day: Long,
    val pending: Set<Long> = emptySet(),
    val exposed: Set<QuranVerseReference> = emptySet(),
    val practised: Set<Long> = emptySet(),
    val seen: Map<QuranVerseReference, Long> = emptyMap(),
) {
    fun onDay(today: Long): HifzLearning = if (today == day) this else HifzLearning(today, pending, seen = seen)

    fun pending(id: Long, needed: Boolean): HifzLearning = copy(pending = if (needed) pending + id else pending - id)

    /** A time seen within a minute stays as it was: the eraser's every move is no new write, and a minute is nothing to the gap. */
    fun expose(verses: Set<QuranVerseReference>, at: Long = System.currentTimeMillis()): HifzLearning = copy(
        exposed = exposed + verses,
        seen = seen.filterValues { it > at - HifzFreshGapMs } + verses.associateWith { verse -> seen[verse]?.takeIf { it > at - 60_000L } ?: at },
    )

    fun practised(id: Long, completed: Boolean): HifzLearning = copy(practised = if (completed) practised + id else practised - id)

    fun retain(ids: Set<Long>): HifzLearning = copy(pending = pending.intersect(ids), practised = practised.intersect(ids))

    /** UI owns pending/practised changes; service exposure must survive a UI snapshot taken before playback. */
    fun write(context: Context): HifzLearning = synchronized(storageLock) {
        val stored = readStored(context, day)
        // A delayed write from yesterday must not erase today's background listening or completed practice.
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

        /** Called by actual playback, including with the reader backgrounded or absent. */
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
                .putStringSet("pending", value.pending.map { it.toString() }.toSet())
                .putStringSet("exposed", value.exposed.map { "${it.surah}:${it.ayah}" }.toSet())
                .putStringSet("practised", value.practised.map { it.toString() }.toSet())
                .putStringSet("seen", value.seen.map { (verse, at) -> "${verse.surah}:${verse.ayah}:$at" }.toSet())
                .apply()
        }

        private fun readStored(context: Context, fallbackDay: Long): HifzLearning {
            val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            return HifzLearning(
                day = prefs.getLong("day", fallbackDay),
                pending = prefs.getStringSet("pending", emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet(),
                exposed = prefs.getStringSet("exposed", emptySet()).orEmpty().mapNotNull { value ->
                    val pieces = value.split(':')
                    val surah = pieces.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                    val ayah = pieces.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                    if (surah !in 1..114 || ayah !in 1..286) null else QuranVerseReference(surah, ayah)
                }.toSet(),
                practised = prefs.getStringSet("practised", emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet(),
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

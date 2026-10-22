package com.tunisianprayertimes.quran.audio

import com.tunisianprayertimes.quran.QuranVerseReference
import com.tunisianprayertimes.quran.hifz.HifzDrillTitle
import com.tunisianprayertimes.quran.hifz.HifzListenOneTitle
import com.tunisianprayertimes.quran.hifz.HifzListenTitle
import org.json.JSONArray
import org.json.JSONObject

/**
 * Separate passages recited one after another, the whole list [rounds] times. With a [gapFactor], each passage is followed
 * by one silence to say it again, joined, none between its verses: a hifz drill («47 ثم 115 ثم 47»). Without one it plays
 * straight through: the fixes of «مواضعي صوتا», the cue before a point.
 */
data class QuranPlaylist(
    /** «مواضعي صوتا», «تدريب». */
    val title: String,
    /** Each passage is recited once per round. */
    val items: List<QuranRepeatRange>,
    /** One per item: «البقرة 67 ← 68 ← 69». */
    val labels: List<String>,
    val rounds: Int = 1,
    /** Silence after each passage, as a multiple of its whole length; 0 for none. */
    val gapFactor: Float = 1f,
    /** Null keeps the listener's own speed. */
    val speed: Float? = null,
    /**
     * What the silence after any other passage asks for from memory, and is long enough for: a drill's right wording,
     * said after its look-alike. Null when each silence is for the passage just recited.
     */
    val answer: QuranRepeatRange? = null,
) {
    val isValid: Boolean
        get() = items.isNotEmpty() && items.size == labels.size && rounds in 1..MAX_ROUNDS &&
            items.all { it.isValid } && gapFactor in 0f..4f && (speed == null || speed in 0.5f..2f) && answer?.isValid != false

    fun encode(): String = JSONObject()
        .put("title", title)
        .put("rounds", rounds)
        .put("gap", gapFactor.toDouble())
        .put("speed", speed?.toDouble() ?: 0.0)
        .put("items", JSONArray(items.zip(labels).map { (range, label) ->
            JSONArray(listOf(range.from.surah, range.from.ayah, range.to.surah, range.to.ayah, label))
        }))
        .apply { answer?.let { put("answer", JSONArray(listOf(it.from.surah, it.from.ayah, it.to.surah, it.to.ayah))) } }
        .toString()

    companion object {
        const val MAX_ROUNDS = 99

        fun decode(value: String?): QuranPlaylist? = runCatching {
            val source = JSONObject(value ?: return null)
            val rows = source.getJSONArray("items")
            val items = List(rows.length()) { index ->
                val row = rows.getJSONArray(index)
                QuranRepeatRange(QuranVerseReference(row.getInt(0), row.getInt(1)), QuranVerseReference(row.getInt(2), row.getInt(3)), 1) to row.getString(4)
            }
            QuranPlaylist(
                title = source.getString("title"),
                items = items.map { it.first },
                labels = items.map { it.second },
                rounds = source.getInt("rounds"),
                gapFactor = source.getDouble("gap").toFloat(),
                speed = source.getDouble("speed").toFloat().takeIf { it > 0f },
                answer = source.optJSONArray("answer")?.let { a ->
                    QuranRepeatRange(QuranVerseReference(a.getInt(0), a.getInt(1)), QuranVerseReference(a.getInt(2), a.getInt(3)), 1)
                },
            )
        }.getOrNull()?.takeIf { it.isValid }
    }
}

/** «انتهى التدريب», «انتهى الاستماع إلى المواضع»: said when a playlist titled [title] has been recited to its end. */
internal fun quranPlaylistEnded(title: String): String = when (title) {
    HifzDrillTitle -> "انتهى التدريب"
    HifzListenTitle -> "انتهى الاستماع إلى المواضع"
    HifzListenOneTitle -> "انتهى الاستماع إلى الموضع"
    else -> "انتهى الاستماع"
}

/** Where a playlist goes once an item has been recited. */
internal sealed interface QuranPlaylistStep {
    data class Item(val index: Int, val round: Int) : QuranPlaylistStep
    data object Finished : QuranPlaylistStep
}

internal fun QuranPlaylist.after(index: Int, round: Int): QuranPlaylistStep = when {
    index + 1 < items.size -> QuranPlaylistStep.Item(index + 1, round)
    round < rounds -> QuranPlaylistStep.Item(0, round + 1)
    else -> QuranPlaylistStep.Finished
}

/** The item before, or the start of the first one again. */
internal fun QuranPlaylist.before(index: Int, round: Int): QuranPlaylistStep.Item = when {
    index > 0 -> QuranPlaylistStep.Item(index - 1, round)
    round > 1 -> QuranPlaylistStep.Item(items.lastIndex, round - 1)
    else -> QuranPlaylistStep.Item(0, 1)
}

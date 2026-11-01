package com.tunisianprayertimes.quran.audio

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

internal data class QuranAutoPlace(
    val selection: QuranAutoBrowse.Selection,
    val positionMs: Long = 0,
    val ayah: Int? = null,
    val savedAtMs: Long = 0,
)

/** Car preferences never change the reader's practice settings or Hifz records. */
internal data class QuranAutoSettings(val repeatSurah: Boolean = false, val continueQueue: Boolean = true) {
    fun listening() = QuranListening(surahTimes = if (repeatSurah) 0 else 1, continueAfterSurah = continueQueue)
}

internal enum class QuranAutoSource(val id: String) {
    Catalogue("catalogue"), Downloaded("downloaded"), Favorites("favorites"), Recent("recent");
}

internal data class QuranAutoQueue(
    val items: List<QuranAutoBrowse.Selection>,
    val index: Int,
    val source: QuranAutoSource,
) {
    init { require(items.isNotEmpty() && index in items.indices && items.size <= 342) }
    val current get() = items[index]
    fun next(): QuranAutoQueue? = if (index < items.lastIndex) copy(index = index + 1) else null
    fun previous(): QuranAutoQueue? = if (index > 0) copy(index = index - 1) else null
}

/** Only verified/prepared ordinary listening is admitted here; requested or missing audio is never a resume point. */
internal object QuranAutoStore {
    private const val NAME = "quran_auto"
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()
    private fun prefs(context: Context) = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun settings(context: Context) = prefs(context).let {
        QuranAutoSettings(it.getBoolean("repeat_surah", false), it.getBoolean("continue_queue", true))
    }

    fun setSettings(context: Context, settings: QuranAutoSettings) {
        prefs(context).edit().putBoolean("repeat_surah", settings.repeatSurah)
            .putBoolean("continue_queue", settings.continueQueue).apply()
        changed()
    }

    fun recent(context: Context): List<QuranAutoPlace> = runCatching {
        val rows = JSONArray(prefs(context).getString("recent", "[]"))
        (0 until minOf(rows.length(), 8)).mapNotNull { i ->
            val row = rows.getJSONObject(i)
            selection(row)?.let { QuranAutoPlace(it, row.optLong("position").coerceAtLeast(0),
                row.optInt("ayah").takeIf { n -> n > 0 }, row.optLong("savedAt")) }
        }.distinctBy { it.selection }
    }.getOrDefault(emptyList())

    fun lastPlace(context: Context): QuranAutoPlace? = recent(context).firstOrNull() ?: run {
        // Adopt an existing phone listening place without rewriting its preferences.
        val saved = context.getSharedPreferences(QuranPlaybackService.PREFERENCES, Context.MODE_PRIVATE)
        val surah = saved.getInt(QuranPlaybackService.EXTRA_SURAH, 0).takeIf { it in 1..114 } ?: return null
        val reciter = saved.getString(QuranPlaybackService.EXTRA_RECITER, null)
            ?.takeIf { id -> QuranAudioController.reciters.any { it.id == id } } ?: QuranAudioController.DEFAULT_RECITER_ID
        QuranAutoPlace(QuranAutoBrowse.Selection(reciter, surah),
            saved.getLong(QuranPlaybackService.EXTRA_POSITION, 0).coerceAtLeast(0),
            savedAtMs = saved.getLong(QuranPlaybackService.KEY_SAVED_AT, 0))
    }

    @Synchronized fun remember(context: Context, state: QuranPlaybackState) {
        val surah = state.surah ?: return
        if (state.notDownloaded || state.loading || state.error != null) return
        val place = QuranAutoPlace(QuranAutoBrowse.Selection(state.reciterId, surah), state.positionMs,
            state.ayah, System.currentTimeMillis())
        val before = recent(context)
        val next = (listOf(place) + before.filterNot { it.selection == place.selection }).take(8)
        prefs(context).edit().putString("recent", JSONArray(next.map { row ->
            encode(row.selection).put("position", row.positionMs).put("ayah", row.ayah ?: 0).put("savedAt", row.savedAtMs)
        }).toString()).apply()
        if (before.firstOrNull()?.selection != place.selection || !state.playing) changed()
    }

    fun favorites(context: Context): List<QuranAutoBrowse.Selection> = decodeSelections(prefs(context).getString("favorites", null))

    @Synchronized fun toggleFavorite(context: Context, selection: QuranAutoBrowse.Selection) {
        val old = favorites(context)
        val next = if (selection in old) old - selection else old + selection
        prefs(context).edit().putString("favorites", JSONArray(next.map(::encode)).toString()).apply()
        changed()
    }

    fun saveQueue(context: Context, queue: QuranAutoQueue?) {
        prefs(context).edit().putString("queue", queue?.let {
            JSONObject().put("items", JSONArray(it.items.map(::encode))).put("index", it.index).put("source", it.source.id).toString()
        }).apply()
    }

    fun queue(context: Context): QuranAutoQueue? = runCatching {
        val row = JSONObject(prefs(context).getString("queue", null) ?: return null)
        val source = QuranAutoSource.entries.first { it.id == row.getString("source") }
        val rows = row.getJSONArray("items")
        val items = decodeSelections(rows.toString())
        require(items.size == rows.length())
        QuranAutoQueue(items, row.getInt("index"), source)
    }.getOrNull()

    private fun decodeSelections(value: String?): List<QuranAutoBrowse.Selection> = runCatching {
        val rows = JSONArray(value ?: "[]")
        (0 until minOf(rows.length(), 342)).mapNotNull { selection(rows.getJSONObject(it)) }.distinct()
    }.getOrDefault(emptyList())

    private fun selection(row: JSONObject): QuranAutoBrowse.Selection? {
        val reciter = row.optString("reciter")
        val surah = row.optInt("surah")
        if (surah !in 1..114 || QuranAudioController.reciters.none { it.id == reciter }) return null
        return QuranAutoBrowse.Selection(reciter, surah)
    }

    private fun encode(selection: QuranAutoBrowse.Selection) =
        JSONObject().put("reciter", selection.reciterId).put("surah", selection.surah)

    private fun changed() { revision.update { it + 1 } }
}

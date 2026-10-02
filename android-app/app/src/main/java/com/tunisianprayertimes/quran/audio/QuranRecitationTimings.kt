package com.tunisianprayertimes.quran.audio

import android.content.Context
import org.json.JSONObject

internal data class QuranAyahTiming(val ayah: Int, val startMs: Long, val endMs: Long)

internal data class QuranSurahRecording(
    val number: Int,
    val assetPath: String,
    val durationMs: Long,
    val timings: List<QuranAyahTiming>,
) {
    /** Only the supplied interval identifies a verse; never stretch it into untimed audio. */
    fun ayahAt(positionMs: Long): Int? {
        var low = 0
        var high = timings.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val timing = timings[mid]
            when {
                positionMs < timing.startMs -> high = mid - 1
                positionMs >= timing.endMs -> low = mid + 1
                else -> return timing.ayah
            }
        }
        return null
    }

    fun startOf(ayah: Int): Long? = timings.getOrNull(ayah - 1)?.takeIf { it.ayah == ayah }?.startMs
}

internal object QuranRecitationTimings {
    private val cache = mutableMapOf<String, List<QuranSurahRecording>>()

    /** Read on an IO dispatcher, once per reciter. */
    @Synchronized
    fun load(context: Context, reciter: QuranReciter): List<QuranSurahRecording> = cache.getOrPut(reciter.id) {
        val json = JSONObject(context.assets.open(reciter.timingAssetPath).bufferedReader().use { it.readText() })
        require(json.getInt("version") == 1 && json.getString("reciterId") == reciter.id)
        require(json.getString("numbering") == "madani-later") { "Recitation and mushaf verse numbering differ" }
        val surahs = json.getJSONArray("surahs")
        require(surahs.length() == 114)
        List(surahs.length()) { index ->
            val source = surahs.getJSONObject(index)
            val number = source.getInt("number")
            val duration = source.getLong("durationMs")
            val assetPath = source.getString("assetPath")
            require(number == index + 1 && duration > 0L && assetPath.startsWith("quran/audio/") && ".." !in assetPath)
            val rows = source.getJSONArray("timings")
            val timings = List(rows.length()) { row ->
                val item = rows.getJSONObject(row)
                QuranAyahTiming(item.getInt("ayah"), item.getLong("startMs"), item.getLong("endMs"))
            }
            require(timings.isNotEmpty() && timings.withIndex().all { (row, item) ->
                item.ayah == row + 1 && item.startMs >= 0L && item.endMs > item.startMs && item.endMs <= duration &&
                    (row == 0 || timings[row - 1].endMs <= item.startMs)
            }) { "Invalid recitation verse boundaries" }
            QuranSurahRecording(number, assetPath, duration, timings)
        }
    }
}

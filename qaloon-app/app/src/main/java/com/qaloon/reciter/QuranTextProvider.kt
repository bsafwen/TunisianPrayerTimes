package com.qaloon.reciter

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Provides Quran text organized by surah and ayah.
 * Reads from a JSON asset file.
 */
class QuranTextProvider(private val context: Context) {

    data class Surah(
        val number: Int,
        val name: String,
        val ayahs: List<Ayah>
    )

    data class Ayah(
        val number: Int,
        val text: String
    )

    private var surahs: List<Surah> = emptyList()

    fun load(assetName: String = "quran_qaloon.json") {
        val json = context.assets.open(assetName).bufferedReader().readText()
        val arr = JSONArray(json)
        val list = mutableListOf<Surah>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val ayahsArr = obj.getJSONArray("ayahs")
            val ayahs = mutableListOf<Ayah>()
            for (j in 0 until ayahsArr.length()) {
                val ayahObj = ayahsArr.getJSONObject(j)
                ayahs.add(
                    Ayah(
                        number = ayahObj.getInt("number"),
                        text = ayahObj.getString("text")
                    )
                )
            }
            list.add(
                Surah(
                    number = obj.getInt("number"),
                    name = obj.getString("name"),
                    ayahs = ayahs
                )
            )
        }
        surahs = list
    }

    fun getSurahs(): List<Surah> = surahs

    fun getSurah(number: Int): Surah? = surahs.find { it.number == number }

    fun getAyahText(surah: Int, ayah: Int): String? =
        getSurah(surah)?.ayahs?.find { it.number == ayah }?.text

    data class AyahMatch(
        val surah: Int,
        val ayah: Int,
        val text: String,
        val score: Int
    )

    /**
     * Find the ayah that best matches a transcription snippet.
     * Uses normalized word overlap — works even with partial/noisy transcription.
     */
    fun findAyah(transcription: String): AyahMatch? {
        val queryWords = normalize(transcription).split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (queryWords.isEmpty()) return null

        var best: AyahMatch? = null

        for (surah in surahs) {
            for (ayah in surah.ayahs) {
                val ayahWords = normalize(ayah.text).split("\\s+".toRegex()).filter { it.isNotBlank() }
                val score = longestCommonSubseqLen(queryWords, ayahWords)
                if (score > 0 && (best == null || score > best.score)) {
                    best = AyahMatch(surah.number, ayah.number, ayah.text, score)
                }
            }
        }

        return best
    }

    private fun normalize(text: String): String {
        return text
            .replace(Regex("[\u0610-\u061A\u064B-\u065F\u0670\u06D6-\u06DC\u06DF-\u06E4\u06E7\u06E8\u06EA-\u06ED]"), "")
            .replace("\u0622", "\u0627")
            .replace("\u0623", "\u0627")
            .replace("\u0625", "\u0627")
            .replace("\u0629", "\u0647")
            .trim()
    }

    private fun longestCommonSubseqLen(a: List<String>, b: List<String>): Int {
        val m = a.size
        val n = b.size
        // Use single-row DP to save memory (6236 ayahs × many words)
        var prev = IntArray(n + 1)
        var curr = IntArray(n + 1)
        for (i in 1..m) {
            for (j in 1..n) {
                curr[j] = if (a[i - 1] == b[j - 1]) prev[j - 1] + 1
                          else maxOf(prev[j], curr[j - 1])
            }
            val tmp = prev; prev = curr; curr = tmp
            curr.fill(0)
        }
        return prev[n]
    }
}

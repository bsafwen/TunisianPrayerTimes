package com.tunisianprayertimes.quran

import android.content.Context
import org.json.JSONObject
import java.text.Normalizer

/** Page numbers are the one-based image positions in the supplied Qaloun scan. */
data class QuranPage(val number: Int, val assetPath: String, val surahNames: List<String>)

data class QuranSurah(val number: Int, val name: String, val page: Int, val verseCount: Int? = null)

data class QuranSearchResult(val surahName: String, val text: String, val page: Int)

internal data class QuranSearchEntry(val surah: Int, val text: String, val page: Int, val searchText: String = text) {
    val normalized = normalizeQuranSearch(text)
    val spelling = normalizeQuranSearch(searchText)
}

class QuranCatalog internal constructor(
    val pages: List<QuranPage>,
    val surahs: List<QuranSurah>,
    private val entries: List<QuranSearchEntry>,
) {
    private val pageText = entries.groupBy { it.surah to it.page }.values.map { QuranPageText(it) }

    /** The index is independent of verse numbering, which differs between mushaf editions. */
    fun search(query: String): List<QuranSearchResult> {
        val needle = normalizeQuranSearch(query)
        if (needle.isBlank()) return emptyList()
        return pageText.mapNotNull { page ->
            page.excerpt(needle)?.let { text ->
                QuranSearchResult(surahs[page.surah - 1].name, text, page.number)
            }
        }
    }
}

/** Search across verse boundaries while showing only the matching source verse fragments. */
private class QuranPageText(private val entries: List<QuranSearchEntry>) {
    val surah = entries.first().surah
    val number = entries.first().page
    private val original = entries.joinToString(" ") { it.normalized }
    private val spelling = entries.joinToString(" ") { it.spelling }

    fun excerpt(query: String): String? {
        val originalStart = original.indexOf(query)
        val spellingStart = if (originalStart < 0) spelling.indexOf(query) else -1
        val start = maxOf(originalStart, spellingStart)
        if (start < 0) return null
        val end = start + query.length
        var offset = 0
        return entries.filter { entry ->
            val length = if (originalStart >= 0) entry.normalized.length else entry.spelling.length
            val intersects = offset < end && offset + length > start
            offset += length + 1
            intersects
        }.joinToString(" ") { it.text }
    }
}

object QuranRepository {
    @Volatile private var cached: QuranCatalog? = null

    /** Call on an IO dispatcher. Scans and the search index work entirely offline. */
    fun load(context: Context): QuranCatalog = cached ?: synchronized(this) {
        cached ?: read(context.applicationContext).also { cached = it }
    }

    private fun read(context: Context): QuranCatalog {
        val source = JSONObject(context.assets.open("quran/index.json").bufferedReader().use { it.readText() })
        val chapters = source.getJSONArray("surahs")
        val surahs = List(chapters.length()) { index ->
            val chapter = chapters.getJSONObject(index)
            QuranSurah(chapter.getInt("number"), chapter.getString("name"), chapter.getInt("page"), chapter.optInt("verseCount").takeIf { it > 0 })
        }
        require(surahs.map { it.number } == (1..114).toList()) { "Incomplete Quran chapter index" }
        val search = source.getJSONArray("entries")
        val entries = List(search.length()) { index ->
            val entry = search.getJSONObject(index)
            QuranSearchEntry(entry.getInt("surah"), entry.getString("text"), entry.getInt("page"), entry.optString("searchText", entry.getString("text")))
        }
        val namesByPage = entries.groupBy { it.page }.mapValues { (_, values) ->
            values.map { surahs[it.surah - 1].name }.distinct()
        }
        val manifest = JSONObject(context.assets.open("quran/pages/pages.json").bufferedReader().use { it.readText() })
        val images = manifest.getJSONArray("pages")
        val pages = List(images.length()) { index ->
            val image = images.getJSONObject(index)
            val number = image.getInt("n")
            require(number == index + 1) { "Non-contiguous Quran pages" }
            QuranPage(number, "quran/pages/${image.getString("file")}", namesByPage[number].orEmpty())
        }
        require(entries.isNotEmpty() && entries.all { it.surah in 1..114 && it.page in 1..pages.size }) { "Invalid Quran search index" }
        return QuranCatalog(pages, surahs, entries)
    }
}

/** Ignore recitation marks and accept the ordinary Arabic spelling used by keyboards. */
internal fun normalizeQuranSearch(value: String): String = buildString {
    Normalizer.normalize(value, Normalizer.Form.NFKC).forEach { char ->
        when {
            char in "ـۥۦ" || Character.getType(char) in markTypes -> Unit
            char in "أإآٱ" -> append('ا')
            char == 'ى' || char == 'ئ' || char == 'ی' -> append('ي')
            char == 'ؤ' -> append('و')
            char == 'ة' -> append('ه')
            char == 'ک' -> append('ك')
            char.isLetterOrDigit() -> append(char.lowercaseChar())
            else -> append(' ')
        }
    }
}.replace(Regex("\\s+"), " ").trim()

private val markTypes = setOf(
    Character.NON_SPACING_MARK.toInt(),
    Character.COMBINING_SPACING_MARK.toInt(),
    Character.ENCLOSING_MARK.toInt(),
)

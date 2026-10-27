package com.tunisianprayertimes.quran

import android.content.Context
import org.json.JSONObject
import java.text.Normalizer

/** Page numbers are the one-based image positions in the supplied Qaloun scan. */
data class QuranPage(val number: Int, val assetPath: String, val surahNames: List<String>)

data class QuranSurah(val number: Int, val name: String, val page: Int, val verseCount: Int? = null)

internal data class QuranSearchEntry(
    val surah: Int,
    val ayah: Int,
    val text: String,
    val page: Int,
    val searchText: String = text,
    val isFirstFragment: Boolean = true,
    val isLastFragment: Boolean = true,
    /** Kept separate from the original text used for matching and highlighting. */
    private val convertedWords: List<String>? = null,
) {
    val mushafWords: List<String> by lazy { convertedWords ?: QuranMushafText.words(text.split(' ')) }
    // Read when the first search is prepared, not while the index loads.
    val normalized: String by lazy { normalizeQuranSearch(text) }
    val spelling: String by lazy { normalizeQuranSearch(searchText) }
}

class QuranCatalog internal constructor(
    val pages: List<QuranPage>,
    val surahs: List<QuranSurah>,
    entries: List<QuranSearchEntry>,
    /** The mushaf's waqf signs by verse ([QuranWaqfSign]); none for a verse that has none. */
    val waqf: Map<QuranVerseReference, List<QuranWaqfSign>> = emptyMap(),
) {
    /** Includes the 113 unnumbered opening basmalahs as ayah 0. Raw source text remains the search identity. */
    val verses: List<QuranVerse> = entries.groupBy { QuranVerseReference(it.surah, it.ayah) }.map { (reference, fragments) ->
        QuranVerse(
            reference.surah, reference.ayah, fragments.joinToString(" ") { it.text },
            fragments.map { it.page }.distinct(), waqf[reference].orEmpty(),
        )
    }
    private val versesByReference = verses.associateBy { it.reference }
    // Waqf positions belong to a complete verse, even when a search shows only its second page. Convert the
    // complete verse once, then slice by raw token count; grouping words first would shift the sign positions.
    // Lazy, like each verse's own words: converting the whole text here held the reader's verse taps for seconds,
    // while a page needs only its own verses. The search prepares it on a background dispatcher.
    private val displayEntries: List<QuranSearchEntry> by lazy {
        val offsets = mutableMapOf<QuranVerseReference, Int>()
        entries.map { entry ->
            val reference = QuranVerseReference(entry.surah, entry.ayah)
            val from = offsets[reference] ?: 0
            val to = from + entry.text.split(' ').size
            offsets[reference] = to
            entry.copy(convertedWords = versesByReference.getValue(reference).mushafWords.subList(from, to))
        }
    }
    private val searchIndex by lazy { QuranSearchIndex(displayEntries, surahs) }
    val verseFragments: List<QuranVerseFragment> by lazy {
        displayEntries.map {
            QuranVerseFragment(
                QuranVerseReference(it.surah, it.ayah), it.page, it.text, it.isFirstFragment, it.isLastFragment, it.mushafWords,
            )
        }
    }
    private val versesByPage = verses.flatMap { verse -> verse.pages.map { it to verse } }
        .groupBy({ it.first }, { it.second })

    fun verse(surah: Int, ayah: Int): QuranVerse? = verse(QuranVerseReference(surah, ayah))

    fun verse(reference: QuranVerseReference): QuranVerse? = versesByReference[reference]

    fun textForVerse(surah: Int, ayah: Int): String? = verse(surah, ayah)?.text

    fun pagesForVerse(surah: Int, ayah: Int): List<Int> = verse(surah, ayah)?.pages.orEmpty()

    fun versesOnPage(page: Int): List<QuranVerse> = versesByPage[page].orEmpty()

    /**
     * Verses holding the words, best matches first; see [QuranSearchKind]. The index is independent
     * of verse numbering, which differs between mushaf editions.
     */
    fun search(query: String): List<QuranSearchResult> = searchIndex.search(query)

    /** Builds what the first search needs. Call on a background dispatcher once the catalog is read. */
    fun prepareSearch() {
        searchIndex
    }
}

object QuranRepository {
    @Volatile private var cached: QuranCatalog? = null
    @Volatile private var cachedPages: List<QuranPage>? = null
    private val pagesLock = Any()

    /** What this process has already read, without waiting for anything. */
    fun loaded(): QuranCatalog? = cached

    fun loadedPages(): List<QuranPage>? = cached?.pages ?: cachedPages

    /**
     * The page scans alone, not yet named after their chapters: enough to show the mushaf while
     * the much larger text index is still being read. Call on an IO dispatcher.
     */
    fun loadPages(context: Context): List<QuranPage> = loadedPages() ?: synchronized(pagesLock) {
        cachedPages ?: readPages(context.applicationContext).also { cachedPages = it }
    }

    private fun readPages(context: Context): List<QuranPage> {
        val manifest = JSONObject(context.assets.open("quran/pages/pages.json").bufferedReader().use { it.readText() })
        val images = manifest.getJSONArray("pages")
        return List(images.length()) { index ->
            val image = images.getJSONObject(index)
            val number = image.getInt("n")
            require(number == index + 1) { "Non-contiguous Quran pages" }
            QuranPage(number, "quran/pages/${image.getString("file")}", emptyList())
        }
    }

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
            QuranSearchEntry(
                surah = entry.getInt("surah"),
                ayah = entry.getInt("ayah"),
                text = entry.getString("text"),
                page = entry.getInt("page"),
                searchText = entry.optString("searchText", entry.getString("text")),
                isFirstFragment = entry.getBoolean("isFirstFragment"),
                isLastFragment = entry.getBoolean("isLastFragment"),
            )
        }
        val namesByPage = entries.groupBy { it.page }.mapValues { (_, values) ->
            values.map { surahs[it.surah - 1].name }.distinct()
        }
        val pages = loadPages(context).map { it.copy(surahNames = namesByPage[it.number].orEmpty()) }
        require(entries.isNotEmpty() && entries.all {
            it.surah in 1..114 && it.page in 1..pages.size && it.ayah in 0..(surahs[it.surah - 1].verseCount ?: 0)
        }) { "Invalid Quran search index" }
        surahs.forEach { surah ->
            val expected = if (surah.number == 9) 1 else 0
            require(entries.filter { it.surah == surah.number }.map { it.ayah }.distinct() == (expected..requireNotNull(surah.verseCount)).toList()) {
                "Incomplete Qaloun verse index for chapter ${surah.number}"
            }
        }
        return QuranCatalog(pages, surahs, entries, readWaqf(context))
    }

    private fun readWaqf(context: Context): Map<QuranVerseReference, List<QuranWaqfSign>> {
        val source = JSONObject(context.assets.open("quran/waqf.json").bufferedReader().use { it.readText() })
        return parseQuranWaqf(source.getJSONObject("signs"), source.getJSONObject("verses"))
    }
}

/** A waqf sign as the mushaf prints it, over the gap after word [word] (from 0) of its verse. */
data class QuranWaqfSign(val word: Int, val sign: Char)

internal fun parseQuranWaqf(signs: JSONObject, verses: JSONObject): Map<QuranVerseReference, List<QuranWaqfSign>> =
    verses.keys().asSequence().associate { key ->
        val (surah, ayah) = key.split(':').map { it.toInt() }
        val row = verses.getJSONArray(key)
        QuranVerseReference(surah, ayah) to List(row.length()) { i ->
            val sign = row.getJSONArray(i)
            QuranWaqfSign(sign.getInt(0), signs.getString(sign.getString(1)).single())
        }
    }

/** Ignore recitation marks and accept the ordinary Arabic spelling used by keyboards. */
internal fun normalizeQuranSearch(value: String): String = buildString {
    Normalizer.normalize(value, Normalizer.Form.NFKC).forEach { char ->
        when {
            char in "ـۥۦ" || Character.getType(char) in markTypes -> Unit
            char in "أإآٱ" -> append('ا')
            char == 'ى' || char == 'ئ' || char == 'ی' || char == 'ے' -> append('ي')
            char == 'ؤ' -> append('و')
            char == 'ة' -> append('ه')
            char == 'ک' -> append('ك')
            char.isLetterOrDigit() -> append(char.lowercaseChar())
            else -> append(' ')
        }
    }
}.replace(whitespace, " ").trim()

private val whitespace = Regex("\\s+")

private val markTypes = setOf(
    Character.NON_SPACING_MARK.toInt(),
    Character.COMBINING_SPACING_MARK.toInt(),
    Character.ENCLOSING_MARK.toInt(),
)

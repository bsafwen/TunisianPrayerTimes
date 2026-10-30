package com.tunisianprayertimes.quran

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** One eighth of a hizb, as the supplied scan marks it in its margin and before its first verse. */
data class QuranThumn(
    /** Zero-based position among the 480 athman. */
    val index: Int,
    val start: QuranVerseReference,
    /** One-based scan page of [start]. */
    val page: Int,
) {
    val juz: Int get() = index / 16 + 1
    val hizb: Int get() = index / 8 + 1
    /** 1 to 8 within the hizb. */
    val thumn: Int get() = index % 8 + 1
}

/** The 60 ahzab and their athman, in the later Madani count. */
class QuranDivisions internal constructor(val athman: List<QuranThumn>) {
    init {
        require(athman.size == 480 && athman.zipWithNext().all { (a, b) -> a.start < b.start }) { "Invalid Quran divisions" }
    }

    /** The thumn a verse belongs to. */
    fun thumnOf(verse: QuranVerseReference): QuranThumn {
        var low = 0
        var high = athman.lastIndex
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (athman[mid].start <= verse) low = mid else high = mid - 1
        }
        return athman[low]
    }

    fun hizbStart(hizb: Int): QuranThumn? = athman.getOrNull((hizb - 1) * 8)

    fun athmanOf(hizb: Int): List<QuranThumn> = if (hizb in 1..60) athman.subList((hizb - 1) * 8, hizb * 8) else emptyList()
}

object QuranDivisionsRepository {
    @Volatile private var cached: QuranDivisions? = null

    fun loaded(): QuranDivisions? = cached

    /** Call on IO. */
    fun load(context: Context): QuranDivisions = cached ?: synchronized(this) {
        cached ?: read(context).also { cached = it }
    }

    private fun read(context: Context): QuranDivisions {
        val source = JSONObject(context.assets.open("quran/divisions.json").bufferedReader().use { it.readText() })
        require(source.getString("numbering") == "madani-later")
        return parseQuranDivisions(source.getJSONArray("athman"))
    }
}

internal fun parseQuranDivisions(rows: JSONArray): QuranDivisions = QuranDivisions(List(rows.length()) { index ->
    val row = rows.getJSONArray(index)
    QuranThumn(index, QuranVerseReference(row.getInt(0), row.getInt(1)), row.getInt(2))
})

/** What «تشغيل» does now; one rule for the dock, the expanded player and the label beside them. */
sealed interface QuranPlayAction {
    data object Pause : QuranPlayAction
    /** Continue the paused or saved recitation where it is. */
    data object Resume : QuranPlayAction
    data class PlayFrom(val verse: QuranVerseReference) : QuranPlayAction
    /** Nothing to play yet (the page's verses are still being read). */
    data object None : QuranPlayAction
}

/**
 * A verse picked on the page wins; a paused recitation resumes unless the reader has gone elsewhere
 * since pausing and the page in view does not hold it, in which case this page starts.
 */
fun quranPlayAction(
    playing: Boolean,
    hasSession: Boolean,
    selection: QuranVerseReference?,
    sessionVerseOnPage: Boolean,
    navigatedSincePause: Boolean,
    pageStart: QuranVerseReference?,
): QuranPlayAction = when {
    playing -> QuranPlayAction.Pause
    selection != null -> QuranPlayAction.PlayFrom(selection)
    hasSession && (sessionVerseOnPage || !navigatedSincePause || pageStart == null) -> QuranPlayAction.Resume
    pageStart != null -> QuranPlayAction.PlayFrom(pageStart)
    else -> QuranPlayAction.None
}

/** One row offered by the index's «انتقال» field. */
sealed interface QuranGoTo {
    data class Page(val printed: Int) : QuranGoTo
    data class Surah(val number: Int) : QuranGoTo
    data class Verse(val verse: QuranVerseReference) : QuranGoTo
    data class Hizb(val number: Int) : QuranGoTo
}

/**
 * «42» is a page and a chapter; «2:21», «2 21» or «البقرة 21» a verse; «حزب 12» a hizb; «ص 51» a page.
 * Chapter names alone are matched by the index list itself, not here.
 */
fun quranGoTo(input: String, surahs: List<QuranSurah>, printedPages: Int): List<QuranGoTo> {
    val text = input.trim().map { c -> c.digitToIntOrNull()?.digitToChar() ?: c }.joinToString("")
        .replace('،', ' ').replace(Regex("\\s+"), " ")
    if (text.isEmpty()) return emptyList()
    fun verse(surah: Int, ayah: Int): QuranGoTo? {
        val count = surahs.firstOrNull { it.number == surah }?.verseCount ?: return null
        return if (ayah in 1..count) QuranGoTo.Verse(QuranVerseReference(surah, ayah)) else null
    }
    Regex("^(\\d{1,3})\\s*[:：/.-]\\s*(\\d{1,3})$").matchEntire(text)?.let { m ->
        return listOfNotNull(verse(m.groupValues[1].toInt(), m.groupValues[2].toInt()))
    }
    Regex("^(?:ال)?حزب\\s*(\\d{1,2})$").matchEntire(text)?.let { m ->
        val n = m.groupValues[1].toInt()
        return if (n in 1..60) listOf(QuranGoTo.Hizb(n)) else emptyList()
    }
    Regex("^(?:صفحة|ص)\\s*\\.?\\s*(\\d{1,3})$").matchEntire(text)?.let { m ->
        val n = m.groupValues[1].toInt()
        return if (n in 1..printedPages) listOf(QuranGoTo.Page(n)) else emptyList()
    }
    Regex("^(\\d{1,3})\\s+(\\d{1,3})$").matchEntire(text)?.let { m ->
        return listOfNotNull(verse(m.groupValues[1].toInt(), m.groupValues[2].toInt()))
    }
    Regex("^(\\d{1,3})$").matchEntire(text)?.let { m ->
        val n = m.groupValues[1].toInt()
        return listOfNotNull(
            QuranGoTo.Page(n).takeIf { n in 1..printedPages },
            QuranGoTo.Surah(n).takeIf { n in 1..114 },
        )
    }
    // «البقرة 21»: a chapter name followed by a verse number.
    Regex("^(.+?)\\s+(\\d{1,3})$").matchEntire(text)?.let { m ->
        val name = quranNameKey(m.groupValues[1])
        val surah = surahs.firstOrNull { quranNameKey(it.name) == name } ?: return emptyList()
        return listOfNotNull(verse(surah.number, m.groupValues[2].toInt()))
    }
    return emptyList()
}

/** Chapter names compared without vowels, tatweel or the alef forms. */
fun quranNameKey(value: String): String = value.trim()
    .replace(Regex("[\\u064B-\\u065F\\u0670\\u06D6-\\u06EDـ]"), "")
    .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ٱ', 'ا').replace('ى', 'ي').replace('ة', 'ه')
    .removePrefix("سوره ").trim()

/**
 * The text a verse is copied or shared as, as the app's mushaf prints it, for whatever font the other app has: the
 * mushaf's words ([QuranMushafText.words]) with the mushaf's waqf signs ([waqf], read on its scans), in the code points
 * other fonts draw as the mushaf means them ([QuranMushafText.plain]), then where it is. Not the index's own encoding,
 * which is for the King Fahd font: elsewhere its wasl reads as a vowel and its open tanween as other marks, and its waqf
 * signs are at Maghribi positions. The number is the mushaf's, in the later Madani count it names, which other mushafs
 * do not all follow. A passage joins its verses in order, each followed by its number.
 */
fun quranShareText(surahName: String, verses: List<QuranVerse>, waqf: Map<QuranVerseReference, List<QuranWaqfSign>> = emptyMap()): String {
    val numbered = verses.filter { it.ayah > 0 }
    if (numbered.isEmpty()) return ""
    fun words(verse: QuranVerse): String {
        val text = waqf[verse.reference]?.let { QuranMushafText.text(verse.text, it) } ?: verse.mushafText
        return QuranMushafText.plain(text)
    }
    val body = if (numbered.size == 1) words(numbered.first())
        else numbered.joinToString(" ") { "${words(it)} (${it.ayah})" }
    val where = if (numbered.size == 1) "$surahName: ${numbered.first().ayah}"
        else "$surahName: ${numbered.first().ayah} إلى ${numbered.last().ayah}"
    return "﴿$body﴾ [$where] · رواية قالون عن نافع، العدّ المدني الأخير"
}

/**
 * The last page the reader settled on, and recently read pages, in one small store.
 * The reader observes [revision] to redraw after a change.
 */
class QuranLibrary internal constructor(private val prefs: SharedPreferences) {
    var revision: Int = 0
        private set

    /** Scan pages, most recent first. */
    val recentPages: List<Int>
        get() = prefs.getString(KEY_RECENT, "").orEmpty().split(',').mapNotNull { it.toIntOrNull() }

    /** The scan page the reader last chose to read, never one an auto-following recitation turned to. */
    val lastRead: Int?
        get() = prefs.getInt(KEY_LAST_READ, 0).takeIf { it > 0 }

    /** A page the reader settled on: it becomes the last read and joins the recent pages. */
    fun read(page: Int) {
        val recent = (listOf(page) + recentPages.filter { it != page }).take(MAX_RECENT)
        prefs.edit().putInt(KEY_LAST_READ, page).putString(KEY_RECENT, recent.joinToString(",")).apply()
        revision++
    }

    companion object {
        private const val KEY_RECENT = "recent_pages"
        private const val KEY_LAST_READ = "last_read_page"
        private const val MAX_RECENT = 10

        fun of(context: Context): QuranLibrary =
            QuranLibrary(context.getSharedPreferences("quran_library", Context.MODE_PRIVATE))
    }
}

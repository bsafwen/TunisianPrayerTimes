package com.tunisianprayertimes.quran

/** Why a result is listed; results come ordered from the most to the least exact. */
enum class QuranSearchKind {
    /** The query named a verse, such as «2:255» or «البقرة 255». */
    Reference,
    /** The words as typed, side by side. */
    Phrase,
    /** Every word is in the verse, though not side by side. */
    Words,
    /** Close to what was typed: a slip of the keyboard or an unusual spelling. */
    Similar,
}

/** [verses] are the ones the query touches on [page], in mushaf order. */
data class QuranSearchResult(
    val surahName: String,
    val text: String,
    val page: Int,
    val verses: List<QuranVerseReference>,
    val kind: QuranSearchKind = QuranSearchKind.Phrase,
) {
    val hit: QuranSearchHit get() = QuranSearchHit(page, verses)
}

/** What a tapped search result leaves on the page it opens: the verses to colour there. */
data class QuranSearchHit(val page: Int, val verses: List<QuranVerseReference>) {
    fun encode(): String = (listOf(page) + verses.flatMap { listOf(it.surah, it.ayah) }).joinToString(":")

    companion object {
        /** Null for anything [encode] could not have written, so a stale saved value shows nothing. */
        fun decode(value: String?): QuranSearchHit? {
            val numbers = value?.split(':')?.map { it.toIntOrNull() ?: return null } ?: return null
            if (numbers.size < 3 || numbers.size % 2 == 0 || numbers[0] < 1) return null
            return QuranSearchHit(numbers[0], numbers.drop(1).chunked(2).map { QuranVerseReference(it[0], it[1]) })
        }
    }
}

/**
 * The words of [text] that a search for [query] matched, as character ranges to emphasise.
 * With [similar], words a keyboard slip away from the query count too.
 */
fun quranMatchRanges(text: String, query: String, similar: Boolean = false): List<IntRange> {
    val typed = normalizeQuranSearch(query).split(' ').filter { it.isNotEmpty() }
    if (typed.isEmpty()) return emptyList()
    val words = if (similar) typed + typed.map { withoutArticle(it) } else typed
    return Regex("\\S+").findAll(text).filter { token ->
        val word = normalizeQuranSearch(token.value)
        word.isNotEmpty() && words.any { it in word || (similar && withinEdits(word, it, allowedEdits(it.length)) != null) }
    }.map { it.range }.toList()
}

/** The word without its leading «ال», when enough of it is left to mean something. */
private fun withoutArticle(word: String): String = if (word.startsWith("ال") && word.length > 4) word.drop(2) else word

/** The searches the reader offers again, newest first, each kept once however it is spelled. */
fun List<String>.withRecentSearch(query: String, max: Int = 8): List<String> {
    val clean = query.replace(Regex("\\s+"), " ").trim()
    if (normalizeQuranSearch(clean).isEmpty()) return this
    val key = normalizeQuranSearch(clean)
    return (listOf(clean) + filter { normalizeQuranSearch(it) != key }).take(max)
}

/** How many slips of the keyboard a word of this length forgives. */
internal fun allowedEdits(length: Int): Int = when {
    length >= 9 -> 2
    length >= 4 -> 1
    else -> 0
}

/** The edit distance between the two words when it is at most [max], else null. */
internal fun withinEdits(a: String, b: String, max: Int): Int? {
    if (a == b) return 0
    if (max == 0 || kotlin.math.abs(a.length - b.length) > max) return null
    var previous = IntArray(b.length + 1) { it }
    var current = IntArray(b.length + 1)
    for (i in 1..a.length) {
        current[0] = i
        var rowBest = current[0]
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            if (current[j] < rowBest) rowBest = current[j]
        }
        if (rowBest > max) return null
        val swap = previous; previous = current; current = swap
    }
    return previous[b.length].takeIf { it <= max }
}

/**
 * One verse as the search sees it: its fragments in two spellings, the Qaloun text and the
 * ordinary one, each normalised and cut into words.
 */
private class SearchForm(parts: List<String>) {
    val text = parts.joinToString(" ")
    private val starts = IntArray(parts.size).also { starts ->
        var offset = 0
        parts.forEachIndexed { index, part -> starts[index] = offset; offset += part.length + 1 }
    }
    /** Each word with the fragment it belongs to. */
    val words: List<Pair<String, Int>> = parts.flatMapIndexed { index, part ->
        part.split(' ').filter { it.isNotEmpty() }.map { it to index }
    }

    fun partAt(offset: Int): Int {
        var index = starts.lastIndex
        while (index > 0 && starts[index] > offset) index--
        return index
    }

    /** Offset of the first occurrence between whole words, else of the first one anywhere. */
    fun find(needle: String): Pair<Int, Boolean>? {
        var from = 0
        var first = -1
        while (true) {
            val at = text.indexOf(needle, from)
            if (at < 0) break
            if (first < 0) first = at
            val end = at + needle.length
            if ((at == 0 || text[at - 1] == ' ') && (end == text.length || text[end] == ' ')) return at to true
            from = at + 1
        }
        return if (first >= 0) first to false else null
    }
}

private class SearchVerse(val surah: Int, val ayah: Int, val fragments: List<QuranSearchEntry>) {
    val reference = QuranVerseReference(surah, ayah)
    val forms = listOf(SearchForm(fragments.map { it.normalized }), SearchForm(fragments.map { it.spelling }))
}

/** Everything on one scan of one chapter, for phrases that run from one verse into the next. */
private class SearchPage(private val entries: List<QuranSearchEntry>) {
    val number = entries.first().page
    private val originals = entries.map { it.normalized }
    private val spellings = entries.map { it.spelling }

    fun acrossVerses(needle: String): List<List<QuranSearchEntry>> {
        val found = mutableListOf<List<QuranSearchEntry>>()
        for (parts in listOf(originals, spellings)) {
            val text = parts.joinToString(" ")
            var from = 0
            while (true) {
                val start = text.indexOf(needle, from)
                if (start < 0) break
                val end = start + needle.length
                var offset = 0
                val touched = entries.filterIndexed { index, _ ->
                    val intersects = offset < end && offset + parts[index].length > start
                    offset += parts[index].length + 1
                    intersects
                }
                if (touched.map { it.surah to it.ayah }.distinct().size >= 2) found += touched
                from = end
            }
        }
        return found.distinctBy { group -> group.map { it.surah to it.ayah } }
    }
}

private class Ranked(val rank: Int, val distance: Int, val order: Int, val result: QuranSearchResult)

internal class QuranSearchIndex(entries: List<QuranSearchEntry>, private val surahs: List<QuranSurah>) {
    private val verses = entries.groupBy { it.surah to it.ayah }
        .map { (key, fragments) -> SearchVerse(key.first, key.second, fragments) }
        .sortedWith(compareBy({ it.surah }, { it.ayah }))
    private val versesByReference = verses.associateBy { it.reference }
    private val pages = entries.groupBy { it.surah to it.page }.values.map { SearchPage(it) }
    private val surahsByName: Map<String, Int> = buildMap {
        surahs.forEach { surah ->
            val name = normalizeQuranSearch(surah.name).removePrefix("سوره ").trim()
            put(name, surah.number)
            // «البقرة» is as often typed «بقرة».
            put(name.removePrefix("ال").trim(), surah.number)
        }
    }

    fun search(query: String): List<QuranSearchResult> {
        val needle = normalizeQuranSearch(query)
        if (needle.isEmpty()) return emptyList()
        val words = needle.split(' ')
        val ranked = ArrayList<Ranked>()
        val listed = HashSet<QuranVerseReference>()
        referenceOf(needle)?.let { reference ->
            val verse = versesByReference.getValue(reference)
            listed += reference
            ranked += Ranked(
                -1, 0, order(reference),
                QuranSearchResult(
                    surahs[reference.surah - 1].name, verse.fragments.joinToString(" ") { it.text },
                    verse.fragments.first().page, listOf(reference), QuranSearchKind.Reference,
                ),
            )
        }
        for (verse in verses) {
            if (verse.reference in listed) continue
            val match = bestMatch(verse, needle, words) ?: continue
            listed += verse.reference
            val kind = if (match.rank >= 3) QuranSearchKind.Words else QuranSearchKind.Phrase
            ranked += Ranked(match.rank, 0, order(verse.reference), resultOf(verse, match.parts, kind))
        }
        for (page in pages) for (group in page.acrossVerses(needle)) {
            val references = group.map { QuranVerseReference(it.surah, it.ayah) }.distinct()
            ranked += Ranked(
                2, 0, order(references.first()),
                QuranSearchResult(surahs[group.first().surah - 1].name, group.joinToString(" ") { it.text }, page.number, references),
            )
        }
        // A near miss is only worth listing when the exact words found little.
        if (ranked.count { it.rank >= 0 } < SIMILAR_BELOW) ranked += similar(words, listed)
        if (ranked.isEmpty()) {
            // «الكرسي» is written «كرسيه» in the verse: try again without the article.
            val bare = words.joinToString(" ") { withoutArticle(it) }
            if (bare != needle) return search(bare).map { it.copy(kind = QuranSearchKind.Similar) }
        }
        return ranked.sortedWith(compareBy({ it.rank }, { it.distance }, { it.order })).map { it.result }
    }

    private class Match(val rank: Int, val parts: List<Int>)

    private fun bestMatch(verse: SearchVerse, needle: String, words: List<String>): Match? {
        var best: Match? = null
        for (form in verse.forms) {
            val match = matchIn(form, needle, words) ?: continue
            if (best == null || match.rank < best.rank) best = match
        }
        return best
    }

    private fun matchIn(form: SearchForm, needle: String, words: List<String>): Match? {
        form.find(needle)?.let { (at, whole) ->
            val parts = (form.partAt(at)..form.partAt(at + needle.length - 1)).toList()
            return Match(if (whole) 0 else 1, parts)
        }
        if (words.size < 2) return null
        var allWhole = true
        val offsets = words.map { word ->
            val at = form.text.indexOf(word)
            if (at < 0) return null
            if (form.words.none { it.first == word }) allWhole = false
            at
        }
        return Match(if (allWhole) 3 else 4, offsets.map { form.partAt(it) }.distinct().sorted())
    }

    private fun resultOf(verse: SearchVerse, parts: List<Int>, kind: QuranSearchKind): QuranSearchResult {
        val shown = parts.map { verse.fragments[it] }
        return QuranSearchResult(
            surahs[verse.surah - 1].name, shown.joinToString(" ") { it.text }, shown.first().page, listOf(verse.reference), kind,
        )
    }

    private fun similar(words: List<String>, skip: Set<QuranVerseReference>): List<Ranked> {
        val allowed = words.map { allowedEdits(it.length) }
        if (allowed.all { it == 0 }) return emptyList()
        val found = ArrayList<Ranked>()
        for (verse in verses) {
            if (verse.reference in skip) continue
            var best: Ranked? = null
            for (form in verse.forms) {
                var total = 0
                val parts = sortedSetOf<Int>()
                val everyWord = words.indices.all { index ->
                    val near = form.words.mapNotNull { (word, part) -> withinEdits(word, words[index], allowed[index])?.let { it to part } }
                        .minByOrNull { it.first } ?: return@all false
                    total += near.first
                    parts += near.second
                    true
                }
                if (everyWord && (best == null || total < best.distance)) {
                    best = Ranked(5, total, order(verse.reference), resultOf(verse, parts.toList(), QuranSearchKind.Similar))
                }
            }
            best?.let { found += it }
        }
        return found.sortedWith(compareBy({ it.distance }, { it.order })).take(SIMILAR_LIMIT)
    }

    /** «2:25», «2 25», «البقرة 25» or «سورة البقرة 25»; digits typed in either script are read. */
    private fun referenceOf(needle: String): QuranVerseReference? {
        val tokens = needle.split(' ').filter { it.isNotEmpty() }.let { if (it.firstOrNull() == "سوره") it.drop(1) else it }
        if (tokens.size < 2) return null
        val ayah = numberOf(tokens.last()) ?: return null
        val head = tokens.dropLast(1)
        val surah = if (head.size == 1 && numberOf(head[0]) != null) numberOf(head[0]) else surahsByName[head.joinToString(" ")]
        return surah?.let { QuranVerseReference(it, ayah) }?.takeIf { ayah >= 1 && it in versesByReference }
    }

    private fun numberOf(token: String): Int? {
        if (token.isEmpty() || token.length > 4 || !token.all { Character.isDigit(it) }) return null
        return token.fold(0) { value, char -> value * 10 + Character.digit(char, 10) }
    }

    /** Mushaf order, except that the unnumbered basmalahs, which every chapter repeats, come last. */
    private fun order(reference: QuranVerseReference) =
        if (reference.ayah == 0) BASMALAH_ORDER + reference.surah else reference.surah * 1000 + reference.ayah

    private companion object {
        const val BASMALAH_ORDER = 1_000_000
        const val SIMILAR_BELOW = 3
        const val SIMILAR_LIMIT = 40
    }
}

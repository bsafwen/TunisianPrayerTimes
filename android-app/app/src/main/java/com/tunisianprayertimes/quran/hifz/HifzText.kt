package com.tunisianprayertimes.quran.hifz

import com.tunisianprayertimes.quran.QuranCatalog
import com.tunisianprayertimes.quran.QuranMushafText
import com.tunisianprayertimes.quran.QuranVerseReference
import com.tunisianprayertimes.quran.audio.QuranPlaylist
import com.tunisianprayertimes.quran.audio.QuranRepeatRange
import com.tunisianprayertimes.quran.normalizeQuranSearch

/**
 * A verse's words as printed, in the mushaf's spelling with their marks ([QuranMushafText]), and as compared:
 * without marks, in plain letters, taken from the bundled text itself. A token that is only a mark stays with the
 * word before it; a waqf sign alone goes.
 */
class HifzWords(val printed: List<String>, val keys: List<String>) {
    val size: Int get() = keys.size
    fun text(from: Int = 0, to: Int = size): String = printed.subList(from.coerceIn(0, size), to.coerceIn(0, size)).joinToString(" ")
}

fun hifzWords(text: String): HifzWords {
    val printed = mutableListOf<String>()
    val keys = mutableListOf<String>()
    var pending = ""
    val tokens = text.split(' ').filter { it.isNotBlank() }
    tokens.zip(QuranMushafText.words(tokens)).forEach { (token, shown) ->
        val key = normalizeQuranSearch(token).replace(" ", "")
        when {
            key.isNotEmpty() -> { printed += pending + shown; keys += key; pending = "" }
            shown.isEmpty() -> {}
            printed.isNotEmpty() -> printed[printed.lastIndex] = printed.last() + " " + shown
            else -> pending += "$shown "
        }
    }
    return HifzWords(printed, keys)
}

/** How two verses' words meet: shared opening, shared closing, and the longest shared run. */
data class HifzOverlap(val prefix: Int, val suffix: Int, val run: Int, val runA: Int, val runB: Int)

fun hifzOverlap(a: List<String>, b: List<String>): HifzOverlap {
    var prefix = 0
    while (prefix < a.size && prefix < b.size && a[prefix] == b[prefix]) prefix++
    var suffix = 0
    while (suffix < a.size && suffix < b.size && a[a.size - 1 - suffix] == b[b.size - 1 - suffix]) suffix++
    var run = 0
    var runA = 0
    var runB = 0
    var previous = IntArray(b.size + 1)
    for (i in 1..a.size) {
        val row = IntArray(b.size + 1)
        for (j in 1..b.size) {
            if (a[i - 1] == b[j - 1]) {
                row[j] = previous[j - 1] + 1
                if (row[j] > run) { run = row[j]; runA = i - row[j]; runB = j - row[j] }
            }
        }
        previous = row
    }
    return HifzOverlap(prefix, suffix, run, runA, runB)
}

/** The words of each verse that the other does not share, from a word-level alignment. */
fun hifzDifferences(a: List<String>, b: List<String>): Pair<Set<Int>, Set<Int>> {
    val lengths = Array(a.size + 1) { IntArray(b.size + 1) }
    for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
        lengths[i][j] = if (a[i] == b[j]) lengths[i + 1][j + 1] + 1 else maxOf(lengths[i + 1][j], lengths[i][j + 1])
    }
    val keptA = mutableSetOf<Int>()
    val keptB = mutableSetOf<Int>()
    var i = 0
    var j = 0
    while (i < a.size && j < b.size) {
        when {
            a[i] == b[j] -> { keptA += i; keptB += j; i++; j++ }
            lengths[i + 1][j] >= lengths[i][j + 1] -> i++
            else -> j++
        }
    }
    return (a.indices.toSet() - keptA) to (b.indices.toSet() - keptB)
}

/**
 * How verses open, to show the fewest opening words that tell one from every other. Only first words are
 * read when it is built, which is quick; a verse's other words are read when it is asked about. Use it
 * from one thread.
 */
class HifzOpenings(catalog: QuranCatalog) {
    private val texts: Map<QuranVerseReference, String> = catalog.verses.filter { it.ayah > 0 }.associate { it.reference to it.text }
    private val byFirstWord: Map<String, List<QuranVerseReference>> = texts.entries.groupBy({ firstKey(it.value) }, { it.key })
    private val keys = HashMap<QuranVerseReference, List<String>>()

    /** The verse's words as compared: without marks, in plain letters. */
    fun words(verse: QuranVerseReference): List<String> = keys.getOrPut(verse) { texts[verse]?.let { hifzWords(it).keys }.orEmpty() }

    /** The verse's first [count] words as printed, with their marks. */
    fun printed(verse: QuranVerseReference, count: Int): String? = texts[verse]?.let { hifzWords(it).text(0, count) }

    /**
     * The fewest opening words no other verse starts with, at least one: what a prompt shows of [verse].
     * Its whole length when another verse begins with all of its words; 0 for a verse it does not know.
     */
    fun distinct(verse: QuranVerseReference): Int {
        val own = words(verse)
        if (own.isEmpty()) return 0
        var others = byFirstWord[own.first()].orEmpty().filter { it != verse }
        var count = 1
        while (others.isNotEmpty() && count < own.size) {
            others = others.filter { words(it).size > count && words(it)[count] == own[count] }
            count++
        }
        return count
    }

    private companion object {
        /** A verse's first word as compared; a token that is only a mark is not a word. */
        fun firstKey(text: String): String =
            text.split(' ').firstNotNullOfOrNull { token -> normalizeQuranSearch(token).replace(" ", "").takeIf { it.isNotEmpty() } }.orEmpty()
    }
}

/** The look-alike index, built once per process on a background dispatcher. */
object HifzLookAlikesCache {
    @Volatile var loaded: HifzLookAlikes? = null
        private set

    fun get(catalog: QuranCatalog): HifzLookAlikes = loaded ?: synchronized(this) {
        loaded ?: HifzLookAlikes(catalog).also { loaded = it }
    }
}

/** A verse that may be mistaken for another, and why. */
data class HifzLookAlike(val verse: QuranVerseReference, val overlap: HifzOverlap, val score: Int)

/**
 * Look-alikes computed from the app's own Qaloun text. Candidates share at least two words in a
 * row with the verse, found through an index of word pairs, so a query reads only a few verses.
 */
class HifzLookAlikes(private val catalog: QuranCatalog) {
    private val references: List<QuranVerseReference>
    private val words: List<List<String>>
    private val position: Map<QuranVerseReference, Int>
    private val pairs: Map<String, IntArray>

    init {
        val numbered = catalog.verses.filter { it.ayah > 0 }.sortedBy { it.reference }
        references = numbered.map { it.reference }
        words = numbered.map { hifzWords(it.text).keys }
        position = references.withIndex().associate { it.value to it.index }
        val index = HashMap<String, MutableList<Int>>()
        words.forEachIndexed { verse, keys ->
            keys.zipWithNext { x, y -> "$x $y" }.distinct().forEach { index.getOrPut(it) { mutableListOf() } += verse }
        }
        pairs = index.mapValues { it.value.toIntArray() }
    }

    fun words(verse: QuranVerseReference): List<String> = position[verse]?.let(words::get).orEmpty()

    /** Verses the recitation could drift into: a long shared opening first, then a long shared run. */
    fun similar(verse: QuranVerseReference, limit: Int = 3): List<HifzLookAlike> = rank(verse, limit) { a, _, overlap ->
        val enough = overlap.prefix >= 2 || overlap.run >= minOf(4, a.size - 1).coerceAtLeast(2)
        // A long shared run decides; a shared opening of 2 words or more counts twice, since recitation
        // drifts from where the verses start alike. A one-word opening («ومن», «إن») counts for nothing.
        if (!enough) null else (if (overlap.prefix >= 2) overlap.prefix else 0) + overlap.run
    }

    /** Verses whose ending the recitation could take: a shared run right before differing last words. */
    fun endings(verse: QuranVerseReference, limit: Int = 3): List<HifzLookAlike> = rank(verse, limit) { a, b, overlap ->
        val endA = overlap.runA + overlap.run
        val endB = overlap.runB + overlap.run
        val beforeEnding = overlap.run >= 2 && endA in (a.size - 3).coerceAtLeast(1) until a.size && endB < b.size &&
            a[endA] != b[endB]
        if (!beforeEnding) null else overlap.run * 10 + (if (endB >= b.size - 3) 5 else 0)
    }

    private fun rank(
        verse: QuranVerseReference,
        limit: Int,
        score: (a: List<String>, b: List<String>, overlap: HifzOverlap) -> Int?,
    ): List<HifzLookAlike> {
        val self = position[verse] ?: return emptyList()
        val a = words[self]
        val candidates = a.zipWithNext { x, y -> "$x $y" }.distinct()
            .flatMap { pairs[it]?.asList().orEmpty() }.toSet() - self
        return candidates.mapNotNull { index ->
            val b = words[index]
            val overlap = hifzOverlap(a, b)
            val value = score(a, b, overlap) ?: return@mapNotNull null
            val other = references[index]
            // Within the same chapter, nearer verses are likelier.
            val nearness = if (other.surah == verse.surah) 3 else 0
            HifzLookAlike(other, overlap, value * 10 + nearness)
        }.sortedWith(compareByDescending<HifzLookAlike> { it.score }.thenBy { kotlin.math.abs(index(it.verse) - self) })
            .take(limit)
    }

    private fun index(verse: QuranVerseReference): Int = position[verse] ?: 0
}

/**
 * The closings a verse may be ended with by mistake, as the reader remembers them: the last two words of the verses
 * around it (its page and the pages either side, then its chapter, the nearest first), each closing once, never its
 * own. Each comes with the nearest verse that ends so.
 */
fun hifzClosings(catalog: QuranCatalog, verse: QuranVerseReference, limit: Int = 8): List<Pair<String, QuranVerseReference>> {
    val own = catalog.verse(verse)?.let { hifzWords(it.text) } ?: return emptyList()
    val ownKey = own.keys.takeLast(2)
    val pages = catalog.pagesForVerse(verse.surah, verse.ayah)
    val nearby = pages.flatMap { page -> (page - 1..page + 1).flatMap { catalog.versesOnPage(it) } }
    val chapter = catalog.verses.filter { it.surah == verse.surah }
    val around = (nearby + chapter).filter { it.ayah > 0 && it.reference != verse }.distinctBy { it.reference }
        .sortedBy { if (it.surah == verse.surah) kotlin.math.abs(it.ayah - verse.ayah) else 10_000 }
    val seen = mutableSetOf<List<String>>()
    val closings = mutableListOf<Pair<String, QuranVerseReference>>()
    for (candidate in around) {
        val words = hifzWords(candidate.text)
        val key = words.keys.takeLast(2)
        if (words.size < 2 || key == ownKey || !seen.add(key)) continue
        closings += words.text(words.size - 2) to candidate.reference
        if (closings.size == limit) break
    }
    return closings
}

/** «كلمة واحدة», «كلمتين», «7 كلمات», «17 كلمة». */
fun hifzWordsLabel(count: Int): String = when (count) {
    1 -> "كلمة واحدة"
    2 -> "كلمتين"
    in 3..10 -> "$count كلمات"
    else -> "$count كلمة"
}

/** «تتفقان في كلمتين متتاليتين», «تتفقان في 4 كلمات متتالية». */
fun hifzRunLabel(count: Int): String = when (count) {
    1 -> "تتفقان في كلمة واحدة"
    2 -> "تتفقان في كلمتين متتاليتين"
    in 3..10 -> "تتفقان في $count كلمات متتالية"
    else -> "تتفقان في $count كلمة متتالية"
}

/** «آية واحدة», «آيتان», «5 آيات», «68 آية». */
fun hifzVersesLabel(count: Int): String = when {
    count == 1 -> "آية واحدة"
    count == 2 -> "آيتان"
    count % 100 in 3..10 -> "$count آيات"
    else -> "$count آية"
}

/** After a preposition: «بعد آيتين», «بعد 5 آيات». */
fun hifzVersesAfterLabel(count: Int): String = if (count == 2) "آيتين" else hifzVersesLabel(count)

/** «آية أخرى», «آيتان أخريان», «3 آيات أخرى», «12 آية أخرى». */
private fun hifzOtherVersesLabel(count: Int): String = when {
    count == 1 -> "آية أخرى"
    count == 2 -> "آيتان أخريان"
    count % 100 in 3..10 -> "$count آيات أخرى"
    else -> "$count آية أخرى"
}

/** Everything a fix card, a hint and a heads-up need, from the index text. */
class HifzFix(
    val point: HifzPoint,
    val words: HifzWords,
    val otherWords: HifzWords?,
    /** Words of the verse the other does not share. */
    val differs: Set<Int>,
    val otherDiffers: Set<Int>,
    /** The first word of the verse after the part they share: «هنا يفترقان». */
    val partAt: Int?,
    /** For a skip or a hesitation at the start: the verses around it. */
    val before: HifzWords?,
    val after: HifzWords?,
    /** «لماذا يلتبس؟», facts a script can prove. */
    val reasons: List<String>,
    /** Where an ending is said from: the few words it shares with its look-alike just before they part. */
    private val endingFrom: Int? = null,
    /** The first word of [otherWords] after the part the verses share, where the look-alike goes its own way. */
    val otherPartAt: Int? = null,
) {
    /**
     * Where an ending is said from: the words shared with its look-alike before they part, or else the clause it
     * closes, from «وَاللَّهُ», «فَإِنَّ», «إِنَّ», never from the middle of it («كُلِّ شَيْءٍ قَدِيرٌ»).
     */
    private val endingStart: Int
        get() = endingFrom ?: hifzClauseStart(words, ((partAt ?: (words.size - 1)) - 2).coerceAtLeast(0))

    /** The words that tell the verse from its look-alike: from where they part, never the whole verse. */
    val focus: Set<Int> get() = partAt?.let { at -> differs.filter { it >= at }.toSet() }?.ifEmpty { null } ?: differs

    /** The look-alike's own words from where it parts. */
    val otherFocus: Set<Int> get() = otherPartAt?.let { at -> otherDiffers.filter { it >= at }.toSet() }?.ifEmpty { null } ?: otherDiffers

    /** The wording to keep in mind, never the wrong one. */
    val wording: String
        get() = when (point.kind) {
            // As the ending is said: «فَهُمْ لَا يَرْجِعُونَ», «وَاللَّهُ عَلَىٰ كُلِّ شَيْءٍ قَدِيرٌ».
            HifzKind.Ending -> words.text(endingStart)
            HifzKind.Similar -> partAt?.let { words.text(it, it + 5) + if (it + 5 < words.size) " …" else "" } ?: words.text(0, 5)
            HifzKind.Skip, HifzKind.Start -> {
                val shown = (distinctAt + 1).coerceAtLeast(4)
                words.text(0, shown) + if (shown < words.size) " …" else ""
            }
            // Each wrong word with its neighbours; words far apart are joined by «…».
            HifzKind.Word -> wordingWords.withIndex().joinToString(" ") { (n, i) ->
                if (n > 0 && i > wordingWords[n - 1] + 1) "… ${words.printed[i]}" else words.printed[i]
            }
        }

    /**
     * The words [wording] shows, as indexes of [words], in order; a gap between two of them is the «…» of a
     * word slip.
     */
    val wordingWords: List<Int>
        get() = when (point.kind) {
            HifzKind.Ending -> (endingStart until words.size).toList()
            HifzKind.Similar -> (partAt?.let { it until minOf(it + 5, words.size) } ?: (0 until minOf(5, words.size))).toList()
            HifzKind.Skip, HifzKind.Start -> (0 until minOf((distinctAt + 1).coerceAtLeast(4), words.size)).toList()
            HifzKind.Word -> markedWords.flatMap { (it - 1).coerceAtLeast(0) until minOf(it + 2, words.size) }.distinct().sorted()
                .ifEmpty { (0 until minOf(4, words.size)).toList() }
        }

    /** A word slip's words that are in the verse. */
    private val markedWords: List<Int> get() = point.slipWords.filter { it < words.size }.sorted()

    /** The words of [wording] to mind: the right words, or where the verse parts from its look-alike. */
    val stressed: Set<Int>
        get() = when (point.kind) {
            HifzKind.Word -> markedWords.toSet()
            HifzKind.Ending, HifzKind.Similar -> focus
            HifzKind.Skip, HifzKind.Start -> emptySet()
        }

    /** «خاتمتها», «بعد «فَقَدِ»», «أولها». */
    val wordingLabel: String
        get() = when (point.kind) {
            HifzKind.Ending -> "خاتمتها"
            HifzKind.Similar -> partAt?.takeIf { it > 0 }?.let { "بعد «${words.printed[it - 1]}»" } ?: "أولها"
            HifzKind.Skip -> if (point.verse.ayah == 1) "أول السورة" else "لا تتجاوزها"
            HifzKind.Start -> "أولها"
            HifzKind.Word -> "انتبه إلى"
        }

    /** The first word of the verse that its neighbours do not open with. */
    val distinctAt: Int
        get() {
            val shared = listOfNotNull(before, after).maxOfOrNull { hifzOverlap(words.keys, it.keys).prefix } ?: 0
            return shared.coerceAtMost(words.size - 1)
        }

    /**
     * What a review hides before «أظهر», as word indexes; null for a skip or a hesitation, whose review
     * hides the whole verse. Never empty: the answer is not shown before it is asked.
     */
    val hidden: Set<Int>?
        get() {
            val all = (0 until words.size).toSet()
            // A one-word verse («والفجر») has nothing to keep visible: the whole verse is the answer.
            if (words.size < 2 && point.kind != HifzKind.Skip && point.kind != HifzKind.Start) return all
            val hidden = when (point.kind) {
                HifzKind.Ending -> (partAt ?: (words.size - 2)).coerceIn(1, words.size - 1).let { it until words.size }.toSet()
                HifzKind.Similar -> (partAt ?: 1).coerceIn(1, words.size - 1).let { it until words.size }.toSet()
                HifzKind.Word -> markedWords.toSet()
                HifzKind.Skip, HifzKind.Start -> return null
            }
            return hidden.ifEmpty { all }
        }

    /** The review's question. */
    val question: String
        get() = when (point.kind) {
            HifzKind.Ending -> "بماذا تختم هذه الآية؟ قلها بصوتك ثم اضغط «أظهر»"
            HifzKind.Similar -> "كيف تكمل هذه الآية؟ قلها بصوتك ثم اضغط «أظهر»"
            HifzKind.Word -> when (markedWords.size) {
                0 -> "اقرأ الآية ${point.verse.ayah} من حفظك، ثم اضغط «أظهر»"
                1 -> "ما الكلمة المخفية؟ قلها بصوتك ثم اضغط «أظهر»"
                else -> "ما الكلمات المخفية؟ قلها بصوتك ثم اضغط «أظهر»"
            }
            HifzKind.Skip -> if (point.verse.ayah == 1) "ما أول آية في هذه السورة؟ قلها ثم اضغط «أظهر»"
                else "ما الآية التي تأتي بعد الآية ${point.verse.ayah - 1}؟ قلها ثم اضغط «أظهر»"
            HifzKind.Start -> "كيف تبدأ الآية ${point.verse.ayah}؟ قلها ثم اضغط «أظهر»"
        }

    /**
     * The reminder's line for a point, fresh or due a check: what will be asked, never its answer, which the review hides
     * until «أظهر».
     */
    val digestPrompt: String
        get() = when (point.kind) {
            HifzKind.Ending -> "بماذا تختم؟"
            HifzKind.Similar -> partAt?.takeIf { it > 0 }?.let { "ماذا بعد «${words.printed[it - 1]}»؟" } ?: "كيف تكملها؟"
            HifzKind.Word -> when (markedWords.size) {
                0 -> "اقرأها من حفظك"
                1 -> "ما الكلمة الصحيحة؟"
                else -> "ما الكلمات الصحيحة؟"
            }
            HifzKind.Skip -> if (point.verse.ayah == 1) "ما أول آية في السورة؟" else "ما الآية بعد الآية ${point.verse.ayah - 1}؟"
            HifzKind.Start -> "كيف تبدأ؟"
        }
}

/**
 * The start of the clause a verse's last words close, looking back at most 3 words from [from]: a word that opens with
 * «و» or «ف», or «إن» and «إنه». [from] itself when none is that near.
 */
internal fun hifzClauseStart(words: HifzWords, from: Int): Int {
    val openers = setOf("ان", "انه", "انهم", "انا")
    for (i in (from - 1) downTo (from - 3).coerceAtLeast(0)) {
        val key = words.keys[i]
        if (key in openers || (key.length > 2 && (key.startsWith('و') || key.startsWith('ف')))) return i
    }
    return from
}

/** Builds the fix of [point] from the index; null when its verse is not in it. */
fun hifzFix(catalog: QuranCatalog, point: HifzPoint, lookAlikes: HifzLookAlikes? = null): HifzFix? {
    val verse = catalog.verse(point.verse) ?: return null
    val words = hifzWords(verse.text)
    val surahName = { n: Int -> catalog.surahs.getOrNull(n - 1)?.name.orEmpty() }
    val reasons = mutableListOf<String>()
    fun near(n: Int) = catalog.verse(point.verse.surah, n)?.takeIf { n >= 1 }?.let { hifzWords(it.text) }
    return when (point.kind) {
        HifzKind.Ending, HifzKind.Similar -> {
            val other = point.other?.let { catalog.verse(it) }?.let { hifzWords(it.text) }
            if (other == null) {
                return HifzFix(point, words, null, emptySet(), emptySet(), point.word, null, null, emptyList())
            }
            val overlap = hifzOverlap(words.keys, other.keys)
            val (differs, otherDiffers) = hifzDifferences(words.keys, other.keys)
            val runEnd = overlap.runA + overlap.run
            val partAt = when {
                point.word != null -> point.word
                point.kind == HifzKind.Similar && overlap.prefix in 2 until words.size -> overlap.prefix
                // No shared opening: the verses part after the words they share in the middle.
                point.kind == HifzKind.Similar && overlap.run > 0 && runEnd < words.size -> runEnd
                // Where the shared run before the ending stops, as the record sheet suggests it.
                point.kind == HifzKind.Ending && runEnd < words.size && overlap.run > 0 && runEnd >= words.size - 3 -> runEnd
                // The verses end alike: they part on the differing words just before that shared ending.
                point.kind == HifzKind.Ending && overlap.suffix in 1 until words.size -> {
                    val last = words.size - overlap.suffix - 1
                    (last downTo 0).takeWhile { it in differs }.lastOrNull() ?: last
                }
                point.kind == HifzKind.Ending -> (words.size - 2).coerceAtLeast(1)
                else -> differs.minOrNull()
            }
            when {
                overlap.prefix == words.size && words.size == other.size -> reasons += "الآيتان متطابقتان؛ الفرق فيما يأتي بعد كل منهما"
                // Each fact is said only where it is true of the text.
                point.kind == HifzKind.Similar && overlap.prefix >= 2 && partAt == overlap.prefix ->
                    reasons += "تتفقان في أول ${hifzWordsLabel(overlap.prefix)}، وتفترقان بعد كلمة «${words.printed[partAt - 1]}»"
                point.kind == HifzKind.Ending && overlap.run > 0 && runEnd == partAt && runEnd >= words.size - 3 ->
                    reasons += "تتفقان في ${hifzWordsLabel(overlap.run)} قبل الخاتمة"
                overlap.run > 1 -> reasons += "تشتركان في " + hifzRunLabel(overlap.run).removePrefix("تتفقان في ")
            }
            val otherRef = point.other!!
            val between = kotlin.math.abs(otherRef.ayah - point.verse.ayah) - 1
            when {
                otherRef.surah != point.verse.surah -> reasons += "الأخرى في سورة ${surahName(otherRef.surah)}"
                between == 0 -> reasons += "في السورة نفسها، متتاليتان"
                between > 0 -> reasons += "في السورة نفسها، بينهما ${hifzVersesLabel(between)}"
            }
            if (point.bothWays) reasons += "قد تخلط بينهما في الاتجاهين، فهي تُراجَع عند الآيتين"
            // Where the look-alike parts, matched to the verse's own parting.
            val otherPartAt = when {
                partAt == null -> null
                overlap.run > 0 && partAt == runEnd -> overlap.runB + overlap.run
                partAt == overlap.prefix -> overlap.prefix
                point.kind == HifzKind.Ending -> (other.size - (words.size - partAt)).coerceIn(0, other.size)
                else -> null
            }
            // A short shared lead-in is how the ending is said: «وَاللَّهُ غَنِيٌّ حَلِيمٌ».
            val endingFrom = overlap.runA.takeIf { point.kind == HifzKind.Ending && overlap.run in 1..4 && partAt == runEnd }
            HifzFix(point, words, other, differs, otherDiffers, partAt, null, null, reasons, endingFrom, otherPartAt)
        }
        HifzKind.Skip -> {
            val before = near(point.verse.ayah - 1)
            val landed = point.other?.takeIf { it.surah == point.verse.surah }?.ayah ?: (point.verse.ayah + 1)
            val after = near(landed)
            val group = listOfNotNull(before, words, after)
            val shared = group.drop(1).minOfOrNull { hifzOverlap(group.first().keys, it.keys).prefix } ?: 0
            if (group.size == 3 && shared >= 2) {
                reasons += "${point.verse.ayah - 1} و${point.verse.ayah} و$landed تشترك في أول ${hifzWordsLabel(shared)}، ثم تفترق"
            } else {
                listOfNotNull(before?.let { point.verse.ayah - 1 to it }, after?.let { landed to it }).forEach { (n, other) ->
                    val p = hifzOverlap(words.keys, other.keys).prefix
                    if (p >= 2) reasons += "${point.verse.ayah} و$n تشتركان في أول ${hifzWordsLabel(p)}"
                }
            }
            val landedPages = catalog.pagesForVerse(point.verse.surah, landed)
            val pages = catalog.pagesForVerse(point.verse.surah, point.verse.ayah)
            // A page turn between them: one sentence, the skipped verse first.
            val endsPage = pages.size == 1 && landedPages.firstOrNull() != pages.first() &&
                catalog.versesOnPage(pages.first()).lastOrNull { it.ayah > 0 }?.reference == point.verse
            val opensPage = landedPages.isNotEmpty() && pages.isNotEmpty() && landedPages.first() != pages.last() &&
                catalog.versesOnPage(landedPages.first()).firstOrNull { it.ayah > 0 }?.let { it.surah == point.verse.surah && it.ayah == landed } == true
            val endsText = "${point.verse.ayah} آخر آية في الصفحة ${pages.firstOrNull()?.plus(1)}"
            val opensText = "$landed في أول الصفحة ${landedPages.firstOrNull()?.plus(1)}"
            when {
                endsPage && opensPage -> reasons += "$endsText، و$opensText"
                endsPage -> reasons += endsText
                opensPage -> reasons += opensText
            }
            HifzFix(point, words, null, emptySet(), emptySet(), null, before, after, reasons.distinct())
        }
        HifzKind.Start -> {
            val before = near(point.verse.ayah - 1)
            val opening = words.keys.take(2)
            if (opening.size == 2) {
                val others = lookAlikes?.let { index ->
                    catalog.verses.count { it.ayah > 0 && it.reference != point.verse && index.words(it.reference).take(2) == opening }
                } ?: 0
                if (others > 0) reasons += "${hifzOtherVersesLabel(others)} في المصحف ${if (others == 2) "تبدآن" else "تبدأ"} بالكلمتين نفسيهما"
            }
            if (point.verse.ayah == 1) reasons += "أول سورة ${surahName(point.verse.surah)}"
            HifzFix(point, words, null, emptySet(), emptySet(), null, before, near(point.verse.ayah + 1), reasons)
        }
        HifzKind.Word -> {
            if (point.hafs) reasons += "قرأتها كما في رواية حفص؛ وهي هنا برواية قالون"
            HifzFix(point, words, null, emptySet(), emptySet(), point.word, null, null, reasons)
        }
    }
}

/** «البقرة 17», «البقرة 67 ← 68 ← 69». */
fun hifzRangeLabel(surahName: String, range: QuranRepeatRange): String =
    if (range.from == range.to) "$surahName ${range.from.ayah}"
    else "$surahName " + (range.from.ayah..range.to.ayah).joinToString(" ← ")

/** The passage that holds a point's fix: the verse, with its neighbours for a skip or a hesitation. */
fun hifzPassage(catalog: QuranCatalog, point: HifzPoint): QuranRepeatRange {
    val count = catalog.surahs.getOrNull(point.verse.surah - 1)?.verseCount ?: point.verse.ayah
    val v = point.verse
    return when (point.kind) {
        HifzKind.Skip -> {
            val landed = point.other?.takeIf { it.surah == v.surah && it.ayah > v.ayah }?.ayah ?: (v.ayah + 1)
            QuranRepeatRange(QuranVerseReference(v.surah, (v.ayah - 1).coerceAtLeast(1)), QuranVerseReference(v.surah, landed.coerceAtMost(count)), 1)
        }
        HifzKind.Start -> QuranRepeatRange(QuranVerseReference(v.surah, (v.ayah - 1).coerceAtLeast(1)), v, 1)
        else -> QuranRepeatRange(v, v, 1)
    }
}

/** The title of a drill's playlist, in the dock and the notification. */
const val HifzDrillTitle = "تدريب"
/** The title of one point's right wording heard once. */
const val HifzListenOneTitle = "الاستماع إلى الموضع"
/** The title of «مواضعي صوتا», several points heard in turn. */
const val HifzListenTitle = "مواضعي صوتا"
/** The title of the verse before a point, heard once as a cue. */
const val HifzCueTitle = "ما قبل الموضع"

/**
 * «درّب»: the right verse and its look-alike in turn, the right one first and last (5 recitations, never the
 * same verse twice in a row); or the right verse or the passage around a skip, 3 times. Each recitation is
 * followed by the drill's silence, to repeat it.
 */
fun hifzDrill(catalog: QuranCatalog, point: HifzPoint, settings: HifzSettings): QuranPlaylist {
    val name = { n: Int -> catalog.surahs.getOrNull(n - 1)?.name.orEmpty() }
    val own = hifzPassage(catalog, point)
    val other = point.other?.takeIf { point.kind == HifzKind.Ending || point.kind == HifzKind.Similar }
    val ownLabel = hifzRangeLabel(name(own.from.surah), own)
    val (items, labels) = if (other != null) {
        val look = QuranRepeatRange(other, other, 1)
        val right = "الصواب · $ownLabel"
        val wrong = "الشبيهة · ${hifzRangeLabel(name(other.surah), look)}"
        listOf(own, look, own, look, own) to listOf(right, wrong, right, wrong, right)
    } else List(3) { own } to List(3) { ownLabel }
    return QuranPlaylist(
        title = HifzDrillTitle,
        items = items,
        labels = labels,
        rounds = 1,
        gapFactor = settings.drillGap,
        speed = if (point.hafs) 0.75f else settings.drillSpeed.takeIf { it != 1f },
    )
}

/**
 * «مواضعي صوتا»: every fix in turn, the right wording only, straight through; one point's is «الاستماع إلى الموضع».
 * The drill's silence is for «درّب» alone, as its setting says.
 */
fun hifzListen(catalog: QuranCatalog, points: List<HifzPoint>, settings: HifzSettings): QuranPlaylist? {
    if (points.isEmpty()) return null
    val name = { n: Int -> catalog.surahs.getOrNull(n - 1)?.name.orEmpty() }
    val items = points.sortedBy { it.verse }.map { hifzPassage(catalog, it) }.distinct()
    return QuranPlaylist(
        title = if (points.size == 1) HifzListenOneTitle else HifzListenTitle,
        items = items,
        labels = items.map { hifzRangeLabel(name(it.from.surah), it) },
        rounds = 1,
        gapFactor = 0f,
        speed = if (points.size == 1 && points.single().hafs) 0.75f else settings.drillSpeed.takeIf { it != 1f },
    )
}

/**
 * The verse before [point], heard once as a cue before reciting the point from memory: whatever the listener's
 * own repetition and silence settings, it never repeats. Null for a first verse.
 */
fun hifzCue(catalog: QuranCatalog, point: HifzPoint, settings: HifzSettings): QuranPlaylist? {
    if (point.verse.ayah <= 1) return null
    val before = QuranVerseReference(point.verse.surah, point.verse.ayah - 1)
    val range = QuranRepeatRange(before, before, 1)
    return QuranPlaylist(
        title = HifzCueTitle,
        items = listOf(range),
        labels = listOf(hifzRangeLabel(catalog.surahs.getOrNull(before.surah - 1)?.name.orEmpty(), range)),
        rounds = 1,
        gapFactor = 0f,
        speed = settings.drillSpeed.takeIf { it != 1f },
    )
}

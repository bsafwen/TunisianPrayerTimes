package com.tunisianprayertimes.mosque

import com.tunisianprayertimes.adhkar.DhikrCatalog
import com.tunisianprayertimes.adhkar.DhikrCategory
import com.tunisianprayertimes.adhkar.DhikrEntry
import com.tunisianprayertimes.adhkar.countForCollection
import com.tunisianprayertimes.adhkar.referenceForCollection
import kotlin.math.abs
import kotlin.math.ceil

/**
 * One screen of the mosque's adhkar: [text] to be said [count] times, with its [reference].
 * [entryId] is the reviewed catalog entry, or null for a mosque's own text from the USB file.
 * A multi-part item (the 33/33/33/1 tasbih) or a long text shows as several slides: [part] of [parts].
 */
data class AdhkarSlide(
    val entryId: String?,
    val text: String,
    val reference: String,
    val count: Int,
    val durationMillis: Long,
    val part: Int = 1,
    val parts: Int = 1,
)

/** One line of the adhan screen: what the listener says. A [fajrOnly] line answers the phrase only Fajr's adhan has. */
data class AdhanReply(val text: String, val fajrOnly: Boolean = false)

/** One text of a mosque's list: a reviewed text of the catalog, or the mosque's own. */
sealed interface MosqueDhikr

/**
 * A reviewed catalog entry, by its [id]: its text and source come from the catalog, exactly as
 * reviewed. [count] changes how many times it is said after the prayer; null is the catalog's count.
 */
data class ReviewedDhikr(val id: String, val count: Int? = null) : MosqueDhikr

/** A mosque's own dhikr or dua; a [reference] is required so every text is sourced. */
data class CustomDhikr(val text: String, val reference: String, val count: Int = 1) : MosqueDhikr

/**
 * A mosque's list: its [items] after the bundled texts, or in their place. Replacing is also how a
 * bundled text is hidden or moved: the list then names the reviewed texts it keeps, in its order.
 */
data class CustomAdhkarList(val mode: Mode, val items: List<MosqueDhikr>) {
    enum class Mode { REPLACE, APPEND }
}

/** What a mosque changed in the texts; null lists are the bundled, reviewed texts. */
data class AdhkarContent(val afterSalah: CustomAdhkarList? = null, val ticker: CustomAdhkarList? = null) {
    val isBundled: Boolean get() = afterSalah == null && ticker == null
}

/**
 * The texts a mosque screen shows, all from the shared reviewed catalog (the phone's) unless the
 * mosque replaced or extended them. Every sequence plays once, in order, paced by its length.
 */
object MosqueAdhkar {

    /**
     * What the listener says while the muezzin calls, in the adhan's order, all shown at once for the
     * whole adhan screen (the wall cannot follow the muezzin, so nothing is paced against the call).
     * The hadith of ʿUmar ibn al-Khaṭṭāb, Muslim 385 (quoted whole in the narrations of
     * "adhan_response"): the listener repeats each phrase, except at the two «حيّ على» where he says
     * «لا حول ولا قوة إلا بالله». Only the replies: the muezzin does not follow the screen, so no line
     * names the phrase it answers. Each reply comes as many times as the muezzin says its phrase: twice
     * for each shahada and each «حيّ على». The two Fajr-only lines answering «الصلاة خير من النوم» are
     * the owner's editorial choice of 2026-09-30, by analogy with the two «حيّ على»: no narration gives
     * a reply to it, so they are not attributed to Muslim 385 (docs/adhkar-sources.md).
     * The reply to the shahada (Muslim 386) stays in the catalog for the phone app; the dua after the
     * adhan (Bukhari 614) follows these replies on a screen of its own ([adhanDua]).
     */
    val ADHAN_REPLIES: List<AdhanReply> = listOf(
        AdhanReply("اللَّهُ أَكْبَرُ اللَّهُ أَكْبَرُ"),
        AdhanReply("أَشْهَدُ أَنْ لَا إِلَهَ إِلَّا اللَّهُ"),
        AdhanReply("أَشْهَدُ أَنْ لَا إِلَهَ إِلَّا اللَّهُ"),
        AdhanReply("أَشْهَدُ أَنَّ مُحَمَّدًا رَسُولُ اللَّهِ"),
        AdhanReply("أَشْهَدُ أَنَّ مُحَمَّدًا رَسُولُ اللَّهِ"),
        AdhanReply("لَا حَوْلَ وَلَا قُوَّةَ إِلَّا بِاللَّهِ"),
        AdhanReply("لَا حَوْلَ وَلَا قُوَّةَ إِلَّا بِاللَّهِ"),
        AdhanReply("لَا حَوْلَ وَلَا قُوَّةَ إِلَّا بِاللَّهِ"),
        AdhanReply("لَا حَوْلَ وَلَا قُوَّةَ إِلَّا بِاللَّهِ"),
        AdhanReply("لَا حَوْلَ وَلَا قُوَّةَ إِلَّا بِاللَّهِ", fajrOnly = true),
        AdhanReply("لَا حَوْلَ وَلَا قُوَّةَ إِلَّا بِاللَّهِ", fajrOnly = true),
        AdhanReply("اللَّهُ أَكْبَرُ اللَّهُ أَكْبَرُ"),
        AdhanReply("لَا إِلَهَ إِلَّا اللَّهُ"),
    )

    /** The replies of one adhan: Fajr's has the lines for «الصلاة خير من النوم», the others do not. */
    fun adhanReplies(fajr: Boolean): List<AdhanReply> = ADHAN_REPLIES.filter { fajr || !it.fajrOnly }

    /** The catalog's dua after the adhan: «اللهم رب هذه الدعوة التامة…», Bukhari 614. */
    const val ADHAN_DUA_ID = "after_adhan_wasila"

    /**
     * The dua after the adhan, alone on the screen for a minute once the adhan screen ends
     * (FlowTiming.ADHAN_DUA_MINUTES): the reviewed catalog entry, its text and its reference exactly as
     * reviewed, full stop included. Null only if the catalog lost the entry (a test guards it).
     */
    fun adhanDua(): DhikrEntry? = DhikrCatalog.find(ADHAN_DUA_ID)

    /**
     * The short daily texts of the ticker under the prayer times. It runs all day, so only texts of the
     * day, the morning and the evening: none said only right after the prayer.
     */
    val TICKER_IDS = listOf("subhanallah_bihamdih", "kalimatan_khafifatan", "la_hawla_quwwata", DhikrCatalog.SALAWAT_ID)

    /** The bundled adhkar after the obligatory prayer: the catalog's after-prayer collection. */
    val AFTER_SALAH_IDS: List<String> = DhikrCatalog.collectionOrder[DhikrCategory.SALAH].orEmpty()

    /** The adhkar after the obligatory prayer: the bundled ones, the mosque's list, or both. */
    fun afterSalah(content: AdhkarContent = AdhkarContent()): List<AdhkarSlide> =
        items(AFTER_SALAH_IDS, content.afterSalah).flatMap(::afterSalahSlides)

    /** The texts a list shows, in order: the [bundledIds] as reviewed texts, then or instead the mosque's [custom] ones. */
    fun items(bundledIds: List<String>, custom: CustomAdhkarList?): List<MosqueDhikr> {
        val bundled = bundledIds.map { ReviewedDhikr(it) }
        return when (custom?.mode) {
            null -> bundled
            CustomAdhkarList.Mode.REPLACE -> custom.items
            CustomAdhkarList.Mode.APPEND -> bundled + custom.items
        }
    }

    /** One text as the after-prayer screen shows it; a reviewed id the catalog no longer has shows nothing. */
    fun afterSalahSlides(item: MosqueDhikr): List<AdhkarSlide> = when (item) {
        is ReviewedDhikr -> DhikrCatalog.find(item.id)?.let { slides(it, DhikrCategory.SALAH, item.count) }.orEmpty()
        is CustomDhikr -> ownSlides(item)
    }

    /**
     * One text as the ticker shows it: read once, however many times it is said elsewhere, and on one
     * line (the ticker is a single line: a text written line by line would stand over the arcade).
     */
    fun tickerSlides(item: MosqueDhikr): List<AdhkarSlide> = when (item) {
        is ReviewedDhikr -> DhikrCatalog.find(item.id)?.let { entry ->
            slides(entry.copy(text = oneLine(entry.text)), null, 1).map { it.copy(count = 1, durationMillis = AdhkarPacer.durationMillis(it.text, 1)) }
        }.orEmpty()
        is CustomDhikr -> ownSlides(item.copy(text = oneLine(item.text), count = 1))
    }

    /** [text] with its line breaks and runs of spaces as single spaces. */
    private fun oneLine(text: String): String = text.trim().replace(WHITESPACE, " ")

    private val WHITESPACE = Regex("""\s+""")

    /** The ticker holds every slide at least this long; a long text is set smaller or on two lines, never scrolled. */
    const val TICKER_MIN_SLIDE_MILLIS = 12_000L

    /** About how long one round of the ticker takes for these texts (without the announcements between them). */
    fun tickerMillis(slides: List<AdhkarSlide>): Long = slides.sumOf { maxOf(TICKER_MIN_SLIDE_MILLIS, it.durationMillis) }

    /** The ticker's texts: the bundled ones, the mosque's list, or both. */
    fun ticker(content: AdhkarContent = AdhkarContent()): List<AdhkarSlide> =
        items(TICKER_IDS, content.ticker).flatMap(::tickerSlides)

    /**
     * The ticker with the mosque's written [announcements] between its adhkar, one after every two,
     * each labelled [label] where a dhikr shows its source, and on one line as the texts are. Every
     * announcement comes once per round.
     */
    fun tickerWithAnnouncements(ticker: List<AdhkarSlide>, announcements: List<String>, label: String): List<AdhkarSlide> {
        val news = announcements.map(::oneLine).map { AdhkarSlide(null, it, label, 1, AdhkarPacer.durationMillis(it, 1)) }
        if (news.isEmpty()) return ticker
        if (ticker.isEmpty()) return news
        val result = mutableListOf<AdhkarSlide>()
        var next = 0
        var texts = 0
        for (slide in ticker) {
            result += slide
            // A text ends on its last page or step; one announcement after every two whole texts.
            if (slide.part == slide.parts && ++texts % 2 == 0) result += news[next++ % news.size]
        }
        while (next < news.size) result += news[next++]
        return result
    }

    /** The slide showing [elapsedMillis] into a sequence, or null once it has played through. */
    fun slideAt(slides: List<AdhkarSlide>, elapsedMillis: Long): IndexedValue<AdhkarSlide>? {
        if (elapsedMillis < 0) return slides.withIndex().firstOrNull()
        var end = 0L
        for (slide in slides.withIndex()) {
            end += slide.value.durationMillis
            if (elapsedMillis < end) return slide
        }
        return null
    }

    fun totalMillis(slides: List<AdhkarSlide>): Long = slides.sumOf { it.durationMillis }

    private fun entries(ids: List<String>, category: DhikrCategory?): List<AdhkarSlide> =
        ids.mapNotNull(DhikrCatalog::find).flatMap { slides(it, category, null) }

    /**
     * A mosque's text as slides. A text too long for one screen is shown page by page, and the whole
     * text again for each repetition (at most [MAX_PAGED_REPETITIONS]), so it is said whole each time.
     */
    private fun ownSlides(item: CustomDhikr): List<AdhkarSlide> {
        val pages = AdhkarPacer.pages(item.text)
        if (pages.size == 1) {
            return listOf(AdhkarSlide(null, item.text, item.reference, item.count, AdhkarPacer.durationMillis(item.text, item.count)))
        }
        val once = pages.mapIndexed { index, page -> AdhkarSlide(null, page, item.reference, 1, AdhkarPacer.durationMillis(page, 1), index + 1, pages.size) }
        return List(item.count.coerceIn(1, MAX_PAGED_REPETITIONS)) { once }.flatten()
    }

    const val MAX_PAGED_REPETITIONS = 3

    /**
     * An entry as slides: one per step (33 × three phrases, then the tahlil), or one per page of a
     * long text. [countOverride] is the mosque's count; a text said in steps keeps its own. The source
     * is the part of the entry's that concerns [category], when the catalog gives one.
     */
    private fun slides(entry: DhikrEntry, category: DhikrCategory?, countOverride: Int?): List<AdhkarSlide> {
        val reference = entry.referenceForCollection(category)
        if (entry.steps.isNotEmpty()) {
            return entry.steps.mapIndexed { index, step ->
                AdhkarSlide(entry.id, step.text, reference, step.repetitions,
                    AdhkarPacer.durationMillis(step.text, step.repetitions), index + 1, entry.steps.size)
            }
        }
        val count = countOverride ?: entry.countForCollection(category)
        val pages = AdhkarPacer.pages(entry.text)
        if (pages.size == 1) {
            return listOf(AdhkarSlide(entry.id, entry.text, reference, count, AdhkarPacer.durationMillis(entry.text, count)))
        }
        // A long text is read page by page, and whole again for each repetition (at most three).
        val once = pages.mapIndexed { index, page ->
            AdhkarSlide(entry.id, page, reference, 1, AdhkarPacer.durationMillis(page, 1), index + 1, pages.size)
        }
        return List(count.coerceIn(1, MAX_PAGED_REPETITIONS)) { once }.flatten()
    }
}

/** How long a text stays on screen, and where a long one is split, so the congregation can read it. */
object AdhkarPacer {

    const val MIN_REPETITION_MILLIS = 2_500L
    const val MILLIS_PER_WORD = 450L
    const val MIN_SLIDE_MILLIS = 6_000L
    const val MAX_SLIDE_MILLIS = 150_000L

    /** Longer texts are split into pages of about this many characters. */
    const val PAGE_CHARS = 260

    /**
     * A page holds at most this many lines of a text written line by line (a mosque's own dua, one
     * phrase per line): the after-prayer screen fits them at over half its size, never cut.
     */
    const val PAGE_LINES = 6

    fun durationMillis(text: String, count: Int): Long {
        val words = text.split(Regex("""\s+""")).count { it.isNotBlank() }
        val perRepetition = maxOf(MIN_REPETITION_MILLIS, MILLIS_PER_WORD * words)
        return (perRepetition * count.coerceAtLeast(1)).coerceIn(MIN_SLIDE_MILLIS, MAX_SLIDE_MILLIS)
    }

    /**
     * [text] split into pages of at most [PAGE_CHARS] characters and [PAGE_LINES] lines, never inside
     * a word: after a verse number, a pause mark, punctuation or a line break when there is one in
     * reach, otherwise after a space. The pages are as even as those cuts allow (a text a little too
     * long for one page makes two halves, not a full page and its last clause alone). The pages put
     * together give back [text].
     */
    fun pages(text: String): List<String> {
        if (fill(text, 0, text.length) <= 1.0) return listOf(text)
        val breaks = BREAK.findAll(text).map { it.range.last + 1 }.filter { it in 1 until text.length }.toList()
        val spaces = SPACE.findAll(text).map { it.range.last + 1 }.filter { it in 1 until text.length }.toList()
        val pages = mutableListOf<String>()
        var start = 0
        while (true) {
            val rest = fill(text, start, text.length)
            if (rest <= 1.0) break
            // The rest needs this many pages: this one takes its share, and leaves no more than the others can hold.
            val count = ceil(rest)
            val share = rest / count
            fun fits(cut: Int) = cut > start && fill(text, start, cut) <= 1.0
            fun leaves(cut: Int) = fill(text, cut, text.length) <= count - 1
            fun nearest(cuts: List<Int>, ok: (Int) -> Boolean) = cuts.filter(ok).minByOrNull { abs(fill(text, start, it) - share) }
            val cut = nearest(breaks) { fits(it) && leaves(it) } ?: nearest(spaces) { fits(it) && leaves(it) }
                ?: nearest(breaks, ::fits) ?: nearest(spaces, ::fits)
                ?: break // a single 260-letter word: left whole
            pages += text.substring(start, cut)
            start = cut
        }
        pages += text.substring(start)
        return pages.filter { it.isNotBlank() }.ifEmpty { listOf(text) }
    }

    /** How much of a page [text] from [from] to [to] takes: its share of the characters or of the lines, whichever is more. */
    private fun fill(text: String, from: Int, to: Int): Double {
        val lines = text.substring(from, to).trimEnd().count { it == '\n' } + 1
        return maxOf((to - from).toDouble() / PAGE_CHARS, lines.toDouble() / PAGE_LINES)
    }

    /** After a verse number (۝253), a pause mark (ۚ ۖ ۗ), punctuation or a line break, with the space after it. */
    private val BREAK = Regex("""(۝[0-9٠-٩]+|[ۖۗۚ،,.؛;؟?!:\n]+)\s*""")
    private val SPACE = Regex("""\s+""")
}

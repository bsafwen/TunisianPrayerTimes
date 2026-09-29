package com.tunisianprayertimes.mosque

import com.tunisianprayertimes.adhkar.DhikrCatalog
import com.tunisianprayertimes.adhkar.DhikrCategory
import com.tunisianprayertimes.adhkar.DhikrEntry
import com.tunisianprayertimes.adhkar.countForCollection

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

/** A mosque's own dhikr or dua from the USB file; a [reference] is required so every text is sourced. */
data class CustomDhikr(val text: String, val reference: String, val count: Int = 1)

/** A mosque's texts in place of the bundled ones, or after them. */
data class CustomAdhkarList(val mode: Mode, val items: List<CustomDhikr>) {
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

    /** Said while and after the muezzin calls, in this order. */
    val ADHAN_IDS = listOf("adhan_response", "adhan_shahada", "after_adhan_wasila")

    /** The short daily texts of the ticker under the prayer times. */
    val TICKER_IDS = listOf("salah_salam", "subhanallah_bihamdih", "kalimatan_khafifatan", "la_hawla_quwwata", DhikrCatalog.SALAWAT_ID)

    /** The adhkar after the obligatory prayer: the catalog's after-prayer collection, then the mosque's own. */
    fun afterSalah(content: AdhkarContent = AdhkarContent()): List<AdhkarSlide> =
        combine(entries(DhikrCatalog.collectionOrder[DhikrCategory.SALAH].orEmpty(), DhikrCategory.SALAH), content.afterSalah)

    fun adhanCompanion(): List<AdhkarSlide> = entries(ADHAN_IDS, DhikrCategory.PRAYER)

    /**
     * What to show [elapsedMillis] into an adhan screen of [screenMillis]: the reply to the muezzin
     * while he calls, the shahada after his, and the dua once the call is over. Spread over the
     * screen rather than paced by length, because the adhan itself sets the rhythm.
     */
    fun adhanCompanionAt(elapsedMillis: Long, screenMillis: Long, slides: List<AdhkarSlide> = adhanCompanion()): AdhkarSlide? {
        if (slides.isEmpty()) return null
        val shares = ADHAN_SHARES.take(slides.size).let { if (it.size < slides.size) List(slides.size) { 1.0 } else it }
        val position = elapsedMillis.coerceAtLeast(0).toDouble() / screenMillis.coerceAtLeast(1) * shares.sum()
        var end = 0.0
        shares.forEachIndexed { index, share ->
            end += share
            if (position < end) return slides[index]
        }
        return slides.last()
    }

    /** Half the adhan for the reply, a fifth for the shahada, the rest for the dua after it. */
    private val ADHAN_SHARES = listOf(0.5, 0.2, 0.3)

    /** Read once each, however many times the dhikr is said elsewhere. */
    fun ticker(content: AdhkarContent = AdhkarContent()): List<AdhkarSlide> =
        combine(entries(TICKER_IDS, null).map { it.copy(count = 1, durationMillis = AdhkarPacer.durationMillis(it.text, 1)) }, content.ticker)

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
        ids.mapNotNull(DhikrCatalog::find).flatMap { slides(it, category) }

    private fun combine(bundled: List<AdhkarSlide>, custom: CustomAdhkarList?): List<AdhkarSlide> {
        val own = custom?.items.orEmpty().flatMap { item -> ownSlides(item) }
        return when (custom?.mode) {
            null -> bundled
            CustomAdhkarList.Mode.REPLACE -> own
            CustomAdhkarList.Mode.APPEND -> bundled + own
        }
    }

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

    private const val MAX_PAGED_REPETITIONS = 3

    /** An entry as slides: one per step (33 × three phrases, then the tahlil), or one per page of a long text. */
    private fun slides(entry: DhikrEntry, category: DhikrCategory?): List<AdhkarSlide> {
        if (entry.steps.isNotEmpty()) {
            return entry.steps.mapIndexed { index, step ->
                AdhkarSlide(entry.id, step.text, entry.reference, step.repetitions,
                    AdhkarPacer.durationMillis(step.text, step.repetitions), index + 1, entry.steps.size)
            }
        }
        val count = entry.countForCollection(category)
        val pages = AdhkarPacer.pages(entry.text)
        // Repetitions are for the whole text; a long text read once is paced page by page.
        return pages.mapIndexed { index, page ->
            AdhkarSlide(entry.id, page, entry.reference, count,
                AdhkarPacer.durationMillis(page, if (pages.size == 1) count else 1), index + 1, pages.size)
        }
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

    fun durationMillis(text: String, count: Int): Long {
        val words = text.split(Regex("""\s+""")).count { it.isNotBlank() }
        val perRepetition = maxOf(MIN_REPETITION_MILLIS, MILLIS_PER_WORD * words)
        return (perRepetition * count.coerceAtLeast(1)).coerceIn(MIN_SLIDE_MILLIS, MAX_SLIDE_MILLIS)
    }

    /**
     * [text] split into pages of at most [PAGE_CHARS] characters, never inside a word: after a verse
     * number, a pause mark or punctuation when there is one in reach, otherwise after a space. The
     * pages put together give back [text].
     */
    fun pages(text: String): List<String> {
        if (text.length <= PAGE_CHARS) return listOf(text)
        val breaks = BREAK.findAll(text).map { it.range.last + 1 }.filter { it in 1 until text.length }.toList()
        val spaces = SPACE.findAll(text).map { it.range.last + 1 }.filter { it in 1 until text.length }.toList()
        val pages = mutableListOf<String>()
        var start = 0
        while (text.length - start > PAGE_CHARS) {
            fun inReach(cuts: List<Int>) = cuts.lastOrNull { it > start && it - start <= PAGE_CHARS }
            val cut = inReach(breaks) ?: inReach(spaces) ?: break // a single 260-letter word: left whole
            pages += text.substring(start, cut)
            start = cut
        }
        pages += text.substring(start)
        return pages.filter { it.isNotBlank() }.ifEmpty { listOf(text) }
    }

    /** After a verse number (۝253), a pause mark (ۚ ۖ ۗ), punctuation or a line break, with the space after it. */
    private val BREAK = Regex("""(۝[0-9٠-٩]+|[ۖۗۚ،,.؛;؟?!:\n]+)\s*""")
    private val SPACE = Regex("""\s+""")
}

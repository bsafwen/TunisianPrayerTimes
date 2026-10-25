package com.tunisianprayertimes.quran

/** Later Madani/Qaloun numbering; ayah 0 identifies an unnumbered opening basmalah. */
data class QuranVerseReference(val surah: Int, val ayah: Int) : Comparable<QuranVerseReference> {
    /** Mushaf order. */
    override fun compareTo(other: QuranVerseReference): Int =
        if (surah != other.surah) surah.compareTo(other.surah) else ayah.compareTo(other.ayah)
}

/** A complete verse and the one-based supplied scan pages on which it appears. */
data class QuranVerse(
    val surah: Int,
    val ayah: Int,
    val text: String,
    val pages: List<Int>,
    /** Scan-derived signs indexed by the original tokens in [text]. */
    val waqf: List<QuranWaqfSign> = emptyList(),
) {
    val reference: QuranVerseReference get() = QuranVerseReference(surah, ayah)
    /** One converted word per source token; retained empty tokens keep Hifz and scan indexes stable. */
    val mushafWords: List<String> by lazy { QuranMushafText.words(text.split(' '), waqf) }
    val mushafText: String get() = mushafWords.filter { it.isNotEmpty() }.joinToString(" ")
}

/** A verse can continue onto the next scan; only its final fragment has a verse medallion. */
data class QuranVerseFragment(
    val reference: QuranVerseReference,
    val page: Int,
    val text: String,
    val isFirstFragment: Boolean,
    val isLastFragment: Boolean,
    /** The full verse's converted words sliced at this fragment's source-token offsets. */
    val mushafWords: List<String> = QuranMushafText.words(text.split(' ')),
) {
    val mushafText: String get() = mushafWords.filter { it.isNotEmpty() }.joinToString(" ")
}

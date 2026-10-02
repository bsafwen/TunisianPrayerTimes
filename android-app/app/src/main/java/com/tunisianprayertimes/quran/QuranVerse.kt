package com.tunisianprayertimes.quran

/** Later Madani/Qaloun numbering; ayah 0 identifies an unnumbered opening basmalah. */
data class QuranVerseReference(val surah: Int, val ayah: Int)

/** A complete verse and the one-based supplied scan pages on which it appears. */
data class QuranVerse(
    val surah: Int,
    val ayah: Int,
    val text: String,
    val pages: List<Int>,
) {
    val reference: QuranVerseReference get() = QuranVerseReference(surah, ayah)
}

/** A verse can continue onto the next scan; only its final fragment has a verse medallion. */
data class QuranVerseFragment(
    val reference: QuranVerseReference,
    val page: Int,
    val text: String,
    val isFirstFragment: Boolean,
    val isLastFragment: Boolean,
)

package com.tunisianprayertimes

import java.text.Normalizer
import java.util.Locale

/**
 * The picker's search over catalogue cities (plan 6.8): folds ø→o, ß→ss, ł→l, đ/ð→d, þ→th, æ→ae, œ→oe and ı→i,
 * adds the ue/oe/ae and aa spellings, knows exonyms (the catalogue's aliases, كولونيا and Cologne for Köln), and
 * breaks ties by population. And the Arabic country names: the 22 Arab members from the app's own table (PS is
 * «فلسطين»), the others from ICU.
 */
object CitySearch {
    private val FOLDS = mapOf(
        'ø' to "o", 'ß' to "ss", 'ł' to "l", 'đ' to "d", 'ð' to "d", 'þ' to "th", 'æ' to "ae", 'œ' to "oe", 'ı' to "i",
    )
    private val ARABIC_FOLDS = mapOf('أ' to 'ا', 'إ' to 'ا', 'آ' to 'ا', 'ى' to 'ي', 'ة' to 'ه', 'ؤ' to 'و', 'ئ' to 'ي')
    private val MARKS = Regex("\\p{Mn}+")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

    /** Between two spellings of a city in [Index]; never in a folded name, which keeps letters, digits and spaces. */
    private const val SEPARATOR = "\u0001"

    /** Lower case, diacritics and tatweel removed, letters folded, spaces collapsed. */
    fun normalize(text: String): String {
        val lower = text.lowercase(Locale.ROOT)
        val folded = buildString { lower.forEach { c -> append(FOLDS[c] ?: ARABIC_FOLDS[c]?.toString() ?: c.toString()) } }
        val stripped = Normalizer.normalize(folded, Normalizer.Form.NFD)
            .replace(MARKS, "")
            .replace("ـ", "")
        return stripped.replace(NON_WORD, " ").trim()
    }

    /** The spellings a name is found by: its folded form, and ü/ö/ä/å written ue/oe/ae/aa. */
    private fun spellings(name: String): List<String> {
        val lower = name.lowercase(Locale.ROOT)
        val transliterated = lower.replace("ü", "ue").replace("ö", "oe").replace("ä", "ae").replace("å", "aa")
        return listOf(normalize(name), normalize(transliterated)).distinct()
    }

    /**
     * The picker's cities with their names folded once: a query scans one string per city, a few milliseconds for the
     * catalogue's 26,600 cities, where folding every name again on every key took over 100 ms on a computer and
     * seconds on a phone, so a typed name came after the next keys or not at all.
     */
    class Index(val rows: List<PlaceCatalogue.Row>) {
        /** Each city's spellings, each between separators: "\u0001ivry sur seine\u0001ivry sobre sena\u0001…". */
        private val names: Array<String> = Array(rows.size) { i ->
            val row = rows[i]
            (listOf(row.name, row.ascii, row.arabic) + row.aliases).filter { it.isNotBlank() }.flatMap(::spellings)
                .filter { it.isNotEmpty() }.distinct().joinToString(SEPARATOR, SEPARATOR, SEPARATOR)
        }

        val isEmpty: Boolean get() = rows.isEmpty()

        /**
         * Rows matching [query], best first: a whole name, then the start of a name, then the start of any word in a name
         * («sur seine» finds Ivry-sur-Seine); ties by population. [checkpoint] runs every 1,024 cities, to stop a search
         * the next key made stale.
         */
        fun search(query: String, limit: Int = 60, checkpoint: () -> Unit = {}): List<PlaceCatalogue.Row> =
            searchRanked(query, limit, checkpoint).map { it.first }

        /** [search] with each city's rank (0 a whole name, 1 a name's start, 2 a word's start), the picker's scale. */
        fun searchRanked(query: String, limit: Int = 60, checkpoint: () -> Unit = {}): List<Pair<PlaceCatalogue.Row, Int>> {
            val q = normalize(query)
            if (q.isEmpty()) return emptyList()
            val whole = SEPARATOR + q + SEPARATOR
            val nameStart = SEPARATOR + q
            val wordStart = " $q"
            val hits = ArrayList<Pair<PlaceCatalogue.Row, Int>>()
            for (i in rows.indices) {
                if (i % 1024 == 0) checkpoint()
                val n = names[i]
                val rank = when {
                    n.contains(whole) -> 0
                    n.contains(nameStart) -> 1
                    n.contains(wordStart) -> 2
                    else -> continue
                }
                hits += rows[i] to rank
            }
            return hits.sortedWith(compareBy<Pair<PlaceCatalogue.Row, Int>>({ it.second }, { -it.first.ref.population })).take(limit)
        }

        companion object {
            val EMPTY = Index(emptyList())
        }
    }

    /** Rows matching [query], best first (see [Index.search]); folds [rows] each call, so the picker keeps an [Index]. */
    fun search(rows: List<PlaceCatalogue.Row>, query: String, limit: Int = 60): List<PlaceCatalogue.Row> =
        Index(rows).search(query, limit)

    /** The app's own Arabic names of the Arab League members (6.8: ICU's default for PS is «الأراضي الفلسطينية»). */
    private val ARAB_NAMES = mapOf(
        "DZ" to "الجزائر", "BH" to "البحرين", "KM" to "جزر القمر", "DJ" to "جيبوتي", "EG" to "مصر", "IQ" to "العراق",
        "JO" to "الأردن", "KW" to "الكويت", "LB" to "لبنان", "LY" to "ليبيا", "MR" to "موريتانيا", "MA" to "المغرب",
        "OM" to "عُمان", "PS" to "فلسطين", "QA" to "قطر", "SA" to "السعودية", "SO" to "الصومال", "SD" to "السودان",
        "SY" to "سوريا", "TN" to "تونس", "AE" to "الإمارات", "YE" to "اليمن", "EH" to "المغرب",
    )

    /** The Arabic name of a country (6.8): the app's table for the Arab members, ICU in Arabic for the others. */
    fun countryName(countryCode: String): String =
        ARAB_NAMES[countryCode.uppercase()] ?: Locale("", countryCode.uppercase()).getDisplayCountry(Locale("ar")).ifBlank { countryCode }

    /** With an empty query: one group per country, Arabic names in alphabetical order, each with its 50 largest cities. */
    fun countryGroups(rows: List<PlaceCatalogue.Row>, perCountry: Int = 50): List<Pair<String, List<PlaceCatalogue.Row>>> =
        rows.groupBy { com.tunisianprayertimes.place.CountryResolver.normalize(it.ref.countryCode) }
            .map { (cc, list) -> countryName(cc) to list.sortedByDescending { it.ref.population }.take(perCountry) }
            .sortedBy { it.first }
}

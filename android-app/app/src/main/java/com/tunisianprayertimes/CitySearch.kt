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

    /** Lower case, diacritics and tatweel removed, letters folded, spaces collapsed. */
    fun normalize(text: String): String {
        val lower = text.lowercase(Locale.ROOT)
        val folded = buildString { lower.forEach { c -> append(FOLDS[c] ?: ARABIC_FOLDS[c]?.toString() ?: c.toString()) } }
        val stripped = Normalizer.normalize(folded, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace("ـ", "")
        return stripped.replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    }

    /** The spellings a name is found by: its folded form, and ü/ö/ä/å written ue/oe/ae/aa. */
    private fun spellings(name: String): List<String> {
        val lower = name.lowercase(Locale.ROOT)
        val transliterated = lower.replace("ü", "ue").replace("ö", "oe").replace("ä", "ae").replace("å", "aa")
        return listOf(normalize(name), normalize(transliterated)).distinct()
    }

    /** Rows matching [query], best first: a whole-name match, then a prefix, then a word prefix; ties by population. */
    fun search(rows: List<PlaceCatalogue.Row>, query: String, limit: Int = 60): List<PlaceCatalogue.Row> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        return rows.mapNotNull { row ->
            val names = (listOf(row.name, row.ascii, row.arabic) + row.aliases).filter { it.isNotBlank() }.flatMap(::spellings)
            val rank = names.minOfOrNull { n ->
                when {
                    n == q -> 0
                    n.startsWith(q) -> 1
                    n.split(' ').any { it.startsWith(q) } -> 2
                    else -> 9
                }
            } ?: 9
            if (rank == 9) null else row to rank
        }.sortedWith(compareBy<Pair<PlaceCatalogue.Row, Int>>({ it.second }, { -it.first.ref.population }))
            .take(limit).map { it.first }
    }

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

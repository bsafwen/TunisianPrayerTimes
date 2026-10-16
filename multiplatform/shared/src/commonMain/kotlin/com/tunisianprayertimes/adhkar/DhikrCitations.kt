package com.tunisianprayertimes.adhkar

/** Hadith collections a catalog reference cites, followed by numbers or, for some, by name alone. */
private val CITED_BOOKS = listOf(
    "صحيح البخاري", "صحيح مسلم", "سنن أبي داود", "سنن الترمذي", "سنن النسائي", "سنن ابن ماجه",
    "السنن الكبرى للنسائي", "مسند أحمد", "مستدرك الحاكم", "الأدب المفرد للبخاري",
)

/** Qur'an passages the catalog cites: by surah and verse («البقرة 253–254») or by surah number («القرآن 112»). */
private val CITED_SURAHS = listOf("القرآن", "البقرة", "النور", "الزخرف", "الأنبياء")

/** One number or range, then more joined by «،» or «و»: «611، 613», «3369 و6360», «253–254». */
private const val CITED_NUMBERS = "\\d+(?:[–-]\\d+)?(?:(?:،\\s*|\\s+و)\\d+(?:[–-]\\d+)?)*"

private val CITATION = Regex(
    "(" + CITED_BOOKS.joinToString("|") + ")(?: (" + CITED_NUMBERS + "))?" +
        "|(?<!\\p{L})(" + CITED_SURAHS.joinToString("|") + ") (" + CITED_NUMBERS + ")" +
        "|(أثر عن [^،؛:(]+)",
)

private val NUMBER_SEPARATOR = Regex("،\\s*|\\s+و")

/**
 * Only the works and numbers a source cites, without its occasions, gradings and remarks:
 * «سنن الترمذي 3398؛ سنن أبي داود 5045، وصححه الألباني دون ذكر التكرار ثلاثًا» reads «سنن الترمذي 3398؛ سنن أبي داود 5045».
 * Numbers from the same work gather under its first mention. A note citing nothing known keeps its
 * first clause.
 */
fun dhikrCitations(reference: String): String {
    val cited = linkedMapOf<String, MutableList<String>>()
    CITATION.findAll(reference).forEach { match ->
        val groups = match.groupValues
        val (work, numbers) = when {
            groups[1].isNotEmpty() -> groups[1] to groups[2]
            groups[3].isNotEmpty() -> groups[3] to groups[4]
            else -> groups[5].trim() to ""
        }
        val listed = cited.getOrPut(work) { mutableListOf() }
        numbers.split(NUMBER_SEPARATOR).filter { it.isNotEmpty() && it !in listed }.forEach(listed::add)
    }
    if (cited.isEmpty()) return reference.substringBefore('؛').trim()
    return cited.entries.joinToString("؛ ") { (work, numbers) ->
        if (numbers.isEmpty()) work else work + " " + numbers.joinToString("، ")
    }
}

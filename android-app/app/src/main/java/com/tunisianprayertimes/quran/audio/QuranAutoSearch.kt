package com.tunisianprayertimes.quran.audio

import com.tunisianprayertimes.quran.QuranSurah
import com.tunisianprayertimes.quran.normalizeQuranSearch

/** Matches spoken chapter/reciter names without changing Quran text or guessing an unknown request. */
internal object QuranAutoSearch {
    fun resolve(
        query: String,
        title: String?,
        artist: String?,
        surahs: List<QuranSurah>,
        preferred: QuranAutoBrowse.Selection,
    ): QuranAutoBrowse.Selection? {
        val spoken = normalize(query)
        val requestedTitle = normalize(title.orEmpty())
        val requestedArtist = normalize(artist.orEmpty())
        val reciterText = requestedArtist.ifEmpty { spoken }
        val reciter = QuranAudioController.reciters.firstOrNull { reciter ->
            aliases(reciter).any { contains(reciterText, normalize(it)) }
        }
        if (requestedArtist.isNotEmpty() && reciter == null) return null
        val chapterText = requestedTitle.ifEmpty { spoken }
        val named = surahs.sortedByDescending { normalize(it.name).length }
            .firstOrNull { contains(chapterText, normalize(it.name)) }
        val number = Regex("(?:^| )(?:سوره |surah |sura |chapter )?([0-9]{1,3})(?: |$)")
            .find(chapterText)?.groupValues?.get(1)?.toIntOrNull()
        if (number != null && number !in 1..114) return null
        val surah = named?.number ?: number?.takeIf { n -> surahs.any { it.number == n } }
        if (requestedTitle.isNotEmpty() && surah == null) return null
        if (contains(chapterText, "سوره") && surah == null) return null
        if (surah == null && reciter == null && spoken.isNotEmpty() && spoken !in GeneralRequests) return null
        return QuranAutoBrowse.Selection(reciter?.id ?: preferred.reciterId, surah ?: preferred.surah)
    }

    private fun normalize(text: String): String = normalizeQuranSearch(text).map { char ->
        if (char.isDigit()) char.digitToIntOrNull()?.digitToChar() ?: char else char
    }.joinToString("")

    private fun contains(text: String, phrase: String): Boolean = phrase.isNotEmpty() && " $phrase " in " $text "

    private fun aliases(reciter: QuranReciter): List<String> =
        listOf(reciter.name, reciter.shortName, "ل${reciter.shortName.removePrefix("ا")}", "ب${reciter.shortName}") + when (reciter.id) {
        "hosary-qaloun" -> listOf("حصري", "محمود الحصري", "husary", "hosary", "hussary")
        "ayoub-qaloun" -> listOf("أيوب", "الركباوي", "ayoub", "rekbaoui")
        "trabelsi-qaloun" -> listOf("الطرابلسي", "أحمد الطرابلسي", "trabelsi")
        else -> emptyList()
    }

    private val GeneralRequests = setOf("", "قران", "القران", "القران الكريم", "quran", "play quran")
}

package com.tunisianprayertimes.ui

import com.tunisianprayertimes.quran.QuranCatalog
import com.tunisianprayertimes.quran.QuranPage
import com.tunisianprayertimes.quran.QuranSearchEntry
import com.tunisianprayertimes.quran.QuranSurah
import com.tunisianprayertimes.quran.QuranVerseReference
import com.tunisianprayertimes.quran.hifz.HifzKind
import com.tunisianprayertimes.quran.hifz.HifzMistake
import com.tunisianprayertimes.quran.hifz.HifzPoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HifzNotebookSearchTest {
    private val catalog = QuranCatalog(
        pages = listOf(QuranPage(1, "quran/pages/1.webp", emptyList())),
        surahs = (1..67).map { number ->
            QuranSurah(number, when (number) { 2 -> "البقرة"; 67 -> "الملك"; else -> "سورة $number" }, 1, if (number == 2) 286 else 30)
        },
        entries = listOf(
            QuranSearchEntry(2, 67, "وإذ قال موسى لقومه", 1),
            QuranSearchEntry(67, 2, "الذي خلق الموت والحياة", 1),
        ),
    )
    private fun point(surah: Int, ayah: Int) = HifzPoint(
        id = (surah * 1_000 + ayah).toLong(), verse = QuranVerseReference(surah, ayah),
        mistakes = listOf(HifzMistake(HifzKind.Word)), key = "تذكّر ترتيب الكلمات", dueDay = 100L, createdAt = 0L,
    )
    private val baqarah = point(2, 67)
    private val mulk = point(67, 2)

    @Test
    fun explicitNumericReferencesKeepSurahAndAyahOrder_inEverySupportedSeparatorAndDigitSet() {
        for (query in listOf("2:67", "2：67", "2/67", "2.67", "2-67", "2 67", "٢:٦٧", "۲：۶۷", "٢،٦٧", "  2 : 67  ")) {
            assertTrue(query, hifzNotebookMatches(catalog, baqarah, query))
            assertFalse(query, hifzNotebookMatches(catalog, mulk, query))
        }
        assertTrue(hifzNotebookMatches(catalog, mulk, "67:2"))
        assertFalse(hifzNotebookMatches(catalog, baqarah, "67:2"))
    }

    @Test
    fun invalidExplicitReferencesDoNotFallBackToNumbersInNotes() {
        val noted = mulk.copy(key = "2 999 1234 0 67")
        for (query in listOf("2:999", "2 999", "1234:2", "67:0")) {
            assertFalse(query, hifzNotebookMatches(catalog, noted, query))
        }
    }

    @Test
    fun exactReferenceAlsoFindsItsSavedLookAlike() {
        val linked = mulk.copy(mistakes = listOf(HifzMistake(HifzKind.Similar, baqarah.verse)))
        assertTrue(hifzNotebookMatches(catalog, linked, "2:67"))
        assertTrue(hifzNotebookMatches(catalog, linked, "البقرة ٦٧"))
    }

    @Test
    fun broadTextAndSingleNumbersStillFindNamesNotesAndVerseText() {
        for (query in listOf("الملك", "ترتيب الكلمات", "الموت", "67", "٢", "الملك: ٢", "")) {
            assertTrue(query, hifzNotebookMatches(catalog, mulk, query))
        }
        assertFalse(hifzNotebookMatches(catalog, mulk, "البقرة"))
    }
}

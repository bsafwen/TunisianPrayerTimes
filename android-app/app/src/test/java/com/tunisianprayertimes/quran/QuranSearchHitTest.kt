package com.tunisianprayertimes.quran

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuranSearchHitTest {
    // Fatiha's first verses on scan 1; verse 4 runs over onto scan 2.
    private val catalog = QuranCatalog(
        pages = listOf(QuranPage(1, "quran/pages/1.webp", listOf("الفاتحة")), QuranPage(2, "quran/pages/2.webp", listOf("الفاتحة"))),
        surahs = listOf(QuranSurah(1, "الفاتحة", 1, 4)),
        entries = listOf(
            QuranSearchEntry(1, 1, "الْحَمْدُ لِلَّهِ رَبِّ الْعَالَمِينَ", 1),
            QuranSearchEntry(1, 2, "الرَّحْمَٰنِ الرَّحِيمِ", 1),
            QuranSearchEntry(1, 3, "مَٰلِكِ يَوْمِ الدِّينِ", 1),
            QuranSearchEntry(1, 4, "إِيَّاكَ نَعْبُدُ", 1, isLastFragment = false),
            QuranSearchEntry(1, 4, "وَإِيَّاكَ نَسْتَعِينُ", 2, isFirstFragment = false),
        ),
    )

    @Test
    fun resultNamesTheVerseTheQueryIsIn() {
        val result = catalog.search("الرحيم").single()

        assertEquals(1, result.page)
        assertEquals(listOf(QuranVerseReference(1, 2)), result.verses)
    }

    @Test
    fun queryRunningThroughTwoVerses_namesBoth() {
        val result = catalog.search("الرحيم ملك").single()

        assertEquals(listOf(QuranVerseReference(1, 2), QuranVerseReference(1, 3)), result.verses)
    }

    @Test
    fun verseContinuingOntoTheNextScan_isFoundOnTheScanWithTheQuery() {
        val result = catalog.search("نستعين").single()

        assertEquals(2, result.page)
        assertEquals(listOf(QuranVerseReference(1, 4)), result.verses)
        assertEquals(QuranSearchHit(2, listOf(QuranVerseReference(1, 4))), result.hit)
    }

    @Test
    fun hitSurvivesBeingSavedAsText() {
        val hit = QuranSearchHit(37, listOf(QuranVerseReference(2, 255), QuranVerseReference(2, 256)))

        assertEquals(hit, QuranSearchHit.decode(hit.encode()))
    }

    @Test
    fun valuesNoHitWrote_showNothing() {
        assertNull(QuranSearchHit.decode(null))
        assertNull(QuranSearchHit.decode(""))
        assertNull(QuranSearchHit.decode("5"))
        assertNull(QuranSearchHit.decode("5:2"))
        assertNull(QuranSearchHit.decode("5:2:x"))
        assertNull(QuranSearchHit.decode("0:2:3"))
    }
}

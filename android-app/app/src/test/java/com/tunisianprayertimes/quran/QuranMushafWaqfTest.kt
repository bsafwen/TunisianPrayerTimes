package com.tunisianprayertimes.quran

import com.tunisianprayertimes.quran.hifz.hifzWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuranMushafWaqfTest {
    @Test
    fun scanSignsReplaceTheSourcesDifferentSigns() {
        val verse = QuranVerse(1, 1, "نُورٌۖ مُبِينٌۖ", listOf(1), listOf(QuranWaqfSign(0, 'ۚ')))
        assertEquals(listOf("نُورٌۚ", "مُبِينٌ"), verse.mushafWords)
        assertEquals("نُورٌۚ مُبِينٌ", verse.mushafText)
        assertFalse(verse.mushafText.contains('ۖ'))
        assertTrue(quranShareText("الفاتحة", listOf(verse)).contains("نُورٌۚ مُبِينٌ"))
    }

    @Test
    fun marksAreAttachedBeforeHifzGroupsMarkOnlyTokens() {
        val verse = QuranVerse(1, 1, "نُورٌ ٓ ۖ مُبِينٌ", listOf(1), listOf(QuranWaqfSign(3, 'ۚ')))
        val before = hifzWords(verse.text)
        val after = hifzWords(verse)
        assertEquals(4, verse.mushafWords.size)
        assertEquals("", verse.mushafWords[2])
        assertEquals(before.keys, after.keys)
        assertEquals(before.size, after.size)
        assertEquals(listOf("نور", "مبين"), after.keys)
        assertEquals(before.printed[0], after.printed[0])
        assertEquals(before.printed[1] + 'ۚ', after.printed[1])
        assertEquals("مُبِينٌۚ", after.text(1))
    }

    @Test
    fun fragmentsUseCompleteVerseContextAndOriginalWaqfOffsets() {
        val catalog = splitCatalog()
        val verse = requireNotNull(catalog.verse(1, 1))
        val fragments = catalog.verseFragments.filter { it.reference == verse.reference }
        // Ana takes the silent alif mark from the word on the following page.
        assertEquals("أَنَاْ", fragments[0].mushafText)
        assertEquals(listOf("عَابِدٌۚ", "لِلَّهِ"), fragments[1].mushafWords)
        assertEquals(verse.mushafWords, fragments.flatMap { it.mushafWords })
        val secondPageResult = catalog.search("عابد").first()
        assertEquals(2, secondPageResult.page)
        assertEquals(fragments[1].mushafWords, secondPageResult.mushafWords)
        assertEquals(verse.mushafWords, catalog.search("1:1").first().mushafWords)
    }

    @Test
    fun aSearchAcrossVersesKeepsEachVersesSignsWithoutChangingItsMatchingText() {
        val catalog = splitCatalog()
        val result = catalog.search("لله رب").single { it.verses.size == 2 }
        assertEquals(listOf(QuranVerseReference(1, 1), QuranVerseReference(1, 2)), result.verses)
        assertEquals("عَابِدٌ لِلَّهِۖ رَبِّ", result.text)
        assertEquals(listOf("عَابِدٌۚ", "لِلَّهِ", "رَبِّۗ"), result.mushafWords)
        assertEquals(result.text.split(' ').size, result.mushafWords.size)
    }

    private fun splitCatalog() = QuranCatalog(
        pages = listOf(QuranPage(1, "1.webp", emptyList()), QuranPage(2, "2.webp", emptyList())),
        surahs = listOf(QuranSurah(1, "الفاتحة", 1, 2)),
        entries = listOf(
            QuranSearchEntry(1, 1, "أَنَا", 1, isLastFragment = false),
            QuranSearchEntry(1, 1, "عَابِدٌ لِلَّهِۖ", 2, isFirstFragment = false),
            QuranSearchEntry(1, 2, "رَبِّ", 2),
        ),
        waqf = mapOf(
            QuranVerseReference(1, 1) to listOf(QuranWaqfSign(1, 'ۚ')),
            QuranVerseReference(1, 2) to listOf(QuranWaqfSign(0, 'ۗ')),
        ),
    )
}

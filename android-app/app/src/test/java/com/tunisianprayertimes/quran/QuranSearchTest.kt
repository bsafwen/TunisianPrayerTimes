package com.tunisianprayertimes.quran

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuranSearchTest {
    private val catalog = QuranCatalog(
        pages = (1..3).map { QuranPage(it, "quran/pages/$it.webp", emptyList()) },
        surahs = listOf(QuranSurah(1, "الفاتحة", 1, 4), QuranSurah(2, "البقرة", 2, 286)),
        entries = listOf(
            QuranSearchEntry(1, 1, "الْحَمْدُ لِلَّهِ رَبِّ الْعَالَمِينَ", 1),
            QuranSearchEntry(1, 2, "الرَّحْمَٰنِ الرَّحِيمِ", 1),
            QuranSearchEntry(1, 3, "مَٰلِكِ يَوْمِ الدِّينِ", 1),
            QuranSearchEntry(1, 4, "إِيَّاكَ نَعْبُدُ", 1, isLastFragment = false),
            QuranSearchEntry(1, 4, "وَإِيَّاكَ نَسْتَعِينُ", 2, isFirstFragment = false),
            QuranSearchEntry(2, 2, "ذَٰلِكَ الْكِتَابُ لَا رَيْبَ فِيهِ", 2),
            QuranSearchEntry(2, 6, "سَوَاءٌ عَلَيْهِمْ ءَأَنذَرْتَهُمْ", 2),
            QuranSearchEntry(2, 10, "وَاللَّهُ عَلِيمٌ", 2),
            QuranSearchEntry(2, 20, "عَلَيْهِمْ شَيْءٌ", 2),
            QuranSearchEntry(2, 255, "اللَّهُ لَا إِلَٰهَ إِلَّا هُوَ الْحَيُّ الْقَيُّومُ", 3),
        ),
    )

    private fun verses(query: String) = catalog.search(query).map { it.verses.map { v -> "${v.surah}:${v.ayah}" } }

    @Test
    fun verseReference_opensThatVerseFirst_inAnySpelling() {
        for (query in listOf("2:255", "2 255", "\u0662:\u0662\u0665\u0665", "البقرة 255", "سورة البقرة \u0662\u0665\u0665", "بقرة 255")) {
            val first = catalog.search(query).first()
            assertEquals(query, QuranSearchKind.Reference, first.kind)
            assertEquals(query, listOf(QuranVerseReference(2, 255)), first.verses)
            assertEquals(query, 3, first.page)
        }
    }

    @Test
    fun referenceToAVerseThatDoesNotExist_isNotAResult() {
        assertTrue(catalog.search("2:999").none { it.kind == QuranSearchKind.Reference })
        assertTrue(catalog.search("9:1").none { it.kind == QuranSearchKind.Reference })
    }

    @Test
    fun wholeWordsComeBeforeWordsThatMerelyContainTheQuery() {
        // 2:10 holds «والله», which contains «الله» but is not it; 2:255 holds the word itself.
        assertEquals(listOf(listOf("2:255"), listOf("2:10")), verses("الله").take(2))
    }

    @Test
    fun everyOccurrenceIsListed_notOnlyTheFirstOnAPage() {
        assertEquals(listOf(listOf("2:6"), listOf("2:20")), verses("عليهم").take(2))
    }

    @Test
    fun wordsInAnyOrderInOneVerse_areFoundAfterTheExactPhrase() {
        for (query in listOf("الكتاب ريب", "ريب الكتاب")) {
            val result = catalog.search(query).single()
            assertEquals(QuranSearchKind.Words, result.kind)
            assertEquals(listOf(QuranVerseReference(2, 2)), result.verses)
        }
    }

    @Test
    fun phraseRunningThroughTwoVerses_isStillFound() {
        val result = catalog.search("الرحيم ملك").single()

        assertEquals(QuranSearchKind.Phrase, result.kind)
        assertEquals(listOf(QuranVerseReference(1, 2), QuranVerseReference(1, 3)), result.verses)
    }

    @Test
    fun aSlipOfTheKeyboard_isOfferedAsSimilar() {
        val result = catalog.search("القيوب").single()

        assertEquals(QuranSearchKind.Similar, result.kind)
        assertEquals(listOf(QuranVerseReference(2, 255)), result.verses)
    }

    @Test
    fun shortWordsAreNeverGuessed() {
        assertTrue(catalog.search("كتب").isEmpty())
    }

    @Test
    fun nearMissesAreLeftOutOnceTheExactWordHasEnoughResults() {
        val many = QuranCatalog(
            pages = listOf(QuranPage(1, "quran/pages/1.webp", emptyList())),
            surahs = listOf(QuranSurah(1, "الفاتحة", 1, 4)),
            entries = listOf(
                QuranSearchEntry(1, 1, "عَلَيْهِمْ", 1), QuranSearchEntry(1, 2, "عَلَيْهِمْ شَيْءٌ", 1),
                QuranSearchEntry(1, 3, "ثُمَّ عَلَيْهِمْ", 1), QuranSearchEntry(1, 4, "عَلِيمٌ", 1),
            ),
        )

        // «عليم» is one edit from «عليهم», but three verses already hold the word itself.
        assertEquals(List(3) { QuranSearchKind.Phrase }, many.search("عليهم").map { it.kind })
    }

    @Test
    fun unnumberedBasmalahs_comeAfterTheNumberedVerses() {
        val withBasmalah = QuranCatalog(
            pages = (1..2).map { QuranPage(it, "quran/pages/$it.webp", emptyList()) },
            surahs = listOf(QuranSurah(1, "الفاتحة", 1, 1), QuranSurah(2, "البقرة", 2, 6)),
            entries = listOf(
                QuranSearchEntry(1, 0, "بِسْمِ اللَّهِ", 1), QuranSearchEntry(1, 1, "الْحَمْدُ", 1),
                QuranSearchEntry(2, 0, "بِسْمِ اللَّهِ", 2), QuranSearchEntry(2, 5, "هُدًى مِنَ اللَّهِ", 2),
            ),
        )

        assertEquals(listOf("2:5", "1:0", "2:0"), withBasmalah.search("الله").map { it.verses.single().let { v -> "${v.surah}:${v.ayah}" } })
    }

    @Test
    fun aWordWrittenWithoutItsArticle_isFoundWhenNothingElseIs() {
        val kursi = QuranCatalog(
            pages = listOf(QuranPage(1, "quran/pages/1.webp", emptyList())),
            surahs = listOf(QuranSurah(1, "الفاتحة", 1, 1)),
            entries = listOf(QuranSearchEntry(1, 1, "وَسِعَ كُرْسِيُّهُ", 1)),
        )

        val result = kursi.search("الكرسي").single()

        assertEquals(QuranSearchKind.Similar, result.kind)
        assertEquals(listOf("كُرْسِيُّهُ"), quranMatchRanges(result.text, "الكرسي", similar = true).map { result.text.substring(it) })
    }

    @Test
    fun matchedWordsAreMarkedInTheResultText() {
        val text = "الْحَمْدُ لِلَّهِ رَبِّ الْعَالَمِينَ"

        assertEquals(listOf("رَبِّ"), quranMatchRanges(text, "رب").map { text.substring(it) })
        assertEquals(listOf("لِلَّهِ", "رَبِّ"), quranMatchRanges(text, "لله رب").map { text.substring(it) })
        assertEquals(emptyList<IntRange>(), quranMatchRanges(text, "   "))
    }

    @Test
    fun similarWordsAreMarkedOnlyWhenAskedFor() {
        val text = "الْحَيُّ الْقَيُّومُ"

        assertEquals(emptyList<IntRange>(), quranMatchRanges(text, "القيوب"))
        assertEquals(listOf("الْقَيُّومُ"), quranMatchRanges(text, "القيوب", similar = true).map { text.substring(it) })
    }

    @Test
    fun editDistance_isBoundedByWhatTheWordForgives() {
        assertEquals(1, withinEdits("القيوم", "القيوب", 1))
        assertEquals(0, withinEdits("الله", "الله", 0))
        assertNull(withinEdits("القيوم", "القيام", 0))
        assertNull(withinEdits("القيوم", "الحمدل", 2))
        assertEquals(1, withinEdits("رحمه", "رحمة", 1))
        assertEquals(0, allowedEdits(3))
        assertEquals(1, allowedEdits(5))
        assertEquals(1, allowedEdits(8))
        assertEquals(2, allowedEdits(9))
    }

    @Test
    fun recentSearches_keepEachOnceNewestFirst_andIgnoreBlanks() {
        val recents = listOf("الرحمن", "الله")

        assertEquals(listOf("الرَّحْمَٰن", "الله"), recents.withRecentSearch("  الرَّحْمَٰن "))
        assertEquals(recents, recents.withRecentSearch("   "))
        assertEquals(listOf("ب", "أ"), listOf("أ").withRecentSearch("ب"))
        assertEquals(3, listOf("ا", "ب", "ج", "د").withRecentSearch("هـ", max = 3).size)
    }
}

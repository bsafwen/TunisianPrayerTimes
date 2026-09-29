package com.tunisianprayertimes.mosque

import com.tunisianprayertimes.adhkar.DhikrCatalog
import com.tunisianprayertimes.adhkar.DhikrCategory
import com.tunisianprayertimes.mosque.MosqueSettingsFile.ContentList
import com.tunisianprayertimes.mosque.MosqueSettingsFile.ErrorCode
import com.tunisianprayertimes.mosque.MosqueSettingsFile.ParseResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MosqueAdhkarTest {

    @Test
    fun everyIdTheScreenUsesIsInTheReviewedCatalog() {
        val ids = DhikrCatalog.collectionOrder.getValue(DhikrCategory.SALAH) + MosqueAdhkar.ADHAN_IDS + MosqueAdhkar.TICKER_IDS
        assertEquals(emptyList(), ids.filter { DhikrCatalog.find(it) == null })
    }

    @Test
    fun afterThePrayerTheCollectionPlaysOnceInOrder() {
        val slides = MosqueAdhkar.afterSalah()
        assertEquals(DhikrCatalog.collectionOrder.getValue(DhikrCategory.SALAH), slides.mapNotNull { it.entryId }.distinct())
        // The hundred: three times 33, then the tahlil once, as four parts.
        val hundred = slides.filter { it.entryId == DhikrCatalog.SALAH_HUNDRED_ID }
        assertEquals(listOf(33, 33, 33, 1), hundred.map { it.count })
        assertEquals(listOf(1, 2, 3, 4), hundred.map { it.part })
        // The texts are the catalog's, unchanged.
        val istighfar = slides.first()
        assertEquals(DhikrCatalog.find("salah_istighfar")!!.text, istighfar.text)
        assertEquals(3, istighfar.count)
        // Played through, the screen is done: it never starts over.
        val total = MosqueAdhkar.totalMillis(slides)
        assertNull(MosqueAdhkar.slideAt(slides, total))
        assertEquals(slides.lastIndex, MosqueAdhkar.slideAt(slides, total - 1)!!.index)
        assertTrue(total <= FlowTiming().afterSalahMinutes * 60_000L, "the sequence fits the after-prayer time ($total ms)")
    }

    @Test
    fun textsStayLongEnoughToBeRead() {
        val slides = MosqueAdhkar.afterSalah()
        assertTrue(slides.all { it.durationMillis >= AdhkarPacer.MIN_SLIDE_MILLIS })
        assertTrue(slides.first { it.entryId == "salah_istighfar" }.durationMillis >= 7_500)
        assertTrue(slides.filter { it.entryId == DhikrCatalog.SALAH_HUNDRED_ID && it.count == 33 }.all { it.durationMillis >= 82_500 })
        assertTrue(slides.filter { it.entryId == "ayat_kursi" }.sumOf { it.durationMillis } >= 20_000)
        assertEquals(AdhkarPacer.MAX_SLIDE_MILLIS, AdhkarPacer.durationMillis("سبحان الله", 1000))
    }

    @Test
    fun longTextsSplitBetweenVersesAndGiveBackTheWholeText() {
        val kursi = DhikrCatalog.find("ayat_kursi")!!.text
        val pages = AdhkarPacer.pages(kursi)
        assertEquals(kursi, pages.joinToString(""))
        assertTrue(pages.size > 1)
        assertTrue(pages.dropLast(1).all { page -> Regex("""(۝[0-9٠-٩]+|[ۖۗۚ،,.])\s*$""").containsMatchIn(page) })
        assertEquals(listOf("قصير"), AdhkarPacer.pages("قصير"))
    }

    @Test
    fun theAdhanCompanionFollowsTheCall() {
        val slides = MosqueAdhkar.adhanCompanion()
        assertEquals(MosqueAdhkar.ADHAN_IDS, slides.map { it.entryId })
        assertEquals(DhikrCatalog.find("after_adhan_wasila")!!.text, slides.last().text)
        assertTrue(slides.all { it.reference.isNotBlank() })
        // Over a 3-minute adhan screen: the reply first, the dua only at the end.
        val screen = 180_000L
        assertEquals("adhan_response", MosqueAdhkar.adhanCompanionAt(60_000, screen)?.entryId)
        assertEquals("adhan_shahada", MosqueAdhkar.adhanCompanionAt(100_000, screen)?.entryId)
        assertEquals("after_adhan_wasila", MosqueAdhkar.adhanCompanionAt(130_000, screen)?.entryId)
        assertEquals("after_adhan_wasila", MosqueAdhkar.adhanCompanionAt(400_000, screen)?.entryId)
    }

    @Test
    fun theTickerReadsEachTextOnce() {
        // subhanallah_bihamdih is said 100 times elsewhere; in the ticker it is read once.
        val ticker = MosqueAdhkar.ticker()
        assertTrue(ticker.all { it.count == 1 && it.durationMillis == AdhkarPacer.durationMillis(it.text, 1) })
        assertTrue(ticker.all { it.durationMillis < 30_000 })
    }

    @Test
    fun aLongTextWithoutPunctuationSplitsBetweenWords() {
        val words = List(120) { "كلمة$it" }.joinToString(" ")
        val pages = AdhkarPacer.pages(words)
        assertEquals(words, pages.joinToString(""))
        assertTrue(pages.all { it.length <= AdhkarPacer.PAGE_CHARS })
        assertTrue(pages.dropLast(1).all { it.endsWith(" ") })
    }

    @Test
    fun aLongOwnTextIsShownWholeForEachRepetition() {
        val long = CustomDhikr(List(80) { "دعاء$it" }.joinToString(" "), "مصدر", count = 3)
        val slides = MosqueAdhkar.afterSalah(AdhkarContent(afterSalah = CustomAdhkarList(CustomAdhkarList.Mode.REPLACE, listOf(long))))
        val pages = AdhkarPacer.pages(long.text).size
        assertEquals(pages * 3, slides.size)
        assertEquals(List(3) { (1..pages).toList() }.flatten(), slides.map { it.part })
        assertTrue(slides.all { it.count == 1 })
    }

    private val own = CustomDhikr("سُبْحَانَ اللَّهِ وَبِحَمْدِهِ", "صحيح مسلم 2692", 100)

    @Test
    fun aMosqueCanAddItsOwnTextsOrReplaceTheBundledOnes() {
        val appended = MosqueAdhkar.afterSalah(AdhkarContent(afterSalah = CustomAdhkarList(CustomAdhkarList.Mode.APPEND, listOf(own))))
        assertEquals(MosqueAdhkar.afterSalah().size + 1, appended.size)
        assertEquals(null, appended.last().entryId)
        assertEquals(100, appended.last().count)
        val replaced = MosqueAdhkar.ticker(AdhkarContent(ticker = CustomAdhkarList(CustomAdhkarList.Mode.REPLACE, listOf(own))))
        assertEquals(listOf(own.text), replaced.map { it.text })
    }

    private fun parse(text: String, content: AdhkarContent = AdhkarContent()) =
        MosqueSettingsFile.parse(text, MosqueSchedule.DEFAULT, currentContent = content)

    @Test
    fun theSettingsFileCarriesTheMosquesTexts() {
        val result = assertIs<ParseResult.Success>(parse(
            """{ "adhkar": { "afterSalah": { "mode": "replace", "items": [ { "text": "${own.text}", "reference": "${own.reference}", "count": "١٠٠" } ] },
                             "ticker": [ { "النص": "لا حول ولا قوة إلا بالله", "المصدر": "صحيح البخاري 6384" } ] } }"""))
        assertEquals(CustomAdhkarList(CustomAdhkarList.Mode.REPLACE, listOf(own)), result.content.afterSalah)
        assertEquals(CustomAdhkarList.Mode.APPEND, result.content.ticker?.mode)
        assertEquals(listOf(ContentList.AFTER_SALAH, ContentList.TICKER), result.contentChanges.map { it.list })
        // Written back and read again, nothing changes; null returns to the bundled texts.
        val written = MosqueSettingsFile.write(MosqueSchedule.DEFAULT, content = result.content)
        assertTrue(!assertIs<ParseResult.Success>(parse(written, result.content)).hasChanges)
        val reverted = assertIs<ParseResult.Success>(parse("""{ "adhkar": null }""", result.content))
        assertTrue(reverted.content.isBundled)
        assertNotNull(reverted.contentChanges.singleOrNull { it.list == ContentList.AFTER_SALAH })
    }

    @Test
    fun unsourcedOrMalformedTextsAreRefused() {
        fun codes(text: String) = assertIs<ParseResult.Failure>(parse(text)).errors.map { it.code to it.path }
        assertEquals(listOf(ErrorCode.INVALID_ADHKAR to "adhkar.afterSalah.items[0]"),
            codes("""{ "adhkar": { "afterSalah": [ { "text": "نص بلا مصدر" } ] } }"""))
        assertEquals(listOf(ErrorCode.INVALID_ADHKAR to "adhkar.ticker.mode"),
            codes("""{ "adhkar": { "ticker": { "mode": "shuffle", "items": [] } } }"""))
        assertEquals(listOf(ErrorCode.UNKNOWN_FIELD to "adhkar.duringKhutba"),
            codes("""{ "adhkar": { "duringKhutba": [] } }"""))
    }

    @Test
    fun writtenAnnouncementsHaveDatesAndReplaceTheList() {
        val result = assertIs<ParseResult.Success>(parse(
            """{ "announcements": [ { "text": "درس بعد العشاء", "from": "2026-10-01", "until": "٢٠٢٦-١٠-٣١" }, { "النص": "تبرعات لترميم المسجد" } ] }"""))
        val lesson = result.announcements.first()
        assertEquals(java.time.LocalDate.of(2026, 10, 31), lesson.until)
        assertTrue(lesson.isShownOn(java.time.LocalDate.of(2026, 10, 15)))
        assertTrue(!lesson.isShownOn(java.time.LocalDate.of(2026, 11, 1)))
        assertTrue(result.announcements[1].isShownOn(java.time.LocalDate.of(2030, 1, 1)))
        assertEquals(ContentList.ANNOUNCEMENTS, result.contentChanges.single().list)
        // Round trip, and an empty list removes them.
        val written = MosqueSettingsFile.write(MosqueSchedule.DEFAULT, announcements = result.announcements)
        assertEquals(result.announcements, assertIs<ParseResult.Success>(parse(written)).announcements)
        val cleared = assertIs<ParseResult.Success>(
            MosqueSettingsFile.parse("""{ "announcements": [] }""", MosqueSchedule.DEFAULT, currentAnnouncements = result.announcements))
        assertEquals(emptyList(), cleared.announcements)
        val bad = assertIs<ParseResult.Failure>(parse("""{ "announcements": [ { "text": "x", "from": "2026-10-10", "until": "2026-10-01" } ] }"""))
        assertEquals(listOf(ErrorCode.INVALID_ANNOUNCEMENT to "announcements[0]"), bad.errors.map { it.code to it.path })
    }
}

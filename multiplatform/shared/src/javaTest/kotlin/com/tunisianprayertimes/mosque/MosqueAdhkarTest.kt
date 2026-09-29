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
    fun writtenAnnouncementsComeBetweenTheTickersAdhkar() {
        val ticker = MosqueAdhkar.ticker()
        val mixed = MosqueAdhkar.tickerWithAnnouncements(ticker, listOf("درس بعد العشاء", "تبرعات"), "إعلان")
        assertEquals(ticker.size + 2, mixed.size)
        assertEquals("درس بعد العشاء", mixed[2].text)
        assertEquals("إعلان", mixed[2].reference)
        assertEquals(listOf("درس بعد العشاء", "تبرعات"), mixed.filter { it.entryId == null }.map { it.text })
        // Many announcements and a short ticker: every one still comes once per round.
        val many = MosqueAdhkar.tickerWithAnnouncements(ticker.take(1), List(4) { "إعلان $it" }, "إعلان")
        assertEquals(5, many.size)
        assertEquals(ticker, MosqueAdhkar.tickerWithAnnouncements(ticker, emptyList(), "إعلان"))
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
    fun aMosqueListCanHideMoveAndPickReviewedTexts() {
        val list = CustomAdhkarList(CustomAdhkarList.Mode.REPLACE, listOf(
            ReviewedDhikr("ayat_kursi"), ReviewedDhikr("salah_istighfar", count = 5), own, ReviewedDhikr("no_such_text"),
        ))
        val slides = MosqueAdhkar.afterSalah(AdhkarContent(afterSalah = list))
        // Ayat al-Kursi first, then the istighfar five times, then the mosque's text; an unknown id shows nothing.
        assertEquals("ayat_kursi", slides.first().entryId)
        assertEquals(5, slides.single { it.entryId == "salah_istighfar" }.count)
        assertEquals(own.text, slides.last().text)
        assertTrue(slides.none { it.entryId == "salah_salam" })
        // The ticker reads a reviewed text once.
        val ticker = MosqueAdhkar.ticker(AdhkarContent(ticker = CustomAdhkarList(CustomAdhkarList.Mode.REPLACE, listOf(ReviewedDhikr("salah_istighfar")))))
        assertEquals(listOf(1), ticker.map { it.count })
        // The bundled lists are the same texts as before, as reviewed items.
        assertEquals(MosqueAdhkar.afterSalah(), MosqueAdhkar.items(MosqueAdhkar.AFTER_SALAH_IDS, null).flatMap(MosqueAdhkar::afterSalahSlides))
    }

    @Test
    fun theSettingsFileNamesReviewedTextsByTheirId() {
        val result = assertIs<ParseResult.Success>(parse(
            """{ "adhkar": { "afterSalah": { "mode": "replace", "items": [
                 { "id": "ayat_kursi" }, { "id": "salah_istighfar", "count": 5 }, { "id": "salah_salam", "count": 1 },
                 { "text": "${own.text}", "reference": "${own.reference}", "count": 100 } ] } } }"""))
        assertEquals(
            listOf(ReviewedDhikr("ayat_kursi"), ReviewedDhikr("salah_istighfar", 5), ReviewedDhikr("salah_salam"), own),
            result.content.afterSalah?.items,
        )
        val written = MosqueSettingsFile.write(MosqueSchedule.DEFAULT, content = result.content)
        assertTrue(written.contains("""{ "id": "salah_istighfar", "count": 5 }"""), written)
        assertTrue(written.contains("""{ "id": "ayat_kursi" }"""), written)
        assertEquals(result.content, assertIs<ParseResult.Success>(parse(written)).content)
    }

    @Test
    fun reviewedTextsKeepTheirWordingAndSource() {
        fun errors(text: String) = assertIs<ParseResult.Failure>(parse(text)).errors.map { it.path to it.message }
        fun one(item: String, list: String = "afterSalah") = errors("""{ "adhkar": { "$list": [ $item ] } }""").single()
        assertTrue(one("""{ "id": "made_up" }""").second.contains("made_up"))
        assertTrue(one("""{ "id": "ayat_kursi", "text": "آية" }""").second.contains("لا الاثنان"))
        assertTrue(one("""{ "id": "ayat_kursi", "reference": "x" }""").second.contains("لا الاثنان"))
        assertTrue(one("""{ "id": "salah_istighfar", "count": 3 }""", list = "ticker").second.contains("مرة واحدة"))
        assertTrue(one("""{ "id": "salah_hundred", "count": 10 }""").second.contains("خطواته"))
        assertTrue(one("""{ "id": "salah_istighfar", "count": 0 }""").second.contains("«count»"))
        assertTrue(one("""{ "id": 12 }""").second.contains("«id»"))
        assertEquals("adhkar.afterSalah.items[0].title", one("""{ "id": "ayat_kursi", "title": "x" }""").first)
    }

    @Test
    fun aChangedListSaysWhichTextsComeAndGo() {
        fun change(text: String, content: AdhkarContent = AdhkarContent()) =
            assertIs<ParseResult.Success>(parse(text, content)).contentChanges.single { it.list == ContentList.AFTER_SALAH }
        val bundled = MosqueAdhkar.AFTER_SALAH_IDS
        val hidden = change("""{ "adhkar": { "afterSalah": { "mode": "replace", "items": [ """ +
            bundled.drop(1).joinToString(", ") { """{ "id": "$it" }""" } + """, { "id": "sayyid_istighfar" } ] } } }""")
        assertEquals(listOf("سيد الاستغفار"), hidden.added)
        assertEquals(listOf("الاستغفار بعد الصلاة"), hidden.removed)
        assertTrue(hidden.before.startsWith("النصوص المضمّنة · 8 نصوص · نحو "), hidden.before)
        assertTrue(hidden.after.startsWith("قائمة المسجد · 8 نصوص"), hidden.after)
        val moved = change("""{ "adhkar": { "afterSalah": { "mode": "replace", "items": [ """ +
            bundled.reversed().joinToString(", ") { """{ "id": "$it" }""" } + """ ] } } }""")
        assertTrue(moved.reordered)
        assertEquals(emptyList(), moved.added + moved.removed)
        val appended = change("""{ "adhkar": { "afterSalah": [ { "text": "${own.text}", "reference": "مسلم" } ] } }""")
        assertTrue(appended.after.startsWith("النصوص المضمّنة ونص واحد بعدها · 9 نصوص"), appended.after)
        assertEquals(1, appended.added.size)
        // Another count for a kept text is said as such, not as a text removed and added.
        val recounted = change("""{ "adhkar": { "afterSalah": { "mode": "replace", "items": [ """ +
            bundled.joinToString(", ") { if (it == "salah_istighfar") """{ "id": "$it", "count": 5 }""" else """{ "id": "$it" }""" } + """ ] } } }""")
        assertEquals(listOf("الاستغفار بعد الصلاة: العدد 3 ← 5"), recounted.recounted)
        assertEquals(emptyList(), recounted.added + recounted.removed)
        assertTrue(!recounted.reordered)
    }

    @Test
    fun aCountOnALongReviewedTextRepeatsTheWholeText() {
        val pages = AdhkarPacer.pages(DhikrCatalog.find("ayat_kursi")!!.text).size
        assertTrue(pages > 1)
        val slides = MosqueAdhkar.afterSalahSlides(ReviewedDhikr("ayat_kursi", count = 3))
        assertEquals(List(3) { (1..pages).toList() }.flatten(), slides.map { it.part })
        assertTrue(slides.all { it.count == 1 })
        // At most three whole readings, and the ticker reads it once.
        assertEquals(pages * 3, MosqueAdhkar.afterSalahSlides(ReviewedDhikr("ayat_kursi", count = 7)).size)
        assertEquals(pages, MosqueAdhkar.tickerSlides(ReviewedDhikr("ayat_kursi")).size)
        // The ticker reads the mosque's own text once too.
        assertEquals(listOf(1), MosqueAdhkar.tickerSlides(CustomDhikr("لا إله إلا الله", "مسلم", 10)).map { it.count })
    }

    @Test
    fun announcementsInTheTickerComeBetweenWholeTexts() {
        val slides = listOf("ayat_kursi", "salah_salam", "salah_hundred").map(::ReviewedDhikr).flatMap(MosqueAdhkar::tickerSlides)
        val mixed = MosqueAdhkar.tickerWithAnnouncements(slides, listOf("إعلان"), "إعلان")
        val at = mixed.indexOfFirst { it.entryId == null }
        // After Ayat al-Kursi (all its pages) and the salam: never inside a text's pages or steps.
        assertEquals(slides.indexOfFirst { it.entryId == "salah_hundred" }, at)
        assertEquals(slides.size + 1, mixed.size)
    }

    @Test
    fun anAfterPrayerListLongerThanTheScreenAllowsIsRefused() {
        val long = List(40) { """{ "id": "ayat_kursi", "count": 3 }""" }.joinToString(", ")
        val errors = assertIs<ParseResult.Failure>(parse("""{ "adhkar": { "afterSalah": { "mode": "replace", "items": [ $long ] } } }""")).errors
        assertEquals("adhkar.afterSalah", errors.single().path)
        assertTrue(errors.single().message.contains("${FlowTiming.MAX_AFTER_SALAH_MINUTES} د"), errors.single().message)
    }

    @Test
    fun theTvsOwnSavedListsSurviveAnUpdateThatRetiresAText() {
        val saved = """{ "adhkar": {
            "afterSalah": { "mode": "append", "items": [ { "text": "دعاء المسجد", "reference": "مأثور" }, { "id": "retired_text", "count": 4 },
                                                         { "id": "salah_hundred", "count": 5 } ] },
            "ticker": [ { "text": "سبحان الله", "reference": "مسلم", "count": 3 } ] } }"""
        assertIs<ParseResult.Failure>(parse(saved))
        val stored = assertIs<ParseResult.Success>(MosqueSettingsFile.parse(saved, MosqueSchedule.DEFAULT, stored = true)).content
        assertEquals(listOf(CustomDhikr("دعاء المسجد", "مأثور"), ReviewedDhikr("retired_text"), ReviewedDhikr("salah_hundred")), stored.afterSalah?.items)
        assertEquals(listOf(CustomDhikr("سبحان الله", "مسلم", 1)), stored.ticker?.items)
        // A retired text shows nothing; the mosque's own texts stay.
        assertEquals(MosqueAdhkar.afterSalah().size + 1 + 4, MosqueAdhkar.afterSalah(stored).size)
    }

    @Test
    fun smallMistakesGetPreciseMessages() {
        fun message(text: String) = assertIs<ParseResult.Failure>(parse(text)).errors.single().message
        assertTrue(message("""{ "adhkar": { "ticker": { "mode": 1, "items": [ { "id": "salah_salam" } ] } } }""").contains("«mode»"))
        assertTrue(message("""{ "adhkar": { "afterSalah": [ { "text": "دعاء", "reference": "${"م".repeat(201)}" } ] } }""").contains("أطول"))
        assertTrue(message("""{ "adhkar": { "ticker": [ { "text": "دعاء", "reference": "مأثور", "count": 3 } ] } }""").contains("مرة واحدة"))
    }

    @Test
    fun editsOfTheMosquesTextsAreNamedAsEdits() {
        val longText = "اللهم اجعل هذا المسجد عامرا بذكرك وتلاوة كتابك إلى يوم الدين"
        val before = AdhkarContent(afterSalah = CustomAdhkarList(CustomAdhkarList.Mode.APPEND, listOf(CustomDhikr(longText, "مأثور"))))
        fun change(item: String) = assertIs<ParseResult.Success>(parse("""{ "adhkar": { "afterSalah": [ $item ] } }""", before))
            .contentChanges.single()
        val source = change("""{ "text": "$longText", "reference": "دعاء مأثور" }""")
        assertTrue(source.added.isEmpty() && source.removed.isEmpty())
        assertTrue(source.recounted.single().endsWith("مصدر آخر"), source.recounted.toString())
        val wording = change("""{ "text": "$longText وصلى الله على نبينا", "reference": "مأثور" }""")
        assertTrue(wording.added.isEmpty() && wording.removed.isEmpty())
        assertTrue(wording.recounted.single().endsWith("نص معدَّل"), wording.recounted.toString())
        // A hundred own texts after the bundled ones: "100 نص", then "108 نصوص" in all.
        val hundred = List(100) { """{ "text": "دعاء $it", "reference": "مأثور" }""" }.joinToString(", ")
        val many = assertIs<ParseResult.Success>(parse("""{ "adhkar": { "afterSalah": [ $hundred ] } }""")).contentChanges.single()
        assertTrue(many.after.startsWith("النصوص المضمّنة و100 نص بعدها · 108 نصوص"), many.after)
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

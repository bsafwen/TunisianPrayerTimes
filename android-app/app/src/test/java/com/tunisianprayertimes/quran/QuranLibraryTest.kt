package com.tunisianprayertimes.quran

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.adhkar.MushafPassages
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class QuranLibraryTest {
    private val surahs = listOf(
        QuranSurah(1, "الفاتحة", 1, 7),
        QuranSurah(2, "البقرة", 1, 285),
        QuranSurah(3, "آل عمران", 49, 200),
        QuranSurah(42, "الشورى", 482, 50),
    )
    private fun verse(surah: Int, ayah: Int) = QuranVerseReference(surah, ayah)

    @Test
    fun divisions_matchTheSuppliedScan() {
        val source = JSONObject(File("src/main/assets/quran/divisions.json").readText())
        assertEquals("madani-later", source.getString("numbering"))
        val divisions = parseQuranDivisions(source.getJSONArray("athman"))
        assertEquals(480, divisions.athman.size)
        // The second hizb opens at Al-Baqarah 75, on printed page 11 (scan 10).
        val second = divisions.hizbStart(2)!!
        assertEquals(verse(2, 75), second.start)
        assertEquals(10, second.page)
        assertEquals(8, divisions.athmanOf(1).size)
        assertEquals(60, divisions.athman.last().hizb)
        assertEquals(30, divisions.athman.last().juz)
    }

    @Test
    fun thumnOf_findsTheEighthAVerseBelongsTo() {
        val divisions = QuranDivisions(List(480) { i -> QuranThumn(i, verse(1 + i / 5, 1 + (i % 5) * 10), 1 + i) })
        assertEquals(0, divisions.thumnOf(verse(1, 1)).index)
        assertEquals(0, divisions.thumnOf(verse(1, 10)).index)
        assertEquals(1, divisions.thumnOf(verse(1, 11)).index)
        assertEquals(4, divisions.thumnOf(verse(1, 300)).index)
        assertEquals(5, divisions.thumnOf(verse(2, 1)).index)
        assertEquals(479, divisions.thumnOf(verse(200, 1)).index)
        assertEquals(1, divisions.thumnOf(verse(2, 1)).hizb)
        assertEquals(7, divisions.thumnOf(verse(2, 11)).thumn)
        assertEquals(2, divisions.thumnOf(verse(2, 31)).hizb)
    }

    @Test(expected = IllegalArgumentException::class)
    fun divisionsOutOfOrder_areRejected() {
        val rows = JSONArray(List(480) { JSONArray(listOf(1, 1, 1)) })
        parseQuranDivisions(rows)
    }

    @Test
    fun playing_pauses() {
        assertEquals(QuranPlayAction.Pause, quranPlayAction(true, true, verse(2, 5), false, true, verse(2, 1)))
    }

    @Test
    fun aPickedVerse_winsOverAPausedRecitation() {
        assertEquals(QuranPlayAction.PlayFrom(verse(2, 5)), quranPlayAction(false, true, verse(2, 5), true, false, verse(2, 1)))
    }

    @Test
    fun aPausedRecitation_resumesUntilTheReaderGoesElsewhere() {
        assertEquals(QuranPlayAction.Resume, quranPlayAction(false, true, null, false, false, verse(3, 1)))
        assertEquals(QuranPlayAction.Resume, quranPlayAction(false, true, null, true, true, verse(2, 1)))
        assertEquals(QuranPlayAction.PlayFrom(verse(3, 1)), quranPlayAction(false, true, null, false, true, verse(3, 1)))
        // The page's verses are not read yet: the recitation is all there is to play.
        assertEquals(QuranPlayAction.Resume, quranPlayAction(false, true, null, false, true, null))
    }

    @Test
    fun withoutARecitation_thePageStarts() {
        assertEquals(QuranPlayAction.PlayFrom(verse(2, 1)), quranPlayAction(false, false, null, false, false, verse(2, 1)))
        assertEquals(QuranPlayAction.None, quranPlayAction(false, false, null, false, false, null))
    }

    @Test
    fun goTo_readsVersesPagesChaptersAndAhzab() {
        assertEquals(listOf(QuranGoTo.Verse(verse(2, 21))), quranGoTo("2:21", surahs, 604))
        assertEquals(listOf(QuranGoTo.Verse(verse(2, 21))), quranGoTo(" 2 21 ", surahs, 604))
        assertEquals(listOf(QuranGoTo.Verse(verse(2, 21))), quranGoTo("البقرة 21", surahs, 604))
        assertEquals(listOf(QuranGoTo.Verse(verse(2, 21))), quranGoTo("سورة البقرة 21", surahs, 604))
        assertEquals(listOf(QuranGoTo.Verse(verse(3, 7))), quranGoTo("ال عمران 7", surahs, 604))
        assertEquals(listOf(QuranGoTo.Hizb(12)), quranGoTo("حزب 12", surahs, 604))
        assertEquals(listOf(QuranGoTo.Hizb(12)), quranGoTo("الحزب 12", surahs, 604))
        assertEquals(listOf(QuranGoTo.Page(51)), quranGoTo("ص 51", surahs, 604))
        assertEquals(listOf(QuranGoTo.Page(51)), quranGoTo("صفحة 51", surahs, 604))
        assertEquals(listOf(QuranGoTo.Page(42), QuranGoTo.Surah(42)), quranGoTo("42", surahs, 604))
        assertEquals(listOf(QuranGoTo.Page(200)), quranGoTo("200", surahs, 604))
    }

    @Test
    fun goTo_acceptsArabicIndicDigits() {
        assertEquals(listOf(QuranGoTo.Verse(verse(2, 21))), quranGoTo("٢:٢١", surahs, 604))
    }

    @Test
    fun goTo_offersNothingOutsideTheMushaf() {
        assertEquals(emptyList<QuranGoTo>(), quranGoTo("2:286", surahs, 604))
        assertEquals(emptyList<QuranGoTo>(), quranGoTo("2:0", surahs, 604))
        assertEquals(emptyList<QuranGoTo>(), quranGoTo("حزب 61", surahs, 604))
        assertEquals(emptyList<QuranGoTo>(), quranGoTo("ص 605", surahs, 604))
        assertEquals(emptyList<QuranGoTo>(), quranGoTo("700", surahs, 604))
        assertEquals(emptyList<QuranGoTo>(), quranGoTo("مريم 3", surahs, 604))
        assertEquals(emptyList<QuranGoTo>(), quranGoTo("   ", surahs, 604))
    }

    @Test
    fun shareText_isTheMushafsWordsForAnyFontAndSaysWhereItIs() {
        // 1:5 and 1:6 as the index has them: the King Fahd encoding, whose wasl carries a joining vowel and a dot (other
        // fonts would show a damma to read on the alif of ihdina, a fatha on the article).
        val one = QuranVerse(1, 5, "\u0627\u064F\u06EA\u0647\u0652\u062F\u0650\u0646\u064E\u0627 \u0627\u064E\u06EC\u0644\u0635\u0651\u0650\u0631\u064E\u0670\u0637\u064E \u0627\u064E\u06EC\u0644\u0652\u0645\u064F\u0633\u0652\u062A\u064E\u0642\u0650\u064A\u0645\u064E", listOf(1))
        val two = QuranVerse(1, 6, "\u0635\u0650\u0631\u064E\u0670\u0637\u064E \u0627\u064E\u06EC\u0644\u0630\u0650\u064A\u0646\u064E \u0623\u064E\u0646\u0652\u0639\u064E\u0645\u0652\u062A\u064E \u0639\u064E\u0644\u064E\u064A\u0652\u0647\u0650\u0645\u0652", listOf(1))
        assertEquals("\uFD3F\u0671\u0647\u0652\u062F\u0650\u0646\u064E\u0627 \u0671\u0644\u0635\u0651\u0650\u0631\u064E\u0670\u0637\u064E \u0671\u0644\u0652\u0645\u064F\u0633\u0652\u062A\u064E\u0642\u0650\u064A\u0645\u064E\uFD3E [الفاتحة: 5] · رواية قالون عن نافع، العدّ المدني الأخير", quranShareText("الفاتحة", listOf(one)))
        assertEquals(
            "\uFD3F\u0671\u0647\u0652\u062F\u0650\u0646\u064E\u0627 \u0671\u0644\u0635\u0651\u0650\u0631\u064E\u0670\u0637\u064E \u0671\u0644\u0652\u0645\u064F\u0633\u0652\u062A\u064E\u0642\u0650\u064A\u0645\u064E (5) \u0635\u0650\u0631\u064E\u0670\u0637\u064E \u0671\u0644\u0651\u064E\u0630\u0650\u064A\u0646\u064E \u0623\u064E\u0646\u0652\u0639\u064E\u0645\u0652\u062A\u064E \u0639\u064E\u0644\u064E\u064A\u0652\u0647\u0650\u0645\u0652 (6)\uFD3E [الفاتحة: 5 إلى 6] · رواية قالون عن نافع، العدّ المدني الأخير",
            quranShareText("الفاتحة", listOf(one, two)),
        )
        assertEquals("", quranShareText("الفاتحة", listOf(QuranVerse(1, 0, "\u0628\u0650\u0633\u0652\u0645\u0650 \u0627\u0644\u0644\u064E\u0651\u0647\u0650 \u0627\u0644\u0631\u064E\u0651\u062D\u0652\u0645\u064E\u0670\u0646\u0650 \u0627\u0644\u0631\u064E\u0651\u062D\u0650\u064A\u0645\u0650", listOf(1)))))
    }

    private val waqf by lazy {
        val source = JSONObject(File("src/main/assets/quran/waqf.json").readText())
        parseQuranWaqf(source.getJSONObject("signs"), source.getJSONObject("verses"))
    }

    private val verseTexts by lazy {
        val entries = JSONObject(File("src/main/assets/quran/index.json").readText()).getJSONArray("entries")
        val texts = LinkedHashMap<QuranVerseReference, MutableList<String>>()
        for (k in 0 until entries.length()) {
            val entry = entries.getJSONObject(k)
            texts.getOrPut(QuranVerseReference(entry.getInt("surah"), entry.getInt("ayah"))) { mutableListOf() } += entry.getString("text")
        }
        texts.mapValues { it.value.joinToString(" ") }
    }

    private fun sharedWords(verse: QuranVerseReference): List<String> =
        quranShareText("x", listOf(QuranVerse(verse.surah, verse.ayah, verseTexts.getValue(verse), listOf(1))), waqf)
            .substringAfter('\uFD3F').substringBefore('\uFD3E').split(' ')

    @Test
    fun shareText_ofEveryVerseIsTheMushafsWordsAndSignsInMarksOtherFontsDrawAsMeant() {
        // Signs the mushaf does not print (the lazim mim, la, the small seen), the sajdah sign, the small zero, the empty
        // low stop, the encoding's open tanween and its ya barree, a right-to-left mark: none is drawn by other fonts as
        // the mushaf means it.
        val foreign = Regex("[\u06D8\u06D9\u06DC-\u06DF\u06E9\u06EA\u0656\u0657\u065E\u06D2\u200F]")
        val signs = Regex("[\u06D6\u06D7\u06DA\u06DB]")
        var shared = 0
        for ((verse, source) in verseTexts) {
            if (verse.ayah == 0) continue
            val text = quranShareText("x", listOf(QuranVerse(verse.surah, verse.ayah, source, listOf(1))), waqf)
            val body = text.substringAfter('\uFD3F').substringBefore('\uFD3E')
            assertEquals(verse.toString(), "\uFD3F$body\uFD3E [x: ${verse.ayah}] · رواية قالون عن نافع، العدّ المدني الأخير", text)
            assertFalse(verse.toString(), foreign.containsMatchIn(body))
            // The wasl alif alone, as other fonts expect it: its joining vowel and dot are the mushaf font's.
            assertFalse(verse.toString(), Regex("\u0671[\u064B-\u0652\u06EC\u065C]").containsMatchIn(body))
            val words = body.split(' ')
            assertEquals(verse.toString(), QuranMushafText.text(source).split(' ').size, words.size)
            // Each sign at its word's end, after the word's own marks.
            val expected = waqf[verse].orEmpty().associate { it.word to it.sign }
            words.forEachIndexed { i, word ->
                val sign = word.lastOrNull()?.takeIf { signs.matches(it.toString()) }
                assertEquals("$verse word $i", expected[i], sign)
                assertEquals("$verse word $i", if (sign == null) 0 else 1, signs.findAll(word).count())
            }
            shared++
        }
        assertEquals(6214, shared)
        assertEquals(4305, waqf.values.sumOf { it.size })
    }

    @Test
    fun shareText_signsAreThoseReadByHandForThePassagesShownOutsideTheQuranTab() {
        val signs = Regex("[\u06D6\u06D7\u06DA\u06DB]")
        for (id in MushafPassages.ids - "basmala") {
            val surah = MushafPassages.SOURCES.getValue(id).split(' ').last().split(':').first().toInt()
            val shared = MushafPassages.SOURCES.getValue(id).split(' ')
                .map { it.split(':') }.filter { it[0].toInt() == surah && it[1].toInt() > 0 }
                .flatMap { (_, ayah, range) ->
                    val (from, to) = range.split('-').map(String::toInt)
                    sharedWords(QuranVerseReference(surah, ayah.toInt())).subList(from, to + 1)
                }
                .joinToString("") { word -> signs.findAll(word).joinToString("") { it.value } }
            val hand = signs.findAll(MushafPassages.text(id)!!).joinToString("") { it.value }
            assertEquals(id, hand, shared)
        }
    }

    @Test
    fun library_togglesBookmarksAndKeepsTenRecentPages() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val library = QuranLibrary(context.getSharedPreferences("quran_library_test", Context.MODE_PRIVATE))
        assertTrue(library.toggleVerse(verse(2, 255), 10L))
        assertTrue(library.toggleVerse(verse(1, 1), 20L))
        assertEquals(listOf(verse(1, 1), verse(2, 255)), library.verseBookmarks.map { it.verse })
        assertFalse(library.toggleVerse(verse(2, 255), 30L))
        assertFalse(library.isBookmarked(verse(2, 255)))
        assertTrue(library.togglePage(42))
        assertTrue(library.isPageBookmarked(42))
        assertFalse(library.togglePage(42))
        (1..12).forEach { library.read(it) }
        library.read(5)
        assertEquals(5, library.lastRead)
        assertEquals(listOf(5, 12, 11, 10, 9, 8, 7, 6, 4, 3), library.recentPages)
    }
}

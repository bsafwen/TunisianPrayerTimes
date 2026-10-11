package com.tunisianprayertimes.quran

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
    fun shareText_keepsTheIndexTextAndSaysWhereItIs() {
        val one = QuranVerse(2, 21, "نص الآية", listOf(5))
        val two = QuranVerse(2, 22, "نص التالية", listOf(5))
        assertEquals("﴿نص الآية﴾ [البقرة: 21] · رواية قالون عن نافع", quranShareText("البقرة", listOf(one)))
        assertEquals(
            "﴿نص الآية (21) نص التالية (22)﴾ [البقرة: 21 إلى 22] · رواية قالون عن نافع",
            quranShareText("البقرة", listOf(one, two)),
        )
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

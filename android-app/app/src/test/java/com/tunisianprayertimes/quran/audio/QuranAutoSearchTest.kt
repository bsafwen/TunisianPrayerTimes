package com.tunisianprayertimes.quran.audio

import com.tunisianprayertimes.quran.QuranSurah
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuranAutoSearchTest {
    private val surahs = listOf(
        QuranSurah(1, "الفاتحة", 1), QuranSurah(2, "البقرة", 2),
        QuranSurah(36, "يس", 440), QuranSurah(67, "الملك", 562), QuranSurah(114, "الناس", 604),
    )
    private val preferred = QuranAutoBrowse.Selection("ayoub-qaloun", 2)

    private fun search(query: String, title: String? = null, artist: String? = null) =
        QuranAutoSearch.resolve(query, title, artist, surahs, preferred)

    @Test fun spokenArabicNamesIgnoreDiacriticsAndMatchTheReciter() {
        assertEquals(QuranAutoBrowse.Selection("hosary-qaloun", 67), search("سورة المُلْك بصوت الحُصري"))
        assertEquals(QuranAutoBrowse.Selection("hosary-qaloun", 67), search("سورة الملك للحصري"))
    }

    @Test fun structuredAssistantFieldsTakePrecedenceOverTheQuery() {
        assertEquals(QuranAutoBrowse.Selection("trabelsi-qaloun", 67),
            search("البقرة", title = "الملك", artist = "أحمد خضر الطرابلسي"))
    }

    @Test fun chapterNumbersAcceptArabicAndPersianDigits() {
        listOf("سورة ٦٧", "سورة ۶۷", "chapter 67").forEach { query ->
            assertEquals(preferred.copy(surah = 67), search(query))
        }
    }

    @Test fun emptyOrGeneralRequestsUseThePreferredPlace() {
        assertEquals(preferred, search(""))
        assertEquals(preferred, search("القرآن الكريم"))
        assertEquals(preferred.copy(reciterId = "hosary-qaloun"), search("الحصري"))
    }

    @Test fun unknownContentIsRejectedInsteadOfPlayingAnUnrelatedChapter() {
        assertNull(search("طلب غير معروف"))
        assertNull(search("سورة غير معروفة بصوت الحصري"))
        assertNull(search("سورة 200"))
        assertNull(search("الملك", artist = "قارئ غير معروف"))
        assertNull(search("الحصري", title = "سورة غير معروفة"))
    }
}

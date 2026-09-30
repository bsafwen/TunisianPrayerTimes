package com.tunisianprayertimes.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class TvStringsTest {

    @Test
    fun datesUseTheTunisianMonthNames() {
        assertEquals("الثلاثاء 29 سبتمبر 2026", TvStrings.gregorianDate(LocalDate.of(2026, 9, 29)))
        assertEquals("الأحد 21 فيفري 2027", TvStrings.gregorianDate(LocalDate.of(2027, 2, 21)))
        assertEquals("31 أوت 2026", TvStrings.gregorianDate(LocalDate.of(2026, 8, 31), withWeekday = false))
    }

    @Test
    fun writtenAnnouncementsAreNotCountedAsFiles() {
        // Two written announcements from the phone and no file: no «ملفان» to look for.
        assertEquals("إعلانان مكتوبان", TvStrings.announcementsCount(files = 0, written = 2))
        assertEquals("ملف واحد · 3 إعلانات مكتوبة", TvStrings.announcementsCount(files = 1, written = 3))
        assertEquals("ملفان", TvStrings.announcementsCount(files = 2, written = 0))
        assertEquals("لا إعلانات", TvStrings.announcementsCount(files = 0, written = 0))
    }

    @Test
    fun countsAgreeWithTheirNumbers() {
        assertEquals("5 ساعات و7 دقائق", TvStrings.hoursAndMinutes(5 * 60 + 7))
        assertEquals("ساعتان", TvStrings.hoursAndMinutes(120))
        assertEquals("دقيقة", TvStrings.hoursAndMinutes(1))
        assertEquals("ساعة و15 دقيقة", TvStrings.hoursAndMinutes(75))
        assertEquals("9 ثوانٍ", TvStrings.seconds(9))
        assertEquals("ونصان آخران", TvStrings.andOthers(2))
        assertEquals("و3 نصوص أخرى", TvStrings.andOthers(3))
        assertEquals("ونص آخر", TvStrings.andOthers(1))
        assertEquals(null, TvStrings.times(1))
        assertEquals("مرتان", TvStrings.times(2))
        assertEquals("33 مرة", TvStrings.times(33))
        assertEquals("103 مرات", TvStrings.times(103))
    }

    @Test
    fun verseRangesStayLeftToRight() {
        val isolated = "${Char(0x2066)}253–254${Char(0x2069)}"
        assertEquals("البقرة $isolated (قالون)", TvStrings.source("البقرة 253–254 (قالون)"))
        // A single number, or none, is left as it is.
        assertEquals("النساء 102", TvStrings.source("النساء 102"))
        assertEquals("البسملة", TvStrings.source("البسملة"))
    }

    @Test
    fun latinFoldersAndYearRangesStayLeftToRight() {
        val lri = Char(0x2066)
        val pdi = Char(0x2069)
        // Not «/backgrounds» nor «2035–2020» in the right-to-left pages.
        assertTrue(TvStrings.BACKGROUNDS_FOLDER_HINT.startsWith("${lri}backgrounds/$pdi "))
        assertTrue(TvStrings.ANNOUNCEMENTS_FOLDER_HINT.startsWith("${lri}announcements/$pdi "))
        assertTrue(TvStrings.offlineYears(2020, 2035).endsWith(" ${lri}2020–2035$pdi"))
    }

    @Test
    fun theSettingsHintGivesEveryWayWithTheDetectorsNumbers() {
        // The RLM keeps «5» with «مرات», not read next to «اضغط» as part of «OK 5».
        val rlm = Char(0x200F)
        assertEquals("لفتح الإعدادات: اضغط OK مطولًا 3 ثوانٍ، أو اضغط OK$rlm 5 مرات بسرعة، أو زر القائمة", TvStrings.HOLD_OK_HINT)
    }

    @Test
    fun phoneNumbersAndDatesInAnnouncementsStayLeftToRight() {
        fun ltr(text: String) = "${Char(0x2066)}$text${Char(0x2069)}"
        assertEquals("للتبرع الاتصال بالرقم ${ltr("98 123 456")}", TvStrings.mosqueText("للتبرع الاتصال بالرقم 98 123 456"))
        assertEquals("الدرس يوم ${ltr("25-10-2026")} بعد العصر", TvStrings.mosqueText("الدرس يوم 25-10-2026 بعد العصر"))
        assertEquals("الهاتف ${ltr("+216 98 123 456")}.", TvStrings.mosqueText("الهاتف +216 98 123 456."))
        assertEquals("الرقم ${ltr("٩٨ ١٢٣ ٤٥٦")}", TvStrings.mosqueText("الرقم ٩٨ ١٢٣ ٤٥٦"))
        // A time stays whole: its two numbers are never isolated apart.
        assertEquals("الدرس ${ltr("20:00")}", TvStrings.mosqueText("الدرس 20:00"))
        assertEquals("يوم ${ltr("25/10/2026")}", TvStrings.mosqueText("يوم 25/10/2026"))
        // A lone number, a list, a sentence's end and a line break are left as they are.
        assertEquals("الدرس رقم 3، ثم 4\n5 أيام", TvStrings.mosqueText("الدرس رقم 3، ثم 4\n5 أيام"))
        assertEquals("الطابق 2. 30 مقعدًا", TvStrings.mosqueText("الطابق 2. 30 مقعدًا"))
    }

    @Test
    fun timesAndCountdowns() {
        assertEquals("05:01", TvStrings.hm(LocalTime.of(5, 1)))
        assertEquals("01:24:48", TvStrings.countdown(3600 + 24 * 60 + 48))
        assertEquals("29:12", TvStrings.countdown(29 * 60 + 12))
        assertEquals("00:00", TvStrings.countdown(-5))
    }
}

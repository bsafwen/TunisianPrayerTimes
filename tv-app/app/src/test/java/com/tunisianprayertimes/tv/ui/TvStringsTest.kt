package com.tunisianprayertimes.tv.ui

import org.junit.Assert.assertEquals
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
    fun verseRangesStayLeftToRight() {
        val isolated = "${Char(0x2066)}253–254${Char(0x2069)}"
        assertEquals("البقرة $isolated (قالون)", TvStrings.source("البقرة 253–254 (قالون)"))
        // A single number, or none, is left as it is.
        assertEquals("النساء 102", TvStrings.source("النساء 102"))
        assertEquals("البسملة", TvStrings.source("البسملة"))
    }

    @Test
    fun timesAndCountdowns() {
        assertEquals("05:01", TvStrings.hm(LocalTime.of(5, 1)))
        assertEquals("01:24:48", TvStrings.countdown(3600 + 24 * 60 + 48))
        assertEquals("29:12", TvStrings.countdown(29 * 60 + 12))
        assertEquals("00:00", TvStrings.countdown(-5))
    }
}

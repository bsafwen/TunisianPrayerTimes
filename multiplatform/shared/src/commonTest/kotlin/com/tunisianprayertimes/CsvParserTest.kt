package com.tunisianprayertimes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CsvParserTest {

    @Test
    fun parsesValidCsv() {
        val csv = """
            Day,Fajr,Shuruk,Duhr,Asr,Maghrib,Isha
            1,05:30,06:50,12:15,15:30,18:10,19:30
            2,05:29,06:49,12:15,15:31,18:11,19:31
        """.trimIndent()

        val result = CsvParser.parsePrayerTimes(csv)

        assertEquals(2, result.size)

        val day1 = result[0]
        assertEquals(1, day1.day)
        assertEquals(5, day1.fajr.hour)
        assertEquals(30, day1.fajr.minute)
        assertEquals(Prayer.FAJR, day1.fajr.prayer)
        assertEquals(6, day1.shurukHour)
        assertEquals(50, day1.shurukMinute)
        assertEquals(12, day1.dhuhr.hour)
        assertEquals(15, day1.dhuhr.minute)
        assertEquals(15, day1.asr.hour)
        assertEquals(30, day1.asr.minute)
        assertEquals(18, day1.maghrib.hour)
        assertEquals(10, day1.maghrib.minute)
        assertEquals(19, day1.isha.hour)
        assertEquals(30, day1.isha.minute)

        val day2 = result[1]
        assertEquals(2, day2.day)
        assertEquals(5, day2.fajr.hour)
        assertEquals(29, day2.fajr.minute)
    }

    @Test
    fun skipsBlanksAndInvalidLines() {
        val csv = """
            Day,Fajr,Shuruk,Duhr,Asr,Maghrib,Isha

            bad line
            1,05:30,06:50,12:15,15:30,18:10,19:30

        """.trimIndent()

        val result = CsvParser.parsePrayerTimes(csv)
        assertEquals(1, result.size)
        assertEquals(1, result[0].day)
    }

    @Test
    fun emptyInputReturnsEmptyList() {
        val result = CsvParser.parsePrayerTimes("")
        assertTrue(result.isEmpty())
    }

    @Test
    fun headerOnlyReturnsEmptyList() {
        val csv = "Day,Fajr,Shuruk,Duhr,Asr,Maghrib,Isha"
        val result = CsvParser.parsePrayerTimes(csv)
        assertTrue(result.isEmpty())
    }
}

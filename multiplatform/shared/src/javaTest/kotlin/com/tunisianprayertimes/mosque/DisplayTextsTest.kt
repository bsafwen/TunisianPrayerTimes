package com.tunisianprayertimes.mosque

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DisplayTextsTest {

    @Test
    fun theHeaderVerseFollowsTheDay() {
        assertEquals(DisplayTexts.PRAYER_VERSES.first(), DisplayTexts.headerVerse(isRamadan = false, isFriday = false))
        assertEquals(DisplayTexts.FRIDAY_VERSE, DisplayTexts.headerVerse(isRamadan = false, isFriday = true))
        // A Friday in Ramadan keeps Ramadan's verse.
        assertEquals(DisplayTexts.RAMADAN_VERSE, DisplayTexts.headerVerse(isRamadan = true, isFriday = true))
    }

    @Test
    fun everyTextIsVocalizedAndSourced() {
        assertEquals(DisplayTexts.ALL.size, DisplayTexts.ALL.map { it.id }.toSet().size)
        DisplayTexts.ALL.forEach { text ->
            val letters = text.text.count { it in 'ء'..'ي' }
            val marks = text.text.count { it in 'ً'..'ْ' }
            assertTrue(marks >= letters / 2, "${text.id} is vocalized")
            assertTrue(text.reference.isNotBlank(), "${text.id} has a source")
        }
    }

    @Test
    fun versesUseTheQaloonMadaniCount() {
        // The Hafs (Kufan) count would say النساء 103 and البقرة 185.
        assertEquals(
            listOf(
                "النساء 102", "البقرة 236", "المؤمنون 1–2", "العنكبوت 45", "طه 131", "الإسراء 78",
                "البقرة 42", "طه 13", "البقرة 44", "لقمان 16", "هود 114", "إبراهيم 42",
            ),
            DisplayTexts.PRAYER_VERSES.map { it.reference },
        )
        assertEquals("البقرة 184", DisplayTexts.RAMADAN_VERSE.reference)
        assertEquals("الجمعة 9", DisplayTexts.FRIDAY_VERSE.reference)
    }

    @Test
    fun theBasmalaIsNotCitedAsAVerseOfAlFatiha() {
        // In the Madani count it opens the suras without being al-Fatiha's first verse, as it is in Hafs.
        assertEquals("البسملة", DisplayTexts.BASMALA.reference)
        assertEquals("بسم الله الرحمن الرحيم", DisplayTexts.BASMALA.text.filter { it in 'ء'..'ي' || it == ' ' })
        assertTrue(DisplayTexts.BASMALA in DisplayTexts.ALL)
    }

    @Test
    fun everyPrayerTimeMeetsEveryVerse() {
        val verses = DisplayTexts.PRAYER_VERSES
        // Six turns a day: before Fajr, then after each of the five adhans.
        for (turnOfDay in 0..5) {
            val seen = (0L until 2L * verses.size).map { day ->
                DisplayTexts.headerVerse(false, false, DisplayTexts.turnAt(java.time.LocalDate.ofEpochDay(20_000 + day), turnOfDay))
            }.toSet()
            assertEquals(verses.toSet(), seen, "turn $turnOfDay of the day")
        }
        // Consecutive turns never repeat a verse.
        (0L..200L).forEach { turn ->
            assertTrue(DisplayTexts.headerVerse(false, false, turn) != DisplayTexts.headerVerse(false, false, turn + 1))
        }
    }

    @Test
    fun theVersesFitOneHeaderLine() {
        DisplayTexts.PRAYER_VERSES.forEach { verse ->
            assertTrue(verse.text.count { it in 'ء'..'ي' } <= 45, "${verse.id} is short")
        }
    }
}

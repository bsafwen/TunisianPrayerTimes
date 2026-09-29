package com.tunisianprayertimes.mosque

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DisplayTextsTest {

    @Test
    fun theHeaderVerseFollowsTheDay() {
        assertEquals(DisplayTexts.TIMES_VERSE, DisplayTexts.headerVerse(isRamadan = false, isFriday = false))
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
        assertEquals("النساء 102", DisplayTexts.TIMES_VERSE.reference)
        assertEquals("البقرة 184", DisplayTexts.RAMADAN_VERSE.reference)
        assertEquals("الجمعة 9", DisplayTexts.FRIDAY_VERSE.reference)
    }
}

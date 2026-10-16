package com.tunisianprayertimes.adhkar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DhikrClosingTextsTest {
    private val rewards = DhikrClosingTexts.REWARDS

    @Test
    fun eachTextComesInTurnOncePerRound() {
        val day = 20_375L
        val round = (0 until rewards.size).map { DhikrClosingTexts.rewardFor(day + it) }
        assertEquals(rewards.toSet(), round.toSet())
        assertEquals(DhikrClosingTexts.rewardFor(day), DhikrClosingTexts.rewardFor(day + rewards.size))
        // Days before 1970 still land inside the list, and the turn carries on across the epoch.
        assertEquals(rewards.last(), DhikrClosingTexts.rewardFor(-1L))
        assertEquals(DhikrClosingTexts.rewardFor(day), DhikrClosingTexts.rewardFor(day - (day / rewards.size + 1) * rewards.size))
    }

    @Test
    fun theEveningOfADayMeetsAnotherTextThanItsMorning() {
        (0L until rewards.size).forEach { day ->
            assertNotEquals(DhikrClosingTexts.rewardFor(day, 0), DhikrClosingTexts.rewardFor(day, 3))
        }
    }

    @Test
    fun textsFollowTheCatalogsConventions() {
        assertEquals(rewards.size, rewards.map { it.id }.toSet().size)
        rewards.forEach { reward ->
            val codes = reward.text.map { it.code }
            // Western digits only, as everywhere in the app, verse markers and references included.
            assertTrue((reward.text + reward.reference).none { it.code in 0x0660..0x0669 || it.code in 0x06F0..0x06F9 }, reward.id)
            // No tatweel, and a vowelled alif maqsura is written as ya, as in the catalog.
            assertTrue(codes.none { it == 0x0640 }, reward.id)
            assertTrue(codes.zipWithNext().none { (letter, mark) -> letter == 0x0649 && mark in 0x064B..0x0652 }, reward.id)
            // The card shows the reference on its own line: the text carries none.
            assertTrue("\n" !in reward.text && "— " !in reward.text, reward.id)
            assertTrue(reward.reference.isNotBlank(), reward.id)
        }
        assertTrue(rewards.filter { it.quran }.all { r -> r.text.none { it == '«' || it == 'ﷺ' } })
        assertTrue(rewards.filterNot { it.quran }.all { it.reference.startsWith("صحيح ") && "ﷺ" in it.text })
    }
}

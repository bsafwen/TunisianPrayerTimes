package com.tunisianprayertimes.tv.data

import com.tunisianprayertimes.GouvernoratJsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GouvernoratRepositoryTest {

    private val repo = GouvernoratRepository(
        gouvernoratsJson = { TestData.gouvernoratsJson },
        prayerTimes = { TestData.prayerTimes },
    )

    @Test
    fun everyPublishedDelegationCanBeChosenAndHasTimes() {
        val bundled = GouvernoratJsonParser.parse(TestData.gouvernoratsJson).flatMap { it.delegations }.map { it.id }.toSet()
        val offered = repo.loadAll().flatMap { it.delegations }
        // Only delegations INM never publishes are hidden (scripts/prayer_formula/README.md).
        assertEquals(INM_UNPUBLISHED, bundled - offered.map { it.id }.toSet())
        for (delegation in offered) {
            assertNotNull("${delegation.nomAr} has no times", TestData.prayerTimes.loadDayPrayerTimes(delegation.id, 2027, 1, 1))
        }
    }

    @Test
    fun allGouvernoratsAreOffered() {
        assertEquals(24, repo.loadAll().size)
        assertTrue(repo.loadAll().all { it.delegations.isNotEmpty() })
    }

    private companion object {
        /** Arram (Medenine): INM publishes no prayer times for it in any year. */
        val INM_UNPUBLISHED = setOf(495)
    }
}

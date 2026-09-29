package com.tunisianprayertimes.tv.data

import com.tunisianprayertimes.Delegation
import com.tunisianprayertimes.Gouvernorat
import com.tunisianprayertimes.GouvernoratJsonParser
import com.tunisianprayertimes.InmPrayerTimes

/**
 * Gouvernorats and delegations offered in setup and settings. Only delegations with
 * formula parameters are listed, so every choice has prayer times.
 */
class GouvernoratRepository(
    private val gouvernoratsJson: () -> String,
    private val prayerTimes: () -> InmPrayerTimes,
) {

    fun loadAll(): List<Gouvernorat> {
        val source = prayerTimes()
        return GouvernoratJsonParser.parse(gouvernoratsJson())
            .map { gouvernorat -> gouvernorat.copy(delegations = gouvernorat.delegations.filter { source.hasDelegation(it.id) }) }
            .filter { it.delegations.isNotEmpty() }
    }
}

/** The gouvernorat and delegation with this id, or null when it is not offered. */
fun List<Gouvernorat>.findDelegation(id: Int): Pair<Gouvernorat, Delegation>? =
    firstNotNullOfOrNull { gouvernorat -> gouvernorat.delegations.find { it.id == id }?.let { gouvernorat to it } }

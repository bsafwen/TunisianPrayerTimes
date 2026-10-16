package com.tunisianprayertimes

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** No analytics where the EU's rules would need consent (plan M9's privacy review). */
class AnalyticsRegionTest {
    @Test
    fun analyticsStayOffWheneverTheSimTheNetworkOrThePlaceIsEuropean() {
        assertTrue(AnalyticsTracker.collectionAllowed(listOf("tn", "TN", "TN")))
        assertTrue(AnalyticsTracker.collectionAllowed(emptyList()))
        assertTrue(AnalyticsTracker.collectionAllowed(listOf("sa", "ae", "TR")))
        // A Tunisian SIM roaming in France, a French SIM at home, a place in Réunion, the UK, Switzerland, Norway.
        assertFalse(AnalyticsTracker.collectionAllowed(listOf("tn", "fr", "TN")))
        assertFalse(AnalyticsTracker.collectionAllowed(listOf("fr", "tn")))
        assertFalse(AnalyticsTracker.collectionAllowed(listOf("tn", "tn", "RE")))
        assertFalse(AnalyticsTracker.collectionAllowed(listOf("gb")))
        assertFalse(AnalyticsTracker.collectionAllowed(listOf("ch")))
        assertFalse(AnalyticsTracker.collectionAllowed(listOf("NO")))
    }
}

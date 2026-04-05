package com.tunisianprayertimes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GouvernoratModelsTest {

    @Test
    fun haversineKmSamePointIsZero() {
        val d = haversineKm(36.8, 10.18, 36.8, 10.18)
        assertEquals(0.0, d, 0.001)
    }

    @Test
    fun haversineKmKnownDistance() {
        // Tunis (36.8065, 10.1815) to Sfax (34.7406, 10.7603) ≈ 232 km
        val d = haversineKm(36.8065, 10.1815, 34.7406, 10.7603)
        assertTrue(d > 220 && d < 245, "Expected ~232km, got $d")
    }

    @Test
    fun delegationDisplayNameWithGouvernorat() {
        val d = Delegation(1, "Centre", "المركز", "Centre", "تونس", 0.0, 0.0)
        assertEquals("المركز (تونس)", d.displayName())
    }

    @Test
    fun delegationDisplayNameWithoutGouvernorat() {
        val d = Delegation(1, "Centre", "المركز", "Centre", "", 0.0, 0.0)
        assertEquals("المركز", d.displayName())
    }

    @Test
    fun delegationSearchableTextContainsAllNames() {
        val d = Delegation(1, "Bab Bhar", "باب البحر", "Bab Bhar", "Tunis", 0.0, 0.0)
        val text = d.searchableText()
        assertTrue("bab bhar" in text)
        assertTrue("باب البحر" in text)
        assertTrue("tunis" in text)
    }

    @Test
    fun gouvernoratDisplayName() {
        val g = Gouvernorat(1, "Tunis", "تونس", "Tunis", emptyList())
        assertEquals("تونس", g.displayName())
    }
}

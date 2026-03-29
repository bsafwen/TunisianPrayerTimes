package com.tunisianprayertimes

import com.tunisianprayertimes.ui.delegationSearchScore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DelegationSearchScoreTest {

    private fun delegation(nomFr: String, nomAr: String, nomEn: String, govName: String = "") =
        Delegation(id = 1, nomFr = nomFr, nomAr = nomAr, nomEn = nomEn, gouvernoratName = govName)

    private fun terms(query: String) = query.lowercase().trim().split(" ").filter { it.isNotEmpty() }

    // --- Exact match (score 4) ---

    @Test
    fun `exact match on nomAr returns 4`() {
        val d = delegation("Bardo", "باردو", "Bardo", "تونس")
        assertEquals(4, delegationSearchScore(d, terms("باردو")))
    }

    @Test
    fun `exact match on nomFr returns 4`() {
        val d = delegation("Bardo", "باردو", "Bardo", "تونس")
        assertEquals(4, delegationSearchScore(d, terms("bardo")))
    }

    @Test
    fun `exact match on nomEn returns 4`() {
        val d = delegation("La Marsa", "المرسى", "La Marsa", "تونس")
        assertEquals(4, delegationSearchScore(d, terms("la marsa")))
    }

    // --- Starts-with match (score 3) ---

    @Test
    fun `starts with on nomAr returns 3`() {
        val d = delegation("Omrane Superieur", "العمران الأعلى", "Omrane Superieur", "تونس")
        assertEquals(3, delegationSearchScore(d, terms("العمران")))
    }

    @Test
    fun `starts with on nomFr returns 3`() {
        val d = delegation("Omrane Superieur", "العمران الأعلى", "Omrane Superieur", "تونس")
        assertEquals(3, delegationSearchScore(d, terms("omrane")))
    }

    // --- Contains match (score 2) ---

    @Test
    fun `contains term in nomAr returns 2`() {
        val d = delegation("Tunis", "مدينة تونس", "Tunis", "تونس")
        assertEquals(2, delegationSearchScore(d, terms("تونس")))
    }

    @Test
    fun `contains term in nomFr returns 2`() {
        val d = delegation("Cite El Khadhra", "حي الخضراء", "Cite El Khadhra", "تونس")
        assertEquals(2, delegationSearchScore(d, terms("khadhra")))
    }

    // --- No name match (score 0) ---

    @Test
    fun `no name match returns 0`() {
        val d = delegation("La Marsa", "المرسى", "La Marsa", "تونس")
        assertEquals(0, delegationSearchScore(d, terms("تونس")))
    }

    @Test
    fun `gouvernorat name is not considered in scoring`() {
        val d = delegation("Carthage", "قرطاج", "Carthage", "تونس")
        assertEquals(0, delegationSearchScore(d, terms("تونس")))
    }

    // --- Ranking order ---

    @Test
    fun `exact match ranks above starts-with`() {
        val exact = delegation("Bardo", "باردو", "Bardo")
        val prefix = delegation("Bardo Superieur", "باردو الأعلى", "Bardo Superieur")
        assertTrue(delegationSearchScore(exact, terms("باردو")) > delegationSearchScore(prefix, terms("باردو")))
    }

    @Test
    fun `starts-with ranks above contains`() {
        val prefix = delegation("Omrane", "العمران", "Omrane")
        val contains = delegation("Omrane Superieur", "العمران الأعلى", "Omrane Superieur")
        // "العمران" is exact for prefix, and starts-with for contains
        // Let's use a query that's a prefix for one and contains for another
        val prefixD = delegation("Tunis Centre", "تونس المدينة", "Tunis Centre")
        val containsD = delegation("Medina Tunis", "مدينة تونس", "Medina Tunis")
        assertTrue(delegationSearchScore(prefixD, terms("تونس")) > delegationSearchScore(containsD, terms("تونس")))
    }

    @Test
    fun `contains ranks above no match`() {
        val contains = delegation("Tunis", "مدينة تونس", "Tunis", "تونس")
        val noMatch = delegation("La Marsa", "المرسى", "La Marsa", "تونس")
        assertTrue(delegationSearchScore(contains, terms("تونس")) > delegationSearchScore(noMatch, terms("تونس")))
    }

    @Test
    fun `tunis search ranks madina tunis above la marsa`() {
        val madinaTunis = delegation("Tunis", "مدينة تونس", "Tunis", "تونس")
        val laMarsa = delegation("La Marsa", "المرسى", "La Marsa", "تونس")
        val carthage = delegation("Carthage", "قرطاج", "Carthage", "تونس")
        val query = terms("تونس")

        val sorted = listOf(laMarsa, carthage, madinaTunis).sortedByDescending { delegationSearchScore(it, query) }
        assertEquals("مدينة تونس", sorted.first().nomAr)
    }

    // --- Edge cases ---

    @Test
    fun `empty search terms returns 0`() {
        val d = delegation("Bardo", "باردو", "Bardo", "تونس")
        assertEquals(0, delegationSearchScore(d, emptyList()))
    }

    @Test
    fun `case insensitive matching for latin names`() {
        val d = delegation("La Marsa", "المرسى", "La Marsa")
        assertEquals(4, delegationSearchScore(d, terms("LA MARSA")))
    }
}

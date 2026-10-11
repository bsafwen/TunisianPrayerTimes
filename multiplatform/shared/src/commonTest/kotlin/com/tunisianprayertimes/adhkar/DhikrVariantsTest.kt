package com.tunisianprayertimes.adhkar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DhikrVariantsTest {
    private val salawat = DhikrCatalog.find(DhikrCatalog.SALAWAT_ID)!!

    @Test
    fun entryOwnWordingIsTheFirstVariant() {
        val first = salawat.variants.first()
        assertEquals(salawat.text, first.text)
        assertEquals(salawat.reference, first.reference)
        assertEquals(salawat.narration, first.narration)
        assertEquals(first, salawat.currentVariant)
    }

    @Test
    fun variantsAreDistinctWordings() {
        assertEquals(5, salawat.variants.size)
        assertEquals(salawat.variants.size, salawat.variants.map { it.id }.toSet().size)
        assertEquals(salawat.variants.size, salawat.variants.map { it.text }.toSet().size)
        assertTrue(salawat.variants.all { it.narration.contains("\n— ") })
    }

    @Test
    fun choosingAWordingKeepsTheSameDhikr() {
        val other = salawat.variants[2]
        val read = salawat.withVariant(other.id)
        assertEquals(salawat.id, read.id)
        assertEquals(salawat.defaultCount, read.defaultCount)
        assertEquals(other.text, read.text)
        assertEquals(other.reference, read.reference)
        assertEquals(other.narration, read.narration)
        assertEquals(other, read.currentVariant)
        assertNotEquals(salawat.text, read.text)
    }

    @Test
    fun ownOrUnknownWordingLeavesTheEntryUnchanged() {
        assertSame(salawat, salawat.withVariant(salawat.variants.first().id))
        assertSame(salawat, salawat.withVariant("missing"))
        assertSame(salawat, salawat.withVariant(null))
    }
}

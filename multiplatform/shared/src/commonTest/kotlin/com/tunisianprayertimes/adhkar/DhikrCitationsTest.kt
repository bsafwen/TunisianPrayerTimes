package com.tunisianprayertimes.adhkar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DhikrCitationsTest {
    @Test
    fun keepsOnlyTheCitedWorks() {
        assertEquals("صحيح البخاري 3293؛ صحيح مسلم 2691", dhikrCitations("صحيح البخاري 3293؛ صحيح مسلم 2691"))
        assertEquals("سنن أبي داود 996", dhikrCitations("سنن أبي داود 996؛ صححه الألباني"))
        assertEquals("سنن الترمذي 3428", dhikrCitations("سنن الترمذي 3428؛ حسّنه الألباني وضعّفه غيره، ورجّح المصدر ضعفه"))
        assertEquals("صحيح البخاري 3282؛ صحيح مسلم 2610",
            dhikrCitations("صحيح البخاري 3282؛ وفي صحيح مسلم 2610: «من الشيطان الرجيم»"))
    }

    @Test
    fun gathersNumbersUnderTheirWork() {
        assertEquals("صحيح مسلم 2709، 2708", dhikrCitations("مساءً: صحيح مسلم 2709؛ وعند نزول المنزل: صحيح مسلم 2708"))
        assertEquals("صحيح البخاري 3369، 6360؛ صحيح مسلم 407",
            dhikrCitations("صحيح البخاري 3369 و6360؛ صحيح مسلم 407؛ متفق عليه من حديث أبي حميد الساعدي، واللفظ للبخاري"))
    }

    @Test
    fun citesQuranPassagesAndUnnumberedWorks() {
        assertEquals("البقرة 253–254؛ صحيح البخاري 2311؛ مستدرك الحاكم؛ السنن الكبرى للنسائي", dhikrCitations(
            "البقرة 253–254 (قالون، العد المدني الأخير)؛ صحيح البخاري 2311؛ مستدرك الحاكم (حديث أبي بن كعب)؛ السنن الكبرى للنسائي (حديث أبي أمامة)؛ وصححهما الألباني"))
        assertEquals("مسند أحمد", dhikrCitations("مسند أحمد (حديث عبد الرحمن بن أبزى)"))
        assertEquals("أثر عن الحسن البصري", dhikrCitations("أثر عن الحسن البصري، وليس حديثًا مرفوعًا"))
    }

    @Test
    fun unknownSourceKeepsItsFirstClause() {
        assertEquals("كتاب آخر", dhikrCitations("كتاب آخر؛ ملاحظة"))
    }

    /** A work missing from the lists would drop its numbers; every number in the catalog must survive. */
    @Test
    fun everyCatalogCitationKeepsItsNumbers() {
        val references = DhikrCatalog.entries.flatMap { entry -> listOf(entry.reference) + entry.variants.map { it.reference } }
        val digits = Regex("\\d+")
        references.forEach { reference ->
            val citations = dhikrCitations(reference)
            assertTrue(citations.isNotBlank(), reference)
            assertEquals(digits.findAll(reference).map { it.value }.toSet(), digits.findAll(citations).map { it.value }.toSet(), reference)
        }
    }
}

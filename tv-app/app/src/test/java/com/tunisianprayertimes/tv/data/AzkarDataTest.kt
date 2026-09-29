package com.tunisianprayertimes.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The TV must show the phone's reviewed after-salah wording unchanged, never an edited copy. */
class AzkarDataTest {

    private val catalog = TestData.phoneDhikrCatalog

    /** The quoted value of `field = "..."` inside the phone catalog entry with this id. */
    private fun entryField(id: String, field: String): String {
        val entry = catalog.substringAfter("id = \"$id\"", missingDelimiterValue = "").substringBefore("DhikrEntry(")
        check(entry.isNotEmpty()) { "phone catalog has no entry $id" }
        return Regex("""$field = "([^"]*)"""").find(entry)?.groupValues?.get(1) ?: error("$id has no $field")
    }

    /** (text, repetitions) of the steps of the 'hundred' dhikr, e.g. DhikrStep("التسبيح", "سُبْحَانَ اللَّهِ", 33). */
    private val hundredSteps: List<Pair<String, Int>> =
        Regex("""DhikrStep\("[^"]*", "([^"]*)", (\d+)\)""").findAll(catalog.substringAfter("salahHundredSteps = listOf("))
            .take(4).map { it.groupValues[1] to it.groupValues[2].toInt() }.toList()

    private val hundredReference: String =
        Regex("""id = SALAH_HUNDRED_ID,[\s\S]*?reference = "([^"]*)"""").find(catalog)!!.groupValues[1]

    @Test
    fun afterSalahCardsAreThePhoneEntriesVerbatim() {
        val expected = listOf(
            Triple(entryField("salah_istighfar", "text"), "3 مرات", entryField("salah_istighfar", "reference")),
            Triple(entryField("salah_salam", "text"), "مرة واحدة", entryField("salah_salam", "reference")),
            Triple(entryField("salah_la_mani", "text"), "مرة واحدة", entryField("salah_la_mani", "reference")),
        ) + hundredSteps.map { (text, count) ->
            Triple(text, if (count == 1) "مرة واحدة" else "$count مرة", hundredReference)
        }
        assertEquals(expected, AzkarData.AFTER_SALAH_AZKAR.map { Triple(it.text, it.repetition, it.source) })
    }

    @Test
    fun phoneCountsAreTheOnesShown() {
        assertTrue(Regex("""id = "salah_istighfar"[\s\S]*?defaultCount = 3""").containsMatchIn(catalog))
        assertEquals(listOf(33, 33, 33, 1), hundredSteps.map { it.second })
    }

    @Test
    fun tickerOnlyRepeatsCardTexts() {
        val cardTexts = AzkarData.AFTER_SALAH_AZKAR.map { it.text }.toSet()
        assertTrue((AzkarData.TICKER_ITEMS + AzkarData.RAMADAN_TICKER_ITEMS).all { it in cardTexts })
    }
}

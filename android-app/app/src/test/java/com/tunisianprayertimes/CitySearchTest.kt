package com.tunisianprayertimes

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The picker's city search on the bundled catalogue (plan 6.8): every way of typing a city finds it, at once. */
class CitySearchTest {
    private val index: CitySearch.Index by lazy {
        CitySearch.Index(
            listOf("src/main/assets/places/europe-cities.tsv", "src/main/assets/places/arab-cities.tsv")
                .flatMap { PlaceCatalogue.parse(File(it).readLines().asSequence()) }
                .filter { it.listed },
        )
    }

    private fun first(query: String) = index.search(query).firstOrNull()?.name

    @Test
    fun ivrySurSeineIsFoundHoweverItIsTyped() {
        // The owner typed «ivry sur seine» and found nothing (2026-10-18): the search folded all 26,600 cities' names
        // again on every key, a second or more on a phone, so the sheet showed the previous query's cities meanwhile.
        for (query in listOf("ivry sur seine", "Ivry-sur-Seine", "IVRY SUR SEINE", "ivry", "ivry sur", "إيفري سور سين", "إيفري")) {
            assertEquals(query, "Ivry-sur-Seine", first(query))
        }
        // From any word of the name, not only its first.
        assertTrue(index.search("sur seine").any { it.name == "Ivry-sur-Seine" })
        assertTrue(index.search("seine").any { it.name == "Ivry-sur-Seine" })
    }

    @Test
    fun aQueryScansTheFoldedNamesOnly() {
        val queries = listOf("i", "iv", "ivr", "ivry", "ivry s", "ivry sur", "ivry sur seine", "paris", "köln", "الرياض")
        index.search("warm up")
        val started = System.nanoTime()
        queries.forEach { index.search(it) }
        val ms = (System.nanoTime() - started) / 1_000_000
        // Folding every name on every key took about 1.4 s for these ten queries on the development computer.
        assertTrue("ten queries took $ms ms", ms < 500)
    }
}

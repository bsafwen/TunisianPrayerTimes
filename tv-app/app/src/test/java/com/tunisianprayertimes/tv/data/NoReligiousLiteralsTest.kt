package com.tunisianprayertimes.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every Quran, hadith or dua text on the TV comes from the shared reviewed catalog (or the mosque's
 * own sourced texts from USB), never from a string typed into the TV app, where it would escape review.
 */
class NoReligiousLiteralsTest {

    private val literal = Regex(""""([^"\\]|\\.)*"""")
    private val tokens = listOf("اللهم", "رواه", "صحيح البخاري", "صحيح مسلم", "سنن ")

    /** Mostly-vocalized Arabic is quoted text, not an interface label (labels carry a mark or two at most). */
    private fun isVocalized(text: String): Boolean {
        val letters = text.count { it in 'ء'..'ي' }
        val marks = text.count { it in 'ً'..'ْ' }
        return letters >= 8 && marks >= letters * 0.3
    }

    @Test
    fun theAppSourcesHoldNoReligiousText() {
        val sources = TestData.tvSources.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("found the sources", sources.size > 20)
        val found = sources.flatMap { file ->
            file.readLines().withIndex().flatMap { (index, line) ->
                literal.findAll(line).map { it.value }
                    .filter { text -> isVocalized(text) || tokens.any(text::contains) }
                    .map { "${file.name}:${index + 1}: ${it.take(60)}" }
            }
        }
        assertEquals(emptyList<String>(), found)
    }
}

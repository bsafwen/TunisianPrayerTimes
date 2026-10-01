package com.tunisianprayertimes.tv.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every Quran, hadith or dua text on the TV comes from the shared reviewed catalog (or the mosque's
 * own sourced texts from USB), never from a string typed into the TV app, where it would escape review:
 * not in the code of any source set (main, the flavors, debug), nor in its string resources.
 */
class NoReligiousLiteralsTest {

    /** A raw string, whatever its lines, or a one-line string. */
    private val literal = Regex(""""{3}[\s\S]*?"{3}|"([^"\\\n]|\\.)*"""")
    /** The text of a resource: a string, or an item of an array or plural. */
    private val resourceText = Regex(""">([^<]+)<""")
    // The basmala is short and was typed bare: it is caught by its words, not by its marks.
    private val tokens = listOf("اللهم", "رواه", "صحيح البخاري", "صحيح مسلم", "سنن ", "بسم الله")

    /** Mostly-vocalized Arabic is quoted text, not an interface label (labels carry a mark or two at most). */
    private fun isVocalized(text: String): Boolean {
        val letters = text.count { it in 'ء'..'ي' }
        val marks = text.count { it in 'ً'..'ْ' }
        return letters >= 8 && marks >= letters * 0.3
    }

    private fun isReligious(text: String): Boolean = isVocalized(text) || tokens.any(text::contains)

    /** Each text [pattern] finds in [file] that looks religious, with its line. */
    private fun religiousTexts(file: File, pattern: Regex): List<String> {
        val content = file.readText()
        return pattern.findAll(content).filter { isReligious(it.value) }
            .map { "${file.parentFile.name}/${file.name}:${content.lineNumberAt(it.range.first)}: ${it.value.trim().take(60)}" }
            .toList()
    }

    private fun String.lineNumberAt(index: Int): Int = 1 + substring(0, index).count { it == '\n' }

    @Test
    fun theAppSourcesHoldNoReligiousText() {
        val sources = TestData.appSourceSets.flatMap { set ->
            listOf("java", "kotlin").map { File(set, it) }.filter { it.isDirectory }
                .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        }
        assertTrue("found the sources", sources.size > 20)
        assertTrue("found the flavors' sources", sources.any { "github" in it.path } && sources.any { "debug" in it.path })
        assertEquals(emptyList<String>(), sources.flatMap { religiousTexts(it, literal) })
    }

    @Test
    fun theAppResourcesHoldNoReligiousText() {
        val resources = TestData.appSourceSets.flatMap { set ->
            File(set, "res").listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("values") }
                .flatMap { dir -> dir.listFiles().orEmpty().filter { it.extension == "xml" } }
        }
        assertTrue("found the string resources", resources.any { it.name == "strings.xml" })
        assertEquals(emptyList<String>(), resources.flatMap { religiousTexts(it, resourceText) })
    }

    @Test
    fun aMultiLineRawStringIsReadWhole() {
        val dua = "\"\"\"\nاللهم\n\"\"\""
        assertEquals(listOf(dua), literal.findAll("val a = $dua").map { it.value }.filter(::isReligious).toList())
    }
}

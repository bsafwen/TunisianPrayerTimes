package com.tunisianprayertimes.tv

import com.tunisianprayertimes.tv.data.TestData
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The drawables the manifests name (the TV banner, the icon) exist and can be drawn: a `<bitmap>`
 * without `android:src` fails its whole drawable, and the TV launcher then shows no banner at all.
 */
class ManifestResourcesTest {

    private val reference = Regex("""@(drawable|mipmap)/(\w+)""")
    private val bitmapWithoutSource = Regex("""<bitmap(?![^>]*android:src)[^>]*>""")

    @Test
    fun theDrawablesTheManifestsNameExistAndCanBeDrawn() {
        val sets = TestData.appSourceSets
        val named = sets.map { File(it, "AndroidManifest.xml") }.filter { it.isFile }
            .flatMap { manifest -> reference.findAll(manifest.readText()).map { it.groupValues[1] to it.groupValues[2] }.toList() }
            .toSet()
        assertTrue("the manifest names the TV banner", "drawable" to "tv_banner" in named)

        val problems = named.flatMap { (type, name) ->
            val files = sets.flatMap { set -> File(set, "res").listFiles().orEmpty().filter { it.name == type || it.name.startsWith("$type-") } }
                .mapNotNull { dir -> dir.listFiles().orEmpty().firstOrNull { it.nameWithoutExtension == name } }
            if (files.isEmpty()) listOf("@$type/$name: no file")
            else files.filter { it.extension == "xml" && bitmapWithoutSource.containsMatchIn(it.readText()) }
                .map { "${it.parentFile.name}/${it.name}: <bitmap> without android:src" }
        }
        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun aBitmapWithoutSourceIsFound() {
        assertTrue(bitmapWithoutSource.containsMatchIn("""<item><bitmap android:gravity="center" /></item>"""))
        assertTrue(!bitmapWithoutSource.containsMatchIn("""<bitmap android:src="@drawable/star" android:gravity="center" />"""))
    }
}

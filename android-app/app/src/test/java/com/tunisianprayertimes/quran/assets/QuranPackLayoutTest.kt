package com.tunisianprayertimes.quran.assets

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class QuranPackLayoutTest {
    private fun pack(name: String, kind: String, files: List<String>, surahs: IntRange? = null, key: String = "v1/packs/$name-0123456789ab.zip", delivery: String? = null) =
        JSONObject().put("name", name).put("kind", kind).put("bytes", files.size * 10L)
            .apply { if (delivery != null) put("delivery", delivery) }
            .put("archive", JSONObject().put("key", key).put("bytes", files.size * 10L + 100).put("sha256", "a".repeat(64)))
            .put("files", JSONArray(files.map { JSONObject().put("path", it).put("bytes", 10) }))
            .apply { if (surahs != null) put("reciterId", "hosary-qaloun").put("surahs", JSONArray(listOf(surahs.first, surahs.last))) }

    private fun layout(vararg packs: JSONObject, cdn: String = "https://cdn.example/") =
        JSONObject().put("version", 1).put("cdn", cdn).put("packs", JSONArray(packs.toList())).toString()

    private val sample = layout(
        pack("quran_pages", "pages", listOf("quran/pages/001.webp", "quran/pages/002.webp")),
        pack("quran_hosary_01", "audio", listOf("quran/audio/hosary/001.mp3"), 1..1),
        pack("quran_hosary_02", "audio", listOf("quran/audio/hosary/002.mp3"), 2..2),
        pack("quran_hosary_03", "audio", (3..5).map { "quran/audio/hosary/%03d.mp3".format(it) }, 3..5, delivery = "play"),
        pack("quran_later_01", "audio", listOf("quran/audio/later/001.mp3"), 1..1, delivery = "cdn")
            .put("reciterId", "later"),
        cdn = "https://cdn.example",
    )

    @Test
    fun filesAndChaptersFindTheirPacks() {
        val layout = QuranPackLayout.parse(sample)
        assertEquals("quran_pages", layout.pages?.name)
        assertEquals("quran_hosary_03", layout.packOf("quran/audio/hosary/004.mp3")?.name)
        assertNull(layout.packOf("quran/audio/hosary/006.mp3"))
        assertEquals(listOf("quran_hosary_02", "quran_hosary_03"), layout.audioPacks("hosary-qaloun", 2..3).map { it.name })
        assertEquals(listOf("quran_hosary_03"), layout.audioPacks("hosary-qaloun", 5..5).map { it.name })
        assertTrue(layout.audioPacks("other-reciter", 1..114).isEmpty())
        // The base URL always ends with a slash before the key is appended.
        assertEquals("https://cdn.example/v1/packs/quran_hosary_01-0123456789ab.zip", layout.archiveUrl(layout.packs[1]))
    }

    @Test
    fun packsSayWhetherGooglePlayCarriesThem() {
        val layout = QuranPackLayout.parse(sample)
        // Layouts from before the delivery field only had Play packs.
        assertEquals(QuranPack.Delivery.Play, layout.packOf("quran/audio/hosary/001.mp3")?.delivery)
        assertEquals(QuranPack.Delivery.Cdn, layout.packOf("quran/audio/later/001.mp3")?.delivery)
        assertEquals(listOf("quran_pages", "quran_hosary_01", "quran_hosary_02", "quran_hosary_03"), layout.playPacks().packs.map { it.name })
        assertThrows(IllegalStateException::class.java) {
            QuranPackLayout.parse(layout(pack("quran_pages", "pages", listOf("quran/pages/1.webp"), delivery = "sideload")))
        }
    }

    @Test
    fun layoutsThatCouldEscapeOrMislabelArePrevented() {
        val traversal = layout(pack("quran_pages", "pages", listOf("quran/../../shared_prefs/x.xml")))
        val foreignKey = layout(pack("quran_pages", "pages", listOf("quran/pages/1.webp"), key = "../secret.zip"))
        val audioWithoutChapters = layout(pack("quran_hosary_01", "audio", listOf("quran/audio/hosary/001.mp3")))
        val duplicate = layout(pack("quran_pages", "pages", listOf("quran/pages/1.webp")), pack("quran_pages", "pages", listOf("quran/pages/2.webp")))
        val plainHttpsless = layout(pack("quran_pages", "pages", listOf("quran/pages/1.webp")), cdn = "file:///sdcard/")
        for (json in listOf(traversal, foreignKey, audioWithoutChapters, duplicate, plainHttpsless)) {
            assertThrows(IllegalArgumentException::class.java) { QuranPackLayout.parse(json) }
        }
    }

    /** The committed layout agrees with the recitation timings and the page list the app reads. */
    @Test
    fun committedLayoutCoversEveryRecitationAndPageExactlyOnce() {
        val packsJson = findRepoFile("android-app/app/src/main/assets/quran/packs.json")
        assumeTrue("No Quran pack layout committed yet", packsJson != null)
        val layout = QuranPackLayout.parse(packsJson!!.readText())
        val recitations = requireNotNull(findRepoFile("android-app/app/src/main/assets/quran/audio")).listFiles().orEmpty()
            .map { File(it, "timings.json") }.filter { it.isFile }
        assertTrue("No recitation timings committed", recitations.isNotEmpty())
        val pages = JSONObject(requireNotNull(findRepoFile("android-app/app/src/main/assets/quran/pages/pages.json")).readText())

        for (file in recitations) {
            val timings = JSONObject(file.readText())
            val audio = layout.audioPacks(timings.getString("reciterId"))
            assertEquals((1..114).toList(), audio.flatMap { it.surahs!!.toList() })
            val surahs = timings.getJSONArray("surahs")
            assertEquals(List(surahs.length()) { surahs.getJSONObject(it).getString("assetPath") }.sorted(),
                audio.flatMap { pack -> pack.files.map { it.path } }.sorted())
            assertEquals("A reciter comes from one place", 1, audio.map { it.delivery }.distinct().size)
            if (audio.first().delivery == QuranPack.Delivery.Play) assertTrue(audio.size <= 9)
        }
        val scans = pages.getJSONArray("pages")
        assertEquals(List(scans.length()) { "quran/pages/" + scans.getJSONObject(it).getString("file") }.sorted(),
            layout.pages!!.files.map { it.path }.sorted())
        assertEquals(QuranPack.Delivery.Play, layout.pages!!.delivery)

        val fromPlay = layout.playPacks().packs
        assertTrue("A bundle holds at most 100 asset packs", fromPlay.size <= 100)
        assertTrue("Play carries at most ten reciters", fromPlay.mapNotNull { it.reciterId }.distinct().size <= 10)
        layout.packs.forEach { pack ->
            assertTrue("${pack.name} would need mobile-data consent", pack.archive.bytes < 200_000_000L)
            assertEquals(pack.files.sumOf { it.bytes }, pack.bytes)
            val module = findRepoFile("android-app/quran-packs/${pack.name}/build.gradle.kts")?.isFile == true
            assertEquals("${pack.name} must be a module exactly when Play carries it", pack in fromPlay, module)
        }
        val manifest = requireNotNull(findRepoFile("android-app/quran-assets/manifest.tsv")).readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("pack\t") }
            .map { it.split('\t').let { columns -> listOf(columns[0], columns[1], columns[2], columns[5]) } }
        assertEquals(layout.packs.flatMap { pack -> pack.files.map { listOf(pack.name, it.path, it.bytes.toString(), pack.delivery.name.lowercase()) } }
            .sortedBy { it[1] }, manifest.sortedBy { it[1] })
    }

    private fun findRepoFile(relativePath: String): File? {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val candidate = File(directory ?: return null, relativePath)
            if (candidate.exists()) return candidate
            directory = directory?.parentFile
        }
        return null
    }
}

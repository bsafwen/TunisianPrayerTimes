package com.tunisianprayertimes.quran.assets

import android.content.Context
import org.json.JSONObject
import java.io.FileNotFoundException

/** One file of a pack, at the same relative path as a bundled asset (e.g. quran/audio/hosary/001.mp3). */
data class QuranPackFile(val path: String, val bytes: Long)

/** The zip the quran-cdn Worker serves for a pack; its key is content-addressed and never reused. */
data class QuranPackArchive(val key: String, val bytes: Long, val sha256: String)

/**
 * Media the base app does not carry. Every install can download a pack's archive from the
 * quran-cdn Worker; Google Play also delivers [Delivery.Play] packs to Play installs.
 */
data class QuranPack(
    val name: String,
    val kind: Kind,
    val delivery: Delivery,
    /** For recitations: whose, and which consecutive chapters. */
    val reciterId: String?,
    val surahs: IntRange?,
    val bytes: Long,
    val archive: QuranPackArchive,
    val files: List<QuranPackFile>,
) {
    enum class Kind { Pages, Audio }

    enum class Delivery {
        /** A Play Asset Delivery pack in the bundle: the page scans and the first ten reciters. */
        Play,
        /** Only the quran-cdn Worker serves it, on Play installs too. */
        Cdn,
    }
}

/** quran/packs.json, written by scripts/quran_assets.py layout. */
class QuranPackLayout(
    /** Base URL of the quran-cdn Worker, ending with a slash. */
    val cdn: String,
    val packs: List<QuranPack>,
) {
    private val byPath = packs.flatMap { pack -> pack.files.map { it.path to pack } }.toMap()

    val pages: QuranPack? = packs.firstOrNull { it.kind == QuranPack.Kind.Pages }

    /** The same layout limited to the packs Google Play delivers. */
    fun playPacks(): QuranPackLayout = QuranPackLayout(cdn, packs.filter { it.delivery == QuranPack.Delivery.Play })

    fun packOf(assetPath: String): QuranPack? = byPath[assetPath]

    fun audioPacks(reciterId: String): List<QuranPack> = packs.filter { it.kind == QuranPack.Kind.Audio && it.reciterId == reciterId }

    /** The packs holding chapters [surahs] of [reciterId]'s recitation, in order. */
    fun audioPacks(reciterId: String, surahs: IntRange): List<QuranPack> =
        audioPacks(reciterId).filter { pack -> pack.surahs?.let { it.first <= surahs.last && surahs.first <= it.last } == true }

    fun archiveUrl(pack: QuranPack): String = cdn + pack.archive.key

    companion object {
        const val ASSET = "quran/packs.json"

        @Volatile private var cached: Result<QuranPackLayout?>? = null

        /** Null when this build carries every Quran file itself (no packs.json). Call on IO. */
        fun load(context: Context): QuranPackLayout? = (cached ?: synchronized(this) {
            cached ?: runCatching {
                try {
                    parse(context.assets.open(ASSET).bufferedReader().use { it.readText() })
                } catch (_: FileNotFoundException) {
                    null
                }
            }.also { cached = it }
        }).getOrThrow()

        fun parse(json: String): QuranPackLayout {
            val source = JSONObject(json)
            require(source.getInt("version") == 1) { "Unknown Quran pack layout" }
            val cdn = source.getString("cdn").let { if (it.endsWith('/')) it else "$it/" }
            require(cdn.startsWith("https://") || cdn.startsWith("http://")) { "Invalid Quran CDN" }
            val items = source.getJSONArray("packs")
            val packs = List(items.length()) { index ->
                val item = items.getJSONObject(index)
                val archive = item.getJSONObject("archive")
                val files = item.getJSONArray("files")
                val range = item.optJSONArray("surahs")
                QuranPack(
                    name = item.getString("name"),
                    kind = when (val kind = item.getString("kind")) {
                        "pages" -> QuranPack.Kind.Pages
                        "audio" -> QuranPack.Kind.Audio
                        else -> error("Unknown Quran pack kind $kind")
                    },
                    delivery = when (val delivery = item.optString("delivery", "play")) {
                        "play" -> QuranPack.Delivery.Play
                        "cdn" -> QuranPack.Delivery.Cdn
                        else -> error("Unknown Quran pack delivery $delivery")
                    },
                    reciterId = item.optString("reciterId").takeIf { it.isNotEmpty() },
                    surahs = range?.let { it.getInt(0)..it.getInt(1) },
                    bytes = item.getLong("bytes"),
                    archive = QuranPackArchive(archive.getString("key"), archive.getLong("bytes"), archive.getString("sha256")),
                    files = List(files.length()) { files.getJSONObject(it).let { file -> QuranPackFile(file.getString("path"), file.getLong("bytes")) } },
                )
            }
            packs.forEach { pack ->
                require(pack.name.matches(PACK_NAME)) { "Invalid Quran pack name ${pack.name}" }
                require(pack.archive.key.matches(ARCHIVE_KEY)) { "Invalid Quran pack archive ${pack.archive.key}" }
                require(pack.files.isNotEmpty() && pack.files.all { it.path.startsWith("quran/") && ".." !in it.path.split('/') }) {
                    "Invalid Quran pack files in ${pack.name}"
                }
                require((pack.kind == QuranPack.Kind.Audio) == (pack.reciterId != null && pack.surahs != null)) {
                    "Quran pack ${pack.name} does not say which recitation it holds"
                }
            }
            require(packs.map { it.name }.toSet().size == packs.size) { "Duplicate Quran pack names" }
            return QuranPackLayout(cdn, packs)
        }

        private val PACK_NAME = Regex("[a-z][a-z0-9_]*")
        private val ARCHIVE_KEY = Regex("v1/packs/[a-z][a-z0-9_]*-[0-9a-f]{12}\\.zip")
    }
}

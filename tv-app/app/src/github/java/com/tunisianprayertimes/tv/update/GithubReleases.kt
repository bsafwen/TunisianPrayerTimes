package com.tunisianprayertimes.tv.update

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** A TV update published on GitHub: the APK of the GitHub build, and how to check it. */
data class ReleaseAsset(
    val versionName: String,
    val versionCode: Int,
    val url: String,
    val size: Long,
    /** Lowercase hex SHA-256 that GitHub computed at upload, when it gives one. */
    val sha256: String?,
    /** When the release was published (epoch milliseconds), when GitHub says. */
    val publishedAt: Long? = null,
)

/**
 * The TV's releases on GitHub. The repository also holds the phone app's releases (the newest of
 * which is the repository's "latest"), so TV releases are found by their tag and asset names:
 * tag "tv-v1.2", asset "TunisianPrayerTimesTV-github-1.2-42.apk" (version name, then version code).
 */
object GithubReleases {

    const val REPOSITORY = "bsafwen/TunisianPrayerTimes"
    const val TAG_PREFIX = "tv-v"
    val ASSET = Regex("""TunisianPrayerTimesTV-github-([0-9A-Za-z.\-]{1,20})-([0-9]{1,9})\.apk""")

    /**
     * Published releases, newest first, a page at a time; unauthenticated (60 requests an hour per
     * address), so checked about once a day. A check first reads the [FIRST_PAGE_SIZE] newest, which
     * nearly always hold the newest TV release, and only without one reads on, at most [MAX_PAGES]
     * pages of [PAGE_SIZE].
     */
    const val FIRST_PAGE_SIZE = 20
    const val PAGE_SIZE = 100
    const val MAX_PAGES = 3
    const val FIRST_PAGE_URL = "https://api.github.com/repos/$REPOSITORY/releases?per_page=$FIRST_PAGE_SIZE"
    fun pageUrl(page: Int) = "https://api.github.com/repos/$REPOSITORY/releases?per_page=$PAGE_SIZE&page=$page"

    /** One page of releases: the TV releases on it, and how many releases it held. */
    class Page(val tvReleases: List<ReleaseAsset>, val size: Int)

    /** The newest published TV release above [currentVersionCode], or null. Never throws. */
    fun newest(json: String, currentVersionCode: Int): ReleaseAsset? = page(json)?.tvReleases?.let { newest(it, currentVersionCode) }

    fun newest(releases: List<ReleaseAsset>, currentVersionCode: Int): ReleaseAsset? =
        releases.filter { it.versionCode > currentVersionCode }.maxByOrNull { it.versionCode }

    /** The page's published TV releases; null when [json] is not a list of releases (an error, a rate limit). Never throws. */
    fun page(json: String): Page? = runCatching {
        val releases = Json.parseToJsonElement(json) as JsonArray
        Page(tvReleases(releases), releases.size)
    }.getOrNull()

    private fun tvReleases(releases: JsonArray): List<ReleaseAsset> =
        releases.mapNotNull { element ->
            val release = element as? JsonObject ?: return@mapNotNull null
            if (release.flag("draft") || release.flag("prerelease")) return@mapNotNull null
            if (!release.text("tag_name").orEmpty().startsWith(TAG_PREFIX)) return@mapNotNull null
            (release["assets"] as? JsonArray).orEmpty().firstNotNullOfOrNull { assetElement ->
                val asset = assetElement as? JsonObject ?: return@firstNotNullOfOrNull null
                val match = ASSET.matchEntire(asset.text("name").orEmpty()) ?: return@firstNotNullOfOrNull null
                val url = asset.text("browser_download_url")?.takeIf { it.startsWith("https://github.com/$REPOSITORY/releases/download/") }
                    ?: return@firstNotNullOfOrNull null
                ReleaseAsset(
                    versionName = match.groupValues[1],
                    versionCode = match.groupValues[2].toInt(),
                    url = url,
                    size = (asset["size"] as? JsonPrimitive)?.longOrNull ?: return@firstNotNullOfOrNull null,
                    sha256 = asset.text("digest")?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase(),
                    publishedAt = release.text("published_at")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
                )
            }
        }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true
}

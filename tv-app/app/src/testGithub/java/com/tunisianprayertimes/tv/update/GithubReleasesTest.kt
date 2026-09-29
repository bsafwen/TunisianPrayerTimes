package com.tunisianprayertimes.tv.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GithubReleasesTest {

    private val base = "https://github.com/bsafwen/TunisianPrayerTimes/releases/download"

    private fun release(tag: String, asset: String, draft: Boolean = false, prerelease: Boolean = false, digest: String? = null, url: String? = null) = """
        {"tag_name":"$tag","draft":$draft,"prerelease":$prerelease,
         "assets":[{"name":"$asset","size":1234,"browser_download_url":"${url ?: "$base/$tag/$asset"}",
                    "digest":${digest?.let { "\"$it\"" } ?: "null"}}]}
    """.trimIndent()

    @Test
    fun theNewestTvReleaseAboveTheInstalledOneIsChosen() {
        val json = "[" + listOf(
            // The phone app's releases share the repository (and are its "latest").
            release("v2.144", "TunisianPrayerTimes-v2.144.apk"),
            release("tv-v1.1", "TunisianPrayerTimesTV-github-1.1-11.apk", digest = "sha256:ABCDEF"),
            release("tv-v1.2", "TunisianPrayerTimesTV-github-1.2-12.apk", draft = true),
            release("tv-v1.3", "TunisianPrayerTimesTV-github-1.3-13.apk", prerelease = true),
            release("tv-v1.0", "TunisianPrayerTimesTV-github-1.0-10.apk"),
        ).joinToString(",") + "]"
        val found = GithubReleases.newest(json, currentVersionCode = 10)!!
        assertEquals("1.1", found.versionName)
        assertEquals(11, found.versionCode)
        assertEquals("abcdef", found.sha256)
        assertEquals(1234L, found.size)
        assertNull("nothing newer", GithubReleases.newest(json, currentVersionCode = 11))
    }

    @Test
    fun assetsFromElsewhereOrWithOtherNamesAreIgnored() {
        assertNull(GithubReleases.newest("[" + release("tv-v2.0", "TunisianPrayerTimesTV-play-2.0-20.apk") + "]", 1))
        assertNull(GithubReleases.newest("[" + release("tv-v2.0", "TunisianPrayerTimesTV-github-2.0-20.apk", url = "https://evil.example/x.apk") + "]", 1))
        assertNull(GithubReleases.newest("not json", 1))
        assertNull(GithubReleases.newest("""{"message":"API rate limit exceeded"}""", 1))
    }

    @Test
    fun aPageTellsHowManyReleasesItHeldSoTheCheckKnowsWhetherToReadOnFrom() {
        val phoneOnly = GithubReleases.page("[" + (1..100).joinToString(",") { release("v2.$it", "TunisianPrayerTimes-v2.$it.apk") } + "]")!!
        assertEquals(100, phoneOnly.size)
        assertEquals(emptyList<ReleaseAsset>(), phoneOnly.tvReleases)
        val mixed = GithubReleases.page("[" + release("tv-v1.4", "TunisianPrayerTimesTV-github-1.4-14.apk") + "]")!!
        assertEquals(14, GithubReleases.newest(phoneOnly.tvReleases + mixed.tvReleases, 10)!!.versionCode)
        assertNull(GithubReleases.page("""{"message":"API rate limit exceeded"}"""))
        assertEquals("https://api.github.com/repos/bsafwen/TunisianPrayerTimes/releases?per_page=100&page=2", GithubReleases.pageUrl(2))
    }
}

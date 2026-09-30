package com.tunisianprayertimes.tv.update

/**
 * The self-updater's decisions, apart from Android and the network so they can be tested. Times
 * are wall-clock milliseconds; a clock that went backwards makes whatever waits due at once.
 */
object UpdatePolicy {

    /** A successful release check is repeated after this, plus the box's own jitter. */
    const val CHECK_EVERY_MILLIS = 20 * 60 * 60 * 1000L
    const val MAX_JITTER_MILLIS = 4 * 60 * 60 * 1000L

    /** A failed check (no network, GitHub's limit) is retried after 1 hour, then 2, 4… up to [CHECK_EVERY_MILLIS]. */
    const val CHECK_RETRY_FIRST_MILLIS = 60 * 60 * 1000L

    /** While the app keeps crashing, GitHub is asked every hour: a fixed release must reach the box soon. */
    const val RESCUE_CHECK_EVERY_MILLIS = 60 * 60 * 1000L

    /** After a failed download, the next waits 1 hour, then 2, 4… up to a day (metered hotspots). */
    const val RETRY_FIRST_MILLIS = 60 * 60 * 1000L
    const val RETRY_MAX_MILLIS = 24 * 60 * 60 * 1000L

    /**
     * A release is installed at night only once it has been published this long, so a bad release
     * that is withdrawn quickly never reaches most walls. The admin's install and the rescue of a
     * crashing app do not wait.
     */
    const val SETTLE_MILLIS = 2 * 24 * 60 * 60 * 1000L

    /** After a failed night install, the next waits 20 hours (the next night), then 40, 80; then the admin must install it. */
    const val INSTALL_RETRY_FIRST_MILLIS = 20 * 60 * 60 * 1000L
    const val MAX_INSTALL_FAILS = 4

    fun checkDue(now: Long, lastSuccess: Long, lastAttempt: Long, fails: Int, jitter: Long, rescue: Boolean): Boolean {
        if (now < lastSuccess || now < lastAttempt) return true
        if (rescue) return now - lastAttempt >= RESCUE_CHECK_EVERY_MILLIS
        if (fails > 0) {
            val wait = minOf(CHECK_RETRY_FIRST_MILLIS shl (fails - 1).coerceAtMost(10), CHECK_EVERY_MILLIS)
            if (now - lastAttempt < wait) return false
        }
        return now - lastSuccess >= CHECK_EVERY_MILLIS + jitter
    }

    fun downloadDue(fails: Int, failedAt: Long, now: Long): Boolean {
        if (fails == 0) return true
        val wait = minOf(RETRY_FIRST_MILLIS shl (fails - 1).coerceAtMost(10), RETRY_MAX_MILLIS)
        return now < failedAt || now - failedAt >= wait
    }

    /** What a release list means for the update found before. */
    enum class Listing { REPLACE, WITHDRAW, KEEP }

    /**
     * [found] is the newest listed release above the installed version (rejected ones left out),
     * [anyListed] whether the pages read held any TV release at all.
     */
    fun onReleasesListed(found: ReleaseAsset?, anyListed: Boolean): Listing = when {
        found != null -> Listing.REPLACE
        // TV releases were listed and none is newer: an update seen before was withdrawn.
        anyListed -> Listing.WITHDRAW
        // No TV release in the pages read: what was found before stays.
        else -> Listing.KEEP
    }

    /** The releases worth considering: not the one this box already downloaded and found wrong. */
    fun notRejected(releases: List<ReleaseAsset>, rejected: String?): List<ReleaseAsset> =
        releases.filterNot { rejected != null && rejectionKey(it) == rejected }

    /** A release is known by its version code and its SHA-256, so a corrected re-upload is tried again. */
    fun rejectionKey(release: ReleaseAsset): String = "${release.versionCode}:${release.sha256.orEmpty()}"

    /**
     * Whether Android installs without asking: the device owner always may; otherwise from Android 12
     * (sdk 31), unless the box asked for a confirmation for this installed version.
     */
    fun installsSilently(sdk: Int, deviceOwner: Boolean, askedAtVersion: Int, currentVersionCode: Int): Boolean =
        deviceOwner || (sdk >= 31 && askedAtVersion != currentVersionCode)

    /** Whether the night install may be tried now, after [fails] failed installs of this update. */
    fun installDue(fails: Int, failedAt: Long, now: Long): Boolean {
        if (fails == 0) return true
        if (fails >= MAX_INSTALL_FAILS) return false
        return now < failedAt || now - failedAt >= INSTALL_RETRY_FIRST_MILLIS shl (fails - 1)
    }

    /**
     * Whether a release published at [publishedAt] (null when GitHub did not say) has waited long
     * enough. A clock far behind the release date is wrong, and must not hold the update forever.
     */
    fun settled(publishedAt: Long?, now: Long): Boolean =
        publishedAt == null || now - publishedAt >= SETTLE_MILLIS || publishedAt - now >= SETTLE_MILLIS
}

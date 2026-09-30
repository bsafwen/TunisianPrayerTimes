package com.tunisianprayertimes.tv.update

import com.tunisianprayertimes.tv.update.UpdatePolicy.Listing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {

    private val hour = 60 * 60 * 1000L
    private val day = 24 * hour
    private val now = 1_000 * day

    private fun release(code: Int, sha: String? = "aa") = ReleaseAsset("1.$code", code, "https://x/$code.apk", 10L, sha)

    @Test
    fun aSuccessfulCheckWaitsADayPlusTheBoxsJitter() {
        val jitter = 2 * hour
        assertFalse(UpdatePolicy.checkDue(now, lastSuccess = now - 21 * hour, lastAttempt = now - 21 * hour, fails = 0, jitter = jitter, rescue = false))
        assertTrue(UpdatePolicy.checkDue(now, lastSuccess = now - 22 * hour, lastAttempt = now - 22 * hour, fails = 0, jitter = jitter, rescue = false))
        assertTrue("never checked", UpdatePolicy.checkDue(now, 0L, 0L, 0, jitter, rescue = false))
    }

    @Test
    fun aFailedCheckIsRetriedWithinHoursNotTomorrow() {
        val lastSuccess = now - 30 * hour
        assertFalse(UpdatePolicy.checkDue(now, lastSuccess, lastAttempt = now - 30 * 60_000L, fails = 1, jitter = 0L, rescue = false))
        assertTrue(UpdatePolicy.checkDue(now, lastSuccess, lastAttempt = now - hour, fails = 1, jitter = 0L, rescue = false))
        assertFalse(UpdatePolicy.checkDue(now, lastSuccess, lastAttempt = now - 3 * hour, fails = 3, jitter = 0L, rescue = false))
        assertTrue(UpdatePolicy.checkDue(now, lastSuccess, lastAttempt = now - 4 * hour, fails = 3, jitter = 0L, rescue = false))
        // Many failures in a row wait no longer than a successful check.
        assertTrue(UpdatePolicy.checkDue(now, lastSuccess, lastAttempt = now - 20 * hour, fails = 30, jitter = 0L, rescue = false))
    }

    @Test
    fun aCrashingAppAsksEveryHour() {
        assertFalse(UpdatePolicy.checkDue(now, now - 10 * 60_000L, now - 10 * 60_000L, 0, 0L, rescue = true))
        assertTrue(UpdatePolicy.checkDue(now, now - hour, now - hour, 0, 0L, rescue = true))
    }

    @Test
    fun aClockThatWentBackwardsMakesEverythingDue() {
        assertTrue(UpdatePolicy.checkDue(now, lastSuccess = now + day, lastAttempt = now + day, fails = 0, jitter = 0L, rescue = false))
        assertTrue(UpdatePolicy.downloadDue(fails = 5, failedAt = now + hour, now = now))
        assertTrue(UpdatePolicy.installDue(fails = 1, failedAt = now + hour, now = now))
    }

    @Test
    fun downloadsBackOffUpToADay() {
        assertTrue(UpdatePolicy.downloadDue(0, 0L, now))
        assertFalse(UpdatePolicy.downloadDue(1, now - 59 * 60_000L, now))
        assertTrue(UpdatePolicy.downloadDue(1, now - hour, now))
        assertFalse(UpdatePolicy.downloadDue(3, now - 3 * hour, now))
        assertTrue(UpdatePolicy.downloadDue(3, now - 4 * hour, now))
        assertTrue(UpdatePolicy.downloadDue(40, now - day, now))
    }

    @Test
    fun aFailedNightInstallWaitsForTheNextNightsThenForTheAdmin() {
        assertTrue(UpdatePolicy.installDue(0, 0L, now))
        assertFalse("not twice a night", UpdatePolicy.installDue(1, now - 10 * hour, now))
        assertTrue(UpdatePolicy.installDue(1, now - 20 * hour, now))
        assertFalse(UpdatePolicy.installDue(2, now - 30 * hour, now))
        assertTrue(UpdatePolicy.installDue(2, now - 40 * hour, now))
        assertFalse(UpdatePolicy.installDue(UpdatePolicy.MAX_INSTALL_FAILS, now - 30 * day, now))
    }

    @Test
    fun aReleaseListReplacesWithdrawsOrKeepsTheUpdateFound() {
        assertEquals(Listing.REPLACE, UpdatePolicy.onReleasesListed(release(12), anyListed = true))
        assertEquals(Listing.WITHDRAW, UpdatePolicy.onReleasesListed(null, anyListed = true))
        assertEquals(Listing.KEEP, UpdatePolicy.onReleasesListed(null, anyListed = false))
    }

    @Test
    fun aRejectedReleaseIsSkippedSoTheNextOneCanWin() {
        val bogus = release(999_999_999)
        val real = release(46)
        val rejected = UpdatePolicy.rejectionKey(bogus)
        val left = UpdatePolicy.notRejected(listOf(bogus, real), rejected)
        assertEquals(46, GithubReleases.newest(left, 45)!!.versionCode)
        // Re-uploaded with another checksum, it is tried again.
        assertEquals(2, UpdatePolicy.notRejected(listOf(release(999_999_999, sha = "bb"), real), rejected).size)
        assertEquals(2, UpdatePolicy.notRejected(listOf(bogus, real), null).size)
    }

    @Test
    fun theDeviceOwnerInstallsSilentlyOnEveryAndroid() {
        assertTrue(UpdatePolicy.installsSilently(sdk = 28, deviceOwner = true, askedAtVersion = 44, currentVersionCode = 44))
        assertFalse(UpdatePolicy.installsSilently(sdk = 30, deviceOwner = false, askedAtVersion = -1, currentVersionCode = 44))
        assertTrue(UpdatePolicy.installsSilently(sdk = 31, deviceOwner = false, askedAtVersion = -1, currentVersionCode = 44))
        assertFalse("asked for this version", UpdatePolicy.installsSilently(sdk = 34, deviceOwner = false, askedAtVersion = 44, currentVersionCode = 44))
        assertTrue("asked for an older one", UpdatePolicy.installsSilently(sdk = 34, deviceOwner = false, askedAtVersion = 43, currentVersionCode = 44))
    }

    @Test
    fun aNewReleaseSettlesForTwoDaysBeforeANightInstall() {
        assertFalse(UpdatePolicy.settled(now - day, now))
        assertTrue(UpdatePolicy.settled(now - 2 * day, now))
        assertTrue("unknown date", UpdatePolicy.settled(null, now))
        assertTrue("a clock far behind", UpdatePolicy.settled(now + 30 * day, now))
        assertFalse("a clock a little behind", UpdatePolicy.settled(now + hour, now))
    }
}

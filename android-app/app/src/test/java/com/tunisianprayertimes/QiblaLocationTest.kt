package com.tunisianprayertimes

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

@OptIn(ExperimentalCoroutinesApi::class) // virtual-time currentTime
class QiblaLocationTest {

    // runTest skips delays in virtual time; this only stops a regression from hanging the build.
    @get:Rule
    val timeout: Timeout = Timeout.seconds(10)

    @Test
    fun tunisiaCheck_prefersTheMobileNetworkCountry() {
        assertTrue(isLikelyInTunisia("tn", "Europe/Paris"))
        assertTrue(isLikelyInTunisia("TN", null))
        assertFalse(isLikelyInTunisia("fr", "Africa/Tunis"))
    }

    @Test
    fun tunisiaCheck_fallsBackToTheTimeZoneWithoutAMobileNetwork() {
        assertTrue(isLikelyInTunisia("", "Africa/Tunis"))
        assertTrue(isLikelyInTunisia(null, "Africa/Tunis"))
        assertFalse(isLikelyInTunisia(null, "Africa/Algiers"))
        assertFalse(isLikelyInTunisia(null, null))
    }

    @Test
    fun awaitFirstMatching_returnsTheFirstAnswerAndCancelsTheRest() = runTest {
        val gps = CompletableDeferred<String?>()
        val network = CompletableDeferred<String?>()
        launch {
            delay(20)
            network.complete("network")
        }

        val result = awaitFirstMatching(listOf(gps, answered(null), network)) { true }

        assertEquals("network", result)
        assertTrue(gps.isCancelled)
    }

    @Test
    fun awaitFirstMatching_skipsRejectedAnswers() = runTest {
        val stale = answered("stale")
        val fresh = CompletableDeferred<String?>()
        launch {
            delay(20)
            fresh.complete("fresh")
        }

        assertEquals("fresh", awaitFirstMatching(listOf(stale, fresh)) { answer -> answer != "stale" })
    }

    @Test
    fun awaitFirstMatching_returnsNullWhenNoSourceAnswers() = runTest {
        assertNull(awaitFirstMatching(listOf(answered(null), answered(null))) { true })
        assertEquals(0L, currentTime)
    }

    @Test
    fun awaitFirstMatching_givesUpOnSourcesThatNeverAnswer() = runTest {
        val silent = CompletableDeferred<String?>()

        assertNull(awaitFirstMatching(listOf(silent, answered(null))) { true })
        assertEquals(FIRST_LOCATION_TIMEOUT_MS, currentTime)
        assertTrue(silent.isCancelled)
    }

    // CompletableDeferred<String?>(null) would pick the CompletableDeferred(parent: Job?) overload
    // and never complete.
    private fun answered(value: String?) = CompletableDeferred<String?>().apply { complete(value) }
}

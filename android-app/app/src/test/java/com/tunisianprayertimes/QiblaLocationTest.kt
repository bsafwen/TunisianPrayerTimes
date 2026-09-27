package com.tunisianprayertimes

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QiblaLocationTest {

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
    fun awaitFirstMatching_returnsTheFirstAnswerAndCancelsTheRest() = runBlocking<Unit> {
        val gps = CompletableDeferred<String?>()
        val network = CompletableDeferred<String?>()
        launch {
            delay(20)
            network.complete("network")
        }

        val result = awaitFirstMatching(listOf(gps, CompletableDeferred<String?>(null), network)) { true }

        assertEquals("network", result)
        assertTrue(gps.isCancelled)
    }

    @Test
    fun awaitFirstMatching_skipsRejectedAnswers() = runBlocking<Unit> {
        val stale = CompletableDeferred<String?>("stale")
        val fresh = CompletableDeferred<String?>()
        launch {
            delay(20)
            fresh.complete("fresh")
        }

        assertEquals("fresh", awaitFirstMatching(listOf(stale, fresh)) { answer -> answer != "stale" })
    }

    @Test
    fun awaitFirstMatching_returnsNullWhenNoSourceAnswers() = runBlocking<Unit> {
        val requests = listOf(CompletableDeferred<String?>(null), CompletableDeferred<String?>(null))
        assertNull(awaitFirstMatching(requests) { true })
    }
}

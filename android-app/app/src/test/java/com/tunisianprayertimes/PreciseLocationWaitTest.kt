package com.tunisianprayertimes

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** The locate button's wait (awaitPreciseEnough): a fix as "name:accuracy[:age in seconds]", "stale:" ones unusable. */
@OptIn(ExperimentalCoroutinesApi::class) // virtual-time currentTime
class PreciseLocationWaitTest {

    // runTest skips delays in virtual time; this only stops a regression from hanging the build.
    @get:Rule
    val timeout: Timeout = Timeout.seconds(10)

    private fun accuracy(fix: String) = fix.split(':')[1].toFloat()
    private fun ageMs(fix: String) = (fix.split(':').getOrNull(2)?.toLong() ?: 0L) * 1_000
    private fun best(fixes: List<String>) = fixes.filterNot { it.startsWith("stale") }.minByOrNull(::accuracy)

    private suspend fun TestScope.wait(requests: List<Pair<LiveSource, CompletableDeferred<String?>>>, known: String? = null) =
        awaitPreciseEnough(requests, known, ::best, ::accuracy, ::ageMs)

    private fun TestScope.answerAt(request: CompletableDeferred<String?>, atMs: Long, fix: String?) {
        launch { delay(atMs); request.complete(fix) }
    }

    @Test
    fun aFineFixEndsTheWaitAtOnceAndCancelsGps() = runTest {
        val fused = CompletableDeferred<String?>()
        val gps = CompletableDeferred<String?>()
        answerAt(fused, 1_000, "fused:14")

        assertEquals("fused:14", wait(listOf(LiveSource.OTHER to fused, LiveSource.GPS to gps)))
        assertEquals(1_000L, currentTime)
        assertTrue(gps.isCancelled)
    }

    @Test
    fun aCoarseAnswerGivesGpsOnlyAShortGraceOnceFusedAndNetworkAnswered() = runTest {
        val fused = CompletableDeferred<String?>()
        val network = CompletableDeferred<String?>()
        val gps = CompletableDeferred<String?>()
        val passive = CompletableDeferred<String?>()
        answerAt(fused, 500, "fused:80")
        answerAt(network, 1_000, "network:90")

        val result = wait(listOf(LiveSource.OTHER to fused, LiveSource.OTHER to network, LiveSource.GPS to gps, LiveSource.PASSIVE to passive))

        assertEquals("fused:80", result)
        assertEquals(3_000L, currentTime)
        assertTrue(gps.isCancelled && passive.isCancelled)
    }

    @Test
    fun gpsArrivingInItsGraceWins() = runTest {
        val network = CompletableDeferred<String?>()
        val gps = CompletableDeferred<String?>()
        answerAt(network, 1_000, "network:90")
        answerAt(gps, 2_500, "gps:6")

        assertEquals("gps:6", wait(listOf(LiveSource.OTHER to network, LiveSource.GPS to gps)))
        assertEquals(2_500L, currentTime)
    }

    @Test
    fun aVeryCoarseAnswerStillWaitsTheFullTimeForGps() = runTest {
        val network = CompletableDeferred<String?>()
        val gps = CompletableDeferred<String?>()
        answerAt(network, 1_000, "network:900")
        answerAt(gps, 6_000, "gps:6")

        assertEquals("gps:6", wait(listOf(LiveSource.OTHER to network, LiveSource.GPS to gps)))
        assertEquals(6_000L, currentTime)
    }

    @Test
    fun theLiveFixThatEndsTheWaitBeatsAMoreAccurateKnownOne() = runTest {
        // The known fix may be minutes old, from a place left since: the fresh answer wins.
        val fused = CompletableDeferred<String?>()
        val gps = CompletableDeferred<String?>()
        answerAt(fused, 1_000, "fused:25")

        assertEquals("fused:25", wait(listOf(LiveSource.OTHER to fused, LiveSource.GPS to gps), known = "known:4"))
        assertTrue(gps.isCancelled)
    }

    @Test
    fun aKeptFixFromMinutesAgoDoesNotEndTheWait() = runTest {
        // Fused hands back its last fix, taken 2 minutes ago maybe elsewhere: the fresh GPS fix that follows wins.
        val fused = CompletableDeferred<String?>()
        val gps = CompletableDeferred<String?>()
        answerAt(fused, 100, "kept:4:120")
        answerAt(gps, 3_000, "gps:8")

        assertEquals("gps:8", wait(listOf(LiveSource.OTHER to fused, LiveSource.GPS to gps)))
        assertEquals(3_000L, currentTime)
    }

    @Test
    fun withoutAnyUsableLiveFixTheKnownFixComesBackAtTheTimeout() = runTest {
        val gps = CompletableDeferred<String?>()
        val network = CompletableDeferred<String?>()
        answerAt(network, 1_000, "stale:5")

        assertEquals("known:30", wait(listOf(LiveSource.OTHER to network, LiveSource.GPS to gps), known = "known:30"))
        assertEquals(FIRST_LOCATION_TIMEOUT_MS, currentTime)
        assertTrue(gps.isCancelled)
    }

    @Test
    fun noSourceAnsweringReturnsNull() = runTest {
        assertNull(wait(listOf(LiveSource.OTHER to answered(null), LiveSource.PASSIVE to answered(null))))
        assertEquals(0L, currentTime)
    }

    private fun answered(fix: String?) = CompletableDeferred<String?>().apply { complete(fix) }
}

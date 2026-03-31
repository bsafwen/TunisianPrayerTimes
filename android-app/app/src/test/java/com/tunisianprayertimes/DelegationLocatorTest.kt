package com.tunisianprayertimes

import android.location.LocationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DelegationLocatorTest {

    @Test
    fun `fine permission prefers gps then network then passive`() {
        val providers = fallbackProviders(LocationPermissionState(hasFine = true, hasCoarse = true))

        assertEquals(
            listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            ),
            providers
        )
    }

    @Test
    fun `coarse permission falls back to network then passive`() {
        val providers = fallbackProviders(LocationPermissionState(hasFine = false, hasCoarse = true))

        assertEquals(
            listOf(
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            ),
            providers
        )
    }

    @Test
    fun `no permission has no providers`() {
        assertEquals(
            emptyList<String>(),
            fallbackProviders(LocationPermissionState(hasFine = false, hasCoarse = false))
        )
    }

    @Test
    fun `more accurate location beats newer less accurate one`() {
        val olderAccurate = Candidate(time = 1_000L, accuracy = 5f)
        val newerLessAccurate = Candidate(time = 2_000L, accuracy = 50f)

        val best = chooseBestCandidate(
            candidates = listOf(olderAccurate, newerLessAccurate),
            timeSelector = { it.time },
            accuracySelector = { it.accuracy }
        )

        assertEquals(olderAccurate, best)
    }

    @Test
    fun `same accuracy prefers newer`() {
        val older = Candidate(time = 1_000L, accuracy = 10f)
        val newer = Candidate(time = 2_000L, accuracy = 10f)

        val best = chooseBestCandidate(
            candidates = listOf(older, newer),
            timeSelector = { it.time },
            accuracySelector = { it.accuracy }
        )

        assertEquals(newer, best)
    }

    @Test
    fun `same timestamp prefers better accuracy`() {
        val lessAccurate = Candidate(time = 2_000L, accuracy = 40f)
        val moreAccurate = Candidate(time = 2_000L, accuracy = 8f)

        val best = chooseBestCandidate(
            candidates = listOf(lessAccurate, moreAccurate),
            timeSelector = { it.time },
            accuracySelector = { it.accuracy }
        )

        assertEquals(moreAccurate, best)
    }

    @Test
    fun `empty candidate list returns null`() {
        assertNull(
            chooseBestCandidate<Candidate>(
                candidates = emptyList(),
                timeSelector = { it.time },
                accuracySelector = { it.accuracy }
            )
        )
    }

    private data class Candidate(val time: Long, val accuracy: Float)
}
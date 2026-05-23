package com.tunisianprayertimes

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class DelegationLocatorTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("prayer_silence_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        grantLocationPermission()
    }

    @After
    fun tearDown() {
        DelegationLocator.resetLocationProviderForTests()
    }

    @Test
    fun `silent update changes delegation for recent accurate location`() = runBlocking {
        PrefsManager.setDelegationId(context, 386)
        DelegationLocator.locationProvider = FakeLocationProvider(mouroujLocation())

        val changed = DelegationLocator.updateDelegationFromLastLocation(context)

        assertTrue(changed)
        assertEquals(447, PrefsManager.getDelegationId(context))
    }

    @Test
    fun `silent update ignores stale location`() = runBlocking {
        PrefsManager.setDelegationId(context, 386)
        DelegationLocator.locationProvider = FakeLocationProvider(
            mouroujLocation(ageMs = 10 * 60 * 1_000L)
        )

        val changed = DelegationLocator.updateDelegationFromLastLocation(context)

        assertFalse(changed)
        assertEquals(386, PrefsManager.getDelegationId(context))
    }

    @Test
    fun `silent update ignores low accuracy location`() = runBlocking {
        PrefsManager.setDelegationId(context, 386)
        DelegationLocator.locationProvider = FakeLocationProvider(
            mouroujLocation(accuracyMeters = 50_000f)
        )

        val changed = DelegationLocator.updateDelegationFromLastLocation(context)

        assertFalse(changed)
        assertEquals(386, PrefsManager.getDelegationId(context))
    }

    @Test
    fun `silent update ignores location outside Tunisia`() = runBlocking {
        PrefsManager.setDelegationId(context, 386)
        DelegationLocator.locationProvider = FakeLocationProvider(
            testLocation(lat = 48.8566, lng = 2.3522)
        )

        val changed = DelegationLocator.updateDelegationFromLastLocation(context)

        assertFalse(changed)
        assertEquals(386, PrefsManager.getDelegationId(context))
    }

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

    private class FakeLocationProvider(
        private val location: Location?
    ) : DelegationLocationProvider {
        override suspend fun findCurrentLocation(
            context: Context,
            permissionState: LocationPermissionState
        ): Location? = location

        override suspend fun findRecentLocation(
            context: Context,
            permissionState: LocationPermissionState
        ): Location? = location

        override suspend fun findFreshLocation(
            context: Context,
            permissionState: LocationPermissionState
        ): Location? = location
    }

    private fun grantLocationPermission() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication() as Application)
            .grantPermissions(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
    }

    private fun mouroujLocation(
        ageMs: Long = 0L,
        accuracyMeters: Float = 25f
    ): Location {
        val lat = 36 + 43.0 / 60.0 + 0.1 / 3600.0
        val lng = 10 + 12.0 / 60.0 + 9.1 / 3600.0
        return testLocation(lat = lat, lng = lng, ageMs = ageMs, accuracyMeters = accuracyMeters)
    }

    private fun testLocation(
        lat: Double,
        lng: Double,
        ageMs: Long = 0L,
        accuracyMeters: Float = 25f
    ): Location {
        return Location("test").apply {
            latitude = lat
            longitude = lng
            time = System.currentTimeMillis() - ageMs
            accuracy = accuracyMeters
        }
    }
}
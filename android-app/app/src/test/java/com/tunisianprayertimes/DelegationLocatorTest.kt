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
    fun `GPS button rejects stale future and malformed fixes from provider`() = runBlocking {
        val invalidLocations = listOf(
            mouroujLocation(ageMs = 10 * 60 * 1_000L),
            mouroujLocation(ageMs = -60_000L),
            mouroujLocation().apply { time = 0L },
            mouroujLocation().apply { latitude = Double.NaN },
            mouroujLocation().apply { longitude = Double.POSITIVE_INFINITY },
            mouroujLocation().apply { latitude = 91.0 },
            mouroujLocation(accuracyMeters = Float.NaN),
            mouroujLocation(accuracyMeters = Float.POSITIVE_INFINITY),
            mouroujLocation(accuracyMeters = -1f),
            mouroujLocation(accuracyMeters = 0f)
        )
        invalidLocations.forEach { invalid ->
            DelegationLocator.locationProvider = FakeLocationProvider(invalid)
            assertEquals(DelegationLocationResult.LocationUnavailable, DelegationLocator.detectNearestDelegation(context))
            assertNull(DelegationLocator.detectCurrentLocation(context))
        }
    }

    @Test
    fun `silent updates keep saved selection when accuracy is unknown or invalid`() = runBlocking {
        PrefsManager.setDelegationId(context, 386)
        val invalidLocations = listOf(
            mouroujLocation().apply { removeAccuracy() },
            mouroujLocation(accuracyMeters = Float.NaN),
            mouroujLocation(accuracyMeters = Float.POSITIVE_INFINITY),
            mouroujLocation(accuracyMeters = 0f),
            mouroujLocation(ageMs = -60_000L),
            mouroujLocation().apply { longitude = Double.NaN }
        )
        invalidLocations.forEach { invalid ->
            DelegationLocator.locationProvider = FakeLocationProvider(invalid)
            assertFalse(DelegationLocator.updateDelegationFromLastLocation(context))
            assertFalse(DelegationLocator.updateDelegationFromCurrentLocation(context))
            assertEquals(386, PrefsManager.getDelegationId(context))
            assertFalse(PrefsManager.getLocationSelection(context).fromGps)
        }
    }

    @Test
    fun `best location ignores invalid fixes before comparing accuracy`() {
        val now = System.currentTimeMillis()
        val usable = mouroujLocation(accuracyMeters = 40f).apply { time = now - 1_000L }
        val rejected = listOf(
            mouroujLocation(accuracyMeters = 1f).apply { time = now - 300_001L },
            mouroujLocation(accuracyMeters = 1f).apply { time = now + 1L },
            mouroujLocation(accuracyMeters = 1f).apply { time = 0L },
            mouroujLocation(accuracyMeters = 1f).apply { time = now; latitude = Double.NaN },
            mouroujLocation(accuracyMeters = Float.NaN).apply { time = now },
            mouroujLocation(accuracyMeters = 0f).apply { time = now }
        )
        assertEquals(usable, chooseBestLocation(rejected + usable, now))
        assertNull(chooseBestLocation(rejected, now))
    }

    @Test
    fun `unknown accuracy ranks after a usable measured accuracy`() {
        val now = System.currentTimeMillis()
        val unknown = mouroujLocation().apply { time = now; removeAccuracy() }
        val measured = mouroujLocation(accuracyMeters = 1_000f).apply { time = now - 1_000L }
        assertEquals(measured, chooseBestLocation(listOf(unknown, measured), now))
        assertEquals(unknown, chooseBestLocation(listOf(unknown), now))
    }

    @Test
    fun `known fine GPS beats coarse network fix within usable age window`() {
        val now = System.currentTimeMillis()
        val fine = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 36.8640
            longitude = 10.1647
            time = now - 10_000L
            accuracy = 8f
        }
        val coarse = Location(LocationManager.NETWORK_PROVIDER).apply {
            latitude = 36.88
            longitude = 10.18
            time = now
            accuracy = 2_000f
        }
        assertEquals(fine, chooseBestLocation(listOf(coarse, fine), now))
        val result = DelegationLocator.resolveGpsLocation(context, fine.latitude, fine.longitude)
            as DelegationLocationResult.Success
        assertEquals("النصر 2", result.locality?.name)
    }

    @Test
    fun `GPS returns neighborhood label and independently nearest timetable`() {
        val result = DelegationLocator.resolveGpsLocation(context, 36.8428, 10.1465)
            as DelegationLocationResult.Success
        assertEquals("المنزه 9 أ", result.locality?.name)
        assertEquals(GouvernoratRepository.findNearestDelegation(context, 36.8428, 10.1465)?.id, result.delegation.id)
        assertEquals(result.delegation.id, result.locality?.delegationId)
    }

    @Test
    fun `GPS button obtains a named polygon from device coordinates`() = runBlocking {
        DelegationLocator.locationProvider = FakeLocationProvider(testLocation(36.8640, 10.1647))
        val result = DelegationLocator.detectNearestDelegation(context) as DelegationLocationResult.Success
        assertEquals("النصر 2", result.locality?.name)
        PrefsManager.setGpsLocation(context, result)
        assertEquals("النصر 2", PrefsManager.getLocationSelection(context).name)
        assertTrue(PrefsManager.getLocationSelection(context).fromGps)
    }

    @Test
    fun `moving between neighborhoods updates label even when timetable does not change`() = runBlocking {
        DelegationLocator.locationProvider = FakeLocationProvider(testLocation(36.810562, 10.146875))
        DelegationLocator.updateDelegationFromLastLocation(context)
        assertEquals("بوشوشة", PrefsManager.getLocationSelection(context).name)
        assertEquals(394, PrefsManager.getDelegationId(context))
        DelegationLocator.locationProvider = FakeLocationProvider(testLocation(36.805274, 10.126553))
        assertFalse(DelegationLocator.updateDelegationFromLastLocation(context))
        assertEquals("خزندار", PrefsManager.getLocationSelection(context).name)
        assertEquals(394, PrefsManager.getDelegationId(context))
    }

    @Test
    fun `GPS without neighborhood geometry clears stale neighborhood label`() {
        PrefsManager.setLocality(context, LocalityRepository.loadAll(context).first())
        val source = GouvernoratRepository.loadAllDelegations(context).first()
        PrefsManager.setGpsLocation(context, DelegationLocationResult.Success(source))
        assertNull(PrefsManager.getLocationSelection(context).name)
        assertNull(PrefsManager.getLocalityId(context))
        assertTrue(PrefsManager.getLocationSelection(context).fromGps)
        assertEquals(source.id, PrefsManager.getDelegationId(context))
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

package com.tunisianprayertimes

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.location.Location
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30, 33, 34])
class SilenceReceiverTest {

    private lateinit var context: Context
    private lateinit var receiver: SilenceReceiver
    private lateinit var shadowAlarmManager: ShadowAlarmManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        receiver = SilenceReceiver()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        shadowAlarmManager = Shadows.shadowOf(alarmManager)
        context.getSharedPreferences("prayer_silence_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        DelegationLocator.resetLocationProviderForTests()
    }

    @After
    fun tearDown() {
        DelegationLocator.resetLocationProviderForTests()
    }

    @Test
    @Config(sdk = [26])
    fun checkDelegationBeforePrayer_whenDelegationChanges_reschedulesAlarms() = runBlocking {
        grantLocationPermission()
        PrefsManager.setEnabled(context, true)
        PrefsManager.setAutoLocationUpdateEnabled(context, true)
        PrefsManager.setDelegationId(context, 386)
        DelegationLocator.locationProvider = FakeLocationProvider(mouroujLocation())

        val changed = receiver.checkDelegationBeforePrayer(context, Prayer.FAJR.name)

        assertTrue(changed)
        assertEquals(447, PrefsManager.getDelegationId(context))
        assertTrue(shadowAlarmManager.scheduledAlarms.isNotEmpty())
    }

    @Test
    fun onReceive_silenceAction_doesNotCrash() {
        val intent = Intent("com.tunisianprayertimes.ACTION_SILENCE").apply {
            putExtra("extra_prayer", "FAJR")
        }
        // Should not throw — DND permission won't be granted in test, so it just skips
        receiver.onReceive(context, intent)
    }

    @Test
    fun onReceive_unsilenceAction_doesNotCrash() {
        val intent = Intent("com.tunisianprayertimes.ACTION_UNSILENCE").apply {
            putExtra("extra_prayer", "DHUHR")
        }
        receiver.onReceive(context, intent)
    }

    @Test
    fun onReceive_rescheduleAction_doesNotCrash() {
        val intent = Intent("com.tunisianprayertimes.ACTION_RESCHEDULE")
        receiver.onReceive(context, intent)
    }

    @Test
    fun onReceive_delegationCheckAction_doesNotCrash() {
        val intent = Intent("com.tunisianprayertimes.ACTION_DELEGATION_CHECK").apply {
            putExtra("extra_prayer", "FAJR")
        }
        receiver.onReceive(context, intent)
    }

    @Test
    fun onReceive_unknownAction_doesNotCrash() {
        val intent = Intent("com.tunisianprayertimes.UNKNOWN_ACTION")
        receiver.onReceive(context, intent)
    }

    @Test
    fun onReceive_nullAction_doesNotCrash() {
        val intent = Intent()
        receiver.onReceive(context, intent)
    }

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

    private fun mouroujLocation(): Location {
        val lat = 36 + 43.0 / 60.0 + 0.1 / 3600.0
        val lng = 10 + 12.0 / 60.0 + 9.1 / 3600.0
        return Location("test").apply {
            latitude = lat
            longitude = lng
            time = System.currentTimeMillis()
            accuracy = 25f
        }
    }
}

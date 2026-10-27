package com.tunisianprayertimes

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * 6.8's background fixes on Android 10+: Android refuses them to an app granted the location for its screens only, so
 * the pre-prayer check, which only wakes the phone for that fix, is armed only with the background-location permission.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackgroundLocationGateTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var alarms: ShadowAlarmManager

    @Before
    fun setup() {
        context.getSharedPreferences("prayer_silence_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationPolicyAccessGranted(true)
        shadowOf(context as Application).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        PrefsManager.setEnabled(context, true)
    }

    private fun prePrayerChecks() = alarms.scheduledAlarms.count {
        shadowOf(it.operation).savedIntent.action == "com.tunisianprayertimes.ACTION_DELEGATION_CHECK"
    }

    @Test
    fun withoutBackgroundLocation_noPrePrayerCheckIsArmed() {
        SilenceScheduler.scheduleAll(context)

        assertTrue(alarms.scheduledAlarms.isNotEmpty())
        assertEquals(0, prePrayerChecks())
        assertTrue(DelegationLocator.needsBackgroundLocationPermission(context))
    }

    @Test
    fun withBackgroundLocation_thePrePrayerChecksAreArmed() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

        SilenceScheduler.scheduleAll(context)

        assertTrue(prePrayerChecks() > 0)
        assertFalse(DelegationLocator.needsBackgroundLocationPermission(context))
    }

    @Test
    fun withAutoLocationOff_nothingIsAskedOrArmed() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        PrefsManager.setAutoLocationUpdateEnabled(context, false)

        SilenceScheduler.scheduleAll(context)

        assertEquals(0, prePrayerChecks())
        assertFalse(DelegationLocator.needsBackgroundLocationPermission(context))
    }
}

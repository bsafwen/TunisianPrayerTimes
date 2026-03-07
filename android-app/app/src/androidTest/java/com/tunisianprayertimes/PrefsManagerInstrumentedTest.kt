package com.tunisianprayertimes

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for PrefsManager on a real device.
 * Verifies SharedPreferences work correctly across Android versions.
 */
@RunWith(AndroidJUnit4::class)
class PrefsManagerInstrumentedTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("prayer_silence_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun delegationId_persistsAndReloads() {
        PrefsManager.setDelegationId(context, 400)
        assertEquals(400, PrefsManager.getDelegationId(context))
    }

    @Test
    fun silenceMode_persistsPerPrayer() {
        PrefsManager.setSilenceMode(context, Prayer.FAJR, SilenceMode.FIXED_TIME)
        PrefsManager.setSilenceMode(context, Prayer.DHUHR, SilenceMode.DURATION)

        assertEquals(SilenceMode.FIXED_TIME, PrefsManager.getSilenceMode(context, Prayer.FAJR))
        assertEquals(SilenceMode.DURATION, PrefsManager.getSilenceMode(context, Prayer.DHUHR))
    }

    @Test
    fun fixedTime_persistsCorrectly() {
        PrefsManager.setFixedTime(context, Prayer.MAGHRIB, 19, 45)
        assertEquals(19, PrefsManager.getFixedTimeHour(context, Prayer.MAGHRIB))
        assertEquals(45, PrefsManager.getFixedTimeMinute(context, Prayer.MAGHRIB))
    }

    @Test
    fun enabledState_togglesPersistently() {
        PrefsManager.setEnabled(context, false)
        assertFalse(PrefsManager.isEnabled(context))
        PrefsManager.setEnabled(context, true)
        assertTrue(PrefsManager.isEnabled(context))
    }

    @Test
    fun afterMinutes_allPrayersIndependent() {
        PrefsManager.setAfterMinutes(context, Prayer.FAJR, 15)
        PrefsManager.setAfterMinutes(context, Prayer.DHUHR, 25)
        PrefsManager.setAfterMinutes(context, Prayer.ASR, 35)

        assertEquals(15, PrefsManager.getAfterMinutes(context, Prayer.FAJR))
        assertEquals(25, PrefsManager.getAfterMinutes(context, Prayer.DHUHR))
        assertEquals(35, PrefsManager.getAfterMinutes(context, Prayer.ASR))
    }
}

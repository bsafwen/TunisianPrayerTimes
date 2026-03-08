package com.tunisianprayertimes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30, 33, 34])
class PrefsManagerTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // Clear prefs before each test
        context.getSharedPreferences("prayer_silence_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun defaultDelegationId_isTunis() {
        assertEquals(615, PrefsManager.getDelegationId(context))
    }

    @Test
    fun setAndGetDelegationId() {
        PrefsManager.setDelegationId(context, 400)
        assertEquals(400, PrefsManager.getDelegationId(context))
    }

    @Test
    fun defaultEnabled_isTrue() {
        assertTrue(PrefsManager.isEnabled(context))
    }

    @Test
    fun setEnabled_persistsValue() {
        PrefsManager.setEnabled(context, false)
        assertFalse(PrefsManager.isEnabled(context))
        PrefsManager.setEnabled(context, true)
        assertTrue(PrefsManager.isEnabled(context))
    }

    @Test
    fun firstLaunch_isTrueInitially() {
        assertTrue(PrefsManager.isFirstLaunch(context))
    }

    @Test
    fun markFirstLaunchDone_setsFirstLaunchFalse() {
        PrefsManager.markFirstLaunchDone(context)
        assertFalse(PrefsManager.isFirstLaunch(context))
    }

    @Test
    fun defaultAfterMinutes_correctPerPrayer() {
        assertEquals(60, PrefsManager.getAfterMinutes(context, Prayer.FAJR))
        assertEquals(60, PrefsManager.getAfterMinutes(context, Prayer.DHUHR))
        assertEquals(30, PrefsManager.getAfterMinutes(context, Prayer.ASR))
        assertEquals(20, PrefsManager.getAfterMinutes(context, Prayer.MAGHRIB))
        // Isha depends on Ramadan — just check it's > 0
        assertTrue(PrefsManager.getAfterMinutes(context, Prayer.ISHA) > 0)
    }

    @Test
    fun setAndGetAfterMinutes() {
        PrefsManager.setAfterMinutes(context, Prayer.FAJR, 45)
        assertEquals(45, PrefsManager.getAfterMinutes(context, Prayer.FAJR))
    }

    @Test
    fun defaultSilenceMode_isDuration() {
        for (prayer in Prayer.values()) {
            if (prayer == Prayer.DHUHR) {
                assertEquals(SilenceMode.FIXED_TIME, PrefsManager.getSilenceMode(context, prayer))
            } else {
                assertEquals(SilenceMode.DURATION, PrefsManager.getSilenceMode(context, prayer))
            }
        }
    }

    @Test
    fun setAndGetSilenceMode() {
        PrefsManager.setSilenceMode(context, Prayer.DHUHR, SilenceMode.FIXED_TIME)
        assertEquals(SilenceMode.FIXED_TIME, PrefsManager.getSilenceMode(context, Prayer.DHUHR))
        // Other prayers remain unchanged
        assertEquals(SilenceMode.DURATION, PrefsManager.getSilenceMode(context, Prayer.FAJR))
    }

    @Test
    fun defaultFixedTime_isNegativeOne() {
        assertEquals(-1, PrefsManager.getFixedTimeHour(context, Prayer.ASR))
        assertEquals(-1, PrefsManager.getFixedTimeMinute(context, Prayer.ASR))
    }

    @Test
    fun defaultFixedTime_dhuhr_is1315() {
        assertEquals(13, PrefsManager.getFixedTimeHour(context, Prayer.DHUHR))
        assertEquals(15, PrefsManager.getFixedTimeMinute(context, Prayer.DHUHR))
    }

    @Test
    fun setAndGetFixedTime() {
        PrefsManager.setFixedTime(context, Prayer.MAGHRIB, 19, 30)
        assertEquals(19, PrefsManager.getFixedTimeHour(context, Prayer.MAGHRIB))
        assertEquals(30, PrefsManager.getFixedTimeMinute(context, Prayer.MAGHRIB))
    }

    @Test
    fun getConfig_returnsCorrectDefaults() {
        val config = PrefsManager.getConfig(context, Prayer.FAJR)
        assertEquals(SilenceMode.DURATION, config.mode)
        assertEquals(60, config.afterMinutes)
        assertEquals(-1, config.fixedHour)
        assertEquals(-1, config.fixedMinute)
    }

    @Test
    fun getConfig_returnsModifiedValues() {
        PrefsManager.setSilenceMode(context, Prayer.ASR, SilenceMode.FIXED_TIME)
        PrefsManager.setAfterMinutes(context, Prayer.ASR, 45)
        PrefsManager.setFixedTime(context, Prayer.ASR, 16, 15)

        val config = PrefsManager.getConfig(context, Prayer.ASR)
        assertEquals(SilenceMode.FIXED_TIME, config.mode)
        assertEquals(45, config.afterMinutes)
        assertEquals(16, config.fixedHour)
        assertEquals(15, config.fixedMinute)
    }

    @Test
    fun invalidSilenceMode_fallsToDuration() {
        // Write an invalid mode string directly
        context.getSharedPreferences("prayer_silence_prefs", Context.MODE_PRIVATE)
            .edit().putString("mode_FAJR", "INVALID_MODE").commit()
        assertEquals(SilenceMode.DURATION, PrefsManager.getSilenceMode(context, Prayer.FAJR))
    }
}

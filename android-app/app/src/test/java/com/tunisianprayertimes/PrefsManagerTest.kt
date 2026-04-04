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
            if (prayer == Prayer.DHUHR || prayer == Prayer.JOMOAA) {
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

    @Test
    fun invalidDelayMode_fallsToMinutes() {
        context.getSharedPreferences("prayer_silence_prefs", Context.MODE_PRIVATE)
            .edit().putString("delay_mode_FAJR", "GARBAGE_VALUE").commit()
        assertEquals(DelayMode.MINUTES, PrefsManager.getDelayMode(context, Prayer.FAJR))
    }

    @Test
    fun getConfig_includesAllDelayFields() {
        PrefsManager.setDelayMode(context, Prayer.MAGHRIB, DelayMode.FIXED_TIME)
        PrefsManager.setDelayFixedTime(context, Prayer.MAGHRIB, 18, 45)
        PrefsManager.setDelayMinutes(context, Prayer.MAGHRIB, 7)

        val config = PrefsManager.getConfig(context, Prayer.MAGHRIB)
        assertEquals(DelayMode.FIXED_TIME, config.delayMode)
        assertEquals(18, config.delayFixedHour)
        assertEquals(45, config.delayFixedMinute)
        assertEquals(7, config.delayMinutes)
    }

    @Test
    fun getConfig_allFivePrayers_returnIndependentConfigs() {
        // Set each prayer to a distinct config
        PrefsManager.setAfterMinutes(context, Prayer.FAJR, 10)
        PrefsManager.setAfterMinutes(context, Prayer.DHUHR, 20)
        PrefsManager.setAfterMinutes(context, Prayer.ASR, 30)
        PrefsManager.setAfterMinutes(context, Prayer.MAGHRIB, 40)
        PrefsManager.setAfterMinutes(context, Prayer.ISHA, 50)

        assertEquals(10, PrefsManager.getConfig(context, Prayer.FAJR).afterMinutes)
        assertEquals(20, PrefsManager.getConfig(context, Prayer.DHUHR).afterMinutes)
        assertEquals(30, PrefsManager.getConfig(context, Prayer.ASR).afterMinutes)
        assertEquals(40, PrefsManager.getConfig(context, Prayer.MAGHRIB).afterMinutes)
        // ISHA may be overridden during Ramadan, so check it's either 50 or 90
        val ishaMinutes = PrefsManager.getConfig(context, Prayer.ISHA).afterMinutes
        assertTrue("ISHA afterMinutes should be 50 or 90 (Ramadan)", ishaMinutes == 50 || ishaMinutes == 90)
    }

    @Test
    fun ishaDefaultDuringRamadan_returns90() {
        // This tests the Ramadan override: during Ramadan, default for Isha is 90
        // If we're currently in Ramadan, getAfterMinutes returns 90 regardless of saved value
        if (RamadanDetector.isRamadan()) {
            // Save 30 for Isha — Ramadan override should still return 90
            PrefsManager.setAfterMinutes(context, Prayer.ISHA, 30)
            assertEquals(
                "Ramadan: Isha should always be 90 regardless of saved value",
                90,
                PrefsManager.getAfterMinutes(context, Prayer.ISHA)
            )
        } else {
            // Outside Ramadan: saved value is returned
            PrefsManager.setAfterMinutes(context, Prayer.ISHA, 30)
            assertEquals(30, PrefsManager.getAfterMinutes(context, Prayer.ISHA))
        }
    }

    @Test
    fun ishaDefault_outsideRamadan_is30() {
        // Outside Ramadan, default Isha afterMinutes should be 30
        if (!RamadanDetector.isRamadan()) {
            assertEquals(30, PrefsManager.getAfterMinutes(context, Prayer.ISHA))
        }
        // If currently Ramadan, default is overridden to 90
        if (RamadanDetector.isRamadan()) {
            assertEquals(90, PrefsManager.getAfterMinutes(context, Prayer.ISHA))
        }
    }

    @Test
    fun settingsSurviveDelegationChange() {
        // Set delay and duration settings
        PrefsManager.setDelayMinutes(context, Prayer.FAJR, 10)
        PrefsManager.setDelayMode(context, Prayer.FAJR, DelayMode.FIXED_TIME)
        PrefsManager.setDelayFixedTime(context, Prayer.FAJR, 5, 15)
        PrefsManager.setAfterMinutes(context, Prayer.FAJR, 45)
        PrefsManager.setSilenceMode(context, Prayer.FAJR, SilenceMode.FIXED_TIME)
        PrefsManager.setFixedTime(context, Prayer.FAJR, 6, 30)

        // Change delegation
        PrefsManager.setDelegationId(context, 400)

        // All settings should survive
        assertEquals(10, PrefsManager.getDelayMinutes(context, Prayer.FAJR))
        assertEquals(DelayMode.FIXED_TIME, PrefsManager.getDelayMode(context, Prayer.FAJR))
        assertEquals(5, PrefsManager.getDelayFixedHour(context, Prayer.FAJR))
        assertEquals(15, PrefsManager.getDelayFixedMinute(context, Prayer.FAJR))
        assertEquals(45, PrefsManager.getAfterMinutes(context, Prayer.FAJR))
        assertEquals(SilenceMode.FIXED_TIME, PrefsManager.getSilenceMode(context, Prayer.FAJR))
        assertEquals(6, PrefsManager.getFixedTimeHour(context, Prayer.FAJR))
        assertEquals(30, PrefsManager.getFixedTimeMinute(context, Prayer.FAJR))
    }

    // ── JOMOAA defaults ─────────────────────────

    @Test
    fun jomoaaDefaultAfterMinutes_is60() {
        assertEquals(60, PrefsManager.getAfterMinutes(context, Prayer.JOMOAA))
    }

    @Test
    fun jomoaaDefaultSilenceMode_isFixedTime() {
        assertEquals(SilenceMode.FIXED_TIME, PrefsManager.getSilenceMode(context, Prayer.JOMOAA))
    }

    @Test
    fun jomoaaDefaultFixedTime_is1315() {
        assertEquals(13, PrefsManager.getFixedTimeHour(context, Prayer.JOMOAA))
        assertEquals(15, PrefsManager.getFixedTimeMinute(context, Prayer.JOMOAA))
    }

    @Test
    fun jomoaaConfig_isIndependentFromDhuhr() {
        PrefsManager.setAfterMinutes(context, Prayer.JOMOAA, 90)
        PrefsManager.setAfterMinutes(context, Prayer.DHUHR, 45)
        assertEquals(90, PrefsManager.getAfterMinutes(context, Prayer.JOMOAA))
        assertEquals(45, PrefsManager.getAfterMinutes(context, Prayer.DHUHR))
    }

    @Test
    fun jomoaaConfig_setAndGet() {
        PrefsManager.setSilenceMode(context, Prayer.JOMOAA, SilenceMode.DURATION)
        PrefsManager.setAfterMinutes(context, Prayer.JOMOAA, 75)
        PrefsManager.setFixedTime(context, Prayer.JOMOAA, 14, 30)
        PrefsManager.setDelayMinutes(context, Prayer.JOMOAA, 5)
        PrefsManager.setDelayMode(context, Prayer.JOMOAA, DelayMode.FIXED_TIME)
        PrefsManager.setDelayFixedTime(context, Prayer.JOMOAA, 12, 45)

        val config = PrefsManager.getConfig(context, Prayer.JOMOAA)
        assertEquals(SilenceMode.DURATION, config.mode)
        assertEquals(75, config.afterMinutes)
        assertEquals(14, config.fixedHour)
        assertEquals(30, config.fixedMinute)
        assertEquals(5, config.delayMinutes)
        assertEquals(DelayMode.FIXED_TIME, config.delayMode)
        assertEquals(12, config.delayFixedHour)
        assertEquals(45, config.delayFixedMinute)
    }
}

package com.tunisianprayertimes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.chrono.HijrahDate
import java.time.temporal.ChronoField

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
        assertEquals(50, PrefsManager.getConfig(context, Prayer.ISHA).afterMinutes)
    }

    @Test
    fun ramadanOverride_bumpsIshaWhenLowerThan90() {
        // Simulate Ramadan: use a known Ramadan date (9th month of Hijri calendar)
        val ramadanDate = findRamadanDate()
        PrefsManager.setAfterMinutes(context, Prayer.ISHA, 30)
        PrefsManager.applyRamadanIshaOverrideIfNeeded(context, ramadanDate)
        assertEquals(90, PrefsManager.getAfterMinutes(context, Prayer.ISHA))
    }

    @Test
    fun ramadanOverride_doesNotLowerIshaWhenAlreadyHigher() {
        val ramadanDate = findRamadanDate()
        PrefsManager.setAfterMinutes(context, Prayer.ISHA, 120)
        PrefsManager.applyRamadanIshaOverrideIfNeeded(context, ramadanDate)
        assertEquals(120, PrefsManager.getAfterMinutes(context, Prayer.ISHA))
    }

    @Test
    fun ramadanOverride_appliedOnce_userChangeRespected() {
        val ramadanDate = findRamadanDate()
        // First call: override applied
        PrefsManager.applyRamadanIshaOverrideIfNeeded(context, ramadanDate)
        assertEquals(90, PrefsManager.getAfterMinutes(context, Prayer.ISHA))

        // User changes to 45
        PrefsManager.setAfterMinutes(context, Prayer.ISHA, 45)

        // Second call: override NOT re-applied because already applied this Hijri year
        PrefsManager.applyRamadanIshaOverrideIfNeeded(context, ramadanDate)
        assertEquals(45, PrefsManager.getAfterMinutes(context, Prayer.ISHA))
    }

    @Test
    fun ramadanOverride_notAppliedOutsideRamadan() {
        // Use a non-Ramadan date (Hijri month 1)
        val nonRamadanDate = HijrahDate.now().with(ChronoField.MONTH_OF_YEAR, 1).with(ChronoField.DAY_OF_MONTH, 15)
        PrefsManager.setAfterMinutes(context, Prayer.ISHA, 30)
        PrefsManager.applyRamadanIshaOverrideIfNeeded(context, nonRamadanDate)
        assertEquals(30, PrefsManager.getAfterMinutes(context, Prayer.ISHA))
    }

    @Test
    fun ramadanOverride_exactlyAt90_notChanged() {
        val ramadanDate = findRamadanDate()
        PrefsManager.setAfterMinutes(context, Prayer.ISHA, 90)
        PrefsManager.applyRamadanIshaOverrideIfNeeded(context, ramadanDate)
        assertEquals(90, PrefsManager.getAfterMinutes(context, Prayer.ISHA))
    }

    @Test
    fun ramadanOverride_doesNotAffectOtherPrayers() {
        val ramadanDate = findRamadanDate()
        PrefsManager.setAfterMinutes(context, Prayer.FAJR, 10)
        PrefsManager.setAfterMinutes(context, Prayer.DHUHR, 15)
        PrefsManager.setAfterMinutes(context, Prayer.ASR, 20)
        PrefsManager.setAfterMinutes(context, Prayer.MAGHRIB, 5)

        PrefsManager.applyRamadanIshaOverrideIfNeeded(context, ramadanDate)

        assertEquals(10, PrefsManager.getAfterMinutes(context, Prayer.FAJR))
        assertEquals(15, PrefsManager.getAfterMinutes(context, Prayer.DHUHR))
        assertEquals(20, PrefsManager.getAfterMinutes(context, Prayer.ASR))
        assertEquals(5, PrefsManager.getAfterMinutes(context, Prayer.MAGHRIB))
    }

    @Test
    fun ramadanOverride_reappliesNextHijriYear() {
        val thisYear = HijrahDate.now().get(ChronoField.YEAR)
        val thisYearRamadan = HijrahDate.of(thisYear, 9, 15)
        val nextYearRamadan = HijrahDate.of(thisYear + 1, 9, 15)

        // Apply override this year
        PrefsManager.applyRamadanIshaOverrideIfNeeded(context, thisYearRamadan)
        assertEquals(90, PrefsManager.getAfterMinutes(context, Prayer.ISHA))

        // User lowers it back to 30
        PrefsManager.setAfterMinutes(context, Prayer.ISHA, 30)

        // Next year's Ramadan: override should apply again
        PrefsManager.applyRamadanIshaOverrideIfNeeded(context, nextYearRamadan)
        assertEquals(90, PrefsManager.getAfterMinutes(context, Prayer.ISHA))
    }

    @Test
    fun ramadanOverride_multipleCallsSameYear_idempotent() {
        val ramadanDate = findRamadanDate()
        PrefsManager.setAfterMinutes(context, Prayer.ISHA, 30)

        PrefsManager.applyRamadanIshaOverrideIfNeeded(context, ramadanDate)
        PrefsManager.applyRamadanIshaOverrideIfNeeded(context, ramadanDate)
        PrefsManager.applyRamadanIshaOverrideIfNeeded(context, ramadanDate)

        assertEquals(90, PrefsManager.getAfterMinutes(context, Prayer.ISHA))
    }

    @Test
    fun ramadanOverride_defaultIshaValue_getsBumped() {
        // No explicit setAfterMinutes — default is 30, should get bumped to 90
        val ramadanDate = findRamadanDate()
        assertEquals(30, PrefsManager.getAfterMinutes(context, Prayer.ISHA))

        PrefsManager.applyRamadanIshaOverrideIfNeeded(context, ramadanDate)
        assertEquals(90, PrefsManager.getAfterMinutes(context, Prayer.ISHA))
    }

    @Test
    fun ramadanOverride_userSetsHigherThenLower_secondRamadanBumpsAgain() {
        val thisYear = HijrahDate.now().get(ChronoField.YEAR)
        val thisYearRamadan = HijrahDate.of(thisYear, 9, 15)
        val nextYearRamadan = HijrahDate.of(thisYear + 1, 9, 15)

        // This year: user already has 120, override doesn't lower it
        PrefsManager.setAfterMinutes(context, Prayer.ISHA, 120)
        PrefsManager.applyRamadanIshaOverrideIfNeeded(context, thisYearRamadan)
        assertEquals(120, PrefsManager.getAfterMinutes(context, Prayer.ISHA))

        // User later sets it to 20
        PrefsManager.setAfterMinutes(context, Prayer.ISHA, 20)

        // Next year Ramadan: bumps back to 90
        PrefsManager.applyRamadanIshaOverrideIfNeeded(context, nextYearRamadan)
        assertEquals(90, PrefsManager.getAfterMinutes(context, Prayer.ISHA))
    }

    @Test
    fun ishaDefault_outsideRamadan_is30() {
        // Default Isha afterMinutes is always 30 (Ramadan override is a one-time write, not a getter override)
        assertEquals(30, PrefsManager.getAfterMinutes(context, Prayer.ISHA))
    }

    /** Helper: find a HijrahDate in Ramadan month 9, day 15 of the current Hijri year */
    private fun findRamadanDate(): HijrahDate {
        val now = HijrahDate.now()
        val year = now.get(ChronoField.YEAR)
        return HijrahDate.of(year, 9, 15)
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
}

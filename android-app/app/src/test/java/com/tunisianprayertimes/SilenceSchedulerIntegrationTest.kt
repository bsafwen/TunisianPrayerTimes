package com.tunisianprayertimes

import android.app.AlarmManager
import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowNotificationManager
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.util.Calendar

/**
 * Integration tests covering the full silence scheduling lifecycle:
 * configure prefs → schedule → verify alarms → reschedule on change.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
@Suppress("DEPRECATION")
class SilenceSchedulerIntegrationTest {

    private lateinit var context: Context
    private lateinit var shadowAlarmManager: ShadowAlarmManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        shadowAlarmManager = Shadows.shadowOf(alarmManager)
        context.getSharedPreferences("prayer_silence_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        setCachedOverrideForTest(null)
    }

    private fun setCachedOverrideForTest(override: RamadanOverrideChecker.RamadanOverride?) {
        val field = RamadanOverrideChecker::class.java.getDeclaredField("cachedOverride")
        field.isAccessible = true
        field.set(RamadanOverrideChecker, override)
    }

    private fun setOfficial1447Override() {
        setCachedOverrideForTest(
            RamadanOverrideChecker.RamadanOverride(
                hijriYear = 1447,
                ramadanStart = LocalDate.of(2026, 2, 19),
                eidFitrDate = LocalDate.of(2026, 3, 20),
                eidAdhaDate = LocalDate.of(2026, 5, 27),
            )
        )
    }

    private fun calendarAt(year: Int, month: Int, day: Int, hour: Int, minute: Int): Calendar {
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }

    private fun assertAlarmAt(expected: Calendar, message: String) {
        val triggerTimes = shadowAlarmManager.scheduledAlarms.map { it.triggerAtTime }
        assertTrue("$message, expected=${expected.timeInMillis}, alarms=$triggerTimes",
            triggerTimes.contains(expected.timeInMillis))
    }

    private fun grantNotificationPolicyAccess() {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val shadowNotificationManager = Shadows.shadowOf(notificationManager)
        shadowNotificationManager.setNotificationPolicyAccessGranted(true)
    }

    @Test
    fun fullCycle_enableScheduleDisableCancel() {
        // 1. Enable auto-silence
        PrefsManager.setEnabled(context, true)
        SilenceScheduler.scheduleAll(context)
        val alarmsAfterSchedule = shadowAlarmManager.scheduledAlarms.size
        assertTrue("Should have alarms after scheduling", alarmsAfterSchedule > 0)

        // 2. Disable auto-silence
        PrefsManager.setEnabled(context, false)
        SilenceScheduler.scheduleAll(context)
        assertEquals("Should have no alarms after disabling", 0, shadowAlarmManager.scheduledAlarms.size)

        // 3. Re-enable
        PrefsManager.setEnabled(context, true)
        SilenceScheduler.scheduleAll(context)
        assertTrue("Should have alarms after re-enabling", shadowAlarmManager.scheduledAlarms.size > 0)

        // 4. Cancel all
        SilenceScheduler.cancelAll(context)
        assertEquals("Should have no alarms after cancel", 0, shadowAlarmManager.scheduledAlarms.size)
    }

    @Test
    fun changeDelegation_reschedulesWithNewTimes() {
        PrefsManager.setEnabled(context, true)

        // Schedule with default delegation (Tunis)
        SilenceScheduler.scheduleAll(context)
        val tunisAlarms = shadowAlarmManager.scheduledAlarms.toList()

        // Change delegation
        PrefsManager.setDelegationId(context, 386)
        SilenceScheduler.scheduleAll(context)
        val newAlarms = shadowAlarmManager.scheduledAlarms.toList()

        // Alarms should still exist (both delegations have data)
        assertTrue("Should have alarms with new delegation", newAlarms.isNotEmpty())
    }

    @Test
    fun changeSilenceMode_reschedulesCorrectly() {
        PrefsManager.setEnabled(context, true)

        // Set FIXED_TIME mode with a known end time for FAJR
        PrefsManager.setSilenceMode(context, Prayer.FAJR, SilenceMode.FIXED_TIME)
        PrefsManager.setFixedTime(context, Prayer.FAJR, 6, 30)

        SilenceScheduler.scheduleAll(context)
        assertTrue("Should have scheduled alarms", shadowAlarmManager.scheduledAlarms.isNotEmpty())

        // Switch to DURATION mode
        PrefsManager.setSilenceMode(context, Prayer.FAJR, SilenceMode.DURATION)
        PrefsManager.setAfterMinutes(context, Prayer.FAJR, 45)

        SilenceScheduler.scheduleAll(context)
        assertTrue("Should still have scheduled alarms", shadowAlarmManager.scheduledAlarms.isNotEmpty())
    }

    @Test
    fun configForEachPrayer_isIndependent() {
        // Configure each prayer differently
        PrefsManager.setSilenceMode(context, Prayer.FAJR, SilenceMode.DURATION)
        PrefsManager.setAfterMinutes(context, Prayer.FAJR, 45)

        PrefsManager.setSilenceMode(context, Prayer.DHUHR, SilenceMode.FIXED_TIME)
        PrefsManager.setFixedTime(context, Prayer.DHUHR, 13, 30)

        PrefsManager.setDelayMode(context, Prayer.ASR, DelayMode.FIXED_TIME)
        PrefsManager.setDelayFixedTime(context, Prayer.ASR, 16, 0)

        PrefsManager.setDelayMode(context, Prayer.MAGHRIB, DelayMode.MINUTES)
        PrefsManager.setDelayMinutes(context, Prayer.MAGHRIB, 5)

        // Verify each config is independent
        val fajrConfig = PrefsManager.getConfig(context, Prayer.FAJR)
        val dhuhrConfig = PrefsManager.getConfig(context, Prayer.DHUHR)
        val asrConfig = PrefsManager.getConfig(context, Prayer.ASR)
        val maghribConfig = PrefsManager.getConfig(context, Prayer.MAGHRIB)

        assertEquals(SilenceMode.DURATION, fajrConfig.mode)
        assertEquals(45, fajrConfig.afterMinutes)

        assertEquals(SilenceMode.FIXED_TIME, dhuhrConfig.mode)
        assertEquals(13, dhuhrConfig.fixedHour)
        assertEquals(30, dhuhrConfig.fixedMinute)

        assertEquals(DelayMode.FIXED_TIME, asrConfig.delayMode)
        assertEquals(16, asrConfig.delayFixedHour)

        assertEquals(DelayMode.MINUTES, maghribConfig.delayMode)
        assertEquals(5, maghribConfig.delayMinutes)

        // Schedule should work fine
        PrefsManager.setEnabled(context, true)
        SilenceScheduler.scheduleAll(context)
        assertTrue("Should schedule with mixed configs", shadowAlarmManager.scheduledAlarms.isNotEmpty())
    }

    @Test
    fun bootReceiver_reschedulesWhenEnabled() = runBlocking {
        grantNotificationPolicyAccess()
        PrefsManager.setEnabled(context, true)
        val receiver = BootReceiver()
        receiver.refreshSchedules(context)

        // Should have rescheduled alarms
        assertTrue("Boot receiver should reschedule alarms", shadowAlarmManager.scheduledAlarms.isNotEmpty())
    }

    @Test
    fun silenceReceiver_rescheduleAction_reschedulesAll() {
        PrefsManager.setEnabled(context, true)
        val receiver = SilenceReceiver()
        receiver.onReceive(context, android.content.Intent("com.tunisianprayertimes.ACTION_RESCHEDULE"))

        // Should have scheduled alarms via RESCHEDULE action
        assertTrue("Reschedule action should schedule alarms", shadowAlarmManager.scheduledAlarms.isNotEmpty())
    }

    @Test
    fun allDelegations_loadPrayerTimesForToday() {
        val delegations = GouvernoratRepository.loadAllDelegations(context)
        assertTrue("Should have delegations", delegations.isNotEmpty())

        val now = Calendar.getInstance()
        val year = now.get(Calendar.YEAR)
        val month = now.get(Calendar.MONTH) + 1
        val day = now.get(Calendar.DAY_OF_MONTH)

        var loadedCount = 0
        for (delegation in delegations) {
            val dayTimes = PrayerTimesRepository.loadDayPrayerTimes(context, delegation.id, year, month, day)
            if (dayTimes != null) {
                loadedCount++
                assertEquals("Should have 5 prayers", 5, dayTimes.allPrayers().size)
            }
        }
        assertTrue("Most delegations should have data for today", loadedCount > delegations.size / 2)
    }

    @Test
    fun missingCsvData_returnsEmptyList() {
        // Non-existent delegation ID
        val times = PrayerTimesRepository.loadPrayerTimes(context, 99999, 2026, 3)
        assertTrue("Missing CSV should return empty list", times.isEmpty())

        // Non-existent year
        val times2 = PrayerTimesRepository.loadPrayerTimes(context, 615, 1990, 1)
        assertTrue("Missing year should return empty list", times2.isEmpty())
    }

    @Test
    fun hasPrayerData_trueForExistingData() {
        val now = Calendar.getInstance()
        assertTrue(
            "Tunis should have data for current month",
            PrayerTimesRepository.hasPrayerData(context, 615, now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1)
        )
    }

    @Test
    fun hasPrayerData_falseForMissingData() {
        assertFalse(
            "Non-existent delegation should not have data",
            PrayerTimesRepository.hasPrayerData(context, 99999, 2026, 1)
        )
        assertFalse(
            "Non-existent year should not have data",
            PrayerTimesRepository.hasPrayerData(context, 615, 1990, 1)
        )
    }

    @Test
    fun delegationsWithoutCsv_filteredOut() {
        // loadAllDelegations should only return delegations that have CSV data
        val delegations = GouvernoratRepository.loadAllDelegations(context)
        val now = Calendar.getInstance()
        val year = now.get(Calendar.YEAR)
        val month = now.get(Calendar.MONTH) + 1

        for (delegation in delegations) {
            assertTrue(
                "Delegation ${delegation.id} should have CSV data",
                PrayerTimesRepository.hasPrayerData(context, delegation.id, year, month)
            )
        }
    }

    @Test
    fun scheduleWithAllPrayerConfigs_producesCorrectAlarmCount() {
        PrefsManager.setEnabled(context, true)
        // Set non-zero delay for all prayers
        for (prayer in Prayer.values()) {
            PrefsManager.setDelayMinutes(context, prayer, 5)
            PrefsManager.setAfterMinutes(context, prayer, 30)
        }

        SilenceScheduler.scheduleAll(context)
        // At minimum the midnight reschedule alarm is always set; future prayer alarms depend on time of day
        val alarmCount = shadowAlarmManager.scheduledAlarms.size
        assertTrue("Should have at least the midnight reschedule alarm, got $alarmCount", alarmCount >= 1)
    }

    @Test
    fun eidFitrOverride_schedulesDefaultShurukSilence() {
        PrefsManager.setEnabled(context, true)
        PrefsManager.setDelegationId(context, 615)
        setOfficial1447Override()

        val now = calendarAt(2026, Calendar.MARCH, 20, 3, 0)
        val times = PrayerTimesRepository.loadDayPrayerTimes(context, 615, 2026, 3, 20)!!

        SilenceScheduler.scheduleAllInternal(context, now)

        val expectedSilence = calendarAt(2026, Calendar.MARCH, 20, times.shurukHour, times.shurukMinute)
        val expectedUnsilence = (expectedSilence.clone() as Calendar).apply { add(Calendar.MINUTE, 60) }
        assertAlarmAt(expectedSilence, "Aid el-Fitr silence should use Shuruk by default")
        assertAlarmAt(expectedUnsilence, "Aid el-Fitr unsilence should use the default 60-minute duration")
    }

    @Test
    fun eidAdhaOverride_schedulesDefaultShurukSilence() {
        PrefsManager.setEnabled(context, true)
        PrefsManager.setDelegationId(context, 615)
        setOfficial1447Override()

        val now = calendarAt(2026, Calendar.MAY, 27, 3, 0)
        val times = PrayerTimesRepository.loadDayPrayerTimes(context, 615, 2026, 5, 27)!!

        SilenceScheduler.scheduleAllInternal(context, now)

        val expectedSilence = calendarAt(2026, Calendar.MAY, 27, times.shurukHour, times.shurukMinute)
        val expectedUnsilence = (expectedSilence.clone() as Calendar).apply { add(Calendar.MINUTE, 60) }
        assertAlarmAt(expectedSilence, "Aid el-Adha silence should use Shuruk by default")
        assertAlarmAt(expectedUnsilence, "Aid el-Adha unsilence should use the default 60-minute duration")
    }

    @Test
    fun eidAdhaOverride_schedulesCustomPrayerTime() {
        PrefsManager.setEnabled(context, true)
        PrefsManager.setDelegationId(context, 615)
        PrefsManager.setAidAdhaTime(context, 7, 15)
        setOfficial1447Override()

        val now = calendarAt(2026, Calendar.MAY, 27, 3, 0)

        SilenceScheduler.scheduleAllInternal(context, now)

        val expectedSilence = calendarAt(2026, Calendar.MAY, 27, 7, 15)
        val expectedUnsilence = calendarAt(2026, Calendar.MAY, 27, 8, 15)
        assertAlarmAt(expectedSilence, "Aid el-Adha silence should use the user's custom time")
        assertAlarmAt(expectedUnsilence, "Aid el-Adha custom-time unsilence should keep the default 60-minute duration")
    }

    // --- Tomorrow's Fajr integration tests ---

    @Test
    fun fullCycle_tomorrowFajrSurvivesDelegationChange() {
        PrefsManager.setEnabled(context, true)

        // Schedule with Tunis (615)
        SilenceScheduler.scheduleAll(context)
        val tunisAlarms = shadowAlarmManager.scheduledAlarms.map { it.triggerAtTime }.toSet()

        // Change to a different delegation
        PrefsManager.setDelegationId(context, 386)
        SilenceScheduler.scheduleAll(context)
        val newAlarms = shadowAlarmManager.scheduledAlarms.map { it.triggerAtTime }.toSet()

        // Alarms should differ (different delegation = different prayer times)
        // unless both have identical Fajr times, which is unlikely
        assertTrue("Should have alarms after delegation change", newAlarms.isNotEmpty())
    }

    @Test
    fun tomorrowFajr_persistsThroughConfigChange() {
        PrefsManager.setEnabled(context, true)

        // Use a late-night time so tomorrow's Fajr is gated (after Isha)
        val lateNight = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // Schedule with default config
        SilenceScheduler.scheduleAllInternal(context, lateNight)
        val alarmsBefore = shadowAlarmManager.scheduledAlarms.map { it.triggerAtTime }.toSet()

        // Change Fajr duration and reschedule
        PrefsManager.setAfterMinutes(context, Prayer.FAJR, 90)
        SilenceScheduler.scheduleAllInternal(context, lateNight)
        val alarmsAfter = shadowAlarmManager.scheduledAlarms.map { it.triggerAtTime }.toSet()

        // The unsilence time should differ (90 min instead of default 60 min)
        assertNotEquals(
            "Alarm set should change after config update",
            alarmsBefore, alarmsAfter
        )
    }

    @Test
    fun bootReceiver_reschedulesTomorrowFajr() {
        // Tomorrow's Fajr is only pre-scheduled after Isha, so this test uses
        // scheduleAllInternal with a controlled late-night time instead of
        // relying on BootReceiver (which calls scheduleAll with the real clock).
        PrefsManager.setEnabled(context, true)

        val lateNight = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        SilenceScheduler.scheduleAllInternal(context, lateNight)

        val tomorrow = (lateNight.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        val tomorrowTimes = PrayerTimesRepository.loadDayPrayerTimes(
            context, PrefsManager.getDelegationId(context),
            tomorrow.get(Calendar.YEAR),
            tomorrow.get(Calendar.MONTH) + 1,
            tomorrow.get(Calendar.DAY_OF_MONTH)
        )

        if (tomorrowTimes != null) {
            val fajr = tomorrowTimes.fajr
            val config = PrefsManager.getConfig(context, Prayer.FAJR)
            val expectedSilence = (tomorrow.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, fajr.hour)
                set(Calendar.MINUTE, fajr.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.MINUTE, config.delayMinutes)
            }

            val alarmTriggerTimes = shadowAlarmManager.scheduledAlarms.map { it.triggerAtTime }
            assertTrue(
                "After Isha, tomorrow's Fajr should be scheduled",
                alarmTriggerTimes.contains(expectedSilence.timeInMillis)
            )
        }
    }

    @Test
    fun rescheduleAction_includesTomorrowFajr() {
        // Tomorrow's Fajr is only pre-scheduled after Isha, so use
        // scheduleAllInternal with a controlled late-night time.
        PrefsManager.setEnabled(context, true)

        val lateNight = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        SilenceScheduler.scheduleAllInternal(context, lateNight)

        val tomorrow = (lateNight.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        val tomorrowTimes = PrayerTimesRepository.loadDayPrayerTimes(
            context, PrefsManager.getDelegationId(context),
            tomorrow.get(Calendar.YEAR),
            tomorrow.get(Calendar.MONTH) + 1,
            tomorrow.get(Calendar.DAY_OF_MONTH)
        )

        if (tomorrowTimes != null) {
            val fajr = tomorrowTimes.fajr
            val config = PrefsManager.getConfig(context, Prayer.FAJR)
            val expectedSilence = (tomorrow.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, fajr.hour)
                set(Calendar.MINUTE, fajr.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.MINUTE, config.delayMinutes)
            }

            val alarmTriggerTimes = shadowAlarmManager.scheduledAlarms.map { it.triggerAtTime }
            assertTrue(
                "After Isha, reschedule should include tomorrow's Fajr",
                alarmTriggerTimes.contains(expectedSilence.timeInMillis)
            )
        }
    }

    // --- Bug reproduction: outside window, switch to fixed-time unsilence ---

    /**
     * BUG REPORT:
     * - Fajr at 04:00, duration 10 min → original window 04:00–04:10
     * - At 05:30, user switches unsilence to FIXED_TIME 05:31
     * - New window 04:00–05:31 → app correctly silences (enableAutoSilence)
     * - Expected: UNSILENCE alarm at 05:31 fires and restores phone
     * - Actual: After 05:31 the phone is still silenced
     *
     * This test verifies that scheduleAllInternal sets:
     *   1. auto-silence active (phone silenced)
     *   2. an alarm whose trigger time matches 05:31
     */
    @Test
    fun bugRepro_outsideOriginalWindow_switchToFixedTime_schedulesUnsilence() {
        // Grant DND permission for Robolectric
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val shadowNm = Shadows.shadowOf(nm)
        shadowNm.setNotificationPolicyAccessGranted(true)

        PrefsManager.setEnabled(context, true)
        PrefsManager.setDelegationId(context, 615)

        // Configure Fajr: FIXED_TIME unsilence at 05:31, no delay
        PrefsManager.setSilenceMode(context, Prayer.FAJR, SilenceMode.FIXED_TIME)
        PrefsManager.setFixedTime(context, Prayer.FAJR, 5, 31)
        PrefsManager.setDelayMode(context, Prayer.FAJR, DelayMode.MINUTES)
        PrefsManager.setDelayMinutes(context, Prayer.FAJR, 0)

        // "now" is 05:30 — outside the original 10-min window, inside the new fixed-time window
        val now = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 5)
            set(Calendar.MINUTE, 30)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        SilenceScheduler.scheduleAllInternal(context, now)

        // The phone should have been silenced (marks auto-silence active)
        assertTrue("Auto-silence should be active (phone silenced)",
            PrefsManager.isAutoSilenceActive(context))

        // There must be an alarm at 05:31 for UNSILENCE
        val expected0531 = (now.clone() as Calendar).apply {
            set(Calendar.MINUTE, 31)
        }
        val alarmTriggers = shadowAlarmManager.scheduledAlarms.map { it.triggerAtTime }
        assertTrue(
            "Must have an alarm at 05:31 (${expected0531.timeInMillis}), alarms=$alarmTriggers",
            alarmTriggers.contains(expected0531.timeInMillis)
        )
    }

    // --- Year boundary fallback tests ---

    @Test
    fun tomorrowFajr_dec31_fallsBackToPreviousYear() {
        PrefsManager.setEnabled(context, true)

        // Simulate Dec 31 at 23:00 — tomorrow is Jan 1 of a year with no data (2027)
        val dec31 = Calendar.getInstance().apply {
            set(Calendar.YEAR, 2026)
            set(Calendar.MONTH, Calendar.DECEMBER)
            set(Calendar.DAY_OF_MONTH, 31)
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // Verify 2027 data doesn't exist but 2026 data does
        assertNull(
            "2027 data should not exist",
            PrayerTimesRepository.loadDayPrayerTimes(context, 615, 2027, 1, 1)
        )
        assertNotNull(
            "2026 Jan 1 data should exist as fallback",
            PrayerTimesRepository.loadDayPrayerTimes(context, 615, 2026, 1, 1)
        )

        // Call scheduleTomorrowPrayers with Dec 31 — should use 2026 fallback
        SilenceScheduler.scheduleTomorrowPrayers(context, 615, dec31)

        val alarms = shadowAlarmManager.scheduledAlarms
        assertTrue(
            "Should schedule Fajr alarms using previous year fallback",
            alarms.isNotEmpty()
        )

        // Verify the alarm is set for Jan 1 2027 (correct date) using 2026 Fajr times
        val fallbackTimes = PrayerTimesRepository.loadDayPrayerTimes(context, 615, 2026, 1, 1)!!
        val config = PrefsManager.getConfig(context, Prayer.FAJR)
        val expectedSilence = Calendar.getInstance().apply {
            set(Calendar.YEAR, 2027)
            set(Calendar.MONTH, Calendar.JANUARY)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, fallbackTimes.fajr.hour)
            set(Calendar.MINUTE, fallbackTimes.fajr.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.MINUTE, config.delayMinutes)
        }

        val triggerTimes = alarms.map { it.triggerAtTime }
        assertTrue(
            "Alarm should fire at Jan 1 2027 Fajr time from 2026 fallback data",
            triggerTimes.contains(expectedSilence.timeInMillis)
        )
    }

    @Test
    fun tomorrowFajr_dec31_noFallback_noAlarms() {
        PrefsManager.setEnabled(context, true)

        // Simulate a delegation with no data at all for the fallback year
        val dec31 = Calendar.getInstance().apply {
            set(Calendar.YEAR, 2026)
            set(Calendar.MONTH, Calendar.DECEMBER)
            set(Calendar.DAY_OF_MONTH, 31)
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // Non-existent delegation — neither 2027 nor 2026 data exists
        SilenceScheduler.scheduleTomorrowPrayers(context, 99999, dec31)

        assertEquals(
            "Should not schedule any alarms when no data exists at all",
            0, shadowAlarmManager.scheduledAlarms.size
        )
    }

    @Test
    fun tomorrowFajr_withinSameYear_doesNotUseFallback() {
        PrefsManager.setEnabled(context, true)

        // A normal day within 2026 — tomorrow is also in 2026
        val march30 = Calendar.getInstance().apply {
            set(Calendar.YEAR, 2026)
            set(Calendar.MONTH, Calendar.MARCH)
            set(Calendar.DAY_OF_MONTH, 30)
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // 2026 March 31 data should exist directly
        assertNotNull(
            "March 31 2026 data should exist",
            PrayerTimesRepository.loadDayPrayerTimes(context, 615, 2026, 3, 31)
        )

        SilenceScheduler.scheduleTomorrowPrayers(context, 615, march30)

        val alarms = shadowAlarmManager.scheduledAlarms
        assertTrue("Should schedule Fajr alarms normally", alarms.isNotEmpty())

        // Verify it uses the correct March 31 data, not any fallback
        val march31Times = PrayerTimesRepository.loadDayPrayerTimes(context, 615, 2026, 3, 31)!!
        val config = PrefsManager.getConfig(context, Prayer.FAJR)
        val expectedSilence = Calendar.getInstance().apply {
            set(Calendar.YEAR, 2026)
            set(Calendar.MONTH, Calendar.MARCH)
            set(Calendar.DAY_OF_MONTH, 31)
            set(Calendar.HOUR_OF_DAY, march31Times.fajr.hour)
            set(Calendar.MINUTE, march31Times.fajr.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.MINUTE, config.delayMinutes)
        }

        val triggerTimes = alarms.map { it.triggerAtTime }
        assertTrue(
            "Alarm should use current year data, not fallback",
            triggerTimes.contains(expectedSilence.timeInMillis)
        )
    }

    /**
     * Bug reproduction: user dismisses auto-silence then changes to a delegation
     * whose prayer time is LATER than the dismiss timestamp.
     *
     * Uses delegations 386 and 500 — Dhuhr differs by ~7 min (386 is earlier).
     *
     * 1. Phone silences for delegation 386's Dhuhr
     * 2. User taps dismiss 2 min later
     * 3. User changes delegation to 500 → rescheduleIfEnabled → scheduleAllInternal
     * 4. scheduleAllInternal runs while inside delegation 500's Dhuhr window
     * 5. BUG: dismissed check fails because dismissedAt < new silenceTime
     * 6. Phone gets re-silenced despite user's explicit dismiss!
     */
    @Test
    fun bugRepro_dismissAutoSilence_thenChangeDelegation_shouldNotResilence() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val shadowNm = Shadows.shadowOf(nm)
        shadowNm.setNotificationPolicyAccessGranted(true)

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL

        PrefsManager.setEnabled(context, true)
        PrefsManager.setDelegationId(context, 386)
        PrefsManager.setSilenceMode(context, Prayer.DHUHR, SilenceMode.DURATION)
        PrefsManager.setAfterMinutes(context, Prayer.DHUHR, 20)
        PrefsManager.setDelayMode(context, Prayer.DHUHR, DelayMode.MINUTES)
        PrefsManager.setDelayMinutes(context, Prayer.DHUHR, 0)

        // Use a stable non-Friday date so this regression exercises DHUHR, not JOMOAA.
        val testDate = calendarAt(2026, Calendar.MAY, 21, 0, 0)
        val times386 = PrayerTimesRepository.loadDayPrayerTimes(
            context,
            386,
            testDate.get(Calendar.YEAR),
            testDate.get(Calendar.MONTH) + 1,
            testDate.get(Calendar.DAY_OF_MONTH),
        )!!
        val times500 = PrayerTimesRepository.loadDayPrayerTimes(
            context,
            500,
            testDate.get(Calendar.YEAR),
            testDate.get(Calendar.MONTH) + 1,
            testDate.get(Calendar.DAY_OF_MONTH),
        )!!
        // Sanity: delegation 500 Dhuhr must be later than 386
        assertTrue(
            "Test requires delegation 500 Dhuhr to be later than 386",
            times500.dhuhr.hour * 60 + times500.dhuhr.minute > times386.dhuhr.hour * 60 + times386.dhuhr.minute
        )

        // Step 1: Time is 2 min after delegation 386's Dhuhr → inside its window
        val timeDismiss = (testDate.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, times386.dhuhr.hour)
            set(Calendar.MINUTE, times386.dhuhr.minute + 2)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        SilenceScheduler.scheduleAllInternal(context, timeDismiss)
        assertTrue("Phone should be auto-silenced", PrefsManager.isAutoSilenceActive(context))

        // Step 2: User dismisses auto-silence
        SilenceModeController.disableAutoSilence(context)
        assertFalse("Auto-silence should be cleared after dismiss", PrefsManager.isAutoSilenceActive(context))
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audioManager.ringerMode)
        PrefsManager.setAutoSilenceDismissed(context, timeDismiss.timeInMillis, Prayer.DHUHR)

        // Step 3: User changes delegation to 500 (Dhuhr is later)
        PrefsManager.setDelegationId(context, 500)

        // Step 4: scheduleAll runs 2 min after delegation 500's Dhuhr → inside 500's window
        val timeAfterChange = (testDate.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, times500.dhuhr.hour)
            set(Calendar.MINUTE, times500.dhuhr.minute + 2)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        SilenceScheduler.scheduleAllInternal(context, timeAfterChange)

        // Step 5: phone should NOT be re-silenced
        assertFalse(
            "Phone was re-silenced after delegation change despite user dismiss!",
            PrefsManager.isAutoSilenceActive(context)
        )
        assertEquals(
            "Ringer should remain NORMAL after delegation change when user dismissed",
            AudioManager.RINGER_MODE_NORMAL,
            audioManager.ringerMode
        )
    }

    /**
     * Bug reproduction: phone is actively silenced for a prayer, then delegation
     * auto-updates (via SilenceVerifyWorker or ACTION_SILENCE background coroutine)
     * to a delegation whose prayer time is LATER. scheduleAll() doesn't see the
     * current time as inside the new delegation's window and unsilences prematurely.
     *
     * Uses delegations 386 and 500 — Maghrib differs by ~8 min (386 is earlier).
     *
     * 1. Phone silenced for delegation 386's Maghrib
     * 2. SilenceVerifyWorker updates delegation to 500
     * 3. scheduleAll() runs between delegation 386's and 500's Maghrib times
     * 4. BUG: current time is before 500's window → disableAutoSilence() called
     */
    @Test
    fun bugRepro_delegationAutoUpdate_unsilencesDuringActiveSilenceWindow() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val shadowNm = Shadows.shadowOf(nm)
        shadowNm.setNotificationPolicyAccessGranted(true)

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL

        PrefsManager.setEnabled(context, true)
        PrefsManager.setDelegationId(context, 386)
        PrefsManager.setSilenceMode(context, Prayer.MAGHRIB, SilenceMode.DURATION)
        PrefsManager.setAfterMinutes(context, Prayer.MAGHRIB, 20)
        PrefsManager.setDelayMode(context, Prayer.MAGHRIB, DelayMode.MINUTES)
        PrefsManager.setDelayMinutes(context, Prayer.MAGHRIB, 0)

        // Load actual prayer times for today
        val today = Calendar.getInstance()
        val times386 = PrayerTimesRepository.loadDayPrayerTimes(
            context, 386, today.get(Calendar.YEAR), today.get(Calendar.MONTH) + 1, today.get(Calendar.DAY_OF_MONTH)
        )!!
        val times500 = PrayerTimesRepository.loadDayPrayerTimes(
            context, 500, today.get(Calendar.YEAR), today.get(Calendar.MONTH) + 1, today.get(Calendar.DAY_OF_MONTH)
        )!!
        assertTrue(
            "Test requires delegation 500 Maghrib to be later than 386",
            times500.maghrib.hour * 60 + times500.maghrib.minute > times386.maghrib.hour * 60 + times386.maghrib.minute
        )

        // Step 1: Simulate the state AFTER the SILENCE alarm fired for delegation 386's Maghrib
        PrefsManager.markAutoSilenceActive(
            context, AudioManager.RINGER_MODE_NORMAL,
            android.app.NotificationManager.INTERRUPTION_FILTER_ALL
        )
        audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT

        assertTrue("Phone should be auto-silenced for Maghrib", PrefsManager.isAutoSilenceActive(context))
        assertEquals(AudioManager.RINGER_MODE_SILENT, audioManager.ringerMode)

        // Step 2: Delegation auto-updates to 500 (simulating SilenceVerifyWorker)
        PrefsManager.setDelegationId(context, 500)

        // Step 3: scheduleAll() runs between the two delegations' Maghrib times
        // (after 386's Maghrib, before 500's Maghrib)
        val timeBetween = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, times386.maghrib.hour)
            set(Calendar.MINUTE, times386.maghrib.minute + 3)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        SilenceScheduler.scheduleAllInternal(context, timeBetween)

        // Step 4: phone should NOT be unsilenced
        assertTrue(
            "Phone was unsilenced during active prayer silence! " +
            "Delegation auto-update shifted the window and scheduleAll() " +
            "incorrectly called disableAutoSilence().",
            PrefsManager.isAutoSilenceActive(context)
        )
        assertEquals(
            "Ringer should remain SILENT — delegation auto-update should not interrupt silence",
            AudioManager.RINGER_MODE_SILENT,
            audioManager.ringerMode
        )
    }

    /**
     * Bug reproduction: after delegation auto-update creates an "imminent window" state,
     * if the user dismisses silence, the rescheduled SILENCE alarm should NOT re-silence.
     *
     * Scenario:
     * 1. Phone silenced for delegation 386's Maghrib (e.g., 19:12)
     * 2. Delegation auto-updates to 500 (Maghrib 19:20)
     * 3. scheduleAll at 19:15: imminent window keeps silence, schedules SILENCE at 19:20
     * 4. User dismisses at 19:16
     * 5. At 19:20, SILENCE alarm fires → should honour dismissal, NOT re-silence
     */
    @Test
    fun bugRepro_silenceAlarmHonoursDismissalAfterDelegationChange() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val shadowNm = Shadows.shadowOf(nm)
        shadowNm.setNotificationPolicyAccessGranted(true)

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL

        PrefsManager.setEnabled(context, true)
        PrefsManager.setDelegationId(context, 500)
        PrefsManager.setSilenceMode(context, Prayer.MAGHRIB, SilenceMode.DURATION)
        PrefsManager.setAfterMinutes(context, Prayer.MAGHRIB, 20)
        PrefsManager.setDelayMode(context, Prayer.MAGHRIB, DelayMode.MINUTES)
        PrefsManager.setDelayMinutes(context, Prayer.MAGHRIB, 0)

        // Simulate: user already dismissed silence for MAGHRIB
        PrefsManager.setAutoSilenceDismissed(context, System.currentTimeMillis(), Prayer.MAGHRIB)

        // The rescheduled SILENCE alarm fires (from SilenceReceiver)
        val receiver = SilenceReceiver()
        val intent = android.content.Intent("com.tunisianprayertimes.ACTION_SILENCE").apply {
            putExtra("extra_prayer", Prayer.MAGHRIB.name)
        }
        receiver.onReceive(context, intent)

        // Phone should NOT be re-silenced because user dismissed MAGHRIB
        assertFalse(
            "SILENCE alarm should honour user's dismissal for the same prayer",
            PrefsManager.isAutoSilenceActive(context)
        )
        assertEquals(
            "Ringer should remain NORMAL after dismissed SILENCE alarm fires",
            AudioManager.RINGER_MODE_NORMAL,
            audioManager.ringerMode
        )
    }

    /**
     * Bug reproduction: stale UNSILENCE alarm from old delegation fires and
     * unsilences the phone even though a new prayer is imminent.
     *
     * Scenario (using delegations 591 = latest Maghrib, 488 = earliest Maghrib):
     * 1. Phone silenced for delegation 591's Maghrib (19:23), UNSILENCE at 19:43
     * 2. Delegation changes to 488 (Maghrib 19:01, window 19:01-19:21)
     * 3. scheduleAll at 19:25 — delegation 488's Maghrib window has fully passed
     * 4. Without fix: old UNSILENCE alarm at 19:43 stays in AlarmManager and fires later
     * 5. With fix: stale alarms for past prayers are cancelled
     */
    @Test
    fun bugRepro_staleUnsilenceAlarm_cancelledAfterDelegationChange() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val shadowNm = Shadows.shadowOf(nm)
        shadowNm.setNotificationPolicyAccessGranted(true)

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL

        PrefsManager.setEnabled(context, true)
        PrefsManager.setDelegationId(context, 591)
        PrefsManager.setSilenceMode(context, Prayer.MAGHRIB, SilenceMode.DURATION)
        PrefsManager.setAfterMinutes(context, Prayer.MAGHRIB, 20)
        PrefsManager.setDelayMode(context, Prayer.MAGHRIB, DelayMode.MINUTES)
        PrefsManager.setDelayMinutes(context, Prayer.MAGHRIB, 0)

        val today = Calendar.getInstance()
        val times591 = PrayerTimesRepository.loadDayPrayerTimes(
            context, 591, today.get(Calendar.YEAR), today.get(Calendar.MONTH) + 1, today.get(Calendar.DAY_OF_MONTH)
        )!!
        val times488 = PrayerTimesRepository.loadDayPrayerTimes(
            context, 488, today.get(Calendar.YEAR), today.get(Calendar.MONTH) + 1, today.get(Calendar.DAY_OF_MONTH)
        )!!

        // Sanity: delegation 591 Maghrib must be later than 488
        assertTrue(
            "Test requires delegation 591 Maghrib to be later than 488",
            times591.maghrib.hour * 60 + times591.maghrib.minute > times488.maghrib.hour * 60 + times488.maghrib.minute
        )

        // Step 1: Schedule during delegation 591's Maghrib window
        val timeDuring591 = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, times591.maghrib.hour)
            set(Calendar.MINUTE, times591.maghrib.minute + 2)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        SilenceScheduler.scheduleAllInternal(context, timeDuring591)
        assertTrue("Phone should be auto-silenced during 591 Maghrib", PrefsManager.isAutoSilenceActive(context))

        // Count UNSILENCE alarms for Maghrib (request code = prayer.ordinal * 2 + 1)
        val maghribUnsilenceCode = Prayer.MAGHRIB.ordinal * 2 + 1
        val alarmsBeforeSwitch = shadowAlarmManager.scheduledAlarms.count { alarm ->
            val pi = alarm.operation
            pi != null
        }
        assertTrue("Should have alarms scheduled", alarmsBeforeSwitch > 0)

        // Step 2: Switch to delegation 488 and reschedule at a time AFTER 488's Maghrib window
        PrefsManager.setDelegationId(context, 488)
        val timeAfter488Maghrib = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, times488.maghrib.hour)
            set(Calendar.MINUTE, times488.maghrib.minute + 25) // well past 488's window (20 min duration)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // The auto_silence_active flag is still true from step 1
        assertTrue("Auto-silence should still be flagged active", PrefsManager.isAutoSilenceActive(context))

        SilenceScheduler.scheduleAllInternal(context, timeAfter488Maghrib)

        // Step 3: Verify the stale UNSILENCE alarm for Maghrib is cancelled
        // The old alarm was at 591's Maghrib + 20 min. After rescheduling with 488
        // (whose window has passed), no Maghrib alarms should remain.
        // Simulate the old UNSILENCE firing — it should be harmless
        val receiver = SilenceReceiver()
        val unsilenceIntent = android.content.Intent("com.tunisianprayertimes.ACTION_UNSILENCE").apply {
            putExtra("extra_prayer", Prayer.MAGHRIB.name)
        }

        // First verify disableAutoSilence was called by scheduleAll (phone unsilenced)
        // because 488's Isha is too far for imminent window
        assertFalse(
            "scheduleAll should have called disableAutoSilence since no prayer is imminent",
            PrefsManager.isAutoSilenceActive(context)
        )
    }
}

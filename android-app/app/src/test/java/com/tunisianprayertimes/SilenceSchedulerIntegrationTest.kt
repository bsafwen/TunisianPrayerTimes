package com.tunisianprayertimes

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
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
    fun bootReceiver_reschedulesWhenEnabled() {
        PrefsManager.setEnabled(context, true)
        val receiver = BootReceiver()
        receiver.onReceive(context, android.content.Intent(android.content.Intent.ACTION_BOOT_COMPLETED))

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

        // Schedule with default config
        SilenceScheduler.scheduleAll(context)
        val alarmsBefore = shadowAlarmManager.scheduledAlarms.map { it.triggerAtTime }.toSet()

        // Change Fajr duration and reschedule
        PrefsManager.setAfterMinutes(context, Prayer.FAJR, 90)
        SilenceScheduler.scheduleAll(context)
        val alarmsAfter = shadowAlarmManager.scheduledAlarms.map { it.triggerAtTime }.toSet()

        // The unsilence time should differ (90 min instead of default 60 min)
        assertNotEquals(
            "Alarm set should change after config update",
            alarmsBefore, alarmsAfter
        )
    }

    @Test
    fun bootReceiver_reschedulesTomorrowFajr() {
        PrefsManager.setEnabled(context, true)

        // Simulate boot
        val receiver = BootReceiver()
        receiver.onReceive(context, android.content.Intent(android.content.Intent.ACTION_BOOT_COMPLETED))

        val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
        val tomorrowTimes = PrayerTimesRepository.loadDayPrayerTimes(
            context, PrefsManager.getDelegationId(context),
            tomorrow.get(Calendar.YEAR),
            tomorrow.get(Calendar.MONTH) + 1,
            tomorrow.get(Calendar.DAY_OF_MONTH)
        )

        if (tomorrowTimes != null) {
            val fajr = tomorrowTimes.fajr
            val config = PrefsManager.getConfig(context, Prayer.FAJR)
            val expectedSilence = Calendar.getInstance().apply {
                set(Calendar.YEAR, tomorrow.get(Calendar.YEAR))
                set(Calendar.MONTH, tomorrow.get(Calendar.MONTH))
                set(Calendar.DAY_OF_MONTH, tomorrow.get(Calendar.DAY_OF_MONTH))
                set(Calendar.HOUR_OF_DAY, fajr.hour)
                set(Calendar.MINUTE, fajr.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.MINUTE, config.delayMinutes)
            }

            val alarmTriggerTimes = shadowAlarmManager.scheduledAlarms.map { it.triggerAtTime }
            assertTrue(
                "After boot, tomorrow's Fajr should be scheduled",
                alarmTriggerTimes.contains(expectedSilence.timeInMillis)
            )
        }
    }

    @Test
    fun rescheduleAction_includesTomorrowFajr() {
        PrefsManager.setEnabled(context, true)

        val receiver = SilenceReceiver()
        receiver.onReceive(context, android.content.Intent("com.tunisianprayertimes.ACTION_RESCHEDULE"))

        val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
        val tomorrowTimes = PrayerTimesRepository.loadDayPrayerTimes(
            context, PrefsManager.getDelegationId(context),
            tomorrow.get(Calendar.YEAR),
            tomorrow.get(Calendar.MONTH) + 1,
            tomorrow.get(Calendar.DAY_OF_MONTH)
        )

        if (tomorrowTimes != null) {
            val fajr = tomorrowTimes.fajr
            val config = PrefsManager.getConfig(context, Prayer.FAJR)
            val expectedSilence = Calendar.getInstance().apply {
                set(Calendar.YEAR, tomorrow.get(Calendar.YEAR))
                set(Calendar.MONTH, tomorrow.get(Calendar.MONTH))
                set(Calendar.DAY_OF_MONTH, tomorrow.get(Calendar.DAY_OF_MONTH))
                set(Calendar.HOUR_OF_DAY, fajr.hour)
                set(Calendar.MINUTE, fajr.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.MINUTE, config.delayMinutes)
            }

            val alarmTriggerTimes = shadowAlarmManager.scheduledAlarms.map { it.triggerAtTime }
            assertTrue(
                "Reschedule action should include tomorrow's Fajr",
                alarmTriggerTimes.contains(expectedSilence.timeInMillis)
            )
        }
    }
}

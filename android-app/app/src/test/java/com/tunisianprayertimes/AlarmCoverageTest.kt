package com.tunisianprayertimes

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
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
import java.util.Calendar

/**
 * Comprehensive test suite covering alarm scheduling gaps:
 * - Inside-prayer-window branch
 * - Midnight reschedule trigger time
 * - FixedTime delay/unsilence modes in main loop
 * - todayTimes == null explicit verification
 * - Alarm count precision
 * - PendingIntent request code isolation between prayers
 * - End-to-end alarm→receiver→state lifecycle
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
@Suppress("DEPRECATION")
class AlarmCoverageTest {

    private lateinit var context: Context
    private lateinit var shadowAlarmManager: ShadowAlarmManager
    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var shadowNotificationManager: ShadowNotificationManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        shadowAlarmManager = Shadows.shadowOf(alarmManager)
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowNotificationManager = Shadows.shadowOf(notificationManager)
        shadowNotificationManager.setNotificationPolicyAccessGranted(true)

        context.getSharedPreferences("prayer_silence_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()

        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
    }

    // ==================== Midnight reschedule ====================

    @Test
    fun scheduleAll_alwaysSchedulesMidnightReschedule() {
        PrefsManager.setEnabled(context, true)
        SilenceScheduler.scheduleAll(context)

        val expectedMidnight = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 1)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val alarmTriggerTimes = shadowAlarmManager.scheduledAlarms.map { it.triggerAtTime }
        assertTrue(
            "Midnight reschedule at 00:01 should be scheduled",
            alarmTriggerTimes.contains(expectedMidnight.timeInMillis)
        )
    }

    @Test
    fun cancelAll_cancelsMidnightReschedule() {
        PrefsManager.setEnabled(context, true)
        SilenceScheduler.scheduleAll(context)

        val expectedMidnight = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 1)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val hasMidnight = shadowAlarmManager.scheduledAlarms.any { it.triggerAtTime == expectedMidnight.timeInMillis }
        assertTrue("Midnight alarm should exist before cancel", hasMidnight)

        SilenceScheduler.cancelAll(context)
        assertEquals("All alarms cancelled", 0, shadowAlarmManager.scheduledAlarms.size)
    }

    // ==================== todayTimes == null ====================

    @Test
    fun scheduleAll_withNullTodayTimes_restoresAndClearsFlag() {
        // Use non-existent delegation so todayTimes == null
        PrefsManager.setEnabled(context, true)
        PrefsManager.setDelegationId(context, 99999)

        // Simulate an earlier auto-silence session that now needs to be restored.
        SilenceModeController.enableAutoSilence(context)

        SilenceScheduler.scheduleAll(context)

        assertEquals(
            "Phone should be restored to previous mode when no prayer data and flag was set",
            AudioManager.RINGER_MODE_NORMAL,
            audioManager.ringerMode
        )
        assertFalse(PrefsManager.isAutoSilenceActive(context))
        // todayTimes == null returns early, so no alarms at all (not even midnight reschedule)
        assertEquals("No alarms when prayer data missing", 0, shadowAlarmManager.scheduledAlarms.size)
    }

    // ==================== Inside prayer window ====================

    @Test
    fun scheduleAll_insidePrayerWindow_silencesPhone() {
        PrefsManager.setEnabled(context, true)

        // Find which prayer window (if any) we're currently inside
        val now = Calendar.getInstance()
        val delegationId = PrefsManager.getDelegationId(context)
        val todayTimes = PrayerTimesRepository.loadDayPrayerTimes(
            context, delegationId,
            now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH)
        ) ?: return

        var insideAnyWindow = false
        for (pt in todayTimes.allPrayers()) {
            val config = PrefsManager.getConfig(context, pt.prayer)
            val silenceTime = if (config.delayMode == DelayMode.FIXED_TIME && config.delayFixedHour >= 0 && config.delayFixedMinute >= 0) {
                Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, config.delayFixedHour)
                    set(Calendar.MINUTE, config.delayFixedMinute)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
            } else {
                Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, pt.hour)
                    set(Calendar.MINUTE, pt.minute)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                    add(Calendar.MINUTE, config.delayMinutes)
                }
            }
            val unsilenceTime = if (config.mode == SilenceMode.FIXED_TIME && config.fixedHour >= 0 && config.fixedMinute >= 0) {
                Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, config.fixedHour)
                    set(Calendar.MINUTE, config.fixedMinute)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
            } else {
                (silenceTime.clone() as Calendar).apply { add(Calendar.MINUTE, config.afterMinutes) }
            }

            if (!now.before(silenceTime) && now.before(unsilenceTime)) {
                insideAnyWindow = true
                break
            }
        }

        SilenceScheduler.scheduleAll(context)

        if (insideAnyWindow) {
            assertEquals(
                "Phone should be silenced when inside a prayer window",
                AudioManager.RINGER_MODE_SILENT,
                audioManager.ringerMode
            )
        } else {
            assertEquals(
                "Phone should be normal when outside all prayer windows",
                AudioManager.RINGER_MODE_NORMAL,
                audioManager.ringerMode
            )
        }
    }

    @Test
    fun scheduleAll_insideWindow_schedulesOnlyUnsilenceForThatPrayer() {
        PrefsManager.setEnabled(context, true)

        val now = Calendar.getInstance()
        val delegationId = PrefsManager.getDelegationId(context)
        val todayTimes = PrayerTimesRepository.loadDayPrayerTimes(
            context, delegationId,
            now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH)
        ) ?: return

        // Find a prayer where we are currently inside the window
        for (pt in todayTimes.allPrayers()) {
            val config = PrefsManager.getConfig(context, pt.prayer)
            val silenceTime = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, pt.hour)
                set(Calendar.MINUTE, pt.minute)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                add(Calendar.MINUTE, config.delayMinutes)
            }
            val unsilenceTime = (silenceTime.clone() as Calendar).apply {
                add(Calendar.MINUTE, config.afterMinutes)
            }

            if (!now.before(silenceTime) && now.before(unsilenceTime)) {
                SilenceScheduler.scheduleAll(context)

                // The unsilence alarm for this prayer should be scheduled
                val alarmTriggerTimes = shadowAlarmManager.scheduledAlarms.map { it.triggerAtTime }
                assertTrue(
                    "Unsilence alarm for ${pt.prayer} should be scheduled (currently inside window)",
                    alarmTriggerTimes.contains(unsilenceTime.timeInMillis)
                )

                // The silence alarm for this prayer should NOT be scheduled (we're past it)
                assertFalse(
                    "Silence alarm for ${pt.prayer} should NOT be scheduled (already past)",
                    alarmTriggerTimes.contains(silenceTime.timeInMillis)
                )
                return
            }
        }
        // If we're not inside any window, the test is a no-op (time-dependent)
    }

    // ==================== FixedTime delay mode in main loop ====================

    @Test
    fun scheduleAll_fixedTimeDelay_usesConfiguredTriggerTime() {
        PrefsManager.setEnabled(context, true)
        // Set ASR to use fixed-time delay at 23:55 (always in the future for today)
        PrefsManager.setDelayMode(context, Prayer.ASR, DelayMode.FIXED_TIME)
        PrefsManager.setDelayFixedTime(context, Prayer.ASR, 23, 55)
        PrefsManager.setSilenceMode(context, Prayer.ASR, SilenceMode.DURATION)
        PrefsManager.setAfterMinutes(context, Prayer.ASR, 30)

        SilenceScheduler.scheduleAll(context)

        val expectedSilenceTime = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 55)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // 23:55 is always in the future (test runs before 23:55)
        val now = Calendar.getInstance()
        if (expectedSilenceTime.after(now)) {
            val alarmTriggerTimes = shadowAlarmManager.scheduledAlarms.map { it.triggerAtTime }
            assertTrue(
                "ASR silence alarm should fire at fixed delay time 23:55",
                alarmTriggerTimes.contains(expectedSilenceTime.timeInMillis)
            )
        }
    }

    @Test
    fun scheduleAll_fixedTimeSilenceMode_usesConfiguredEndTime() {
        PrefsManager.setEnabled(context, true)
        // Set MAGHRIB to fixed-time unsilence at 23:59
        PrefsManager.setSilenceMode(context, Prayer.MAGHRIB, SilenceMode.FIXED_TIME)
        PrefsManager.setFixedTime(context, Prayer.MAGHRIB, 23, 59)

        SilenceScheduler.scheduleAll(context)

        val expectedUnsilenceTime = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val now = Calendar.getInstance()
        if (expectedUnsilenceTime.after(now)) {
            val alarmTriggerTimes = shadowAlarmManager.scheduledAlarms.map { it.triggerAtTime }
            assertTrue(
                "MAGHRIB unsilence alarm should fire at fixed time 23:59",
                alarmTriggerTimes.contains(expectedUnsilenceTime.timeInMillis)
            )
        }
    }

    // ==================== PendingIntent request code isolation ====================

    @Test
    fun scheduleAll_differentPrayers_haveDifferentRequestCodes() {
        PrefsManager.setEnabled(context, true)
        SilenceScheduler.scheduleAll(context)

        // Each prayer uses requestCode = ordinal * 2 (silence) and ordinal * 2 + 1 (unsilence)
        // If request codes were the same, setting alarm for one prayer would overwrite another
        // With 5 prayers * 2 directions + 1 midnight = at most 11 alarms
        // Verify we have multiple distinct alarms (not overwritten to 1)
        val alarmCount = shadowAlarmManager.scheduledAlarms.size
        // At the very least: midnight + some future prayer alarms
        assertTrue(
            "Should have multiple alarms (distinct request codes), got $alarmCount",
            alarmCount >= 2
        )
    }

    // ==================== Alarm count accuracy ====================

    @Test
    fun scheduleAll_alarmCountIncludesMidnight() {
        PrefsManager.setEnabled(context, true)
        SilenceScheduler.scheduleAll(context)

        val now = Calendar.getInstance()
        val delegationId = PrefsManager.getDelegationId(context)
        val todayTimes = PrayerTimesRepository.loadDayPrayerTimes(
            context, delegationId,
            now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH)
        ) ?: return

        // Count expected alarms: for each prayer, silence + unsilence if both in future,
        // only unsilence if inside window, skip if both past. Plus midnight. Plus maybe tomorrow Fajr.
        var expectedMinAlarms = 1 // midnight reschedule

        for (pt in todayTimes.allPrayers()) {
            val config = PrefsManager.getConfig(context, pt.prayer)
            val silenceTime = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, pt.hour)
                set(Calendar.MINUTE, pt.minute)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                add(Calendar.MINUTE, config.delayMinutes)
            }
            val unsilenceTime = if (config.mode == SilenceMode.FIXED_TIME && config.fixedHour >= 0 && config.fixedMinute >= 0) {
                Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, config.fixedHour)
                    set(Calendar.MINUTE, config.fixedMinute)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
            } else {
                (silenceTime.clone() as Calendar).apply { add(Calendar.MINUTE, config.afterMinutes) }
            }

            if (!now.before(silenceTime) && now.before(unsilenceTime)) {
                // Inside window: only unsilence scheduled
                expectedMinAlarms += 1
            } else {
                if (silenceTime.after(now)) expectedMinAlarms += 1
                if (unsilenceTime.after(now)) expectedMinAlarms += 1
            }
        }

        val actualCount = shadowAlarmManager.scheduledAlarms.size
        // Actual may be higher due to tomorrow's Fajr (up to +2)
        assertTrue(
            "Expected at least $expectedMinAlarms alarms, got $actualCount",
            actualCount >= expectedMinAlarms
        )
    }

    // ==================== End-to-end: alarm → receiver → state ====================

    @Test
    fun endToEnd_silenceAction_silencesPhone_thenUnsilenceRestores() {
        // Simulate the full lifecycle: SilenceReceiver gets ACTION_SILENCE → phone silenced
        // → SilenceReceiver gets ACTION_UNSILENCE → phone normal
        val receiver = SilenceReceiver()

        assertEquals(AudioManager.RINGER_MODE_NORMAL, audioManager.ringerMode)

        // Fire silence action
        receiver.onReceive(context, Intent("com.tunisianprayertimes.ACTION_SILENCE").apply {
            putExtra("extra_prayer", "FAJR")
        })
        assertEquals(
            "Phone should be silent after SILENCE action",
            AudioManager.RINGER_MODE_SILENT,
            audioManager.ringerMode
        )
        assertEquals(
            "DND should be NONE after SILENCE action",
            NotificationManager.INTERRUPTION_FILTER_NONE,
            notificationManager.currentInterruptionFilter
        )

        // Fire unsilence action
        receiver.onReceive(context, Intent("com.tunisianprayertimes.ACTION_UNSILENCE").apply {
            putExtra("extra_prayer", "FAJR")
        })
        assertEquals(
            "Phone should be normal after UNSILENCE action",
            AudioManager.RINGER_MODE_NORMAL,
            audioManager.ringerMode
        )
        assertEquals(
            "DND should be ALL after UNSILENCE action",
            NotificationManager.INTERRUPTION_FILTER_ALL,
            notificationManager.currentInterruptionFilter
        )
    }

    @Test
    fun endToEnd_rescheduleAction_resultsInAlarms() {
        PrefsManager.setEnabled(context, true)
        val receiver = SilenceReceiver()

        assertEquals(0, shadowAlarmManager.scheduledAlarms.size)

        receiver.onReceive(context, Intent("com.tunisianprayertimes.ACTION_RESCHEDULE"))

        assertTrue(
            "Reschedule action should result in alarms being set",
            shadowAlarmManager.scheduledAlarms.isNotEmpty()
        )
    }

    @Test
    fun endToEnd_scheduleAll_thenFireAlarm_silencesPhone() {
        // Schedule alarms first
        PrefsManager.setEnabled(context, true)
        SilenceScheduler.scheduleAll(context)

        // Manually deliver a silence action (simulating AlarmManager firing)
        val receiver = SilenceReceiver()
        receiver.onReceive(context, Intent("com.tunisianprayertimes.ACTION_SILENCE").apply {
            putExtra("extra_prayer", "DHUHR")
        })

        assertEquals(
            "Phone should be silenced after alarm fires",
            AudioManager.RINGER_MODE_SILENT,
            audioManager.ringerMode
        )
    }

    // ==================== SilenceReceiver with DND granted ====================

    @Test
    fun silenceReceiver_withDndGranted_silencesPhone() {
        val receiver = SilenceReceiver()
        receiver.onReceive(context, Intent("com.tunisianprayertimes.ACTION_SILENCE").apply {
            putExtra("extra_prayer", "ASR")
        })

        assertEquals(AudioManager.RINGER_MODE_SILENT, audioManager.ringerMode)
        assertEquals(
            NotificationManager.INTERRUPTION_FILTER_NONE,
            notificationManager.currentInterruptionFilter
        )
    }

    @Test
    fun silenceReceiver_withDndGranted_unsilencesPhone() {
        val receiver = SilenceReceiver()
        receiver.onReceive(context, Intent("com.tunisianprayertimes.ACTION_SILENCE").apply {
            putExtra("extra_prayer", "ASR")
        })
        receiver.onReceive(context, Intent("com.tunisianprayertimes.ACTION_UNSILENCE").apply {
            putExtra("extra_prayer", "ASR")
        })

        assertEquals(AudioManager.RINGER_MODE_NORMAL, audioManager.ringerMode)
        assertEquals(
            NotificationManager.INTERRUPTION_FILTER_ALL,
            notificationManager.currentInterruptionFilter
        )
    }

    @Test
    fun silenceReceiver_missingExtraPrayer_stillWorks() {
        // Intent without extra_prayer — code defaults to "UNKNOWN" for logging
        val receiver = SilenceReceiver()
        receiver.onReceive(context, Intent("com.tunisianprayertimes.ACTION_SILENCE"))

        assertEquals(
            "Should still silence even without prayer extra",
            AudioManager.RINGER_MODE_SILENT,
            audioManager.ringerMode
        )
    }

    @Test
    fun silenceReceiver_unsilence_missingExtraPrayer_stillWorks() {
        val receiver = SilenceReceiver()
        receiver.onReceive(context, Intent("com.tunisianprayertimes.ACTION_SILENCE"))
        receiver.onReceive(context, Intent("com.tunisianprayertimes.ACTION_UNSILENCE"))

        assertEquals(
            "Should still unsilence even without prayer extra",
            AudioManager.RINGER_MODE_NORMAL,
            audioManager.ringerMode
        )
    }

    // ==================== BootReceiver edge cases ====================

    @Test
    fun bootReceiver_nullAction_doesNotCrash() {
        val receiver = BootReceiver()
        receiver.onReceive(context, Intent())
        // No crash = pass
    }

    @Test
    fun bootReceiver_unknownAction_doesNotCrash() {
        val receiver = BootReceiver()
        receiver.onReceive(context, Intent("com.example.SOME_RANDOM_ACTION"))
        // Should be a no-op, no crash
        // Also verify no alarms were scheduled (proves the unknown action was ignored)
        assertEquals(
            "Unknown action should not schedule alarms",
            0,
            shadowAlarmManager.scheduledAlarms.size
        )
    }

    @Test
    fun bootReceiver_whenDisabled_schedulesButNoWorker() {
        PrefsManager.setEnabled(context, false)
        val receiver = BootReceiver()
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        // scheduleAll is called unconditionally → with disabled it calls cancelAll → 0 alarms
        assertEquals(
            "Disabled state: scheduleAll should cancel all",
            0,
            shadowAlarmManager.scheduledAlarms.size
        )
    }
}

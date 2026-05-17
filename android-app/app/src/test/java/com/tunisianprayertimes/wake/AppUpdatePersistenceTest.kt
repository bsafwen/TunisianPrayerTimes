package com.tunisianprayertimes.wake

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.DelayMode
import com.tunisianprayertimes.ManualSilenceMode
import com.tunisianprayertimes.MathDifficulty
import com.tunisianprayertimes.OffsetDirection
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.RingtonePreset
import com.tunisianprayertimes.SilenceMode
import com.tunisianprayertimes.WakeMainAlarmMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class AppUpdatePersistenceTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("prayer_silence_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        runBlocking {
          context.prayerWakeDataStore.edit { preferences ->
            preferences.remove(prayerWakeStoreKey)
          }
        }
    }

    @Test
    fun existingSettingsFromPreviousInstall_areLoadedAfterAppUpdate() = runBlocking {
        val legacyWakeStoreJson = legacyWakeStoreJson()

        seedExistingSilenceSettings()
        assertEquals(1, decodePrayerWakeStore(legacyWakeStoreJson).alarms.size)
        seedLegacyWakeAlarmStore(legacyWakeStoreJson)

        val repositoryAfterUpdate = PrayerWakeRepository(context)

        assertEquals(386, PrefsManager.getDelegationId(context))
        assertFalse(PrefsManager.isEnabled(context))
        assertFalse(PrefsManager.isCallEndVibrationEnabled(context))
        assertFalse(PrefsManager.isAutoLocationUpdateEnabled(context))
        assertEquals(ManualSilenceMode.DURATION, PrefsManager.getManualSilenceMode(context))
        assertEquals(95, PrefsManager.getManualSilenceDurationMinutes(context))

        val fajrConfig = PrefsManager.getConfig(context, Prayer.FAJR)
        assertEquals(SilenceMode.FIXED_TIME, fajrConfig.mode)
        assertEquals(42, fajrConfig.afterMinutes)
        assertEquals(6, fajrConfig.fixedHour)
        assertEquals(20, fajrConfig.fixedMinute)
        assertEquals(DelayMode.FIXED_TIME, fajrConfig.delayMode)
        assertEquals(7, fajrConfig.delayMinutes)
        assertEquals(5, fajrConfig.delayFixedHour)
        assertEquals(12, fajrConfig.delayFixedMinute)

        val wakeStore = repositoryAfterUpdate.getCurrentStore()
        assertEquals(1, wakeStore.alarms.size)

        val alarm = wakeStore.alarms.single()
        assertEquals("fajr_1", alarm.id)
        assertEquals(Prayer.FAJR, alarm.prayer)
        assertTrue(alarm.enabled)
        assertEquals(WakeMainAlarmMode.PRAYER_RELATIVE, alarm.mainAlarm.mode)
        assertEquals(-20, alarm.mainAlarm.prayerOffset.minutes)
        assertEquals(RingtonePreset.ADHAN_MADINAH_MARWAN_QASSAS, alarm.playback.ringtone)
        assertTrue(alarm.playback.wakeUpCheckEnabled)
        assertEquals(MathDifficulty.HARD, alarm.playback.mathDifficulty)
        assertTrue(alarm.playback.progressiveVolume)

        val subAlarm = alarm.subAlarms.single()
        assertEquals("legacy_sub", subAlarm.id)
        assertEquals(10, subAlarm.minutesOffset)
        assertEquals(OffsetDirection.BEFORE, subAlarm.direction)
        assertEquals(RingtonePreset.CLASSIC_BEEP, subAlarm.playback.ringtone)
    }

    private fun seedExistingSilenceSettings() {
        context.getSharedPreferences("prayer_silence_prefs", Context.MODE_PRIVATE)
            .edit()
            .putInt("delegation_id", 386)
            .putBoolean("silence_enabled", false)
            .putBoolean("call_end_vibration_enabled", false)
            .putBoolean("auto_location_update", false)
            .putString("mode_FAJR", SilenceMode.FIXED_TIME.name)
            .putInt("after_FAJR", 42)
            .putInt("fixed_hour_FAJR", 6)
            .putInt("fixed_minute_FAJR", 20)
            .putString("delay_mode_FAJR", DelayMode.FIXED_TIME.name)
            .putInt("delay_FAJR", 7)
            .putInt("delay_fixed_hour_FAJR", 5)
            .putInt("delay_fixed_minute_FAJR", 12)
            .putBoolean("manual_silence_uses_duration", true)
            .putInt("manual_silence_duration_minutes", 95)
            .commit()
    }

    private suspend fun seedLegacyWakeAlarmStore(legacyWakeStoreJson: String) {
      context.prayerWakeDataStore.edit { preferences ->
        preferences[prayerWakeStoreKey] = legacyWakeStoreJson
      }
    }

    private fun legacyWakeStoreJson(): String =
      """
            {
              "configs": {
                "FAJR": {
                  "prayer": "FAJR",
                  "enabled": true,
                  "mainAlarm": {
                    "mode": "PRAYER_RELATIVE",
                    "prayerOffset": { "minutes": -20 }
                  },
                  "playback": {
                    "wakeUpCheckEnabled": true,
                    "mathDifficulty": "HARD",
                    "progressiveVolume": true,
                    "ringtone": "ADHAN_MADINAH_MARWAN_QASSAS"
                  },
                  "subAlarms": [
                    {
                      "id": "legacy_sub",
                      "minutesOffset": 10,
                      "direction": "BEFORE",
                      "playback": {
                        "ringtone": "CLASSIC_BEEP"
                      }
                    }
                  ]
                }
              }
            }
        """.trimIndent()
}
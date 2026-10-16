package com.tunisianprayertimes

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two schedule runs a moment apart, the first opening a silence window (the guard service starts) and the second
 * finding none (it stops): a place change racing the refresh of `TIMEZONE_CHANGED` does this. Android crashes the app
 * when a service started with `startForegroundService` is stopped before it calls `startForeground`, so a stop must
 * never overtake a start. Run by scripts/device_checks/run_device_scenarios.py on its throwaway emulator, as an
 * instrumentation of its own, since a crash ends the process.
 */
@RunWith(AndroidJUnit4::class)
class GuardServiceRaceInstrumentedTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aStopRightAfterAStartDoesNotCrashTheApp() {
        assumeTrue("run by the script with -e deviceScenarios true", InstrumentationRegistry.getArguments().getString("deviceScenarios") == "true")
        assumeTrue("Only on an emulator", Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        val prefs = context.getSharedPreferences("prayer_silence_prefs", Context.MODE_PRIVATE)
        val saved = HashMap(prefs.all)
        try {
            PrefsManager.markFirstLaunchDone(context)
            PrefsManager.setEnabled(context, true)
            // With a screen open, so Android allows the foreground start, as it does from the receivers' exemptions.
            ActivityScenario.launch(MainActivity::class.java).use {
                repeat(30) {
                    PrefsManager.markAutoSilenceActive(context, AudioManager.RINGER_MODE_NORMAL, NotificationManager.INTERRUPTION_FILTER_ALL)
                    SilenceGuardService.start(context)
                    PrefsManager.clearAutoSilenceState(context)
                    SilenceGuardService.stopIfNotNeeded(context)
                }
                // The system's crash, if any, arrives within moments.
                Thread.sleep(5_000)
            }
        } finally {
            val edit = prefs.edit().clear()
            for ((key, value) in saved) {
                when (value) {
                    is Boolean -> edit.putBoolean(key, value)
                    is Int -> edit.putInt(key, value)
                    is Long -> edit.putLong(key, value)
                    is Float -> edit.putFloat(key, value)
                    is String -> edit.putString(key, value)
                    is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            edit.commit()
            SilenceGuardService.stopIfNotNeeded(context)
        }
    }
}

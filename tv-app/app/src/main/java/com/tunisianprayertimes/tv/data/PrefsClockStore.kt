package com.tunisianprayertimes.tv.data

import android.content.SharedPreferences
import androidx.core.content.edit
import com.tunisianprayertimes.time.ClockStore

/**
 * The clock guard's memory in the TV's preferences. The clock is read every second, so the last
 * good time is written at most once a minute (and at once if it moves backwards, after a confirm).
 */
class PrefsClockStore(private val prefs: SharedPreferences) : ClockStore {

    private var lastGood = prefs.getLong(KEY_LAST_GOOD, 0L)
    private var persistedLastGood = lastGood

    override var lastKnownGoodMillis: Long
        get() = lastGood
        set(value) {
            lastGood = value
            if (value < persistedLastGood || value - persistedLastGood >= PERSIST_EVERY_MILLIS) {
                persistedLastGood = value
                prefs.edit { putLong(KEY_LAST_GOOD, value) }
            }
        }

    override var correctionMillis: Long
        get() = prefs.getLong(KEY_CORRECTION, 0L)
        set(value) = prefs.edit { putLong(KEY_CORRECTION, value) }

    override var correctionElapsedMillis: Long
        get() = prefs.getLong(KEY_CORRECTION_ELAPSED, 0L)
        set(value) = prefs.edit { putLong(KEY_CORRECTION_ELAPSED, value) }

    private companion object {
        const val KEY_LAST_GOOD = "clock_last_known_good_millis"
        const val KEY_CORRECTION = "clock_correction_millis"
        const val KEY_CORRECTION_ELAPSED = "clock_correction_elapsed_millis"
        const val PERSIST_EVERY_MILLIS = 60_000L
    }
}

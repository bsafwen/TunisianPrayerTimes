package com.tunisianprayertimes.tv.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.tunisianprayertimes.time.ClockGuard
import com.tunisianprayertimes.time.ClockStore
import com.tunisianprayertimes.time.DeviceMark
import com.tunisianprayertimes.time.TunisTime
import java.time.Instant
import java.time.LocalDate

/**
 * Today in Tunisia on the screen's clock, for other threads (the official dates' poller): the
 * [device] clock with the guard's stored correction, since the guard itself is read on the main
 * thread only. Null when that time cannot be right.
 */
fun correctedToday(store: ClockStore, device: Instant): LocalDate? {
    val instant = device.plusMillis(store.correctionMillis)
    if (instant.isBefore(ClockGuard.EARLIEST) || instant.isAfter(ClockGuard.LATEST)) return null
    return instant.atZone(TunisTime.ZONE).toLocalDate()
}

/**
 * The clock guard's memory, in its own small file: kept apart from the mosque's settings, which a
 * write every minute would rewrite whole, and from their reset (a TV moved to another mosque keeps
 * its clock). The clock is read every second, so the last good time and the device clock's mark are
 * written together at most once a minute (and at once when one moves back, after a confirm or a set,
 * or the mark is from another boot).
 */
class PrefsClockStore(private val prefs: SharedPreferences) : ClockStore {

    constructor(context: Context) : this(context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    private var lastGood = prefs.getLong(KEY_LAST_GOOD, 0L)
    private var mark = prefs.takeIf { it.contains(KEY_MARK) }?.let {
        DeviceMark(it.getLong(KEY_MARK, 0L), it.getLong(KEY_MARK_ELAPSED, 0L), it.getString(KEY_MARK_BOOT, null))
    }
    private var persistedLastGood = lastGood
    private var persistedMark = mark

    override var lastKnownGoodMillis: Long
        get() = lastGood
        set(value) {
            lastGood = value
            persistMarks()
        }

    override var deviceMark: DeviceMark?
        get() = mark
        set(value) {
            mark = value
            persistMarks()
        }

    override var foreignZoneSeen: Boolean
        get() = prefs.getBoolean(KEY_FOREIGN_ZONE, false)
        set(value) = prefs.edit { putBoolean(KEY_FOREIGN_ZONE, value) }

    override var correctionMillis: Long
        get() = prefs.getLong(KEY_CORRECTION, 0L)
        set(value) = prefs.edit { putLong(KEY_CORRECTION, value) }

    override var confirmedBy: String?
        get() = prefs.getString(KEY_CONFIRMED_BY, null)
        set(value) = prefs.edit { putString(KEY_CONFIRMED_BY, value) }

    /** Both marks in one write, once either moved back or a minute on, or the device's is from another boot. */
    private fun persistMarks() {
        if (!due(lastGood, persistedLastGood) && !markDue()) return
        persistedLastGood = lastGood
        persistedMark = mark
        prefs.edit {
            putLong(KEY_LAST_GOOD, lastGood)
            mark?.let {
                putLong(KEY_MARK, it.millis)
                putLong(KEY_MARK_ELAPSED, it.elapsed)
                putString(KEY_MARK_BOOT, it.boot)
            }
        }
    }

    private fun markDue(): Boolean {
        val new = mark ?: return false
        val old = persistedMark ?: return true
        return new.boot != old.boot || due(new.millis, old.millis)
    }

    private fun due(value: Long, persisted: Long) = value < persisted || value - persisted >= PERSIST_EVERY_MILLIS

    companion object {
        const val PREFS_NAME = "clock"
        private const val KEY_LAST_GOOD = "last_known_good_millis"
        private const val KEY_MARK = "device_mark_millis"
        private const val KEY_MARK_ELAPSED = "device_mark_elapsed"
        private const val KEY_MARK_BOOT = "device_mark_boot"
        private const val KEY_CORRECTION = "correction_millis"
        private const val KEY_CONFIRMED_BY = "confirmed_by"
        private const val KEY_FOREIGN_ZONE = "foreign_zone_seen"
        private const val PERSIST_EVERY_MILLIS = 60_000L
    }
}

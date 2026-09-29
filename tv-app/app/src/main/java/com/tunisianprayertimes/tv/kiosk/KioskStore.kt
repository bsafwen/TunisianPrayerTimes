package com.tunisianprayertimes.tv.kiosk

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import java.io.File

/**
 * The kiosk's own small preference file, kept apart from the mosque settings: it is written on
 * every start and stop, and with commit() where the process is about to die.
 */
class KioskStore(
    private val prefs: SharedPreferences,
    val eventLog: EventLog,
    /** The device's boot count, so times written before a reboot are recognised (-1 when unknown). */
    private val bootCount: () -> Int = { -1 },
) {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        EventLog(File(context.applicationContext.filesDir, "kiosk-events.log")),
        { kotlin.runCatching { Settings.Global.getInt(context.applicationContext.contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(-1) },
    )

    var state: KioskState
        get() = if (prefs.getInt(KEY_BOOT, -1) != bootCount()) KioskState() else KioskState(
            resumedAt = long(KEY_RESUMED),
            stoppedAt = long(KEY_STOPPED),
            refrontedAt = long(KEY_REFRONTED),
            adminAwayUntil = long(KEY_ADMIN_AWAY, maxAhead = MAX_AWAY_MILLIS),
        )
        set(value) {
            prefs.edit()
                .putOrRemove(KEY_RESUMED, value.resumedAt)
                .putOrRemove(KEY_STOPPED, value.stoppedAt)
                .putOrRemove(KEY_REFRONTED, value.refrontedAt)
                .putOrRemove(KEY_ADMIN_AWAY, value.adminAwayUntil)
                .putInt(KEY_BOOT, bootCount())
                .apply()
        }

    fun update(transform: (KioskState) -> KioskState) = synchronized(this) { state = transform(state) }

    /** Written synchronously: the crash handler calls this just before the process is killed. */
    var crashes: CrashLoopGuard.State
        get() = CrashLoopGuard.State.decode(prefs.getString(KEY_CRASHES, null))
        set(value) {
            prefs.edit().putString(KEY_CRASHES, value.encode()).commit()
        }


    /** Values from before a reboot lie further ahead of the clock than any real one: ignore them. */
    private fun long(key: String, maxAhead: Long = 0L): Long? {
        if (!prefs.contains(key)) return null
        val value = prefs.getLong(key, 0L)
        return value.takeIf { it <= SystemClock.elapsedRealtime() + maxAhead }
    }

    private fun SharedPreferences.Editor.putOrRemove(key: String, value: Long?) =
        if (value == null) remove(key) else putLong(key, value)

    companion object {
        const val PREFS_NAME = "kiosk"
        const val MAX_AWAY_MILLIS = 60 * 60_000L
        private const val KEY_RESUMED = "resumed_at"
        private const val KEY_STOPPED = "stopped_at"
        private const val KEY_REFRONTED = "refronted_at"
        private const val KEY_ADMIN_AWAY = "admin_away_until"
        private const val KEY_CRASHES = "crash_guard"
        private const val KEY_BOOT = "boot_count"
    }
}

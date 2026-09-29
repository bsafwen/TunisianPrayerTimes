package com.tunisianprayertimes.tv.kiosk

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import java.util.Locale

/**
 * How fast the display came up after the last boot: when the app process, the quick-start service,
 * the boot broadcast and the display itself first ran, in seconds since boot. Written once per boot
 * into the kiosk log, so an admin can read it on the box (a Fire TV has no other way to tell).
 */
object BootTiming {

    const val APP = "app"
    const val SERVICE = "service"
    const val RECEIVER = "receiver"
    const val SCREEN = "screen"

    /** Past this, a start is not part of the boot any more. */
    const val WINDOW_MILLIS = 10 * 60_000L

    private const val PREFS = "boot_timing"
    private const val KEY_BOOT = "boot_count"
    private const val KEY_LOGGED = "logged"

    /** Records the first time [what] ran in this boot; returns true the first time only. */
    fun mark(context: Context, what: String, now: Long = SystemClock.elapsedRealtime()): Boolean {
        if (now > WINDOW_MILLIS) return false
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val boot = bootCount(context)
        synchronized(this) {
            // apply(): the in-memory values change at once, and nothing slows down the start being measured.
            if (prefs.getInt(KEY_BOOT, Int.MIN_VALUE) != boot) prefs.edit().clear().putInt(KEY_BOOT, boot).apply()
            if (prefs.contains(what)) return false
            prefs.edit().putLong(what, now).apply()
        }
        return true
    }

    /** Once per boot, when the display first shows: the line for the kiosk log, or null. */
    fun takeSummary(context: Context): String? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        synchronized(this) {
            if (prefs.getInt(KEY_BOOT, Int.MIN_VALUE) != bootCount(context) || prefs.getBoolean(KEY_LOGGED, false)) return null
            val times = times(prefs)
            if (times[SCREEN] == null) return null
            prefs.edit().putBoolean(KEY_LOGGED, true).apply()
            return summary(times) + " boot=${bootCount(context)}"
        }
    }

    /** When the boot notice came after the display was up (quick start): the full line again, now with its time. */
    fun lateSummary(context: Context): String? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        synchronized(this) {
            if (prefs.getInt(KEY_BOOT, Int.MIN_VALUE) != bootCount(context) || !prefs.getBoolean(KEY_LOGGED, false)) return null
            val times = times(prefs)
            if (times[SCREEN] == null) return null
            return summary(times) + " boot=${bootCount(context)}"
        }
    }

    private fun times(prefs: android.content.SharedPreferences) =
        listOf(APP, SERVICE, RECEIVER, SCREEN).associateWith { key -> prefs.getLong(key, -1L).takeIf { it >= 0 } }

    /** "screen=9.1s service=8.4s receiver=19.7s app=8.2s": the display first, then what started it. */
    fun summary(times: Map<String, Long?>): String =
        listOf(SCREEN, SERVICE, RECEIVER, APP).mapNotNull { key ->
            times[key]?.let { "$key=" + String.format(Locale.ROOT, "%.1fs", it / 1000.0) }
        }.joinToString(" ")

    /** The seconds from boot to the display in a logged summary, or null. */
    fun screenSeconds(summary: String): Double? =
        Regex("""screen=([0-9.]+)s""").find(summary)?.groupValues?.get(1)?.toDoubleOrNull()

    /** The boot a logged line belongs to ("boot=12"), or null. */
    fun bootOf(detail: String): Int? = Regex("""boot=(-?[0-9]+)""").find(detail)?.groupValues?.get(1)?.toIntOrNull()

    fun bootCount(context: Context): Int =
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(-1)
}

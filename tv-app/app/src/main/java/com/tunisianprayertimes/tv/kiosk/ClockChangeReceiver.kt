package com.tunisianprayertimes.tv.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import com.tunisianprayertimes.time.ClockGuard
import com.tunisianprayertimes.time.ClockSource
import com.tunisianprayertimes.time.TunisTime
import com.tunisianprayertimes.tv.data.PrefsManager
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

/** What the system changed: its clock, or only its zone. */
enum class ClockChange { TIME, ZONE }

/**
 * The system's clock and zone changes, whether the display runs or not. A manifest receiver: both
 * broadcasts still reach one on Android 8+, and only the system can send them.
 *
 * A clock set on purpose (by the admin in the box's settings, or by the network) makes the guard's
 * correction and confirmation meaningless: they are dropped ([ClockGuard.systemClockChanged]) and the
 * display, told through [listener], asks the network again. The app's own setting of the clock on a
 * device-owner box ([KioskController.setSystemTime]) is recognised and keeps the confirmation it
 * followed. A new zone moves no instant: it is only logged, as the guard reads the zone at every tick.
 */
class ClockChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val change = when (intent.action) {
            Intent.ACTION_TIME_CHANGED -> ClockChange.TIME
            Intent.ACTION_TIMEZONE_CHANGED -> ClockChange.ZONE
            else -> return
        }
        val device = Instant.now()
        val log = KioskStore(context).eventLog
        if (change == ClockChange.TIME) {
            val own = OwnClockSet.consume(device.toEpochMilli(), SystemClock.elapsedRealtime())
            if (!own) ClockGuard.systemClockChanged(PrefsManager(context).clockStore, device)
            log.append(KioskEvent.CLOCK_SET, ClockLog.clockSet(device, ZoneId.systemDefault(), autoTime(context), own))
            if (own) return
        } else {
            // The new zone comes with the broadcast: the process's default zone may be reset only after it.
            val zone = intent.getStringExtra(EXTRA_TIME_ZONE)?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
            log.append(KioskEvent.ZONE_SET, ClockLog.zoneSet(zone, device))
        }
        listener?.invoke(change)
    }

    private fun autoTime(context: Context): Boolean? =
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.AUTO_TIME) == 1 }.getOrNull()

    companion object {
        /** Intent.EXTRA_TIMEZONE, public only from Android 11. */
        private const val EXTRA_TIME_ZONE = "time-zone"

        /**
         * The running display, told on the main thread once the store was updated. On [ClockChange.TIME] it
         * calls its own guard's [ClockGuard.systemClockChanged] (which also keeps the guard from taking the
         * jump for another change) and asks the network for the time again. Set while the display runs.
         */
        @Volatile
        var listener: ((ClockChange) -> Unit)? = null
    }
}

/** The details of the clock's kiosk events: one format for the receiver, the network check and the display. */
object ClockLog {
    /** CLOCK_SET: the new device clock read in Tunisia, the device zone, the automatic time, and "own" when the app set it. */
    fun clockSet(device: Instant, zone: ZoneId, autoTime: Boolean?, own: Boolean): String =
        "tunis=${tunis(device)} zone=${zone.id} autoTime=${onOff(autoTime)}" + if (own) " own" else ""

    /** ZONE_SET: the new zone, and whether it reads Tunisia's time at [at]. */
    fun zoneSet(zone: ZoneId, at: Instant): String =
        "zone=${zone.id} tunisTime=${zone.rules.getOffset(at) == TunisTime.ZONE.rules.getOffset(at)}"

    /** CLOCK_CONFIRMED: who confirmed the time, and how far the screen's time moved when it was corrected ("moved=+3600s"). */
    fun confirmed(source: ClockSource, movedMillis: Long = 0): String {
        val seconds = movedMillis / 1000
        return "source=${source.name}" + if (seconds != 0L) " moved=%+ds".format(Locale.ROOT, seconds) else ""
    }

    private fun tunis(instant: Instant) = LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.SECONDS), TunisTime.ZONE).toString()

    private fun onOff(value: Boolean?) = when (value) {
        true -> "on"
        false -> "off"
        null -> "?"
    }
}

/**
 * The system clock the app set itself, on a device-owner box, after the time was confirmed. Its
 * time-set broadcast comes a moment later and must not drop the confirmation it followed; any other
 * clock change, or this one arriving too late, is treated as someone else's.
 */
object OwnClockSet {
    private const val WINDOW_MILLIS = 60_000L
    private const val TOLERANCE_MILLIS = 5_000L

    /** The time set, in epoch millis, and the time since boot when it was set. */
    @Volatile
    private var pending: Pair<Long, Long>? = null

    fun expect(targetMillis: Long, elapsedMillis: Long) {
        pending = targetMillis to elapsedMillis
    }

    fun cancel() {
        pending = null
    }

    /** Whether the clock change just broadcast is the one expected; it is forgotten either way. */
    fun consume(deviceMillis: Long, elapsedMillis: Long): Boolean {
        val expected = pending ?: return false
        pending = null
        return matches(expected.first, expected.second, deviceMillis, elapsedMillis)
    }

    /** The device clock now is the time set plus what elapsed since, within a few seconds, and soon after. */
    internal fun matches(targetMillis: Long, setAtElapsed: Long, deviceMillis: Long, nowElapsed: Long): Boolean {
        val since = nowElapsed - setAtElapsed
        return since in 0..WINDOW_MILLIS && abs(deviceMillis - (targetMillis + since)) <= TOLERANCE_MILLIS
    }
}

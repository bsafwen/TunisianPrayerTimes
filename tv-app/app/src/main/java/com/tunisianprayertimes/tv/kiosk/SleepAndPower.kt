package com.tunisianprayertimes.tv.kiosk

/** One reading of the device clocks: wall time, time since boot, and time since boot while awake. */
data class ClockSample(val wallMillis: Long, val elapsedMillis: Long, val uptimeMillis: Long)

/** The box slept (Energy saver, standby) between two readings, for [sleptMillis]. */
data class SleepGap(val fromWall: Long, val toWall: Long, val sleptMillis: Long)

/**
 * Finds the times the box slept although the app keeps the screen on: Google TV's Energy saver and
 * the TV's standby cannot be overridden by an app, so they are detected after the fact and shown
 * to the admin. Asleep, elapsedRealtime keeps counting and uptime does not.
 */
object SleepGapDetector {

    const val MIN_SLEEP_MILLIS = 60_000L

    fun compare(previous: ClockSample, current: ClockSample): SleepGap? {
        if (current.elapsedMillis < previous.elapsedMillis) return null // a reboot; logged at boot
        val slept = (current.elapsedMillis - current.uptimeMillis) - (previous.elapsedMillis - previous.uptimeMillis)
        return if (slept > MIN_SLEEP_MILLIS) SleepGap(previous.wallMillis, current.wallMillis, slept) else null
    }
}

/** How a power setting looks for a screen that must stay on all day. */
enum class PowerLevel { OK, WARNING, UNKNOWN }

data class PowerStatus(
    /** Android TV 11+ "Energy saver": turns the box off after this long without a remote key press. */
    val attentiveTimeout: PowerLevel,
    val attentiveTimeoutMillis: Long?,
    /** Developer option "Stay awake" while plugged in. */
    val stayAwake: PowerLevel,
)

/** Reads the power settings; each read may be refused on some boxes (null or an exception). */
interface PowerSettingsReader {
    fun attentiveTimeoutMillis(): Long?
    fun stayOnWhilePluggedIn(): Int?
}

object PowerSettingsProbe {

    fun probe(reader: PowerSettingsReader): PowerStatus {
        val attentive = runCatching { reader.attentiveTimeoutMillis() }.getOrNull()
        val stayOn = runCatching { reader.stayOnWhilePluggedIn() }.getOrNull()
        return PowerStatus(
            attentiveTimeout = when {
                attentive == null -> PowerLevel.UNKNOWN
                attentive <= 0 -> PowerLevel.OK // never
                else -> PowerLevel.WARNING
            },
            attentiveTimeoutMillis = attentive,
            stayAwake = when {
                stayOn == null -> PowerLevel.UNKNOWN
                stayOn != 0 -> PowerLevel.OK
                else -> PowerLevel.WARNING
            },
        )
    }
}

/**
 * A slow drift of the whole screen against burn-in on panels showing the same layout all day:
 * eight positions on a small circle, one every [SLOT_MINUTES] minutes, never more than [RADIUS_DP].
 */
object PixelShift {

    const val SLOT_MINUTES = 6L
    const val RADIUS_DP = 6f
    private const val POSITIONS = 8

    /** The offset in dp at [epochMinutes]; the same all through a slot. */
    fun offsetAt(epochMinutes: Long): Pair<Float, Float> {
        val slot = Math.floorMod(epochMinutes / SLOT_MINUTES, POSITIONS.toLong()).toInt()
        val angle = 2 * Math.PI * slot / POSITIONS
        return (RADIUS_DP * Math.cos(angle)).toFloat() to (RADIUS_DP * Math.sin(angle)).toFloat()
    }
}

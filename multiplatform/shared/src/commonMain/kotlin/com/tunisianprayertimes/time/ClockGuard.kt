package com.tunisianprayertimes.time

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Tunisia's civil time: every prayer time is computed and shown in it, whatever the device zone. */
object TunisTime {
    val ZONE: ZoneId = ZoneId.of("Africa/Tunis")
}

/** How far the device clock can be trusted for prayer times. */
enum class ClockTrust {
    /** The instant is known to be right ([ClockSource] says how). */
    TRUSTED,

    /**
     * Plausible, but nothing has confirmed it and the device's zone is not Tunisia's. A clock set
     * by hand to the local time on such a box is off by the zones' difference (an hour on GMT, seven
     * on Shanghai time), and the instant alone cannot tell. The screen shows the time from the
     * instant and the admin is asked.
     */
    UNVERIFIED,

    /** The clock cannot be right (a box without a clock battery after a power cut): prayer times must not be shown. */
    IMPLAUSIBLE,
}

/** How the time was confirmed. */
enum class ClockSource {
    /** The device's own zone keeps Tunisia's time, so its clock, however it was set, means Tunisia's. */
    ZONE,

    /** A network time (an HTTP date, the system's network clock) agreed, or corrected it. */
    NETWORK,

    /** The admin confirmed or set the time on the TV. */
    ADMIN,

    /** The admin's phone set it, from the management page. */
    PHONE,
}

/** The time to use now, in Tunisia, whether it can be trusted and, when it is, why. */
data class ClockReading(val now: LocalDateTime, val trust: ClockTrust, val source: ClockSource? = null)

/** What the guard remembers across reboots. */
interface ClockStore {
    /** The latest time seen while the clock was trusted or unverified, in epoch millis; 0 when unknown. */
    var lastKnownGoodMillis: Long

    /** Added to the device clock: set by the network, the admin or the phone. */
    var correctionMillis: Long

    /** How the time was last confirmed ([ClockSource.name]), or null when it was not. */
    var confirmedBy: String?

    /**
     * The device clock when the time was confirmed or corrected. Found earlier than this later, the
     * device clock was reset (a power cut without a clock battery) and the confirmation no longer holds.
     */
    var confirmedAtDeviceMillis: Long
}

/**
 * Judges the device clock. Many mosque TV boxes have no clock battery and half of them are offline,
 * so after a power cut the clock can restart years in the past and nothing corrects it. Many also
 * leave the factory on a foreign zone, and a clock set by hand there is off by hours. Showing prayer
 * times from such a clock is worse than showing none, so the guard checks the instant, not the zone:
 * against the network when the TV is online, else by asking the admin once, and keeps the answer as
 * a correction until the device clock is changed.
 */
class ClockGuard(
    private val store: ClockStore,
    private val systemNow: () -> Instant,
    private val elapsedMillis: () -> Long,
    private val deviceZone: () -> ZoneId,
) {
    /** The device clock and the time since boot at the last reading, to notice the clock being changed. */
    private var lastDevice: Instant? = null
    private var lastElapsed = 0L

    fun read(): ClockReading {
        val device = systemNow()
        noticeChange(device)
        // Earlier than when it was confirmed: the device clock was reset since, the answer no longer holds.
        if (store.confirmedBy != null && device.toEpochMilli() < store.confirmedAtDeviceMillis - RESET_TOLERANCE.toMillis()) forget()
        val instant = device.plusMillis(store.correctionMillis)
        val confirmed = store.confirmedBy?.let { name -> ClockSource.entries.firstOrNull { it.name == name } }
        val (trust, source) = when {
            implausible(instant) -> ClockTrust.IMPLAUSIBLE to null
            confirmed != null -> ClockTrust.TRUSTED to confirmed
            !zoneDiffers(instant) -> ClockTrust.TRUSTED to ClockSource.ZONE
            else -> ClockTrust.UNVERIFIED to null
        }
        if (trust != ClockTrust.IMPLAUSIBLE && instant.toEpochMilli() > store.lastKnownGoodMillis) {
            store.lastKnownGoodMillis = instant.toEpochMilli()
        }
        return ClockReading(LocalDateTime.ofInstant(instant, TunisTime.ZONE), trust, source)
    }

    /** The device's own zone, for the admin pages. */
    fun deviceZone(): ZoneId = deviceZone.invoke()

    /** Whether the device's zone reads a different time from Tunisia's now. */
    fun zoneDiffers(): Boolean = zoneDiffers(correctedNow())

    /**
     * What the admin is offered when the time is not confirmed, in Tunisia's time: first the time
     * from the device's instant (right when the clock came from the network), then, when the zone
     * differs, the device's own clock read as Tunisia's time (right when someone set it by hand to
     * the time on their watch).
     */
    fun candidates(): List<LocalDateTime> {
        val instant = correctedNow()
        val fromInstant = LocalDateTime.ofInstant(instant, TunisTime.ZONE)
        val fromDeviceClock = LocalDateTime.ofInstant(instant, deviceZone())
        return if (Duration.between(fromInstant, fromDeviceClock).abs() < Duration.ofMinutes(1)) listOf(fromInstant)
        else listOf(fromInstant, fromDeviceClock)
    }

    /**
     * Where an admin setting the time by hand should start: the later of the device time and the last
     * good time (plus the floor), so a box reset to 1970 does not need years of button presses.
     */
    fun suggestedTime(): LocalDateTime {
        val candidates = listOf(correctedNow(), EARLIEST, Instant.ofEpochMilli(store.lastKnownGoodMillis))
        return LocalDateTime.ofInstant(candidates.filter { !it.isAfter(LATEST) }.max(), TunisTime.ZONE)
    }

    /** The admin says the time now in Tunisia is [tunisNow] (one of [candidates], or set by hand). False when it cannot be. */
    fun accept(tunisNow: LocalDateTime, source: ClockSource = ClockSource.ADMIN): Boolean =
        acceptInstant(tunisNow.atZone(TunisTime.ZONE).toInstant(), source)

    /** The admin (or the phone) gives the true instant. False when it cannot be right (before the floor). */
    fun acceptInstant(instant: Instant, source: ClockSource): Boolean {
        if (implausibleAlone(instant)) return false
        val device = systemNow()
        store.correctionMillis = Duration.between(device, instant).toMillis()
        confirm(source, device, instant)
        return true
    }

    /** The admin says the time the screen shows is right, keeping any correction. */
    fun confirm(): Boolean {
        val instant = correctedNow()
        if (implausibleAlone(instant)) return false
        confirm(ClockSource.ADMIN, systemNow(), instant)
        return true
    }

    /**
     * A network time: [instant] is now. Close to the screen's time, it confirms it; further off, it
     * corrects it, whatever the admin said before: the network is the better clock.
     */
    fun networkTime(instant: Instant) {
        if (implausibleAlone(instant)) return
        val device = systemNow()
        val shown = device.plusMillis(store.correctionMillis)
        if (Duration.between(shown, instant).abs() > NETWORK_TOLERANCE) store.correctionMillis = Duration.between(device, instant).toMillis()
        confirm(ClockSource.NETWORK, device, instant)
    }

    /** The device clock was changed on purpose (the system's time-set broadcast): see [systemClockChanged]. */
    fun systemClockChanged() {
        systemClockChanged(store, systemNow())
        lastDevice = null
    }

    private fun confirm(source: ClockSource, device: Instant, instant: Instant) {
        store.confirmedBy = source.name
        store.confirmedAtDeviceMillis = device.toEpochMilli()
        // Even earlier than before: this is the reference now.
        store.lastKnownGoodMillis = instant.toEpochMilli()
    }

    private fun forget() {
        store.correctionMillis = 0
        store.confirmedBy = null
        store.confirmedAtDeviceMillis = 0
    }

    /**
     * A jump of the device clock while the app runs is someone (or the network) setting it: never a
     * power cut. The old correction and confirmation no longer mean anything.
     */
    private fun noticeChange(device: Instant) {
        val elapsed = elapsedMillis()
        val previous = lastDevice
        if (previous != null && elapsed >= lastElapsed) {
            val drift = Duration.between(previous, device).toMillis() - (elapsed - lastElapsed)
            if (Math.abs(drift) > JUMP_TOLERANCE.toMillis()) systemClockChanged(store, device)
        }
        lastDevice = device
        lastElapsed = elapsed
    }

    private fun correctedNow(): Instant = systemNow().plusMillis(store.correctionMillis)

    private fun zoneDiffers(instant: Instant): Boolean =
        deviceZone().rules.getOffset(instant) != TunisTime.ZONE.rules.getOffset(instant)

    private fun implausible(instant: Instant): Boolean =
        implausibleAlone(instant) ||
            (store.lastKnownGoodMillis > 0 && instant.toEpochMilli() < store.lastKnownGoodMillis - BACKWARD_TOLERANCE.toMillis())

    private fun implausibleAlone(instant: Instant): Boolean = instant.isBefore(EARLIEST) || instant.isAfter(LATEST)

    companion object {
        /** No real clock can be earlier: this code did not exist before. */
        val EARLIEST: Instant = Instant.parse("2026-09-01T00:00:00Z")

        /** The bundled prayer-time formula covers up to 2100. */
        val LATEST: Instant = Instant.parse("2101-01-01T00:00:00Z")

        /** Small corrections (network time, an admin fixing a few minutes) are normal; a jump back of hours is not. */
        val BACKWARD_TOLERANCE: Duration = Duration.ofHours(1)

        /** More than this between the device clock and the time since boot is the clock being set. */
        val JUMP_TOLERANCE: Duration = Duration.ofMinutes(2)

        /** A network time this close confirms the screen's time without moving it (HTTP dates are to the second). */
        val NETWORK_TOLERANCE: Duration = Duration.ofMinutes(2)

        /** The device clock running a little behind its own confirmation is drift, not a reset. */
        val RESET_TOLERANCE: Duration = Duration.ofMinutes(5)

        /**
         * The device clock was set on purpose, to [device] (by the admin in the system settings, or
         * by the network): any correction and confirmation are dropped, and the new clock is the
         * reference, even earlier than the last good time (it is a fix, not a reset). Called from the
         * system's broadcast when the app is not reading the clock itself.
         */
        fun systemClockChanged(store: ClockStore, device: Instant) {
            store.correctionMillis = 0
            store.confirmedBy = null
            store.confirmedAtDeviceMillis = 0
            if (!device.isBefore(EARLIEST) && !device.isAfter(LATEST)) store.lastKnownGoodMillis = device.toEpochMilli()
        }
    }
}

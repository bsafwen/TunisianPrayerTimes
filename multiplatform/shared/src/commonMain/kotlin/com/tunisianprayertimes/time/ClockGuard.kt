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
     * Plausible, but nothing has confirmed it and the device's zone is not Tunisia's (all year, or
     * since the clock was set). A clock set by hand to the local time on such a box is off by the
     * zones' difference (an hour on GMT or on Paris' summer time, seven on Shanghai time), and the
     * instant alone cannot tell. The screen shows the time from the instant and the admin is asked.
     */
    UNVERIFIED,

    /** The clock cannot be right (a box without a clock battery after a power cut): prayer times must not be shown. */
    IMPLAUSIBLE,
}

/** How the time was confirmed. */
enum class ClockSource {
    /**
     * The device's own zone keeps Tunisia's time all year, and did when the clock was set, so its
     * clock, however it was set, means Tunisia's.
     */
    ZONE,

    /** A network time (an HTTP date, the system's network clock) agreed, or corrected it. */
    NETWORK,

    /** The admin confirmed or set the time on the TV. */
    ADMIN,

    /** The admin's phone set it, from the management page. */
    PHONE,
}

/** The time to use now, in Tunisia, whether it can be trusted and, when it is, why. */
data class ClockReading(val now: LocalDateTime, val trust: ClockTrust, val source: ClockSource? = null) {
    /**
     * The wait until just after the next second of the screen's time. The display ticks then, not on
     * the device's own seconds: a correction carries any milliseconds, and each second must show once
     * (and a countdown reach 00:00 on time).
     */
    fun millisToNextTick(): Long = 1000L - now.nano / 1_000_000 + TICK_LATE_MILLIS

    private companion object {
        const val TICK_LATE_MILLIS = 5L
    }
}

/** What the guard remembers across reboots. */
interface ClockStore {
    /** The latest time seen while the clock was trusted or unverified, in epoch millis; 0 when unknown. */
    var lastKnownGoodMillis: Long

    /** Added to the device clock: set by the network, the admin or the phone. */
    var correctionMillis: Long

    /** How the time was last confirmed ([ClockSource.name]), or null when it was not. */
    var confirmedBy: String?

    /**
     * The latest device clock seen (raw, without the correction), and when in which boot; null when
     * unknown. The device clock goes back only when someone sets it, which moves this mark: found
     * earlier than it otherwise, the clock was reset (a power cut without a clock battery), and a
     * correction or confirmation made on the old clock no longer holds.
     */
    var deviceMark: DeviceMark?

    /**
     * The device clock was seen on a zone that does not keep Tunisia's time, and was not set since on
     * one that does: it may have been set by hand to that zone's time, so a change of zone alone does
     * not make it Tunisia's ([ClockSource.ZONE]).
     */
    var foreignZoneSeen: Boolean
}

/** The device clock at [millis] (epoch millis), [elapsed] millis into boot [boot] (null, and [elapsed] 0, when not known). */
data class DeviceMark(val millis: Long, val elapsed: Long, val boot: String?)

/**
 * Judges the device clock. Many mosque TV boxes have no clock battery and half of them are offline,
 * so after a power cut the clock can restart years in the past and nothing corrects it. Many also
 * leave the factory on a foreign zone, and a clock set by hand there is off by hours. Showing prayer
 * times from such a clock is worse than showing none, so the guard checks the instant, not the zone:
 * against the network when the TV is online, else by asking the admin once, and keeps the answer as
 * a correction until the device clock is changed or reset.
 */
class ClockGuard(
    private val store: ClockStore,
    private val systemNow: () -> Instant,
    private val elapsedMillis: () -> Long,
    private val deviceZone: () -> ZoneId,
    /**
     * Which boot of the device this is, to recognise a new one: an id only a real boot changes (a
     * restart of the system alone keeps the kernel, its clock and the time since boot); null when unknown.
     */
    private val bootId: () -> String? = { null },
) {
    /** The device clock and the time since boot at the last reading, to notice the clock being changed. */
    private var lastDevice: Instant? = null
    private var lastElapsed = 0L

    fun read(): ClockReading {
        val device = systemNow()
        noticeChange(device)
        noticeReset(device)
        // A confirmed time that cannot be right was confirmed on a clock reset since (a cut the marks
        // missed): it no longer holds, and must not be offered for confirmation again.
        if (store.confirmedBy != null && implausible(device.plusMillis(store.correctionMillis))) forget()
        val instant = device.plusMillis(store.correctionMillis)
        val confirmed = store.confirmedBy?.let { name -> ClockSource.entries.firstOrNull { it.name == name } }
        val zoneKeepsTunisTime = keepsTunisTime(deviceZone(), instant)
        if (!zoneKeepsTunisTime && !store.foreignZoneSeen) store.foreignZoneSeen = true
        val (trust, source) = when {
            implausible(instant) -> ClockTrust.IMPLAUSIBLE to null
            confirmed != null -> ClockTrust.TRUSTED to confirmed
            zoneKeepsTunisTime && !store.foreignZoneSeen -> ClockTrust.TRUSTED to ClockSource.ZONE
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
     * Whether the device's zone keeps Tunisia's time all year, the rule [read] trusts a zone by: a zone
     * with summer time (Europe/Paris) agrees with Tunisia in winter but still leaves the clock unconfirmed.
     */
    fun zoneKeepsTunisTime(): Boolean = keepsTunisTime(deviceZone(), correctedNow())

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
     * A network time: [instant] is now, give or take [uncertainty]. Further off the screen's time than
     * that (and than [NETWORK_TOLERANCE]), it corrects it, whatever the admin said before: the network
     * is the better clock; closer, it confirms it. A time nothing vouches for ([authenticated] false:
     * plain NTP, which anyone on the mosque's network can answer) is only taken for a clock that cannot
     * be right, where the wall shows no prayer times anyway. False when it is not taken.
     */
    fun networkTime(instant: Instant, uncertainty: Duration, authenticated: Boolean): Boolean {
        if (implausibleAlone(instant)) return false
        val device = systemNow()
        val shown = device.plusMillis(store.correctionMillis)
        if (!authenticated && !implausible(shown)) return false
        if (Duration.between(shown, instant).abs() > maxOf(NETWORK_TOLERANCE, uncertainty)) {
            store.correctionMillis = Duration.between(device, instant).toMillis()
        }
        confirm(ClockSource.NETWORK, device, instant)
        return true
    }

    /** The device clock was changed on purpose (the system's time-set broadcast): see [systemClockChanged]. */
    fun systemClockChanged() {
        clockSet(systemNow())
        lastDevice = null
    }

    /** The device clock was set on purpose, to [device]: the companion's [systemClockChanged], marked in this boot. */
    private fun clockSet(device: Instant) {
        clockSet(store, device, deviceZone(), markOf(device))
    }

    private fun markOf(device: Instant) = DeviceMark(device.toEpochMilli(), elapsedMillis(), bootId())

    private fun confirm(source: ClockSource, device: Instant, instant: Instant) {
        store.confirmedBy = source.name
        // The correction holds for the device clock as it is now: a reset is found against it from here.
        store.deviceMark = markOf(device)
        // Even earlier than before: this is the reference now.
        store.lastKnownGoodMillis = instant.toEpochMilli()
    }

    private fun forget() {
        store.correctionMillis = 0
        store.confirmedBy = null
    }

    /**
     * The device clock found earlier than it has already been (beyond a clock battery's drift), or on a
     * new boot started earlier than that: it was reset since (a power cut on a box without a clock
     * battery), as a clock set on purpose moves the mark ([systemClockChanged]). The start of the boot
     * catches a reset however late the app starts after it. A correction or confirmation was made on
     * the old clock and is dropped; the clock now is the new mark.
     */
    private fun noticeReset(device: Instant) {
        val now = device.toEpochMilli()
        val elapsed = elapsedMillis()
        val boot = bootId()
        val mark = store.deviceMark
        val reset = mark != null && run {
            val floor = mark.millis - resetTolerance(mark.elapsed)
            val newBoot = boot != null && mark.boot != null && boot != mark.boot
            now < floor || (newBoot && now - elapsed < floor)
        }
        if (reset) forget()
        if (mark == null || reset || now > mark.millis) store.deviceMark = DeviceMark(now, elapsed, boot)
    }

    /** [RESET_TOLERANCE], and [RTC_DRIFT] of the [uptime] (millis) a mark was seen at. */
    private fun resetTolerance(uptime: Long): Long = RESET_TOLERANCE.toMillis() + (uptime * RTC_DRIFT).toLong()

    /**
     * A jump of the device clock while the app runs is someone (or the network) setting it: never a
     * power cut. The old correction and confirmation no longer mean anything.
     */
    private fun noticeChange(device: Instant) {
        val elapsed = elapsedMillis()
        val previous = lastDevice
        if (previous != null && elapsed >= lastElapsed) {
            val drift = Duration.between(previous, device).toMillis() - (elapsed - lastElapsed)
            if (Math.abs(drift) > JUMP_TOLERANCE.toMillis()) clockSet(device)
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

        /**
         * A network time this close confirms the screen's time without moving it, unless its own
         * margin is wider: a few seconds are not worth a correction (and a log line) at every check.
         */
        val NETWORK_TOLERANCE: Duration = Duration.ofSeconds(10)

        /**
         * The device clock found this much earlier than it has been, and [RTC_DRIFT] of the uptime it was
         * seen at, is a reset. At a reboot the system clock restarts from the clock battery's, which
         * Android writes only when the time is set and which drifts from it (seconds a day, minutes
         * after weeks); a reset goes back the whole uptime, to where that boot started or further. The
         * marks are written once a minute, which only makes it later.
         */
        val RESET_TOLERANCE: Duration = Duration.ofMinutes(1)

        /** The drift allowed between the system clock and the clock battery's: 0.1 % (86 s a day), well above a crystal's. */
        const val RTC_DRIFT = 0.001

        /** How far either side of now a zone must keep Tunisia's time: summer time comes back every year. */
        private val ZONE_YEAR: Duration = Duration.ofDays(366)

        /**
         * The device clock was set on purpose, to [device], on [zone] (by the admin in the system
         * settings, or by the network): any correction and confirmation are dropped, and the new clock
         * is the reference, even earlier than the last good time and the mark (it is a fix, not a
         * reset). Called from the system's broadcast when the app is not reading the clock itself.
         */
        fun systemClockChanged(store: ClockStore, device: Instant, zone: ZoneId) {
            // The boot, and the time into it, are recorded at the next reading.
            clockSet(store, device, zone, DeviceMark(device.toEpochMilli(), elapsed = 0, boot = null))
        }

        private fun clockSet(store: ClockStore, device: Instant, zone: ZoneId, mark: DeviceMark) {
            store.correctionMillis = 0
            store.confirmedBy = null
            store.deviceMark = mark
            // Set on a zone that keeps Tunisia's time, the clock means Tunisia's again; on another, it may not.
            store.foreignZoneSeen = !keepsTunisTime(zone, device)
            if (!device.isBefore(EARLIEST) && !device.isAfter(LATEST)) store.lastKnownGoodMillis = device.toEpochMilli()
        }

        /**
         * Whether [zone] keeps Tunisia's time all year around [at]: Africa/Algiers or a fixed +01:00 do;
         * a zone with summer time (Europe/Paris, Africa/Casablanca in Ramadan) agrees only part of the
         * year, and a clock set by hand in the other part is off by an hour.
         */
        private fun keepsTunisTime(zone: ZoneId, at: Instant): Boolean {
            val from = at.minus(ZONE_YEAR)
            val to = at.plus(ZONE_YEAR)
            // Offsets change only at transitions: comparing them at the start and at each one covers every instant.
            val changes = sequenceOf(zone, TunisTime.ZONE).flatMap { transitions(it, from, to) }
            return (sequenceOf(from) + changes).all { zone.rules.getOffset(it) == TunisTime.ZONE.rules.getOffset(it) }
        }

        private fun transitions(zone: ZoneId, from: Instant, to: Instant): Sequence<Instant> =
            generateSequence(zone.rules.nextTransition(from)) { zone.rules.nextTransition(it.instant) }
                .map { it.instant }
                .takeWhile { !it.isAfter(to) }
    }
}

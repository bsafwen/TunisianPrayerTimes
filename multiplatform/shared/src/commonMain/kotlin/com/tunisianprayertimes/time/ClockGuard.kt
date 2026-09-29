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
    TRUSTED,

    /** The instant looks right but the device zone is not Tunisia's: times are converted, the admin is warned. */
    WRONG_ZONE,

    /** The clock cannot be right (a box without a clock battery after a power cut): prayer times must not be shown. */
    IMPLAUSIBLE,
}

/** The time to use now, in Tunisia, and whether it can be trusted. */
data class ClockReading(val now: LocalDateTime, val trust: ClockTrust)

/** What the guard remembers between readings and across reboots. */
interface ClockStore {
    /** The latest wall-clock time seen while the clock was trusted, in epoch millis; 0 when unknown. */
    var lastKnownGoodMillis: Long

    /** An in-app correction added to the device clock; valid only in the boot it was made in. */
    var correctionMillis: Long

    /** Time since boot when the correction was made; a smaller time since boot means the box rebooted. */
    var correctionElapsedMillis: Long
}

/**
 * Judges the device clock. Many mosque TV boxes have no clock battery and half of them are offline,
 * so after a power cut the clock can restart years in the past and nothing corrects it. Showing
 * prayer times from such a clock is worse than showing none.
 */
class ClockGuard(
    private val store: ClockStore,
    private val systemNow: () -> Instant,
    private val elapsedMillis: () -> Long,
    private val deviceZone: () -> ZoneId,
) {

    fun read(): ClockReading {
        val instant = correctedNow()
        val trust = when {
            implausible(instant) -> ClockTrust.IMPLAUSIBLE
            deviceZone().rules.getOffset(instant) != TunisTime.ZONE.rules.getOffset(instant) -> ClockTrust.WRONG_ZONE
            else -> ClockTrust.TRUSTED
        }
        if (trust != ClockTrust.IMPLAUSIBLE && instant.toEpochMilli() > store.lastKnownGoodMillis) {
            store.lastKnownGoodMillis = instant.toEpochMilli()
        }
        return ClockReading(LocalDateTime.ofInstant(instant, TunisTime.ZONE), trust)
    }

    /**
     * Where an admin setting the time by hand should start: the later of the device time and the last
     * good time (plus the floor), so a box reset to 1970 does not need years of button presses.
     */
    fun suggestedTime(): LocalDateTime {
        val candidates = listOf(correctedNow(), EARLIEST, Instant.ofEpochMilli(store.lastKnownGoodMillis))
        return LocalDateTime.ofInstant(candidates.filter { !it.isAfter(LATEST) }.max(), TunisTime.ZONE)
    }

    /** The admin says the current time is right: it becomes the reference, even if earlier than before. */
    fun confirm() {
        store.lastKnownGoodMillis = correctedNow().toEpochMilli()
    }

    /** The admin sets the time in the app (for boxes whose system time settings are locked). */
    fun setTime(tunisNow: LocalDateTime) {
        store.correctionMillis = Duration.between(systemNow(), tunisNow.atZone(TunisTime.ZONE).toInstant()).toMillis()
        store.correctionElapsedMillis = elapsedMillis()
        confirm()
    }

    /** Drops an in-app correction, for example once the system clock has been fixed. */
    fun clearCorrection() {
        store.correctionMillis = 0
        store.correctionElapsedMillis = 0
    }

    private fun correctedNow(): Instant {
        val system = systemNow()
        if (store.correctionMillis == 0L) return system
        // A correction only holds until the next reboot (the device clock is reset by then), and
        // only while the device clock is still wrong: once it is fixed, adding it would break it again.
        if (elapsedMillis() < store.correctionElapsedMillis || !implausible(system)) {
            clearCorrection()
            return system
        }
        return system.plusMillis(store.correctionMillis)
    }

    private fun implausible(instant: Instant): Boolean =
        instant.isBefore(EARLIEST) ||
            instant.isAfter(LATEST) ||
            (store.lastKnownGoodMillis > 0 && instant.toEpochMilli() < store.lastKnownGoodMillis - BACKWARD_TOLERANCE.toMillis())

    companion object {
        /** No real clock can be earlier: this code did not exist before. */
        val EARLIEST: Instant = Instant.parse("2026-09-01T00:00:00Z")

        /** The bundled prayer-time formula covers up to 2100. */
        val LATEST: Instant = Instant.parse("2101-01-01T00:00:00Z")

        /** Small corrections (network time, an admin fixing a few minutes) are normal; a jump back of hours is not. */
        val BACKWARD_TOLERANCE: Duration = Duration.ofHours(1)
    }
}

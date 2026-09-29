package com.tunisianprayertimes.tv.kiosk

import com.tunisianprayertimes.mosque.FlowPhase
import java.time.Duration
import java.time.LocalDateTime

/**
 * A nightly restart clears slow leaks on boxes that run for months. It happens once the app has
 * run for [MIN_UPTIME], in the quiet of the night: [AFTER_ISHA] after the Isha iqamah (so after
 * tarawih too) and at least [BEFORE_FAJR] before Fajr, and only while no prayer screen is shown.
 * The prayer flow is a function of the clock, so the restarted app lands on the same screen.
 */
object MaintenanceRestart {

    val MIN_UPTIME: Duration = Duration.ofHours(20)
    val AFTER_ISHA: Duration = Duration.ofMinutes(90)
    val BEFORE_FAJR: Duration = Duration.ofMinutes(60)

    /** Isha to the next Fajr is at most about ten hours; more means they are not the same night. */
    private val LONGEST_NIGHT: Duration = Duration.ofHours(14)

    fun isDue(
        now: LocalDateTime,
        uptime: Duration,
        phase: FlowPhase,
        lastIshaIqamah: LocalDateTime?,
        nextFajrAdhan: LocalDateTime?,
    ): Boolean {
        if (uptime < MIN_UPTIME || phase != FlowPhase.IDLE) return false
        if (lastIshaIqamah == null || nextFajrAdhan == null) return false
        if (Duration.between(lastIshaIqamah, nextFajrAdhan) > LONGEST_NIGHT) return false // daytime
        val opens = lastIshaIqamah.plus(AFTER_ISHA)
        val closes = nextFajrAdhan.minus(BEFORE_FAJR)
        return !now.isBefore(opens) && now.isBefore(closes)
    }
}

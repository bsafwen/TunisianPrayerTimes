package com.tunisianprayertimes.mosque

import com.tunisianprayertimes.DayPrayerTimes
import com.tunisianprayertimes.IslamicDay
import com.tunisianprayertimes.Prayer
import java.time.LocalDate
import java.time.LocalDateTime

/** What a mosque screen says about the day, beyond its prayers. */
sealed interface DayBanner {
    /** A day or night of Ramadan; [countdown] is null when no fast is running or ahead (the last evening). */
    data class Ramadan(val countdown: FastCountdown?) : DayBanner

    /** An Eid day; [prayerAt] until the Eid prayer has begun. */
    data class Eid(val prayer: Prayer, val prayerAt: LocalDateTime?) : DayBanner

    data object Arafah : DayBanner
}

/** Time left until suhoor ends (Fajr) or until iftar (Maghrib). */
data class FastCountdown(val kind: Kind, val until: LocalDateTime) {
    enum class Kind { SUHOOR_ENDS, IFTAR }
}

object DayBanners {

    /**
     * The banner at [now]. The night before the first fast already counts down to its suhoor, since
     * the Hijri day begins at sunset. [eidPrayerAt] is today's Eid prayer as the prayer flow resolved it.
     */
    fun at(
        now: LocalDateTime,
        today: IslamicDay,
        tomorrow: IslamicDay,
        times: DayPrayerTimes?,
        tomorrowTimes: DayPrayerTimes?,
        eidPrayerAt: LocalDateTime? = null,
    ): DayBanner? {
        val date = now.toLocalDate()
        if (today.isEid) {
            val prayer = if (today.isEidFitr) Prayer.AID_FITR else Prayer.AID_ADHA
            return DayBanner.Eid(prayer, eidPrayerAt?.takeIf { now.isBefore(it) })
        }
        if (today.isArafah) return DayBanner.Arafah
        if (!today.isRamadan && !tomorrow.isRamadan) return null
        if (times == null) return if (today.isRamadan) DayBanner.Ramadan(null) else null
        val fajr = date.atTime(times.fajr.hour, times.fajr.minute)
        val maghrib = date.atTime(times.maghrib.hour, times.maghrib.minute)
        val countdown = when {
            today.isRamadan && now.isBefore(fajr) -> FastCountdown(FastCountdown.Kind.SUHOOR_ENDS, fajr)
            today.isRamadan && now.isBefore(maghrib) -> FastCountdown(FastCountdown.Kind.IFTAR, maghrib)
            now.isBefore(maghrib) -> return null // the afternoon before Ramadan
            tomorrow.isRamadan -> tomorrowFajr(date.plusDays(1), tomorrowTimes)?.let { FastCountdown(FastCountdown.Kind.SUHOOR_ENDS, it) }
            else -> null
        }
        return DayBanner.Ramadan(countdown)
    }

    private fun tomorrowFajr(date: LocalDate, times: DayPrayerTimes?): LocalDateTime? =
        times?.let { date.atTime(it.fajr.hour, it.fajr.minute) }
}

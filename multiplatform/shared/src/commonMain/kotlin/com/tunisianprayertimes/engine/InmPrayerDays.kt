package com.tunisianprayertimes.engine

import com.tunisianprayertimes.DayPrayerTimes
import com.tunisianprayertimes.InmPrayerTimes
import com.tunisianprayertimes.PrayerFormulaSettings
import com.tunisianprayertimes.place.PrayerPlace
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * INM's times as instants (plan 6.2, 6.6): each hour:minute that [InmPrayerTimes] already returns,
 * relabelled at INM's fixed offset +01:00. No math, so INM's times cannot drift; a phone set to
 * another zone then shows and silences them at their real instants.
 */
class InmPrayerDays(private val inm: InmPrayerTimes) {
    /** Prayer day [date] of delegation [delegationId], or null outside INM's delegations and years. */
    fun day(
        delegationId: Int,
        date: LocalDate,
        settings: PrayerFormulaSettings = PrayerFormulaSettings.OFFICIAL,
    ): PrayerDay? {
        val times = inm.loadDayPrayerTimes(delegationId, date.year, date.monthValue, date.dayOfMonth, settings) ?: return null
        return relabel(PrayerPlace.Inm(delegationId), date, times)
    }

    companion object {
        /** INM computes and publishes in UTC+1 all year, with no summer time. */
        val INM_OFFSET: ZoneOffset = ZoneOffset.ofHours(1)

        fun relabel(place: PrayerPlace.Inm, date: LocalDate, times: DayPrayerTimes): PrayerDay {
            val midnight = date.atStartOfDay().toEpochSecond(INM_OFFSET)
            fun at(event: TimeEvent, hour: Int, minute: Int) =
                EventTime(event, java.time.Instant.ofEpochSecond(midnight + hour * 3600L + minute * 60L), EventStatus.COMPUTED)
            return PrayerDay(
                date = date,
                zone = place.zone,
                placeKey = place.cacheKey,
                events = listOf(
                    at(TimeEvent.FAJR, times.fajr.hour, times.fajr.minute),
                    at(TimeEvent.SUNRISE, times.shurukHour, times.shurukMinute),
                    at(TimeEvent.DHUHR, times.dhuhr.hour, times.dhuhr.minute),
                    at(TimeEvent.ASR, times.asr.hour, times.asr.minute),
                    at(TimeEvent.MAGHRIB, times.maghrib.hour, times.maghrib.minute),
                    at(TimeEvent.ISHA, times.isha.hour, times.isha.minute),
                ),
            )
        }
    }
}

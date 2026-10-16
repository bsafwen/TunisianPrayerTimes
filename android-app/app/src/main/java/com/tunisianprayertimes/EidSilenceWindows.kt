package com.tunisianprayertimes

import android.content.Context
import com.tunisianprayertimes.engine.PrayerDay
import com.tunisianprayertimes.engine.TimeEvent
import com.tunisianprayertimes.place.PrayerPlace
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * The Eid prayers of a prayer day (A10, 6.9): Tunisian places only, on Tunisia's Eid dates, at the edited Eid
 * time or else at sunrise. Their windows are windows of their prayer day in the planner's scan, with their own
 * day slots, so no look-back of their own is needed.
 */
internal object EidSilenceWindows {
    val prayers = listOf(Prayer.AID_FITR, Prayer.AID_ADHA)

    fun eidPrayers(context: Context, place: PrayerPlace, date: LocalDate, day: PrayerDay?): List<Pair<Prayer, Instant>> {
        if (place !is PrayerPlace.Inm || day == null) return emptyList()
        val sunrise = day[TimeEvent.SUNRISE].at ?: return emptyList()
        return buildList {
            if (RamadanOverrideChecker.isEidFitr(date)) {
                add(Prayer.AID_FITR to time(place, date, PrefsManager.getAidFitrTimeHour(context), PrefsManager.getAidFitrTimeMinute(context), sunrise))
            }
            if (RamadanOverrideChecker.isEidAdha(date)) {
                add(Prayer.AID_ADHA to time(place, date, PrefsManager.getAidAdhaTimeHour(context), PrefsManager.getAidAdhaTimeMinute(context), sunrise))
            }
        }
    }

    /** An edited Eid time is a clock time in the place's zone (6.6); an unset one (−1) is sunrise. */
    private fun time(place: PrayerPlace, date: LocalDate, hour: Int, minute: Int, sunrise: Instant): Instant =
        if (hour in 0..23 && minute in 0..59) ZonedDateTime.of(date, LocalTime.of(hour, minute), place.zone).toInstant() else sunrise
}

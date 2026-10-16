package com.tunisianprayertimes.silence

import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.engine.HighLatitudeRule
import com.tunisianprayertimes.engine.PrayerDay
import com.tunisianprayertimes.engine.PrayerEngine
import com.tunisianprayertimes.engine.TimeEvent
import com.tunisianprayertimes.method.PrayerMethod
import com.tunisianprayertimes.method.PrayerMethods
import com.tunisianprayertimes.place.LatLngE5
import com.tunisianprayertimes.place.PrayerPlace
import com.tunisianprayertimes.place.ZoneSource
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Plan 9.1's scan of the endpoint domain (6.9). For every 15-minute instant of 2026 at Paris (D5), London (middle
 * of the night) and Tromsø, with every prayer's window at the domain's corners (a start at adhan − 720 or + 720, an
 * end at adhan + 1,440), prayer days D−6..D+3 are searched by brute force: no window outside D−2..D+1 contains the
 * instant, and none of D+2 or later starts before the next 00:01 run in the place's zone. So the scheduler's scan of
 * D−2..D+1, re-run every day at 00:01, arms every window that can be open or start before the next run.
 */
class SilenceScanTest {
    private class Site(val name: String, val place: PrayerPlace.Coordinates, val method: PrayerMethod)

    private fun place(lat: Double, lng: Double, zone: String, country: String) =
        PrayerPlace.Coordinates(LatLngE5.of(lat, lng).latE5, LatLngE5.of(lat, lng).lngE5, null, zone, ZoneSource.USER, country)

    private val fivePrayers = listOf(
        Prayer.FAJR to TimeEvent.FAJR, Prayer.DHUHR to TimeEvent.DHUHR, Prayer.ASR to TimeEvent.ASR,
        Prayer.MAGHRIB to TimeEvent.MAGHRIB, Prayer.ISHA to TimeEvent.ISHA,
    )

    private val corners = listOf(
        SilenceRule(SilenceStart.FromAdhan(-SilenceWindowPlanner.START_DOMAIN_MINUTES.toInt()), SilenceEnd.FromAdhan(SilenceWindowPlanner.END_DOMAIN_MINUTES.toInt())),
        SilenceRule(SilenceStart.FromAdhan(SilenceWindowPlanner.START_DOMAIN_MINUTES.toInt()), SilenceEnd.FromAdhan(SilenceWindowPlanner.END_DOMAIN_MINUTES.toInt())),
    )

    @Test
    fun theFourScannedPrayerDaysHoldEveryWindowThatMatters() {
        val sites = listOf(
            Site("Paris", place(48.8566, 2.3522, "Europe/Paris", "FR"), PrayerMethods.MWL),
            Site("London", place(51.5074, -0.1278, "Europe/London", "GB"), PrayerMethods.MWL.copy(highLatitude = HighLatitudeRule.MiddleOfNight())),
            Site("Tromsø", place(69.6492, 18.9553, "Europe/Oslo", "NO"), PrayerMethods.MWL),
        )
        val problems = ArrayList<String>()
        var checked = 0L
        var openFromDMinus2 = 0
        for (site in sites) {
            val zone = site.place.zone
            val days = HashMap<LocalDate, PrayerDay?>()
            fun dayOf(date: LocalDate) = days.getOrPut(date) { PrayerEngine.day(site.place, date, site.method) }
            for (rule in corners) {
                val windows = HashMap<LocalDate, List<SilenceWindow>>()
                fun windowsOf(date: LocalDate) = windows.getOrPut(date) {
                    val day = dayOf(date) ?: return@getOrPut emptyList()
                    fivePrayers.mapNotNull { (prayer, event) ->
                        day[event].at?.let { adhan -> SilenceWindowPlanner.window(prayer, date, adhan, rule, zone, inm = false) }
                    }
                }
                var t = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, zone).toInstant()
                val end = ZonedDateTime.of(2027, 1, 1, 0, 0, 0, 0, zone).toInstant()
                while (t < end && problems.size < 20) {
                    val today = t.atZone(zone).toLocalDate()
                    val nextRun = ZonedDateTime.of(today.plusDays(1), LocalTime.of(0, 1), zone).toInstant()
                    // The look-back is needed: a window of D−2 can still be open on D (Paris's Isha in June, 6.9).
                    if (windowsOf(today.minusDays(2)).any { t in it }) openFromDMinus2++
                    for (k in longArrayOf(-6, -5, -4, -3, 2, 3)) {
                        for (window in windowsOf(today.plusDays(k))) {
                            checked++
                            if (t in window) problems += "${site.name} $rule: ${window.prayer} of D$k is open at $t"
                            if (k >= 2 && window.start < nextRun) problems += "${site.name} $rule: ${window.prayer} of D+$k starts at ${window.start}, before the run at $nextRun"
                        }
                    }
                    t = t.plusSeconds(15 * 60L)
                }
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
        assertTrue(checked > 3_000_000, "windows checked: $checked")
        assertTrue(openFromDMinus2 > 0, "no window of D−2 was ever open on D")
    }
}

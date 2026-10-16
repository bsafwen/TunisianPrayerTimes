package com.tunisianprayertimes.engine

import com.tunisianprayertimes.engine.EngineTestSupport.place
import com.tunisianprayertimes.method.PrayerMethods
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Summer-time days asserted as instants, not wall-clock fields (plan 9.1): a change of the clock never
 * moves a day's solar cycle, so each day's instants equal those computed with the zone's standard
 * offset alone, and follow the days around it by about 24 h.
 */
class TimeZoneDaysTest {
    private data class Day(val name: String, val lat: Double, val lng: Double, val zone: String, val dates: List<String>)

    private val days = listOf(
        Day("London", 51.5074, -0.1278, "Europe/London", listOf("2026-03-29", "2026-10-25")),
        Day("Lisbon", 38.7223, -9.1393, "Europe/Lisbon", listOf("2026-03-29", "2026-10-25")),
        Day("Paris", 48.8566, 2.3522, "Europe/Paris", listOf("2026-03-29", "2026-10-25")),
        Day("Berlin", 52.52, 13.405, "Europe/Berlin", listOf("2026-03-29", "2026-10-25")),
        Day("Helsinki", 60.1699, 24.9384, "Europe/Helsinki", listOf("2026-03-29", "2026-10-25")),
        Day("Kyiv", 50.4501, 30.5234, "Europe/Kyiv", listOf("2026-03-29", "2026-10-25")),
        Day("Istanbul", 41.0082, 28.9784, "Europe/Istanbul", listOf("2026-03-29", "2026-10-25")),
        Day("Moscow", 55.7558, 37.6173, "Europe/Moscow", listOf("2026-03-29", "2026-10-25")),
        Day("Cairo", 30.0444, 31.2357, "Africa/Cairo", listOf("2026-04-24", "2026-10-29", "2026-10-30")),
        Day("Beirut", 33.8938, 35.5018, "Asia/Beirut", listOf("2026-03-29", "2026-10-25")),
        Day("Gaza", 31.5017, 34.4668, "Asia/Gaza", listOf("2026-03-28", "2026-10-24")),
        Day("Hebron", 31.5326, 35.0998, "Asia/Hebron", listOf("2026-03-28", "2026-10-24")),
        Day("Casablanca", 33.5731, -7.5898, "Africa/Casablanca", listOf("2026-09-20")),
        Day("Amman", 31.9539, 35.9106, "Asia/Amman", listOf("2022-10-28")),
        Day("Damascus", 33.5138, 36.2765, "Asia/Damascus", listOf("2022-10-28")),
    )

    @Test
    fun summerTimeDaysKeepTheirSolarCycle() {
        for (d in days) for (iso in d.dates) {
            val date = LocalDate.parse(iso)
            val zoned = place(d.lat, d.lng, d.zone)
            val noon = ZonedDateTime.of(date, LocalTime.NOON, zoned.zone).toInstant()
            val standard = ZoneOffset.ofTotalSeconds(
                zoned.zone.rules.getOffset(noon).totalSeconds - zoned.zone.rules.getDaylightSavings(noon).seconds.toInt(),
            )
            val fixed = place(d.lat, d.lng, standard.id)
            // Methods without clock times: the instants cannot depend on the clock's offset.
            val method = PrayerMethods.ISNA
            val inZone = PrayerEngine.day(zoned, date, method)
            val inStandard = PrayerEngine.day(fixed, date, method)
            assertEquals(inStandard.events, inZone.events, "${d.name} $date")
            val before = PrayerEngine.day(zoned, date.minusDays(1), method)
            val after = PrayerEngine.day(zoned, date.plusDays(1), method)
            for (event in TimeEvent.entries) {
                val a = Duration.between(before[event].at, inZone[event].at).toMinutes()
                val b = Duration.between(inZone[event].at, after[event].at).toMinutes()
                // Up to about 4 min a day at Helsinki in spring; a clock change would show as 60.
                assertTrue(abs(a - 1440) <= 8 && abs(b - 1440) <= 8, "${d.name} $date $event: $a and $b min")
            }
        }
    }
}

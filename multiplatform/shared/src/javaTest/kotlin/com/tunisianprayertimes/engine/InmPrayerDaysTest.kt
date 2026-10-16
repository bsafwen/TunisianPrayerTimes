package com.tunisianprayertimes.engine

import com.tunisianprayertimes.InmPrayerTimes
import com.tunisianprayertimes.PrayerFormulaSettings
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** INM's times relabelled as instants at +01:00, with no math (plan 6.2, 6.6). */
class InmPrayerDaysTest {
    private val inm = InmPrayerTimes.fromParamsJson(
        checkNotNull(javaClass.getResource("/delegation_params.json")).readText(),
    )
    private val days = InmPrayerDays(inm)

    @Test
    fun everyDelegationAndDayRoundTripsThroughInstants() {
        assertEquals(258, inm.delegationIds.size)
        var checked = 0
        for (id in inm.delegationIds) {
            var date = LocalDate.of(2026, 1, 1)
            while (date.year == 2026) {
                val times = checkNotNull(inm.loadDayPrayerTimes(id, date.year, date.monthValue, date.dayOfMonth))
                val day = checkNotNull(days.day(id, date))
                val expected = listOf(
                    times.fajr.hour to times.fajr.minute,
                    times.shurukHour to times.shurukMinute,
                    times.dhuhr.hour to times.dhuhr.minute,
                    times.asr.hour to times.asr.minute,
                    times.maghrib.hour to times.maghrib.minute,
                    times.isha.hour to times.isha.minute,
                )
                day.events.zip(expected).forEach { (event, hm) ->
                    val local = checkNotNull(event.at).atOffset(ZoneOffset.ofHours(1))
                    assertEquals(date, local.toLocalDate(), "$id $date ${event.event}")
                    assertEquals(hm, local.hour to local.minute, "$id $date ${event.event}")
                    assertEquals(EventStatus.COMPUTED, event.status)
                }
                assertEquals("inm:$id", day.placeKey)
                checked++
                date = date.plusDays(1)
            }
        }
        assertEquals(258 * 365, checked)
    }

    @Test
    fun aPhoneElsewhereSeesTheRealInstant() {
        // Tunis 2026-07-01: INM's 13:00-style wall times are UTC+1 all year, so a Paris phone in summer sees them an hour later.
        val day = checkNotNull(days.day(615, LocalDate.of(2026, 7, 1)))
        val dhuhr = checkNotNull(day[TimeEvent.DHUHR].at)
        val tunis = dhuhr.atZone(java.time.ZoneId.of("Africa/Tunis")).toLocalTime()
        val paris = dhuhr.atZone(java.time.ZoneId.of("Europe/Paris")).toLocalTime()
        assertEquals(tunis.plusHours(1), paris)
    }

    @Test
    fun settingsAndMissingData() {
        val custom = PrayerFormulaSettings(fajrAngle = 16.0)
        val official = checkNotNull(days.day(615, LocalDate.of(2026, 9, 30)))
        val changed = checkNotNull(days.day(615, LocalDate.of(2026, 9, 30), custom))
        assertEquals(official[TimeEvent.DHUHR], changed[TimeEvent.DHUHR])
        val fajr16 = checkNotNull(inm.loadDayPrayerTimes(615, 2026, 9, 30, custom)).fajr
        assertEquals(LocalDate.of(2026, 9, 30).atTime(fajr16.hour, fajr16.minute).toInstant(ZoneOffset.ofHours(1)), changed[TimeEvent.FAJR].at)
        assertEquals(true, checkNotNull(changed[TimeEvent.FAJR].at) > checkNotNull(official[TimeEvent.FAJR].at))
        assertNull(days.day(99999, LocalDate.of(2026, 9, 30)))
        assertNull(days.day(615, LocalDate.of(2019, 12, 31)))
    }
}

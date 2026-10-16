package com.tunisianprayertimes.engine.arab

import com.tunisianprayertimes.engine.EngineReferenceCases
import com.tunisianprayertimes.engine.EngineTestSupport
import com.tunisianprayertimes.engine.EventStatus
import com.tunisianprayertimes.engine.PrayerEngine
import com.tunisianprayertimes.engine.RuleName
import com.tunisianprayertimes.engine.TimeEvent
import com.tunisianprayertimes.method.AsrRule
import com.tunisianprayertimes.method.PrayerMethod
import com.tunisianprayertimes.method.PrayerMethods
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Arab grid (plan 6.2 "Invariants", 9.1): 13°S to 38°N and 18°W to 60°E, in 2026 and 2099, with the
 * reference presets and a stand-in for each authority profile's mechanisms (the profiles themselves
 * come with the AM2 fits). Every day: six times, the astronomical order of the raw events, the displayed
 * order on the instants and on the minutes into the next day, the night's lowest point between prayer
 * days, and no time estimated by D5's rule, a polar rule or the order guard. Zones are whole-hour offsets
 * and 2099 is read in UTC only.
 */
class ArabGridInvariantsTest {
    private val methods: List<PrayerMethod> = PrayerMethods.REFERENCE + listOf(
        EngineReferenceCases.JORDAN_LIKE,
        EngineReferenceCases.YEMEN_LIKE,
        EngineReferenceCases.LIBYA_LIKE,
        EngineReferenceCases.OMAN_LIKE,
        EngineReferenceCases.SAUDI_LIKE,
        EngineReferenceCases.ALGERIA_LIKE.copy(asr = AsrRule.Shadow),
        EngineReferenceCases.QATAR_LIKE,
        EngineReferenceCases.COMOROS_LIKE,
        EngineReferenceCases.BAHRAIN_LIKE,
        EngineReferenceCases.MOROCCO_LIKE,
        EngineReferenceCases.SYRIA_LIKE,
    )

    private val forbidden = setOf(
        RuleName.ANGLE_BASED, RuleName.MIN_DAY_NIGHT_5H, RuleName.POLAR_ASR_MIDPOINT, RuleName.DIYANET_CLAMP,
        RuleName.IRN_MECCA_CYCLE, RuleName.HELD_IN_ORDER, RuleName.NIGHT_LOWEST_POINT,
    )

    @Test
    fun everyDayKeepsTheContract() {
        var days = 0
        for (year in listOf(2026, 2099)) {
            for (lat in -13..38 step 4) for (lng in -18..60 step 8) {
                val zone = ZoneOffset.ofHours(Math.round(lng / 15.0).toInt()).id
                val place = EngineTestSupport.place(lat + 0.37, lng + 0.61, zone, elevationM = (lat * 37 + lng * 11).mod(1700))
                methods.forEachIndexed { m, method ->
                    // Each method on its own days of the year, so every season meets every method.
                    var date = LocalDate.of(year, 1, 1).plusDays(((lat + 13) * 7 + lng + 18 + m * 5).mod(11).toLong())
                    while (date.year == year) {
                        check(place, date, method)
                        days++
                        date = date.plusDays(11)
                    }
                }
            }
        }
        assertTrue(days > 50_000, "$days days")
    }

    private fun check(place: com.tunisianprayertimes.place.PrayerPlace.Coordinates, date: LocalDate, method: PrayerMethod) {
        val what = "${method.id} at ${place.latE5 / 1e5},${place.lngE5 / 1e5} on $date"
        val today = PrayerEngine.explain(place, date, method)
        val tomorrow = PrayerEngine.explain(place, date.plusDays(1), method)
        for (e in listOf(today, tomorrow)) {
            e.day.events.forEach { time ->
                assertNotNull(time.at, "$what: ${time.event} absent")
                assertTrue(time.rule !in forbidden, "$what: ${time.event} estimated by ${time.rule}")
            }
        }
        // The astronomical order of the raw events: sunrise < transit < Asr < sunset < sunrise(D + 1).
        val raw = TimeEvent.entries.associateWith { today.events[it.ordinal].raw }
        val sunset = checkNotNull(today.nightAfter).sunset
        val order = listOfNotNull(raw[TimeEvent.SUNRISE], raw[TimeEvent.DHUHR], raw[TimeEvent.ASR].takeIf { method.asr is AsrRule.Shadow || method.asr is AsrRule.SignedShadow }, sunset, today.nightAfter?.sunrise)
        assertEquals(order, order.sorted(), "$what: astronomical order")
        // The displayed order, on the instants and on the minutes, into the next day's Fajr.
        val shown = today.day.events.map { checkNotNull(it.at) } + checkNotNull(tomorrow.day[TimeEvent.FAJR].at)
        assertEquals(shown, shown.sorted(), "$what: displayed order on the minutes")
        val unrounded = today.events.map { checkNotNull(it.unrounded) } + checkNotNull(tomorrow.events[0].unrounded)
        assertEquals(unrounded, unrounded.sorted(), "$what: displayed order on the instants")
        // The night's lowest point separates the two prayer days, before rounding.
        val lowest: Instant = checkNotNull(today.nightAfter).lowestPoint
        assertTrue(today.events[TimeEvent.MAGHRIB.ordinal].unrounded!! <= lowest && today.events[TimeEvent.ISHA.ordinal].unrounded!! <= lowest, "$what: evening after the lowest point")
        assertTrue(tomorrow.events[TimeEvent.FAJR.ordinal].unrounded!! >= lowest && tomorrow.events[TimeEvent.SUNRISE.ordinal].unrounded!! >= lowest, "$what: morning before the lowest point")
        assertTrue(today.day.events.none { it.status == EventStatus.ABSENT })
    }
}

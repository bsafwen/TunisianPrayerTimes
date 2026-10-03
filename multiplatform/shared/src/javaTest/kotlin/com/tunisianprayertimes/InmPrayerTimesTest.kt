package com.tunisianprayertimes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InmPrayerTimesTest {

    private val params = checkNotNull(javaClass.getResource("/delegation_params.json")) {
        "missing test resource /delegation_params.json"
    }.readText()
    private val source = InmPrayerTimes.fromParamsJson(params)
    private val tunis = 615

    @Test
    fun supportedYearsAreTheWholeRangeOffline() {
        assertFalse(source.hasPrayerData(tunis, 2019, 12))
        assertTrue(source.hasPrayerData(tunis, 2020, 1))
        assertTrue(source.hasPrayerData(tunis, 2027, 1))
        assertTrue(source.hasPrayerData(tunis, 2100, 12))
        assertFalse(source.hasPrayerData(tunis, 2101, 1))
        assertFalse(source.hasPrayerData(tunis, 2027, 0))
        assertFalse(source.hasPrayerData(tunis, 2027, 13))
    }

    @Test
    fun monthsHaveTheirCalendarLength() {
        assertEquals(29, source.loadPrayerTimes(tunis, 2028, 2).size)
        assertEquals(28, source.loadPrayerTimes(tunis, 2027, 2).size)
        assertEquals(31, source.loadPrayerTimes(tunis, 2027, 1).size)
        assertEquals((1..30).toList(), source.loadPrayerTimes(tunis, 2027, 4).map { it.day })
    }

    @Test
    fun daysMatchTheFormula() {
        val location = InmLocation.parseAll(params).getValue(tunis)
        assertEquals(InmPrayerFormula.dayPrayerTimes(location, 2027, 3, 9), source.loadDayPrayerTimes(tunis, 2027, 3, 9))
        assertEquals(source.loadPrayerTimes(tunis, 2027, 3)[8], source.loadDayPrayerTimes(tunis, 2027, 3, 9))
    }

    @Test
    fun invalidDaysAndUnknownDelegationsHaveNoTimes() {
        assertNull(source.loadDayPrayerTimes(tunis, 2027, 2, 29))
        assertNull(source.loadDayPrayerTimes(tunis, 2027, 1, 0))
        assertNull(source.loadDayPrayerTimes(-1, 2027, 1, 1))
        assertTrue(source.loadPrayerTimes(-1, 2027, 1).isEmpty())
        assertFalse(source.hasDelegation(-1))
        assertTrue(source.hasDelegation(tunis))
    }
}

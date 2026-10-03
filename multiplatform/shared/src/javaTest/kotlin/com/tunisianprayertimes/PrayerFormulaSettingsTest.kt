package com.tunisianprayertimes

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A mosque's own values for the formula; INM's stay the default and reproduce the official times. */
class PrayerFormulaSettingsTest {
    private val params = checkNotNull(javaClass.getResource("/delegation_params.json")).readText()
    private val tunis = InmLocation.parseAll(params).getValue(615)

    private fun hhmm(hour: Int, minute: Int) = "%02d:%02d".format(hour, minute)

    private fun DayPrayerTimes.columns() = listOf(
        hhmm(fajr.hour, fajr.minute), hhmm(shurukHour, shurukMinute), hhmm(dhuhr.hour, dhuhr.minute),
        hhmm(asr.hour, asr.minute), hhmm(maghrib.hour, maghrib.minute), hhmm(isha.hour, isha.minute),
    ).joinToString(" ")

    /** Tunis on 2026-09-30 (the dashboard mockups' JS gives the same minutes). */
    private fun tunis(settings: PrayerFormulaSettings) = InmPrayerFormula.dayPrayerTimes(tunis, 2026, 9, 30, settings).columns()

    @Test
    fun tunisIsTheDelegationThePinnedTimesAssume() {
        assertEquals(InmLocation(36.8, 10.183, 9.0), tunis)
    }

    @Test
    fun officialSettingsGiveTodaysTimes() {
        assertEquals("04:47 06:13 12:16 15:31 18:07 19:31", tunis(PrayerFormulaSettings.OFFICIAL))
        assertEquals(InmPrayerFormula.minutes(tunis, 2026, 9, 30), InmPrayerFormula.minutes(tunis, 2026, 9, 30, PrayerFormulaSettings()))
    }

    @Test
    fun officialSettingsReproduceTunisWholeMeteoTn2026Table() {
        val csvRoot = File(checkNotNull(System.getProperty("tunisianprayertimes.docsCsv")))
        var days = 0
        for (month in 1..12) {
            for (line in File(csvRoot, "615/2026/%02d.csv".format(month)).readLines().drop(1).filter { it.isNotBlank() }) {
                val cells = line.split(",")
                val computed = InmPrayerFormula.dayPrayerTimes(tunis, 2026, month, cells[0].toInt(), PrayerFormulaSettings.OFFICIAL)
                assertEquals(cells.drop(1).joinToString(" "), computed.columns(), "2026-$month-${cells[0]}")
                days++
            }
        }
        assertEquals(365, days)
    }

    @Test
    fun customValuesMoveOnlyTheirPrayers() {
        assertEquals("04:57 06:13 12:16 15:31 18:07 19:31", tunis(PrayerFormulaSettings(fajrAngle = 16.0)))
        assertEquals("04:47 06:13 12:16 16:22 18:07 19:31", tunis(PrayerFormulaSettings(asrShadow = 2)))
        assertEquals("04:47 06:14 12:16 15:31 18:06 19:30", tunis(PrayerFormulaSettings(elevation = false)))
        assertEquals(
            "04:58 06:14 12:16 16:22 18:06 19:32",
            tunis(PrayerFormulaSettings(fajrAngle = 16.0, asrShadow = 2, elevation = false, adjustments = mapOf(Prayer.ISHA to 2))),
        )
        assertEquals(
            "04:50 06:13 12:15 15:31 18:08 19:33",
            tunis(PrayerFormulaSettings(fajrAngle = 17.5, ishaAngle = 18.5, dhuhrMinutes = 6, maghribMinutes = 3)),
        )
    }

    @Test
    fun adjustmentsAddWholeMinutesButNeverMoveTheSunrise() {
        val adjusted = PrayerFormulaSettings(
            adjustments = mapOf(Prayer.FAJR to -1, Prayer.DHUHR to 15, Prayer.ASR to -15, Prayer.MAGHRIB to 3, Prayer.ISHA to 2),
        )
        assertEquals("04:46 06:13 12:31 15:16 18:10 19:33", tunis(adjusted))
    }

    @Test
    fun withoutElevationTheSunriseQuirkGoesToo() {
        val zeriba = InmLocation.parseAll(params).getValue(409)
        val flat = PrayerFormulaSettings(elevation = false)
        assertEquals(
            InmPrayerFormula.dayPrayerTimes(zeriba.copy(elevationM = 0.0, sunriseElevationOverrides = emptyMap()), 2026, 1, 1),
            InmPrayerFormula.dayPrayerTimes(zeriba, 2026, 1, 1, flat),
        )
    }

    @Test
    fun zeroAdjustmentsAreLeftOutSoEqualSettingsAreEqual() {
        val moved = PrayerFormulaSettings.OFFICIAL.withAdjustment(Prayer.ISHA, 2)
        assertEquals(mapOf(Prayer.ISHA to 2), moved.adjustments)
        assertEquals(2, moved.adjustment(Prayer.ISHA))
        assertEquals(0, moved.adjustment(Prayer.FAJR))
        assertFalse(moved.isOfficial)
        assertEquals(PrayerFormulaSettings.OFFICIAL, moved.withAdjustment(Prayer.ISHA, 0))
        assertTrue(moved.withAdjustment(Prayer.ISHA, 0).isOfficial)
        assertFailsWith<IllegalArgumentException> { PrayerFormulaSettings(adjustments = mapOf(Prayer.FAJR to 0)) }
        assertFailsWith<IllegalArgumentException> { PrayerFormulaSettings(adjustments = mapOf(Prayer.JOMOAA to 2)) }
    }

    @Test
    fun rangesAreTheOnesTheFileAndDashboardAccept() {
        assertTrue(PrayerFormulaSettings.OFFICIAL.isInRange)
        assertTrue(PrayerFormulaSettings(fajrAngle = 15.0, ishaAngle = 20.0, asrShadow = 2, dhuhrMinutes = 0, maghribMinutes = 10).isInRange)
        assertTrue(PrayerFormulaSettings.angleAccepted(17.5))
        assertFalse(PrayerFormulaSettings.angleAccepted(17.3))
        assertFalse(PrayerFormulaSettings.angleAccepted(14.5))
        assertFalse(PrayerFormulaSettings.angleAccepted(20.5))
        assertFalse(PrayerFormulaSettings(asrShadow = 3).isInRange)
        assertFalse(PrayerFormulaSettings(dhuhrMinutes = 16).isInRange)
        assertFalse(PrayerFormulaSettings(maghribMinutes = 11).isInRange)
        assertFalse(PrayerFormulaSettings(adjustments = mapOf(Prayer.FAJR to -16)).isInRange)
        assertEquals(listOf(Prayer.FAJR, Prayer.DHUHR, Prayer.ASR, Prayer.MAGHRIB, Prayer.ISHA), PrayerFormulaSettings.ADJUSTABLE)
    }

    @Test
    fun inmPrayerTimesComputesWithTheSettingsAndGivesTheLocation() {
        val source = InmPrayerTimes.fromParamsJson(params)
        val hanafi = PrayerFormulaSettings(asrShadow = 2)
        assertEquals("16:22", source.loadDayPrayerTimes(615, 2026, 9, 30, hanafi)!!.asr.let { hhmm(it.hour, it.minute) })
        assertEquals("15:31", source.loadDayPrayerTimes(615, 2026, 9, 30)!!.asr.let { hhmm(it.hour, it.minute) })
        assertEquals(source.loadDayPrayerTimes(615, 2026, 9, 30, hanafi), source.loadPrayerTimes(615, 2026, 9, hanafi)[29])
        assertEquals(tunis, source.location(615))
        assertNull(source.location(-1))
    }
}

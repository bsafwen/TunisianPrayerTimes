package com.tunisianprayertimes

import java.time.LocalDate
import java.time.chrono.HijrahDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Same JSON fixture asserted byte-for-value against the Python publisher's output. */
class OfficialDateContractTest {
    private lateinit var environment: OfficialDateTestEnvironment

    @BeforeTest fun setup() { environment = OfficialDateTestEnvironment() }
    @AfterTest fun cleanup() { environment.close() }

    private fun fixture(): String = checkNotNull(
        javaClass.getResourceAsStream("/islamic-calendar/1447.json"),
    ).bufferedReader().use { it.readText() }

    @Test
    fun publishedJsonKeepsCalendarAndRamadanAndBothEidsConsistentAfterRestart() {
        OfficialIslamicDates.importJson(fixture())
        assertPublishedDates()
        environment.restart()
        OfficialIslamicDates.loadCachedDates()
        assertPublishedDates()
    }

    private fun assertPublishedDates() {
        val record = assertNotNull(OfficialIslamicDates.cachedForYear(1447))
        assertEquals("2026-02-18T20:00:00Z", record.ramadanStartUpdated)
        assertEquals("2026-03-19T20:00:00Z", record.eidFitrUpdated)
        assertEquals("2026-05-17T20:00:00Z", record.eidAdhaUpdated)
        val calendar = OfficialIslamicDates.calendar()
        val ramadan = LocalDate.of(2026, 2, 19)
        val fitr = LocalDate.of(2026, 3, 20)
        val adha = LocalDate.of(2026, 5, 27)
        assertEquals(HijriCalendarDate(1447, 9, 1, false), calendar.date(ramadan))
        assertEquals(HijriCalendarDate(1447, 9, 29, false), calendar.date(fitr.minusDays(1)))
        assertEquals(HijriCalendarDate(1447, 10, 1, false), calendar.date(fitr))
        assertEquals(HijriCalendarDate(1447, 12, 10, false), calendar.date(adha))
        assertEquals(fitr, RamadanOverrideChecker.getEidFitrDate(1447))
        assertEquals(adha, RamadanOverrideChecker.getEidAdhaDate(1447))
        assertTrue(RamadanOverrideChecker.isEidFitr(fitr))
        assertFalse(RamadanOverrideChecker.isEidFitr(fitr.minusDays(1)))
        assertTrue(RamadanOverrideChecker.isEidAdha(adha))
        assertFalse(RamadanOverrideChecker.isEidAdha(adha.plusDays(1)))
        // Banner buffers do not assign extra days to the calendar's Ramadan month.
        assertTrue(RamadanDetector.isRamadan(HijrahDate.from(ramadan.minusDays(1))))
        assertFalse(RamadanDetector.isRamadan(HijrahDate.from(ramadan.minusDays(2))))
        assertTrue(RamadanDetector.isRamadan(HijrahDate.from(fitr)))
        assertFalse(RamadanDetector.isRamadan(HijrahDate.from(fitr.plusDays(1))))
        assertTrue(RamadanOverrideChecker.shouldShowEidFitrPrayer(fitr, 11, 59, 12, 0, true))
        assertFalse(RamadanOverrideChecker.shouldShowEidFitrPrayer(fitr, 12, 0, 12, 0, true))
        assertTrue(RamadanOverrideChecker.shouldShowEidAdhaPrayer(adha, 11, 59, 12, 0, true))
        assertFalse(RamadanOverrideChecker.shouldShowEidAdhaPrayer(adha, 12, 0, 12, 0, true))
    }

    @Test
    fun rejectedFitrDoesNotStopPollingAndLaterValidAnnouncementCorrectsVisibleDate() {
        val invalid = fixture().replace("2026-03-20", "2026-03-19")
        OfficialIslamicDates.importJson(invalid)
        RamadanOverrideChecker.testDateOverride = LocalDate.of(2026, 3, 19)
        assertTrue(OfficialIslamicDates.calendar().month(1447, 10).isEstimated)
        assertTrue(RamadanOverrideChecker.shouldStartPolling())
        assertFalse(RamadanOverrideChecker.shouldStopPolling(
            assertNotNull(OfficialIslamicDates.cachedForYear(1447)),
        ))
        OfficialIslamicDates.importJson(fixture().replace("2026-03-19T20:00:00Z", "2026-03-19T21:00:00Z"))
        assertEquals(HijriCalendarDate(1447, 10, 1, false),
            OfficialIslamicDates.calendar().date(LocalDate.of(2026, 3, 20)))
        assertFalse(RamadanOverrideChecker.shouldStartPolling())
        assertTrue(RamadanOverrideChecker.shouldStopPolling(
            assertNotNull(OfficialIslamicDates.cachedForYear(1447)),
        ))
    }

    @Test
    fun missingFitrDoesNotBlockAdhaPollingAndPartialAdhaResponseSatisfiesIt() {
        val partial = """{"hijriYear":1447,"ramadanStart":"2026-02-19","lastUpdated":"2026-02-18T20:00:00Z"}"""
        OfficialIslamicDates.importJson(partial)
        RamadanOverrideChecker.testDateOverride = LocalDate.of(2026, 5, 20)
        assertTrue(RamadanOverrideChecker.shouldStartPolling())
        OfficialIslamicDates.importJson("""{"hijriYear":1447,"eidAdhaDate":"2026-05-27","lastUpdated":"2026-05-17T20:00:00Z"}""")
        assertFalse(RamadanOverrideChecker.shouldStartPolling())
        assertEquals(LocalDate.of(2026, 2, 19), OfficialIslamicDates.cachedForYear(1447)?.ramadanStart)
        assertEquals(HijriCalendarDate(1447, 12, 10, false),
            OfficialIslamicDates.calendar().date(LocalDate.of(2026, 5, 27)))
    }
}

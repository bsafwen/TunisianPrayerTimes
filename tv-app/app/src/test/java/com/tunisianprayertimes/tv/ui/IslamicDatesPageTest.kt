package com.tunisianprayertimes.tv.ui

import com.tunisianprayertimes.DateSource
import com.tunisianprayertimes.EventDate
import com.tunisianprayertimes.YearDates
import com.tunisianprayertimes.tv.ui.kiosk.HealthLevel
import com.tunisianprayertimes.tv.ui.kiosk.HealthRow
import com.tunisianprayertimes.tv.ui.settings.DateStep
import com.tunisianprayertimes.tv.ui.settings.EstimatedDates
import com.tunisianprayertimes.tv.ui.settings.stepDate
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Ramadan and Eid dates page: what a press saves, and when an estimate is brought to the admin. */
class IslamicDatesPageTest {

    private val ramadan = LocalDate.of(2027, 2, 8)
    private fun estimate(date: LocalDate = ramadan) = EventDate(date, DateSource.ESTIMATE, withoutManual = ramadan, announced = false)
    private fun manual(date: LocalDate) = EventDate(date, DateSource.MANUAL, withoutManual = ramadan, announced = false)

    // Where the date goes once the admin's own is cleared: here the estimate itself.
    private val onEstimate = { ramadan }

    @Test
    fun aPressMovesTheDateAndBackOntoTheAutomaticDateIsAutomaticAgain() {
        assertEquals(DateStep(ramadan.minusDays(1)), stepDate(estimate(), manual = null, days = -1, onEstimate))
        // + then −: back on the estimate, so a later announcement still applies.
        assertEquals(DateStep(null), stepDate(manual(ramadan.minusDays(1)), manual = ramadan.minusDays(1), days = 1, onEstimate))
        // Three days either side at most.
        assertEquals(DateStep(ramadan.plusDays(3)), stepDate(manual(ramadan.plusDays(2)), manual = ramadan.plusDays(2), days = 1, onEstimate))
        assertNull(stepDate(manual(ramadan.plusDays(3)), manual = ramadan.plusDays(3), days = 1, onEstimate))
        assertNull(stepDate(manual(ramadan.minusDays(3)), manual = ramadan.minusDays(3), days = -1, onEstimate))
    }

    @Test
    fun aDateSetFurtherOffFromThePhoneStepsBackTowardTheEstimate() {
        // The phone and a key allow five days: from there, only a step back is taken.
        val far = ramadan.plusDays(5)
        assertEquals(DateStep(ramadan.plusDays(4)), stepDate(manual(far), manual = far, days = -1, onEstimate))
        assertNull(stepDate(manual(far), manual = far, days = 1, onEstimate))
        assertEquals(DateStep(ramadan.minusDays(4)), stepDate(manual(ramadan.minusDays(5)), manual = ramadan.minusDays(5), days = 1, onEstimate))
        assertEquals(DateStep(ramadan.plusDays(3)), stepDate(manual(ramadan.plusDays(4)), manual = ramadan.plusDays(4), days = -1, onEstimate))
    }

    @Test
    fun backToAutomaticIsWhereTheOtherDatesPutTheEvent() {
        // The admin's Ramadan a day late moved the estimated Eid with it: the Eid's automatic date is a day late too.
        val eid = LocalDate.of(2027, 3, 10)
        val moved = { eid.plusDays(1) }
        val row = EventDate(eid.plusDays(2), DateSource.MANUAL, withoutManual = eid, announced = false)
        // Onto the estimate is still the admin's date: cleared, the Eid would stay a day late.
        assertEquals(DateStep(eid), stepDate(row.copy(date = eid.plusDays(1)), manual = eid.plusDays(1), days = -1, moved))
        // Onto where the other dates put it is automatic.
        assertEquals(DateStep(null), stepDate(row, manual = eid.plusDays(2), days = -1, moved))
    }

    @Test
    fun aPressStepsFromTheAdminsOwnDate() {
        // An admin's date the calendar could not keep: the row shows the estimate, the press moves the admin's date.
        assertEquals(DateStep(ramadan.plusDays(3)), stepDate(estimate(), manual = ramadan.plusDays(2), days = 1, onEstimate))
        // Without one, a date the other dates moved is kept by hand even on the automatic date.
        assertEquals(DateStep(ramadan), stepDate(estimate(ramadan.plusDays(1)), manual = null, days = -1, onEstimate))
    }

    @Test
    fun anEstimateIsBroughtToTheAdminFromThreeDaysBefore() {
        assertFalse(EstimatedDates.isAhead(ramadan.minusDays(4), estimate()))
        assertTrue(EstimatedDates.isAhead(ramadan.minusDays(3), estimate()))
        assertTrue(EstimatedDates.isAhead(ramadan, estimate()))
        assertFalse(EstimatedDates.isAhead(ramadan.plusDays(1), estimate()))
        // An announced or confirmed date is not.
        assertFalse(EstimatedDates.isAhead(ramadan, estimate().copy(source = DateSource.OFFICIAL)))
        assertFalse(EstimatedDates.isAhead(ramadan, manual(ramadan)))

        val fitr = EventDate(LocalDate.of(2027, 3, 9), DateSource.ESTIMATE, LocalDate.of(2027, 3, 9), announced = false)
        val adha = EventDate(LocalDate.of(2027, 5, 16), DateSource.ESTIMATE, LocalDate.of(2027, 5, 16), announced = false)
        val year = YearDates(1448, estimate(), fitr, adha)
        assertEquals(TvStrings.RAMADAN_START to estimate(), EstimatedDates.ahead(ramadan.minusDays(1), year))
        assertEquals(TvStrings.EID_FITR to fitr, EstimatedDates.ahead(LocalDate.of(2027, 3, 7), year))
        assertNull(EstimatedDates.ahead(LocalDate.of(2027, 4, 1), year))
        // Online, the announcement is on its way: nothing to confirm.
        assertNull(EstimatedDates.pending(ramadan, online = true))

        assertEquals(emptyList<HealthRow>(), EstimatedDates.rows(null))
        val row = EstimatedDates.rows(TvStrings.EID_FITR to fitr).single()
        assertEquals(HealthLevel.WARNING, row.level)
        assertEquals("تاريخ عيد الفطر (${TvStrings.gregorianDate(fitr.date)}) تقديري: الشاشة لم تتلقَّ الإعلان الرسمي", row.text)
        assertEquals(TvStrings.ESTIMATED_DATE_FIX, row.fix)
        assertEquals("تاريخ عيد الفطر تقديري: الإعدادات ← رمضان والعيد", TvStrings.estimatedDateMark(TvStrings.EID_FITR))
    }
}

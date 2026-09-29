package com.tunisianprayertimes

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IslamicDaysTest {

    // Tunisia's announcements for 1447: Ramadan from 2026-02-19, Eid al-Fitr 2026-03-20, Eid al-Adha 2026-05-27.
    private val announced1447 = mapOf(
        1447 to RamadanOverrideChecker.RamadanOverride(
            1447, LocalDate.of(2026, 2, 19), LocalDate.of(2026, 3, 20), LocalDate.of(2026, 5, 27),
        )
    )
    private val official = TunisianHijriCalendar(announced1447)

    private fun day(iso: String, calendar: TunisianHijriCalendar = official) = IslamicDays.of(LocalDate.parse(iso), calendar)

    @Test
    fun ramadanIsExactlyTheAnnouncedMonth() {
        assertFalse(day("2026-02-18").isRamadan, "the eve is still Sha'ban")
        assertEquals(1, day("2026-02-19").ramadanDay)
        assertEquals(29, day("2026-03-19").ramadanDay)
        assertEquals(20, day("2026-03-10").ramadanDay)
        assertFalse(day("2026-03-10").isLastTenDays)
        assertTrue(day("2026-03-11").isLastTenDays)
        assertNull(day("2026-03-20").ramadanDay, "Eid al-Fitr is not a day of Ramadan")
    }

    @Test
    fun eidAndArafahFallOnTheirDays() {
        assertTrue(day("2026-03-20").isEidFitr)
        assertFalse(day("2026-03-21").isEidFitr)
        assertTrue(day("2026-05-26").isArafah)
        assertTrue(day("2026-05-27").isEidAdha)
        assertFalse(day("2026-05-28").isEid)
    }

    @Test
    fun theAdminsDatesWinOverTheAnnouncement() {
        // The mosque celebrates Eid al-Fitr a day later than announced.
        val manual = mapOf(1447 to ManualIslamicDates(eidFitr = LocalDate.of(2026, 3, 21)))
        val merged = TunisianHijriCalendar(withManualDates(announced1447, manual))
        assertEquals(30, day("2026-03-20", merged).ramadanDay)
        assertTrue(day("2026-03-21", merged).isEidFitr)
        assertEquals(LocalDate.of(2026, 2, 19), merged.month(1447, 9).start, "untouched fields keep the announcement")
    }

    @Test
    fun anOfflineTvWithoutAnnouncementsCanSetTheDates() {
        // 1448 has no bundled announcement: the admin sets Ramadan by hand.
        val estimate = TunisianHijriCalendar()
        val manual = mapOf(1448 to ManualIslamicDates(ramadanStart = estimate.month(1448, 9).start.plusDays(1)))
        val merged = TunisianHijriCalendar(withManualDates(emptyMap(), manual))
        assertEquals(estimate.month(1448, 9).start.plusDays(1), merged.month(1448, 9).start)
    }

    @Test
    fun yearDatesTellWhereEachDateComesFrom() {
        val manual = ManualIslamicDates(eidFitr = LocalDate.of(2026, 3, 21))
        val merged = TunisianHijriCalendar(withManualDates(announced1447, mapOf(1447 to manual)))
        val dates = IslamicDays.yearDates(1447, merged, official, manual)
        assertEquals(DateSource.OFFICIAL, dates.ramadanStart.source)
        assertEquals(LocalDate.of(2026, 2, 19), dates.ramadanStart.date)
        assertEquals(DateSource.MANUAL, dates.eidFitr.source)
        assertEquals(LocalDate.of(2026, 3, 21), dates.eidFitr.date)
        assertEquals(LocalDate.of(2026, 3, 20), dates.eidFitr.withoutManual)
        assertTrue(dates.eidFitr.conflictsWithAnnouncement)
        assertEquals(LocalDate.of(2026, 5, 27), dates.eidAdha.date)

        val unannounced = IslamicDays.yearDates(1448, TunisianHijriCalendar(), TunisianHijriCalendar(), ManualIslamicDates())
        assertEquals(DateSource.ESTIMATE, unannounced.ramadanStart.source)
        assertFalse(unannounced.ramadanStart.conflictsWithAnnouncement)
    }

    @Test
    fun theUpcomingYearTurnsAfterEidAlAdha() {
        assertEquals(1447, IslamicDays.upcomingYear(LocalDate.of(2026, 5, 27), official))
        assertEquals(1448, IslamicDays.upcomingYear(LocalDate.of(2026, 5, 28), official))
        assertEquals(1448, IslamicDays.upcomingYear(LocalDate.of(2026, 9, 29), official))
    }

    @Test
    fun manualDatesPersistAndClear() {
        var saved: String? = null
        val store = ManualIslamicDateStore(read = { saved }, write = { saved = it })
        store.set(1448, ManualIslamicDates(ramadanStart = LocalDate.of(2027, 2, 8), eidAdha = LocalDate.of(2027, 5, 16)))
        val reloaded = ManualIslamicDateStore(read = { saved }, write = { saved = it })
        assertEquals(ManualIslamicDates(ramadanStart = LocalDate.of(2027, 2, 8), eidAdha = LocalDate.of(2027, 5, 16)), reloaded.all()[1448])
        reloaded.set(1448, ManualIslamicDates())
        assertNull(saved, "back to automatic leaves nothing stored")
        assertEquals(emptyMap(), ManualIslamicDateStore.decode("not json"))
    }

    @Test
    fun hijriLabelsUseTunisianMonthNames() {
        assertEquals("ربيع الثاني 1448 هـ", HijriLabels.monthLabel(1448, 4))
        assertEquals("1 رمضان 1447 هـ", HijriLabels.dateLabel(official.date(LocalDate.of(2026, 2, 19))))
    }
}

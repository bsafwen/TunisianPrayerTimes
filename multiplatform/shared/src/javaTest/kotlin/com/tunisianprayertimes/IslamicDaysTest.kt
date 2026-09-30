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
        val merged = TunisianHijriCalendar(announced1447, manual)
        assertEquals(30, day("2026-03-20", merged).ramadanDay)
        assertTrue(day("2026-03-21", merged).isEidFitr)
        assertEquals(LocalDate.of(2026, 2, 19), merged.month(1447, 9).start, "untouched fields keep the announcement")
    }

    @Test
    fun anOfflineTvWithoutAnnouncementsCanSetTheDates() {
        // 1448 has no bundled announcement: the admin sets Ramadan by hand.
        val estimate = TunisianHijriCalendar()
        val manual = mapOf(1448 to ManualIslamicDates(ramadanStart = estimate.month(1448, 9).start.plusDays(1)))
        val merged = TunisianHijriCalendar(emptyMap(), manual)
        assertEquals(estimate.month(1448, 9).start.plusDays(1), merged.month(1448, 9).start)
    }

    @Test
    fun yearDatesTellWhereEachDateComesFrom() {
        val manual = ManualIslamicDates(eidFitr = LocalDate.of(2026, 3, 21))
        val merged = TunisianHijriCalendar(announced1447, mapOf(1447 to manual))
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
    fun theUpcomingYearTurnsOnceEidAlAdhaCanNoLongerBeMoved() {
        // Eid al-Adha 1447 on 2026-05-27: it may still be moved to the 30th, so the year stays until the 31st.
        assertEquals(1447, IslamicDays.upcomingYear(LocalDate.of(2026, 5, 27), official))
        assertEquals(1447, IslamicDays.upcomingYear(LocalDate.of(2026, 5, 28), official))
        assertEquals(1447, IslamicDays.upcomingYear(LocalDate.of(2026, 5, 31), official))
        assertEquals(1448, IslamicDays.upcomingYear(LocalDate.of(2026, 6, 1), official))
        assertEquals(1448, IslamicDays.upcomingYear(LocalDate.of(2026, 9, 29), official))
    }

    /** Runs [block] on the app's own stores: Tunisia's announcements for 1447, and the admin's [manual] dates. */
    private fun withStores(manual: Map<Int, ManualIslamicDates>, block: () -> Unit) {
        val previousManual = ManualIslamicDateOverrides.store
        val previousOfficial = OfficialIslamicDates.store
        var saved: String? = ManualIslamicDateStore.encode(manual)
        try {
            ManualIslamicDateOverrides.store = ManualIslamicDateStore(read = { saved }, write = { saved = it })
            OfficialIslamicDates.store = OfficialIslamicDateStore(
                readSavedYear = { null },
                writeSavedYear = { _, _ -> },
                readLegacy = { null },
                fetchYear = { throw AssertionError("This test must not fetch from the network") },
                nanoTime = { 0L },
                currentHijriYear = { 1447 },
                onRecord = {},
                legacyOverride = { null },
                manual = { ManualIslamicDateOverrides.all() },
            ).apply { record(announced1447.getValue(1447)) }
            block()
        } finally {
            ManualIslamicDateOverrides.store = previousManual
            OfficialIslamicDates.store = previousOfficial
        }
    }

    @Test
    fun theAdminsOwnEidAlAdhaNeverTurnsThePageToNextYear() {
        // A press of − too many moved Eid al-Adha (announced for 2026-05-27) three days back, before today.
        withStores(mapOf(1447 to ManualIslamicDates(eidAdha = LocalDate.of(2026, 5, 24)))) {
            assertTrue(IslamicDays.of(LocalDate.of(2026, 5, 24)).isEidAdha, "the calendar follows the admin")
            assertEquals(1447, IslamicDays.upcomingYear(LocalDate.of(2026, 5, 25)))
            // Days after the admin's Eid the page still shows this year, so + can put it back.
            assertEquals(1447, IslamicDays.upcomingYear(LocalDate.of(2026, 5, 29)))
            assertEquals(1447, IslamicDays.upcomingYear(LocalDate.of(2026, 5, 31)))
            assertEquals(1448, IslamicDays.upcomingYear(LocalDate.of(2026, 6, 1)))
        }
    }

    @Test
    fun proposedDatesAreResolvedBeforeTheyAreSaved() {
        // 1448 has no announcement: a Ramadan a day late moves the estimated Eid al-Fitr with it.
        val estimate = TunisianHijriCalendar()
        val ramadan = estimate.month(1448, 9).start.plusDays(1)
        withStores(emptyMap()) {
            val proposed = IslamicDays.yearDates(1448, ManualIslamicDates(ramadanStart = ramadan))
            assertEquals(DateSource.MANUAL, proposed.ramadanStart.source)
            assertEquals(estimate.month(1448, 10).start.plusDays(1), proposed.eidFitr.date)
            assertEquals(DateSource.ESTIMATE, proposed.eidFitr.source)
            assertEquals(estimate.month(1448, 10).start, IslamicDays.yearDates(1448).eidFitr.date, "nothing was saved")
        }
    }

    @Test
    fun aManualDateOfAnotherEventKeepsTheAnnouncedOnes() {
        // Tunisia announced a 30-day Ramadan (Eid on 2026-03-21); the mosque, following its own sighting, set Eid a day later.
        val announced = mapOf(1447 to RamadanOverrideChecker.RamadanOverride(
            1447, LocalDate.of(2026, 2, 19), LocalDate.of(2026, 3, 21), LocalDate.of(2026, 5, 27),
        ))
        val manual = ManualIslamicDates(eidFitr = LocalDate.of(2026, 3, 22))
        val merged = TunisianHijriCalendar(announced, mapOf(1447 to manual))
        assertTrue(day("2026-03-22", merged).isEidFitr, "the admin's Eid wins")
        assertEquals(LocalDate.of(2026, 2, 19), merged.month(1447, 9).start, "the announced Ramadan keeps its day")
        assertEquals(31, merged.month(1447, 9).lengthOfMonth)
        val dates = IslamicDays.yearDates(1447, merged, TunisianHijriCalendar(announced), manual)
        assertEquals(DateSource.MANUAL, dates.eidFitr.source)
        assertEquals(LocalDate.of(2026, 3, 22), dates.eidFitr.date)
        assertEquals(DateSource.OFFICIAL, dates.ramadanStart.source)
    }

    @Test
    fun aManualEidAlFitrDoesNotPushTheAnnouncedEidAlAdha() {
        // Announced: Eid al-Fitr 2026-03-20 and Eid al-Adha 2026-05-26, so Shawwal and Dhu al-Qi'dah have 29 days each.
        val announced = mapOf(1447 to RamadanOverrideChecker.RamadanOverride(
            1447, LocalDate.of(2026, 2, 19), LocalDate.of(2026, 3, 20), LocalDate.of(2026, 5, 26),
        ))
        // The mosque fasted 30 days and celebrated Eid al-Fitr a day after the nation.
        val manual = ManualIslamicDates(eidFitr = LocalDate.of(2026, 3, 21))
        val merged = TunisianHijriCalendar(announced, mapOf(1447 to manual))
        assertTrue(day("2026-03-21", merged).isEidFitr)
        assertTrue(day("2026-05-25", merged).isArafah)
        assertTrue(day("2026-05-26", merged).isEidAdha, "Eid al-Adha stays on its announced day")
        assertFalse(merged.month(1447, 12).isEstimated)
        val dates = IslamicDays.yearDates(1447, merged, TunisianHijriCalendar(announced), manual)
        assertEquals(DateSource.OFFICIAL, dates.eidAdha.source)
        assertEquals(LocalDate.of(2026, 5, 26), dates.eidAdha.date)
    }

    @Test
    fun anAdminDateTheCalendarCannotKeepIsNotLabelledManual() {
        // Two dates of the admin 31 days apart: the earlier stays, the later is not the date shown.
        val manual = ManualIslamicDates(ramadanStart = LocalDate.of(2026, 2, 19), eidFitr = LocalDate.of(2026, 3, 22))
        val merged = TunisianHijriCalendar(emptyMap(), mapOf(1447 to manual))
        val dates = IslamicDays.yearDates(1447, merged, TunisianHijriCalendar(), manual)
        assertEquals(DateSource.MANUAL, dates.ramadanStart.source)
        assertEquals(DateSource.ESTIMATE, dates.eidFitr.source)
        assertTrue(dates.eidFitr.date != LocalDate.of(2026, 3, 22))
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
        assertEquals("جمادى الثانية 1448 هـ", HijriLabels.monthLabel(1448, 6))
        assertEquals("ذو الحجة 1447 هـ", HijriLabels.monthLabel(1447, 12))
        assertEquals("1 رمضان 1447 هـ", HijriLabels.dateLabel(official.date(LocalDate.of(2026, 2, 19))))
        // After the day's number the month is genitive.
        assertEquals("10 ذي الحجة 1447 هـ", HijriLabels.dateLabel(official.date(LocalDate.of(2026, 5, 27))))
        assertEquals("1 ذي القعدة 1447 هـ", HijriLabels.dateLabel(HijriCalendarDate(1447, 11, 1, false)))
    }
}

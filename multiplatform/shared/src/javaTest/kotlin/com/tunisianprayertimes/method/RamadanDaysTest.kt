package com.tunisianprayertimes.method

import com.tunisianprayertimes.engine.EngineTestSupport
import com.tunisianprayertimes.engine.PrayerEngine
import com.tunisianprayertimes.engine.TimeEvent
import java.time.Duration
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** E8's computed Ramadan days (plan 6.10, 9.6). */
class RamadanDaysTest {
    @Test
    fun ummAlQurasMonthNine() {
        // KACST's tables: 1 Ramadan 1447 = 2026-02-18 to 2026-03-19; 1 Ramadan 1448 = 2027-02-08.
        for (method in listOf(ArabAuthorityProfiles.SA_UMM_AL_QURA, ArabAuthorityProfiles.QA_AWQAF, ArabAuthorityProfiles.QA_QCH, PrayerMethods.UMM_AL_QURA)) {
            assertFalse(method.isRamadanDay(LocalDate.of(2026, 2, 17)), method.id)
            assertTrue(method.isRamadanDay(LocalDate.of(2026, 2, 18)), method.id)
            assertTrue(method.isRamadanDay(LocalDate.of(2026, 3, 19)), method.id)
            assertFalse(method.isRamadanDay(LocalDate.of(2026, 3, 20)), method.id)
            assertFalse(method.isRamadanDay(LocalDate.of(2027, 2, 7)), method.id)
            assertTrue(method.isRamadanDay(LocalDate.of(2027, 2, 8)), method.id)
        }
        // A method without Ramadan rules never asks the calendar.
        assertFalse(PrayerMethods.MWL.isRamadanDay(LocalDate.of(2026, 3, 1)))
        assertFalse(PrayerMethods.byId("diyanet_eu")!!.isRamadanDay(LocalDate.of(2026, 3, 1)))
    }

    @Test
    fun theRamadanIshaAcrossTheDateLine() {
        // Plan 9.6: `umm_al_qura` at Mata-Utu, Isha at Maghrib + 90 on 2026-02-17 and + 120 from 2026-02-18.
        val mataUtu = EngineTestSupport.place(-13.2825, -176.1736, "Pacific/Wallis", "WF")
        val method = PrayerMethods.UMM_AL_QURA
        for ((date, expected) in listOf(LocalDate.of(2026, 2, 17) to 90L, LocalDate.of(2026, 2, 18) to 120L)) {
            val day = PrayerEngine.day(mataUtu, date, method, ramadanDay = method.isRamadanDay(date))
            assertEquals(expected, Duration.between(day[TimeEvent.MAGHRIB].at, day[TimeEvent.ISHA].at).toMinutes(), "$date")
        }
        // Paris: the Ramadan Isha on the evenings of Umm al-Qura's Ramadan 1447.
        val paris = EngineTestSupport.place(48.8566, 2.3522, "Europe/Paris", "FR")
        val march = LocalDate.of(2026, 3, 1)
        val day = PrayerEngine.day(paris, march, method, ramadanDay = method.isRamadanDay(march))
        assertEquals(120L, Duration.between(day[TimeEvent.MAGHRIB].at, day[TimeEvent.ISHA].at).toMinutes())
    }
}

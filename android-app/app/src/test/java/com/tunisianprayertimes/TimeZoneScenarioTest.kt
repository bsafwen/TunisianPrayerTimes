package com.tunisianprayertimes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.engine.TimeEvent
import com.tunisianprayertimes.place.CityRef
import com.tunisianprayertimes.place.LatLngE5
import com.tunisianprayertimes.place.PrayerPlace
import com.tunisianprayertimes.place.ZoneSource
import com.tunisianprayertimes.ui.prayerDayOffset
import com.tunisianprayertimes.ui.prayerTimelineAnchors
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Plan 9.1's time-zone scenarios and 6.8's travel persistence: a place on a phone kept at another zone is shown in
 * the phone's clock and silenced at the place's instants; the same point with a new resolved zone is stored; a
 * skipped first run's place is confirmed by a fix.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class TimeZoneScenarioTest {
    private lateinit var context: Context
    private val phoneZone: TimeZone = TimeZone.getDefault()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("prayer_silence_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        PrefsManager.setEnabled(context, true)
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(phoneZone)
        PlaceCatalogue.setForTests(null)
    }

    @Test
    fun aTunisPlaceOnANewYorkPhoneShowsFajrTheEveningBeforeAndSilencesAtTunisInstants() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        val tunis = PrayerPlace.Inm(615)
        val date = LocalDate.of(2026, 6, 15)
        val fajr = prayerTimelineAnchors(context, date, tunis).getValue(Prayer.FAJR)
        assertEquals(ZoneId.of("America/New_York"), fajr.displayZone)
        // About 03:20 in Tunis is about 22:20 the evening before in New York: «قبل منتصف الليل».
        assertEquals(-1, prayerDayOffset(fajr.minutes))
        val adhan = requireNotNull(PrayerTimesRepository.prayerDay(context, date, tunis))[TimeEvent.FAJR].at
        assertEquals(adhan, fajr.adhan)
        val window = SilenceScheduler.planner(context, tunis).windowsOf(date).first { it.prayer == Prayer.FAJR }
        assertEquals(adhan, window.adhan)
    }

    @Test
    fun aMoscowPlaceOnAParisPhoneKeepsMoscowInstants() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"))
        val moscow = PrayerPlace.Coordinates(LatLngE5.of(55.7558, 37.6173).latE5, LatLngE5.of(55.7558, 37.6173).lngE5, null,
            "Europe/Moscow", ZoneSource.CATALOGUE, "RU")
        // A Wednesday: on a Friday the planner puts Jumu'a in Dhuhr's place (A18).
        val date = LocalDate.of(2026, 3, 18)
        val day = requireNotNull(PrayerTimesRepository.prayerDay(context, date, moscow))
        val dhuhr = prayerTimelineAnchors(context, date, moscow).getValue(Prayer.DHUHR)
        assertEquals(day[TimeEvent.DHUHR].at, dhuhr.adhan)
        // Moscow is one hour ahead of Paris in March before summer time: the axis shows Paris's clock.
        val moscowClock = requireNotNull(dhuhr.adhan).atZone(ZoneId.of("Europe/Moscow"))
        assertEquals(moscowClock.hour * 60 + moscowClock.minute - 120, dhuhr.minutes)
        val window = SilenceScheduler.planner(context, moscow).windowsOf(date).first { it.prayer == Prayer.DHUHR }
        assertEquals(day[TimeEvent.DHUHR].at, window.adhan)
    }

    @Test
    fun theSamePointWithANewCountryAndZoneIsStored() {
        // 6.8: Haparanda and Tornio, 1.5 km apart across the border; the network decides between them.
        val haparanda = PlaceCatalogue.Row(CityRef(2708365, LatLngE5.of(65.8355, 24.1368), "SE", "Europe/Stockholm", population = 4856), "Haparanda", "Haparanda", "هاباراندا", emptyList(), null)
        val tornio = PlaceCatalogue.Row(CityRef(634093, LatLngE5.of(65.8481, 24.1466), "FI", "Europe/Helsinki", population = 22000), "Tornio", "Tornio", "تورنيو", emptyList(), null)
        PlaceCatalogue.setForTests(PlaceCatalogue.of(listOf(haparanda, tornio)))
        PrefsManager.setRegionOpen(context, europe = true)
        val swedish = PlaceResolution.resolveAbroad(context, 65.84, 24.14, network = "se", phoneZone = "Europe/Stockholm")
        val inSweden = (swedish as DelegationLocationResult.Abroad).place
        assertEquals("SE", inSweden.countryCode)
        val finnish = PlaceResolution.resolveAbroad(context, 65.84, 24.14, network = "fi", phoneZone = "Europe/Helsinki")
        val inFinland = (finnish as DelegationLocationResult.Abroad).place
        assertEquals("FI", inFinland.countryCode)
        assertEquals("Europe/Helsinki", inFinland.zoneId)
        assertEquals(inSweden.point, inFinland.point)
        assertTrue(PlaceResolution.shouldStore(context, inSweden, inFinland))
        assertFalse(PlaceResolution.shouldStore(context, inFinland, inFinland))
    }

    @Test
    fun aFixConfirmsASkippedFirstRunsPlace() {
        PrefsManager.setDelegationId(context, 615)
        PrefsManager.setPlaceProvisional(context, true)
        val fix = DelegationLocator.resolveGpsLocation(context, 36.8065, 10.1815)
        assertTrue(fix is DelegationLocationResult.Success)
        PrefsManager.setGpsLocation(context, fix as DelegationLocationResult.Success)
        assertFalse(PrefsManager.isPlaceProvisional(context))
    }
}

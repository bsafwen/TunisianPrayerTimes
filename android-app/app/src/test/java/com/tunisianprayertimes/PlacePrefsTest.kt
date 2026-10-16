package com.tunisianprayertimes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.engine.TimeEvent
import com.tunisianprayertimes.method.PrayerMethodChoice
import com.tunisianprayertimes.place.LatLngE5
import com.tunisianprayertimes.place.PrayerPlace
import com.tunisianprayertimes.place.Region
import com.tunisianprayertimes.place.ZoneSource
import java.time.LocalDate
import java.time.LocalTime
import java.time.chrono.HijrahDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The place model, the per-country choice and Jumu'a time, the region switches and 6.10's Ramadan order. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class PlacePrefsTest {
    private lateinit var context: Context
    private val paris = PrayerPlace.Coordinates(
        LatLngE5.of(48.86, 2.35).latE5, LatLngE5.of(48.86, 2.35).lngE5, null, "Europe/Paris", ZoneSource.CATALOGUE, "FR", 2988507L, null, "75",
    )
    private val riyadh = PrayerPlace.Coordinates(
        LatLngE5.of(24.71, 46.68).latE5, LatLngE5.of(24.71, 46.68).lngE5, 612, "Asia/Riyadh", ZoneSource.DEVICE, "SA",
    )

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("prayer_silence_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun aPlaceAbroadRoundTripsAndADelegationReplacesIt() {
        assertEquals(PrayerPlace.Inm(615), PrefsManager.getPlace(context))
        PrefsManager.setPlace(context, paris, "قرب باريس", fromGps = true)
        assertEquals(paris, PrefsManager.getPlace(context))
        assertEquals("قرب باريس", PrefsManager.getPlaceNameSnapshot(context))
        // Storing a delegation brings the INM place back (6.8).
        PrefsManager.setDelegationId(context, 386)
        assertEquals(PrayerPlace.Inm(386), PrefsManager.getPlace(context))
        assertNull(PrefsManager.getPlaceNameSnapshot(context))
    }

    @Test
    fun choicesAndJumuaTimesArePerCountry() {
        PrefsManager.setMethodChoice(context, paris, PrayerMethodChoice("isna", adjustments = mapOf(TimeEvent.MAGHRIB to 3)))
        assertEquals("isna", PrefsManager.getMethodChoice(context, paris)?.methodId)
        assertNull(PrefsManager.getMethodChoice(context, riyadh))
        // The country's default until a choice is stored (6.4).
        PrefsManager.setPlace(context, riyadh, null, fromGps = true)
        assertEquals("sa_umm_al_qura", PrayerTimesRepository.choice(context)?.methodId)
        // An edited Jumu'a time is a mosque time: the home mosque's never applies in Mecca (6.9).
        PrefsManager.setJomoaaTime(context, 13, 0)
        PrefsManager.setJumuaTime(context, paris, LocalTime.of(14, 0))
        assertEquals(LocalTime.of(13, 0), PrefsManager.getJumuaTime(context, PrayerPlace.Inm(615)))
        assertEquals(LocalTime.of(14, 0), PrefsManager.getJumuaTime(context, paris))
        assertNull(PrefsManager.getJumuaTime(context, riyadh))
    }

    @Test
    fun regionsStayClosedUntilTheirMilestones() {
        assertTrue(PrefsManager.isRegionOpen(context, Region.TUNISIA))
        assertFalse(PrefsManager.isRegionOpen(context, Region.ARAB_LEAGUE))
        assertFalse(PrefsManager.isRegionOpen(context, Region.EUROPE))
        PrefsManager.setRegionOpen(context, arabLeague = true)
        assertTrue(PrefsManager.isRegionOpen(context, Region.ARAB_LEAGUE))
        assertFalse(PrefsManager.isRegionOpen(context, Region.EUROPE))
    }

    @Test
    fun theRamadanBumpIsTunisianAndItsRestoreIsEverywhere() {
        PrefsManager.setAfterMinutes(context, Prayer.ISHA, 40)
        // A place abroad neither writes the bump nor records the year (6.10, step 2).
        PrefsManager.applyRamadanIshaOverrideIfNeeded(context, HijrahDate.of(1448, 9, 10), paris)
        assertEquals(40, PrefsManager.getAfterMinutes(context, Prayer.ISHA))
        // Back in Tunisia the same Ramadan, it applies.
        PrefsManager.applyRamadanIshaOverrideIfNeeded(context, HijrahDate.of(1448, 9, 12), PrayerPlace.Inm(615))
        assertEquals(90, PrefsManager.getAfterMinutes(context, Prayer.ISHA))
        // Abroad while it is in force, Isha reads the user's own 40 (step 3); the stored 90 stays.
        assertEquals(40, PrefsManager.getConfig(context, Prayer.ISHA, paris).afterMinutes)
        assertEquals(90, PrefsManager.getConfig(context, Prayer.ISHA, PrayerPlace.Inm(615)).afterMinutes)
        // After that Ramadan the restore runs at every place, abroad included (step 1).
        PrefsManager.applyRamadanIshaOverrideIfNeeded(context, HijrahDate.of(1448, 10, 5), paris)
        assertEquals(40, PrefsManager.getAfterMinutes(context, Prayer.ISHA))
    }

    @Test
    fun aPlaceAbroadGetsEngineTimesAndNoEid() {
        PrefsManager.setPlace(context, riyadh, null, fromGps = true)
        val day = requireNotNull(PrayerTimesRepository.prayerDay(context, LocalDate.of(2026, 3, 20)))
        assertEquals("geo:" + riyadh.latE5 + "," + riyadh.lngE5 + ",Asia/Riyadh", day.placeKey)
        assertTrue(EidSilenceWindows.eidPrayers(context, riyadh, LocalDate.of(2026, 3, 20), day).isEmpty())
    }

    @Test
    fun theEditorsOpenOnTheDefaultsAndSaveOnlyWhatMoved() {
        // D12 in Tunisia: an unedited Dhuhr window opens as 12:40–13:15, both ends fixed.
        val tunisian = PrefsManager.getEffectiveConfig(context, Prayer.DHUHR, PrayerPlace.Inm(615))
        assertEquals(DelayMode.FIXED_TIME, tunisian.delayMode)
        assertEquals(12, tunisian.delayFixedHour)
        assertEquals(40, tunisian.delayFixedMinute)
        assertEquals(SilenceMode.FIXED_TIME, tunisian.mode)
        assertEquals(13, tunisian.fixedHour)
        assertEquals(15, tunisian.fixedMinute)
        // Abroad the same unedited window counts from the adhan (+5 to +45).
        val abroad = PrefsManager.getEffectiveConfig(context, Prayer.DHUHR, paris)
        assertEquals(DelayMode.MINUTES, abroad.delayMode)
        assertEquals(5, abroad.delayMinutes)
        assertEquals(45, abroad.endOffsetMinutes)

        // Moving only the end stores only the end, with its saved adhan offset (D16); the start keeps following D12.
        PrefsManager.setConfig(
            context, Prayer.DHUHR, tunisian.copy(fixedHour = 13, fixedMinute = 30),
            start = PrefsManager.EndpointSave.KEEP, end = PrefsManager.EndpointSave.WRITE, savedEndOffsetMinutes = 60,
        )
        val rule = PrefsManager.getSilenceRule(context, Prayer.DHUHR)
        assertEquals(com.tunisianprayertimes.silence.SilenceStart.Unset, rule.start)
        assertEquals(com.tunisianprayertimes.silence.SilenceEnd.Clock(LocalTime.of(13, 30), 60), rule.end)

        // An end moved back to its default follows the default again.
        PrefsManager.setConfig(context, Prayer.DHUHR, tunisian, start = PrefsManager.EndpointSave.KEEP, end = PrefsManager.EndpointSave.CLEAR)
        assertEquals(com.tunisianprayertimes.silence.SilenceEnd.Unset, PrefsManager.getSilenceRule(context, Prayer.DHUHR).end)
    }
}
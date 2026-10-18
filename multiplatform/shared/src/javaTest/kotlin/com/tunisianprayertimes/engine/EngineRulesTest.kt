package com.tunisianprayertimes.engine

import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.ALGERIA_LIKE
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.ALGIERS_ZONE
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.AMMAN
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.AMMAN_ZONE
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.BAHRAIN_EVENT_LIKE
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.BAHRAIN_LIKE
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.BAHRAIN_OWN_ZONE
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.BAHRAIN_ZONE
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.BEIRUT
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.BLIDA
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.BLIDA_ZONE
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.BOSNIA_LIKE
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.COMOROS_LIKE
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.CUSTOM_EARLY
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.CUSTOM_GMP
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.DAMASCUS
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.DAMASCUS_SCREEN_ZONE
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.DOHA
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.HASAKAH
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.HASAKAH_SCREEN_ZONE
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.IN_GUEZZAM
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.IRBID
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.IRBID_ZONE
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.JAHRA_ZONE
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.JORDAN_LIKE
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.KOSOVO_LIKE
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.KUWAIT_CITY
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.KUWAIT_CITY_ZONE
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.KUWAIT_JAHRA
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.KUWAIT_PRINTED
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.KUWAIT_PRINTED_CITY_ZONE
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.LEBANON_PRINTED
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.LJUBLJANA
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.LJUBLJANA_OWN_ZONE
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.LONDON
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.MANAMA
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.MAYADIN
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.MAYADIN_LIVE_ZONE
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.MECCA
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.MORONI
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.MOSTAR
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.MOSTAR_ZONE
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.MWL_CAPPED
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.PARIS
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.PRISTINA
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.QATAR_LIKE
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.QATAR_PRINTED
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.REUNION_LIKE
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.RIYADH
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.RIYADH_ANGLES_ZONE
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.SAINT_DENIS_RE
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.SANAA
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.SANAA_ZONE
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.SARAJEVO_ZONE
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.SAUDI_LIKE
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.SEIYUN
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.SEIYUN_ZONE
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.SYRIA_SCREENS
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.TRIPOLI
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.TUNIS
import com.tunisianprayertimes.engine.EngineReferenceCases.Companion.YEMEN_LIKE
import com.tunisianprayertimes.engine.EngineTestSupport.method
import com.tunisianprayertimes.engine.EngineTestSupport.place
import com.tunisianprayertimes.method.AsrRule
import com.tunisianprayertimes.method.AsrSun
import com.tunisianprayertimes.method.FajrRule
import com.tunisianprayertimes.method.IshaRule
import com.tunisianprayertimes.method.PrayerMethod
import com.tunisianprayertimes.method.PrayerMethods
import com.tunisianprayertimes.method.Rounding
import com.tunisianprayertimes.place.OfficialZone
import com.tunisianprayertimes.place.PrayerPlace
import com.tunisianprayertimes.place.TableRows
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The E-rules of plan 6.2, each checked on its own against the method without it. */
class EngineRulesTest {
    private val june = LocalDate.of(2026, 6, 21)
    private val march = LocalDate.of(2026, 3, 20)
    private val december = LocalDate.of(2026, 12, 21)

    private fun explain(
        place: PrayerPlace.Coordinates,
        date: LocalDate,
        method: PrayerMethod,
        ramadanDay: Boolean = false,
        zone: OfficialZone? = null,
        baseZone: OfficialZone? = null,
        offsetWindow: ClosedRange<LocalDate>? = null,
        adjustments: Map<TimeEvent, Int> = emptyMap(),
    ) = PrayerEngine.explain(place, date, method, ramadanDay, zone, baseZone, offsetWindow, adjustments)

    private fun EngineExplanation.unrounded(event: TimeEvent): Instant = checkNotNull(events[event.ordinal].unrounded)

    private fun EngineExplanation.raw(event: TimeEvent): Instant = checkNotNull(events[event.ordinal].raw)

    private fun EngineExplanation.at(event: TimeEvent): Instant = checkNotNull(day[event].at)

    private fun seconds(a: Instant, b: Instant) = Duration.between(a, b).toNanos() / 1e9

    private fun assertSeconds(expected: Double, a: Instant, b: Instant, tolerance: Double = 1e-3, message: String = "") =
        assertEquals(expected, seconds(a, b), tolerance, message)

    @Test
    fun e1RoundingModesShareTheUnroundedInstant() {
        val nearest = explain(TUNIS, march, PrayerMethods.MWL)
        val up = explain(TUNIS, march, PrayerMethods.MWL.copy(rounding = TimeEvent.entries.associateWith { Rounding.UP }))
        val down = explain(TUNIS, march, PrayerMethods.MWL.copy(rounding = TimeEvent.entries.associateWith { Rounding.DOWN }))
        for (event in TimeEvent.entries) {
            val t = nearest.unrounded(event)
            assertEquals(t, up.unrounded(event))
            val epoch = t.epochSecond + t.nano / 1e9
            assertEquals(60 * floor((epoch + 30) / 60), nearest.at(event).epochSecond.toDouble(), "$event nearest")
            assertEquals(60 * kotlin.math.ceil(epoch / 60), up.at(event).epochSecond.toDouble(), "$event up")
            assertEquals(60 * floor(epoch / 60), down.at(event).epochSecond.toDouble(), "$event down")
        }
    }

    @Test
    fun e7WholeDiscPastTheMeridian() {
        val plain = explain(MANAMA, march, BAHRAIN_LIKE.copy(dhuhrAfterTransitMin = 0.0))
        val bahrain = explain(MANAMA, march, BAHRAIN_LIKE)
        assertSeconds(66.0, plain.unrounded(TimeEvent.DHUHR), bahrain.unrounded(TimeEvent.DHUHR))
    }

    @Test
    fun e7FractionalZoneOffsetsAreKeptUntilTheRounding() {
        val zone = EngineReferenceCases.DAMASCUS_ZONE
        val plain = explain(EngineReferenceCases.DAMASCUS, march, EngineReferenceCases.SYRIA_LIKE, zone = zone.copy(offsets = emptyMap()))
        val fitted = explain(EngineReferenceCases.DAMASCUS, march, EngineReferenceCases.SYRIA_LIKE, zone = zone)
        assertSeconds(15.0, plain.unrounded(TimeEvent.ASR), fitted.unrounded(TimeEvent.ASR))
        assertSeconds(15.0, plain.unrounded(TimeEvent.ISHA), fitted.unrounded(TimeEvent.ISHA))
        assertSeconds(0.0, plain.unrounded(TimeEvent.MAGHRIB), fitted.unrounded(TimeEvent.MAGHRIB))
    }

    @Test
    fun e8RamadanIntervalAndOffset() {
        val day = LocalDate.of(2026, 3, 1)
        val normal = explain(MECCA, day, PrayerMethods.UMM_AL_QURA)
        val ramadan = explain(MECCA, day, PrayerMethods.UMM_AL_QURA, ramadanDay = true)
        assertSeconds(90 * 60.0, normal.unrounded(TimeEvent.MAGHRIB), normal.unrounded(TimeEvent.ISHA))
        assertSeconds(120 * 60.0, ramadan.unrounded(TimeEvent.MAGHRIB), ramadan.unrounded(TimeEvent.ISHA))
        // "Rounded Maghrib + 90": the interval Isha takes Maghrib's mode (UP).
        assertEquals(normal.at(TimeEvent.MAGHRIB).plusSeconds(90 * 60), normal.at(TimeEvent.ISHA))
        val qatar = explain(DOHA, day, QATAR_LIKE)
        val qatarRamadan = explain(DOHA, day, QATAR_LIKE, ramadanDay = true)
        assertSeconds(60.0, qatar.unrounded(TimeEvent.MAGHRIB), qatarRamadan.unrounded(TimeEvent.MAGHRIB))
        assertSeconds(60.0, qatar.unrounded(TimeEvent.ISHA), qatarRamadan.unrounded(TimeEvent.ISHA))
    }

    @Test
    fun e9RevisionsApplyFromTheirFirstDay() {
        assertEquals(null, QATAR_LIKE.revisedFor(LocalDate.of(2020, 8, 19)).offsets[TimeEvent.FAJR])
        assertEquals(-1, QATAR_LIKE.revisedFor(LocalDate.of(2020, 8, 20)).offsets[TimeEvent.FAJR])
        val day = LocalDate.of(2020, 8, 20)
        val unrevised = explain(DOHA, day, QATAR_LIKE.copy(revisions = emptyList()))
        val revised = explain(DOHA, day, QATAR_LIKE)
        assertSeconds(-60.0, unrevised.unrounded(TimeEvent.FAJR), revised.unrounded(TimeEvent.FAJR))
        val before = explain(DOHA, LocalDate.of(2020, 8, 19), QATAR_LIKE)
        val beforeUnrevised = explain(DOHA, LocalDate.of(2020, 8, 19), QATAR_LIKE.copy(revisions = emptyList()))
        assertEquals(beforeUnrevised.unrounded(TimeEvent.FAJR), before.unrounded(TimeEvent.FAJR))
    }

    private fun maghribMinutesAfterSunset(e: EngineExplanation) = seconds(e.raw(TimeEvent.MAGHRIB), e.unrounded(TimeEvent.MAGHRIB)) / 60

    @Test
    fun e10TerrainClass() {
        assertEquals(10.0, maghribMinutesAfterSunset(explain(SANAA, march, YEMEN_LIKE, zone = SANAA_ZONE)), 1e-6)
        // Seiyun's location lies between the limits and keeps its own (lowland) class.
        assertEquals(6.0, maghribMinutesAfterSunset(explain(SEIYUN, march, YEMEN_LIKE, zone = SEIYUN_ZONE)), 1e-6)
        assertEquals(6.0, maghribMinutesAfterSunset(explain(SEIYUN, march, YEMEN_LIKE)), 1e-6)
        assertEquals(6.0, maghribMinutesAfterSunset(explain(SEIYUN.copy(elevationM = 875), march, YEMEN_LIKE)), 1e-6)
        assertEquals(10.0, maghribMinutesAfterSunset(explain(SEIYUN.copy(elevationM = 876), march, YEMEN_LIKE)), 1e-6)
        assertEquals(10.0, maghribMinutesAfterSunset(explain(SANAA, march, YEMEN_LIKE)), 1e-6)
    }

    @Test
    fun e12MaghribByAngle() {
        val plain = explain(TUNIS, march, PrayerMethods.MWL)
        val angle = explain(TUNIS, march, PrayerMethods.MWL.copy(maghribAngle = 4.0))
        assertTrue(seconds(plain.unrounded(TimeEvent.MAGHRIB), angle.unrounded(TimeEvent.MAGHRIB)) in 10 * 60.0..25 * 60.0)
        assertEquals(-4.0, checkNotNull(angle.events[TimeEvent.MAGHRIB.ordinal].altitudeDeg), 1e-12)
    }

    @Test
    fun e13AsrOnTheApparentSunComesSecondsLater() {
        for (date in listOf(march, june, december)) {
            val geometric = explain(TUNIS, date, PrayerMethods.MWL)
            val apparent = explain(TUNIS, date, PrayerMethods.MWL.copy(asrSun = AsrSun.APPARENT))
            val later = seconds(geometric.unrounded(TimeEvent.ASR), apparent.unrounded(TimeEvent.ASR))
            // 5–10 s at Jordan's and the UAE's latitudes (plan 8.6); up to about 16 s under Tunis's low December sun.
            assertTrue(later in 3.0..20.0, "$date: apparent Asr $later s later")
        }
    }

    @Test
    fun e15SignedShadowAndAsrBeforeMaghrib() {
        // In Guezzam in June, the sun passes north of the zenith: the signed shadow brings Asr earlier, up to 16 min.
        val shadow = explain(IN_GUEZZAM, june, ALGERIA_LIKE.copy(asr = AsrRule.Shadow))
        val signed = explain(IN_GUEZZAM, june, ALGERIA_LIKE)
        val earlier = seconds(signed.unrounded(TimeEvent.ASR), shadow.unrounded(TimeEvent.ASR))
        assertTrue(earlier in 5 * 60.0..17 * 60.0, "signed shadow $earlier s earlier")
        // In Algiers the sun stays south: the two rules agree.
        val algiers = EngineReferenceCases.ALGIERS
        assertEquals(
            explain(algiers, june, ALGERIA_LIKE.copy(asr = AsrRule.Shadow)).unrounded(TimeEvent.ASR),
            explain(algiers, june, ALGERIA_LIKE).unrounded(TimeEvent.ASR),
        )
        val comoros = explain(MORONI, march, COMOROS_LIKE)
        assertSeconds(-168 * 60.0, comoros.unrounded(TimeEvent.MAGHRIB), comoros.unrounded(TimeEvent.ASR))
        assertSeconds(74 * 60.0, comoros.unrounded(TimeEvent.MAGHRIB), comoros.unrounded(TimeEvent.ISHA))
        assertSeconds(-73 * 60.0, comoros.raw(TimeEvent.SUNRISE), comoros.unrounded(TimeEvent.FAJR))
    }

    @Test
    fun e16ElevationShifts() {
        val flat = explain(SAINT_DENIS_RE, march, REUNION_LIKE.copy(elevationShifts = emptyList()))
        val high = explain(SAINT_DENIS_RE, march, REUNION_LIKE)
        assertSeconds(-240.0, flat.unrounded(TimeEvent.FAJR), high.unrounded(TimeEvent.FAJR))
        assertSeconds(-240.0, flat.unrounded(TimeEvent.SUNRISE), high.unrounded(TimeEvent.SUNRISE))
        assertSeconds(240.0, flat.unrounded(TimeEvent.MAGHRIB), high.unrounded(TimeEvent.MAGHRIB))
        assertSeconds(240.0, flat.unrounded(TimeEvent.ISHA), high.unrounded(TimeEvent.ISHA))
        assertSeconds(0.0, flat.unrounded(TimeEvent.DHUHR), high.unrounded(TimeEvent.DHUHR))
        assertSeconds(0.0, flat.unrounded(TimeEvent.ASR), high.unrounded(TimeEvent.ASR))
        val band = explain(SAINT_DENIS_RE.copy(elevationM = 250), march, REUNION_LIKE)
        assertSeconds(90.0, flat.unrounded(TimeEvent.MAGHRIB), band.unrounded(TimeEvent.MAGHRIB))
        val none = explain(SAINT_DENIS_RE.copy(elevationM = null), march, REUNION_LIKE)
        assertEquals(flat.unrounded(TimeEvent.MAGHRIB), none.unrounded(TimeEvent.MAGHRIB))
    }

    @Test
    fun e17TwilightLatitudeMovesOnlyFajrAndIsha() {
        val plain = explain(TRIPOLI, march, PrayerMethods.MWL)
        val scaled = explain(TRIPOLI, march, PrayerMethods.MWL.copy(twilightLatitudeScale = 0.99330562))
        for (event in listOf(TimeEvent.SUNRISE, TimeEvent.DHUHR, TimeEvent.ASR, TimeEvent.MAGHRIB)) {
            assertEquals(plain.unrounded(event), scaled.unrounded(event), event.name)
        }
        assertTrue(plain.unrounded(TimeEvent.FAJR) != scaled.unrounded(TimeEvent.FAJR))
        assertTrue(plain.unrounded(TimeEvent.ISHA) != scaled.unrounded(TimeEvent.ISHA))
    }

    @Test
    fun e18SeasonalOffsetsInterpolateThroughTheYear() {
        val plain = explain(PRISTINA, LocalDate.of(2026, 1, 1), KOSOVO_LIKE.copy(seasonalOffsets = emptyMap()))
        val seasonal = explain(PRISTINA, LocalDate.of(2026, 1, 1), KOSOVO_LIKE)
        // Day 1 is the first point: Fajr + 10, Maghrib + 4.5, Isha − 4.
        assertSeconds(600.0, plain.unrounded(TimeEvent.FAJR), seasonal.unrounded(TimeEvent.FAJR))
        assertSeconds(270.0, plain.unrounded(TimeEvent.MAGHRIB), seasonal.unrounded(TimeEvent.MAGHRIB))
        assertSeconds(-240.0, plain.unrounded(TimeEvent.ISHA), seasonal.unrounded(TimeEvent.ISHA))
        // Halfway between the first two points (day 1 + 365/72): the mean of the two values.
        val values = checkNotNull(KOSOVO_LIKE.seasonalOffsets[TimeEvent.FAJR])
        val day = LocalDate.of(2026, 1, 1).plusDays(73) // p = 36 * 73 / 365 = 7.2
        val expected = values[7] * 0.8 + values[8] * 0.2
        val a = explain(PRISTINA, day, KOSOVO_LIKE.copy(seasonalOffsets = emptyMap()))
        val b = explain(PRISTINA, day, KOSOVO_LIKE)
        assertSeconds(expected * 60, a.unrounded(TimeEvent.FAJR), b.unrounded(TimeEvent.FAJR), 1e-2)
    }

    @Test
    fun e2Horizons() {
        val standard = explain(MECCA, march, PrayerMethods.MWL)
        val fixed = explain(MECCA, march, PrayerMethods.MWL.copy(horizon = Horizons(HorizonModel.Fixed(-0.83), HorizonModel.Standard)))
        assertTrue(seconds(standard.unrounded(TimeEvent.SUNRISE), fixed.unrounded(TimeEvent.SUNRISE)) in 0.5..1.5)
        assertEquals(standard.unrounded(TimeEvent.MAGHRIB), fixed.unrounded(TimeEvent.MAGHRIB))
        val pressureAtSea = explain(TUNIS, march, PrayerMethods.MWL.copy(horizon = Horizons(HorizonModel.Pressure, HorizonModel.Pressure)))
        val tunis = explain(TUNIS, march, PrayerMethods.MWL)
        assertTrue(abs(seconds(tunis.unrounded(TimeEvent.SUNRISE), pressureAtSea.unrounded(TimeEvent.SUNRISE))) < 1.0)
        // Refraction at Amman's zone: a dipped, refracted horizon gives an earlier sunrise than the standard one.
        val refraction = explain(AMMAN, march, JORDAN_LIKE, zone = AMMAN_ZONE)
        val sunriseAltitude = checkNotNull(refraction.events[TimeEvent.SUNRISE.ordinal].altitudeDeg)
        assertTrue(sunriseAltitude < -1.5, "Amman's morning horizon at 1100 m: $sunriseAltitude")
        // PerZone: the zone's own altitudes, the fallback at exact coordinates.
        val rabat = EngineReferenceCases.RABAT
        val zoned = explain(rabat, march, EngineReferenceCases.MOROCCO_LIKE, zone = EngineReferenceCases.RABAT_ZONE)
        assertEquals(-1.17, checkNotNull(zoned.events[TimeEvent.SUNRISE.ordinal].altitudeDeg), 1e-12)
        assertEquals(-1.15, checkNotNull(zoned.events[TimeEvent.MAGHRIB.ordinal].altitudeDeg), 1e-12)
        val exact = explain(rabat, march, EngineReferenceCases.MOROCCO_LIKE)
        assertEquals(-0.8333, checkNotNull(exact.events[TimeEvent.SUNRISE.ordinal].altitudeDeg), 1e-12)
    }

    @Test
    fun e4EvaluationModes() {
        val oman = explain(EngineReferenceCases.MUSCAT, march, EngineReferenceCases.OMAN_LIKE)
        val jd0 = SolarEngine.julianDay(oman.cycleDate)
        val daily = SolarEngine.sun(SunModel.MEEUS, jd0)
        for (event in TimeEvent.entries) {
            assertEquals(daily.declinationDeg, checkNotNull(oman.events[event.ordinal].declinationDeg), 1e-12, event.name)
        }
        val saudi = explain(EngineReferenceCases.RIYADH, march, SAUDI_LIKE)
        val kacstDeclination = SolarEngine.sun(SunModel.MEEUS, SolarEngine.julianDay(saudi.cycleDate) + 21.0 / 24).declinationDeg
        val kacstEquation = SolarEngine.sun(SunModel.MEEUS, SolarEngine.julianDay(saudi.cycleDate) + 9.0 / 24).equationOfTimeMin
        val fajr = saudi.events[TimeEvent.FAJR.ordinal]
        assertEquals(kacstDeclination, checkNotNull(fajr.declinationDeg), 1e-12)
        assertEquals(kacstEquation, checkNotNull(fajr.equationOfTimeMin), 1e-12)
        // Sunrise keeps the event instant.
        assertTrue(abs(checkNotNull(saudi.events[TimeEvent.SUNRISE.ordinal].declinationDeg) - kacstDeclination) > 1e-4)
        // Yemen's Asr: the noon shadow takes the declination at local midnight.
        val yemen = explain(SANAA, march, YEMEN_LIKE, zone = SANAA_ZONE)
        val midnight = java.time.ZonedDateTime.of(march, LocalTime.MIDNIGHT, SANAA.zone).toInstant().epochSecond.toDouble()
        val shadowDecl = SolarEngine.sun(SunModel.MEEUS, SolarEngine.julianDay(midnight)).declinationDeg
        val expected = Sky.asrAltitude(SANAA_ZONE.point.latitude, shadowDecl, 1.0, signed = false, apparent = false, 1010.0, 10.0)
        assertEquals(checkNotNull(expected), checkNotNull(yemen.events[TimeEvent.ASR.ordinal].altitudeDeg), 1e-12)
    }

    @Test
    fun e6MorningAndEveningPoints() {
        val e = explain(MANAMA, march, BAHRAIN_LIKE, zone = BAHRAIN_ZONE)
        for (event in listOf(TimeEvent.FAJR, TimeEvent.SUNRISE)) {
            assertEquals(50.68, checkNotNull(e.events[event.ordinal].longitudeDeg), 1e-9, event.name)
        }
        for (event in listOf(TimeEvent.DHUHR, TimeEvent.ASR, TimeEvent.MAGHRIB, TimeEvent.ISHA)) {
            assertEquals(50.44, checkNotNull(e.events[event.ordinal].longitudeDeg), 1e-9, event.name)
        }
        // At exact coordinates every time is at the place's own point.
        val exact = explain(MANAMA, march, BAHRAIN_LIKE)
        assertTrue(exact.events.all { it.longitudeDeg == null || abs(it.longitudeDeg!! - 50.586) < 1e-9 })
    }

    @Test
    fun e6TimesAtTheirOwnPoints() {
        // Bahrain's own-point zone: Fajr at the morning point, Dhuhr and Maghrib at the evening point, sunrise, Asr
        // and Isha each at its own.
        val e = explain(MANAMA, march, BAHRAIN_EVENT_LIKE, zone = BAHRAIN_OWN_ZONE)
        val points = mapOf(
            TimeEvent.FAJR to (26.21 to 50.72), TimeEvent.SUNRISE to (26.205 to 50.62), TimeEvent.DHUHR to (null to 50.44),
            TimeEvent.ASR to (26.18 to 50.44), TimeEvent.MAGHRIB to (26.15 to 50.44), TimeEvent.ISHA to (26.145 to 50.72),
        )
        for ((event, point) in points) {
            val explained = e.events[event.ordinal]
            assertEquals(point.second, checkNotNull(explained.longitudeDeg), 1e-9, event.name)
            point.first?.let { assertEquals(it, checkNotNull(explained.latitudeDeg), 1e-9, event.name) }
        }
        // An Isha rule's own point (Slovenia's Sarajevo) wins over the zone's Isha point; Fajr takes the zone's.
        val si = com.tunisianprayertimes.method.EuropeanPresets.SI_ISLAMSKA_SKUPNOST
        val sarajevo = checkNotNull((si.isha as IshaRule.Angle).at)
        val sl = explain(LJUBLJANA, LocalDate.of(2026, 1, 15), si, zone = LJUBLJANA_OWN_ZONE)
        assertEquals(sarajevo.longitude, checkNotNull(sl.events[TimeEvent.ISHA.ordinal].longitudeDeg), 1e-9)
        assertEquals(15.0, checkNotNull(sl.events[TimeEvent.FAJR.ordinal].longitudeDeg), 1e-9)
        // A zone without its own points is untouched.
        assertEquals(
            explain(MANAMA, march, BAHRAIN_LIKE, zone = BAHRAIN_ZONE).day,
            explain(MANAMA, march, BAHRAIN_LIKE, zone = BAHRAIN_ZONE.copy(eventPoints = emptyMap())).day,
        )
    }

    @Test
    fun a8PerpetualTablesShowTheirReferenceYear() {
        val plain = KUWAIT_PRINTED.copy(referenceYear = null)
        fun clock(e: EngineExplanation) = TimeEvent.entries.map { e.at(it).atZone(KUWAIT_CITY.zone).toLocalTime() }
        // Any other year shows the reference year's (2011) clock minutes on the same month and day, on its own date;
        // 29 February takes the first leap year from the reference year.
        for ((date, table) in listOf(
            LocalDate.of(2026, 3, 15) to LocalDate.of(2011, 3, 15),
            LocalDate.of(2031, 7, 1) to LocalDate.of(2011, 7, 1),
            LocalDate.of(2028, 2, 29) to LocalDate.of(2012, 2, 29),
        )) {
            val shown = explain(KUWAIT_CITY, date, KUWAIT_PRINTED, zone = KUWAIT_PRINTED_CITY_ZONE)
            assertEquals(clock(explain(KUWAIT_CITY, table, plain, zone = KUWAIT_PRINTED_CITY_ZONE)), clock(shown), "$date")
            assertEquals(date, shown.day.date)
            assertEquals(date, shown.at(TimeEvent.DHUHR).atZone(KUWAIT_CITY.zone).toLocalDate())
            assertEquals(KUWAIT_PRINTED, shown.method)
        }
        assertEquals(LocalDate.of(2012, 2, 29), PrayerEngine.tableDate(LocalDate.of(2028, 2, 29), 2011))
        assertEquals(LocalDate.of(2008, 2, 29), PrayerEngine.tableDate(LocalDate.of(2024, 2, 29), 2006))
        assertEquals(LocalDate.of(2006, 12, 31), PrayerEngine.tableDate(LocalDate.of(2026, 12, 31), 2006))
        // The reference year itself is computed as before.
        val june2011 = LocalDate.of(2011, 6, 21)
        assertEquals(clock(explain(KUWAIT_CITY, june2011, plain)), clock(explain(KUWAIT_CITY, june2011, KUWAIT_PRINTED)))
        // E9 and E8 follow the actual date: Qatar's Fajr − 1 from 2020-08-20, its Ramadan Maghrib + 1.
        val unrevised = QATAR_PRINTED.copy(revisions = emptyList())
        assertSeconds(-60.0, explain(DOHA, june, unrevised).unrounded(TimeEvent.FAJR), explain(DOHA, june, QATAR_PRINTED).unrounded(TimeEvent.FAJR))
        val beforeRevision = LocalDate.of(2020, 8, 19)
        assertEquals(explain(DOHA, beforeRevision, unrevised).unrounded(TimeEvent.FAJR), explain(DOHA, beforeRevision, QATAR_PRINTED).unrounded(TimeEvent.FAJR))
        val ramadan = LocalDate.of(2027, 2, 20)
        assertSeconds(60.0, explain(DOHA, ramadan, QATAR_PRINTED).unrounded(TimeEvent.MAGHRIB),
            explain(DOHA, ramadan, QATAR_PRINTED, ramadanDay = true).unrounded(TimeEvent.MAGHRIB))
        // Summer time: the table's clock is the zone's standard time (+02 in Beirut), so a summer date shows its row
        // an hour later on the phone's clock, and a winter date as printed.
        val tableYear = LEBANON_PRINTED.copy(referenceYear = null)
        val standard = BEIRUT.copy(zoneId = "+02:00")
        for ((date, hours) in listOf(LocalDate.of(2026, 7, 1) to 1L, LocalDate.of(2026, 12, 21) to 0L)) {
            val shown = explain(BEIRUT, date, LEBANON_PRINTED)
            val printed = explain(standard, LocalDate.of(1931, date.monthValue, date.dayOfMonth), tableYear)
            for (event in TimeEvent.entries) {
                assertEquals(printed.at(event).atZone(ZoneOffset.ofHours(2)).toLocalTime().plusHours(hours),
                    shown.at(event).atZone(BEIRUT.zone).toLocalTime(), "$date $event")
            }
        }
    }

    @Test
    fun e4PraytimesIsAladhansOnePass() {
        // aladhan's PrayerTimes (the PHP port of PrayTimes.js), written out here: the Almanac's low-precision sun with
        // PrayTimes' constants, each time's sun once at its default hour of local mean time, the hour angle in one pass,
        // Dhuhr + 1 and Maghrib + 2, and Asr's noon shadow at the zone's hour after 0h UT (the API server's clock).
        fun rad(x: Double) = Math.toRadians(x)
        fun sunAt(jd: Double): Pair<Double, Double> {
            val d = jd - 2451545.0
            val g = (357.529 + 0.98560028 * d).mod(360.0)
            val q = (280.459 + 0.98564736 * d).mod(360.0)
            val l = q + 1.915 * sin(rad(g)) + 0.020 * sin(rad(2 * g))
            val e = 23.439 - 0.00000036 * d
            val ra = Math.toDegrees(atan2(cos(rad(e)) * sin(rad(l)), cos(rad(l)))) / 15
            return Math.toDegrees(asin(sin(rad(e)) * sin(rad(l)))) to ((q / 15 - ra.mod(24.0) + 12).mod(24.0) - 12) * 60
        }
        val hours = mapOf(TimeEvent.FAJR to 5.0, TimeEvent.SUNRISE to 6.0, TimeEvent.DHUHR to 12.0, TimeEvent.ASR to 13.0,
            TimeEvent.MAGHRIB to 18.0, TimeEvent.ISHA to 18.0)
        fun aladhanUt(event: TimeEvent, date: LocalDate, lat: Double, lng: Double, shadowHour: Double?): Double {
            val jd0 = SolarEngine.julianDay(date)
            val (decl, eot) = sunAt(jd0 - lng / 360 + hours.getValue(event) / 24)
            val noon = 12 - eot / 60 - lng / 15
            fun ha(alt: Double) = Math.toDegrees(acos((sin(rad(alt)) - sin(rad(lat)) * sin(rad(decl))) / (cos(rad(lat)) * cos(rad(decl))))) / 15
            return when (event) {
                TimeEvent.FAJR -> noon - ha(-18.0)
                TimeEvent.SUNRISE -> noon - ha(-0.833)
                TimeEvent.DHUHR -> noon + 1 / 60.0
                TimeEvent.ASR -> {
                    val noonDecl = shadowHour?.let { sunAt(jd0 + it / 24).first } ?: decl
                    noon + ha(Math.toDegrees(atan(1 / (1 + tan(rad(abs(lat - noonDecl)))))))
                }
                TimeEvent.MAGHRIB -> noon + ha(-0.833) + 2 / 60.0
                TimeEvent.ISHA -> noon + ha(-17.0)
            }
        }
        val point = checkNotNull(DAMASCUS_SCREEN_ZONE.eveningPoint)
        // In the zone's own year a date shows itself, so the zone's computing point and Asr shadow hour are read directly.
        for (date in listOf(LocalDate.of(2011, 1, 15), LocalDate.of(2011, 6, 21), LocalDate.of(2011, 10, 18))) {
            val shown = explain(DAMASCUS, date, SYRIA_SCREENS, zone = DAMASCUS_SCREEN_ZONE)
            val midnight = date.atStartOfDay(ZoneOffset.UTC).toInstant()
            for (event in TimeEvent.entries) {
                val expected = aladhanUt(event, date, point.latitude, point.longitude, DAMASCUS_SCREEN_ZONE.asrShadowHourUt)
                assertSeconds(expected * 3600, midnight, shown.unrounded(event), tolerance = 0.05, message = "$date $event")
            }
        }
        // At exact coordinates, PrayTimes.js's own noon shadow: the Asr sun's declination.
        val exact = explain(DAMASCUS, june, SYRIA_SCREENS)
        val midnight = june.atStartOfDay(ZoneOffset.UTC).toInstant()
        for (event in TimeEvent.entries) {
            val expected = aladhanUt(event, june, DAMASCUS.latE5 / 1e5, DAMASCUS.lngE5 / 1e5, null)
            assertSeconds(expected * 3600, midnight, exact.unrounded(event), tolerance = 0.05, message = "exact $event")
        }
    }

    @Test
    fun a8ScreenRowsAtAZone() {
        fun d(text: String) = LocalDate.parse(text)
        // The screens read row (the date's day in a leap year − 1): 29 February copies 28 February or 1 March.
        assertEquals(d("2003-02-28"), PrayerEngine.tableDate(d("2028-02-29"), 2003, TableRows.LEAP_ROWS_FEB28))
        assertEquals(d("2003-03-01"), PrayerEngine.tableDate(d("2026-03-01"), 2003, TableRows.LEAP_ROWS_FEB28))
        assertEquals(d("2020-02-28"), PrayerEngine.tableDate(d("2024-02-29"), 2020, TableRows.LEAP_ROWS_FEB28))
        assertEquals(d("2011-03-01"), PrayerEngine.tableDate(d("2028-02-29"), 2011, TableRows.LEAP_ROWS_MAR1))
        assertEquals(d("2011-12-31"), PrayerEngine.tableDate(d("2026-12-31"), 2011, TableRows.LEAP_ROWS_MAR1))
        // A table of 365 days without 29 February, read by leap-year day: the next day's row from 1 March to 30 December.
        for ((date, shown) in listOf(
            "2026-01-15" to "2024-01-15", "2026-02-28" to "2024-02-28", "2028-02-29" to "2024-03-01", "2026-03-01" to "2024-03-02",
            "2026-10-18" to "2024-10-19", "2026-12-30" to "2024-12-31", "2026-12-31" to "2024-12-31",
        )) assertEquals(d(shown), PrayerEngine.tableDate(d(date), 2024, TableRows.COMMON_ROWS), date)
        assertEquals(d("2026-03-02"), PrayerEngine.tableDate(d("2026-03-01"), 2026, TableRows.COMMON_ROWS))
        assertEquals(d("2026-03-01"), PrayerEngine.tableDate(d("2028-02-29"), 2026, TableRows.COMMON_ROWS))
        assertEquals(d("2026-12-31"), PrayerEngine.tableDate(d("2026-12-31"), 2026, TableRows.COMMON_ROWS))
        // The zone's year and rows win over the method's: even in the table's own year, 1 March shows 2 March's row,
        // a day earlier on the clock; in 2026 the same row shows at the same clock minutes.
        val calendar = HASAKAH_SCREEN_ZONE.copy(tableRows = TableRows.CALENDAR)
        val march2 = explain(HASAKAH, d("2024-03-02"), SYRIA_SCREENS, zone = calendar)
        val march1 = explain(HASAKAH, d("2024-03-01"), SYRIA_SCREENS, zone = HASAKAH_SCREEN_ZONE)
        assertEquals(d("2024-03-01"), march1.day.date)
        for (event in TimeEvent.entries) assertSeconds(-86_400.0, march2.unrounded(event), march1.unrounded(event), message = event.name)
        val later = explain(HASAKAH, d("2026-03-01"), SYRIA_SCREENS, zone = HASAKAH_SCREEN_ZONE)
        for (event in TimeEvent.entries) {
            assertEquals(march2.at(event).atZone(HASAKAH.zone).toLocalTime(), later.at(event).atZone(HASAKAH.zone).toLocalTime(), event.name)
        }
        // A zone without a year leaves the method's (none here): the date computes as itself.
        assertEquals(explain(HASAKAH, june, SYRIA_SCREENS, zone = calendar.copy(referenceYear = null)).day.date, june)
    }

    @Test
    fun s5ZoneAnglesReplaceTheMethodsAngles() {
        // A zone's own Isha angle is the method's with that angle, at the zone; Fajr keeps the method's.
        val live = MAYADIN_LIVE_ZONE
        val plainZone = live.copy(ishaAngleDeg = null)
        val withZone = explain(MAYADIN, june, SYRIA_SCREENS, zone = live)
        val byMethod = explain(MAYADIN, june, SYRIA_SCREENS.copy(isha = IshaRule.Angle(18.0)), zone = plainZone)
        for (event in TimeEvent.entries) assertSeconds(0.0, byMethod.unrounded(event), withZone.unrounded(event), message = event.name)
        assertEquals(IshaRule.Angle(18.0), withZone.method.isha)
        val seventeen = explain(MAYADIN, june, SYRIA_SCREENS, zone = plainZone)
        assertTrue(seconds(seventeen.unrounded(TimeEvent.ISHA), withZone.unrounded(TimeEvent.ISHA)) > 240.0)
        // Isha by minutes keeps its minutes; Fajr by angle takes the zone's.
        val riyadh = explain(RIYADH, june, SAUDI_LIKE, zone = RIYADH_ANGLES_ZONE)
        val plain = explain(RIYADH, june, SAUDI_LIKE, zone = RIYADH_ANGLES_ZONE.copy(fajrAngleDeg = null, ishaAngleDeg = null))
        assertEquals(SAUDI_LIKE.isha, riyadh.method.isha)
        assertSeconds(0.0, plain.unrounded(TimeEvent.ISHA), riyadh.unrounded(TimeEvent.ISHA))
        assertTrue(seconds(riyadh.unrounded(TimeEvent.FAJR), plain.unrounded(TimeEvent.FAJR)) > 60.0, "19° comes before 18.5°")
        // An Isha angle's own point stays with the zone's angle.
        val slovenia = com.tunisianprayertimes.method.EuropeanPresets.SI_ISLAMSKA_SKUPNOST
        val sarajevo = checkNotNull((slovenia.isha as IshaRule.Angle).at)
        val angled = explain(LJUBLJANA, LocalDate.of(2026, 1, 15), slovenia, zone = LJUBLJANA_OWN_ZONE.copy(eventPoints = emptyMap(), ishaAngleDeg = 18.0))
        assertEquals(IshaRule.Angle(18.0, sarajevo, (slovenia.isha as IshaRule.Angle).atNameAr), angled.method.isha)
    }

    @Test
    fun zoneShifts() {
        // Published, from a base zone: Kuwait City's times plus Jahra's minute on all six.
        val city = explain(KUWAIT_JAHRA, june, PrayerMethods.MWL, zone = KUWAIT_CITY_ZONE)
        val jahra = explain(KUWAIT_JAHRA, june, PrayerMethods.MWL, zone = JAHRA_ZONE, baseZone = KUWAIT_CITY_ZONE)
        for (event in TimeEvent.entries) assertSeconds(60.0, city.unrounded(event), jahra.unrounded(event), message = event.name)
        // The Takvim: Mostar = Sarajevo + whole minutes from the month's means.
        val sarajevo = explain(MOSTAR, june, BOSNIA_LIKE, zone = SARAJEVO_ZONE)
        val mostar = explain(MOSTAR, june, BOSNIA_LIKE, zone = MOSTAR_ZONE, baseZone = SARAJEVO_ZONE)
        val dLambda = 4 * ((18 + 26 / 60.0) - MOSTAR_ZONE.point.longitude)
        val dPhi = BOSNIA_LIKE.takvimMeans[12 + 5] * 0.9 * (MOSTAR_ZONE.point.latitude - (43 + 52 / 60.0))
        assertSeconds(60 * floor(dLambda + dPhi + 0.5), sarajevo.unrounded(TimeEvent.FAJR), mostar.unrounded(TimeEvent.FAJR))
        assertSeconds(60 * floor(dLambda + 0.5), sarajevo.unrounded(TimeEvent.DHUHR), mostar.unrounded(TimeEvent.DHUHR))
        assertSeconds(60 * floor(dLambda - dPhi + 0.5), sarajevo.unrounded(TimeEvent.MAGHRIB), mostar.unrounded(TimeEvent.MAGHRIB))
        // Jordan: Amman's times plus the monthly difference of Irbid's own times, to the half-minute.
        val irbid = explain(IRBID, june, JORDAN_LIKE, zone = IRBID_ZONE, baseZone = AMMAN_ZONE)
        assertTrue(irbid.zoneOffsets.values.all { it * 2 == floor(it * 2) }, "half-minutes: ${irbid.zoneOffsets}")
        val ammanTimes = explain(IRBID, june, JORDAN_LIKE, zone = AMMAN_ZONE)
        for (event in TimeEvent.entries) {
            assertSeconds((irbid.zoneOffsets[event] ?: 0.0) * 60, ammanTimes.unrounded(event), irbid.unrounded(event), message = event.name)
        }
        // Algeria: the ceiling of the largest difference over the window, so never earlier than the place's own time.
        val window = LocalDate.of(2026, 10, 10)..LocalDate.of(2026, 11, 8)
        val blida = explain(BLIDA, LocalDate.of(2026, 10, 15), ALGERIA_LIKE, zone = BLIDA_ZONE, baseZone = ALGIERS_ZONE, offsetWindow = window)
        assertTrue(blida.zoneOffsets.values.all { it == floor(it) })
        var day = window.start
        while (day <= window.endInclusive) {
            val own = explain(BLIDA, day, ALGERIA_LIKE, zone = BLIDA_ZONE)
            val shifted = explain(BLIDA, day, ALGERIA_LIKE, zone = BLIDA_ZONE, baseZone = ALGIERS_ZONE, offsetWindow = window)
            for (event in TimeEvent.entries) {
                assertTrue(shifted.unrounded(event) >= own.unrounded(event).minusMillis(1), "$day $event: not before its own time")
            }
            day = day.plusDays(7)
        }
    }

    @Test
    fun ishaLatestCapAndCustomEarliestFloor() {
        val capped = explain(LONDON, june, MWL_CAPPED)
        assertEquals(LocalTime.of(22, 45), capped.at(TimeEvent.ISHA).atZone(LONDON.zone).toLocalTime())
        assertEquals(EventStatus.ESTIMATED, capped.day[TimeEvent.ISHA].status)
        val winter = explain(PARIS, december, CUSTOM_EARLY)
        assertEquals(LocalTime.of(19, 0), winter.at(TimeEvent.ISHA).atZone(PARIS.zone).toLocalTime())
        assertEquals(EventStatus.COMPUTED, winter.day[TimeEvent.ISHA].status)
    }

    @Test
    fun e19AtAMainlandFrenchPlace() {
        // A method without Dhuhr minutes shows transit + 1.2, rounded up and estimated, every day.
        val paris = explain(PARIS, march, PrayerMethods.MWL)
        val dhuhr = paris.events[TimeEvent.DHUHR.ordinal]
        assertSeconds(72.0, paris.raw(TimeEvent.DHUHR), paris.unrounded(TimeEvent.DHUHR))
        assertEquals(Rounding.UP, dhuhr.rounding)
        assertEquals(RuleName.VALIDITY_ENVELOPE, dhuhr.time.rule)
        assertEquals(EnvelopeBound.DHUHR, dhuhr.steps.single { it.kind == StepKind.VALIDITY_ENVELOPE }.bound)
        // Outside mainland France the same method keeps the bare transit.
        val tunis = explain(TUNIS, march, PrayerMethods.MWL)
        assertEquals(tunis.raw(TimeEvent.DHUHR), tunis.unrounded(TimeEvent.DHUHR))
        // Fajr not after the 12° instant: a 10° Fajr is held there and rounded down.
        val shallow = explain(PARIS, march, PrayerMethods.MWL.copy(fajr = FajrRule.Angle(10.0)))
        val twelve = explain(PARIS, march, PrayerMethods.MWL.copy(fajr = FajrRule.Angle(12.0)))
        assertSeconds(0.0, twelve.unrounded(TimeEvent.FAJR), shallow.unrounded(TimeEvent.FAJR))
        assertEquals(Rounding.DOWN, shallow.events[TimeEvent.FAJR.ordinal].rounding)
        // Maghrib not before sunset + 1: a method's Maghrib − 5 is held at sunset + 1, rounded up.
        val early = explain(PARIS, march, PrayerMethods.MWL.copy(offsets = mapOf(TimeEvent.MAGHRIB to -5)))
        assertSeconds(60.0, early.raw(TimeEvent.MAGHRIB), early.unrounded(TimeEvent.MAGHRIB))
        // An interval Isha is held by the «Shafaq Ahmer» curve on a June night, and marked estimated.
        val gmp = explain(PARIS, june, CUSTOM_GMP)
        val ahmer = MoonsightingSeasonal.minutes(MoonsightingSeasonal.Curve.ISHA_AHMER, PARIS.latE5 / 1e5, june)
        val sunset = gmp.raw(TimeEvent.MAGHRIB)
        assertTrue(gmp.unrounded(TimeEvent.ISHA) >= sunset.plusMillis((ahmer * 60_000).toLong() - 1))
        assertEquals(EventStatus.ESTIMATED, gmp.day[TimeEvent.ISHA].status)
        // A custom method outside mainland France: Fajr no earlier than the 20° instant.
        val istanbul = explain(EngineReferenceCases.ISTANBUL, december, CUSTOM_EARLY.copy(fajr = FajrRule.MinutesBeforeSunrise(120)))
        val twenty = explain(EngineReferenceCases.ISTANBUL, december, PrayerMethods.MWL.copy(fajr = FajrRule.Angle(20.0)))
        assertTrue(istanbul.unrounded(TimeEvent.FAJR) >= twenty.unrounded(TimeEvent.FAJR).minusSeconds(60))
    }

    @Test
    fun step9WindowCheck() {
        // Mecca: a preset's Dhuhr at transit is already before transit + 1.2: an adjustment away is held at its unadjusted time.
        val away = explain(MECCA, june, PrayerMethods.MWL, adjustments = mapOf(TimeEvent.DHUHR to -5))
        val plain = explain(MECCA, june, PrayerMethods.MWL)
        assertEquals(plain.unrounded(TimeEvent.DHUHR), away.unrounded(TimeEvent.DHUHR))
        assertEquals(RuleName.ADJUSTMENT_HELD, away.day[TimeEvent.DHUHR].rule)
        val toward = explain(MECCA, june, PrayerMethods.MWL, adjustments = mapOf(TimeEvent.DHUHR to 5))
        assertSeconds(300.0, plain.unrounded(TimeEvent.DHUHR), toward.unrounded(TimeEvent.DHUHR))
        assertEquals(EventStatus.COMPUTED, toward.day[TimeEvent.DHUHR].status)
        // Maghrib − 10 is held at sunset; sunrise + 5 at the computed sunrise.
        val held = explain(MECCA, june, PrayerMethods.MWL, adjustments = mapOf(TimeEvent.MAGHRIB to -10, TimeEvent.SUNRISE to 5))
        assertEquals(held.raw(TimeEvent.MAGHRIB), held.unrounded(TimeEvent.MAGHRIB))
        assertEquals(held.raw(TimeEvent.SUNRISE), held.unrounded(TimeEvent.SUNRISE))
        // Carried past by 1 min or less, a time stays.
        val within = explain(MECCA, june, PrayerMethods.MWL, adjustments = mapOf(TimeEvent.MAGHRIB to -1))
        assertSeconds(-60.0, plain.unrounded(TimeEvent.MAGHRIB), within.unrounded(TimeEvent.MAGHRIB))
        // Isha has no bound in step 9, and the Maghrib stepper does not carry into an interval Isha.
        val uaq = explain(MECCA, june, PrayerMethods.UMM_AL_QURA, adjustments = mapOf(TimeEvent.MAGHRIB to 5))
        val uaqPlain = explain(MECCA, june, PrayerMethods.UMM_AL_QURA)
        assertEquals(uaqPlain.unrounded(TimeEvent.ISHA), uaq.unrounded(TimeEvent.ISHA))
    }

    @Test
    fun d5AngleBasedRuleBoundsShortNights() {
        val paris = explain(PARIS, june, PrayerMethods.MWL)
        val night = checkNotNull(paris.nightAfter)
        val isha = paris.events[TimeEvent.ISHA.ordinal]
        assertEquals(RuleName.ANGLE_BASED, isha.time.rule)
        val bound = night.sunset.plusNanos((17.0 / 60 * Duration.between(night.sunset, night.sunrise).toNanos()).toLong())
        assertTrue(abs(Duration.between(bound, checkNotNull(isha.unrounded)).toMillis()) < 5)
        // The 18° Fajr is absent at Paris on 21 June: the rule estimates it.
        assertEquals(null, paris.events[TimeEvent.FAJR.ordinal].raw)
        assertEquals(RuleName.ANGLE_BASED, paris.day[TimeEvent.FAJR].rule)
        assertNotNull(paris.day[TimeEvent.FAJR].at)
        // Where the angle is reached early enough, nothing changes.
        assertEquals(EventStatus.COMPUTED, explain(MECCA, june, PrayerMethods.MWL).day[TimeEvent.ISHA].status)
    }

    @Test
    fun resultsDoNotDependOnTheDefaultTimeZone() {
        val saved = java.util.TimeZone.getDefault()
        try {
            val results = listOf("Pacific/Auckland", "Africa/Tunis", "UTC").map { id ->
                java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(id))
                listOf(PARIS to PrayerMethods.MWL, MECCA to PrayerMethods.UMM_AL_QURA, EngineReferenceCases.MATA_UTU to PrayerMethods.MWL)
                    .map { (place, method) -> PrayerEngine.day(place, june, method) }
            }
            assertEquals(results[0], results[1])
            assertEquals(results[0], results[2])
        } finally {
            java.util.TimeZone.setDefault(saved)
        }
    }

    @Test
    fun zonedDateTimeRoundTrip() {
        for (event in PrayerEngine.day(PARIS, june, PrayerMethods.MWL).events) {
            val at = checkNotNull(event.at)
            assertEquals(at, at.atZone(PARIS.zone).toInstant())
        }
    }

    @Test
    fun tunisia1818StaysWithinAMinuteOfInm() {
        // tunisia_18_18 has INM's values; it differs from INM by the horizon (0.8333° against 0.83° and the
        // dip of 9 m) and by Asr's noon declination (at the transit, against INM's 0h UT).
        val e = explain(place(36.8065, 10.1815, "Africa/Tunis"), LocalDate.of(2026, 10, 3), PrayerMethods.TUNISIA_18_18)
        // meteo.tn's Tunis row for 2026-10-03 (docs/csv/615/2026/10.csv).
        val inm = listOf("04:50", "06:16", "12:15", "15:28", "18:02", "19:26").map { LocalTime.parse(it).toSecondOfDay() }
        e.day.events.zip(inm).forEach { (time, published) ->
            val shown = checkNotNull(time.at).atZone(TUNIS.zone).toLocalTime().toSecondOfDay()
            assertTrue(abs(shown - published) <= 60, "${time.event}: ${LocalTime.ofSecondOfDay(shown.toLong())}")
        }
    }
}

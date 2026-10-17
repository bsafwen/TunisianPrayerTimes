package com.tunisianprayertimes.method

import com.tunisianprayertimes.engine.HighLatitudeRule
import com.tunisianprayertimes.engine.HorizonModel
import com.tunisianprayertimes.engine.Horizons
import com.tunisianprayertimes.engine.PolarFallback
import com.tunisianprayertimes.engine.SunEvaluation
import com.tunisianprayertimes.engine.SunModel
import com.tunisianprayertimes.engine.TimeEvent
import com.tunisianprayertimes.place.LatLngE5
import java.time.LocalTime

/**
 * The 26 presets tied to European bodies or practice (plan 7.5) and overseas France's four (7.6).
 * Values are the plan's; where it says a value is fitted in M3 from local tables, the fitted value
 * replaces it when the fit lands (the note in each evidence line says so).
 */
object EuropeanPresets {
    private val DAILY_0H = TimeEvent.entries.associateWith { SunEvaluation.DAILY_0H_UT }
    private val UP_FIVE_DOWN_SUNRISE = TimeEvent.entries.associateWith { if (it == TimeEvent.SUNRISE) Rounding.DOWN else Rounding.UP }
    private val DIYANET_OFFSETS = mapOf(TimeEvent.SUNRISE to -7, TimeEvent.DHUHR to 5, TimeEvent.ASR to 4, TimeEvent.MAGHRIB to 7)

    private fun angles(
        id: String,
        fajr: Double,
        isha: Double,
        evidence: Evidence,
        rule: HighLatitudeRule = HighLatitudeRule.AngleBased,
        build: PrayerMethod.() -> PrayerMethod = { this },
    ) = PrayerMethod(id = id, fajr = FajrRule.Angle(fajr), isha = IshaRule.Angle(isha), highLatitude = rule, evidence = evidence).build()

    private fun published(source: String) = Evidence(EvidenceKind.PUBLISHED, source)
    private fun tables(source: String) = Evidence(EvidenceKind.FROM_TABLES, source)
    private fun approximate(source: String) = Evidence(EvidenceKind.APPROXIMATE, source)

    val MWL_ANGLE = angles("mwl_angle", 18.0, 17.0, approximate("ICCI's 2026 website code, not ICCI's own table")) {
        copy(ishaLatest = LocalTime.of(22, 45), offsets = mapOf(TimeEvent.DHUHR to 1))
    }

    val MDF_12 = angles("mdf_12", 12.0, 12.0, tables("The French practice in the mosque dataset (12° and 12°)"), HighLatitudeRule.AngleBasedAbsentNights)

    // M3 fit (fits/rhone_ctir): Maghrib + 2, the five prayers up and sunrise down, Fajr, Isha and Asr on a late-day sun (E4).
    val RHONE_CTIR = angles("rhone_ctir", 14.5, 13.5, tables("The Conseil des Mosquées du Rhône's 2020 unified calendar (fits/rhone_ctir)")) {
        copy(
            offsets = mapOf(TimeEvent.DHUHR to 5, TimeEvent.MAGHRIB to 2),
            rounding = UP_FIVE_DOWN_SUNRISE,
            sunEvaluation = listOf(TimeEvent.FAJR, TimeEvent.ISHA, TimeEvent.ASR).associateWith { SunEvaluation.KACST },
        )
    }

    val BE_SUNRISE_90 = PrayerMethod(
        id = "be_sunrise_90",
        fajr = FajrRule.MinutesBeforeSunrise(90),
        isha = IshaRule.Angle(18.0),
        highLatitude = HighLatitudeRule.AngleBased,
        evidence = tables("Belgium's common practice in the mosque dataset"),
    )

    val FAJR15_ISHA90 = PrayerMethod(
        id = "fajr15_isha90",
        fajr = FajrRule.Angle(15.0),
        isha = IshaRule.MinutesAfter(90),
        highLatitude = HighLatitudeRule.AngleBased,
        evidence = tables("Common in Switzerland and Germany in the mosque dataset"),
    )

    /**
     * 15° and 15° as Europe's mosques practise it (D4, 5.6): France's most common after 12° and 12°, and beside MWL in
     * Italy, Spain, Switzerland, the Netherlands and the UK. The angles are ISNA's, but Europe's lists offer them under
     * the practice, not under a North American society's name (owner, 2026-10-17); `isna` stays among the others.
     */
    val FAJR15_ISHA15 = PrayerMethod(
        id = "fajr15_isha15",
        fajr = FajrRule.Angle(15.0),
        isha = IshaRule.Angle(15.0),
        highLatitude = HighLatitudeRule.AngleBased,
        evidence = tables("Common in mosques in France, Italy, Spain, Switzerland, the Netherlands and the UK in the mosque dataset"),
    )

    val GMP = PrayerMethod(
        id = "gmp",
        fajr = FajrRule.Angle(18.0),
        isha = IshaRule.MinutesAfter(87),
        offsets = mapOf(TimeEvent.DHUHR to 5, TimeEvent.MAGHRIB to 3),
        highLatitude = HighLatitudeRule.MoonsightingSeasonal(fajrOnly = true),
        evidence = tables("The Grande Mosquée de Paris's October 2026 timetable, all six times within 1 min"),
    )

    // M3 fit (fits/apbif): APBIF's Paris table is perpetual (its 2025 pages equal 2026's), its sun about 2014 (E5);
    // the angles, offsets, horizon and rounding confirmed, and Asr on the apparent sun with the noon shadow at Dhuhr's
    // instant (332 Asr windows against 223), which every town left out of the fit confirms.
    val APBIF = angles("apbif", 18.0, 12.0, published("APBIF's methodology page and its 2026 tables (fits/apbif)"), HighLatitudeRule.MiddleOfNight(ramped = false)) {
        copy(
            offsets = mapOf(TimeEvent.FAJR to 6, TimeEvent.DHUHR to 6, TimeEvent.ASR to 6, TimeEvent.MAGHRIB to 6),
            horizon = Horizons(HorizonModel.Fixed(-1.0), HorizonModel.Fixed(-1.0)),
            rounding = Rounding.ALL_NEAREST + mapOf(TimeEvent.SUNRISE to Rounding.DOWN, TimeEvent.MAGHRIB to Rounding.UP),
            asrSun = AsrSun.APPARENT,
            sunEvaluation = mapOf(TimeEvent.ASR to SunEvaluation.SHADOW_AT_DHUHR),
        )
    }

    val DIYANET_EU = angles("diyanet_eu", 18.0, 16.0, tables("Diyanet's 2021 Congress criteria, checked on its 2027 European tables (fits/diyanet_takdir)"), HighLatitudeRule.DiyanetTakdir) {
        copy(offsets = DIYANET_OFFSETS, polar = PolarFallback.DIYANET_CLAMP, minDayNightHours = 5, sunEvaluation = DAILY_0H)
    }

    val DIYANET_TR = angles("diyanet_tr", 18.0, 17.0, published("Diyanet İşleri Başkanlığı"), HighLatitudeRule.DiyanetTakdir) {
        copy(offsets = DIYANET_OFFSETS, polar = PolarFallback.DIYANET_CLAMP, minDayNightHours = 5, sunEvaluation = DAILY_0H)
    }

    // M3 fit (fits/irn, all 358 of IRN's 2026 tables): the sun once at 12:00 Norwegian time for sunrise, the transit,
    // Asr and sunset, and the declination at the event with the equation of time at that noon for the twilights (S4);
    // sunrise − 2 and Maghrib + 5, Dhuhr + 5 and + 5 more from 21 March to 20 September; every time rounded down;
    // H7 south of 63.56°N and H8 from there, on the zones' switch days.
    val IRN = angles("irn", 16.0, 15.0, tables("Islamic Council Norway's 2026 tables (fits/irn)"), HighLatitudeRule.Irn) {
        copy(
            offsets = mapOf(TimeEvent.SUNRISE to -2, TimeEvent.DHUHR to 5, TimeEvent.MAGHRIB to 5),
            datedOffsets = listOf(DatedOffset(TimeEvent.DHUHR, 3, 21, 9, 20, 5.0)),
            rounding = TimeEvent.entries.associateWith { Rounding.DOWN },
            sunEvaluation = listOf(TimeEvent.SUNRISE, TimeEvent.DHUHR, TimeEvent.ASR, TimeEvent.MAGHRIB).associateWith { SunEvaluation.DAILY_LOCAL_NOON } +
                listOf(TimeEvent.FAJR, TimeEvent.ISHA).associateWith { SunEvaluation.EVENT_DECLINATION },
            polar = PolarFallback.IRN_MECCA_CYCLE,
        )
    }

    val RU_DUM_RT = angles("ru_dum_rt", 18.0, 15.0, tables("DUM RT's 2026 workbook for 44 Tatarstan places"), HighLatitudeRule.DumRt) {
        copy(asrShadow = 2)
    }

    // M3 fit (fits/bosnia_iz): the sun once a day at 0 h UT on all six times (E4); the Takvim's Table 1 (page 12) as
    // takvimMeans, north then south with the printed signs (6.2, 8.8).
    val BOSNIA_IZ = angles("bosnia_iz", 18.0, 16.0, published("The Islamic Community of Bosnia and Herzegovina's Takvim (fits/bosnia_iz)")) {
        copy(
            offsets = mapOf(TimeEvent.SUNRISE to -6, TimeEvent.DHUHR to 1, TimeEvent.MAGHRIB to 6),
            sunEvaluation = DAILY_0H,
            takvimMeans = listOf(
                3.40, 1.90, 0.15, -1.68, -3.33, -4.30, -3.90, -2.35, -0.60, 1.25, 2.98, 3.98,
                -2.80, -1.58, -0.13, 1.33, 2.73, 3.53, 3.18, 1.95, 0.50, -1.03, -2.43, -3.23,
            ),
        )
    }

    // M3 fit (fits/hr_miz_zagreb, the Ramadan 2025 sheet): the TEXTBOOK sun once a day at 10 h UT; the asset's E6 point
    // 45.81444N 15.90158E. The summer form runs to 1 September (the 2021 sheet and the mosque's year), and its 113 is
    // counted from the computed sunrise, so the printed Fajr is the printed sunrise − 105 (7.4).
    val HR_MIZ_ZAGREB = PrayerMethod(
        id = "hr_miz_zagreb",
        fajr = FajrRule.Angle(19.3),
        isha = IshaRule.MinutesAfter(80),
        offsets = mapOf(TimeEvent.SUNRISE to -8, TimeEvent.DHUHR to 1, TimeEvent.MAGHRIB to 5),
        rounding = Rounding.ALL_NEAREST + mapOf(TimeEvent.FAJR to Rounding.UP, TimeEvent.SUNRISE to Rounding.UP, TimeEvent.MAGHRIB to Rounding.UP),
        sunEvaluation = TimeEvent.entries.associateWith { SunEvaluation.DAILY_10H_UT },
        sunModel = SunModel.TEXTBOOK,
        highLatitude = HighLatitudeRule.SummerForm(3, 25, 9, 1, fajrBeforeSunrise = 113),
        evidence = tables("MIZ Zagreb's Ramadan 2025 sheet (fits/hr_miz_zagreb)"),
    )

    /** Sarajevo's point in the IZ BiH's Takvim, where Slovenia's Isha is computed (8.8). */
    private val SARAJEVO = LatLngE5.of(43.86667, 18.43333)

    // M3 fit (fits/si_islamska_skupnost, October–December 2026): the TEXTBOOK sun at the event instant, the asset's E6
    // point 45.76608N 14.41841E. Its Isha is the IZ BiH's former Sarajevo Isha (17° at Sarajevo, the sun at 0 h UT)
    // + 18 min on 91 of the feed's 92 days; the summer form's 111 and 72 keep the printed «sunrise − 105» and
    // «Maghrib + 90» of the Ramadan 2020 sheet under these offsets, until vaktija.si's summer months confirm them.
    val SI_ISLAMSKA_SKUPNOST = PrayerMethod(
        id = "si_islamska_skupnost",
        fajr = FajrRule.Angle(19.0),
        isha = IshaRule.Angle(17.0, at = SARAJEVO, atNameAr = "سراييفو"),
        offsets = mapOf(
            TimeEvent.FAJR to 1, TimeEvent.SUNRISE to -5, TimeEvent.DHUHR to 2, TimeEvent.ASR to -1, TimeEvent.MAGHRIB to 5, TimeEvent.ISHA to 18,
        ),
        rounding = Rounding.ALL_NEAREST + mapOf(TimeEvent.ASR to Rounding.UP),
        sunEvaluation = mapOf(TimeEvent.ISHA to SunEvaluation.DAILY_0H_UT),
        sunModel = SunModel.TEXTBOOK,
        highLatitude = HighLatitudeRule.SummerForm(4, 24, 5, 23, fajrBeforeSunrise = 111, ishaAfterMaghrib = 72),
        evidence = tables("vaktija.si, October–December 2026 (fits/si_islamska_skupnost)"),
    )

    // M3 fit (fits/luxembourg_shoura): 12.8° / 12.8° (the windows' own 12.815° and 12.787°); Asr and Isha on the sun at
    // 10 h UT and Maghrib at 0 h UT (E4); Isha up. Every held-out time within 1 min but one Asr day.
    val LUXEMBOURG_SHOURA = angles(
        "luxembourg_shoura", 12.8, 12.8, tables("The Shoura's 2026 table (fits/luxembourg_shoura)"), HighLatitudeRule.AngleBasedAbsentNights,
    ) {
        copy(
            rounding = Rounding.ALL_NEAREST + mapOf(TimeEvent.ISHA to Rounding.UP),
            sunEvaluation = mapOf(
                TimeEvent.ASR to SunEvaluation.DAILY_10H_UT, TimeEvent.MAGHRIB to SunEvaluation.DAILY_0H_UT, TimeEvent.ISHA to SunEvaluation.DAILY_10H_UT,
            ),
        )
    }

    // M3 fit (fits/ie_ifi): the IFI's perpetual Dublin table, its intervals and minutes as 36 E18 values a time (Dhuhr's
    // + 2 among them); the 33 towns keep Dublin's intervals at their own sunrise and Maghrib (the asset's zones, Q23).
    val IE_IFI = PrayerMethod(
        id = "ie_ifi",
        fajr = FajrRule.MinutesBeforeSunrise(100),
        isha = IshaRule.MinutesAfter(90),
        seasonalOffsets = mapOf(
            TimeEvent.FAJR to listOf(
                -17.37, -14.57, -11.60, -7.97, -4.75, -1.52, 0.36, 1.58, 1.50, 0.29, -1.81, -5.11, -8.99, -13.83, -19.06, -24.29, -29.10, -30.80,
                -29.62, -26.20, -21.33, -16.46, -11.79, -7.67, -5.30, -2.94, -2.28, -2.37, -3.92, -5.47, -8.06, -10.87, -13.69, -16.01, -18.01, -17.69,
            ),
            TimeEvent.SUNRISE to listOf(
                -3.02, -2.64, -2.27, -1.88, -1.50, -1.12, -0.93, -0.74, -0.81, -1.09, -1.38, -1.66, -1.94, -2.23, -2.36, -2.76, -3.14, -3.75,
                -4.36, -4.78, -4.93, -5.08, -4.88, -4.69, -4.49, -4.30, -4.55, -4.57, -4.87, -5.18, -5.08, -4.99, -4.58, -4.18, -3.79, -3.40,
            ),
            TimeEvent.DHUHR to listOf(
                1.70, 1.48, 1.58, 1.75, 1.94, 2.14, 2.53, 2.60, 2.45, 2.44, 2.07, 1.96, 1.84, 1.73, 2.01, 1.91, 1.81, 1.71,
                1.61, 1.51, 1.66, 1.81, 1.95, 2.37, 2.57, 2.59, 2.62, 2.31, 1.90, 1.76, 1.68, 1.79, 1.90, 1.91, 1.92, 1.92,
            ),
            TimeEvent.ASR to listOf(
                1.01, 0.27, -0.01, -0.28, -0.09, 0.16, 0.35, 0.51, 0.66, 0.67, 0.55, 0.44, 0.71, 0.97, 1.20, 1.42, 1.61, 1.80,
                1.99, 1.97, 2.33, 2.68, 3.36, 3.81, 4.25, 4.52, 4.42, 4.38, 4.18, 3.99, 3.80, 3.62, 3.43, 2.96, 2.49, 1.75,
            ),
            TimeEvent.MAGHRIB to listOf(
                2.17, 1.48, 0.79, 0.65, 0.52, 0.69, 0.86, 0.88, 0.91, 0.74, 0.57, 0.40, 0.49, 0.86, 1.24, 1.99, 2.75, 3.51,
                4.26, 4.56, 5.19, 5.60, 6.02, 6.44, 6.85, 6.81, 6.57, 6.33, 5.79, 5.58, 5.36, 5.14, 4.92, 4.70, 3.98, 3.25,
            ),
            TimeEvent.ISHA to listOf(
                18.00, 15.75, 12.84, 9.92, 7.01, 4.68, 2.89, 1.79, 1.74, 2.33, 4.44, 6.55, 9.63, 13.64, 7.51, 0.0, 0.0, 0.0,
                0.0, 2.24, 12.33, 13.41, 9.66, 6.44, 3.88, 2.44, 2.09, 1.74, 2.91, 4.91, 6.90, 9.89, 12.75, 15.60, 17.59, 18.17,
            ),
        ),
        highLatitude = HighLatitudeRule.AngleBased,
        evidence = tables("The IFI's perpetual Dublin table (fits/ie_ifi)"),
    )

    val MCW = angles("mcw", 18.0, 18.0, approximate("Moonsighting Committee Worldwide: coefficients from adhan, MIT; no primary text"), HighLatitudeRule.MoonsightingSeasonal()) {
        copy(offsets = mapOf(TimeEvent.DHUHR to 5, TimeEvent.MAGHRIB to 3))
    }

    val LONDON_UNIFIED = angles("london_unified", 18.0, 18.0, approximate("A proxy for the London Unified Timetable"), HighLatitudeRule.MoonsightingSeasonal()) {
        copy(offsets = mapOf(TimeEvent.SUNRISE to -3, TimeEvent.DHUHR to 5, TimeEvent.MAGHRIB to 3))
    }

    val UK_18_NEAREST_DAY = angles("uk_18_nearest_day", 18.0, 18.0, approximate("Bradford 1983; the Isha angle is assumed"), HighLatitudeRule.NearestDay(lastClockTime = true))

    val CIL_LISBON = PrayerMethod(
        id = "cil_lisbon",
        fajr = FajrRule.Angle(18.0),
        isha = IshaRule.MinutesAfter(77),
        offsets = mapOf(TimeEvent.MAGHRIB to 3),
        highLatitude = HighLatitudeRule.AngleBased,
        evidence = approximate("Aladhan only"),
    )

    // M3 fit (fits/dum_rf): DUM RF's Moscow table is perpetual (computed for 2025, E5; only its Isha ramp changes);
    // at 2025 every time rounds to the nearest minute and Asr takes the sun at 10 h UT; one zone «موسكو» (the asset).
    val DUM_RF = angles("dum_rf", 18.0, 15.0, tables("DUM RF's monthly Moscow tables (fits/dum_rf)"), HighLatitudeRule.DumRf) {
        copy(
            offsets = mapOf(TimeEvent.SUNRISE to -5, TimeEvent.DHUHR to 5, TimeEvent.MAGHRIB to 5),
            rounding = Rounding.ALL_NEAREST,
            sunEvaluation = mapOf(TimeEvent.ASR to SunEvaluation.DAILY_10H_UT),
        )
    }

    val ECFR_90 = PrayerMethod(
        id = "ecfr_90",
        fajr = FajrRule.MinutesBeforeSunrise(90),
        isha = IshaRule.MinutesAfter(90),
        highLatitude = HighLatitudeRule.AngleBased,
        evidence = approximate("An ECFR clause known only second-hand"),
    )

    val AL_KMSH = angles("al_kmsh", 18.0, 17.0, tables("KMSH's 2026 Kalendari")) {
        copy(offsets = DIYANET_OFFSETS, sunEvaluation = DAILY_0H)
    }

    // M3 fit (fits/xk_bik): BIK's Takvim is perpetual (its 2025 edition equals 2026 by date), so 2026–2029 are pooled;
    // 18° / 17° at 42.5N 21E, the sun at the event instant, nearest, and 36 E18 values a time.
    private val XK_BIK_SEASONAL = mapOf(
        TimeEvent.FAJR to listOf(
            15.69, 14.83, 16.34, 16.59, 14.59, 15.47, 15.34, 15.79, 16.54, 13.75, 15.29, 15.06, 12.76, 11.65, 12.41, 10.83, 8.58, 9.92,
            9.06, 8.66, 9.08, 10.35, 11.77, 13.18, 13.47, 13.03, 14.57, 12.52, 13.90, 14.11, 13.91, 14.85, 15.23, 15.55, 15.88, 17.16,
        ),
        TimeEvent.SUNRISE to listOf(
            -3.92, -4.18, -2.78, -4.30, -3.60, -4.23, -6.32, -5.54, -6.90, -7.52, -7.32, -7.94, -6.60, -8.11, -8.74, -8.80, -10.01, -7.74,
            -8.88, -9.78, -11.51, -8.28, -8.56, -9.42, -8.61, -8.50, -9.25, -8.86, -8.24, -6.48, -5.48, -6.03, -3.36, -4.03, -3.63, -2.89,
        ),
        TimeEvent.DHUHR to listOf(
            4.63, 3.35, 4.86, 4.93, 3.40, 4.84, 2.08, 3.95, 4.22, 3.18, 2.78, 2.21, 2.78, 1.83, 2.04, 1.94, 1.13, 2.91,
            2.85, 1.88, 1.27, 2.68, 4.17, 2.48, 3.98, 4.05, 3.90, 2.54, 3.59, 4.27, 4.39, 4.31, 4.50, 4.63, 4.80, 5.70,
        ),
        TimeEvent.ASR to listOf(
            8.94, 7.43, 6.97, 7.00, 6.32, 6.58, 4.68, 3.52, 2.88, 3.13, 1.95, 0.32, 2.89, 1.20, 2.72, 3.15, 0.85, 4.11,
            2.91, 3.59, 2.59, 3.54, 5.19, 2.51, 5.79, 6.50, 5.70, 4.84, 9.31, 8.71, 9.40, 8.65, 8.40, 9.05, 9.53, 11.53,
        ),
        TimeEvent.MAGHRIB to listOf(
            6.97, 6.71, 7.09, 7.22, 7.41, 7.89, 7.06, 7.64, 7.55, 7.38, 7.32, 7.14, 6.92, 5.87, 6.92, 6.36, 5.34, 9.03,
            7.10, 7.14, 6.85, 8.27, 8.70, 8.08, 8.96, 8.67, 8.40, 7.63, 8.76, 8.17, 7.48, 6.46, 6.91, 7.47, 7.63, 8.42,
        ),
        TimeEvent.ISHA to listOf(
            10.32, 9.97, 9.74, 8.66, 9.80, 10.51, 9.50, 10.79, 9.76, 9.38, 8.98, 8.81, 9.08, 5.69, 7.38, 6.86, 5.59, 10.00,
            9.01, 9.82, 8.73, 10.02, 12.29, 11.42, 11.91, 10.75, 11.84, 9.62, 11.14, 10.05, 10.06, 8.32, 9.18, 10.21, 9.92, 10.65,
        ),
    )

    val XK_BIK = angles("xk_bik", 18.0, 17.0, tables("BIK's perpetual Takvim, 2026 edition (fits/xk_bik)")) { copy(seasonalOffsets = XK_BIK_SEASONAL) }

    val MK_BFI = PrayerMethod(
        id = "mk_bfi",
        fajr = FajrRule.MinutesBeforeSunrise(50),
        isha = IshaRule.Angle(15.0),
        offsets = mapOf(TimeEvent.SUNRISE to -7, TimeEvent.DHUHR to 7, TimeEvent.ASR to 2, TimeEvent.MAGHRIB to 9),
        highLatitude = HighLatitudeRule.AngleBased,
        evidence = approximate("One day of bfi.mk's Skopje times; fitted in M3"),
    )

    // M3 fit (fits/bg_grand_mufti, October 2026): every city is Sofia's table plus one whole-minute shift (the asset's
    // 47 shifted zones); 18.2° / 17.2°, the sun at 0 h UT; refitted as the collector's months arrive (12).
    val BG_GRAND_MUFTI = angles("bg_grand_mufti", 18.2, 17.2, tables("The Chief Mufti's Office's tables, October 2026 (fits/bg_grand_mufti)"), HighLatitudeRule.DiyanetTakdir) {
        copy(
            offsets = mapOf(TimeEvent.SUNRISE to -8, TimeEvent.DHUHR to 5, TimeEvent.ASR to 4, TimeEvent.MAGHRIB to 8),
            rounding = Rounding.ALL_NEAREST + mapOf(
                TimeEvent.FAJR to Rounding.DOWN, TimeEvent.SUNRISE to Rounding.UP, TimeEvent.ASR to Rounding.UP, TimeEvent.ISHA to Rounding.DOWN,
            ),
            polar = PolarFallback.DIYANET_CLAMP, minDayNightHours = 5, sunEvaluation = DAILY_0H,
        )
    }

    val EUROPE: List<PrayerMethod> = listOf(
        MWL_ANGLE, MDF_12, RHONE_CTIR, BE_SUNRISE_90, FAJR15_ISHA90, FAJR15_ISHA15, GMP, APBIF, DIYANET_EU, DIYANET_TR, IRN, RU_DUM_RT,
        BOSNIA_IZ, HR_MIZ_ZAGREB, SI_ISLAMSKA_SKUPNOST, LUXEMBOURG_SHOURA, IE_IFI, MCW, LONDON_UNIFIED, UK_18_NEAREST_DAY,
        CIL_LISBON, DUM_RF, ECFR_90, AL_KMSH, XK_BIK, MK_BFI, BG_GRAND_MUFTI,
    )

    // ---- Overseas France (7.6) ----

    /** The Centre Islamique de la Réunion's elevation shifts: from each listed height up to the next. */
    val CIR_SHIFTS = listOf(
        ElevationShift(100, 1.0), ElevationShift(200, 1.5), ElevationShift(300, 2.0), ElevationShift(500, 3.0),
        ElevationShift(800, 4.0), ElevationShift(1100, 5.0), ElevationShift(1600, 6.0), ElevationShift(2100, 7.0),
    )

    val RE_CIR = angles("re_cir", 18.0, 18.0, tables("The CIR's tables of 2023–2026 and the Bras-Fusil display")) {
        copy(offsets = mapOf(TimeEvent.MAGHRIB to 2), rounding = UP_FIVE_DOWN_SUNRISE, elevationShifts = CIR_SHIFTS)
    }

    /** The Moroni table less 3 min on all five prayers: Fajr = sunrise − 76, Dhuhr + 1, Maghrib = sunset, Isha + 74, Asr − 168 (7.6). */
    val YT_TRADITIONAL = PrayerMethod(
        id = "yt_traditional",
        fajr = FajrRule.MinutesBeforeSunrise(76),
        isha = IshaRule.MinutesAfter(74),
        asr = AsrRule.BeforeMaghrib(168),
        offsets = mapOf(TimeEvent.DHUHR to 1),
        highLatitude = HighLatitudeRule.AngleBased,
        evidence = tables("Mayotte's published table and mosques"),
    )

    val GF_A2CMGF = angles("gf_a2cmgf", 18.0, 17.0, published("A2CMGF's calendars name the Muslim World League")) {
        copy(rounding = UP_FIVE_DOWN_SUNRISE)
    }

    // M3 fit (fits/mq_ccim): the page computes with the sun once a day at 0 h UT (E4).
    val MQ_CCIM = angles("mq_ccim", 18.0, 17.0, tables("The IslamicFinder page the CCIM links, October 2026")) {
        copy(offsets = mapOf(TimeEvent.DHUHR to 1, TimeEvent.MAGHRIB to 1), rounding = UP_FIVE_DOWN_SUNRISE, sunEvaluation = DAILY_0H)
    }

    val OVERSEAS: List<PrayerMethod> = listOf(RE_CIR, YT_TRADITIONAL, GF_A2CMGF, MQ_CCIM)
}

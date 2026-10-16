package com.tunisianprayertimes.method

import com.tunisianprayertimes.engine.HighLatitudeRule
import com.tunisianprayertimes.engine.HorizonModel
import com.tunisianprayertimes.engine.Horizons
import com.tunisianprayertimes.engine.PolarFallback
import com.tunisianprayertimes.engine.SunEvaluation
import com.tunisianprayertimes.engine.TimeEvent
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

    val GMP = PrayerMethod(
        id = "gmp",
        fajr = FajrRule.Angle(18.0),
        isha = IshaRule.MinutesAfter(87),
        offsets = mapOf(TimeEvent.DHUHR to 5, TimeEvent.MAGHRIB to 3),
        highLatitude = HighLatitudeRule.MoonsightingSeasonal(fajrOnly = true),
        evidence = tables("The Grande Mosquée de Paris's October 2026 timetable, all six times within 1 min"),
    )

    val APBIF = angles("apbif", 18.0, 12.0, published("APBIF's methodology page and its 2026 tables"), HighLatitudeRule.MiddleOfNight(ramped = false)) {
        copy(
            offsets = mapOf(TimeEvent.FAJR to 6, TimeEvent.DHUHR to 6, TimeEvent.ASR to 6, TimeEvent.MAGHRIB to 6),
            horizon = Horizons(HorizonModel.Fixed(-1.0), HorizonModel.Fixed(-1.0)),
            rounding = Rounding.ALL_NEAREST + mapOf(TimeEvent.SUNRISE to Rounding.DOWN, TimeEvent.MAGHRIB to Rounding.UP),
        )
    }

    val DIYANET_EU = angles("diyanet_eu", 18.0, 16.0, tables("Re-derived from IGGÖ's and Diyanet's tables; summer times may differ by a few minutes"), HighLatitudeRule.DiyanetTakdir) {
        copy(offsets = DIYANET_OFFSETS, polar = PolarFallback.DIYANET_CLAMP, minDayNightHours = 5, sunEvaluation = DAILY_0H)
    }

    val DIYANET_TR = angles("diyanet_tr", 18.0, 17.0, published("Diyanet İşleri Başkanlığı"), HighLatitudeRule.DiyanetTakdir) {
        copy(offsets = DIYANET_OFFSETS, polar = PolarFallback.DIYANET_CLAMP, minDayNightHours = 5, sunEvaluation = DAILY_0H)
    }

    /** Dhuhr + 10 from about 21 March to 22 September and + 5 otherwise, as 36 points a year (E18); fitted on IRN's tables in M3. */
    private val IRN_DHUHR = List(PrayerMethod.SEASONAL_POINTS) { k -> if (k in 8..25) 5.0 else 0.0 }

    val IRN = angles("irn", 16.0, 15.0, tables("Islamic Council Norway's 2026 tables; switch days provisional until fitted"), HighLatitudeRule.Irn) {
        copy(
            offsets = mapOf(TimeEvent.DHUHR to 5, TimeEvent.MAGHRIB to 4),
            seasonalOffsets = mapOf(TimeEvent.DHUHR to IRN_DHUHR),
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

    val HR_MIZ_ZAGREB = PrayerMethod(
        id = "hr_miz_zagreb",
        fajr = FajrRule.Angle(19.0),
        isha = IshaRule.MinutesAfter(80),
        offsets = mapOf(TimeEvent.SUNRISE to -7, TimeEvent.DHUHR to 1, TimeEvent.MAGHRIB to 6),
        highLatitude = HighLatitudeRule.SummerForm(3, 25, 8, 25, fajrBeforeSunrise = 105),
        evidence = tables("MIZ Zagreb's sheets (Ramadan 2025, 2020–2021); fitted in M3"),
    )

    val SI_ISLAMSKA_SKUPNOST = angles(
        "si_islamska_skupnost", 19.0, 18.0, tables("vaktija.si and the Community's Ramadan 2020 sheet; fitted in M3"),
        HighLatitudeRule.SummerForm(4, 24, 5, 23, fajrBeforeSunrise = 105, ishaAfterMaghrib = 90),
    ) {
        copy(offsets = mapOf(TimeEvent.SUNRISE to -5, TimeEvent.DHUHR to 2, TimeEvent.MAGHRIB to 6))
    }

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

    val IE_IFI = PrayerMethod(
        id = "ie_ifi",
        fajr = FajrRule.MinutesBeforeSunrise(100),
        isha = IshaRule.MinutesAfter(90),
        offsets = mapOf(TimeEvent.DHUHR to 2),
        highLatitude = HighLatitudeRule.AngleBased,
        evidence = tables("The IFI's perpetual Dublin table; its seasonal intervals are fitted in M3"),
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

    val DUM_RF = angles("dum_rf", 18.0, 15.0, tables("DUM RF's monthly Moscow tables"), HighLatitudeRule.DumRf) {
        copy(
            offsets = mapOf(TimeEvent.SUNRISE to -5, TimeEvent.DHUHR to 5, TimeEvent.MAGHRIB to 5),
            rounding = Rounding.ALL_NEAREST + mapOf(
                TimeEvent.FAJR to Rounding.UP, TimeEvent.SUNRISE to Rounding.UP,
                TimeEvent.MAGHRIB to Rounding.DOWN, TimeEvent.ISHA to Rounding.DOWN,
            ),
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

    val XK_BIK = angles("xk_bik", 18.0, 17.0, tables("BIK's 2026 Takvim; its seasonal offsets are fitted in M3"))

    val MK_BFI = PrayerMethod(
        id = "mk_bfi",
        fajr = FajrRule.MinutesBeforeSunrise(50),
        isha = IshaRule.Angle(15.0),
        offsets = mapOf(TimeEvent.SUNRISE to -7, TimeEvent.DHUHR to 7, TimeEvent.ASR to 2, TimeEvent.MAGHRIB to 9),
        highLatitude = HighLatitudeRule.AngleBased,
        evidence = approximate("One day of bfi.mk's Skopje times; fitted in M3"),
    )

    val BG_GRAND_MUFTI = angles("bg_grand_mufti", 18.0, 17.0, approximate("The Chief Mufti's Office's city tables; fitted in M3"), HighLatitudeRule.DiyanetTakdir) {
        copy(offsets = DIYANET_OFFSETS, polar = PolarFallback.DIYANET_CLAMP, minDayNightHours = 5, sunEvaluation = DAILY_0H)
    }

    val EUROPE: List<PrayerMethod> = listOf(
        MWL_ANGLE, MDF_12, RHONE_CTIR, BE_SUNRISE_90, FAJR15_ISHA90, GMP, APBIF, DIYANET_EU, DIYANET_TR, IRN, RU_DUM_RT,
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

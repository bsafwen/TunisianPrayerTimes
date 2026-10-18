package com.tunisianprayertimes.method

import com.tunisianprayertimes.engine.HighLatitudeRule
import com.tunisianprayertimes.engine.HorizonModel
import com.tunisianprayertimes.engine.Horizons
import com.tunisianprayertimes.engine.SunEvaluation
import com.tunisianprayertimes.engine.SunModel
import com.tunisianprayertimes.engine.TimeEvent
import java.time.LocalDate
import java.time.LocalTime

/**
 * The 23 profiles of plan 8.7: 19 authority profiles and the four fitted country profiles of A3. Each
 * starts from the values of 8.2 and 8.7; the AM2 fits (and the AM10 ones for KW, PS, SY, MR, BH and LB)
 * finalise them, with their zones (6.5) and fit records (`fit`, 6.3).
 */
object ArabAuthorityProfiles {
    private val ALL_UP = TimeEvent.entries.associateWith { Rounding.UP }
    private val ALL_DOWN = TimeEvent.entries.associateWith { Rounding.DOWN }

    private fun profile(
        id: String,
        fajr: FajrRule,
        isha: IshaRule,
        evidence: Evidence,
        build: PrayerMethod.() -> PrayerMethod = { this },
    ) = PrayerMethod(id = id, fajr = fajr, isha = isha, highLatitude = HighLatitudeRule.AngleBased, evidence = evidence).build()

    private fun angle(deg: Double) = FajrRule.Angle(deg)
    private fun ishaAngle(deg: Double) = IshaRule.Angle(deg)
    private fun tables(source: String) = Evidence(EvidenceKind.FROM_TABLES, source)
    private fun approximate(source: String) = Evidence(EvidenceKind.APPROXIMATE, source)

    /**
     * KACST's times come 1 s after the engine's VSOP87 times on every computed time (AM2 fit,
     * scripts/prayer_formula/fits/sa_umm_al_qura): a constant E18 value; Isha follows Maghrib.
     */
    private val KACST_SECOND = listOf(TimeEvent.FAJR, TimeEvent.SUNRISE, TimeEvent.DHUHR, TimeEvent.ASR, TimeEvent.MAGHRIB)
        .associateWith { List(PrayerMethod.SEASONAL_POINTS) { 1.0 / 60 } }

    // AM2 fit: VSOP87 and the 1 s shift give 99.4–99.8 % exact held out per time (13 tables, 4,518 days).
    val SA_UMM_AL_QURA = profile("sa_umm_al_qura", angle(18.5), IshaRule.MinutesAfter(90, ramadan = 120),
        tables("Re-derived from KACST's tables and its prayer-times service")) {
        copy(
            rounding = TimeEvent.entries.associateWith {
                when (it) {
                    TimeEvent.SUNRISE -> Rounding.DOWN
                    TimeEvent.ISHA -> Rounding.NEAREST
                    else -> Rounding.UP
                }
            },
            horizon = Horizons(HorizonModel.Fixed(-0.83), HorizonModel.Standard),
            sunEvaluation = mapOf(TimeEvent.FAJR to SunEvaluation.KACST, TimeEvent.ASR to SunEvaluation.KACST),
            ramadanDays = RamadanDays.UMM_AL_QURA_COMPUTED,
            sunModel = SunModel.VSOP87,
            seasonalOffsets = KACST_SECOND,
        )
    }

    // AM2 fit (scripts/prayer_formula/fits/eg_egsa): every city's sunrise, Dhuhr and sunset agree only on a
    // −0.8435° horizon; VSOP87 leaves the widest common ranges. 97.4 % exact held out, all within 1 minute.
    val EG_EGSA = profile("eg_egsa", angle(19.5), ishaAngle(17.5), tables("The Egyptian General Survey Authority's city tables (Fajr angle stated)")) {
        copy(
            horizon = Horizons(HorizonModel.Fixed(-0.8435), HorizonModel.Fixed(-0.8435)),
            sunModel = SunModel.VSOP87,
        )
    }

    val MA_HABOUS = profile("ma_habous", angle(19.0), ishaAngle(17.0), tables("The Ministry of Habous's locality tables")) {
        copy(
            offsets = mapOf(TimeEvent.SUNRISE to -1, TimeEvent.DHUHR to 5, TimeEvent.MAGHRIB to 2),
            rounding = Rounding.ALL_NEAREST + (TimeEvent.FAJR to Rounding.DOWN),
            // AM2 fit (fits/ma_habous): the height law of the 26 fitted localities at exact coordinates (8.2).
            horizon = Horizons(
                HorizonModel.PerZone(HorizonModel.SqrtHeight(baseDeg = 1.194, perSqrtMetreDeg = 0.0244)),
                HorizonModel.PerZone(HorizonModel.SqrtHeight(baseDeg = 1.190, perSqrtMetreDeg = 0.0205)),
            ),
            asrSun = AsrSun.APPARENT,
        )
    }

    // AM2 fit (fits/dz_marw): every value confirmed on the 1448 month split and the 1447 Algiers calendar.
    val DZ_MARW = profile("dz_marw", angle(18.0), ishaAngle(17.0), tables("The Ministry of Religious Affairs and Endowments' calendars, 1447 and 1448")) {
        copy(offsets = mapOf(TimeEvent.MAGHRIB to 3), asr = AsrRule.SignedShadow, rounding = ALL_UP)
    }

    // AM2 fit (scripts/prayer_formula/fits/ly_awqaf): 18.357° (window 18.3557–18.3579), reference year 2006.
    val LY_AWQAF = profile("ly_awqaf", angle(18.357), ishaAngle(18.357), tables("The General Authority of Awqaf's perpetual table, as printed (reference year 2006)")) {
        copy(
            referenceYear = 2006,
            offsets = mapOf(TimeEvent.DHUHR to 4, TimeEvent.MAGHRIB to 4),
            rounding = ALL_DOWN,
            sunModel = SunModel.TEXTBOOK,
            sunEvaluation = TimeEvent.entries.associateWith { SunEvaluation.DAILY_10H_UT },
            twilightLatitudeScale = 0.99330562,
        )
    }

    // AM10 fit (fits/mr_islamic_affairs): fixed horizons from Nouakchott's printed sunrise and every capital's Maghrib,
    // Maghrib + 2 (the ministry's rule, notice of 2024-03-10), Asr on the apparent sun; the 2021 and 2022 tables'
    // standard horizons as + 1 on the fixed ones, and Ramadan 2022's 19°/17°. The interior capitals' sunrise − 1.5
    // is their zones' (E7). 98–99.6 % exact at reference year 2023, all within 1 minute.
    val MR_ISLAMIC_AFFAIRS = profile("mr_islamic_affairs", angle(19.5), ishaAngle(17.5), tables("The Ministry of Islamic Affairs's Ramadan tables, applied all year")) {
        copy(
            referenceYear = 2023,
            offsets = mapOf(TimeEvent.SUNRISE to 1, TimeEvent.MAGHRIB to 1),
            horizon = Horizons(HorizonModel.Fixed(-1.066), HorizonModel.Fixed(-1.051)),
            asrSun = AsrSun.APPARENT,
            revisions = listOf(
                MethodRevision(LocalDate.of(2022, 4, 2), MethodChange(fajr = angle(19.0), isha = ishaAngle(17.0))),
                MethodRevision(
                    LocalDate.of(2022, 5, 2),
                    MethodChange(offsets = mapOf(TimeEvent.SUNRISE to 0, TimeEvent.MAGHRIB to 2), fajr = angle(19.5), isha = ishaAngle(17.5)),
                ),
            ),
        )
    }

    val JO_AWQAF = profile("jo_awqaf", angle(18.0), ishaAngle(18.0), tables("The Ministry of Awqaf's calendar, computed with ICOP")) {
        copy(
            horizon = Horizons(HorizonModel.Refraction(), HorizonModel.Refraction()),
            asrSun = AsrSun.APPARENT,
            sunModel = SunModel.VSOP87,
        )
    }

    // AM10 fit (fits/ps_dar_al_ifta), Al-Aqsa's perpetual table at reference year 2016 (MEEUS): every time within 1 minute
    // there, which the app shows every year since A8's revision (2026-10-17).
    val PS_DAR_AL_IFTA = profile(
        "ps_dar_al_ifta", angle(18.065), ishaAngle(17.847),
        tables("Dar al-Ifta's perpetual Al-Aqsa table, as printed (reference year 2016)"),
    ) {
        copy(
            referenceYear = 2016,
            offsets = mapOf(TimeEvent.MAGHRIB to 5),
            rounding = mapOf(
                TimeEvent.FAJR to Rounding.DOWN, TimeEvent.SUNRISE to Rounding.UP, TimeEvent.DHUHR to Rounding.DOWN,
                TimeEvent.ASR to Rounding.DOWN, TimeEvent.MAGHRIB to Rounding.NEAREST, TimeEvent.ISHA to Rounding.UP,
            ),
            horizon = Horizons(HorizonModel.Fixed(-1.5559), HorizonModel.Fixed(-1.0643)),
            asrSun = AsrSun.APPARENT,
        )
    }

    // AM10 fit (fits/ps_gaza_awqaf), the Ministry's 2026 table (emailed to the developer 2026-10-18, every day) at Gaza
    // City's point: each time within 1 minute on 98.4–99.7 % of the days held out (month split), to the minute on 54–66 %;
    // the table is no day-by-day computation (its Dhuhr holds one minute for up to 26 days).
    val PS_GAZA_AWQAF = profile(
        "ps_gaza_awqaf", angle(19.4), ishaAngle(17.394),
        tables("Gaza's Ministry of Awqaf: its 2026 table, at Gaza City's point"),
    ) {
        copy(
            offsets = mapOf(TimeEvent.MAGHRIB to 3),
            rounding = Rounding.ALL_NEAREST + mapOf(TimeEvent.FAJR to Rounding.DOWN, TimeEvent.ISHA to Rounding.UP),
            asrSun = AsrSun.APPARENT,
        )
    }

    // AM10 fit (fits/lb_dar_al_fatwa), Beirut's year table at reference year 1931: Fajr 19.69° (window 19.6936–19.6943°),
    // Isha 17.50°, sunrise at −0.9379°, the sun once a day (Fajr at 0 h UT, the rest at 10 h UT: an almanac's table);
    // 78.5–95.8 % exact at the reference year, which the app shows every year (A8, revised 2026-10-17).
    val LB_DAR_AL_FATWA = profile("lb_dar_al_fatwa", angle(19.69), ishaAngle(17.5), tables("Dar al-Fatwa's Beirut imsakiya (Ramadan 1445)")) {
        copy(
            referenceYear = 1931,
            offsets = mapOf(TimeEvent.MAGHRIB to 4),
            rounding = Rounding.ALL_NEAREST + mapOf(TimeEvent.MAGHRIB to Rounding.UP, TimeEvent.DHUHR to Rounding.DOWN, TimeEvent.ASR to Rounding.DOWN),
            horizon = Horizons(HorizonModel.Fixed(-0.9379), HorizonModel.Standard),
            sunEvaluation = mapOf(
                TimeEvent.FAJR to SunEvaluation.DAILY_0H_UT, TimeEvent.SUNRISE to SunEvaluation.DAILY_10H_UT,
                TimeEvent.ASR to SunEvaluation.DAILY_10H_UT, TimeEvent.MAGHRIB to SunEvaluation.DAILY_10H_UT,
                TimeEvent.ISHA to SunEvaluation.DAILY_10H_UT,
            ),
        )
    }

    // AM10 refit 2026-10-18 (fits/sy_awqaf): the ministry's mosque screens («مسجدك») show aladhan.com's PrayTimes: MWL
    // 18°/17°, its low-precision sun once per time at PrayTimes' hours (E4 PRAYTIMES, S1 ALMANAC), the 0.833° horizon,
    // Dhuhr + 1 and Maghrib + 2, every time to the nearest minute; each region's table year, rows and Asr shadow hour are
    // its zone's (A8).
    val SY_AWQAF = profile("sy_awqaf", angle(18.0), ishaAngle(17.0), tables("The Ministry of Awqaf's mosque-screen year tables, each region's as shown")) {
        copy(
            offsets = mapOf(TimeEvent.DHUHR to 1, TimeEvent.MAGHRIB to 2),
            horizon = Horizons(HorizonModel.Fixed(-0.833), HorizonModel.Fixed(-0.833)),
            sunModel = SunModel.ALMANAC,
            sunEvaluation = TimeEvent.entries.associateWith { SunEvaluation.PRAYTIMES },
        )
    }

    // AM2 fit (fits/iq_sunni_endowment), Baghdad's 2026 table at reference year 2021: each zone's horizon from Hohenkerk and
    // Sinclair's refraction at its Accurate Times inputs (PerZone; SqrtHeight within 0.029° of that law at exact
    // coordinates), sunrise + 0 and Maghrib + 2, every time to the nearest, Asr's noon shadow at the published Dhuhr (E4
    // SHADOW_AT_DHUHR, as Accurate Times computes); 98.4–100 % exact at the reference year, which the app shows every year.
    val IQ_SUNNI_ENDOWMENT = profile("iq_sunni_endowment", angle(18.0), ishaAngle(17.0), tables("The Sunni Endowment's perpetual city tables, as printed (reference year 2021)")) {
        copy(
            referenceYear = 2021,
            offsets = mapOf(TimeEvent.DHUHR to 5, TimeEvent.ASR to 3, TimeEvent.MAGHRIB to 2),
            horizon = Horizons(
                HorizonModel.PerZone(HorizonModel.SqrtHeight(0.7987, 0.03853)),
                HorizonModel.PerZone(HorizonModel.SqrtHeight(0.7987, 0.03853)),
            ),
            asrSun = AsrSun.APPARENT,
            sunModel = SunModel.VSOP87,
            sunEvaluation = mapOf(TimeEvent.ASR to SunEvaluation.SHADOW_AT_DHUHR),
        )
    }

    // AM10 fit (fits/kw_awqaf): Asr on the apparent sun (170 of 180 days against 154), at Kuwait City's computing
    // point (in the asset); 93–99 % exact at reference year 2011, which the app shows every year (A8, revised 2026-10-17).
    val KW_AWQAF = profile("kw_awqaf", angle(18.0), ishaAngle(17.5), tables("The Ministry of Awqaf's perpetual timetable, as printed (reference year 2011)")) {
        copy(referenceYear = 2011, asrSun = AsrSun.APPARENT)
    }

    val QA_AWQAF = profile("qa_awqaf", angle(18.0), IshaRule.MinutesAfter(90), tables("Qatar Calendar House and the Ministry's tables")) {
        copy(
            offsets = mapOf(TimeEvent.MAGHRIB to 2),
            ramadanOffsets = mapOf(TimeEvent.MAGHRIB to 1),
            ramadanDays = RamadanDays.UMM_AL_QURA_COMPUTED,
            revisions = listOf(MethodRevision(LocalDate.of(2020, 8, 20), MethodChange(offsets = mapOf(TimeEvent.FAJR to -1)))),
            // AM2 fit (fits/qa): VSOP87 at reference year 2006 (2,148 of 2,190 minutes against 2,144), every other value confirmed;
            // the perpetual table as printed (A8, revised 2026-10-17).
            referenceYear = 2006,
            sunModel = SunModel.VSOP87,
        )
    }

    val QA_QCH = QA_AWQAF.copy(
        id = "qa_qch",
        evidence = tables("Qatar Calendar House's tables"),
        revisions = QA_AWQAF.revisions + MethodRevision(
            LocalDate.of(2025, 6, 26),
            MethodChange(offsets = mapOf(TimeEvent.DHUHR to 1, TimeEvent.ASR to 1)),
        ),
    )

    // AM10 fit (fits/bh_calendar), on the 1448 calendar, each time at a point of its own (E6 eventPoints, in the asset;
    // 2026-10-17, its points refined on a finer grid 2026-10-18): Fajr 17.975° and Isha 18.055° (the stated 18°),
    // sunrise at −0.86°, Maghrib at −0.84°, Dhuhr 64 s after the transit (the semi-diameter's crossing), Asr on the sun
    // at the Asr instant itself (E4 SHADOW_AT_EVENT); 97.2–99.4 % exact held out (month split), all within 1 minute.
    val BH_CALENDAR = profile("bh_calendar", angle(17.975), ishaAngle(18.055), tables("The Supreme Council for Islamic Affairs's calendar")) {
        copy(
            dhuhrAfterTransitMin = 1.07,
            rounding = TimeEvent.entries.associateWith { if (it == TimeEvent.MAGHRIB) Rounding.NEAREST else Rounding.DOWN },
            horizon = Horizons(HorizonModel.Fixed(-0.86), HorizonModel.Fixed(-0.84)),
            sunEvaluation = mapOf(TimeEvent.ASR to SunEvaluation.SHADOW_AT_EVENT),
            sunModel = SunModel.VSOP87,
        )
    }

    // AM2 fit (fits/ae_awqaf): every value confirmed (Dhuhr's window 1.999–2.001 min, Fajr 17.999–18.000°, Isha
    // 18.000–18.001°, the fitted horizons' medians −1.377° and −1.400°), and Asr's noon shadow at the published Dhuhr (E4
    // SHADOW_AT_DHUHR, as Accurate Times computes): Abu Dhabi and Al Ain pass tier A, 97.5–100 % exact held out.
    val AE_AWQAF = profile("ae_awqaf", angle(18.0), ishaAngle(18.0), tables("Awqaf's tables, computed with Accurate Times")) {
        copy(
            offsets = mapOf(TimeEvent.DHUHR to 2),
            horizon = Horizons(HorizonModel.Refraction(HorizonModel.Fixed(-1.4)), HorizonModel.Refraction(HorizonModel.Fixed(-1.4))),
            asrSun = AsrSun.APPARENT,
            sunModel = SunModel.VSOP87,
            sunEvaluation = mapOf(TimeEvent.ASR to SunEvaluation.SHADOW_AT_DHUHR),
            jumua = JumuaRule.AT_DHUHR,
            revisions = listOf(
                MethodRevision(LocalDate.of(2022, 1, 7), MethodChange(jumua = JumuaRule.Fixed(LocalTime.of(13, 15)))),
                MethodRevision(LocalDate.of(2026, 1, 2), MethodChange(jumua = JumuaRule.Fixed(LocalTime.of(12, 45)))),
            ),
        )
    }

    // AM2 fit (fits/om_mara): every value confirmed on 6 zones × 365 days; the Almanac's low-precision sun (PrayTimes.js's
    // constants) with a constant E18 shift of 0.86 s (13,129 of 13,140 minutes); 99.4–100 % exact held out, all within 1 minute.
    val OM_MARA = profile("om_mara", angle(18.0), ishaAngle(18.0), tables("The Ministry of Awqaf's zone tables")) {
        copy(
            offsets = mapOf(TimeEvent.DHUHR to 5, TimeEvent.ASR to 5, TimeEvent.MAGHRIB to 5),
            sunEvaluation = TimeEvent.entries.associateWith { SunEvaluation.DAILY_0H_UT },
            sunModel = SunModel.ALMANAC,
            seasonalOffsets = TimeEvent.entries.associateWith { List(PrayerMethod.SEASONAL_POINTS) { 0.86 / 60 } },
        )
    }

    // AM2 fit (fits/ye_awqaf): VSOP87 by the Dhuhr windows (4,060 of 4,154 against 4,052), every other value confirmed;
    // 96.2–98.1 % exact held out (May to October), all within 1 minute.
    val YE_AWQAF = profile("ye_awqaf", angle(18.0), ishaAngle(16.0), tables("The General Authority of Awqaf and Guidance's tables for Sanaa's 31 locations")) {
        copy(
            offsets = mapOf(TimeEvent.DHUHR to 10),
            horizon = Horizons(HorizonModel.Pressure, HorizonModel.Pressure),
            sunEvaluation = mapOf(TimeEvent.ASR to SunEvaluation.LOCAL_MIDNIGHT_SHADOW),
            maghribTerrain = TerrainRule(),
            sunModel = SunModel.VSOP87,
        )
    }

    val SD_FITTED = profile("sd_fitted", angle(18.0), ishaAngle(18.0), approximate("Two mosques in the dataset; ICOP's preset"))
    val SO_FITTED = profile("so_fitted", angle(18.0), ishaAngle(17.0), approximate("Three mosques in the dataset, perhaps their software's default"))
    val DJ_FITTED = profile("dj_fitted", angle(18.0), ishaAngle(17.0), approximate("Six mosques in the dataset, perhaps their software's default"))

    val KM_FITTED = PrayerMethod(
        id = "km_fitted",
        fajr = FajrRule.MinutesBeforeSunrise(73),
        isha = IshaRule.MinutesAfter(74),
        asr = AsrRule.BeforeMaghrib(168),
        offsets = mapOf(TimeEvent.DHUHR to 4, TimeEvent.MAGHRIB to 3),
        highLatitude = HighLatitudeRule.AngleBased,
        evidence = tables("The Moroni table 16 of 24 mosques use"),
    )

    val ALL: List<PrayerMethod> = listOf(
        SA_UMM_AL_QURA, EG_EGSA, MA_HABOUS, DZ_MARW, LY_AWQAF, MR_ISLAMIC_AFFAIRS, JO_AWQAF, PS_DAR_AL_IFTA, PS_GAZA_AWQAF,
        LB_DAR_AL_FATWA, SY_AWQAF, IQ_SUNNI_ENDOWMENT, KW_AWQAF, QA_AWQAF, QA_QCH, BH_CALENDAR, AE_AWQAF, OM_MARA, YE_AWQAF,
        SD_FITTED, SO_FITTED, DJ_FITTED, KM_FITTED,
    )
}

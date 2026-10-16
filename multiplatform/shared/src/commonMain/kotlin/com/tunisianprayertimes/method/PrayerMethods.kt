package com.tunisianprayertimes.method

import com.tunisianprayertimes.engine.HighLatitudeRule
import com.tunisianprayertimes.engine.TimeEvent

/**
 * The reference presets that serve every region (plan 6.3), and the list of every preset and profile:
 * the European presets and overseas France's ([EuropeanPresets], 7.5, 7.6) and the authority profiles
 * ([ArabAuthorityProfiles], 8.7). `inm` never reaches the engine (Tunisian places only, D7).
 */
object PrayerMethods {
    val TUNISIA_18_18 = PrayerMethod(
        id = "tunisia_18_18",
        fajr = FajrRule.Angle(18.0),
        isha = IshaRule.Angle(18.0),
        offsets = mapOf(TimeEvent.DHUHR to 7, TimeEvent.MAGHRIB to 2),
        highLatitude = HighLatitudeRule.AngleBased,
        evidence = Evidence(EvidenceKind.PUBLISHED, "INM's values (18°, Dhuhr + 7, Maghrib + 2); the summer rule is the app's"),
    )

    val MWL = PrayerMethod(
        id = "mwl",
        fajr = FajrRule.Angle(18.0),
        isha = IshaRule.Angle(17.0),
        highLatitude = HighLatitudeRule.AngleBased,
        evidence = Evidence(EvidenceKind.PUBLISHED, "Muslim World League: 18° and 17°, without the 1986 minutes"),
    )

    val ISNA = PrayerMethod(
        id = "isna",
        fajr = FajrRule.Angle(15.0),
        isha = IshaRule.Angle(15.0),
        highLatitude = HighLatitudeRule.AngleBased,
        evidence = Evidence(EvidenceKind.PUBLISHED, "Islamic Society of North America: 15° and 15°"),
    )

    val KARACHI = PrayerMethod(
        id = "karachi",
        fajr = FajrRule.Angle(18.0),
        isha = IshaRule.Angle(18.0),
        highLatitude = HighLatitudeRule.AngleBased,
        evidence = Evidence(EvidenceKind.PUBLISHED, "University of Islamic Sciences, Karachi: 18° and 18°"),
    )

    val UMM_AL_QURA = PrayerMethod(
        id = "umm_al_qura",
        fajr = FajrRule.Angle(18.5),
        isha = IshaRule.MinutesAfter(90, IshaFrom.MAGHRIB, ramadan = 120),
        rounding = mapOf(
            TimeEvent.FAJR to Rounding.UP,
            TimeEvent.SUNRISE to Rounding.DOWN,
            TimeEvent.DHUHR to Rounding.UP,
            TimeEvent.ASR to Rounding.UP,
            TimeEvent.MAGHRIB to Rounding.UP,
            TimeEvent.ISHA to Rounding.NEAREST,
        ),
        highLatitude = HighLatitudeRule.AngleBased,
        evidence = Evidence(EvidenceKind.FROM_TABLES, "Re-derived from KACST's tables; KACST publishes no written method"),
    )

    val EGYPTIAN = PrayerMethod(
        id = "egyptian",
        fajr = FajrRule.Angle(19.5),
        isha = IshaRule.Angle(17.5),
        highLatitude = HighLatitudeRule.AngleBased,
        evidence = Evidence(EvidenceKind.PUBLISHED, "The Egyptian General Survey Authority's angles: 19.5° and 17.5°"),
    )

    val REFERENCE: List<PrayerMethod> = listOf(TUNISIA_18_18, MWL, ISNA, KARACHI, UMM_AL_QURA, EGYPTIAN)

    /** Every preset and profile the engine computes (plan 6.3): 6 reference presets (with `inm`, 7), 26 European, 23 authority and fitted profiles, 4 overseas. */
    val ALL: List<PrayerMethod> by lazy { REFERENCE + EuropeanPresets.EUROPE + ArabAuthorityProfiles.ALL + EuropeanPresets.OVERSEAS }

    private val BY_ID: Map<String, PrayerMethod> by lazy { ALL.associateBy { it.id } }

    fun byId(id: String): PrayerMethod? = BY_ID[id]
}

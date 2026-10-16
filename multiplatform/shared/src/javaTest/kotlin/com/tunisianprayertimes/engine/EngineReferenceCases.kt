package com.tunisianprayertimes.engine

import com.tunisianprayertimes.engine.EngineTestSupport.method
import com.tunisianprayertimes.engine.EngineTestSupport.place
import com.tunisianprayertimes.method.AsrRule
import com.tunisianprayertimes.method.AsrSun
import com.tunisianprayertimes.method.ElevationShift
import com.tunisianprayertimes.method.Evidence
import com.tunisianprayertimes.method.EvidenceKind
import com.tunisianprayertimes.method.FajrRule
import com.tunisianprayertimes.method.IshaFrom
import com.tunisianprayertimes.method.IshaRule
import com.tunisianprayertimes.method.MethodChange
import com.tunisianprayertimes.method.MethodRevision
import com.tunisianprayertimes.method.PrayerMethod
import com.tunisianprayertimes.method.PrayerMethods
import com.tunisianprayertimes.method.Rounding
import com.tunisianprayertimes.method.TerrainClass
import com.tunisianprayertimes.method.TerrainRule
import com.tunisianprayertimes.place.LatLngE5
import com.tunisianprayertimes.place.OfficialZone
import com.tunisianprayertimes.place.PrayerPlace
import com.tunisianprayertimes.place.ZoneOffsetRule
import java.io.File
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

/**
 * The cases the engine and the independent Python reference both compute (plan 9.1, reference
 * cross-check): reference presets and profile-like methods exercising every E-rule at places across
 * the regions and the date line. Recorded to test-data/engine-reference/cases.json, with the zone facts
 * the reference cannot look up, by running [record] with ENGINE_CASES_RECORD set to that file.
 */
class EngineReferenceCases {
    data class Case(
        val id: String,
        val place: PrayerPlace.Coordinates,
        val date: LocalDate,
        val method: PrayerMethod,
        val ramadanDay: Boolean = false,
        val zone: OfficialZone? = null,
        val baseZone: OfficialZone? = null,
        val offsetWindow: ClosedRange<LocalDate>? = null,
        val adjustments: Map<TimeEvent, Int> = emptyMap(),
        val terrainClass: TerrainClass? = null,
    )

    @Test
    fun record() {
        val target = System.getenv("ENGINE_CASES_RECORD")?.takeIf { it.isNotBlank() } ?: return
        File(target).writeText(
            all().joinToString(",\n", prefix = "[\n", postfix = "\n]\n") { EngineTestSupport.json.encodeToString(JsonObject.serializer(), toJson(it)) },
        )
    }

    companion object {
        private val E = TimeEvent.entries
        private val SEASONS = EngineTestSupport.dates("2026-01-15", "2026-03-20", "2026-06-21", "2026-09-23", "2026-12-21")

        private fun zone(id: String, lat: Double, lng: Double, build: OfficialZone.() -> OfficialZone = { this }) =
            OfficialZone(authority = "test", id = id, nameAr = id, point = LatLngE5.of(lat, lng)).build()

        // Places.
        val MATA_UTU = place(-13.28, -176.17, "Pacific/Wallis")
        val SHEMYA = place(52.72, 174.11, "America/Adak")
        val KIRITIMATI = place(1.87, -157.43, "Pacific/Kiritimati")
        val SUVA = place(-18.14, 178.44, "Pacific/Fiji")
        val HONOLULU_ON_PARIS = place(21.31, -157.86, "Europe/Paris")
        val TUNIS = place(36.8065, 10.1815, "Africa/Tunis")
        val MECCA = place(21.4225, 39.8262, "Asia/Riyadh")
        val RIYADH = place(24.7136, 46.6753, "Asia/Riyadh")
        val CAIRO = place(30.0444, 31.2357, "Africa/Cairo")
        val PARIS = place(48.8566, 2.3522, "Europe/Paris", country = "FR")
        val MARSEILLE = place(43.2965, 5.3698, "Europe/Paris", country = "FR")
        val LONDON = place(51.5074, -0.1278, "Europe/London", country = "GB")
        val LULEA = place(65.5848, 22.1547, "Europe/Stockholm", country = "SE")
        val ISTANBUL = place(41.0082, 28.9784, "Europe/Istanbul", country = "TR")
        val AMMAN = place(31.9539, 35.9106, "Asia/Amman", country = "JO", elevationM = 1000)
        val IRBID = place(32.5556, 35.85, "Asia/Amman", country = "JO", elevationM = 600)
        val SANAA = place(15.3694, 44.191, "Asia/Aden", country = "YE", elevationM = 2250)
        val SEIYUN = place(15.9431, 48.7879, "Asia/Aden", country = "YE", elevationM = 663)
        val WADI_800 = place(15.93, 48.63, "Asia/Aden", country = "YE", elevationM = 800)
        val TRIPOLI = place(32.8872, 13.1913, "Africa/Tripoli", country = "LY")
        val MUSCAT = place(23.588, 58.3829, "Asia/Muscat", country = "OM")
        val ALGIERS = place(36.7538, 3.0588, "Africa/Algiers", country = "DZ")
        val BLIDA = place(36.47, 2.83, "Africa/Algiers", country = "DZ")
        val IN_GUEZZAM = place(19.5686, 5.7722, "Africa/Algiers", country = "DZ")
        val DOHA = place(25.2854, 51.531, "Asia/Qatar", country = "QA")
        val ABU_DHABI = place(24.4539, 54.3773, "Asia/Dubai", country = "AE")
        val MORONI = place(-11.70, 43.25, "Indian/Comoro", country = "KM")
        val MUTSAMUDU = place(-12.1675, 44.3994, "Indian/Comoro", country = "KM")
        val SAINT_DENIS_RE = place(-20.8823, 55.4504, "Indian/Reunion", country = "RE", elevationM = 820)
        val PRISTINA = place(42.6629, 21.1655, "Europe/Belgrade", country = "XK")
        val MOSTAR = place(43.3438, 17.8078, "Europe/Sarajevo", country = "BA")
        val MANAMA = place(26.2285, 50.586, "Asia/Bahrain", country = "BH")
        val RABAT = place(34.0209, -6.8416, "Africa/Casablanca", country = "MA")
        val IFRANE = place(33.5333, -5.1167, "Africa/Casablanca", country = "MA", elevationM = 1665)
        val LE_TAMPON_300 = place(-21.278, 55.517, "Indian/Reunion", country = "RE", elevationM = 300)
        val CILAOS = place(-21.1336, 55.4717, "Indian/Reunion", country = "RE", elevationM = 1200)
        val KUWAIT_JAHRA = place(29.3375, 47.6581, "Asia/Kuwait", country = "KW")
        val DAMASCUS = place(33.5138, 36.2765, "Asia/Damascus", country = "SY")
        val BERLIN = place(52.52, 13.405, "Europe/Berlin", country = "DE")
        val OSLO = place(59.9139, 10.7522, "Europe/Oslo", country = "NO")
        val AMSTERDAM = place(52.3676, 4.9041, "Europe/Amsterdam", country = "NL")
        val MOSCOW = place(55.7558, 37.6173, "Europe/Moscow", country = "RU")
        val KAZAN = place(55.7887, 49.1221, "Europe/Moscow", country = "RU")
        val VIENNA = place(48.2082, 16.3418, "Europe/Vienna", country = "AT")
        val ZAGREB = place(45.815, 15.9819, "Europe/Zagreb", country = "HR")
        val LJUBLJANA = place(46.0569, 14.5058, "Europe/Ljubljana", country = "SI")
        val TROMSO = place(69.6492, 18.9553, "Europe/Oslo", country = "NO")
        val MURMANSK = place(68.9585, 33.0827, "Europe/Moscow", country = "RU")
        val ROVANIEMI = place(66.5039, 25.7294, "Europe/Helsinki", country = "FI")
        val BODO = place(67.2804, 14.4049, "Europe/Oslo", country = "NO")
        val IRN_FIXTURE_ZONE = OfficialZone("irn", "fixture", "fixture", LatLngE5.of(59.9139, 10.7522),
            switchDays = mapOf(TimeEvent.ISHA to 90..251, TimeEvent.FAJR to 103..238))

        // Profile-like methods: the mechanisms of plan 8.7 with values to be fitted in AM2.
        val JORDAN_LIKE = method("jo_like", 18.0, 18.0) {
            copy(
                horizon = Horizons(HorizonModel.Refraction(), HorizonModel.Refraction()),
                asrSun = AsrSun.APPARENT,
                sunModel = SunModel.VSOP87,
            )
        }
        val YEMEN_LIKE = method("ye_like", 18.0, 16.0) {
            copy(
                offsets = mapOf(TimeEvent.DHUHR to 10),
                horizon = Horizons(HorizonModel.Pressure, HorizonModel.Pressure),
                sunEvaluation = mapOf(TimeEvent.ASR to SunEvaluation.LOCAL_MIDNIGHT_SHADOW),
                maghribTerrain = TerrainRule(),
            )
        }
        val LIBYA_LIKE = method("ly_like", 18.354, 18.354) {
            copy(
                offsets = mapOf(TimeEvent.DHUHR to 4, TimeEvent.MAGHRIB to 4),
                rounding = E.associateWith { Rounding.DOWN },
                sunModel = SunModel.TEXTBOOK,
                sunEvaluation = E.associateWith { SunEvaluation.DAILY_10H_UT },
                twilightLatitudeScale = 0.99330562,
            )
        }
        val OMAN_LIKE = method("om_like", 18.0, 18.0) {
            copy(
                offsets = mapOf(TimeEvent.DHUHR to 5, TimeEvent.ASR to 5, TimeEvent.MAGHRIB to 5),
                sunEvaluation = E.associateWith { SunEvaluation.DAILY_0H_UT },
            )
        }
        val SAUDI_LIKE = PrayerMethods.UMM_AL_QURA.copy(
            id = "sa_like",
            horizon = Horizons(HorizonModel.Fixed(-0.83), HorizonModel.Standard),
            sunEvaluation = mapOf(TimeEvent.FAJR to SunEvaluation.KACST, TimeEvent.ASR to SunEvaluation.KACST),
        )
        val ALGERIA_LIKE = method("dz_like", 18.0, 17.0) {
            copy(offsets = mapOf(TimeEvent.MAGHRIB to 3), rounding = E.associateWith { Rounding.UP }, asr = AsrRule.SignedShadow)
        }
        val QATAR_LIKE = method("qa_like", 18.0, 18.0) {
            copy(
                isha = IshaRule.MinutesAfter(90),
                offsets = mapOf(TimeEvent.MAGHRIB to 2),
                ramadanOffsets = mapOf(TimeEvent.MAGHRIB to 1),
                revisions = listOf(MethodRevision(LocalDate.of(2020, 8, 20), MethodChange(offsets = mapOf(TimeEvent.FAJR to -1)))),
            )
        }
        val COMOROS_LIKE = PrayerMethod(
            id = "km_like",
            fajr = FajrRule.MinutesBeforeSunrise(73),
            isha = IshaRule.MinutesAfter(74),
            asr = AsrRule.BeforeMaghrib(168),
            offsets = mapOf(TimeEvent.DHUHR to 4, TimeEvent.MAGHRIB to 3),
            highLatitude = HighLatitudeRule.AngleBased,
            evidence = Evidence(EvidenceKind.APPROXIMATE, "test"),
        )
        val REUNION_LIKE = method("re_like", 18.0, 18.0) {
            copy(
                offsets = mapOf(TimeEvent.MAGHRIB to 2),
                elevationShifts = listOf(
                    ElevationShift(100, 1.0), ElevationShift(200, 1.5), ElevationShift(300, 2.0), ElevationShift(500, 3.0),
                    ElevationShift(800, 4.0), ElevationShift(1100, 5.0), ElevationShift(1600, 6.0), ElevationShift(2100, 7.0),
                ),
                rounding = E.associateWith { if (it == TimeEvent.SUNRISE) Rounding.DOWN else Rounding.UP },
            )
        }
        val KOSOVO_LIKE = method("xk_like", 18.0, 17.0) {
            copy(
                seasonalOffsets = mapOf(
                    TimeEvent.FAJR to List(36) { 10.0 + 5.0 * sin(2 * PI * it / 36) },
                    TimeEvent.MAGHRIB to List(36) { 3.0 + 1.5 * cos(2 * PI * it / 36) },
                    TimeEvent.ISHA to List(36) { -4.0 + 2.0 * sin(4 * PI * it / 36) },
                ),
            )
        }
        val BOSNIA_LIKE = method("ba_like", 19.0, 17.0) {
            copy(
                takvimMeans = listOf(1.2, 1.3, 1.5, 1.7, 1.9, 2.0, 2.0, 1.8, 1.6, 1.4, 1.2, 1.1) +
                    listOf(-1.1, -1.2, -1.4, -1.6, -1.8, -1.9, -1.9, -1.7, -1.5, -1.3, -1.1, -1.0),
            )
        }
        val BAHRAIN_LIKE = method("bh_like", 18.0, 17.8) {
            copy(
                dhuhrAfterTransitMin = 1.1,
                rounding = E.associateWith { if (it == TimeEvent.MAGHRIB) Rounding.NEAREST else Rounding.DOWN },
                sunModel = SunModel.VSOP87,
            )
        }
        val UAE_LIKE = method("ae_like", 18.0, 18.0) {
            copy(
                offsets = mapOf(TimeEvent.DHUHR to 2),
                horizon = Horizons(HorizonModel.Refraction(HorizonModel.Fixed(-1.4)), HorizonModel.Refraction(HorizonModel.Fixed(-1.4))),
                asrSun = AsrSun.APPARENT,
                sunModel = SunModel.VSOP87,
                sunEvaluation = mapOf(TimeEvent.ASR to SunEvaluation.SHADOW_AT_DHUHR),
            )
        }
        val BAHRAIN_EVENT_LIKE = BAHRAIN_LIKE.copy(id = "bh_event_like", sunEvaluation = mapOf(TimeEvent.ASR to SunEvaluation.SHADOW_AT_EVENT))
        val BAHRAIN_DHUHR_LIKE = BAHRAIN_LIKE.copy(id = "bh_dhuhr_like", sunEvaluation = mapOf(TimeEvent.ASR to SunEvaluation.SHADOW_AT_DHUHR))
        val ALGERIA_DHUHR_LIKE = ALGERIA_LIKE.copy(
            id = "dz_dhuhr_like",
            offsets = mapOf(TimeEvent.MAGHRIB to 3, TimeEvent.DHUHR to 5),
            sunEvaluation = mapOf(TimeEvent.ASR to SunEvaluation.SHADOW_AT_DHUHR),
        )
        val ALGERIA_EVENT_LIKE = ALGERIA_LIKE.copy(id = "dz_event_like", sunEvaluation = mapOf(TimeEvent.ASR to SunEvaluation.SHADOW_AT_EVENT))
        val OMAN_ALMANAC_LIKE = OMAN_LIKE.copy(id = "om_almanac_like", sunModel = SunModel.ALMANAC)
        val MWL_ALMANAC = PrayerMethods.MWL.copy(id = "mwl_almanac", sunModel = SunModel.ALMANAC)
        val MOROCCO_LIKE = method("ma_like", 19.0, 17.0) {
            copy(
                offsets = mapOf(TimeEvent.DHUHR to 5, TimeEvent.SUNRISE to -1, TimeEvent.MAGHRIB to 2),
                rounding = E.associateWith { if (it == TimeEvent.FAJR) Rounding.DOWN else Rounding.NEAREST },
                horizon = Horizons(HorizonModel.PerZone(HorizonModel.Standard), HorizonModel.PerZone(HorizonModel.Standard)),
                asrSun = AsrSun.APPARENT,
            )
        }
        val MOROCCO_HEIGHT_LIKE = MOROCCO_LIKE.copy(
            id = "ma_height_like",
            horizon = Horizons(
                HorizonModel.PerZone(HorizonModel.SqrtHeight(1.194, 0.0244)),
                HorizonModel.PerZone(HorizonModel.SqrtHeight(1.190, 0.0205)),
            ),
        )
        val SYRIA_LIKE = method("sy_like", 18.0, 17.0) {
            copy(
                offsets = mapOf(TimeEvent.MAGHRIB to 2),
                rounding = E.associateWith { if (it == TimeEvent.FAJR || it == TimeEvent.SUNRISE) Rounding.DOWN else Rounding.UP },
            )
        }
        val ECFR_90_LIKE = PrayerMethod(
            id = "ecfr_90_like",
            fajr = FajrRule.MinutesBeforeSunrise(90),
            isha = IshaRule.MinutesAfter(90),
            highLatitude = HighLatitudeRule.AngleBased,
            evidence = Evidence(EvidenceKind.APPROXIMATE, "test"),
        )
        val MAGHRIB_ANGLE = method("maghrib_angle", 16.0, 14.0) { copy(maghribAngle = 4.0) }
        val MWL_CAPPED = PrayerMethods.MWL.copy(id = "mwl_capped", ishaLatest = LocalTime.of(22, 45))
        val ISNA_HANAFI = PrayerMethods.ISNA.copy(id = "isna_hanafi", asrShadow = 2)
        val CUSTOM_GMP = PrayerMethod(
            id = PrayerMethod.CUSTOM_ID,
            fajr = FajrRule.Angle(12.0),
            isha = IshaRule.MinutesAfter(87),
            offsets = mapOf(TimeEvent.DHUHR to 5, TimeEvent.MAGHRIB to 3),
            highLatitude = HighLatitudeRule.AngleBased,
            evidence = Evidence(EvidenceKind.APPROXIMATE, "custom"),
        )
        val CUSTOM_EARLY = PrayerMethod(
            id = PrayerMethod.CUSTOM_ID,
            fajr = FajrRule.MinutesBeforeSunrise(120),
            isha = IshaRule.MinutesAfter(60),
            ishaEarliest = LocalTime.of(19, 0),
            offsets = mapOf(TimeEvent.DHUHR to 2, TimeEvent.MAGHRIB to 1, TimeEvent.SUNRISE to -10),
            horizon = Horizons(HorizonModel.StandardWithDip, HorizonModel.StandardWithDip),
            highLatitude = HighLatitudeRule.AngleBased,
            evidence = Evidence(EvidenceKind.APPROXIMATE, "custom"),
        )

        // Zones.
        val AMMAN_ZONE = zone("amman", 31.95, 35.93) {
            copy(morningPoint = LatLngE5.of(31.95, 36.10), elevationM = 1100, eveningElevationM = 900, pressureHpa = 891.0, temperatureC = 15.0)
        }
        val IRBID_ZONE = zone("irbid", 32.55, 35.85) {
            copy(elevationM = 600, pressureHpa = 940.0, temperatureC = 17.0, baseZoneId = "amman", offsetRule = ZoneOffsetRule.MONTHLY_DIFFERENCE)
        }
        val SANAA_ZONE = zone("sanaa", 15.37, 44.19) { copy(elevationM = 2250) }
        val SEIYUN_ZONE = zone("seiyun", 15.94, 48.79) { copy(elevationM = 663, terrainClass = TerrainClass.LOW) }
        val ALGIERS_ZONE = zone("algiers", 36.75, 3.05)
        val BLIDA_ZONE = zone("blida", 36.47, 2.83) { copy(baseZoneId = "algiers", offsetRule = ZoneOffsetRule.WINDOW_CEILING) }
        val RABAT_ZONE = zone("rabat", 34.02, -6.84) { copy(sunriseAltitudeDeg = -1.17, sunsetAltitudeDeg = -1.15) }
        val MORONI_ZONE = zone("moroni", -11.70, 43.25) { copy(sunriseAtPlace = true) }
        val TAMPON_ZONE = zone("sud_tampon", -21.278, 55.517) { copy(offsets = mapOf(TimeEvent.MAGHRIB to 0.07), elevationShiftFloor = 2.0) }
        val BAHRAIN_ZONE = zone("bahrain", 26.22, 50.56) {
            copy(morningPoint = LatLngE5.of(26.22, 50.68), eveningPoint = LatLngE5.of(26.22, 50.44))
        }
        val KUWAIT_CITY_ZONE = zone("kuwait", 29.37, 47.98)
        val JAHRA_ZONE = zone("jahra", 29.34, 47.66) {
            copy(baseZoneId = "kuwait", offsets = E.associateWith { 1.0 })
        }
        val SARAJEVO_ZONE = zone("sarajevo", 43.8667, 18.4333)
        val MOSTAR_ZONE = zone("mostar", 43.34, 17.81) { copy(baseZoneId = "sarajevo", offsetRule = ZoneOffsetRule.TAKVIM) }
        // TAKVIM at the zone's E6 points: the town stays `point`; one point north of Sarajevo's latitude, one south.
        val MOSTAR_E6_ZONE = MOSTAR_ZONE.copy(id = "mostar_e6", morningPoint = LatLngE5.of(43.30, 17.90), eveningPoint = LatLngE5.of(43.38, 17.75))
        val SPLIT_POINTS_ZONE = MOSTAR_ZONE.copy(id = "split_points", morningPoint = LatLngE5.of(44.05, 17.95), eveningPoint = LatLngE5.of(43.55, 17.70))
        val SHAMAL_ZONE = zone("al_shamal", 26.13, 51.21) { copy(offsets = mapOf(TimeEvent.FAJR to 1.0)) }
        val DAMASCUS_ZONE = zone("damascus", 33.51, 36.29) { copy(offsets = mapOf(TimeEvent.ASR to 0.25, TimeEvent.ISHA to 0.25)) }

        fun all(): List<Case> {
            val cases = mutableListOf<Case>()
            fun add(id: String, place: PrayerPlace.Coordinates, dates: List<LocalDate>, method: PrayerMethod, build: Case.() -> Case = { this }) {
                dates.forEach { cases += Case("$id-$it", place, it, method).build() }
            }
            // The date line and a far phone zone (9.1), with mwl.
            add("mwl-mata-utu", MATA_UTU, EngineTestSupport.dates("2026-10-13", "2026-01-01", "2026-06-30"), PrayerMethods.MWL)
            add("mwl-shemya", SHEMYA, EngineTestSupport.dates("2026-10-13", "2026-10-31", "2026-11-01", "2026-03-08"), PrayerMethods.MWL)
            add("mwl-kiritimati", KIRITIMATI, EngineTestSupport.dates("2026-10-13", "2026-04-01"), PrayerMethods.MWL)
            add("mwl-suva", SUVA, EngineTestSupport.dates("2026-10-13", "2026-12-21"), PrayerMethods.MWL)
            add("mwl-honolulu-paris", HONOLULU_ON_PARIS, EngineTestSupport.dates("2026-03-28", "2026-03-29", "2026-10-24", "2026-10-25"), PrayerMethods.MWL)
            // Reference presets across the regions, and the summer-time days.
            for (m in PrayerMethods.REFERENCE) add(m.id + "-tunis", TUNIS, SEASONS, m)
            add("mwl-mecca", MECCA, SEASONS, PrayerMethods.MWL)
            add("egyptian-cairo", CAIRO, SEASONS + EngineTestSupport.dates("2026-04-24", "2026-10-29", "2026-10-30"), PrayerMethods.EGYPTIAN)
            add("mwl-paris", PARIS, SEASONS + EngineTestSupport.dates("2026-03-29", "2026-10-25", "2026-06-12", "2026-07-01"), PrayerMethods.MWL)
            add("isna-paris", PARIS, SEASONS, PrayerMethods.ISNA)
            add("uaq-paris", PARIS, EngineTestSupport.dates("2026-06-21", "2026-12-21"), PrayerMethods.UMM_AL_QURA)
            add("karachi-marseille", MARSEILLE, SEASONS, PrayerMethods.KARACHI)
            add("mwl-london", LONDON, SEASONS + EngineTestSupport.dates("2026-03-29", "2026-10-25"), PrayerMethods.MWL)
            add("mwl-capped-london", LONDON, EngineTestSupport.dates("2026-06-21", "2026-05-15"), MWL_CAPPED)
            add("isna-hanafi-istanbul", ISTANBUL, SEASONS, ISNA_HANAFI)
            add("maghrib-angle-istanbul", ISTANBUL, SEASONS, MAGHRIB_ANGLE)
            add("mwl-2099", TUNIS, EngineTestSupport.dates("2099-06-21", "2099-12-21"), PrayerMethods.MWL)
            add("uaq-2099", MECCA, EngineTestSupport.dates("2099-03-20"), PrayerMethods.UMM_AL_QURA)
            // E-rules on profile-like methods.
            add("jo-amman-exact", AMMAN, SEASONS, JORDAN_LIKE)
            add("jo-amman-zone", AMMAN, SEASONS, JORDAN_LIKE) { copy(zone = AMMAN_ZONE) }
            add("jo-irbid-derived", IRBID, EngineTestSupport.dates("2026-01-15", "2026-06-03", "2026-09-28"), JORDAN_LIKE) {
                copy(zone = IRBID_ZONE, baseZone = AMMAN_ZONE)
            }
            add("ye-sanaa", SANAA, SEASONS, YEMEN_LIKE) { copy(zone = SANAA_ZONE) }
            add("ye-sanaa-exact", SANAA, EngineTestSupport.dates("2026-06-21"), YEMEN_LIKE)
            add("ye-seiyun", SEIYUN, EngineTestSupport.dates("2026-03-20"), YEMEN_LIKE) { copy(zone = SEIYUN_ZONE) }
            add("ye-seiyun-exact", SEIYUN, EngineTestSupport.dates("2026-03-20"), YEMEN_LIKE)
            // E10 at the place's own point: between the limits, the nearest location's class when passed, else the nearer limit.
            add("ye-band-exact-high", WADI_800, EngineTestSupport.dates("2026-06-21"), YEMEN_LIKE) { copy(terrainClass = TerrainClass.HIGH) }
            add("ye-band-exact-nearer", WADI_800, EngineTestSupport.dates("2026-06-21"), YEMEN_LIKE)
            add("ly-tripoli", TRIPOLI, SEASONS, LIBYA_LIKE)
            add("om-muscat", MUSCAT, SEASONS, OMAN_LIKE)
            add("sa-riyadh", RIYADH, SEASONS, SAUDI_LIKE)
            add("sa-riyadh-ramadan", RIYADH, EngineTestSupport.dates("2026-03-01", "2027-02-20"), SAUDI_LIKE) { copy(ramadanDay = true) }
            add("dz-algiers", ALGIERS, SEASONS, ALGERIA_LIKE) { copy(zone = ALGIERS_ZONE) }
            add("dz-blida-window", BLIDA, EngineTestSupport.dates("2026-10-15", "2026-11-01"), ALGERIA_LIKE) {
                copy(zone = BLIDA_ZONE, baseZone = ALGIERS_ZONE, offsetWindow = LocalDate.of(2026, 10, 10)..LocalDate.of(2026, 11, 8))
            }
            add("dz-in-guezzam", IN_GUEZZAM, EngineTestSupport.dates("2026-06-21", "2026-07-10"), ALGERIA_LIKE)
            add("qa-doha", DOHA, EngineTestSupport.dates("2020-08-19", "2020-08-20", "2026-06-21"), QATAR_LIKE)
            add("qa-doha-ramadan", DOHA, EngineTestSupport.dates("2026-03-01"), QATAR_LIKE) { copy(ramadanDay = true) }
            add("qa-shamal", DOHA, EngineTestSupport.dates("2026-09-23"), QATAR_LIKE) { copy(zone = SHAMAL_ZONE) }
            // E4's Asr shadows at Dhuhr's published instant and at the Asr instant itself; the noon sun near the zenith.
            val nearZenith = EngineTestSupport.dates("2026-06-01", "2026-07-15")
            add("ae-abu-dhabi-shadow-at-dhuhr", ABU_DHABI, SEASONS + nearZenith, UAE_LIKE)
            add("bh-manama-shadow-at-dhuhr", MANAMA, EngineTestSupport.dates("2026-03-20", "2026-06-21"), BAHRAIN_DHUHR_LIKE)
            add("bh-manama-shadow-at-event", MANAMA, SEASONS + nearZenith, BAHRAIN_EVENT_LIKE)
            add("dz-in-guezzam-signed-at-dhuhr", IN_GUEZZAM, EngineTestSupport.dates("2026-06-21", "2026-07-10", "2026-12-21"), ALGERIA_DHUHR_LIKE)
            add("dz-in-guezzam-signed-at-event", IN_GUEZZAM, EngineTestSupport.dates("2026-06-21", "2026-12-21"), ALGERIA_EVENT_LIKE)
            // E14's ALMANAC sun, once a day (Oman) and at each instant.
            add("om-muscat-almanac", MUSCAT, SEASONS, OMAN_ALMANAC_LIKE)
            add("mwl-almanac-paris", PARIS, SEASONS, MWL_ALMANAC)
            add("mwl-almanac-2099", TUNIS, EngineTestSupport.dates("2099-06-21", "2099-12-21"), MWL_ALMANAC)
            add("km-moroni", MORONI, SEASONS, COMOROS_LIKE)
            // 6.5: Moroni's national zone with the sunrise row at the user's own point, on Anjouan.
            add("km-mutsamudu-moroni-zone", MUTSAMUDU, EngineTestSupport.dates("2026-03-20", "2026-06-21", "2026-12-21"), COMOROS_LIKE) {
                copy(zone = MORONI_ZONE)
            }
            add("re-saint-denis", SAINT_DENIS_RE, SEASONS, REUNION_LIKE)
            add("xk-pristina", PRISTINA, SEASONS + EngineTestSupport.dates("2026-02-28", "2026-12-31"), KOSOVO_LIKE)
            add("ba-mostar-takvim", MOSTAR, SEASONS, BOSNIA_LIKE) { copy(zone = MOSTAR_ZONE, baseZone = SARAJEVO_ZONE) }
            add("ba-mostar-takvim-e6", MOSTAR, SEASONS, BOSNIA_LIKE) { copy(zone = MOSTAR_E6_ZONE, baseZone = SARAJEVO_ZONE) }
            add("ba-takvim-split-points", MOSTAR, SEASONS, BOSNIA_LIKE) { copy(zone = SPLIT_POINTS_ZONE, baseZone = SARAJEVO_ZONE) }
            add("bh-manama", MANAMA, SEASONS, BAHRAIN_LIKE) { copy(zone = BAHRAIN_ZONE) }
            add("bh-manama-exact", MANAMA, EngineTestSupport.dates("2026-06-21"), BAHRAIN_LIKE)
            add("ma-rabat", RABAT, SEASONS, MOROCCO_LIKE) { copy(zone = RABAT_ZONE) }
            add("ma-rabat-exact", RABAT, EngineTestSupport.dates("2026-06-21"), MOROCCO_LIKE)
            add("ma-ifrane-height", IFRANE, EngineTestSupport.dates("2026-06-21", "2026-12-21"), MOROCCO_HEIGHT_LIKE)
            add("ma-rabat-height-zone", RABAT, EngineTestSupport.dates("2026-06-21"), MOROCCO_HEIGHT_LIKE) { copy(zone = RABAT_ZONE) }
            add("re-tampon-floor", LE_TAMPON_300, EngineTestSupport.dates("2026-03-20", "2026-06-21"), REUNION_LIKE) { copy(zone = TAMPON_ZONE) }
            add("re-cilaos-floor", CILAOS, EngineTestSupport.dates("2026-06-21"), REUNION_LIKE) { copy(zone = TAMPON_ZONE) }
            add("kw-jahra", KUWAIT_JAHRA, EngineTestSupport.dates("2026-06-21", "2026-12-21"), PrayerMethods.MWL) {
                copy(zone = JAHRA_ZONE, baseZone = KUWAIT_CITY_ZONE)
            }
            add("sy-damascus", DAMASCUS, SEASONS, SYRIA_LIKE) { copy(zone = DAMASCUS_ZONE) }
            // E19 at mainland French places, and custom methods anywhere.
            add("custom-gmp-paris", PARIS, SEASONS, CUSTOM_GMP)
            add("custom-early-paris", PARIS, SEASONS, CUSTOM_EARLY)
            add("custom-early-istanbul", ISTANBUL, SEASONS, CUSTOM_EARLY)
            add("tunisia-paris", PARIS, SEASONS, PrayerMethods.TUNISIA_18_18)
            // The order guard: short nights and steppers pushing neighbours together.
            add("ecfr90-lulea", LULEA, EngineTestSupport.dates("2026-06-21", "2026-06-22", "2026-12-21"), ECFR_90_LIKE)
            add("uaq-lulea-steppers", LULEA, EngineTestSupport.dates("2026-07-15"), PrayerMethods.UMM_AL_QURA) {
                copy(adjustments = mapOf(TimeEvent.FAJR to 30, TimeEvent.SUNRISE to -30))
            }
            val steppers = listOf(
                mapOf(TimeEvent.FAJR to 30, TimeEvent.SUNRISE to -30),
                mapOf(TimeEvent.DHUHR to 30, TimeEvent.ASR to -30),
                mapOf(TimeEvent.MAGHRIB to 30, TimeEvent.ISHA to -30),
                mapOf(TimeEvent.ISHA to 30, TimeEvent.FAJR to -30),
                mapOf(TimeEvent.DHUHR to -5, TimeEvent.MAGHRIB to -10, TimeEvent.SUNRISE to 5),
                mapOf(TimeEvent.ASR to -20, TimeEvent.FAJR to 25),
            )
            steppers.forEachIndexed { i, adjustments ->
                add("steppers$i-mecca", MECCA, EngineTestSupport.dates("2026-06-21"), PrayerMethods.MWL) { copy(adjustments = adjustments) }
                add("steppers$i-paris", PARIS, EngineTestSupport.dates("2026-06-21", "2026-12-21"), PrayerMethods.MWL) { copy(adjustments = adjustments) }
                add("steppers$i-london", LONDON, EngineTestSupport.dates("2026-06-21"), PrayerMethods.ISNA) { copy(adjustments = adjustments) }
            }
            // M2: the high-latitude and polar rules of 7.4, kept away from the clock changes (clock times read on D).
            val summer = EngineTestSupport.dates("2026-05-15", "2026-06-12", "2026-06-21", "2026-07-05", "2026-08-01")
            val rules = listOf(
                "lre" to HighLatitudeRule.LocalRelativeEstimation, "seventh" to HighLatitudeRule.OneSeventh,
                "middle" to HighLatitudeRule.MiddleOfNight(), "middle-unramped" to HighLatitudeRule.MiddleOfNight(ramped = false),
                "nearest" to HighLatitudeRule.NearestDay(), "nearest-clock" to HighLatitudeRule.NearestDay(lastClockTime = true),
                "absent-angle" to HighLatitudeRule.AngleBasedAbsentNights,
            )
            for ((name, rule) in rules) for ((placeName, p) in listOf("london" to LONDON, "paris" to PARIS, "berlin" to BERLIN, "oslo" to OSLO)) {
                add("$name-$placeName", p, summer, PrayerMethods.MWL.copy(id = "mwl_$name", highLatitude = rule))
            }
            add("nl-caps-amsterdam", AMSTERDAM, summer, PrayerMethods.MWL.copy(id = "mwl_nl",
                highLatitude = HighLatitudeRule.ClockCaps(LocalTime.of(23, 30), LocalTime.of(3, 30))))
            add("mcw-london", LONDON, summer + EngineTestSupport.dates("2026-12-21"), com.tunisianprayertimes.method.EuropeanPresets.MCW)
            add("gmp-paris", PARIS, summer + EngineTestSupport.dates("2026-10-13"), com.tunisianprayertimes.method.EuropeanPresets.GMP)
            add("apbif-paris", PARIS, EngineTestSupport.dates("2026-06-12", "2026-06-13", "2026-06-21", "2026-06-30", "2026-07-01", "2026-10-13"), com.tunisianprayertimes.method.EuropeanPresets.APBIF)
            add("dum-rf-moscow", MOSCOW, EngineTestSupport.dates("2026-04-29", "2026-05-31", "2026-06-21", "2026-07-02", "2026-08-03"), com.tunisianprayertimes.method.EuropeanPresets.DUM_RF)
            add("dum-rt-kazan", KAZAN, summer + EngineTestSupport.dates("2026-11-15"), com.tunisianprayertimes.method.EuropeanPresets.RU_DUM_RT)
            add("diyanet-vienna", VIENNA, summer + EngineTestSupport.dates("2026-10-13"), com.tunisianprayertimes.method.EuropeanPresets.DIYANET_EU)
            add("diyanet-sarajevo", place(43.8563, 18.4131, "Europe/Sarajevo"), summer, com.tunisianprayertimes.method.EuropeanPresets.DIYANET_TR)
            add("diyanet-zurich", place(45.2, 7.7, "Europe/Rome"), summer, com.tunisianprayertimes.method.EuropeanPresets.DIYANET_EU)
            // H6 (fits/diyanet_takdir): just north and south of the cutoff, case A, case B, the held night (H2), a
            // midnight sun, and the south, where the angle times stay and an absent one takes α/60 of the night.
            val takdirDates = EngineTestSupport.dates("2026-03-15", "2026-04-20", "2026-05-10", "2026-05-28", "2026-06-03",
                "2026-06-21", "2026-07-15", "2026-08-10", "2026-08-31", "2026-09-20", "2026-10-15")
            val diyanetEu = com.tunisianprayertimes.method.EuropeanPresets.DIYANET_EU
            add("diyanet-cuneo", place(44.39, 7.55, "Europe/Rome"), takdirDates, diyanetEu)
            add("diyanet-rodez", place(44.35, 2.57, "Europe/Paris"), EngineTestSupport.dates("2026-05-28", "2026-06-21"), diyanetEu)
            add("diyanet-paris", PARIS, takdirDates, diyanetEu)
            add("diyanet-hamburg", place(53.55, 10.0, "Europe/Berlin"), takdirDates, diyanetEu)
            add("diyanet-gavle", place(60.67, 17.14, "Europe/Stockholm"), takdirDates, diyanetEu)
            add("diyanet-tromso", TROMSO, takdirDates + EngineTestSupport.dates("2026-12-21"), diyanetEu)
            add("diyanet-ushuaia", place(-54.79, -68.305, "America/Argentina/Ushuaia"),
                EngineTestSupport.dates("2026-06-21", "2026-10-15", "2026-12-21", "2027-01-15"), diyanetEu)
            add("zagreb-summer", ZAGREB, EngineTestSupport.dates("2026-03-24", "2026-03-25", "2026-06-21", "2026-08-25", "2026-09-01", "2026-09-02"), com.tunisianprayertimes.method.EuropeanPresets.HR_MIZ_ZAGREB)
            add("ljubljana-summer", LJUBLJANA, EngineTestSupport.dates("2026-04-23", "2026-04-24", "2026-05-10", "2026-05-23", "2026-05-24"), com.tunisianprayertimes.method.EuropeanPresets.SI_ISLAMSKA_SKUPNOST)
            // Slovenia's Isha at Sarajevo's point (IshaRule.Angle.at) outside the summer form.
            add("ljubljana-sarajevo-isha", LJUBLJANA, EngineTestSupport.dates("2026-01-15", "2026-10-15", "2026-12-21"), com.tunisianprayertimes.method.EuropeanPresets.SI_ISLAMSKA_SKUPNOST)
            add("irn-oslo", OSLO, EngineTestSupport.dates("2026-04-13", "2026-06-21", "2026-08-27", "2026-12-21"), com.tunisianprayertimes.method.EuropeanPresets.IRN)
            add("irn-tromso", TROMSO, EngineTestSupport.dates("2026-06-21", "2026-12-21", "2026-04-20"), com.tunisianprayertimes.method.EuropeanPresets.IRN)
            add("irn-switch-fixture", OSLO, listOf(89, 90, 103, 172, 239, 251, 252).map { com.tunisianprayertimes.engine.IrnRule.onDay(2026, it) }, com.tunisianprayertimes.method.EuropeanPresets.IRN) {
                copy(zone = IRN_FIXTURE_ZONE)
            }
            add("polar-tromso", TROMSO, EngineTestSupport.dates("2026-06-21", "2026-12-21", "2026-01-10", "2026-05-20"), PrayerMethods.MWL)
            add("polar-murmansk", MURMANSK, EngineTestSupport.dates("2026-12-21", "2026-06-21"), PrayerMethods.MWL)
            add("polar-rovaniemi", ROVANIEMI, EngineTestSupport.dates("2026-12-21"), PrayerMethods.MWL)
            add("polar-rovaniemi-tunisia", ROVANIEMI, EngineTestSupport.dates("2026-12-21"), PrayerMethods.TUNISIA_18_18)
            add("polar-bodo-diyanet", BODO, EngineTestSupport.dates("2026-12-21", "2026-06-21"), com.tunisianprayertimes.method.EuropeanPresets.DIYANET_EU)
            add("polar-lulea", LULEA, EngineTestSupport.dates("2026-06-21"), PrayerMethods.MWL)
            add("polar-ecfr-tromso", TROMSO, EngineTestSupport.dates("2026-06-21", "2026-12-21"), com.tunisianprayertimes.method.EuropeanPresets.ECFR_90)
            return cases
        }

        fun toJson(case: Case): JsonObject {
            val zone = case.place.zone
            fun clock(time: LocalTime?) = time?.let {
                val day = if (it < LocalTime.NOON) case.date.plusDays(1) else case.date
                ZonedDateTime.of(day, it, zone).toInstant().epochSecond
            }
            val noon = ZonedDateTime.of(case.date, LocalTime.NOON, zone).toInstant()
            val standard = zone.rules.getOffset(noon).totalSeconds - zone.rules.getDaylightSavings(noon).seconds.toInt()
            return buildJsonObject {
                put("id", case.id)
                put("place", buildJsonObject {
                    put("latE5", case.place.latE5)
                    put("lngE5", case.place.lngE5)
                    put("elevationM", case.place.elevationM)
                    put("zoneId", case.place.zoneId)
                    put("countryCode", case.place.countryCode)
                })
                put("date", case.date.toString())
                put("method", EngineTestSupport.json.encodeToJsonElement(case.method))
                put("ramadanDay", case.ramadanDay)
                put("zone", case.zone?.let(EngineTestSupport::zoneJson) ?: JsonNull)
                put("baseZone", case.baseZone?.let(EngineTestSupport::zoneJson) ?: JsonNull)
                put("offsetWindow", case.offsetWindow?.let { buildJsonObject { put("from", it.start.toString()); put("to", it.endInclusive.toString()) } } ?: JsonNull)
                put("adjustments", buildJsonObject { case.adjustments.forEach { (e, m) -> put(e.name, m) } })
                put("terrainClass", case.terrainClass?.name)
                put("zoneFacts", buildJsonObject {
                    put("standardOffsetSeconds", standard)
                    put("utcOffsetSeconds", zone.rules.getOffset(noon).totalSeconds)
                    put("localMidnightEpochSeconds", ZonedDateTime.of(case.date, LocalTime.MIDNIGHT, zone).toInstant().epochSecond)
                    // Z's offsets over D ± 400 days, for each date's local noon (S4) and H7's clock times.
                    put("offsetChanges", buildJsonArray {
                        val start = noon.minus(Duration.ofDays(400))
                        val end = noon.plus(Duration.ofDays(400))
                        add(buildJsonArray { add(start.epochSecond); add(zone.rules.getOffset(start).totalSeconds) })
                        var transition = zone.rules.nextTransition(start)
                        while (transition != null && transition.instant < end) {
                            add(buildJsonArray { add(transition.instant.epochSecond); add(transition.offsetAfter.totalSeconds) })
                            transition = zone.rules.nextTransition(transition.instant)
                        }
                    })
                    put("ishaLatestEpochSeconds", clock(case.method.ishaLatest))
                    put("ishaEarliestEpochSeconds", clock(case.method.ishaEarliest))
                })
            }
        }
    }
}

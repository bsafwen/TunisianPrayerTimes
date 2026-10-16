package com.tunisianprayertimes.place

import com.tunisianprayertimes.engine.InmPrayerDays
import com.tunisianprayertimes.engine.PrayerDay
import com.tunisianprayertimes.engine.PrayerEngine
import com.tunisianprayertimes.method.CountryMethodDefaults
import com.tunisianprayertimes.method.PrayerMethod
import com.tunisianprayertimes.method.PrayerMethodChoice
import com.tunisianprayertimes.method.PrayerMethods
import com.tunisianprayertimes.method.isRamadanDay
import java.time.LocalDate
import java.time.chrono.HijrahDate
import java.time.temporal.ChronoField

/**
 * The official-zone assets the app bundles (6.5), by authority, and the one that serves a method in a country. Each
 * asset is read on first use ([lazy]: the app's manifest), so a place uses only its own method's asset.
 */
class OfficialZoneIndex private constructor(private val entries: Array<Entry>) {
    /** One asset: what selects it (its authority, method and countries, its zones' included) and how to read it, once. */
    class Entry(val authority: String, val methodId: String, val countries: Set<String>, load: () -> AuthorityZones?) {
        val zones: AuthorityZones? by lazy(load)
    }

    constructor(assets: List<AuthorityZones>) : this(assets.map { a -> Entry(a.authority, a.methodId, countriesOf(a)) { a } }.toTypedArray())

    private val byMethod: Map<String, List<Entry>> = entries.groupBy { it.methodId }
    private val entryOf: Map<String, Entry> = entries.associateBy { it.authority }

    /** Every asset, read. */
    val byAuthority: Map<String, AuthorityZones> by lazy { entries.mapNotNull { e -> e.zones?.let { e.authority to it } }.toMap() }

    /**
     * The asset of [methodId] that covers [country] (its own country list or one of its zones' countries,
     * Ceuta and Melilla under `ma_habous` included); none for a country no asset of the method covers (where
     * [ZoneMapping] would attach no zone anyway), so a method used abroad reads no asset and shows no table's record.
     * An unknown country takes the method's first asset.
     */
    fun forMethod(methodId: String, country: String?): AuthorityZones? {
        val candidates = byMethod[methodId].orEmpty()
        val cc = country?.let(CountryResolver::normalize) ?: return candidates.firstOrNull()?.zones
        return candidates.firstOrNull { cc in it.countries }?.zones
    }

    /** Algeria's offset windows for any zone of the index (6.2, 8.8). */
    val offsetWindows: ZoneMapping.OffsetWindows = ZoneMapping.OffsetWindows { zone, date ->
        entryOf[zone.authority]?.zones?.let { AlgerianOffsetWindows(it).windowFor(zone, date) }
    }

    companion object {
        val EMPTY = OfficialZoneIndex(emptyList<AuthorityZones>())

        /** An index whose assets are read on first use: the app's, from `official-zones/index.tsv`. */
        fun lazy(entries: List<Entry>) = OfficialZoneIndex(entries.toTypedArray())

        /** An asset's countries as the manifest lists them: its own list and its zones'. */
        fun countriesOf(a: AuthorityZones): Set<String> =
            (a.country.split(',') + a.zones.mapNotNull { it.country }).map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }
}

/**
 * The windows of Algeria's `WINDOW_CEILING` offsets (6.2, 8.8): the ministry's Hijri month containing the
 * date, from the 12 month starts per Hijri year in the asset, or Umm al-Qura's (`HijrahDate`) for a year not
 * there yet. For a place whose base zone has `halfMonthWindows` (Adrar), days 1–15, then days 17 to the end,
 * which also serve day 16.
 */
class AlgerianOffsetWindows(private val zones: AuthorityZones) : ZoneMapping.OffsetWindows {
    override fun windowFor(zone: OfficialZone, date: LocalDate): ClosedRange<LocalDate>? {
        if (zone.offsetRule != ZoneOffsetRule.WINDOW_CEILING) return null
        val (start, next) = month(date)
        val half = zones.baseOf(zone)?.halfMonthWindows == true
        if (!half) return start..next.minusDays(1)
        val day = date.toEpochDay() - start.toEpochDay() + 1
        return if (day <= 15) start..start.plusDays(14) else start.plusDays(16)..next.minusDays(1)
    }

    /** The month containing [date]: its first day and the next month's first day. */
    private fun month(date: LocalDate): Pair<LocalDate, LocalDate> {
        val starts = (monthStarts(hijriYear(date) - 1) + monthStarts(hijriYear(date)) + monthStarts(hijriYear(date) + 1)).sorted()
        val index = starts.indexOfLast { it <= date }
        return starts[index] to starts[index + 1]
    }

    private fun monthStarts(year: Int): List<LocalDate> =
        zones.hijriMonthStarts[year]?.takeIf { it.size == 12 } ?: (1..12).map { month -> LocalDate.from(HijrahDate.of(year, month, 1)) }

    private fun hijriYear(date: LocalDate): Int = HijrahDate.from(date).get(ChronoField.YEAR)
}

/**
 * The repository's computation (plan 6.8, 6.9) without Android: a Tunisian delegation through INM's tables
 * relabelled as instants (D7: no Asr factor, no adjustment), any other place through the engine with its
 * country's choice (6.4), the official zone its method maps it to (6.5, with the validity guard), the Ramadan
 * day of E8, Algeria's offset window and the user's adjustments.
 */
class PlaceTimes(
    private val inm: InmPrayerDays?,
    private val zones: OfficialZoneIndex = OfficialZoneIndex.EMPTY,
    private val guard: ValidityGuard = ValidityGuard(zones.offsetWindows),
) {
    /** The country's default choice for [place] (6.4), before the user picks anything. */
    fun defaultChoice(place: PrayerPlace.Coordinates): PrayerMethodChoice =
        PrayerMethodChoice.default(defaults(place))

    fun defaults(place: PrayerPlace.Coordinates) =
        CountryMethodDefaults.forCountry(place.countryCode, place.zoneId, place.admin1, place.admin2, place.point)

    /** The method a choice computes with; an unknown id falls back to the country's default, then `mwl`. */
    fun method(place: PrayerPlace.Coordinates, choice: PrayerMethodChoice?): PrayerMethod =
        choice?.resolve() ?: PrayerMethods.byId(defaults(place).fallback) ?: PrayerMethods.MWL

    /** The zone [place] maps to under [choice] in [year] (6.5), or null for exact coordinates. */
    fun mapping(place: PrayerPlace.Coordinates, choice: PrayerMethodChoice?, year: Int): MappedZone? {
        val method = method(place, choice)
        return ZoneMapping.resolve(place, method, zones.forMethod(method.id, place.countryCode), choice?.zonePick, year, guard)
    }

    /** Prayer day [date] at [place], or null outside INM's delegations or the supported years. */
    fun day(place: PrayerPlace, choice: PrayerMethodChoice?, date: LocalDate): PrayerDay? {
        if (date.year !in PrayerEngine.SUPPORTED_YEARS) return null
        return when (place) {
            is PrayerPlace.Inm -> inm?.day(place.delegationId, date)
            is PrayerPlace.Coordinates -> {
                val method = method(place, choice)
                val mapped = mapping(place, choice, date.year)
                PrayerEngine.day(
                    place, date, method,
                    ramadanDay = method.isRamadanDay(date),
                    zone = mapped?.zone,
                    baseZone = mapped?.baseZone,
                    offsetWindow = mapped?.zone?.let { zones.offsetWindows.windowFor(it, date) },
                    adjustments = choice?.adjustments.orEmpty(),
                    terrainClass = if (mapped == null) ZoneMapping.ownTerrainClass(place, method, zones.forMethod(method.id, place.countryCode)) else null,
                )
            }
        }
    }
}

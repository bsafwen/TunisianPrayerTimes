package com.tunisianprayertimes.place

import com.tunisianprayertimes.method.CountryMethodDefaults
import com.tunisianprayertimes.method.PrayerMethod
import com.tunisianprayertimes.method.TerrainClass
import com.tunisianprayertimes.method.TerrainRule
import com.tunisianprayertimes.method.ZonePick
import java.time.LocalDate
import kotlin.math.floor

/** The zone a place maps to, with the base zone it shifts (6.5, 8.8). */
data class MappedZone(val zone: OfficialZone, val baseZone: OfficialZone?)

/** True when the place is one of the zone's named places: by its catalogue row, or within a named place's area (6.5). */
fun OfficialZone.names(place: PrayerPlace.Coordinates): Boolean = namedPlaces.any { named ->
    (place.catalogueId != null && place.catalogueId in named.catalogueIds) ||
        (named.areaKm > 0 && CountryMethodDefaults.distanceKm(place.point, named.centre) <= named.areaKm)
}

/**
 * The official zone of a place for a method (plan 6.5): the authority's own rule for attaching it, then
 * the validity guard. Null means exact coordinates (the `EXACT` mapping, no zone in reach, the guard, a
 * place outside the authority's countries, or «حسب موقعي بالضبط»).
 */
object ZoneMapping {
    fun resolve(
        place: PrayerPlace.Coordinates,
        method: PrayerMethod,
        zones: AuthorityZones?,
        zonePick: ZonePick?,
        year: Int,
        guard: ValidityGuard? = null,
    ): MappedZone? {
        if (zones == null || zones.mapping == MappingKind.EXACT || zonePick == ZonePick.Exact) return null
        if (place.countryCode == "TN") return null
        val picked = when (zonePick) {
            is ZonePick.Zone -> zones.byId[zonePick.id]
            else -> attach(place, method, zones)
        } ?: return null
        val mapped = MappedZone(picked, zones.baseOf(picked))
        if (guard != null && !picked.names(place) && guard.acts(place, mapped, method, year, ownTerrainClass(place, method, zones))) return null
        return mapped
    }

    /** The authority's own attachment, before the guard. */
    fun attach(place: PrayerPlace.Coordinates, method: PrayerMethod, zones: AuthorityZones): OfficialZone? {
        val country = countryOf(place, zones) ?: return null
        // Iraq's Kurdistan Region: the Sunni Endowment's cities leave it out, so the method runs at the place (8.8, Q8).
        if (place.admin1 != null && place.admin1 in zones.exactAdmin1) return null
        val candidates = zones.zones.filter { (it.country ?: zones.country.split(',').first()) == country }
        if (candidates.isEmpty()) return null
        // Jordan, step 1 (8.8): below sea level, the governorate's Ghor table (the nearest one if the governorate is unknown).
        if ((place.elevationM ?: 0) < 0) {
            candidates.filter { it.belowSeaLevel && (place.admin1 == null || place.admin1 in it.admin1) }
                .minByOrNull { distance(place, it.point) }?.let { return it }
        }
        if (zones.namedFirst) named(place, candidates)?.let { return it }
        return when (zones.mapping) {
            MappingKind.EXACT -> null
            // One table; a zone that lists admin areas covers only those (Rhône: département 69, else exact coordinates),
            // and MIZ Zagreb's covers Croatia's Zagreb area only (8.8; elsewhere the method at the place).
            MappingKind.NATIONAL -> candidates.firstOrNull { zone ->
                (zone.admin1.isEmpty() || place.admin1 in zone.admin1) && (zone.admin2.isEmpty() || place.admin2 in zone.admin2)
            }?.takeUnless { zones.authority == "hr_miz_zagreb" && !CountryMethodDefaults.inZagrebArea(place.point) }
            MappingKind.NEAREST -> nearestWithRules(place, method, zones, candidates)
            MappingKind.NEAREST_LOCAL -> candidates
                .filter { distance(place, it.point) <= it.reachKm }
                .minByOrNull { distance(place, it.point) }
            MappingKind.LISTED_PLACES -> listed(place, zones, candidates)
            MappingKind.DISTRICT -> candidates.firstOrNull { place.admin2 != null && place.admin2 in it.admin2 }
                ?: nearest(place, candidates)
            MappingKind.NAMED_REGIONS -> candidates
                .flatMap { z -> z.namedPlaces.map { it to z } }
                .minByOrNull { (named, _) -> distance(place, named.centre) }?.second
                ?: nearest(place, candidates)
            MappingKind.ADMIN_AREA -> adminArea(place, candidates)
        }
    }

    /** The place's country as the authority's zones see it, or null outside every country they cover. */
    /**
     * E10: the place's own terrain class, its elevation's, or between the limits the class of the authority's
     * nearest location in its country (plan E10); the nearer limit when the authority has no terrain classes.
     * Null for a method without E10.
     */
    fun ownTerrainClass(place: PrayerPlace.Coordinates, method: PrayerMethod, zones: AuthorityZones?): TerrainClass? {
        val rule = method.maghribTerrain ?: return null
        val elevation = (place.elevationM ?: 0).toDouble()
        val authority = zones?.takeIf { it.terrainClassKm != null } ?: return rule.classOf(elevation, null)
        val country = countryOf(place, authority)
        var candidates = authority.zones.filter { country != null && (it.country ?: authority.country.split(',').first()) == country }
        if (authority.byAdmin1 && place.admin1 != null) candidates.filter { place.admin1 in it.admin1 }.takeIf { it.isNotEmpty() }?.let { candidates = it }
        val nearest = candidates.minByOrNull { distance(place, it.point) }
        return rule.classOf(elevation, nearest?.let { classOf(rule, it) })
    }

    private fun classOf(rule: TerrainRule, zone: OfficialZone): TerrainClass = rule.classOf((zone.elevationM ?: 0).toDouble(), zone.terrainClass)

    private fun countryOf(place: PrayerPlace.Coordinates, zones: AuthorityZones): String? {
        val raw = place.countryCode?.uppercase() ?: return null
        val cc = if (raw == "EH") "MA" else raw
        val covered = (listOf(zones.country) + zones.zones.mapNotNull { it.country }).flatMap { it.split(',') }.toSet()
        return when {
            cc in covered -> cc
            // Ceuta and Melilla take their Habous localities (6.8); a Jerusalem place may take jo_awqaf's Jerusalem entry (8.8).
            cc == "ES" && zones.authority == "ma_habous" && place.admin1 in setOf("CE", "ML", "Ceuta", "Melilla") -> "ES"
            else -> null
        }
    }

    private fun distance(place: PrayerPlace.Coordinates, p: LatLngE5) = CountryMethodDefaults.distanceKm(place.point, p)

    /** The nearest zone in reach; at equal distance the one with the smaller reach (a town inside its valley's disc). */
    private fun nearest(place: PrayerPlace.Coordinates, zones: List<OfficialZone>): OfficialZone? =
        zones.filter { distance(place, it.point) <= it.reachKm }.minWithOrNull(compareBy({ distance(place, it.point) }, { it.reachKm }))

    /** NEAREST with the rules some authorities add: by province, named places first, Yemen's terrain class, tiers. */
    private fun nearestWithRules(place: PrayerPlace.Coordinates, method: PrayerMethod, zones: AuthorityZones, all: List<OfficialZone>): OfficialZone? {
        // A zone with a reach serves only places within it (Austria's capitals; Jordan's Jerusalem entry, 8.8).
        val inReach = all.filter { distance(place, it.point) <= it.reachKm }
        var candidates = inReach
        if (zones.byAdmin1 && place.admin1 != null) {
            val province = inReach.filter { place.admin1 in it.admin1 }
            if (province.isNotEmpty()) candidates = province
        }
        if (zones.namedFirst) {
            // Not one of a zone's named places: the nearest town centre (Egypt, Syria) or zone point (Oman, Qatar), in the
            // lowest tier in reach (a Syrian table that no sampled screen shows, at another zone's town, is tier 1).
            val tier = candidates.minOfOrNull { it.mappingTier } ?: return null
            return candidates.filter { it.mappingTier == tier }
                .minByOrNull { z -> (z.namedPlaces.map { distance(place, it.centre) } + distance(place, z.point)).min() }
        }
        zones.terrainClassKm?.let { km ->
            val rule = method.maghribTerrain ?: TerrainRule()
            val nearestLocation = candidates.minByOrNull { distance(place, it.point) } ?: return null
            // E10: the place's own class; between the limits the nearest location's, so that location itself.
            val own = rule.classOf((place.elevationM ?: 0).toDouble(), classOf(rule, nearestLocation))
            candidates.filter { classOf(rule, it) == own && distance(place, it.point) <= km }
                .minByOrNull { distance(place, it.point) }?.let { return it }
            return nearestLocation
        }
        // Tiers (Austria): the lowest tier with a zone in reach, then the nearest.
        for (tier in candidates.map { it.mappingTier }.distinct().sorted()) {
            nearest(place, candidates.filter { it.mappingTier == tier })?.let { return it }
        }
        return null
    }

    /** A zone the place is one of the named places of: a catalogue match first, then the nearest centre whose area holds it (6.5). */
    private fun named(place: PrayerPlace.Coordinates, candidates: List<OfficialZone>): OfficialZone? {
        candidates.firstOrNull { z -> z.namedPlaces.any { place.catalogueId != null && place.catalogueId in it.catalogueIds } }?.let { return it }
        return candidates.flatMap { z -> z.namedPlaces.filter { it.areaKm > 0 && distance(place, it.centre) <= it.areaKm }.map { it to z } }
            .minByOrNull { (named, _) -> distance(place, named.centre) }?.second
    }

    /**
     * LISTED_PLACES: the nearest listed place whatever the distance; where the listed towns have a reach
     * (Kosovo's seven and Montenegro's five, 8.8), a town in reach, else the authority's own fallback:
     * the takvim's table unshifted (Kosovo and the Preševo Valley), or in Montenegro Sarajevo's times plus
     * the whole minute nearest to 4 × (18.41 − λ) on all six times.
     */
    private fun listed(place: PrayerPlace.Coordinates, zones: AuthorityZones, candidates: List<OfficialZone>): OfficialZone? {
        val limited = candidates.filter { it.reachKm.isFinite() }
        if (limited.isEmpty()) return candidates.minByOrNull { distance(place, it.point) }
        nearest(place, limited)?.let { return it }
        if (place.countryCode == "ME") return montenegro(place, zones)
        return candidates.firstOrNull { it.reachKm.isInfinite() } ?: zones.zones.firstOrNull { it.baseZoneId == null }
    }

    private fun montenegro(place: PrayerPlace.Coordinates, zones: AuthorityZones): OfficialZone? {
        val sarajevo = zones.zones.firstOrNull { it.baseZoneId == null } ?: return null
        val shift = floor(4 * (18.41 - place.lngE5 / 1e5) + 0.5)
        // One id per whole-minute shift, the table it names: a move that keeps it is no change of zone (6.8).
        return OfficialZone(
            authority = zones.authority, id = "me:${shift.toInt()}", nameAr = sarajevo.nameAr,
            point = place.point, baseZoneId = sarajevo.id, country = "ME",
            offsets = com.tunisianprayertimes.engine.TimeEvent.entries.associateWith { shift },
        )
    }

    /** ADMIN_AREA: the entry of the place's municipality or district (admin2), else of its province (admin1), else the nearest. */
    private fun adminArea(place: PrayerPlace.Coordinates, candidates: List<OfficialZone>): OfficialZone? =
        candidates.firstOrNull { place.admin2 != null && place.admin2 in it.admin2 }
            ?: candidates.filter { place.admin1 != null && place.admin1 in it.admin1 }.minByOrNull { distance(place, it.point) }
            ?: candidates.minByOrNull { distance(place, it.point) }

    /** The window of Algeria's offset rule containing a date (6.9): supplied by the repository from the Hijri months. */
    fun interface OffsetWindows {
        fun windowFor(zone: OfficialZone, date: LocalDate): ClosedRange<LocalDate>?
    }
}

package com.tunisianprayertimes.place

import com.tunisianprayertimes.engine.EngineExplanation
import com.tunisianprayertimes.engine.EventStatus
import com.tunisianprayertimes.engine.PrayerEngine
import com.tunisianprayertimes.engine.TimeEvent
import com.tunisianprayertimes.method.PrayerMethod
import com.tunisianprayertimes.method.TerrainClass
import java.security.MessageDigest
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import kotlinx.serialization.json.Json

/**
 * Verdicts kept across processes: a year's scan is about 1,500 engine runs (about a second on a fast phone), which a
 * cold start abroad would otherwise pay again. The app clears its store whenever it is installed again, since the
 * engine or the zone assets may have changed.
 */
interface VerdictStore {
    fun get(key: String): Boolean?
    fun put(key: String, acts: Boolean)
}

/**
 * The validity guard of plan 6.5 (owner rule, 2026-10-11): a place the authority does not name itself keeps
 * its attached table only where, over the year, none of the table's prayers comes before the place's own
 * start, and its sunrise not after the place's own, by more than the table itself does at its own place on
 * its worst day plus 4 minutes. Where it acts, the place takes the method at its own point.
 *
 * [offsetWindows] gives Algeria's offset windows, as the repository passes them to the engine (6.9). [store] keeps
 * the verdicts across processes.
 */
class ValidityGuard(private val offsetWindows: ZoneMapping.OffsetWindows? = null, private val store: VerdictStore? = null) {
    private val verdicts = HashMap<String, Boolean>()
    private val margins = HashMap<String, Map<TimeEvent, Double>>()
    private val scans = HashMap<String, FutureTask<Boolean>>()

    /**
     * True when the guard acts: the place takes the method at its own point for [year]. [terrainClass] is the
     * place's own E10 class (`ZoneMapping.ownTerrainClass`), the one it would compute with at its own point.
     * One scan per verdict: a caller arriving while another thread scans (the UI while the warm-up runs) waits for it.
     */
    fun acts(place: PrayerPlace.Coordinates, mapped: MappedZone, method: PrayerMethod, year: Int, terrainClass: TerrainClass? = null): Boolean {
        val key = "${place.cacheKey}|${mapped.zone.authority}|${mapped.zone.id}|${method.id}|${method.hashCode()}|$year"
        synchronized(verdicts) { verdicts[key] }?.let { return it }
        // The in-memory key holds method.hashCode(), which changes from one process to the next (enums): the store's
        // key names the method by its serialized form instead.
        val storeKey = store?.let { "${place.cacheKey}|${mapped.zone.authority}|${mapped.zone.id}|${fingerprint(method)}|$year" }
        if (storeKey != null) runCatching { store?.get(storeKey) }.getOrNull()?.let { return remember(key, it) }
        var owner = false
        val scan = synchronized(scans) {
            scans.getOrPut(key) { owner = true; FutureTask { scan(place, mapped, method, year, terrainClass) } }
        }
        try {
            if (owner) scan.run()
            val acts = try { scan.get() } catch (failed: ExecutionException) { throw failed.cause ?: failed }
            if (owner) {
                remember(key, acts)
                if (storeKey != null) runCatching { store?.put(storeKey, acts) }
            }
            return acts
        } finally {
            if (owner) synchronized(scans) { scans.remove(key) }
        }
    }

    private fun remember(key: String, acts: Boolean): Boolean {
        synchronized(verdicts) {
            if (verdicts.size >= 512) verdicts.clear()
            verdicts[key] = acts
        }
        return acts
    }

    private fun scan(place: PrayerPlace.Coordinates, mapped: MappedZone, method: PrayerMethod, year: Int, terrainClass: TerrainClass?): Boolean {
        val margin = margin(place, mapped, method, year)
        var acts = false
        forEachDay(year) { date ->
            if (acts) return@forEachDay
            val leads = leads(place, mapped, method, date, terrainClass)
            for ((event, lead) in leads) {
                val allowed = (margin[event] ?: Double.NEGATIVE_INFINITY).let { if (it == Double.NEGATIVE_INFINITY) 0.0 else it } + ALLOWANCE_MINUTES
                if (lead > allowed + 1e-9) {
                    acts = true
                    break
                }
            }
        }
        return acts
    }

    /**
     * Each time's margin: the table's largest lead at its own place over the year (6.5, item 4), where the
     * table's place is the zone's point (its elevation, h = 0 when null), or for a zone built at the user's own
     * point, its base zone's.
     */
    private fun margin(place: PrayerPlace.Coordinates, mapped: MappedZone, method: PrayerMethod, year: Int): Map<TimeEvent, Double> {
        val key = "${mapped.zone.authority}|${mapped.zone.id}|${method.id}|${method.hashCode()}|$year"
        synchronized(margins) { margins[key] }?.let { return it }
        val own = if (mapped.zone.id.startsWith("me:") && mapped.baseZone != null) MappedZone(mapped.baseZone, null) else mapped
        val ownPlace = place.copy(
            latE5 = own.zone.point.latE5, lngE5 = own.zone.point.lngE5, elevationM = own.zone.elevationM ?: 0,
            catalogueId = null,
        )
        val largest = HashMap<TimeEvent, Double>()
        // Item 3: in Yemen, the table's own place in the zone's own class (E10).
        val ownClass = method.maghribTerrain?.classOf((own.zone.elevationM ?: 0).toDouble(), own.zone.terrainClass)
        forEachDay(year) { date ->
            for ((event, lead) in leads(ownPlace, own, method, date, ownClass)) {
                largest[event] = maxOf(largest[event] ?: Double.NEGATIVE_INFINITY, lead)
            }
        }
        synchronized(margins) {
            if (margins.size >= 512) margins.clear()
            margins[key] = largest
        }
        return largest
    }

    /**
     * The day's leads in minutes (6.5, items 1–5): R − T for the five prayers and T − R for sunrise, where T
     * is the table's rounded time for the place and R the method's unrounded time at the place's own point,
     * both without the user's adjustments; a time is compared only where both are COMPUTED.
     */
    private fun leads(place: PrayerPlace.Coordinates, mapped: MappedZone, method: PrayerMethod, date: LocalDate, terrainClass: TerrainClass?): Map<TimeEvent, Double> {
        val window = offsetWindows?.windowFor(mapped.zone, date)
        val table = PrayerEngine.day(place, date, method, zone = mapped.zone, baseZone = mapped.baseZone, offsetWindow = window)
        val reference: EngineExplanation = PrayerEngine.explain(place, date, method, terrainClass = terrainClass)
        val out = HashMap<TimeEvent, Double>()
        for (event in TimeEvent.entries) {
            if (event == TimeEvent.SUNRISE && mapped.zone.sunriseAtPlace) continue
            val t = table[event]
            val r = reference.events[event.ordinal]
            if (t.status != EventStatus.COMPUTED || r.time.status != EventStatus.COMPUTED) continue
            val tAt = checkNotNull(t.at)
            val rAt = checkNotNull(r.unrounded)
            val rMinusT = Duration.between(tAt, rAt).toNanos() / 60e9
            out[event] = if (event == TimeEvent.SUNRISE) -rMinusT else rMinusT
        }
        return out
    }

    private inline fun forEachDay(year: Int, action: (LocalDate) -> Unit) {
        var date = LocalDate.of(year, 1, 1)
        while (date.year == year) {
            action(date)
            date = date.plusDays(1)
        }
    }

    companion object {
        /** What the former 60 km reach accepted between a place and its table, rounded up (6.5). */
        const val ALLOWANCE_MINUTES = 4.0

        private val FINGERPRINT_JSON = Json { encodeDefaults = true }

        /** The method as the same text in every process: its serialized form, hashed. */
        private fun fingerprint(method: PrayerMethod): String {
            val canonical = FINGERPRINT_JSON.encodeToString(PrayerMethod.serializer(), method)
            val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            return digest.take(12).joinToString("") { "%02x".format(it) }
        }
    }
}

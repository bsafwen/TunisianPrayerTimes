package com.tunisianprayertimes

import android.content.Context
import android.util.Log
import com.tunisianprayertimes.method.CountryMethodDefaults
import com.tunisianprayertimes.place.CityIndex
import com.tunisianprayertimes.place.CityRef
import com.tunisianprayertimes.place.LatLngE5
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor

/**
 * The bundled cities of plan 6.8 (`assets/places/europe-cities.tsv` and `arab-cities.tsv`, built by
 * scripts/places/build_catalogue.py), in one 1° grid. For the country step only, Tunisia's delegation seats count
 * as TN rows, since the catalogue has none. Land-neighbour rows serve country and zone detection only. Without the
 * assets the catalogue is empty and a fix abroad falls back on the network country and the phone's zone.
 */
class PlaceCatalogue private constructor(
    private val rows: List<Row>,
    /** "CC.code" to GeoNames' admin1 name, for the countries whose admin1 the app stores (6.8). */
    private val admin1Names: Map<String, String> = emptyMap(),
) : CityIndex {
    /** The GeoNames name of a stored admin1 code, or null. */
    fun admin1Name(countryCode: String?, code: String?): String? =
        if (countryCode == null || code == null) null else admin1Names["${countryCode.uppercase()}.$code"]

    /** One row with the names the picker and the «قرب X» label read. */
    data class Row(val ref: CityRef, val name: String, val ascii: String, val arabic: String, val aliases: List<String>, val dem: Int?) {
        /** The picker shows only real catalogue cities (D2): not a neighbour's row, nor a delegation seat. */
        val listed: Boolean get() = !ref.neighbourOnly && ref.countryCode != "TN"
    }

    private val grid: Map<Long, List<Row>> = rows.groupBy { cell(floor(it.ref.point.latitude).toInt(), floor(it.ref.point.longitude).toInt()) }

    val size: Int get() = rows.size

    override fun within(point: LatLngE5, km: Double): List<CityRef> = rowsWithin(point, km).map { it.first.ref }

    /** Rows within [km] of [point], nearest first, with their distances. */
    fun rowsWithin(point: LatLngE5, km: Double): List<Pair<Row, Double>> {
        val latSpan = ceil(km / 111.0).toInt()
        val lngSpan = ceil(km / (111.0 * cos(Math.toRadians(point.latitude)).coerceAtLeast(0.05))).toInt()
        val lat0 = floor(point.latitude).toInt()
        val lng0 = floor(point.longitude).toInt()
        val out = ArrayList<Pair<Row, Double>>()
        for (dLat in -latSpan..latSpan) for (dLng in -lngSpan..lngSpan) {
            grid[cell(lat0 + dLat, wrap(lng0 + dLng))]?.forEach { row ->
                val d = CountryMethodDefaults.distanceKm(point, row.ref.point)
                if (d <= km) out += row to d
            }
        }
        return out.sortedBy { it.second }
    }

    override fun nearestOf(point: LatLngE5, countryCode: String): CityRef? = nearestRowOf(point, countryCode)?.ref

    /** The nearest row of [countryCode], searching wider rings until one is found (6.8: the département of the nearest FR row). */
    fun nearestRowOf(point: LatLngE5, countryCode: String, maxKm: Double = 2_000.0): Row? {
        var km = 50.0
        while (km <= maxKm) {
            rowsWithin(point, km).firstOrNull { (row, _) -> com.tunisianprayertimes.place.CountryResolver.normalize(row.ref.countryCode) == countryCode && !row.ref.neighbourOnly }
                ?.let { return it.first }
            km *= 2
        }
        return rows.filter { com.tunisianprayertimes.place.CountryResolver.normalize(it.ref.countryCode) == countryCode && !it.ref.neighbourOnly }
            .minByOrNull { CountryMethodDefaults.distanceKm(point, it.ref.point) }
    }

    /** The row of a catalogue id (a picked city). */
    fun byId(id: Long): Row? = rows.firstOrNull { it.ref.id == id }

    /** Every listed row, for the picker. */
    fun listedRows(): List<Row> = rows.filter { it.listed }

    private fun cell(lat: Int, lng: Int): Long = lat.toLong() * 1000L + lng.toLong()

    private fun wrap(lng: Int): Int = ((lng + 180) % 360 + 360) % 360 - 180

    companion object {
        private const val TAG = "PlaceCatalogue"
        private val FILES = listOf("places/europe-cities.tsv", "places/arab-cities.tsv")

        @Volatile
        private var loaded: PlaceCatalogue? = null

        fun get(context: Context): PlaceCatalogue = loaded ?: synchronized(this) {
            loaded ?: load(context.applicationContext).also { loaded = it }
        }

        private fun load(context: Context): PlaceCatalogue {
            val rows = ArrayList<Row>()
            for (file in FILES) {
                runCatching { context.assets.open(file).bufferedReader().useLines { lines -> rows += parse(lines) } }
                    .onFailure { Log.i(TAG, "No catalogue file $file") }
            }
            // Tunisia's delegation seats, for the country step only (6.8).
            runCatching {
                GouvernoratRepository.loadAll(context).flatMap { it.delegations }.filter { it.lat != 0.0 && it.lng != 0.0 }.forEach { d ->
                    rows += Row(CityRef(-d.id.toLong(), LatLngE5.of(d.lat, d.lng), "TN", "Africa/Tunis"), d.nomFr, d.nomFr, d.nomAr, emptyList(), null)
                }
            }
            val admin1 = runCatching {
                context.assets.open("places/admin1-names.tsv").bufferedReader().useLines { lines ->
                    lines.drop(1).mapNotNull { line ->
                        val c = line.split('\t')
                        if (c.size >= 3) "${c[0]}.${c[1]}" to c[2] else null
                    }.toMap()
                }
            }.getOrDefault(emptyMap())
            return PlaceCatalogue(rows, admin1)
        }

        /**
         * The build script's format (6.8): a header, then one row per place, sorted by country; an empty zone repeats
         * the row above's (the catalogue's 2 MB budget).
         */
        internal fun parse(lines: Sequence<String>): List<Row> {
            val out = ArrayList<Row>()
            var header: List<String>? = null
            var lastZone = ""
            for (line in lines) {
                if (line.isBlank()) continue
                val cells = line.split('\t')
                if (header == null) {
                    header = cells
                    continue
                }
                val c = header.zip(cells).toMap()
                // Carried down before any row is skipped, so the zone follows the file's own order.
                val zone = c["zone"].orEmpty().ifEmpty { lastZone }
                lastZone = zone
                val id = c["id"]?.toLongOrNull() ?: continue
                val lat = c["latE5"]?.toIntOrNull() ?: continue
                val lng = c["lngE5"]?.toIntOrNull() ?: continue
                val cc = c["country"].orEmpty()
                if (cc.length != 2 || zone.isEmpty()) continue
                out += Row(
                    ref = CityRef(
                        id = id, point = LatLngE5(lat, lng), countryCode = cc, zoneId = zone,
                        admin1 = c["admin1"]?.ifEmpty { null }, admin2 = c["admin2"]?.ifEmpty { null },
                        population = c["population"]?.toIntOrNull() ?: 0, neighbourOnly = c["flags"].orEmpty().contains('n'),
                    ),
                    // The build leaves ascii empty where it equals the name (the catalogue's 2 MB budget).
                    name = c["name"].orEmpty(), ascii = c["ascii"].orEmpty().ifEmpty { c["name"].orEmpty() }, arabic = c["arabic"].orEmpty(),
                    aliases = c["aliases"].orEmpty().split('|').filter { it.isNotEmpty() },
                    dem = c["dem"]?.toIntOrNull(),
                )
            }
            return out
        }

        /** A catalogue over [rows], for tests. */
        internal fun of(rows: List<Row>, admin1Names: Map<String, String> = emptyMap()): PlaceCatalogue = PlaceCatalogue(rows, admin1Names)

        /** Replaces the loaded catalogue, for tests; null loads the assets again. */
        @androidx.annotation.VisibleForTesting
        internal fun setForTests(catalogue: PlaceCatalogue?) {
            loaded = catalogue
        }
    }
}

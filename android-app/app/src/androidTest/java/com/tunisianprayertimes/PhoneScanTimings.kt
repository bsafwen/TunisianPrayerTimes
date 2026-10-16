package com.tunisianprayertimes

import com.tunisianprayertimes.engine.HighLatitudeRule
import com.tunisianprayertimes.engine.InmPrayerDays
import com.tunisianprayertimes.method.PrayerMethodChoice
import com.tunisianprayertimes.place.OfficialZoneIndex
import com.tunisianprayertimes.place.OfficialZones
import com.tunisianprayertimes.place.PlaceTimes
import com.tunisianprayertimes.place.PrayerPlace
import com.tunisianprayertimes.place.ZoneSource
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.ZipFile

/**
 * Plan M9 on a real phone, read-only: the 365-day scans timed in a process of their own, so the app, its data and its
 * alarms are never touched. It runs as the adb shell user with the installed APK on the class path and reads the
 * assets out of that APK:
 *
 *     adb push app-debug-androidTest.apk /data/local/tmp/scan-timings.apk
 *     adb shell CLASSPATH=/data/local/tmp/scan-timings.apk:<base.apk> app_process /system/bin \
 *         com.tunisianprayertimes.PhoneScanTimings <base.apk> [delegation id] [label prefix: one scan, cold]
 *
 * Each scan is cold for its place (its own season, its authority's asset parsed on first use), as when a place is
 * stored; the process itself is interpreted and JIT-compiled from scratch, without the app's installed profile.
 */
object PhoneScanTimings {
    private const val TROMSO = 3133895L
    private const val PARIS = 2988507L
    private const val CASABLANCA = 2553604L
    private const val MUSCAT = 287286L
    private const val HAMBURG = 2911298L

    @JvmStatic
    fun main(args: Array<String>) {
        val apk = ZipFile(args[0])
        fun asset(name: String): String =
            apk.getInputStream(checkNotNull(apk.getEntry("assets/$name")) { name }).bufferedReader().use { it.readText() }
        val entries = asset("official-zones/index.tsv").lines().drop(1).filter { it.isNotBlank() }.mapNotNull { line ->
            val c = line.split('\t')
            if (c.size < 3) null
            else OfficialZoneIndex.Entry(c[0], c[1], c[2].split(',').filter { it.isNotBlank() }.toSet()) {
                OfficialZones.parse(asset("official-zones/${c[0]}.json"))
            }
        }
        val times = PlaceTimes(InmPrayerDays(InmPrayerTimes.fromParamsJson(asset(InmPrayerTimes.PARAMS_ASSET))), OfficialZoneIndex.lazy(entries))
        val rows = PlaceCatalogue.parse(asset("places/europe-cities.tsv").lineSequence()) +
            PlaceCatalogue.parse(asset("places/arab-cities.tsv").lineSequence())

        /** A catalogue city as the picker stores it (PlaceResolution.placeFromCatalogue, its own values). */
        fun city(id: Long): PrayerPlace.Coordinates {
            val ref = rows.first { it.ref.id == id }.ref
            val row = rows.first { it.ref.id == id }
            val arab = ref.countryCode in setOf("MA", "OM")
            return PrayerPlace.Coordinates(
                latE5 = ref.point.latE5, lngE5 = ref.point.lngE5, elevationM = if (arab) row.dem else null,
                zoneId = ref.zoneId, zoneSource = ZoneSource.CATALOGUE, countryCode = ref.countryCode,
                catalogueId = ref.id, admin1 = ref.admin1, admin2 = ref.admin2,
            )
        }

        val from = LocalDate.now(ZoneId.of("Europe/Paris"))
        val lines = mutableListOf("scan\tms\tdays")
        // A third argument runs only the scans whose label starts with it: one scan in a cold process.
        val only = args.getOrNull(2)
        fun timed(label: String, place: PrayerPlace, choice: PrayerMethodChoice?) {
            if (only != null && !label.startsWith(only)) return
            val start = System.nanoTime()
            val days = (0L until 365L).count { times.day(place, choice, from.plusDays(it)) != null }
            lines += "$label\t${(System.nanoTime() - start) / 1_000_000}\t$days"
        }
        val tromso = city(TROMSO)
        timed("tromso-default-${times.defaultChoice(tromso).methodId}", tromso, times.defaultChoice(tromso))
        timed("tromso-mwl-polar-gate", tromso, times.defaultChoice(tromso).copy(methodId = "mwl"))
        val paris = city(PARIS)
        timed("paris-default-${times.defaultChoice(paris).methodId}", paris, times.defaultChoice(paris))
        timed("paris-lre", paris, times.defaultChoice(paris).copy(highLatitude = HighLatitudeRule.LocalRelativeEstimation))
        val casablanca = city(CASABLANCA)
        timed("casablanca-mapped-zone", casablanca, times.defaultChoice(casablanca))
        val muscat = city(MUSCAT)
        timed("muscat-mapped-zone", muscat, times.defaultChoice(muscat))
        val hamburg = city(HAMBURG)
        timed("hamburg-${times.defaultChoice(hamburg).methodId}-takdir", hamburg, times.defaultChoice(hamburg))
        val delegation = args.getOrNull(1)?.toIntOrNull() ?: 615
        timed("delegation-$delegation-inm", PrayerPlace.Inm(delegation), null)
        println(lines.joinToString("\n"))
    }
}

package com.tunisianprayertimes.place

import com.tunisianprayertimes.InmPrayerTimes
import com.tunisianprayertimes.engine.InmPrayerDays
import com.tunisianprayertimes.engine.PrayerDay
import com.tunisianprayertimes.engine.TimeEvent
import com.tunisianprayertimes.method.PrayerMethodChoice
import com.tunisianprayertimes.silence.SilenceDefaults
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.zone.ZoneRulesProvider
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Plan 9.1, "On device": the prayer days that DeviceScenariosInstrumentedTest read on the dedicated emulator
 * (its `files/device-scenarios.tsv`, pulled to the path in DEVICE_SCENARIOS_DUMP), computed again here from the same
 * assets by the engine that EngineReferenceCrossCheckTest ties to the Python reference: each row's instant and
 * status, its wall clock in the place's zone (the phone's zone rules against the JDK's, when both have the same tzdata),
 * and Tunis (615) against meteo.tn's tables in docs/csv/615. Skipped without the variable.
 */
class DeviceScenariosCheckTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val local = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (dir.resolve("android-app/app/src/main/assets/official-zones").isDirectory) return dir
            dir = dir.parentFile
        }
        error("No repository root above ${System.getProperty("user.dir")}")
    }

    @Test
    fun theDevicesPrayerDaysAreTheEngines() {
        val dump = System.getenv("DEVICE_SCENARIOS_DUMP")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        val root = repoRoot()
        val inm = InmPrayerTimes.fromParamsJson(checkNotNull(javaClass.getResource("/delegation_params.json")).readText())
        val assets = root.resolve("android-app/app/src/main/assets/official-zones").listFiles { f -> f.extension == "json" }!!
            .sortedBy { it.name }.map { OfficialZones.parse(it.readText()) }
        val times = PlaceTimes(InmPrayerDays(inm), OfficialZoneIndex(assets))

        val lines = dump.readLines().filter { it.isNotBlank() }
        val device = lines.filter { it.startsWith("#") }.joinToString(" ")
        val body = lines.filterNot { it.startsWith("#") }
        val col = body.first().split('\t').withIndex().associate { it.value to it.index }
        val days = HashMap<String, PrayerDay?>()
        val meteo = HashMap<String, List<String>>()
        val problems = mutableListOf<String>()
        // Wall clocks are compared only when both sides have the same tzdata: a newer release changes zones (Morocco's
        // rules of 2026-09-20 came with 2026c), and then a difference is the older side's, listed but not failed.
        val deviceTz = Regex("""tzdata=(\S+)""").find(device)?.groupValues?.get(1)
        val jdkTz = ZoneRulesProvider.getVersions("UTC").lastKey()
        val sameTzdata = deviceTz == null || deviceTz == jdkTz
        val wallNotes = mutableListOf<String>()
        var rows = 0
        var tunisRows = 0
        for (line in body.drop(1)) {
            val c = line.split('\t')
            fun v(name: String) = c[col.getValue(name)]
            val kind = v("kind")
            val place: PrayerPlace = if (kind.startsWith("inm:")) PrayerPlace.Inm(kind.removePrefix("inm:").toInt()) else PrayerPlace.Coordinates(
                latE5 = v("latE5").toInt(),
                lngE5 = v("lngE5").toInt(),
                elevationM = v("elevationM").toIntOrNull(),
                zoneId = v("zoneId"),
                zoneSource = ZoneSource.valueOf(v("zoneSource")),
                countryCode = v("countryCode").ifEmpty { null },
                catalogueId = v("catalogueId").toLongOrNull(),
                admin1 = v("admin1").ifEmpty { null },
                admin2 = v("admin2").ifEmpty { null },
            )
            val choice = v("choice").ifEmpty { null }?.let { json.decodeFromString(PrayerMethodChoice.serializer(), it) }
            val date = LocalDate.parse(v("date"))
            val event = v("event")
            val where = "${v("scenario")} $date $event"
            val day = days.getOrPut("${place.cacheKey}|${choice?.key}|$date") { times.day(place, choice, date) }
            if (day == null) {
                problems += "$where: no prayer day on the JVM"
                continue
            }
            val expected: Instant?
            val expectedStatus: String
            if (event == "JUMUA") {
                expected = day[TimeEvent.DHUHR].at?.let { SilenceDefaults.BUNDLED.jumuaTime(place, date, it) }
                expectedStatus = ""
            } else {
                val time = day[TimeEvent.valueOf(event)]
                expected = time.at
                expectedStatus = time.status.name
            }
            rows++
            val actual = v("epochSecond").toLongOrNull()
            if (actual != expected?.epochSecond) problems += "$where: device ${actual?.let(Instant::ofEpochSecond)}, JVM $expected"
            if (v("status") != expectedStatus) problems += "$where: device ${v("status")}, JVM $expectedStatus"
            val zone = ZoneId.of(v("zoneId"))
            val wall = expected?.atZone(zone)?.format(local).orEmpty()
            if (actual == expected?.epochSecond && v("local") != wall) {
                val difference = "$where: device shows ${v("local")}, the JDK's $zone rules give $wall"
                if (sameTzdata) problems += difference else wallNotes += difference
            }

            // Tunis against meteo.tn's published month (docs/csv/<delegation>/<year>/<MM>.csv).
            if (place is PrayerPlace.Inm && event != "JUMUA") {
                val file = root.resolve("docs/csv/${place.delegationId}/${date.year}/%02d.csv".format(date.monthValue))
                val month = meteo.getOrPut(file.path) { if (file.isFile) file.readLines() else emptyList() }
                val header = month.firstOrNull()?.split(',') ?: run { problems += "$where: no $file"; continue }
                val row = month.drop(1).map { it.split(',') }.firstOrNull { it[0].toIntOrNull() == date.dayOfMonth }
                val column = header.indexOf(MONTH_COLUMNS.getValue(TimeEvent.valueOf(event)))
                val published = row?.getOrNull(column)
                if (published == null || v("local").substringAfter(' ') != published) {
                    problems += "$where: device shows ${v("local")}, meteo.tn publishes $published"
                }
                tunisRows++
            }
        }
        println("Device scenarios ($device): $rows rows checked, $tunisRows of them against docs/csv")
        if (wallNotes.isNotEmpty()) {
            println("Device scenarios: ${wallNotes.size} wall clocks differ between the device's tzdata $deviceTz and the JDK's $jdkTz (not failed):")
            wallNotes.forEach { println("  $it") }
        }
        assertTrue(rows > 0, "No rows in $dump")
        assertTrue(problems.isEmpty(), "${problems.size} differences:\n" + problems.take(80).joinToString("\n"))
    }

    private companion object {
        val MONTH_COLUMNS = mapOf(
            TimeEvent.FAJR to "Fajr", TimeEvent.SUNRISE to "Shuruk", TimeEvent.DHUHR to "Duhr",
            TimeEvent.ASR to "Asr", TimeEvent.MAGHRIB to "Maghrib", TimeEvent.ISHA to "Isha",
        )
    }
}

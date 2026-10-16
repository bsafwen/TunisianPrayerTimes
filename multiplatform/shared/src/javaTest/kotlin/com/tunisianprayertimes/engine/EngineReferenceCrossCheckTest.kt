package com.tunisianprayertimes.engine

import com.tunisianprayertimes.method.PrayerMethod
import com.tunisianprayertimes.method.Rounding
import com.tunisianprayertimes.method.TerrainClass
import com.tunisianprayertimes.place.PrayerPlace
import com.tunisianprayertimes.place.ZoneSource
import java.time.Instant
import java.time.LocalDate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The engine against the independent Python reference (plan 9.1): every case of
 * test-data/engine-reference/cases.json, computed by scripts/prayer_formula/prayer_engine_reference.py
 * into expected.tsv. The same status (except where the order guard compared two instants less than
 * 1 s apart), instants within 1 s, and the same minute (except within 1 s of the rounding boundary, or
 * of 60 s from the night's lowest point, where the rounding mode changes).
 */
class EngineReferenceCrossCheckTest {
    private val json = Json { ignoreUnknownKeys = true }

    private data class Expected(val status: EventStatus, val rule: String?, val minute: Instant?, val seconds: Double?)

    private fun resource(path: String) = checkNotNull(javaClass.getResource(path)) { "missing $path" }.readText()

    private fun expected(): Map<String, Map<TimeEvent, Expected>> =
        resource("/engine-reference/expected.tsv").lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t') }
            .groupBy({ it[0] }) { cells ->
                TimeEvent.valueOf(cells[1]) to Expected(
                    EventStatus.valueOf(cells[2]),
                    cells[3].takeIf { it != "-" },
                    cells[4].takeIf { it != "-" }?.let(Instant::parse),
                    cells[5].takeIf { it != "-" }?.toDouble(),
                )
            }
            .mapValues { (_, rows) -> rows.toMap() }

    @Test
    fun engineAgreesWithTheReference() {
        val expected = expected()
        val cases = Json.parseToJsonElement(resource("/engine-reference/cases.json")).jsonArray
        assertEquals(cases.size, expected.size, "every case has its six expected times")
        var compared = 0
        var exactMinutes = 0
        var worst = 0.0
        val problems = mutableListOf<String>()
        for (element in cases) {
            val case = element.jsonObject
            val id = case.getValue("id").jsonPrimitive.content
            val p = case.getValue("place").jsonObject
            val place = PrayerPlace.Coordinates(
                latE5 = p.getValue("latE5").jsonPrimitive.int,
                lngE5 = p.getValue("lngE5").jsonPrimitive.int,
                elevationM = p["elevationM"]?.jsonPrimitive?.intOrNull,
                zoneId = p.getValue("zoneId").jsonPrimitive.content,
                zoneSource = ZoneSource.USER,
                countryCode = p["countryCode"]?.jsonPrimitive?.contentOrNull,
            )
            val method = json.decodeFromJsonElement(PrayerMethod.serializer(), case.getValue("method"))
            val window = case["offsetWindow"]?.takeIf { it !is JsonNull }?.jsonObject?.let {
                LocalDate.parse(it.getValue("from").jsonPrimitive.content)..LocalDate.parse(it.getValue("to").jsonPrimitive.content)
            }
            val adjustments = case.getValue("adjustments").jsonObject.map { (k, v) -> TimeEvent.valueOf(k) to v.jsonPrimitive.int }.toMap()
            val e = PrayerEngine.explain(
                place, LocalDate.parse(case.getValue("date").jsonPrimitive.content), method,
                ramadanDay = case.getValue("ramadanDay").jsonPrimitive.boolean,
                zone = EngineTestSupport.zoneOf(case.getValue("zone")),
                baseZone = EngineTestSupport.zoneOf(case.getValue("baseZone")),
                offsetWindow = window,
                adjustments = adjustments,
                terrainClass = case["terrainClass"]?.jsonPrimitive?.contentOrNull?.let(TerrainClass::valueOf),
            )
            val reference = expected.getValue(id)
            val lowest = listOfNotNull(e.nightBefore?.lowestPoint, e.nightAfter?.lowestPoint).map(::seconds)
            val unrounded = e.events.map { it.unrounded?.let(::seconds) }
            for (event in TimeEvent.entries) {
                val mine = e.events[event.ordinal]
                val theirs = reference.getValue(event)
                val t = unrounded[event.ordinal]
                compared++
                if (t == null || theirs.seconds == null) {
                    if ((t == null) != (theirs.seconds == null)) problems += "$id $event: present ${t != null} against ${theirs.seconds != null}"
                    continue
                }
                val difference = abs(t - theirs.seconds)
                worst = maxOf(worst, difference)
                if (difference > 1.0) problems += "$id $event: ${Instant.ofEpochMilli((t * 1000).toLong())} against ${Instant.ofEpochMilli((theirs.seconds * 1000).toLong())} (${"%.3f".format(difference)} s)"
                // Near the guard's comparisons a status may differ: within 1 s of a neighbour or of a lowest point.
                val neighbours = unrounded.filterIndexed { i, _ -> abs(i - event.ordinal) == 1 }.filterNotNull() + lowest
                val nearGuard = neighbours.any { abs(it - t) < 1.0 }
                if (mine.time.status != theirs.status && !nearGuard) problems += "$id $event: ${mine.time.status} against ${theirs.status}"
                // The rule that set an estimated time (README, S9 step 6).
                if (mine.time.status == theirs.status && mine.time.rule?.name != theirs.rule && !nearGuard) {
                    problems += "$id $event: rule ${mine.time.rule} against ${theirs.rule}"
                }
                val minute = checkNotNull(mine.time.at)
                if (minute == theirs.minute) exactMinutes++
                else {
                    val boundary = if (mine.rounding == Rounding.NEAREST) 30.0 else 0.0
                    val offset = ((t % 60) + 60) % 60
                    val nearBoundary = abs(offset - boundary) < 1.0 || abs(offset - boundary) > 59.0
                    val nearModeChange = lowest.any { abs(abs(it - t) - 60.0) < 1.0 }
                    if (!nearBoundary && !nearModeChange) problems += "$id $event: minute $minute against ${theirs.minute}"
                }
            }
        }
        println("Reference cross-check: $compared times in ${cases.size} cases, $exactMinutes on the same minute, worst ${"%.4f".format(worst)} s")
        assertTrue(problems.isEmpty(), problems.take(40).joinToString("\n", prefix = "${problems.size} differences:\n"))
    }

    private fun seconds(instant: Instant) = instant.epochSecond + instant.nano / 1e9
}

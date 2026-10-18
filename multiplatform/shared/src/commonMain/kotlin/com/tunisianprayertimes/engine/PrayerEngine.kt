package com.tunisianprayertimes.engine

import com.tunisianprayertimes.method.AsrRule
import com.tunisianprayertimes.method.FajrRule
import com.tunisianprayertimes.method.IshaFrom
import com.tunisianprayertimes.method.IshaRule
import com.tunisianprayertimes.method.PrayerMethod
import com.tunisianprayertimes.method.Rounding
import com.tunisianprayertimes.method.TerrainClass
import com.tunisianprayertimes.place.LatLngE5
import com.tunisianprayertimes.place.OfficialZone
import com.tunisianprayertimes.place.PrayerPlace
import com.tunisianprayertimes.place.TableRows
import com.tunisianprayertimes.place.ZoneOffsetRule
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.Year
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** A rule moved a time when it changed it by more than this (seconds). */
private const val MOVED = 1e-3

/**
 * The shared prayer engine (plan 6.2): six times as UTC instants for a place outside Tunisia, a date
 * and a method, with each time's status. The order of operations, steps 0–10, is written out in
 * scripts/prayer_formula/README.md, "Shared engine", which the independent Python reference follows.
 * Pure Kotlin and java.time; results never depend on the JVM's default time zone.
 */
object PrayerEngine {
    /** The actual dates computed, the span validated against DE441. */
    val SUPPORTED_YEARS = 2020..2100

    /**
     * Prayer day [date] at [place]. [ramadanDay] is true on a Ramadan day under the method's
     * `ramadanDays` (E8; the repository works it out). [zone] is the official zone the place maps to
     * (null: the place's own coordinates); for a zone with a `baseZoneId`, [baseZone] is that zone and
     * [offsetWindow] the window of Algeria's offset rule. [adjustments] are the user's steppers (step 9).
     * [terrainClass] is E10's class of a place between the limits at its own point (the nearest location's,
     * `ZoneMapping.ownTerrainClass`); without one, the nearer limit decides.
     */
    fun day(
        place: PrayerPlace.Coordinates,
        date: LocalDate,
        method: PrayerMethod,
        ramadanDay: Boolean = false,
        zone: OfficialZone? = null,
        baseZone: OfficialZone? = null,
        offsetWindow: ClosedRange<LocalDate>? = null,
        adjustments: Map<TimeEvent, Int> = emptyMap(),
        terrainClass: TerrainClass? = null,
    ): PrayerDay = explain(place, date, method, ramadanDay, zone, baseZone, offsetWindow, adjustments, terrainClass).day

    /** [day] with the working behind each time. */
    fun explain(
        place: PrayerPlace.Coordinates,
        date: LocalDate,
        method: PrayerMethod,
        ramadanDay: Boolean = false,
        zone: OfficialZone? = null,
        baseZone: OfficialZone? = null,
        offsetWindow: ClosedRange<LocalDate>? = null,
        adjustments: Map<TimeEvent, Int> = emptyMap(),
        terrainClass: TerrainClass? = null,
    ): EngineExplanation {
        // A8 (owner, revised 2026-10-17): a perpetual table shows every year as printed. The date runs as the date of
        // the table's year that shows on it (the zone's own year and rows before the method's year), in the zone's
        // standard time (the table's clock), with every rule but the sun's as it stands on the date (E8 through
        // ramadanDay, E9 through revisedFor); each time keeps its clock minute and moves to the date by whole days.
        val year = zone?.referenceYear ?: method.referenceYear
        val inTable = year?.let { tableDate(date, it, if (zone?.referenceYear != null) zone.tableRows else TableRows.CALENDAR) }
        if (year == null || inTable == null || (date.year == year && inTable == date)) {
            return Computation(place, date, method, ramadanDay, zone, baseZone, offsetWindow, adjustments, terrainClass).run()
        }
        val standard = place.zone.rules.getStandardOffset(date.atTime(12, 0).atZone(place.zone).toInstant())
        val asOf = method.revisedFor(date).copy(revisions = emptyList(), referenceYear = null)
        val explained = Computation(place.copy(zoneId = standard.id), inTable, asOf, ramadanDay, zone, baseZone, offsetWindow,
            adjustments, terrainClass).run()
        return explained.movedTo(date, place, method.revisedFor(date).withAnglesOf(zone), ChronoUnit.DAYS.between(inTable, date) * 86_400L)
    }

    /** A8: the date of a perpetual table's [year] that shows on [date], its [rows] read as [TableRows] says. */
    fun tableDate(date: LocalDate, year: Int, rows: TableRows = TableRows.CALENDAR): LocalDate {
        val feb29 = date.monthValue == 2 && date.dayOfMonth == 29
        return when (rows) {
            TableRows.CALENDAR ->
                if (feb29) LocalDate.of(generateSequence(year) { it + 1 }.first { Year.isLeap(it.toLong()) }, 2, 29)
                else LocalDate.of(year, date.monthValue, date.dayOfMonth)
            TableRows.LEAP_ROWS_FEB28 -> if (feb29) LocalDate.of(year, 2, 28) else LocalDate.of(year, date.monthValue, date.dayOfMonth)
            TableRows.LEAP_ROWS_MAR1 -> if (feb29) LocalDate.of(year, 3, 1) else LocalDate.of(year, date.monthValue, date.dayOfMonth)
            TableRows.COMMON_ROWS -> {
                // The screen's row (the date's day in a leap year, from 0) holds the table year's day of that number
                // without 29 February; the last row repeats 31 December.
                val row = minOf(LocalDate.of(2024, date.monthValue, date.dayOfMonth).dayOfYear - 1, 364)
                val shown = LocalDate.of(year, 1, 1).plusDays(row.toLong())
                if (Year.isLeap(year.toLong()) && row >= 59) shown.plusDays(1) else shown
            }
        }
    }

    /** A table year's explanation moved to [date] at [place] by [seconds] (whole days). */
    private fun EngineExplanation.movedTo(date: LocalDate, place: PrayerPlace.Coordinates, method: PrayerMethod, seconds: Long): EngineExplanation {
        fun Instant.moved(): Instant = plusSeconds(seconds)
        fun EventTime.moved(): EventTime = copy(at = at?.moved())
        fun NightExplanation.moved() = NightExplanation(sunset.moved(), sunrise.moved(), lowestPoint.moved())
        return copy(
            day = PrayerDay(date, place.zone, place.cacheKey, day.events.map { it.moved() }),
            cycleDate = cycleDate.plusDays(seconds / 86_400L),
            civilNoon = civilNoon.moved(),
            method = method,
            nightBefore = nightBefore?.moved(),
            nightAfter = nightAfter?.moved(),
            events = events.map { it.copy(raw = it.raw?.moved(), unrounded = it.unrounded?.moved(), time = it.time.moved()) },
        )
    }
}

/** One time while it is computed: epoch seconds (null when absent), its status and every change to it. */
internal class Slot(val event: TimeEvent) {
    var seconds: Double? = null
    var raw: Double? = null
    var solved: Solved? = null
    var status: EventStatus = EventStatus.COMPUTED
    var rule: RuleName? = null
    var rounding: Rounding = Rounding.NEAREST
    val steps = mutableListOf<EngineStep>()

    fun start(solved: Solved) {
        this.solved = solved
        seconds = solved.seconds
        raw = solved.seconds
    }

    fun startAt(epochSeconds: Double?) {
        seconds = epochSeconds
        raw = epochSeconds
    }

    fun add(step: Int, kind: StepKind, minutes: Double) {
        val t = seconds ?: return
        if (minutes == 0.0) return
        seconds = t + minutes * 60.0
        steps += EngineStep(step, kind, minutes)
    }

    fun moveTo(
        step: Int,
        kind: StepKind,
        to: Double,
        rule: RuleName?,
        bound: EnvelopeBound? = null,
        estimate: Boolean = true,
    ) {
        val from = seconds
        steps += EngineStep(step, kind, if (from == null) 0.0 else (to - from) / 60.0, bound, rule)
        seconds = to
        if (estimate) markEstimated(rule)
    }

    fun markEstimated(rule: RuleName?) {
        if (status != EventStatus.ESTIMATED) {
            status = EventStatus.ESTIMATED
            this.rule = rule
        }
    }
}

/**
 * Steps 1–7 of one date at one site (plan 6.2): the method's own times with the zone's shift
 * [zoneOffsets], before the caps, the validity envelope, the user's steppers and the order guard.
 */
internal class MethodRun(
    val place: PrayerPlace.Coordinates,
    val date: LocalDate,
    val method: PrayerMethod,
    val ramadanDay: Boolean,
    val site: Site,
    val zoneOffsets: Map<TimeEvent, Double>,
    val terrainClass: TerrainClass? = null,
) {
    val sky = Sky(site, date, place.zone, method)
    val season = Season.of(site, place.zone, method).also { it.seed(date, sky) }
    val slots: Map<TimeEvent, Slot> = TimeEvent.entries.associateWith { Slot(it) }

    /** The nights of steps 1–3 on either side of the date: N(D − 1) and N(D). */
    val nightBefore: Night? by lazy { season.nightBefore(date) }
    val nightAfter: Night? by lazy { season.nightAfter(date) }

    /** The sunrise and sunset of steps 1–3: the raw events, or the times a polar rule set (H2's clamp, H8's cycle). */
    var stepsSunrise: Double? = null
        private set
    var stepsSunset: Double? = null
        private set

    operator fun get(event: TimeEvent): Slot = slots.getValue(event)

    fun run(): MethodRun {
        TimeEvent.entries.forEach { this[it].rounding = method.rounding(it) }
        // Step 1: the raw events of the date's solar cycle. A zone that shows the sunrise at the user's own
        // point (`km_fitted`, `yt_traditional`) takes that one row from the place; a Fajr before sunrise still
        // counts from the zone's sunrise (steps 1–3).
        val ownSunrise = if (site.zone?.sunriseAtPlace == true) Sky(Site.of(place, null), date, place.zone, method).sunrise else null
        this[TimeEvent.SUNRISE].start(ownSunrise ?: sky.sunrise)
        this[TimeEvent.DHUHR].start(sky.transit)
        if (method.asr !is AsrRule.BeforeMaghrib) this[TimeEvent.ASR].start(sky.asr)
        this[TimeEvent.MAGHRIB].start(method.maghribAngle?.let(sky::maghribAngle) ?: sky.sunset)
        // Steps 2 and 3: the polar rules.
        val mecca = polarRules()
        // Step 4: all of Maghrib's adjustments.
        val maghrib = this[TimeEvent.MAGHRIB]
        maghrib.add(4, StepKind.METHOD_OFFSET, method.offset(TimeEvent.MAGHRIB).toDouble())
        method.maghribTerrain?.let { rule ->
            val zone = site.zone
            val terrain = if (zone != null) rule.classOf((zone.elevationM ?: 0).toDouble(), zone.terrainClass)
            else rule.classOf((place.elevationM ?: 0).toDouble(), terrainClass)
            maghrib.add(4, StepKind.TERRAIN, rule.minutes(terrain).toDouble())
        }
        if (ramadanDay) maghrib.add(4, StepKind.RAMADAN_OFFSET, (method.ramadanOffsets[TimeEvent.MAGHRIB] ?: 0).toDouble())
        maghrib.add(4, StepKind.ZONE_OFFSET, zoneOffsets[TimeEvent.MAGHRIB] ?: 0.0)
        maghrib.add(4, StepKind.ELEVATION_SHIFT, eveningElevationShift())
        maghrib.add(4, StepKind.SEASONAL_OFFSET, seasonal(TimeEvent.MAGHRIB))
        // Step 5: Fajr and Isha by angle or by interval, and an Asr counted back from Maghrib.
        val fajr = this[TimeEvent.FAJR]
        val isha = this[TimeEvent.ISHA]
        when (val rule = method.fajr) {
            is FajrRule.Angle -> fajr.start(sky.twilight(TimeEvent.FAJR, rule.degrees))
            // Counted from the sunrise of steps 1–3: the raw one, or the one a polar rule set.
            is FajrRule.MinutesBeforeSunrise -> fajr.startAt(season.ends(date).sunrise?.minus(rule.minutes * 60.0))
        }
        when (val rule = method.isha) {
            is IshaRule.Angle -> isha.start(sky.twilight(TimeEvent.ISHA, rule.degrees, at = rule.at))
            is IshaRule.MinutesAfter -> {
                val minutes = if (ramadanDay) rule.ramadan ?: rule.minutes else rule.minutes
                val from = if (rule.from == IshaFrom.MAGHRIB) maghrib.seconds else season.ends(date).sunset
                isha.startAt(from?.plus(minutes * 60.0))
                isha.rounding = method.rounding(TimeEvent.MAGHRIB)
            }
        }
        (method.asr as? AsrRule.BeforeMaghrib)?.let { rule ->
            val asr = this[TimeEvent.ASR]
            asr.startAt(maghrib.seconds?.minus(rule.minutes * 60.0))
            asr.rounding = method.rounding(TimeEvent.MAGHRIB)
        }
        // Step 6: the high-latitude rule, on the night of steps 1–3 (or Mecca's day in irn's polar seasons).
        if (mecca != null) {
            // H8 sets irn's northern Fajr and Isha; a time it leaves at its angle stays computed.
            if (fajr.seconds.let { it == null || abs(mecca.fajr - it) > MOVED }) fajr.moveTo(3, StepKind.POLAR, mecca.fajr, RuleName.IRN_MECCA_CYCLE)
            if (isha.seconds.let { it == null || abs(mecca.isha - it) > MOVED }) isha.moveTo(3, StepKind.POLAR, mecca.isha, RuleName.IRN_MECCA_CYCLE)
        } else {
            // H5's Isha counts from Maghrib before the zone's shift: Isha takes its own in step 7, once.
            highLatitude(maghrib.seconds?.minus((zoneOffsets[TimeEvent.MAGHRIB] ?: 0.0) * 60.0))
        }
        // Step 7: the other times' offsets.
        for (event in listOf(TimeEvent.FAJR, TimeEvent.SUNRISE, TimeEvent.DHUHR, TimeEvent.ASR, TimeEvent.ISHA)) {
            val slot = this[event]
            slot.add(7, StepKind.METHOD_OFFSET, method.offset(event).toDouble())
            if (event == TimeEvent.DHUHR) slot.add(7, StepKind.DHUHR_AFTER_TRANSIT, method.dhuhrAfterTransitMin)
            if (ramadanDay) slot.add(7, StepKind.RAMADAN_OFFSET, (method.ramadanOffsets[event] ?: 0).toDouble())
            slot.add(7, StepKind.ZONE_OFFSET, zoneOffsets[event] ?: 0.0)
            when (event) {
                TimeEvent.FAJR, TimeEvent.SUNRISE -> slot.add(7, StepKind.ELEVATION_SHIFT, -elevationShift())
                TimeEvent.ISHA -> if (method.isha is IshaRule.Angle) slot.add(7, StepKind.ELEVATION_SHIFT, eveningElevationShift())
                else -> Unit
            }
            slot.add(7, StepKind.SEASONAL_OFFSET, seasonal(event))
        }
        return this
    }

    /** E16: the shift of the band the place's own elevation lies in (none without an elevation). */
    private fun elevationShift(): Double {
        val h = place.elevationM ?: return 0.0
        return method.elevationShifts.sortedBy { it.fromM }.lastOrNull { it.fromM <= h }?.minutes ?: 0.0
    }

    /** E16 on Maghrib and Isha: at least the zone's floor (Le Tampon, 7.6). */
    private fun eveningElevationShift(): Double = maxOf(site.zone?.elevationShiftFloor ?: 0.0, elevationShift())

    /** E18: the 36 values a year interpolated linearly at the date's place in the year, and the date's dated offsets. */
    private fun seasonal(event: TimeEvent): Double {
        val dated = method.datedOffsets.filter { it.event == event && it.covers(date) }.sumOf { it.minutes }
        val values = method.seasonalOffsets[event] ?: return dated
        val position = (date.dayOfYear - 1).toDouble() / date.lengthOfYear() * values.size
        val k = floor(position).toInt()
        val w = position - k
        return dated + values[k] * (1 - w) + values[(k + 1) % values.size] * w
    }

    /**
     * Steps 2 and 3 (D6, 7.4): the 5-hour minimum day and night on sunrise and sunset where the method's
     * polar rule acts, the polar Asr, and `irn`'s northern rules (H8), which also set Fajr and Isha (returned).
     */
    private fun polarRules(): IrnNorth.Times? {
        val sunrise = this[TimeEvent.SUNRISE]
        val maghrib = this[TimeEvent.MAGHRIB]
        val asr = this[TimeEvent.ASR]
        val transit = checkNotNull(sky.transit.seconds)
        if (IrnNorth.applies(site, method)) {
            val north = IrnNorth(season).times(date)
            val day = season.day(date)
            if (day.sunriseRaw.let { it == null || abs(north.sunrise - it) > MOVED }) {
                sunrise.moveTo(3, StepKind.POLAR, north.sunrise, RuleName.IRN_MECCA_CYCLE)
            }
            if (method.maghribAngle == null && day.sunsetRaw.let { it == null || abs(north.sunset - it) > MOVED }) {
                maghrib.moveTo(3, StepKind.POLAR, north.sunset, RuleName.IRN_MECCA_CYCLE)
            }
            if (method.asr !is AsrRule.BeforeMaghrib) {
                // IRN's Asr: no earlier than halfway from the transit to sunset, and at least an hour before sunset.
                val shadow = asr.seconds
                val middle = (transit + north.sunset) / 2
                val value = minOf(if (shadow == null) middle else maxOf(shadow, middle), north.sunset - 3600.0)
                if (shadow == null || abs(value - shadow) > MOVED) asr.moveTo(3, StepKind.POLAR, value, RuleName.POLAR_ASR_MIDPOINT)
            }
            stepsSunrise = north.sunrise
            stepsSunset = north.sunset
            return north
        }
        val ends = season.ends(date)
        stepsSunrise = ends.sunrise
        stepsSunset = ends.sunset
        ends.sunriseRule?.let { rule -> ends.sunrise?.let { sunrise.moveTo(2, StepKind.POLAR, it, rule) } }
        if (method.maghribAngle == null) ends.sunsetRule?.let { rule -> ends.sunset?.let { maghrib.moveTo(2, StepKind.POLAR, it, rule) } }
        if (asr.seconds == null && method.asr !is AsrRule.BeforeMaghrib) {
            // Asr when the noon sun stays at or below the horizon: halfway between Dhuhr and Maghrib, or
            // Diyanet's Asr = Dhuhr (the walk then ties it to Dhuhr after the minutes).
            val diyanet = method.polar == PolarFallback.DIYANET_CLAMP || method.minDayNightHours != null
            val sunset = ends.sunset ?: maghrib.seconds
            if (diyanet) asr.moveTo(3, StepKind.POLAR, transit, RuleName.DIYANET_CLAMP)
            else if (sunset != null) asr.moveTo(3, StepKind.POLAR, (transit + sunset) / 2, RuleName.POLAR_ASR_MIDPOINT)
        }
        return null
    }

    /** Step 6: the method's high-latitude rule on the angle times (7.4). */
    private fun highLatitude(maghrib: Double?) {
        val rules = HighLatitudeRules(season, date, place.zone, method, maghrib, site.evening.latitude)
        (method.fajr as? FajrRule.Angle)?.let { angle ->
            val fajr = this[TimeEvent.FAJR]
            val bounded = rules.fajr(fajr.seconds, angle.degrees)
            if (bounded.rule != null && bounded.seconds != null) fajr.moveTo(6, StepKind.HIGH_LATITUDE, bounded.seconds, bounded.rule)
        }
        (method.isha as? IshaRule.Angle)?.let { angle ->
            val isha = this[TimeEvent.ISHA]
            val bounded = rules.isha(isha.seconds, angle.degrees)
            if (bounded.rule != null && bounded.seconds != null) isha.moveTo(6, StepKind.HIGH_LATITUDE, bounded.seconds, bounded.rule)
        }
    }
}

/**
 * S5 at a zone: the zone's own Fajr and Isha angles replace the method's where its rule is an angle (an Isha angle's
 * own point stays); a rule by minutes is kept.
 */
private fun PrayerMethod.withAnglesOf(zone: OfficialZone?): PrayerMethod {
    if (zone == null || (zone.fajrAngleDeg == null && zone.ishaAngleDeg == null)) return this
    val fajr = (fajr as? FajrRule.Angle)?.let { rule -> zone.fajrAngleDeg?.let { rule.copy(degrees = it) } } ?: fajr
    val isha = (isha as? IshaRule.Angle)?.let { rule -> zone.ishaAngleDeg?.let { rule.copy(degrees = it) } } ?: isha
    return copy(fajr = fajr, isha = isha)
}

/** One call of [PrayerEngine.day]: steps 0–10. */
private class Computation(
    val place: PrayerPlace.Coordinates,
    val date: LocalDate,
    val methodAsGiven: PrayerMethod,
    val ramadanDay: Boolean,
    val zone: OfficialZone?,
    val baseZone: OfficialZone?,
    val offsetWindow: ClosedRange<LocalDate>?,
    val adjustments: Map<TimeEvent, Int>,
    val terrainClass: TerrainClass?,
) {
    // Step 0: the method's revision for the actual date (E9), then the zone's own twilight angles (S5).
    val method = methodAsGiven.revisedFor(date).withAnglesOf(zone)

    /** A zone with a base zone runs at the base zone and adds its shift; any other zone runs at its own site. */
    private val shifted = zone != null && zone.baseZoneId != null && baseZone != null
    val site = Site.of(place, if (shifted) baseZone else zone)
    val zoneOffsets: Map<TimeEvent, Double> = zoneOffsets()

    fun run(): EngineExplanation {
        val run = MethodRun(place, date, method, ramadanDay, site, zoneOffsets, terrainClass).run()
        caps(run)
        ValidityEnvelope.apply(run, place, run.nightBefore)
        adjust(run)
        return guard(run)
    }

    // ---- Zone offsets (6.2, 8.8) ----

    private fun zoneOffsets(): Map<TimeEvent, Double> {
        val zone = zone ?: return emptyMap()
        if (!shifted) return zone.offsets
        val base = checkNotNull(baseZone)
        val shift = when (zone.offsetRule) {
            ZoneOffsetRule.PUBLISHED -> zone.offsets
            ZoneOffsetRule.MONTHLY_DIFFERENCE -> DerivedOffsets.cached(key(zone, base, date.withDayOfMonth(1).toString())) {
                monthlyDifference(zone, base)
            }
            ZoneOffsetRule.WINDOW_CEILING -> {
                val window = offsetWindow ?: (date..date)
                DerivedOffsets.cached(key(zone, base, "${window.start}..${window.endInclusive}")) { windowCeiling(zone, base, window) }
            }
            ZoneOffsetRule.TAKVIM -> takvim(zone)
        }
        return TimeEvent.entries.mapNotNull { event ->
            val total = (base.offsets[event] ?: 0.0) + (shift[event] ?: 0.0)
            if (total == 0.0) null else event to total
        }.toMap()
    }

    private fun key(zone: OfficialZone, base: OfficialZone, period: String) =
        "${zone.offsetRule}|${zone.authority}|${zone.id}|${base.id}|${methodAsGiven.id}|${methodAsGiven.hashCode()}|$period"

    /** Steps 1–7 without any zone shift: the method's unrounded times at [site] on [day]. */
    private fun ownTimes(site: Site, day: LocalDate): Map<TimeEvent, Double?> {
        val run = MethodRun(place, day, methodAsGiven.revisedFor(day), false, site, emptyMap()).run()
        return TimeEvent.entries.associateWith { run[it].seconds }
    }

    /** Jordan's further places: the zone's own time minus the base zone's on the 15th, to the half-minute. */
    private fun monthlyDifference(zone: OfficialZone, base: OfficialZone): Map<TimeEvent, Double> {
        val day = date.withDayOfMonth(15)
        val own = ownTimes(Site.of(place, zone), day)
        val reference = ownTimes(Site.of(place, base), day)
        return TimeEvent.entries.mapNotNull { event ->
            val a = own[event]
            val b = reference[event]
            if (a == null || b == null) null else event to floor((a - b) / 60.0 * 2.0 + 0.5) / 2.0
        }.toMap()
    }

    /** Algeria's attached places: the ceiling of the largest difference from the base zone over the window. */
    private fun windowCeiling(zone: OfficialZone, base: OfficialZone, window: ClosedRange<LocalDate>): Map<TimeEvent, Double> {
        val largest = HashMap<TimeEvent, Double>()
        var day = window.start
        while (day <= window.endInclusive) {
            val own = ownTimes(Site.of(place, zone), day)
            val reference = ownTimes(Site.of(place, base), day)
            for (event in TimeEvent.entries) {
                val a = own[event] ?: continue
                val b = reference[event] ?: continue
                val difference = (a - b) / 60.0
                largest[event] = maxOf(largest[event] ?: Double.NEGATIVE_INFINITY, difference)
            }
            day = day.plusDays(1)
        }
        return largest.mapValues { (_, minutes) -> ceil(minutes - 1e-9) }
    }

    /** bosnia_iz's Takvim places: Sarajevo's times plus the Takvim's monthly differences (6.2, 8.8). */
    private fun takvim(zone: OfficialZone): Map<TimeEvent, Double> {
        val means = method.takvimMeans
        if (means.size != 24) return zone.offsets
        // The Takvim's φ and λ are the zone's fitted E6 points; `point` is the town, which the mapping reads (8.8).
        fun parts(point: LatLngE5): Pair<Double, Double> {
            val north = point.latitude >= SARAJEVO_LATITUDE
            val tau = means[(if (north) 0 else 12) + date.monthValue - 1]
            val g = if (north) 1.1 else 0.9
            return 4.0 * (SARAJEVO_LONGITUDE - point.longitude) to tau * g * (point.latitude - SARAJEVO_LATITUDE)
        }
        fun whole(x: Double) = floor(x + 0.5)
        val (lambdaMorning, phiMorning) = parts(zone.morningPoint ?: zone.point)
        val (lambdaEvening, phiEvening) = parts(zone.eveningPoint ?: zone.point)
        val morning = whole(lambdaMorning + phiMorning)
        val evening = whole(lambdaEvening - phiEvening)
        return mapOf(
            TimeEvent.FAJR to morning, TimeEvent.SUNRISE to morning, TimeEvent.DHUHR to whole(lambdaEvening),
            TimeEvent.ASR to evening, TimeEvent.MAGHRIB to evening, TimeEvent.ISHA to evening,
        )
    }

    // ---- Step 8: caps and floors (E19 in ValidityEnvelope) ----

    private fun clock(time: LocalTime, day: LocalDate) = ZonedDateTime.of(day, time, place.zone).toInstant().epochSecond.toDouble()

    private fun caps(run: MethodRun) {
        val isha = run[TimeEvent.ISHA]
        method.ishaLatest?.let { latest ->
            // An evening clock time; one before noon is read on the next day.
            val cap = clock(latest, if (latest < LocalTime.NOON) date.plusDays(1) else date)
            isha.seconds?.let { if (it > cap) isha.moveTo(8, StepKind.ISHA_LATEST, cap, RuleName.ISHA_LATEST) }
        }
        method.ishaEarliest?.let { earliest ->
            val floor = clock(earliest, if (earliest < LocalTime.NOON) date.plusDays(1) else date)
            isha.seconds?.let { if (it < floor) isha.moveTo(8, StepKind.ISHA_EARLIEST, floor, null, estimate = false) }
        }
    }

    // ---- Step 9: the user's steppers, and the window check on the sun's disk ----

    private fun adjust(run: MethodRun) {
        val sky = run.sky
        for (event in TimeEvent.entries) {
            val minutes = adjustments[event] ?: continue
            if (minutes == 0) continue
            val slot = run[event]
            val unadjusted = slot.seconds ?: continue
            val a = minutes * 60.0
            val adjusted = unadjusted + a
            val computedSunrise = sky.sunrise.seconds
            val held = when (event) {
                TimeEvent.DHUHR -> lowerBound(unadjusted, adjusted, a, checkNotNull(sky.transit.seconds) + ValidityEnvelope.ZAWAL_SECONDS)
                TimeEvent.ASR -> sky.firstShadowAsr.seconds?.let { lowerBound(unadjusted, adjusted, a, it) } ?: adjusted
                TimeEvent.MAGHRIB -> sky.sunset.seconds?.let { lowerBound(unadjusted, adjusted, a, it) } ?: adjusted
                TimeEvent.FAJR, TimeEvent.SUNRISE -> computedSunrise?.let { upperBound(unadjusted, adjusted, a, it) } ?: adjusted
                TimeEvent.ISHA -> adjusted
            }
            slot.add(9, StepKind.ADJUSTMENT, minutes.toDouble())
            if (held != adjusted) slot.moveTo(9, StepKind.ADJUSTMENT_HELD, held, RuleName.ADJUSTMENT_HELD)
        }
    }

    /** A lower bound: a time not past it is held there when carried more than 1 min past; one past it keeps only adjustments toward it. */
    private fun lowerBound(unadjusted: Double, adjusted: Double, a: Double, bound: Double): Double =
        if (unadjusted >= bound) (if (adjusted < bound - ValidityEnvelope.ALLOWANCE_SECONDS) bound else adjusted)
        else (if (a > 0) adjusted else unadjusted)

    private fun upperBound(unadjusted: Double, adjusted: Double, a: Double, bound: Double): Double =
        if (unadjusted <= bound) (if (adjusted > bound + ValidityEnvelope.ALLOWANCE_SECONDS) bound else adjusted)
        else (if (a < 0) adjusted else unadjusted)

    // ---- Step 10: the order guard and the rounding ----

    private fun guard(run: MethodRun): EngineExplanation {
        val sky = run.sky
        val before = run.nightBefore
        val after = run.nightAfter
        val inputs = TimeEvent.entries.map { event ->
            val slot = run[event]
            OrderGuard.Input(event, slot.seconds, slot.status, slot.rule, slot.rounding)
        }
        val outputs = OrderGuard.guard(inputs, before?.lowestPoint, after?.lowestPoint)
        val events = outputs.map { out ->
            val at = out.minuteSeconds?.let { Instant.ofEpochSecond(it) }
            if (at == null) EventTime(out.event, null, EventStatus.ABSENT) else EventTime(out.event, at, out.status, out.rule)
        }
        val day = PrayerDay(date, place.zone, place.cacheKey, events)
        val explained = outputs.mapIndexed { i, out ->
            val slot = run[out.event]
            val guardSteps = out.moves.map { move ->
                val kind = when {
                    move.onMinutes -> StepKind.HELD_IN_ORDER_ON_MINUTES
                    move.rule == RuleName.NIGHT_LOWEST_POINT -> StepKind.NIGHT_LOWEST_POINT
                    else -> StepKind.HELD_IN_ORDER
                }
                EngineStep(10, kind, (move.toSeconds - move.fromSeconds) / 60.0, rule = move.rule)
            }
            val solved = slot.solved
            EventExplanation(
                event = out.event,
                latitudeDeg = solved?.point?.latitude,
                longitudeDeg = solved?.point?.longitude,
                altitudeDeg = solved?.altitudeDeg,
                declinationDeg = solved?.sun?.declinationDeg,
                equationOfTimeMin = solved?.sun?.equationOfTimeMin,
                raw = slot.raw?.let(::instantOf),
                steps = slot.steps + guardSteps,
                unrounded = out.seconds?.let(::instantOf),
                rounding = out.rounding,
                time = events[i],
            )
        }
        fun night(n: Night?) = n?.let { NightExplanation(instantOf(it.sunset), instantOf(it.sunrise), instantOf(it.lowestPoint)) }
        return EngineExplanation(
            day = day,
            cycleDate = sky.cycle.cycleDate,
            civilNoon = sky.cycle.civilNoon,
            method = method,
            zoneOffsets = zoneOffsets,
            nightBefore = night(before),
            nightAfter = night(after),
            events = explained,
        )
    }

    companion object {
        const val SARAJEVO_LATITUDE = 43.0 + 52.0 / 60.0
        const val SARAJEVO_LONGITUDE = 18.0 + 26.0 / 60.0
    }
}

/** The derived zone offsets, cached per zone and period: Jordan's month, Algeria's window (8.8). */
internal object DerivedOffsets {
    private val cache = LinkedHashMap<String, Map<TimeEvent, Double>>()

    fun cached(key: String, compute: () -> Map<TimeEvent, Double>): Map<TimeEvent, Double> {
        synchronized(cache) { cache[key] }?.let { return it }
        val value = compute()
        synchronized(cache) {
            if (cache.size >= 512) cache.remove(cache.keys.first())
            cache[key] = value
        }
        return value
    }
}

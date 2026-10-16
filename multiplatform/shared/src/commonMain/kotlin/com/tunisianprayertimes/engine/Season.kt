package com.tunisianprayertimes.engine

import com.tunisianprayertimes.method.AsrRule
import com.tunisianprayertimes.method.FajrRule
import com.tunisianprayertimes.method.IshaRule
import com.tunisianprayertimes.method.PrayerMethod
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/**
 * One date's raw astronomy at a site for a method (epoch seconds; null when absent): Dhuhr's transit,
 * sunrise and sunset (step 1), and the method's own Fajr and Isha angle events (step 5), as the rules
 * that look at other nights need them.
 */
internal class DayAstro(
    val date: LocalDate,
    private val sky: Sky,
    private val method: PrayerMethod,
) {
    val transit: Double = checkNotNull(sky.transit.seconds)
    val sunriseRaw: Double? = sky.sunrise.seconds
    val sunsetRaw: Double? = sky.sunset.seconds

    /** The sun's altitude at the transit, which tells a polar night (below the horizon) from a midnight sun. */
    val noonAltitudeDeg: Double = Solver.altitude(sky.cycle.hours(transit), sky.site.evening.latitude, sky.site.evening.longitude, sky.instantSun, sky.cycle)
    val sunriseHorizonDeg: Double = checkNotNull(sky.sunrise.altitudeDeg)

    /** Only the rules that look at other nights' angles need these: computed on first use. */
    val fajrAngle: Double? by lazy { (method.fajr as? FajrRule.Angle)?.let { sky.twilight(TimeEvent.FAJR, it.degrees).seconds } }
    val ishaAngle: Double? by lazy { (method.isha as? IshaRule.Angle)?.let { sky.twilight(TimeEvent.ISHA, it.degrees, at = it.at).seconds } }
}

/**
 * A date's sunrise and sunset after steps 2 and 3 (plan 6.2, D6): the raw events, or the times a polar
 * rule set, with that rule. The night between them is measured from these.
 */
internal data class DayEnds(val sunrise: Double?, val sunset: Double?, val sunriseRule: RuleName?, val sunsetRule: RuleName?)

/** A night between instants (plan 6.2, "Night"): from a sunset to the next sunrise, with its lowest point. */
internal data class Night(val sunset: Double, val sunrise: Double, val lowestPoint: Double, val endsByRule: Boolean) {
    val seconds: Double get() = sunrise - sunset
}

/**
 * The days around a date at one site, for one method: cached, since the high-latitude rules look at other
 * nights (the ramp's last and next real times, the nearest day, LRE's year) and the polar gate at the year.
 */
internal class Season private constructor(val site: Site, val zone: ZoneId, val method: PrayerMethod) {
    private val days = object : LinkedHashMap<LocalDate, DayAstro>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LocalDate, DayAstro>?) = size > MAX_DAYS
    }
    private val ratios = HashMap<Int, Double?>()

    /** H8 ([IrnNorth]): Mecca's day by date and the northern days by year, kept with the season. */
    internal val meccaOffsets = HashMap<LocalDate, IrnNorth.MeccaOffsets>()
    internal val northDays = HashMap<Int, IrnNorth.Days>()

    fun day(date: LocalDate): DayAstro = synchronized(days) { days[date] } ?: compute(date).also { synchronized(days) { days[date] = it } }

    private fun compute(date: LocalDate): DayAstro = DayAstro(date, Sky(site, date, zone, method), method)

    /** Seeds the cache with the date's own sky, so the date's step 1 and its season share their events. */
    fun seed(date: LocalDate, sky: Sky) {
        synchronized(days) { if (date !in days) days[date] = DayAstro(date, sky, method) }
    }

    /** True when the method's Diyanet clamp applies: no gate, centred on Dhuhr = noon + 5 (7.4). */
    private val diyanet: Boolean get() = method.polar == PolarFallback.DIYANET_CLAMP || method.minDayNightHours != null

    /** Steps 2 and 3 for [date]: the 5-hour minimum day and night where the method's polar rule acts. */
    fun ends(date: LocalDate): DayEnds {
        val d = day(date)
        val clampActs = when {
            method.polar == PolarFallback.IRN_MECCA_CYCLE -> false
            diyanet -> true
            else -> needsClamp(d) && PolarGate.passes(site, method, date.year, zone)
        }
        if (!clampActs) return DayEnds(d.sunriseRaw, d.sunsetRaw, null, null)
        val rule = if (diyanet) RuleName.DIYANET_CLAMP else RuleName.MIN_DAY_NIGHT_5H
        val centre = d.transit + if (diyanet) DIYANET_DHUHR_SECONDS else 0.0
        val polarNight = d.noonAltitudeDeg < d.sunriseHorizonDeg
        val sunrise = d.sunriseRaw?.coerceIn(centre - LONG_HALF, centre - SHORT_HALF)
            ?: if (polarNight) centre - SHORT_HALF else centre - LONG_HALF
        val sunset = d.sunsetRaw?.coerceIn(centre + SHORT_HALF, centre + LONG_HALF)
            ?: if (polarNight) centre + SHORT_HALF else centre + LONG_HALF
        return DayEnds(
            sunrise, sunset,
            if (d.sunriseRaw == null || abs(sunrise - d.sunriseRaw) > MOVED) rule else null,
            if (d.sunsetRaw == null || abs(sunset - d.sunsetRaw) > MOVED) rule else null,
        )
    }

    /** Whether a day or a night around [d] is shorter than 5 h, or an event is missing. */
    private fun needsClamp(d: DayAstro): Boolean {
        val sunrise = d.sunriseRaw ?: return true
        val sunset = d.sunsetRaw ?: return true
        return sunrise > d.transit - SHORT_HALF || sunrise < d.transit - LONG_HALF ||
            sunset < d.transit + SHORT_HALF || sunset > d.transit + LONG_HALF
    }

    /**
     * The night that ends on [date]'s morning, N(date − 1), or begins on its evening, N(date); null when an
     * end is missing. Its lowest point is the sun's lowest altitude between its ends, or its middle where
     * a polar rule set an end (7.4).
     */
    fun nightBefore(date: LocalDate): Night? = night(date.minusDays(1), date)

    fun nightAfter(date: LocalDate): Night? = night(date, date.plusDays(1))

    private fun night(evening: LocalDate, morning: LocalDate): Night? {
        val e = ends(evening)
        val m = ends(morning)
        val sunset = e.sunset ?: return null
        val sunrise = m.sunrise ?: return null
        val byRule = e.sunsetRule != null || m.sunriseRule != null
        val lowest = if (byRule) (sunset + sunrise) / 2 else lowestPoint(evening, sunset, sunrise)
        return Night(sunset, sunrise, lowest, byRule)
    }

    /** The length in seconds of the night beginning on [evening]'s sunset, without its lowest point. */
    fun nightSeconds(evening: LocalDate): Double? {
        val sunset = ends(evening).sunset ?: return null
        val sunrise = ends(evening.plusDays(1)).sunrise ?: return null
        return sunrise - sunset
    }

    private val lowestPoints = HashMap<LocalDate, Double>()

    private fun lowestPoint(evening: LocalDate, sunset: Double, sunrise: Double): Double {
        synchronized(lowestPoints) { lowestPoints[evening] }?.let { return it }
        val sky = Sky(site, evening, zone, method)
        val lowest = Solver.lowestPoint(site.evening.latitude, site.evening.longitude, sunset, sunrise, sky.instantSun, sky.cycle)
        synchronized(lowestPoints) { lowestPoints[evening] = lowest }
        return lowest
    }

    /**
     * Local Relative Estimation's R for [year] (7.4): the mean of (Isha − sunset) / night over the year's
     * stable nights with a real Isha (or, without an Isha by angle, of (sunrise − Fajr) / night); a night is
     * unstable when its minutes differ by more than 10 from a neighbouring night's, or a neighbour has none.
     */
    fun lreRatio(year: Int): Double? {
        synchronized(ratios) { if (year in ratios) return ratios[year] }
        val evening = method.isha is IshaRule.Angle
        val minutes = HashMap<LocalDate, Double?>()
        fun interval(date: LocalDate): Double? = minutes.getOrPut(date) {
            val d = day(date)
            if (evening) d.ishaAngle?.let { i -> ends(date).sunset?.let { (i - it) / 60 } }
            else d.fajrAngle?.let { f -> ends(date).sunrise?.let { (it - f) / 60 } }
        }
        val shares = mutableListOf<Double>()
        var date = LocalDate.of(year, 1, 1)
        while (date.year == year) {
            val here = interval(date)
            if (here != null) {
                val stable = listOf(date.minusDays(1), date.plusDays(1)).all { n -> interval(n)?.let { abs(it - here) <= 10.0 } ?: false }
                val night = if (evening) nightSeconds(date) else nightSeconds(date.minusDays(1))
                if (stable && night != null) shares += here * 60 / night
            }
            date = date.plusDays(1)
        }
        val r = if (shares.isEmpty()) null else shares.average()
        synchronized(ratios) { ratios[year] = r }
        return r
    }

    companion object {
        /** Enough for a year scan and the nights around it. */
        private const val MAX_DAYS = 450
        const val SHORT_HALF = 2.5 * 3600
        const val LONG_HALF = 9.5 * 3600
        const val DIYANET_DHUHR_SECONDS = 5 * 60.0
        private const val MOVED = 1e-3

        private val cache = object : LinkedHashMap<Triple<Site, ZoneId, PrayerMethod>, Season>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Triple<Site, ZoneId, PrayerMethod>, Season>?) = size > 12
        }

        fun of(site: Site, zone: ZoneId, method: PrayerMethod): Season = synchronized(cache) {
            cache.getOrPut(Triple(site, zone, method)) { Season(site, zone, method) }
        }
    }
}

/** D6's gate: whether the place's year has a day without a sunrise, a sunset or a shadow Asr (cached per site and year). */
internal object PolarGate {
    private val cache = HashMap<String, Boolean>()

    fun passes(site: Site, method: PrayerMethod, year: Int, zone: ZoneId): Boolean {
        if (maxOf(abs(site.morning.latitude), abs(site.evening.latitude)) < 60.0) return false
        val key = listOf(site, method.horizon, method.sunModel, method.asrShadow, method.asr, method.asrSun, method.sunEvaluation, year)
            .joinToString("|")
        synchronized(cache) { cache[key] }?.let { return it }
        var date = LocalDate.of(year, 1, 1)
        var passes = false
        while (!passes && date.year == year) {
            val sky = Sky(site, date, zone, method)
            passes = sky.sunrise.seconds == null || sky.sunset.seconds == null ||
                (method.asr !is AsrRule.BeforeMaghrib && sky.asr.seconds == null)
            date = date.plusDays(1)
        }
        synchronized(cache) {
            if (cache.size >= 256) cache.clear()
            cache[key] = passes
        }
        return passes
    }
}

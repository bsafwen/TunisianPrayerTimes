package com.tunisianprayertimes.tv.ui.display

import com.tunisianprayertimes.DayPrayerTimes
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.mosque.AdhkarSlide
import com.tunisianprayertimes.mosque.DayBanner
import com.tunisianprayertimes.mosque.DisplayTexts
import com.tunisianprayertimes.mosque.FastCountdown
import com.tunisianprayertimes.mosque.MosqueAdhkar
import com.tunisianprayertimes.tv.ui.TvStrings
import com.tunisianprayertimes.weather.WeatherNow
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

/** One niche of the arcade. Right to left: Fajr, the sunrise, Dhuhr (Jumu'a on Fridays), Asr, Maghrib, Isha. */
data class ArcadeTile(
    /** Null for the sunrise: it is not a prayer, has no iqamah and is never the next one. */
    val prayer: Prayer?,
    val name: String,
    /** When its adhan is called (or the sun rises). After Isha, Fajr's niche holds tomorrow's. */
    val at: LocalDateTime,
    val iqamah: LocalTime?,
    /** Its adhan is behind us: the niche is dimmed. */
    val passed: Boolean,
    /** The next adhan: the gold niche. */
    val next: Boolean,
    /** A line under the iqamah: «ثم التراويح» under Isha on the nights of Ramadan. */
    val note: String? = null,
) {
    val time: LocalTime get() = at.toLocalTime()
}

/** The countdown beside the clock: [label] over the time left until [target], and [detail] under it. */
data class HeroCountdown(val label: String, val target: LocalDateTime, val detail: String?) {

    /** Whole seconds left, rounded up so that 00:00 comes with [target] itself; never negative. */
    fun secondsLeft(now: LocalDateTime): Long =
        Math.floorDiv(Duration.between(now, target).toMillis() + 999, 1000L).coerceAtLeast(0)

    fun countdownText(now: LocalDateTime): String = TvStrings.countdown(secondsLeft(now))

    /** The last five minutes: «قريبًا» beside the label. It appears once and stays still. */
    fun isSoon(now: LocalDateTime): Boolean = secondsLeft(now) in 1..MainScreenModel.SOON_SECONDS
}

/** Everything the main screen decides, for one minute. */
data class MainScreenState(
    /** Empty while the day's times are unknown. */
    val tiles: List<ArcadeTile>,
    /** Null when there is nothing to count down to: the clock stands alone. */
    val hero: HeroCountdown?,
    val isFriday: Boolean,
    val isRamadan: Boolean,
    /** The header's verse, from the reviewed texts. */
    val verse: DisplayTexts.DisplayText,
    /** A word beside the Hijri date on the days that have one: «يوم عرفة», «عيد مبارك». */
    val dayNote: String?,
) {
    val next: ArcadeTile? get() = tiles.firstOrNull { it.next }
}

/**
 * The decisions of the main screen as plain functions of the clock and the day's data, so they are
 * tested without a screen: which niche is gold, which are behind us, what the hero counts down to.
 */
object MainScreenModel {

    /** «قريبًا» shows in the last five minutes before the hero's moment. */
    const val SOON_SECONDS = 5 * 60L

    /**
     * The screen at [now]. [today] and [tomorrow] are the prayer times of the two civil days;
     * [iqamah] is today's per prayer (with [Prayer.JOMOAA] on Fridays), as the prayer flow resolved
     * it; [tomorrowFajrIqamah] fills Fajr's niche after Isha. [ramadanTomorrow] is whether tomorrow is
     * a day of Ramadan: tonight has tarawih, and tomorrow a fast whose imsak the iftar countdown shows.
     */
    fun at(
        now: LocalDateTime,
        today: DayPrayerTimes?,
        tomorrow: DayPrayerTimes?,
        iqamah: Map<Prayer, LocalTime>,
        tomorrowFajrIqamah: LocalTime?,
        banner: DayBanner?,
        ramadanTomorrow: Boolean,
    ): MainScreenState {
        val isFriday = now.dayOfWeek == DayOfWeek.FRIDAY
        val isRamadan = banner is DayBanner.Ramadan
        val tiles = today?.let { tiles(now, it, tomorrow, iqamah, tomorrowFajrIqamah, isFriday, ramadanTomorrow) }.orEmpty()
        val next = tiles.firstOrNull { it.next }
        val fast = (banner as? DayBanner.Ramadan)?.countdown
        val hero = when {
            fast != null -> fastHero(fast, now, today, tomorrow, iqamah, ramadanTomorrow)
            next?.prayer != null -> HeroCountdown(TvStrings.adhanIn(next.prayer), next.at, next.iqamah?.let { "${TvStrings.IQAMAH} ${TvStrings.hm(it)}" })
            else -> null
        }
        val dayNote = when (banner) {
            DayBanner.Arafah -> TvStrings.ARAFAH
            is DayBanner.Eid -> TvStrings.EID_MUBARAK
            else -> null
        }
        return MainScreenState(tiles, hero, isFriday, isRamadan, DisplayTexts.headerVerse(isRamadan, isFriday, verseTurn(now, today)), dayNote)
    }

    /** The header verse moves on at each of today's adhans (none passed before Fajr, or without times). */
    internal fun verseTurn(now: LocalDateTime, today: DayPrayerTimes?): Long {
        val date = now.toLocalDate()
        val passed = today?.allPrayers()?.count { !date.atTime(it.hour, it.minute).isAfter(now) } ?: 0
        return DisplayTexts.turnAt(date, passed)
    }

    private fun tiles(
        now: LocalDateTime,
        today: DayPrayerTimes,
        tomorrow: DayPrayerTimes?,
        iqamah: Map<Prayer, LocalTime>,
        tomorrowFajrIqamah: LocalTime?,
        isFriday: Boolean,
        ramadanTomorrow: Boolean,
    ): List<ArcadeTile> {
        val date = now.toLocalDate()
        val noon = if (isFriday) Prayer.JOMOAA else Prayer.DHUHR
        val isha = date.atTime(today.isha.hour, today.isha.minute)
        // Once Isha has been called, the next adhan is tomorrow's Fajr: it takes Fajr's niche.
        val tomorrowFajr = tomorrow?.takeIf { !isha.isAfter(now) }?.let { date.plusDays(1).atTime(it.fajr.hour, it.fajr.minute) }
        val slots = listOf(
            Triple(Prayer.FAJR, tomorrowFajr ?: date.atTime(today.fajr.hour, today.fajr.minute), if (tomorrowFajr != null) tomorrowFajrIqamah else iqamah[Prayer.FAJR]),
            Triple(null, date.atTime(today.shurukHour, today.shurukMinute), null),
            Triple(noon, date.atTime(today.dhuhr.hour, today.dhuhr.minute), iqamah[noon]),
            Triple(Prayer.ASR, date.atTime(today.asr.hour, today.asr.minute), iqamah[Prayer.ASR]),
            Triple(Prayer.MAGHRIB, date.atTime(today.maghrib.hour, today.maghrib.minute), iqamah[Prayer.MAGHRIB]),
            Triple(Prayer.ISHA, isha, iqamah[Prayer.ISHA]),
        )
        val nextAt = slots.filter { (prayer, at, _) -> prayer != null && at.isAfter(now) }.minOfOrNull { it.second }
        return slots.map { (prayer, at, iqamahAt) ->
            ArcadeTile(
                prayer = prayer,
                name = prayer?.let(TvStrings::prayerName) ?: TvStrings.SUNRISE,
                at = at,
                iqamah = iqamahAt,
                passed = !at.isAfter(now),
                next = prayer != null && at == nextAt,
                // Tarawih follow Isha on the nights before a fast, the first one included; the eve of Eid has none.
                note = TvStrings.THEN_TARAWIH.takeIf { prayer == Prayer.ISHA && ramadanTomorrow },
            )
        }
    }

    /**
     * Ramadan's hero is the fast. Before iftar: the time left, and tomorrow's imsak under it (the
     * Fajr adhan, as the shared banner defines it). Before imsak: the time left, and the iftar after it.
     */
    private fun fastHero(
        fast: FastCountdown,
        now: LocalDateTime,
        today: DayPrayerTimes?,
        tomorrow: DayPrayerTimes?,
        iqamah: Map<Prayer, LocalTime>,
        ramadanTomorrow: Boolean,
    ): HeroCountdown = when (fast.kind) {
        FastCountdown.Kind.IFTAR -> {
            val imsak = tomorrow?.takeIf { ramadanTomorrow }?.fajr
            val detail = when {
                imsak != null -> "${TvStrings.IMSAK} ${TvStrings.TOMORROW} ${TvStrings.hm(LocalTime.of(imsak.hour, imsak.minute))}"
                // The last fast: no imsak tomorrow, the Maghrib iqamah instead.
                else -> iqamah[Prayer.MAGHRIB]?.let { "${TvStrings.IQAMAH} ${TvStrings.hm(it)}" }
            }
            HeroCountdown(TvStrings.IFTAR_COUNTDOWN, fast.until, detail)
        }
        FastCountdown.Kind.SUHOOR_ENDS -> {
            val fastDay = fast.until.toLocalDate()
            val isTomorrow = fastDay.isAfter(now.toLocalDate())
            val maghrib = (if (isTomorrow) tomorrow else today)?.maghrib
            val detail = maghrib?.let {
                val word = if (isTomorrow) "${TvStrings.IFTAR} ${TvStrings.TOMORROW}" else TvStrings.IFTAR
                "$word ${TvStrings.hm(LocalTime.of(it.hour, it.minute))}"
            }
            HeroCountdown(TvStrings.IMSAK_COUNTDOWN, fast.until, detail)
        }
    }

    /** The next prayer in one line, «العشاء 19:32 · الإقامة 19:40», or null while the times are unknown. */
    fun nextPrayerLine(state: MainScreenState): String? = state.next?.let { tile ->
        val iqamah = tile.iqamah?.let { " · ${TvStrings.IQAMAH} ${TvStrings.hm(it)}" }.orEmpty()
        "${tile.name} ${TvStrings.hm(tile.time)}$iqamah"
    }

    /** [nextPrayerLine] from the day's data, for the foot of the announcements. */
    fun nextPrayerLine(
        now: LocalDateTime,
        today: DayPrayerTimes?,
        tomorrow: DayPrayerTimes?,
        iqamah: Map<Prayer, LocalTime>,
        tomorrowFajrIqamah: LocalTime?,
    ): String? = nextPrayerLine(at(now, today, tomorrow, iqamah, tomorrowFajrIqamah, banner = null, ramadanTomorrow = false))

    /** «المرسى · تونس»: the delegation and its gouvernorat, once when they share a name. */
    fun placeLine(delegation: String, gouvernorat: String?): String =
        listOfNotNull(delegation, gouvernorat).map(String::trim).filter(String::isNotEmpty).distinct().joinToString(" · ")

    /**
     * A written announcement of the mosque among the ticker's adhkar: MosqueAdhkar.tickerWithAnnouncements
     * gives it no catalog entry and the label [TvStrings.ANNOUNCEMENT_LABEL] as its source. Not sacred
     * text, so not set in Amiri.
     */
    fun isAnnouncement(slide: AdhkarSlide): Boolean = slide.entryId == null && slide.reference == TvStrings.ANNOUNCEMENT_LABEL

    /** How long the ticker holds [slide]: its reading time, never less than the ticker's minimum. */
    fun tickerDwellMillis(slide: AdhkarSlide): Long = maxOf(MosqueAdhkar.TICKER_MIN_SLIDE_MILLIS, slide.durationMillis)

    /** «29°», in whole degrees. */
    fun temperatureText(weather: WeatherNow): String = "${Math.round(weather.temperature)}°"
}

/** The banner's title and detail, for the dashboard. */
internal fun dayBannerLines(banner: DayBanner, now: LocalDateTime): Pair<String, String?> = when (banner) {
    is DayBanner.Ramadan -> TvStrings.RAMADAN_BANNER to banner.countdown?.let { countdown ->
        val label = when (countdown.kind) {
            FastCountdown.Kind.SUHOOR_ENDS -> TvStrings.IMSAK_COUNTDOWN
            FastCountdown.Kind.IFTAR -> TvStrings.IFTAR_COUNTDOWN
        }
        "$label ${durationText((Duration.between(now, countdown.until).seconds + 59) / 60)}"
    }
    is DayBanner.Eid -> "${TvStrings.EID_MUBARAK} · ${TvStrings.prayerName(banner.prayer)}" to banner.prayerAt?.let {
        "${TvStrings.EID_PRAYER_AT} ${String.format(Locale.ROOT, "%02d:%02d", it.hour, it.minute)}"
    }
    DayBanner.Arafah -> TvStrings.ARAFAH to null
}

/** Whole minutes left, rounded up, as "2س 5د". */
private fun durationText(minutes: Long): String {
    val h = minutes / 60
    val m = minutes % 60
    return if (h > 0) "${h}س ${m}د" else "${m}د"
}

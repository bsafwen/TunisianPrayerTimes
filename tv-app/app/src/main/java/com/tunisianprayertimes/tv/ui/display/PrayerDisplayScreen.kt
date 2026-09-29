package com.tunisianprayertimes.tv.ui.display

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.DayPrayerTimes
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.mosque.AdhkarSlide
import com.tunisianprayertimes.mosque.DayBanner
import com.tunisianprayertimes.mosque.DisplayTexts
import com.tunisianprayertimes.tv.ui.TvStrings
import com.tunisianprayertimes.tv.ui.common.Digits
import com.tunisianprayertimes.tv.ui.theme.Amiri
import com.tunisianprayertimes.tv.ui.theme.Crescent
import com.tunisianprayertimes.tv.ui.theme.KhatamStar
import com.tunisianprayertimes.tv.ui.theme.Kufi
import com.tunisianprayertimes.tv.ui.theme.LocalDisplayTheme
import com.tunisianprayertimes.tv.ui.theme.Medallion
import com.tunisianprayertimes.tv.ui.theme.Midad
import com.tunisianprayertimes.tv.ui.theme.Sky
import com.tunisianprayertimes.tv.ui.theme.SkyColors
import com.tunisianprayertimes.tv.ui.theme.archTile
import com.tunisianprayertimes.tv.ui.theme.lineBox
import com.tunisianprayertimes.tv.ui.theme.midadStyle
import com.tunisianprayertimes.tv.ui.theme.skyBackground
import com.tunisianprayertimes.weather.OpenMeteo
import com.tunisianprayertimes.weather.WeatherNow
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * The main screen, shown most of the day («أفق», the Main board): the mosque, the header verse and
 * the dates; the clock and the countdown to the next adhan (to iftar or imsak in Ramadan); the
 * prayers as the arcade of Kairouan's courtyard, the next one in gold; and the ticker.
 *
 * It recomposes every second with [now]; everything but the clock's countdown is decided once a
 * minute ([MainScreenModel]) and the parts that did not change are skipped.
 */
@Composable
fun PrayerDisplayScreen(
    /** Today's prayer times; null while they load (or for a day the data lacks): "no data". */
    todayTimes: DayPrayerTimes?,
    /** Tomorrow's: its Fajr fills Fajr's niche after Isha, its Maghrib is the next iftar. */
    tomorrowTimes: DayPrayerTimes?,
    mosqueName: String,
    delegationName: String,
    /** The delegation's gouvernorat, after it: «المرسى · تونس». */
    gouvernoratName: String?,
    /** The trusted time in Tunisia, ticking every second; the display never reads the device clock itself. */
    now: LocalDateTime,
    /** Today's date in the official Tunisian Hijri calendar, «18 ربيع الثاني 1448 هـ». */
    hijriLabel: String,
    /** Today's iqamah per prayer (with Jumu'a on Fridays), as resolved by the shared prayer flow the overlays follow. */
    iqamahTimes: Map<Prayer, LocalTime>,
    /** Tomorrow's Fajr iqamah, for Fajr's niche after Isha. */
    tomorrowFajrIqamah: LocalTime?,
    /** Ramadan's fast countdown, Eid or Arafah; null on ordinary days. */
    banner: DayBanner?,
    /** Whether tomorrow is a day of Ramadan: tarawih tonight, and an imsak to show under the iftar countdown. */
    ramadanTomorrow: Boolean,
    /** The weather at the mosque when the TV is online, it is enabled and recent; null hides it entirely. */
    weather: WeatherNow?,
    /** The ticker's texts, from the reviewed catalog or the mosque's USB file, with its written announcements. */
    ticker: List<AdhkarSlide>,
    /** The sky of the prayer times («أفق»), or null for the plain ground («مداد»). */
    sky: SkyColors?,
    /** The mosque's own images, which replace the sky when there are any. */
    backgroundImages: List<Uri> = emptyList(),
    onSettingsRequested: () -> Unit,
) {
    val minute = now.truncatedTo(ChronoUnit.MINUTES)
    val state = remember(minute, todayTimes, tomorrowTimes, iqamahTimes, tomorrowFajrIqamah, banner, ramadanTomorrow) {
        MainScreenModel.at(minute, todayTimes, tomorrowTimes, iqamahTimes, tomorrowFajrIqamah, banner, ramadanTomorrow)
    }
    val date = minute.toLocalDate()
    val gregorian = remember(date) { TvStrings.gregorianDate(date) }
    val place = remember(delegationName, gouvernoratName) { MainScreenModel.placeLine(delegationName, gouvernoratName) }
    val clock = remember(minute) { TvStrings.hm(minute.toLocalTime()) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.key == Key.Menu && event.type == KeyEventType.KeyUp) {
                    onSettingsRequested(); true
                } else false
            }
            .focusable(),
    ) {
        Backdrop(sky, backgroundImages)
        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp)) {
            Header(
                mosqueName = mosqueName.ifBlank { TvStrings.MOSQUE_DEFAULT },
                place = place,
                verse = state.verse,
                hijriLabel = hijriLabel,
                dayNote = state.dayNote,
                isRamadan = state.isRamadan,
                gregorian = gregorian,
                weather = weather,
            )
            Spacer(Modifier.height(10.dp))
            Hero(clock, state.hero, now)
            Spacer(Modifier.height(25.dp))
            Arcade(state.tiles)
            Spacer(Modifier.height(15.dp))
            AzkarTicker(ticker)
        }
    }
}

/** The sky down to the horizon above the arcade, the mosque's own images in its place, or the plain ground. */
@Composable
private fun Backdrop(sky: SkyColors?, images: List<Uri>) {
    if (images.isEmpty()) {
        Box(Modifier.fillMaxSize().skyBackground(sky, horizon = HORIZON, groundAt = GROUND_AT))
    } else {
        Box(Modifier.fillMaxSize().background(Midad.Ground)) {
            CustomBackground(images, horizon = HORIZON, groundAt = GROUND_AT)
        }
    }
}

// ── Header: the mosque (right), the verse and its medallion (middle), the dates and the weather (left) ──

@Composable
private fun Header(
    mosqueName: String,
    place: String,
    verse: DisplayTexts.DisplayText,
    hijriLabel: String,
    dayNote: String?,
    isRamadan: Boolean,
    gregorian: String,
    weather: WeatherNow?,
) {
    Row(Modifier.fillMaxWidth().height(50.dp), verticalAlignment = Alignment.Top) {
        // The blocks may stand a little taller than the header, as on the board, into the hero's empty top.
        Column(Modifier.wrapContentHeight(Alignment.Top, unbounded = true), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(mosqueName, style = MOSQUE_NAME, maxLines = 1, modifier = Modifier.lineBox(MOSQUE_NAME))
            if (place.isNotEmpty()) Text(place, style = PLACE, maxLines = 1)
        }
        HeaderVerse(verse, Modifier.weight(1f).padding(horizontal = 16.dp).padding(top = 9.dp))
        Column(
            Modifier.wrapContentHeight(Alignment.Top, unbounded = true),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Crescent(if (isRamadan) 18.dp else 17.dp, color = if (isRamadan) Midad.Gold else Midad.Silver)
                Text(hijriLabel, style = HIJRI, maxLines = 1)
                if (dayNote != null) Text("· $dayNote", style = DAY_NOTE, maxLines = 1)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(gregorian, style = GREGORIAN, maxLines = 1)
                // Offline, disabled or stale: the weather is not there at all, never a "--".
                if (weather != null) {
                    Text("·", style = GREGORIAN)
                    weatherIcon(weather.code, weather.isDay)?.let { WeatherGlyph(it, 14.dp) }
                    Digits(MainScreenModel.temperatureText(weather), TEMPERATURE)
                    OpenMeteo.describe(weather.code).takeIf { it.isNotEmpty() }?.let { Text(it, style = GREGORIAN, maxLines = 1) }
                }
            }
            // Open-Meteo's data is CC BY 4.0: credited wherever it is shown.
            if (weather != null) Text(OpenMeteo.ATTRIBUTION, style = CREDIT, maxLines = 1)
        }
    }
}

/**
 * The verse with its silver medallion and reference, centred between the mosque and the dates. On a
 * header too narrow for it (a long mosque name), it is set a little smaller, and left out rather
 * than cut: a verse is never shown in part.
 */
@Composable
private fun HeaderVerse(verse: DisplayTexts.DisplayText, modifier: Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.TopCenter) {
        val measurer = rememberTextMeasurer(cacheSize = 0)
        val width = constraints.maxWidth
        val fixed = with(LocalDensity.current) { (15.dp + 7.dp * 2).roundToPx() }
        val style = remember(verse, width) {
            val reference = measurer.measure(verse.reference, VERSE_REFERENCE, softWrap = false, maxLines = 1).size.width
            (17 downTo 14).map { verseStyle(it) }.firstOrNull { style ->
                measurer.measure(verse.text, style, softWrap = false, maxLines = 1).size.width + reference + fixed <= width
            }
        }
        if (style != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(verse.text, style = style, maxLines = 1, softWrap = false)
                Medallion(15.dp)
                Text(TvStrings.source(verse.reference), style = VERSE_REFERENCE, maxLines = 1, softWrap = false)
            }
        }
    }
}

// ── Hero: the clock (right), the countdown (left), both on the hero's floor ──

@Composable
private fun Hero(clock: String, hero: HeroCountdown?, now: LocalDateTime) {
    Row(
        Modifier.fillMaxWidth().height(180.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Digits(clock, CLOCK, tracking = (-3).dp)
        if (hero != null) Countdown(hero, hero.countdownText(now), hero.isSoon(now))
    }
}

@Composable
private fun Countdown(hero: HeroCountdown, left: String, soon: Boolean) {
    Column(
        Modifier.padding(bottom = 5.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            // Quiet on purpose: it appears five minutes before and stays still, never blinks.
            if (soon) {
                Box(Modifier.border(1.dp, Midad.Gold, RoundedCornerShape(50)).padding(horizontal = 11.dp, vertical = 2.dp)) {
                    Text(TvStrings.SOON, style = SOON_PILL)
                }
            }
            Text(hero.label, style = HERO_LABEL, maxLines = 1)
        }
        Digits(left, COUNTDOWN, tracking = (-1).dp)
        if (hero.detail != null) Text(hero.detail, style = HERO_DETAIL, maxLines = 1)
    }
}

// ── The arcade: one pointed niche per prayer, right to left, the next one in gold ──

@Composable
private fun Arcade(tiles: List<ArcadeTile>) {
    if (tiles.isEmpty()) {
        Box(Modifier.fillMaxWidth().height(ARCADE_HEIGHT), contentAlignment = Alignment.Center) {
            Text(TvStrings.NO_DATA, style = NO_TIMES)
        }
        return
    }
    Row(Modifier.fillMaxWidth().height(ARCADE_HEIGHT), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.forEach { tile -> Niche(tile, Modifier.weight(1f).fillMaxHeight()) }
    }
}

@Composable
private fun Niche(tile: ArcadeTile, modifier: Modifier) {
    val gold = tile.next
    val sunrise = tile.prayer == null
    Column(
        modifier
            .then(if (tile.passed && !gold) Modifier.alpha(PASSED_ALPHA) else Modifier)
            .archTile(if (gold) Midad.Gold else Midad.Surface, if (gold) Midad.GoldLine else Midad.ArchLine)
            .padding(bottom = if (sunrise) 20.dp else if (tile.note != null) 10.dp else 13.dp),
        verticalArrangement = Arrangement.spacedBy(if (tile.note != null) 3.dp else 4.dp, Alignment.Bottom),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (gold) KhatamStar(16.dp, color = Midad.OnGold)
        when {
            sunrise -> {
                Text(tile.name, style = SUNRISE_NAME, maxLines = 1)
                Text(TvStrings.hm(tile.time), style = SUNRISE_TIME, maxLines = 1, modifier = Modifier.lineBox(SUNRISE_TIME))
            }
            gold -> {
                Text(tile.name, style = NEXT_NAME, maxLines = 1)
                Text(TvStrings.hm(tile.time), style = NEXT_TIME, maxLines = 1, modifier = Modifier.lineBox(NEXT_TIME))
                tile.iqamah?.let { Text("${TvStrings.IQAMAH_IN_TILE} ${TvStrings.hm(it)}", style = NEXT_IQAMAH, maxLines = 1) }
                tile.note?.let { Text(it, style = NEXT_NOTE, maxLines = 1) }
            }
            else -> {
                Text(tile.name, style = NAME, maxLines = 1)
                Text(TvStrings.hm(tile.time), style = TIME, maxLines = 1, modifier = Modifier.lineBox(TIME))
                tile.iqamah?.let { Text("${TvStrings.IQAMAH_IN_TILE} ${TvStrings.hm(it)}", style = TILE_IQAMAH, maxLines = 1) }
                tile.note?.let { Text(it, style = NOTE, maxLines = 1) }
            }
        }
    }
}

// Sizes from the Main board, halved (1920 × 1080 px → 960 × 540 dp).

private val HORIZON = 300.dp
private val GROUND_AT = 365.dp
private val ARCADE_HEIGHT = 160.dp
private const val PASSED_ALPHA = 0.5f

private val MOSQUE_NAME = midadStyle(29.sp, FontWeight.SemiBold, family = Kufi, lineHeight = 1.1f)
private val PLACE = midadStyle(15.sp, color = Midad.Muted)
private fun verseStyle(size: Int): TextStyle = midadStyle(size.sp, color = Midad.Verse, family = Amiri, lineHeight = 1.4f)
private val VERSE_REFERENCE = midadStyle(11.sp, color = Midad.Muted)
private val HIJRI = midadStyle(19.sp, FontWeight.Medium)
private val DAY_NOTE = midadStyle(15.sp, color = Midad.Muted)
private val GREGORIAN = midadStyle(14.sp, color = Midad.Muted)
private val TEMPERATURE = midadStyle(14.sp)
private val CREDIT = midadStyle(7.sp, color = Midad.Dim)

private val CLOCK = midadStyle(150.sp, FontWeight.Bold, lineHeight = 0.86f)
private val HERO_LABEL = midadStyle(20.sp, color = Midad.Muted)
private val COUNTDOWN = midadStyle(75.sp, FontWeight.SemiBold, Midad.Gold, lineHeight = 0.95f)
private val HERO_DETAIL = midadStyle(20.sp)
private val SOON_PILL = midadStyle(15.sp, FontWeight.SemiBold, Midad.Gold)

private val NAME = midadStyle(22.sp, FontWeight.Medium)
private val TIME = midadStyle(44.sp, FontWeight.SemiBold, lineHeight = 1f)
private val TILE_IQAMAH = midadStyle(16.sp, color = Midad.Muted)
private val NOTE = midadStyle(14.sp, color = Midad.Muted)
private val NEXT_NAME = midadStyle(22.sp, FontWeight.SemiBold, Midad.OnGold)
private val NEXT_TIME = midadStyle(44.sp, FontWeight.Bold, Midad.OnGold, lineHeight = 1f)
private val NEXT_IQAMAH = midadStyle(16.sp, FontWeight.Medium, Midad.OnGoldMuted)
private val NEXT_NOTE = midadStyle(14.sp, color = Midad.OnGoldMuted)
private val SUNRISE_NAME = midadStyle(22.sp, FontWeight.Medium, Midad.Muted)
private val SUNRISE_TIME = midadStyle(36.sp, FontWeight.Medium, Midad.Muted, lineHeight = 1f)
private val NO_TIMES = midadStyle(20.sp, color = Midad.Muted)

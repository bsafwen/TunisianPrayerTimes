package com.tunisianprayertimes.tv.ui.display

import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.DayPrayerTimes
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerTime
import com.tunisianprayertimes.tv.data.IqamahConfig
import com.tunisianprayertimes.tv.data.IqamahMode
import com.tunisianprayertimes.tv.ui.TvStrings
import com.tunisianprayertimes.tv.ui.theme.*
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalTime
import java.time.chrono.HijrahDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoField
import java.util.Calendar
import java.util.Locale
import androidx.compose.foundation.shape.CircleShape

/**
 * Main 24/7 display screen showing prayer times.
 * Inspired by MAWAQIT & MasjidBox: clean layout, bold times, strong visual hierarchy.
 */
@Composable
fun PrayerDisplayScreen(
    dayPrayerTimes: DayPrayerTimes?,
    shuruk: Pair<Int, Int>?,
    mosqueName: String,
    delegationName: String,
    iqamahConfigs: Map<Prayer, IqamahConfig>,
    jomoaaConfig: IqamahConfig,
    isRamadan: Boolean,
    backgroundImages: List<Uri> = emptyList(),
    onSettingsRequested: () -> Unit,
    onAdhanTriggered: (Prayer) -> Unit,
    onIqamahTriggered: (Prayer) -> Unit
) {
    var currentTime by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            currentTime = LocalTime.now()
            delay(1000L)
        }
    }

    val isFriday = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY
    val nextPrayer = dayPrayerTimes?.nextPrayer(currentTime.hour, currentTime.minute, isFriday)

    // Adhan/iqamah triggers
    LaunchedEffect(currentTime.hour, currentTime.minute) {
        if (dayPrayerTimes == null) return@LaunchedEffect
        val prayers = dayPrayerTimes.scheduledPrayers(isFriday)
        for (pt in prayers) {
            if (pt.hour == currentTime.hour && pt.minute == currentTime.minute && currentTime.second < 2) {
                onAdhanTriggered(pt.prayer)
            }
            val config = if (pt.prayer == Prayer.JOMOAA) jomoaaConfig
                else iqamahConfigs[pt.prayer] ?: continue
            val iqTime = computeIqamahTime(pt, config)
            if (iqTime != null && iqTime.first == currentTime.hour && iqTime.second == currentTime.minute && currentTime.second < 2) {
                onIqamahTriggered(pt.prayer)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.key == Key.Menu && event.type == KeyEventType.KeyUp) {
                    onSettingsRequested(); true
                } else false
            }
            .focusable()
    ) {
        // Background
        if (backgroundImages.isNotEmpty()) {
            CustomBackground(images = backgroundImages)
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            if (isRamadan) listOf(RamadanDeep, RamadanPurple, Color(0xFF1A0F42), RamadanDeep)
                            else listOf(BackgroundDark, Color(0xFF0D1B2E), SurfaceDark, BackgroundDark)
                        )
                    )
            )
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // ── TOP ZONE: Mosque name + Clock + Dates ────────────────────
            TopHeaderSection(
                mosqueName = mosqueName,
                delegationName = delegationName,
                currentTime = currentTime,
                isFriday = isFriday,
                isRamadan = isRamadan
            )

            // ── RAMADAN BANNER (only during Ramadan) ─────────────────────
            if (isRamadan) {
                Spacer(Modifier.height(8.dp))
                RamadanBanner(
                    maghribTime = dayPrayerTimes?.maghrib,
                    fajrTime = dayPrayerTimes?.fajr,
                    currentTime = currentTime
                )
            }

            // Push prayer cards toward bottom
            Spacer(Modifier.weight(1f))

            // ── MIDDLE: Next prayer countdown + sunrise ──────────────────
            NextPrayerCountdownBar(
                shuruk = shuruk,
                nextPrayer = nextPrayer,
                dayPrayerTimes = dayPrayerTimes,
                isFriday = isFriday,
                currentTime = currentTime
            )

            Spacer(Modifier.height(16.dp))

            // ── BOTTOM: 5 prayer cards in a row ──────────────────────────
            if (dayPrayerTimes != null) {
                PrayerCardsRow(
                    dayPrayerTimes = dayPrayerTimes,
                    iqamahConfigs = iqamahConfigs,
                    jomoaaConfig = jomoaaConfig,
                    isFriday = isFriday,
                    nextPrayer = nextPrayer
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(TvStrings.NO_DATA, color = TextMuted, fontSize = 24.sp)
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── BOTTOM TICKER ────────────────────────────────────────────
            AzkarTicker(isRamadan = isRamadan)
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════
//  TOP HEADER — Mosque name (right), big clock (center), dates (left)
// ══════════════════════════════════════════════════════════════════════════
@Composable
private fun TopHeaderSection(
    mosqueName: String,
    delegationName: String,
    currentTime: LocalTime,
    isFriday: Boolean,
    isRamadan: Boolean
) {
    val today = LocalDate.now()
    val hijriDate = HijrahDate.now()
    val hijriDay = hijriDate.get(ChronoField.DAY_OF_MONTH)
    val hijriMonth = hijriDate.get(ChronoField.MONTH_OF_YEAR)
    val hijriYear = hijriDate.get(ChronoField.YEAR_OF_ERA)
    val hijriStr = "$hijriDay ${TvStrings.HIJRI_MONTHS.getOrElse(hijriMonth - 1) { "" }} $hijriYear هـ"
    val gregorianStr = today.format(DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.forLanguageTag("ar")))
    val timeStr = String.format(Locale.US, "%02d:%02d:%02d", currentTime.hour, currentTime.minute, currentTime.second)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceDark.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(16.dp))
            .padding(horizontal = 28.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Right side (RTL): Mosque name
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "🕌",
                    fontSize = 22.sp,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    text = mosqueName.ifBlank { TvStrings.MOSQUE_DEFAULT },
                    color = if (isRamadan) RamadanGold else Gold,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            if (isFriday) {
                Text(
                    text = TvStrings.JOMOAA_REMINDER,
                    color = GoldLight,
                    fontSize = 14.sp
                )
            } else {
                Text(
                    text = delegationName,
                    color = TextMuted,
                    fontSize = 16.sp
                )
            }
        }

        // Center: Large clock in a highlighted box (MAWAQIT-style)
        Box(
            modifier = Modifier
                .background(
                    Brush.verticalGradient(
                        listOf(
                            if (isRamadan) RamadanGold.copy(alpha = 0.15f) else Gold.copy(alpha = 0.12f),
                            if (isRamadan) RamadanGold.copy(alpha = 0.05f) else Gold.copy(alpha = 0.04f)
                        )
                    ),
                    RoundedCornerShape(14.dp)
                )
                .border(1.dp, if (isRamadan) RamadanGold.copy(alpha = 0.3f) else Gold.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
                .padding(horizontal = 32.dp, vertical = 8.dp)
        ) {
            Text(
                text = timeStr,
                color = TextWhite,
                fontSize = 52.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
        }

        // Left side (RTL): Dates
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = hijriStr,
                color = GoldLight,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = gregorianStr,
                color = TextMuted,
                fontSize = 15.sp
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════
//  NEXT PRAYER COUNTDOWN BAR — Shows sunrise + countdown to next prayer
// ══════════════════════════════════════════════════════════════════════════
@Composable
private fun NextPrayerCountdownBar(
    shuruk: Pair<Int, Int>?,
    nextPrayer: Prayer?,
    dayPrayerTimes: DayPrayerTimes?,
    isFriday: Boolean,
    currentTime: LocalTime
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceDark.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Sunrise info
        if (shuruk != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "☀", fontSize = 20.sp)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(text = TvStrings.SUNRISE, color = TextMuted, fontSize = 12.sp)
                    Text(
                        text = String.format(Locale.US, "%02d:%02d", shuruk.first, shuruk.second),
                        color = GoldLight,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // Next prayer countdown
        if (nextPrayer != null && dayPrayerTimes != null) {
            val nextPt = dayPrayerTimes.scheduledPrayers(isFriday).find { it.prayer == nextPrayer }
            if (nextPt != null) {
                val nowMin = currentTime.hour * 60 + currentTime.minute
                val targetMin = nextPt.hour * 60 + nextPt.minute
                val diff = targetMin - nowMin
                val h = diff / 60
                val m = diff % 60
                val countdownStr = if (h > 0) "${h}س ${m}د" else "${m}د"

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${TvStrings.NEXT_PRAYER}: ${TvStrings.prayerName(nextPrayer)}",
                        color = TextWhite,
                        fontSize = 20.sp
                    )
                    Spacer(Modifier.width(16.dp))

                    // Countdown pill (highlighted amber)
                    Box(
                        modifier = Modifier
                            .background(CountdownAmber.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                            .border(1.dp, CountdownAmber.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = countdownStr,
                            color = CountdownAmber,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(Modifier.width(16.dp))
                    val progress = 1f - (diff.toFloat() / 120f).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.width(120.dp).height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = CountdownAmber,
                        trackColor = SurfaceDark.copy(alpha = 0.6f),
                    )
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════
//  PRAYER CARDS ROW — 5 clean cards, next prayer highlighted in gold
// ══════════════════════════════════════════════════════════════════════════
@Composable
private fun PrayerCardsRow(
    dayPrayerTimes: DayPrayerTimes,
    iqamahConfigs: Map<Prayer, IqamahConfig>,
    jomoaaConfig: IqamahConfig,
    isFriday: Boolean,
    nextPrayer: Prayer?
) {
    val prayers = if (isFriday) {
        listOf(
            Triple(TvStrings.FAJR, dayPrayerTimes.fajr, iqamahConfigs[Prayer.FAJR]),
            Triple(TvStrings.JOMOAA, dayPrayerTimes.dhuhr, jomoaaConfig),
            Triple(TvStrings.ASR, dayPrayerTimes.asr, iqamahConfigs[Prayer.ASR]),
            Triple(TvStrings.MAGHRIB, dayPrayerTimes.maghrib, iqamahConfigs[Prayer.MAGHRIB]),
            Triple(TvStrings.ISHA, dayPrayerTimes.isha, iqamahConfigs[Prayer.ISHA])
        )
    } else {
        listOf(
            Triple(TvStrings.FAJR, dayPrayerTimes.fajr, iqamahConfigs[Prayer.FAJR]),
            Triple(TvStrings.DHUHR, dayPrayerTimes.dhuhr, iqamahConfigs[Prayer.DHUHR]),
            Triple(TvStrings.ASR, dayPrayerTimes.asr, iqamahConfigs[Prayer.ASR]),
            Triple(TvStrings.MAGHRIB, dayPrayerTimes.maghrib, iqamahConfigs[Prayer.MAGHRIB]),
            Triple(TvStrings.ISHA, dayPrayerTimes.isha, iqamahConfigs[Prayer.ISHA])
        )
    }

    val prayerEnums = if (isFriday) {
        listOf(Prayer.FAJR, Prayer.JOMOAA, Prayer.ASR, Prayer.MAGHRIB, Prayer.ISHA)
    } else {
        listOf(Prayer.FAJR, Prayer.DHUHR, Prayer.ASR, Prayer.MAGHRIB, Prayer.ISHA)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        prayers.forEachIndexed { index, (name, prayerTime, iqamahConfig) ->
            PrayerCard(
                name = name,
                prayerTime = prayerTime,
                iqamahConfig = iqamahConfig ?: IqamahConfig(),
                isNext = prayerEnums[index] == nextPrayer,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun PrayerCard(
    name: String,
    prayerTime: PrayerTime,
    iqamahConfig: IqamahConfig,
    isNext: Boolean,
    modifier: Modifier = Modifier
) {
    val bgColor by animateColorAsState(
        targetValue = if (isNext) NextPrayerHighlight else SurfaceCard.copy(alpha = 0.85f),
        animationSpec = tween(500),
        label = "cardBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isNext) Gold.copy(alpha = 0.7f) else CardBorder,
        animationSpec = tween(500),
        label = "cardBorder"
    )

    val iqamahTime = computeIqamahTime(prayerTime, iqamahConfig)
    val adhanStr = String.format(Locale.US, "%02d:%02d", prayerTime.hour, prayerTime.minute)
    val iqamahStr = iqamahTime?.let { String.format(Locale.US, "%02d:%02d", it.first, it.second) } ?: "--:--"

    Column(
        modifier = modifier
            .background(bgColor, RoundedCornerShape(16.dp))
            .border(
                width = if (isNext) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(16.dp)
            )
            .padding(vertical = 14.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Prayer name
        Text(
            text = name,
            color = if (isNext) Gold else TextWhite,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(8.dp))

        // Thin decorative line
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(1.dp)
                .background(
                    if (isNext) Gold.copy(alpha = 0.5f) else TextMuted.copy(alpha = 0.2f)
                )
        )

        Spacer(Modifier.height(10.dp))

        // Adhan time — BIG and bold (the main info)
        Text(
            text = adhanStr,
            color = TextWhite,
            fontSize = 36.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )

        Spacer(Modifier.height(6.dp))

        // Iqamah time — smaller, gold-tinted
        Text(
            text = iqamahStr,
            color = if (isNext) Gold else GoldMuted,
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

// ══════════════════════════════════════════════════════════════════════════
//  UTILITY
// ══════════════════════════════════════════════════════════════════════════

fun computeIqamahTime(prayerTime: PrayerTime, config: IqamahConfig): Pair<Int, Int>? {
    return when (config.mode) {
        IqamahMode.DELAY -> {
            val totalMinutes = prayerTime.hour * 60 + prayerTime.minute + config.delayMinutes
            Pair(totalMinutes / 60, totalMinutes % 60)
        }
        IqamahMode.FIXED_TIME -> {
            if (config.fixedHour >= 0 && config.fixedMinute >= 0) {
                Pair(config.fixedHour, config.fixedMinute)
            } else null
        }
    }
}

/**
 * Ramadan banner with iftar/suhoor countdown.
 */
@Composable
private fun RamadanBanner(
    maghribTime: PrayerTime?,
    fajrTime: PrayerTime?,
    currentTime: LocalTime
) {
    val nowMinutes = currentTime.hour * 60 + currentTime.minute

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(RamadanPurple.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
            .border(1.dp, RamadanGold.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
            .padding(horizontal = 24.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = TvStrings.RAMADAN_BANNER,
            color = RamadanGold,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )

        if (maghribTime != null && fajrTime != null) {
            val maghribMinutes = maghribTime.hour * 60 + maghribTime.minute
            val fajrMinutes = fajrTime.hour * 60 + fajrTime.minute

            if (nowMinutes < maghribMinutes) {
                val diff = maghribMinutes - nowMinutes
                val h = diff / 60
                val m = diff % 60
                val countdownStr = if (h > 0) "${h}س ${m}د" else "${m}د"
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = TvStrings.IFTAR_COUNTDOWN, color = RamadanMoon, fontSize = 18.sp)
                    Spacer(Modifier.width(12.dp))
                    Text(text = countdownStr, color = IftarGreen, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                val diff = if (nowMinutes < fajrMinutes) fajrMinutes - nowMinutes
                    else (24 * 60 - nowMinutes) + fajrMinutes
                val h = diff / 60
                val m = diff % 60
                val countdownStr = if (h > 0) "${h}س ${m}د" else "${m}د"
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = TvStrings.SUHOOR_REMINDER, color = RamadanMoon, fontSize = 18.sp)
                    Spacer(Modifier.width(12.dp))
                    Text(text = countdownStr, color = CountdownOrange, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Text(text = "🌙", fontSize = 24.sp)
    }
}

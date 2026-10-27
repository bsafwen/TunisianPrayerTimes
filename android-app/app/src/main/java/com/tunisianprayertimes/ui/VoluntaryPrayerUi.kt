package com.tunisianprayertimes.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.tunisianprayertimes.PrayerTimesRepository
import com.tunisianprayertimes.R
import com.tunisianprayertimes.engine.SolarEvents
import com.tunisianprayertimes.place.PrayerPlace
import com.tunisianprayertimes.platform.PrayerDataLoader
import com.tunisianprayertimes.ui.theme.Gold
import com.tunisianprayertimes.ui.theme.GoldLight
import com.tunisianprayertimes.ui.theme.GreenPrimary
import com.tunisianprayertimes.ui.theme.GreenPrimaryDark
import com.tunisianprayertimes.ui.theme.PrayerNameColor
import com.tunisianprayertimes.ui.theme.TextMuted
import com.tunisianprayertimes.worship.VoluntaryPrayerTimes
import com.tunisianprayertimes.worship.voluntaryPrayerTimes
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private data class VoluntaryPrayerUiState(
    val now: Instant,
    val times: VoluntaryPrayerTimes?,
    val displayZone: ZoneId,
)

/** Reads the same saved place and method as the prayer tab, away from the UI thread. */
@Composable
internal fun VoluntaryPrayerTools(placeRevision: Int) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by produceState<VoluntaryPrayerUiState?>(null, context, lifecycle, placeRevision) {
        value = null
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                val now = Instant.now()
                value = withContext(Dispatchers.IO) {
                    val times = try {
                        val place = PrayerTimesRepository.place(context)
                        val longitude = when (place) {
                            is PrayerPlace.Inm -> PrayerDataLoader.prayerTimes(context).location(place.delegationId)?.longitude
                            is PrayerPlace.Coordinates -> place.point.longitude
                        }
                        voluntaryPrayerTimes(
                            now = now,
                            zone = place.zone,
                            dayFor = { PrayerTimesRepository.prayerDay(context, it, place) },
                            solarNoonFor = { date -> longitude?.let { SolarEvents.transit(date, place.zone, it) } },
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    VoluntaryPrayerUiState(now, times, ZoneId.systemDefault())
                }
                // Resume immediately refreshes clock, place, method and zone; midnight rolls over here too.
                delay(30_000L)
            }
        }
    }
    VoluntaryPrayerCards(
        times = state?.times,
        now = state?.now ?: Instant.now(),
        displayZone = state?.displayZone ?: ZoneId.systemDefault(),
        loading = state == null,
    )
}

/** Stateless content also used to check compact Arabic layouts with a fixed clock. */
@Composable
internal fun VoluntaryPrayerCards(
    times: VoluntaryPrayerTimes?,
    now: Instant,
    displayZone: ZoneId,
    loading: Boolean = false,
) {
    var explanation by rememberSaveable { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.worship_times_clock, displayZone.id),
            color = TextMuted, fontSize = 12.sp, lineHeight = 19.sp,
        )
        WorshipTimeCard(
            title = stringResource(R.string.worship_duha_title),
            icon = R.drawable.ic_adhkar_sun,
            tag = "worship_duha_card",
        ) {
            Text(stringResource(R.string.worship_duha_subtitle), color = TextMuted, fontSize = 13.sp, lineHeight = 21.sp)
            val duha = times?.duha
            if (duha == null) {
                UnavailableWorshipTime(loading, R.string.worship_duha_unavailable)
            } else {
                WorshipTimeRange(
                    startLabel = stringResource(R.string.worship_duha_start), start = duha.start,
                    endLabel = stringResource(R.string.worship_duha_end), end = duha.end, zone = displayZone,
                )
                WorshipTimeStatus(
                    text = when {
                        now < duha.start -> stringResource(R.string.worship_duha_in, worshipCountdown(now, duha.start))
                        now < duha.end -> stringResource(R.string.worship_duha_active, worshipCountdown(now, duha.end))
                        else -> stringResource(R.string.worship_duha_ended)
                    },
                    active = now >= duha.start && now < duha.end,
                )
            }
            WorshipExplanationButton("worship_duha_details") { explanation = "duha" }
        }
        WorshipTimeCard(
            title = stringResource(R.string.worship_night_title),
            icon = R.drawable.ic_prayer_isha_night,
            tag = "worship_night_card",
        ) {
            Text(stringResource(R.string.worship_night_subtitle), color = TextMuted, fontSize = 13.sp, lineHeight = 21.sp)
            val night = times?.night
            if (night == null) {
                UnavailableWorshipTime(loading, R.string.worship_night_unavailable)
            } else {
                Text(
                    stringResource(if (now < night.sunset) R.string.worship_upcoming_night else R.string.worship_current_night),
                    color = GreenPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                )
                WorshipTimeRange(
                    startLabel = stringResource(R.string.worship_isha_time), start = night.isha,
                    endLabel = stringResource(R.string.worship_fajr_time), end = night.fajr, zone = displayZone,
                )
                HorizontalDivider(color = Gold.copy(alpha = .22f))
                Text(stringResource(R.string.worship_last_third), color = PrayerNameColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                WorshipTimeRange(
                    startLabel = stringResource(R.string.worship_third_start), start = night.lastThirdStart,
                    endLabel = stringResource(R.string.worship_fajr_time), end = night.fajr, zone = displayZone,
                )
                WorshipTimeStatus(
                    text = when {
                        now < night.lastThirdPrayerStart -> stringResource(R.string.worship_third_in, worshipCountdown(now, night.lastThirdPrayerStart))
                        now < night.fajr -> stringResource(R.string.worship_third_active, worshipCountdown(now, night.fajr))
                        else -> stringResource(R.string.worship_night_ended)
                    },
                    active = now >= night.lastThirdPrayerStart && now < night.fajr,
                )
                if (night.lastThirdPrayerStart > night.lastThirdStart) {
                    Text(stringResource(R.string.worship_late_isha), color = TextMuted, fontSize = 12.sp, lineHeight = 20.sp)
                }
                if (night.isEstimated) {
                    Text(stringResource(R.string.worship_night_estimated), color = TextMuted, fontSize = 12.sp, lineHeight = 20.sp)
                }
            }
            WorshipExplanationButton("worship_night_details") { explanation = "night" }
        }
    }
    explanation?.let { kind -> WorshipTimeExplanation(duha = kind == "duha", onDismiss = { explanation = null }) }
}

@Composable
private fun WorshipTimeCard(title: String, icon: Int, tag: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag(tag),
        shape = RoundedCornerShape(18.dp), color = Color.White,
        border = BorderStroke(1.dp, Gold.copy(alpha = .25f)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(painterResource(icon), contentDescription = null, tint = GreenPrimary, modifier = Modifier.size(24.dp))
                Text(title, color = PrayerNameColor, fontSize = 19.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).semantics { heading() })
            }
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorshipTimeRange(startLabel: String, start: Instant, endLabel: String, end: Instant, zone: ZoneId) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        WorshipTimeValue(startLabel, start, zone)
        WorshipTimeValue(endLabel, end, zone)
    }
}

@Composable
private fun WorshipTimeValue(label: String, at: Instant, zone: ZoneId) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = TextMuted, fontSize = 12.sp)
        Text(at.atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm", Locale.US)),
            color = GreenPrimaryDark, fontWeight = FontWeight.Bold, fontSize = 24.sp)
        Text(at.atZone(zone).format(DateTimeFormatter.ofPattern("EEE، d MMM", calendarLocale)),
            color = TextMuted, fontSize = 11.sp)
    }
}

@Composable
private fun WorshipTimeStatus(text: String, active: Boolean) {
    Text(
        text, color = if (active) GreenPrimaryDark else TextMuted, fontSize = 13.sp, lineHeight = 21.sp,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.fillMaxWidth()
            .background(if (active) GoldLight.copy(alpha = .45f) else Color(0xFFF7F8F6), RoundedCornerShape(10.dp))
            .padding(10.dp),
    )
}

@Composable
private fun UnavailableWorshipTime(loading: Boolean, message: Int) {
    Text(stringResource(if (loading) R.string.worship_times_loading else message), color = TextMuted, fontSize = 14.sp, lineHeight = 23.sp)
}

@Composable
private fun WorshipExplanationButton(tag: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)) {
        Text(stringResource(R.string.worship_times_details), color = GreenPrimary, fontSize = 13.sp)
    }
}

/** Rounded up so a future boundary never reads as already reached. */
internal fun worshipCountdown(now: Instant, target: Instant): String {
    val remaining = Duration.between(now, target)
    val minutes = if (remaining.isNegative || remaining.isZero) 0 else
        remaining.seconds / 60 + if (remaining.seconds % 60 != 0L || remaining.nano != 0) 1 else 0
    return String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorshipTimeExplanation(duha: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val sourceUrl = if (duha) "https://binbaz.org.sa/fatwas/8356/ما-وقت-كل-من-صلاة-الضحى-وقيام-الليل"
        else "https://binbaz.org.sa/fatwas/17595/كيفية-تقسيم-الليل-لمعرفة-الثلث-الاخير"
    fun openSource(url: String) {
        runCatching { uriHandler.openUri(url) }.onFailure {
            Toast.makeText(context, context.getString(R.string.worship_source_failed), Toast.LENGTH_SHORT).show()
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Color.White) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)
                .testTag("worship_time_explanation"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(if (duha) R.string.worship_duha_title else R.string.worship_night_title),
                color = PrayerNameColor, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
            Text(stringResource(if (duha) R.string.worship_duha_explanation else R.string.worship_night_explanation),
                color = PrayerNameColor, fontSize = 15.sp, lineHeight = 27.sp)
            Text(stringResource(if (duha) R.string.worship_duha_calculation else R.string.worship_night_calculation),
                color = TextMuted, fontSize = 14.sp, lineHeight = 25.sp)
            TextButton(onClick = { openSource(sourceUrl) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(if (duha) R.string.worship_source_prayer_times else R.string.worship_source_thirds), color = GreenPrimary)
            }
            if (!duha) TextButton(
                onClick = { openSource("https://binbaz.org.sa/fatwas/8356/ما-وقت-كل-من-صلاة-الضحى-وقيام-الليل") },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.worship_source_prayer_times), color = GreenPrimary)
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("worship_time_close")) {
                Text(stringResource(R.string.worship_times_close), color = GreenPrimary)
            }
        }
    }
}

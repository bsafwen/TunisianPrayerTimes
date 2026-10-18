package com.tunisianprayertimes.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.MethodPresentation
import com.tunisianprayertimes.OfficialZoneAssets
import com.tunisianprayertimes.PlaceCatalogue
import com.tunisianprayertimes.PrayerTimesRepository
import com.tunisianprayertimes.engine.EventStatus
import com.tunisianprayertimes.engine.HorizonModel
import com.tunisianprayertimes.engine.SunModel
import com.tunisianprayertimes.engine.SunEvaluation
import com.tunisianprayertimes.engine.TimeEvent
import com.tunisianprayertimes.method.AsrRule
import com.tunisianprayertimes.method.AsrSun
import com.tunisianprayertimes.method.FajrRule
import com.tunisianprayertimes.method.FitStats
import com.tunisianprayertimes.method.IshaRule
import com.tunisianprayertimes.method.PrayerMethod
import com.tunisianprayertimes.method.PrayerMethodChoice
import com.tunisianprayertimes.method.Rounding
import com.tunisianprayertimes.place.PrayerPlace
import com.tunisianprayertimes.ui.theme.GreenPrimary
import com.tunisianprayertimes.ui.theme.GreenPrimaryDark
import com.tunisianprayertimes.ui.theme.TextDark
import com.tunisianprayertimes.ui.theme.TextMuted
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val LRI = "⁦"
private const val PDI = "⁩"

/**
 * «طريقتك» (plan 6.11) for a place abroad: a parameter table generated from the method (nothing hard-coded), the
 * fit record of the mapped zone or the profile's aggregate, today's times with their markers, and, where short
 * nights matter, the year strip of nights the angle is never reached with the latitude it stops at.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MethodExplainerSheet(place: PrayerPlace.Coordinates, choice: PrayerMethodChoice, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val placeTimes = remember { PrayerTimesRepository.placeTimesFor(context) }
    val method = remember(choice) { placeTimes.method(place, choice) }
    val today = LocalDate.now(place.zone)
    val mapped = remember(choice) { runCatching { placeTimes.mapping(place, choice, today.year) }.getOrNull() }
    val index = remember { OfficialZoneAssets.index(context) }
    val profileFit = method.fit ?: index.forMethod(method.id, place.countryCode)?.fit
    val fit: FitStats? = mapped?.zone?.fit ?: profileFit
    val admin1Name = remember(place) { PlaceCatalogue.get(context).admin1Name(place.countryCode, place.admin1) }
    val chip = MethodPresentation.chip(method, place, mapped, profileFit, admin1Name)
    val day = remember(choice, today) { runCatching { placeTimes.day(place, choice, today) }.getOrNull() }
    // Diyanet's takdir, the first short-night rule, starts at 44.375° N (README H6).
    val showNights = abs(place.point.latitude) >= 44.375
    val estimatedNights by produceState<List<Boolean>?>(null, choice, today.year, showNights) {
        if (!showNights) return@produceState
        value = withContext(Dispatchers.Default) {
            val start = LocalDate.of(today.year, 1, 1)
            (0 until start.lengthOfYear()).map { offset ->
                val d = runCatching { placeTimes.day(place, choice, start.plusDays(offset.toLong())) }.getOrNull()
                d != null && (d[TimeEvent.FAJR].status != EventStatus.COMPUTED || d[TimeEvent.ISHA].status != EventStatus.COMPUTED)
            }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.92f).navigationBarsPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp).testTag("method_explainer"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("طريقتك", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = GreenPrimaryDark)
            Text(MethodPresentation.name(method.id), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextDark)
            Text(chip.labelAr, fontSize = 12.sp, color = GreenPrimary)
            mapped?.let { Text(MethodPresentation.zoneLine(method.id, it, place), fontSize = 13.sp, color = TextMuted) }
            HorizontalDivider()
            methodRows(method).forEach { (label, value) -> ParameterRow(label, value) }
            HorizontalDivider()
            Text("أوقات اليوم", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextDark)
            val zone = ZoneId.systemDefault()
            TimeEvent.entries.forEach { event ->
                val time = day?.get(event)
                val clock = time?.at?.atZone(zone)?.let { LRI + String.format(Locale.US, "%02d:%02d", it.hour, it.minute) + PDI } ?: "—"
                val marker = if (time?.status == EventStatus.ESTIMATED) "  «تقديري»" else ""
                ParameterRow(eventName(event), clock + marker)
            }
            fit?.let { record ->
                HorizontalDivider()
                Text("مطابقة الجدول الرسمي", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextDark)
                Text("على أيام لم تُستعمل في ضبط الطريقة:", fontSize = 12.sp, color = TextMuted)
                TimeEvent.entries.forEach { event ->
                    record.exact[event]?.let { share ->
                        ParameterRow(eventName(event), LRI + String.format(Locale.US, "%.1f %%", share * 100) + PDI + " بالدقيقة")
                    }
                }
                record.heldOut.forEach { sample ->
                    Text(
                        "${heldOutSourceLabel(sample.source)}: " + LRI + "${sample.from} → ${sample.to}" + PDI + "، " + LRI + "${sample.days}" + PDI + " يومًا",
                        fontSize = 12.sp, color = TextMuted,
                    )
                }
            }
            if (showNights) {
                HorizontalDivider()
                Text("الليالي القصيرة", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextDark)
                estimatedNights?.let { nights -> TwilightYearStrip(nights) }
                angles(method).forEach { angle ->
                    val latitude = 66.56 - angle
                    Text(
                        "لا تبلغ الشمس " + LRI + degrees(angle) + PDI + " تحت الأفق في أقصر الليالي ابتداءً من خط العرض " +
                            LRI + String.format(Locale.US, "%.2f°", latitude) + PDI + ".",
                        fontSize = 13.sp, color = TextDark, lineHeight = 19.sp,
                    )
                }
            }
            HorizontalDivider()
            ParameterRow("المصدر", method.evidence.source)
        }
    }
}

@Composable
private fun ParameterRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, fontSize = 13.sp, color = TextMuted, modifier = Modifier.width(110.dp))
        Text(value, fontSize = 13.sp, color = TextDark, modifier = Modifier.weight(1f))
    }
}

/** 365 cells, one a night: marked where Fajr or Isha is not the angle's own time (6.11). */
@Composable
private fun TwilightYearStrip(nights: List<Boolean>) {
    val marked = GreenPrimary
    val plain = TextMuted.copy(alpha = 0.25f)
    Canvas(Modifier.fillMaxWidth().height(18.dp).testTag("twilight_year_strip")) {
        if (nights.isEmpty()) return@Canvas
        val cell = size.width / nights.size
        nights.forEachIndexed { i, estimated ->
            drawRect(if (estimated) marked else plain, topLeft = Offset(i * cell, 0f), size = Size(cell.coerceAtLeast(1f), size.height))
        }
    }
    Text("الأيام الملوّنة: ليالٍ تُقدَّر فيها أوقات الفجر أو العشاء بقاعدة الليالي القصيرة.", fontSize = 11.sp, color = TextMuted)
}

private fun degrees(value: Double): String =
    if (value % 1.0 == 0.0) "${value.toInt()}°" else value.toBigDecimal().stripTrailingZeros().toPlainString() + "°"

/** A held-out body's source in words where it is not the authority's own table (scripts/prayer_formula/fits/mosque_years.py). */
private fun heldOutSourceLabel(source: String): String = when (source) {
    "mosque_follower" -> "تقويم مسجد يتّبع الجدول الرسمي"
    else -> source
}

private fun angles(method: PrayerMethod): List<Double> = listOfNotNull(
    (method.fajr as? FajrRule.Angle)?.degrees,
    (method.isha as? IshaRule.Angle)?.degrees,
).distinct()

private fun roundingName(rounding: Rounding): String = when (rounding) {
    Rounding.NEAREST -> "أقرب دقيقة"
    Rounding.UP -> "إلى الدقيقة التالية"
    Rounding.DOWN -> "إلى الدقيقة السابقة"
}

private fun horizonName(model: HorizonModel): String = when (model) {
    HorizonModel.Standard -> "القياسي " + LRI + "−0.8333°" + PDI
    is HorizonModel.Fixed -> LRI + String.format(Locale.US, "%.4f°", model.altitudeDeg).replace("-", "−") + PDI
    HorizonModel.Pressure -> "حسب ارتفاع المكان والضغط"
    is HorizonModel.Refraction -> "انكسار الضوء وانخفاض الأفق بارتفاع المكان"
    is HorizonModel.PerZone -> "أفق كل منطقة من جداولها"
    HorizonModel.StandardWithDip -> "القياسي مع انخفاض الأفق بارتفاع المكان"
    is HorizonModel.SqrtHeight -> "حسب ارتفاع المكان"
}

/** The parameter table of «طريقتك», generated from the method (6.11). */
private fun methodRows(method: PrayerMethod): List<Pair<String, String>> = buildList {
    add("الفجر" to when (val f = method.fajr) {
        is FajrRule.Angle -> LRI + degrees(f.degrees) + PDI + " تحت الأفق"
        is FajrRule.MinutesBeforeSunrise -> "قبل الشروق بـ" + LRI + f.minutes + PDI + " دقيقة"
    })
    add("العشاء" to when (val i = method.isha) {
        is IshaRule.Angle -> LRI + degrees(i.degrees) + PDI + " تحت الأفق" + (i.atNameAr?.let { " في $it" } ?: "")
        is IshaRule.MinutesAfter -> "بعد المغرب بـ" + LRI + i.minutes + PDI + " دقيقة" +
            (i.ramadan?.let { "، وفي رمضان " + LRI + it + PDI } ?: "")
    })
    add("العصر" to buildString {
        append(if (method.asrShadow == 2) "الحنفية (المثلان)" else "الجمهور (المثل)")
        if (method.asrSun == AsrSun.APPARENT) append("، بالشمس الظاهرة")
        when (val asr = method.asr) {
            is AsrRule.BeforeMaghrib -> { clear(); append("قبل المغرب بـ" + LRI + asr.minutes + PDI + " دقيقة") }
            AsrRule.SignedShadow -> append("، بالظل الموجّه")
            else -> Unit
        }
    })
    val offsets = TimeEvent.entries.mapNotNull { e -> method.offset(e).takeIf { it != 0 }?.let { eventName(e) + " " + MethodPresentation.signedMinutes(it) } }
    if (offsets.isNotEmpty()) add("الدقائق الثابتة" to offsets.joinToString("، "))
    val roundings = TimeEvent.entries.groupBy { method.rounding(it) }
    add("التقريب" to if (roundings.size == 1) roundingName(roundings.keys.first())
        else roundings.entries.joinToString("؛ ") { (r, events) -> roundingName(r) + ": " + events.joinToString("، ", transform = ::eventName) })
    add("الأفق" to if (method.horizon.sunrise == method.horizon.sunset) horizonName(method.horizon.sunrise)
        else "الشروق: " + horizonName(method.horizon.sunrise) + "؛ الغروب: " + horizonName(method.horizon.sunset))
    add("حساب الشمس" to when (method.sunModel) {
        SunModel.MEEUS -> "معادلات ميوس"
        SunModel.VSOP87 -> "نظرية VSOP87 الدقيقة"
        SunModel.TEXTBOOK -> "المعادلات المختصرة لجدول الهيئة"
        SunModel.ALMANAC -> "معادلات التقويم الفلكي المختصرة"
    } + when {
        method.sunEvaluation.values.any { it == SunEvaluation.PRAYTIMES } -> "، مرة لكل وقت في ساعة ثابتة"
        method.sunEvaluation.values.any { it != SunEvaluation.EVENT_INSTANT && it != SunEvaluation.SHADOW_AT_EVENT } -> "، مرة في اليوم لبعض الأوقات"
        else -> ""
    })
    add("الليالي القصيرة" to highLatitudeName(method.highLatitude))
}

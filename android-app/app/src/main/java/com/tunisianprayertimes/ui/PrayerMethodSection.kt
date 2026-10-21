package com.tunisianprayertimes.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.MethodChip
import com.tunisianprayertimes.MethodPresentation
import com.tunisianprayertimes.PrayerTimesRepository
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.ScheduleRefreshCoordinator
import com.tunisianprayertimes.adhkar.DhikrReminderScheduler
import com.tunisianprayertimes.engine.CustomMethodRanges
import com.tunisianprayertimes.engine.HighLatitudeRule
import com.tunisianprayertimes.engine.TimeEvent
import com.tunisianprayertimes.method.FajrRule
import com.tunisianprayertimes.method.IshaRule
import com.tunisianprayertimes.method.PrayerMethod
import com.tunisianprayertimes.method.PrayerMethodChoice
import com.tunisianprayertimes.method.PrayerMethods
import com.tunisianprayertimes.method.ZonePick
import com.tunisianprayertimes.place.PlaceTimes
import com.tunisianprayertimes.place.PrayerPlace
import com.tunisianprayertimes.ui.theme.GreenPrimary
import com.tunisianprayertimes.ui.theme.GreenPrimaryDark
import com.tunisianprayertimes.ui.theme.TextDark
import com.tunisianprayertimes.ui.theme.TextMuted
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * The prayer-tab settings of plan 6.11 for a place abroad: «طريقة الحساب», the zone line with «حسب موقعي بالضبط»,
 * «وقت العصر», «الليالي القصيرة» where a rule can act, and «تعديل الأوقات بالدقائق». A Tunisian place shows none of
 * them (D7). The sources are in «طريقتك»: the method's own, and the place names' credit (owner, 2026-10-19).
 */
@Composable
internal fun PrayerMethodSection(onTimesChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    val place = remember(revision) { PrayerTimesRepository.place(context) }
    var sheet by remember { mutableStateOf<String?>(null) }

    fun changed() {
        revision++
        onTimesChanged()
        scope.launch {
            ScheduleRefreshCoordinator.syncAll(context)
            // A new method moves the times: the saved adhkar reminders are checked again over the year (6.9).
            withContext(Dispatchers.Default) { DhikrReminderScheduler.recheckSavedReminders(context) }
        }
    }

    val abroad = place as? PrayerPlace.Coordinates
    if (abroad != null) {
        val choice = remember(revision) { PrayerTimesRepository.choice(context, abroad) ?: PrayerMethodChoice(PrayerMethods.MWL.id) }
        val placeTimes = remember { PrayerTimesRepository.placeTimesFor(context) }
        val method = remember(choice) { placeTimes.method(abroad, choice) }
        val year = LocalDate.now(abroad.zone).year
        val mapped = remember(choice, abroad) { runCatching { placeTimes.mapping(abroad, choice.copy(zonePick = choice.zonePick.takeUnless { it == ZonePick.Exact }), year) }.getOrNull() }
        fun save(next: PrayerMethodChoice) {
            PrefsManager.setMethodChoice(context, abroad, next)
            changed()
        }

        SettingsRow(
            title = "طريقة الحساب",
            subtitle = MethodPresentation.summary(method),
            onClick = { sheet = "methods" },
        )
        HorizontalDivider(color = PrayerSilencePalette.SoftBorder)
        // «طريقتك» (6.11): the method's parameters, its record against the official table, today's times.
        SettingsRow(title = "طريقتك", subtitle = "المعايير، ومطابقة الجدول الرسمي، وأوقات اليوم", onClick = { sheet = "explainer" })
        if (mapped != null) {
            HorizontalDivider(color = PrayerSilencePalette.SoftBorder)
            ZoneLineRow(
                text = MethodPresentation.zoneLine(method.id, mapped, abroad),
                exact = choice.zonePick == ZonePick.Exact,
                onExactChange = { exact -> save(choice.copy(zonePick = if (exact) ZonePick.Exact else null)) },
            )
        }
        HorizontalDivider(color = PrayerSilencePalette.SoftBorder)
        AsrRow(factor = choice.asrShadow ?: method.asrShadow, onFactor = { f -> save(choice.copy(asrShadow = f)) })
        if (kotlin.math.abs(abroad.point.latitude) >= HIGH_LATITUDE_FROM) {
            HorizontalDivider(color = PrayerSilencePalette.SoftBorder)
            SettingsRow(
                title = "الليالي القصيرة",
                subtitle = highLatitudeName(choice.highLatitude ?: method.highLatitude),
                onClick = { sheet = "nights" },
            )
        }
        HorizontalDivider(color = PrayerSilencePalette.SoftBorder)
        SettingsRow(
            title = "تعديل الأوقات بالدقائق",
            subtitle = adjustmentsSummary(choice.adjustments),
            onClick = { sheet = "adjust" },
        )
        HorizontalDivider(color = PrayerSilencePalette.SoftBorder)

        when (sheet) {
            "methods" -> PrayerMethodSheet(
                place = abroad,
                current = choice,
                placeTimes = placeTimes,
                onPick = { id ->
                    sheet = null
                    if (id == PrayerMethod.CUSTOM_ID) sheet = "custom"
                    else save(PrayerMethodChoice(id, asrShadow = choice.asrShadow, adjustments = choice.adjustments, chooserAnswered = true, methodPicked = true))
                },
                onDismiss = { sheet = null },
            )
            "custom" -> CustomMethodSheet(
                base = method,
                mainlandFrance = abroad.isMainlandFrance,
                onSave = { custom ->
                    sheet = null
                    save(PrayerMethodChoice(PrayerMethod.CUSTOM_ID, adjustments = choice.adjustments, custom = custom, chooserAnswered = true, methodPicked = true))
                },
                onDismiss = { sheet = null },
            )
            "adjust" -> AdjustmentsSheet(
                adjustments = choice.adjustments,
                onChange = { event, minutes -> save(choice.withAdjustment(event, minutes)) },
                onDismiss = { sheet = null },
            )
            "explainer" -> MethodExplainerSheet(place = abroad, choice = choice, onDismiss = { sheet = null })
            "nights" -> HighLatitudeSheet(
                current = choice.highLatitude ?: method.highLatitude,
                onPick = { rule -> sheet = null; save(choice.copy(highLatitude = rule)) },
                onDismiss = { sheet = null },
            )
        }
    }
}

/** Where a method's own short-night rule can act: Diyanet's takdir, the first, starts at 44.375° N (README H6). */
private const val HIGH_LATITUDE_FROM = 44.375

@Composable
private fun SettingsRow(title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextDark)
            if (!subtitle.isNullOrBlank()) Text(subtitle, fontSize = 12.sp, color = PrayerSilencePalette.SecondaryText)
        }
    }
}

@Composable
private fun ZoneLineRow(text: String, exact: Boolean, onExactChange: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextDark)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("حسب موقعي بالضبط", fontSize = 13.sp, color = PrayerSilencePalette.SecondaryText, modifier = Modifier.weight(1f))
            Switch(checked = exact, onCheckedChange = onExactChange)
        }
    }
}

@Composable
private fun AsrRow(factor: Int, onFactor: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("وقت العصر", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextDark)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = factor == 1, onClick = { onFactor(1) }, label = { Text("الجمهور") })
            FilterChip(selected = factor == 2, onClick = { onFactor(2) }, label = { Text("الحنفية") })
        }
    }
}

private fun adjustmentsSummary(adjustments: Map<TimeEvent, Int>): String =
    if (adjustments.isEmpty()) "بلا تعديل"
    else TimeEvent.entries.mapNotNull { e -> adjustments[e]?.let { eventName(e) + " " + MethodPresentation.signedMinutes(it) } }.joinToString("، ")

internal fun eventName(event: TimeEvent): String = when (event) {
    TimeEvent.FAJR -> "الفجر"
    TimeEvent.SUNRISE -> "الشروق"
    TimeEvent.DHUHR -> "الظهر"
    TimeEvent.ASR -> "العصر"
    TimeEvent.MAGHRIB -> "المغرب"
    TimeEvent.ISHA -> "العشاء"
}

internal fun highLatitudeName(rule: HighLatitudeRule): String = when (rule) {
    is HighLatitudeRule.AngleBased -> "نسبة الزاوية من الليل كل ليلة"
    is HighLatitudeRule.AngleBasedAbsentNights -> "نسبة الزاوية من الليل حين تغيب الزاوية"
    is HighLatitudeRule.LocalRelativeEstimation -> "التقدير النسبي المحلي"
    is HighLatitudeRule.OneSeventh -> "سُبع الليل"
    is HighLatitudeRule.MiddleOfNight -> "منتصف الليل"
    is HighLatitudeRule.NearestDay -> "أقرب يوم تظهر فيه العلامة"
    is HighLatitudeRule.ClockCaps -> "حدود بالساعة"
    is HighLatitudeRule.MoonsightingSeasonal -> "منحنى لجنة رؤية الهلال"
    else -> "قاعدة الطريقة"
}

/** The every-night rules move real times (6.11): offered last, with that warning. */
private val CHOOSABLE_RULES: List<Pair<HighLatitudeRule, Boolean>> = listOf(
    HighLatitudeRule.AngleBasedAbsentNights to false,
    HighLatitudeRule.LocalRelativeEstimation to false,
    HighLatitudeRule.OneSeventh to false,
    HighLatitudeRule.MiddleOfNight() to false,
    HighLatitudeRule.NearestDay() to false,
    HighLatitudeRule.AngleBased to true,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HighLatitudeSheet(current: HighLatitudeRule, onPick: (HighLatitudeRule) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("الليالي القصيرة", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextDark)
            CHOOSABLE_RULES.forEach { (rule, everyNight) ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(rule) }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(highLatitudeName(rule), fontSize = 15.sp, color = TextDark,
                            fontWeight = if (rule::class == current::class) FontWeight.Bold else FontWeight.Normal)
                        if (everyNight) Text("تُطبَّق كل ليلة، وقد تغيّر أوقاتًا حقيقية.", fontSize = 12.sp, color = TextMuted)
                    }
                }
                HorizontalDivider(color = PrayerSilencePalette.SoftBorder)
            }
        }
    }
}

/**
 * `PrayerMethodSheet` (6.11): «الشائعة في بلدك» (the country's list, 6.4), «طرق أخرى», and «مخصّصة»; each entry with
 * its parameters, its chip and today's Fajr, Maghrib and Isha at the place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrayerMethodSheet(
    place: PrayerPlace.Coordinates,
    current: PrayerMethodChoice,
    placeTimes: PlaceTimes,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val defaults = remember(place) { placeTimes.defaults(place) }
    val country = defaults.entries.mapNotNull(PrayerMethods::byId)
    // Only the methods followed at the place (owner, 2026-10-18): never Slovenia's in Riyadh or New York.
    val others = remember(place) { placeTimes.offeredMethods(place).filter { m -> country.none { it.id == m.id } } }
    val today = LocalDate.now(place.zone)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(0.9f).navigationBarsPadding()) {
            item { SectionTitle("الشائعة في بلدك") }
            items(country, key = { "c:" + it.id }) { m ->
                MethodEntry(place, m, today, current.methodId == m.id, placeTimes,
                    inferred = defaults.inferred && m.id == defaults.fallback, onPick = onPick)
            }
            item { SectionTitle("طرق أخرى") }
            items(others, key = { "o:" + it.id }) { m ->
                MethodEntry(place, m, today, current.methodId == m.id, placeTimes, inferred = false, onPick = onPick)
            }
            item { SectionTitle("مخصّصة") }
            item { CustomMethodEntry(place, current, placeTimes, today, onPick) }
        }
    }
}

/**
 * «مخصّصة» (6.11): the bounded editor's entry under its section title, laid out as the other entries; the user's own
 * method, its parameters and today's times when it is the current one.
 */
@Composable
private fun CustomMethodEntry(place: PrayerPlace.Coordinates, current: PrayerMethodChoice, placeTimes: PlaceTimes, today: LocalDate, onPick: (String) -> Unit) {
    val custom = current.custom?.takeIf { current.methodId == PrayerMethod.CUSTOM_ID }
    val day = remember(custom, today) { custom?.let { runCatching { placeTimes.day(place, current, today) }.getOrNull() } }
    Column(Modifier.fillMaxWidth().clickable { onPick(PrayerMethod.CUSTOM_ID) }.padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(
            "تعديل الزوايا والدقائق", fontSize = 15.sp, color = if (custom != null) GreenPrimaryDark else TextDark,
            fontWeight = if (custom != null) FontWeight.Bold else FontWeight.Normal,
        )
        if (custom != null) MethodDetails(custom, day)
        else Text("ضمن الأقوال المعتبرة", fontSize = 12.sp, color = PrayerSilencePalette.SecondaryText)
    }
}

/**
 * An entry's details: the method's parameters, then today's Fajr, Maghrib and Isha in the phone's clock (owner,
 * 2026-10-19: all three). Two blocks on one line where they fit, the times under the parameters where they do not;
 * each label stays with its time.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MethodDetails(method: PrayerMethod, day: com.tunisianprayertimes.engine.PrayerDay?) {
    val zone = ZoneId.systemDefault()
    fun clock(event: TimeEvent) = day?.get(event)?.at?.atZone(zone)?.let { String.format(Locale.US, "⁦%02d:%02d⁩", it.hour, it.minute) } ?: "—"
    val times = listOf("الفجر" to TimeEvent.FAJR, "المغرب" to TimeEvent.MAGHRIB, "العشاء" to TimeEvent.ISHA)
        .joinToString(" · ") { (label, event) -> label + " " + clock(event) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(MethodPresentation.parameters(method), fontSize = 12.sp, color = PrayerSilencePalette.SecondaryText)
        Text(times, fontSize = 12.sp, color = PrayerSilencePalette.SecondaryText)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = GreenPrimary, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp))
}

@Composable
internal fun MethodEntry(
    place: PrayerPlace.Coordinates,
    method: PrayerMethod,
    today: LocalDate,
    selected: Boolean,
    placeTimes: PlaceTimes,
    inferred: Boolean,
    onPick: (String) -> Unit,
) {
    val choice = PrayerMethodChoice(method.id)
    val day = remember(method.id, today) { runCatching { placeTimes.day(place, choice, today) }.getOrNull() }
    val mapped = remember(method.id) { runCatching { placeTimes.mapping(place, choice, today.year) }.getOrNull() }
    val chip = MethodPresentation.chip(method, place, mapped)
    Column(
        Modifier.fillMaxWidth().clickable { onPick(method.id) }.padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                MethodPresentation.name(method.id), fontSize = 15.sp, color = if (selected) GreenPrimaryDark else TextDark,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f),
            )
            Text(chip.labelAr, fontSize = 11.sp, color = if (chip == MethodChip.OFFICIAL) GreenPrimary else TextMuted)
        }
        MethodDetails(method, day)
        if (inferred) Text("مقترحة بالقياس على البلد المجاور، ولم نجد بعدُ جدولًا محليًا.", fontSize = 11.sp, color = TextMuted)
    }
    HorizontalDivider(color = PrayerSilencePalette.SoftBorder, modifier = Modifier.padding(horizontal = 20.dp))
}

/** «تعديل الأوقات بالدقائق»: six steppers, −30 to +30 (6.11). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdjustmentsSheet(adjustments: Map<TimeEvent, Int>, onChange: (TimeEvent, Int) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("تعديل الأوقات بالدقائق", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextDark)
            TimeEvent.entries.forEach { event ->
                val value = adjustments[event] ?: 0
                Stepper(eventName(event), value, PrayerMethodChoice.ADJUSTMENT_RANGE, { onChange(event, it) }, signed = true)
            }
        }
    }
}

@Composable
private fun Stepper(label: String, value: Int, range: IntRange, onValue: (Int) -> Unit, signed: Boolean = false, unit: String = "د") {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 15.sp, color = TextDark, modifier = Modifier.weight(1f))
        TextButton(onClick = { if (value - 1 in range) onValue(value - 1) }, enabled = value - 1 in range) { Text("−") }
        Text(
            (if (signed) MethodPresentation.signedMinutes(value) else "⁦$value⁩") + " " + unit,
            fontSize = 15.sp, color = TextDark, modifier = Modifier.width(72.dp),
        )
        TextButton(onClick = { if (value + 1 in range) onValue(value + 1) }, enabled = value + 1 in range) { Text("+") }
    }
}

/**
 * «مخصّصة»: the bounded editor of 6.11. Only matters of ijtihad, each inside the positions recognised bodies hold;
 * the astronomy stays the engine's. It starts from the current method; E19 checks the result wherever it is used.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomMethodSheet(base: PrayerMethod, mainlandFrance: Boolean, onSave: (PrayerMethod) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val fajrAngles = if (mainlandFrance) CustomMethodRanges.FRANCE_FAJR_ANGLES else CustomMethodRanges.FAJR_ANGLES
    fun clampAngle(x: Double, r: ClosedFloatingPointRange<Double>) = (Math.round(x * 2) / 2.0).coerceIn(r.start, r.endInclusive)
    var fajrByMinutes by remember { mutableStateOf(base.fajr is FajrRule.MinutesBeforeSunrise) }
    var fajrAngle by remember { mutableStateOf(clampAngle((base.fajr as? FajrRule.Angle)?.degrees ?: 18.0, fajrAngles)) }
    var fajrMinutes by remember { mutableIntStateOf(((base.fajr as? FajrRule.MinutesBeforeSunrise)?.minutes ?: 90).coerceIn(CustomMethodRanges.FAJR_MINUTES)) }
    var ishaByMinutes by remember { mutableStateOf(base.isha is IshaRule.MinutesAfter) }
    var ishaAngle by remember { mutableStateOf(clampAngle((base.isha as? IshaRule.Angle)?.degrees ?: 17.0, CustomMethodRanges.ISHA_ANGLES)) }
    var ishaMinutes by remember { mutableIntStateOf(((base.isha as? IshaRule.MinutesAfter)?.minutes ?: 90).coerceIn(CustomMethodRanges.ISHA_MINUTES)) }
    var ishaEarliest by remember { mutableStateOf(base.ishaEarliest) }
    var asr by remember { mutableIntStateOf(base.asrShadow) }
    var dhuhr by remember { mutableIntStateOf((base.offset(TimeEvent.DHUHR)).coerceIn(CustomMethodRanges.DHUHR_MINUTES)) }
    var maghrib by remember { mutableIntStateOf((base.offset(TimeEvent.MAGHRIB)).coerceIn(CustomMethodRanges.MAGHRIB_MINUTES)) }
    var sunrise by remember { mutableIntStateOf((base.offset(TimeEvent.SUNRISE)).coerceIn(CustomMethodRanges.SUNRISE_MINUTES)) }

    fun build(): PrayerMethod = PrayerMethod(
        id = PrayerMethod.CUSTOM_ID,
        fajr = if (fajrByMinutes) FajrRule.MinutesBeforeSunrise(fajrMinutes) else FajrRule.Angle(fajrAngle),
        isha = if (ishaByMinutes) IshaRule.MinutesAfter(ishaMinutes) else IshaRule.Angle(ishaAngle),
        ishaEarliest = ishaEarliest,
        asrShadow = asr,
        offsets = mapOf(TimeEvent.DHUHR to dhuhr, TimeEvent.MAGHRIB to maghrib, TimeEvent.SUNRISE to sunrise).filterValues { it != 0 },
        highLatitude = base.highLatitude,
        polar = base.polar,
        evidence = com.tunisianprayertimes.method.Evidence(com.tunisianprayertimes.method.EvidenceKind.PUBLISHED, "custom"),
    )

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.9f).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("طريقة مخصّصة", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextDark)
            Text("الفجر", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextDark, modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !fajrByMinutes, onClick = { fajrByMinutes = false }, label = { Text("بالزاوية") })
                FilterChip(selected = fajrByMinutes, onClick = { fajrByMinutes = true }, label = { Text("قبل الشروق بدقائق") })
            }
            if (fajrByMinutes) Stepper("الدقائق", fajrMinutes, CustomMethodRanges.FAJR_MINUTES, { fajrMinutes = it })
            else AngleStepper(fajrAngle, fajrAngles) { fajrAngle = it }
            Text("12° مسلمو فرنسا · 15° الجمعية الإسلامية لأمريكا الشمالية · 18° رابطة العالم الإسلامي ورئاسة الشؤون الدينية والمعهد الوطني للرصد الجوي · 19.5° مصر", fontSize = 11.sp, color = TextMuted)

            Text("العشاء", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextDark, modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !ishaByMinutes, onClick = { ishaByMinutes = false }, label = { Text("بالزاوية") })
                FilterChip(selected = ishaByMinutes, onClick = { ishaByMinutes = true }, label = { Text("بعد المغرب بدقائق") })
            }
            if (ishaByMinutes) Stepper("الدقائق", ishaMinutes, CustomMethodRanges.ISHA_MINUTES, { ishaMinutes = it })
            else AngleStepper(ishaAngle, CustomMethodRanges.ISHA_ANGLES) { ishaAngle = it }
            Text("الشفق الأحمر من 12° إلى 17°، والشفق الأبيض 18° (قول الحنفية والمعهد الوطني للرصد الجوي)", fontSize = 11.sp, color = TextMuted)
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("العشاء ليس قبل", fontSize = 15.sp, color = TextDark, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    val t = ishaEarliest ?: LocalTime.of(19, 0)
                    TimePickerDialog(context, { _, h, m -> ishaEarliest = LocalTime.of(h, m) }, t.hour, t.minute, true).show()
                }) { Text(ishaEarliest?.let { String.format(Locale.US, "⁦%02d:%02d⁩", it.hour, it.minute) } ?: "بلا") }
                if (ishaEarliest != null) TextButton(onClick = { ishaEarliest = null }) { Text("إلغاء") }
            }

            Text("العصر", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextDark, modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = asr == 1, onClick = { asr = 1 }, label = { Text("الجمهور (المثل)") })
                FilterChip(selected = asr == 2, onClick = { asr = 2 }, label = { Text("الحنفية (المثلان)") })
            }
            Stepper("الظهر بعد الزوال", dhuhr, CustomMethodRanges.DHUHR_MINUTES, { dhuhr = it })
            Stepper("المغرب بعد الغروب", maghrib, CustomMethodRanges.MAGHRIB_MINUTES, { maghrib = it })
            Stepper("الشروق", sunrise, CustomMethodRanges.SUNRISE_MINUTES, { sunrise = it }, signed = true)

            val problems = CustomMethodRanges.problems(build(), mainlandFrance)
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("إلغاء") }
                TextButton(onClick = { onSave(build()) }, enabled = problems.isEmpty()) { Text("حفظ") }
            }
        }
    }
}

@Composable
private fun AngleStepper(value: Double, range: ClosedFloatingPointRange<Double>, onValue: (Double) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("الزاوية", fontSize = 15.sp, color = TextDark, modifier = Modifier.weight(1f))
        TextButton(onClick = { onValue((value - 0.5).coerceAtLeast(range.start)) }, enabled = value - 0.5 >= range.start) { Text("−") }
        Text(String.format(Locale.US, "⁦%s°⁩", value.toBigDecimal().stripTrailingZeros().toPlainString()), fontSize = 15.sp, color = TextDark, modifier = Modifier.width(72.dp))
        TextButton(onClick = { onValue((value + 0.5).coerceAtMost(range.endInclusive)) }, enabled = value + 0.5 <= range.endInclusive) { Text("+") }
    }
}

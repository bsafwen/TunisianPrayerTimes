package com.tunisianprayertimes.ui

import android.app.NotificationManager
import android.app.TimePickerDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.R
import com.tunisianprayertimes.adhkar.*
import java.time.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable internal fun DhikrReminderEditor(activity: AppCompatActivity, initial: DhikrReminder,
    onDismiss: () -> Unit, onSave: (DhikrReminder, (String) -> Unit) -> Unit) {
    val p = LocalAdhkarPalette.current
    var selectedId by rememberSaveable(initial.id) { mutableStateOf(initial.dhikrId) }
    var collectionName by rememberSaveable(initial.id) { mutableStateOf(initial.collection?.name) }
    var target by rememberSaveable(initial.id) { mutableStateOf(initial.targetCount.toString()) }
    var daysText by rememberSaveable(initial.id) { mutableStateOf(initial.daysOfWeek.sorted().joinToString(",")) }
    val days = daysText.split(',').mapNotNull(String::toIntOrNull).toSet()
    var startKind by rememberSaveable(initial.id) { mutableStateOf(initial.start.kind.name) }
    var startMinute by rememberSaveable(initial.id) { mutableIntStateOf(initial.start.minuteOfDay) }
    var startOffset by rememberSaveable(initial.id) { mutableStateOf(initial.start.offsetMinutes.toString()) }
    var endKind by rememberSaveable(initial.id) { mutableStateOf(initial.end.kind.name) }
    var endMinute by rememberSaveable(initial.id) { mutableIntStateOf(initial.end.minuteOfDay) }
    var endOffset by rememberSaveable(initial.id) { mutableStateOf(initial.end.offsetMinutes.toString()) }
    var nextDay by rememberSaveable(initial.id) { mutableStateOf(initial.endNextDay ?: run {
        val window = DhikrReminderScheduler.currentOrNextWindow(activity, initial)
        window != null && Instant.ofEpochMilli(window.endMillis).atZone(ZoneId.systemDefault()).toLocalDate() >
            Instant.ofEpochMilli(window.startMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    }) }
    var cadence by rememberSaveable(initial.id) { mutableStateOf(initial.cadence.name) }
    var interval by rememberSaveable(initial.id) { mutableStateOf(initial.intervalMinutes.coerceAtLeast(MIN_DHIKR_INTERVAL_MINUTES).toString()) }
    var enabled by rememberSaveable(initial.id) { mutableStateOf(initial.enabled) }
    var vibrate by rememberSaveable(initial.id) { mutableStateOf(initial.vibrate) }
    var selectingDhikr by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(false) }
    var showErrors by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var timeDialog by remember { mutableStateOf(false) }
    var cadenceDialog by remember { mutableStateOf(false) }
    fun integer(value: String): Int? = value.trim().map { if (it.isDigit()) Character.digit(it, 10).digitToChar() else it }.joinToString("").toIntOrNull()
    val collection = collectionName?.let { runCatching { DhikrCategory.valueOf(it) }.getOrNull() }
    val showTarget = collection == null
    val knownEntries = remember(activity) { DhikrRepository(activity).state.value.allEntries }
    val selectedEntry = knownEntries.firstOrNull { it.id == selectedId } ?: knownEntries.first()
    val start = DhikrTime(DhikrTimeKind.valueOf(startKind), startMinute, if (startKind == "FIXED") 0 else integer(startOffset) ?: 9999)
    val end = DhikrTime(DhikrTimeKind.valueOf(endKind), endMinute, if (endKind == "FIXED") 0 else integer(endOffset) ?: 9999)
    val edited = initial.copy(dhikrId = selectedId, collection = collection,
        targetCount = if (collection != null) 1 else integer(target) ?: 0,
        daysOfWeek = days, start = start, end = end, endNextDay = nextDay,
        cadence = DhikrCadence.valueOf(cadence), intervalMinutes = integer(interval) ?: 0,
        enabled = enabled, vibrate = vibrate)
    val problem = remember(edited) { DhikrReminderScheduler.validate(activity, edited) }
    LaunchedEffect(edited) { saveError = null }
    val window = remember(edited) { if (problem == null) DhikrReminderScheduler.currentOrNextWindow(activity, edited) else null }
    val large = LocalConfiguration.current.screenHeightDp < 640 || LocalDensity.current.fontScale > 1.3f
    val isExisting = remember(initial.id) { DhikrRepository(activity).state.value.reminders.any { it.id == initial.id } }
    fun applyTemplate(value: DhikrReminder, clearCollection: Boolean) {
        selectedId = value.dhikrId
        collectionName = if (clearCollection) null else value.collection?.name
        target = value.targetCount.toString()
        daysText = value.daysOfWeek.sorted().joinToString(",")
        startKind = value.start.kind.name; startMinute = value.start.minuteOfDay; startOffset = value.start.offsetMinutes.toString()
        endKind = value.end.kind.name; endMinute = value.end.minuteOfDay; endOffset = value.end.offsetMinutes.toString()
        nextDay = value.endNextDay ?: false
        cadence = value.cadence.name; interval = value.intervalMinutes.coerceAtLeast(MIN_DHIKR_INTERVAL_MINUTES).toString()
    }
    ModalBottomSheet(onDismissRequest = { if (!saving) onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = p.background, modifier = Modifier.testTag("adhkar_reminder_editor")) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(if (large) 1f else .85f).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("إعداد تذكير للأذكار", Modifier.weight(1f), textAlign = TextAlign.Center,
                    fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AdhkarHeading)
                IconButton(onClick = { if (!saving) onDismiss() }) { DhikrIcon(R.drawable.ic_adhkar_back, "رجوع") }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Box {
                    AdhkarCard(Modifier.fillMaxWidth(), onClick = { selectingDhikr = true }) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(54.dp).clip(RoundedCornerShape(16.dp)).background(AdhkarSoftGreen), contentAlignment = Alignment.Center) {
                                DhikrIcon(when {
                                    selectedEntry.id == DhikrCatalog.SALAWAT_ID -> R.drawable.ic_adhkar_mosque
                                    selectedEntry.custom -> R.drawable.ic_adhkar_leaf
                                    else -> categoryIcon(selectedEntry.categories.first())
                                }, modifier = Modifier.size(28.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(selectedEntry.title, color = AdhkarHeading, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(selectedEntry.text, color = p.muted, fontSize = 12.sp, lineHeight = 20.sp,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            DhikrIcon(R.drawable.ic_adhkar_next, tint = p.muted, modifier = Modifier.size(18.dp))
                        }
                    }
                    DropdownMenu(selectingDhikr, onDismissRequest = { selectingDhikr = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                        knownEntries.forEach { entry ->
                            DropdownMenuItem(text = { Text(entry.title) }, onClick = {
                                selectedId = entry.id
                                collectionName = null
                                selectingDhikr = false
                            })
                        }
                    }
                }
                Text("إعدادات التكرار", color = AdhkarHeading, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                AdhkarCard(Modifier.fillMaxWidth()) {
                    Column {
                        if (showTarget) {
                            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("عدد المرات المستهدف", color = p.muted, fontSize = 13.sp)
                                Spacer(Modifier.height(10.dp))
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    StepButton(R.drawable.ic_add, "زيادة العدد") {
                                        target = ((integer(target) ?: 0) + 1).coerceAtMost(100000).toString()
                                    }
                                    OutlinedTextField(
                                        value = target, onValueChange = { if (it.length <= 7) target = it },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center,
                                            fontWeight = FontWeight.Bold, fontSize = 18.sp, color = AdhkarHeading),
                                        shape = RoundedCornerShape(14.dp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedContainerColor = AdhkarSurface, unfocusedContainerColor = AdhkarSurface,
                                            focusedBorderColor = p.primary.copy(alpha = .6f), unfocusedBorderColor = AdhkarBorder),
                                        modifier = Modifier.weight(1f).testTag("adhkar_target_input"),
                                    )
                                    StepButton(R.drawable.ic_remove, "تقليل العدد") {
                                        target = ((integer(target) ?: 1) - 1).coerceAtLeast(1).toString()
                                    }
                                }
                            }
                            HorizontalDivider(color = AdhkarBorder)
                        }
                        SettingRow(R.drawable.ic_adhkar_clock, "الفترة الزمنية",
                            "من " + dhikrTimeLabel(start) + " إلى " + dhikrTimeLabel(end)) { timeDialog = true }
                        HorizontalDivider(color = AdhkarBorder)
                        SettingRow(R.drawable.ic_adhkar_bell, "تذكير كل", cadenceLabel(cadence, interval)) { cadenceDialog = true }
                    }
                }
                Text("خيارات التذكير", color = AdhkarHeading, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                AdhkarCard(Modifier.fillMaxWidth()) {
                    Column {
                        ToggleRow("إشعارات التذكير", "تلقي إشعار في وقت التذكير", enabled) { enabled = it }
                        HorizontalDivider(color = AdhkarBorder)
                        ToggleRow("الاهتزاز", "اهتزاز عند ظهور الإشعار", vibrate) { vibrate = it }
                    }
                }
                Text("قوالب سريعة", color = AdhkarHeading, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TemplateCard("الجمعة", DhikrCatalog.find(DhikrCatalog.SALAWAT_ID)?.title.orEmpty(), null,
                        selected = collection == null && selectedId == DhikrCatalog.SALAWAT_ID && days == setOf(5) && integer(target) == 100,
                        modifier = Modifier.weight(1f)) { applyTemplate(fridayDhikrPreset(), clearCollection = true) }
                    TemplateCard("100 مرة", "يوميًا", null,
                        selected = collection == null && integer(target) == 100 && days.size == 7,
                        modifier = Modifier.weight(1f)) {
                            applyTemplate(tahlilDhikrPreset().copy(dhikrId = selectedId, targetCount = 100), clearCollection = true)
                        }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TemplateCard("أذكار الصباح", "مجموعة كاملة", R.drawable.ic_adhkar_sun,
                        selected = collection == DhikrCategory.MORNING,
                        modifier = Modifier.weight(1f)) { applyTemplate(morningCollectionPreset(), clearCollection = false) }
                    TemplateCard("أذكار المساء", "مجموعة كاملة", R.drawable.ic_adhkar_moon,
                        selected = collection == DhikrCategory.EVENING,
                        modifier = Modifier.weight(1f)) { applyTemplate(eveningCollectionPreset(), clearCollection = false) }
                }
                Column {
                    FieldTitle("الأيام")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        // Full accessible weekday names; controls wrap on compact displays.
                        listOf(7, 1, 2, 3, 4, 5, 6).forEach { day ->
                            FilterChip(day in days, onClick = {
                                daysText = (if (day in days) days - day else days + day).sorted().joinToString(",")
                            }, label = { Text(dhikrWeekdays[day - 1], fontSize = 13.sp) }, modifier = Modifier.heightIn(min = 44.dp), shape = RoundedCornerShape(12.dp))
                        }
                    }
                }
                Column {
                    if (window != null) Text(formatDhikrWindow(window), color = p.muted, fontSize = 12.sp, lineHeight = 23.sp)
                    else if (problem != null && (edited.start.kind != DhikrTimeKind.FIXED || edited.end.kind != DhikrTimeKind.FIXED))
                        Text(problem, color = p.muted, fontSize = 12.sp, lineHeight = 23.sp)
                }
                if (isExisting) Text("التعديلات تبدأ بالفترات القادمة. تتوقف تذكيرات الفترة الحالية ويُحفظ عددها دون تغيير.", color = p.muted, fontSize = 12.sp, lineHeight = 23.sp)
                if (problem == null) Surface(color = AdhkarSoftGreen, shape = RoundedCornerShape(14.dp)) {
                    Text(dhikrRuleSummary(edited), Modifier.padding(16.dp), color = AdhkarHeading, fontSize = 13.sp, lineHeight = 24.sp)
                }
                if (problem == null) {
                    val nextReminder = remember(edited) { DhikrReminderScheduler.nextNudge(activity, edited) }
                    if (nextReminder != null) Text("التذكير القادم: " + formatDhikrTime(nextReminder), color = AdhkarHeading,
                        fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.testTag("adhkar_next_nudge"))
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(when {
                        !DhikrReminderScheduler.notificationsEnabled(activity) -> "الإشعارات غير مسموحة حاليًا. سيبقى تذكيرك محفوظًا."
                        activity.getSystemService(NotificationManager::class.java).getNotificationChannel(DhikrReminderScheduler.CHANNEL_ID)?.shouldVibrate() == false -> "اهتزاز الإشعار متوقف في إعدادات قناة الأذكار"
                        else -> "اهتزاز دون صوت، وفق إعدادات الهاتف"
                    }, color = p.muted, fontSize = 13.sp, lineHeight = 24.sp)
                    TextButton(onClick = { openDhikrNotificationSettings(activity) }) { Text("إعدادات قناة الأذكار") }
                }
            }
            HorizontalDivider(color = AdhkarBorder)
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 16.dp)) {
                (saveError ?: problem?.takeIf { showErrors })?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 8.dp).testTag("adhkar_editor_error"))
                }
                Button(onClick = { if (problem != null) showErrors = true else {
                    saving = true
                    onSave(edited) { error -> saving = false; saveError = error }
                } },
                    enabled = !saving, shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("adhkar_save_reminder")) {
                    DhikrIcon(R.drawable.ic_adhkar_check, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (saving) "جارٍ الحفظ…" else "حفظ التذكير", fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = { preview = true }, modifier = Modifier.fillMaxWidth()) { Text("معاينة الإشعار", fontSize = 12.sp) }
            }
        }
    }
    if (timeDialog) AlertDialog(onDismissRequest = { timeDialog = false }, title = { Text("الفترة الزمنية") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TimeEndpoint(activity, "من", start, Modifier.weight(1f), onKind = { startKind = it.name }, onTime = { startMinute = it })
                TimeEndpoint(activity, "إلى", end, Modifier.weight(1f), onKind = { endKind = it.name }, onTime = { endMinute = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { nextDay = !nextDay }) {
                Checkbox(nextDay, onCheckedChange = { nextDay = it })
                Text("تنتهي في اليوم التالي", color = AdhkarHeading, fontSize = 13.sp)
            }
            if (start.kind != DhikrTimeKind.FIXED) OutlinedTextField(startOffset, { startOffset = it },
                label = { Text("فرق دقائق البداية (− قبل / + بعد)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (end.kind != DhikrTimeKind.FIXED) OutlinedTextField(endOffset, { endOffset = it },
                label = { Text("فرق دقائق النهاية (− قبل / + بعد)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (window != null) Text(formatDhikrWindow(window), color = p.muted, fontSize = 12.sp, lineHeight = 22.sp)
        } }, confirmButton = { TextButton(onClick = { timeDialog = false }) { Text("تم") } })
    if (cadenceDialog) AlertDialog(onDismissRequest = { cadenceDialog = false }, title = { Text("تذكير كل") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val now = System.currentTimeMillis()
            val customInterval = integer(interval)
            fun expected(value: DhikrReminder): String = window
                ?.let { dhikrNudgeTimes(value, it).count { at -> at >= now } }
                ?.takeIf { it > 0 }?.let { " · " + latinNumber(it) + " تذكيرًا" }.orEmpty()
            val options: List<Triple<String, Boolean, () -> Unit>> = listOf(
                Triple("خفيف · حتى 3 تذكيرات" + expected(edited.copy(cadence = DhikrCadence.GENTLE)),
                    cadence == DhikrCadence.GENTLE.name) { cadence = DhikrCadence.GENTLE.name },
                Triple("متوازن · حتى 5 تذكيرات" + expected(edited.copy(cadence = DhikrCadence.BALANCED)),
                    cadence == DhikrCadence.BALANCED.name) { cadence = DhikrCadence.BALANCED.name },
                Triple("كل ساعة" + expected(edited.copy(cadence = DhikrCadence.HOURLY)),
                    cadence == DhikrCadence.HOURLY.name) { cadence = DhikrCadence.HOURLY.name },
            ) + listOf(5, 10, 15, 30, 120, 180).map { minutes ->
                Triple("كل " + latinNumber(minutes) + " دقيقة" + expected(edited.copy(cadence = DhikrCadence.CUSTOM, intervalMinutes = minutes)),
                    cadence == DhikrCadence.CUSTOM.name && customInterval == minutes) {
                    cadence = DhikrCadence.CUSTOM.name
                    interval = minutes.toString()
                }
            }
            options.forEach { (label, selected, apply) ->
                Row(Modifier.fillMaxWidth().clickable { apply(); cadenceDialog = false }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f), color = AdhkarHeading, fontSize = 14.sp)
                    if (selected) DhikrIcon(R.drawable.ic_adhkar_check, tint = p.primary, modifier = Modifier.size(18.dp))
                }
            }
            HorizontalDivider(color = AdhkarBorder, modifier = Modifier.padding(vertical = 8.dp))
            Text("فاصل مخصص", color = AdhkarHeading, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(interval, { if (it.length <= 4) interval = it }, singleLine = true,
                    suffix = { Text("دقيقة", fontSize = 12.sp) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = customInterval != null && customInterval !in MIN_DHIKR_INTERVAL_MINUTES..1440,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f).testTag("adhkar_interval_input"))
                TextButton(onClick = { cadence = DhikrCadence.CUSTOM.name; cadenceDialog = false },
                    enabled = customInterval != null && customInterval in MIN_DHIKR_INTERVAL_MINUTES..1440) { Text("تعيين") }
            }
            Text("يتكرر التذكير خلال الفترة بهذا الفاصل، من 5 دقائق إلى 24 ساعة.",
                color = p.muted, fontSize = 11.sp, lineHeight = 18.sp)
        } }, confirmButton = { TextButton(onClick = { cadenceDialog = false }) { Text("إغلاق") } })
    if (preview) AlertDialog(onDismissRequest = { preview = false }, title = { Text("معاينة الإشعار") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(selectedEntry.title, fontWeight = FontWeight.Bold)
            Text("لحظة للذكر · متابعة هدفك الشخصي")
            Text("متابعة الذكر     ·     بعد 30 دقيقة", color = p.primary)
            Text("هذه معاينة داخل التطبيق، لا ترسل إشعارًا. يظهر التأجيل فقط إذا بقيت 30 دقيقة ضمن الفترة.", fontSize = 12.sp, color = p.muted)
        } }, confirmButton = { TextButton(onClick = { preview = false }) { Text("تم") } })
}

private fun cadenceLabel(cadence: String, interval: String): String = when (DhikrCadence.valueOf(cadence)) {
    DhikrCadence.GENTLE -> "حتى 3 تذكيرات"
    DhikrCadence.BALANCED -> "حتى 5 تذكيرات"
    DhikrCadence.HOURLY -> "كل ساعة"
    DhikrCadence.CUSTOM -> "كل " + latinNumber(interval.toIntOrNull() ?: MIN_DHIKR_INTERVAL_MINUTES) + " دقيقة"
}

@Composable
private fun SettingRow(icon: Int, title: String, value: String, onClick: () -> Unit) {
    val p = LocalAdhkarPalette.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        DhikrIcon(icon, tint = p.primary, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = AdhkarHeading, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(value, color = p.muted, fontSize = 12.sp)
        }
        DhikrIcon(R.drawable.ic_adhkar_next, tint = p.muted, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalAdhkarPalette.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = AdhkarHeading, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(subtitle, color = p.muted, fontSize = 12.sp)
        }
        Switch(checked, onCheckedChange = onChange)
    }
}

@Composable
private fun TemplateCard(title: String, subtitle: String, icon: Int?, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = LocalAdhkarPalette.current
    Box(modifier) {
        Surface(onClick = onClick, shape = RoundedCornerShape(16.dp),
            color = if (selected) AdhkarSoftGreen else AdhkarSurface,
            border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) p.primary else AdhkarBorder)) {
            Column(Modifier.fillMaxWidth().heightIn(min = 88.dp).padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                if (icon != null) {
                    DhikrIcon(icon, tint = if (icon == R.drawable.ic_adhkar_sun) AdhkarGoldAccent else p.primary, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.height(8.dp))
                }
                Text(title, color = AdhkarHeading, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, textAlign = TextAlign.Center)
                if (subtitle.isNotEmpty()) Text(subtitle, color = p.muted, fontSize = 12.sp, textAlign = TextAlign.Center,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (selected) Box(Modifier.align(Alignment.TopEnd).padding(10.dp).size(9.dp).clip(CircleShape).background(p.primary))
    }
}

@Composable
private fun StepButton(icon: Int, description: String, onClick: () -> Unit) {
    val p = LocalAdhkarPalette.current
    Surface(onClick = onClick, shape = CircleShape, color = AdhkarSoftGreen, modifier = Modifier.size(48.dp)) {
        Box(contentAlignment = Alignment.Center) { DhikrIcon(icon, description, tint = p.primary, modifier = Modifier.size(22.dp)) }
    }
}

@Composable private fun FieldTitle(title: String, hint: String? = null) {
    val p = LocalAdhkarPalette.current
    Column {
        Text(title, color = AdhkarHeading, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        hint?.let { Text(it, color = p.muted, fontSize = 11.sp) }
    }
}
@Composable private fun TimeEndpoint(activity: AppCompatActivity, label: String, value: DhikrTime, modifier: Modifier,
    onKind: (DhikrTimeKind) -> Unit, onTime: (Int) -> Unit) {
    val p = LocalAdhkarPalette.current
    var choosing by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = p.muted, fontSize = 12.sp)
        Box {
            OutlinedButton(onClick = { choosing = true }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (value.kind == DhikrTimeKind.FIXED) "وقت ثابت" else dhikrTimeLabel(value.copy(offsetMinutes = 0)), fontSize = 14.sp)
            }
            DropdownMenu(choosing, onDismissRequest = { choosing = false }) {
                DhikrTimeKind.entries.forEach { kind ->
                    DropdownMenuItem(text = { Text(if (kind == DhikrTimeKind.FIXED) "وقت ثابت" else dhikrTimeLabel(DhikrTime(kind))) },
                        onClick = { onKind(kind); choosing = false })
                }
            }
        }
        if (value.kind == DhikrTimeKind.FIXED) TextButton(onClick = {
            TimePickerDialog(activity, { _, hour, minute -> onTime(hour * 60 + minute) }, value.minuteOfDay / 60, value.minuteOfDay % 60, true).show()
        }, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
            DhikrIcon(R.drawable.ic_adhkar_clock, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp)); Text(dhikrTimeLabel(value.copy(offsetMinutes = 0)))
        }
    }
}

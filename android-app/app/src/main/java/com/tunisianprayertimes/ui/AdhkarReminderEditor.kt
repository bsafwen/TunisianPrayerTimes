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
    exactAlarmsAvailable: Boolean, onDismiss: () -> Unit, onSave: (DhikrReminder, (String) -> Unit) -> Unit) {
    val p = LocalAdhkarPalette.current
    remember(activity) { DhikrReminderScheduler.ensureChannel(activity) }
    var selectedId by rememberSaveable(initial.id) { mutableStateOf(initial.dhikrId) }
    var collectionName by rememberSaveable(initial.id) { mutableStateOf(initial.collection?.name) }
    var target by rememberSaveable(initial.id) { mutableStateOf(initial.targetCount.toString()) }
    var daysText by rememberSaveable(initial.id) { mutableStateOf(initial.daysOfWeek.sorted().joinToString(",")) }
    val days = daysText.split(',').mapNotNull(String::toIntOrNull).toSet()
    val initialNextDay = initial.endNextDay ?: run {
        val window = DhikrReminderScheduler.currentOrNextWindow(activity, initial)
        window != null && Instant.ofEpochMilli(window.endMillis).atZone(ZoneId.systemDefault()).toLocalDate() >
            Instant.ofEpochMilli(window.startMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    }
    var intervalDraft by rememberSaveable(initial.id) {
        mutableStateOf(encodeIntervalDraft(initial.intervals().mapIndexed { index, value ->
            if (index == 0) value.copy(endNextDay = initialNextDay) else value
        }))
    }
    val intervals = remember(intervalDraft) { decodeIntervalDraft(intervalDraft) }
        .ifEmpty { listOf(DhikrInterval(initial.start, initial.end, initialNextDay)) }
    var timeDialogIndex by rememberSaveable(initial.id) { mutableIntStateOf(-1) }
    var startOffset by rememberSaveable(initial.id) { mutableStateOf(initial.start.offsetMinutes.toString()) }
    var endOffset by rememberSaveable(initial.id) { mutableStateOf(initial.end.offsetMinutes.toString()) }
    var cadence by rememberSaveable(initial.id) { mutableStateOf(initial.cadence.name) }
    var interval by rememberSaveable(initial.id) { mutableStateOf(initial.intervalMinutes.coerceAtLeast(MIN_DHIKR_INTERVAL_MINUTES).toString()) }
    var enabled by rememberSaveable(initial.id) { mutableStateOf(initial.enabled) }
    var vibrate by rememberSaveable(initial.id) { mutableStateOf(initial.vibrate) }
    var selectingDhikr by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(false) }
    var showErrors by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var cadenceDialog by remember { mutableStateOf(false) }
    fun integer(value: String): Int? = value.trim().map { if (it.isDigit()) Character.digit(it, 10).digitToChar() else it }.joinToString("").toIntOrNull()
    fun replaceInterval(index: Int, value: DhikrInterval) {
        intervalDraft = encodeIntervalDraft(intervals.toMutableList().also { it[index] = value })
    }
    fun openInterval(index: Int) {
        timeDialogIndex = index
        startOffset = intervals[index].start.offsetMinutes.toString()
        endOffset = intervals[index].end.offsetMinutes.toString()
    }
    val collection = collectionName?.let { runCatching { DhikrCategory.valueOf(it) }.getOrNull() }
    val showTarget = collection == null
    val knownEntries = remember(activity) { DhikrRepository(activity).state.value.allEntries }
    val selectedEntry = knownEntries.firstOrNull { it.id == selectedId } ?: knownEntries.first()
    val firstInterval = intervals.first()
    val start = firstInterval.start
    val end = firstInterval.end
    val nextDay = firstInterval.endNextDay
    val edited = initial.copy(dhikrId = selectedId, collection = collection,
        targetCount = if (collection != null) 1 else integer(target) ?: 0,
        daysOfWeek = days, start = start, end = end, endNextDay = nextDay,
        extraIntervals = intervals.drop(1),
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
        intervalDraft = encodeIntervalDraft(value.intervals())
        timeDialogIndex = -1
        cadence = value.cadence.name; interval = value.intervalMinutes.coerceAtLeast(MIN_DHIKR_INTERVAL_MINUTES).toString()
    }
    ModalBottomSheet(onDismissRequest = { if (!saving) onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = p.background, modifier = Modifier.testTag("adhkar_reminder_editor")) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(if (large) 1f else .85f).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("إعداد تذكير", Modifier.weight(1f), textAlign = TextAlign.Center,
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
                Text("الهدف وأوقات التذكير", color = AdhkarHeading, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                AdhkarCard(Modifier.fillMaxWidth()) {
                    Column {
                        if (showTarget) {
                            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("الهدف اليومي", color = p.muted, fontSize = 13.sp)
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
                                if (intervals.size > 1) Text("تُجمع قراءات جميع الفترات في هدف يومي واحد.",
                                    color = p.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                            }
                            HorizontalDivider(color = AdhkarBorder)
                        }
                        intervals.forEachIndexed { index, value ->
                            if (index > 0) HorizontalDivider(color = AdhkarBorder)
                            SettingRow(R.drawable.ic_adhkar_clock,
                                if (intervals.size == 1) "فترة التذكير" else "الفترة " + latinNumber(index + 1),
                                dhikrIntervalLabel(value)) { openInterval(index) }
                        }
                        TextButton(onClick = {
                            intervalDraft = encodeIntervalDraft(intervals + DhikrInterval(
                                DhikrTime(minuteOfDay = 18 * 60), DhikrTime(minuteOfDay = 19 * 60)))
                            timeDialogIndex = intervals.size
                            startOffset = "0"; endOffset = "0"
                        }, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                            DhikrIcon(R.drawable.ic_adhkar_plus, tint = p.primary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("إضافة فترة")
                        }
                        HorizontalDivider(color = AdhkarBorder)
                        SettingRow(R.drawable.ic_adhkar_bell, "تكرار الإشعارات", cadenceLabel(cadence, interval, intervals.size)) { cadenceDialog = true }
                    }
                }
                Text("خيارات التذكير", color = AdhkarHeading, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                AdhkarCard(Modifier.fillMaxWidth()) {
                    Column {
                        ToggleRow("إشعارات التذكير", "تلقّي تذكيرات خلال الفترات المحددة", enabled) { enabled = it }
                        HorizontalDivider(color = AdhkarBorder)
                        ToggleRow("الاهتزاز", "اهتزاز عند وصول التذكير", vibrate) { vibrate = it }
                    }
                }
                Text("خيارات جاهزة", color = AdhkarHeading, fontWeight = FontWeight.Bold, fontSize = 17.sp)
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
                if (isExisting) Text("تسري التعديلات على الفترات القادمة. تتوقف إشعارات الفترة الحالية، ويبقى عدد القراءات محفوظًا.", color = p.muted, fontSize = 12.sp, lineHeight = 23.sp)
                if (problem == null) Surface(color = AdhkarSoftGreen, shape = RoundedCornerShape(14.dp)) {
                    Text(dhikrRuleSummary(edited), Modifier.padding(16.dp), color = AdhkarHeading, fontSize = 13.sp, lineHeight = 24.sp)
                }
                if (problem == null) {
                    val nextReminder = remember(edited) { DhikrReminderScheduler.nextNudge(activity, edited) }
                    if (nextReminder != null) Text("التذكير القادم: " + formatDhikrTime(nextReminder), color = AdhkarHeading,
                        fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.testTag("adhkar_next_nudge"))
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (collection != null && DhikrRepository(activity).state.value.collectionEntries(collection).isEmpty()) {
                        Text("هذه المجموعة فارغة. لن تصلك تذكيراتها حتى تضيف إليها ذكرًا.",
                            color = MaterialTheme.colorScheme.error, fontSize = 13.sp, lineHeight = 24.sp)
                    }
                    val selectedChannel = activity.getSystemService(NotificationManager::class.java)
                        .getNotificationChannel(DhikrReminderScheduler.channelId(vibrate))
                    Text(when {
                        !enabled -> "إشعارات هذا التذكير متوقفة."
                        selectedChannel == null || !DhikrReminderScheduler.notificationsEnabled(activity, vibrate) ->
                            "إشعارات الأذكار غير متاحة حاليًا. سيبقى تذكيرك محفوظًا."
                        selectedChannel.importance < NotificationManager.IMPORTANCE_HIGH ->
                            "قد لا يظهر التذكير منبثقًا لأن أولوية إشعارات الأذكار منخفضة."
                        vibrate && !selectedChannel.shouldVibrate() -> "الاهتزاز معطّل في إعدادات إشعارات الأذكار."
                        !vibrate && selectedChannel.shouldVibrate() -> "الاهتزاز مفعّل في إعدادات إشعارات الأذكار رغم إيقافه هنا."
                        vibrate -> "إشعار مع اهتزاز دون صوت، وفق إعدادات الهاتف."
                        else -> "إشعار دون صوت أو اهتزاز، وفق إعدادات الهاتف."
                    }, color = p.muted, fontSize = 13.sp, lineHeight = 24.sp)
                    TextButton(onClick = { openDhikrNotificationSettings(activity, vibrate) }) { Text("إعدادات إشعارات الأذكار") }
                    if (enabled && !exactAlarmsAvailable) {
                        Text("لم تُفعّل «المنبّهات والتذكيرات». قد يصلك التذكير متأخرًا أو بعد انتهاء الفترة.",
                            color = MaterialTheme.colorScheme.error, fontSize = 13.sp, lineHeight = 24.sp)
                        TextButton(onClick = { openDhikrExactAlarmSettings(activity) }) { Text("تفعيل المنبّهات والتذكيرات") }
                    }
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
    intervals.getOrNull(timeDialogIndex)?.let { selected ->
        AlertDialog(onDismissRequest = { timeDialogIndex = -1 },
            title = { Text(if (intervals.size == 1) "فترة التذكير" else "الفترة " + latinNumber(timeDialogIndex + 1)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TimeEndpoint(activity, "من", selected.start, Modifier.weight(1f), onKind = { kind ->
                        replaceInterval(timeDialogIndex, selected.copy(start = selected.start.copy(
                            kind = kind, offsetMinutes = if (kind == DhikrTimeKind.FIXED) 0 else integer(startOffset) ?: 9999)))
                    }, onTime = { minute ->
                        replaceInterval(timeDialogIndex, selected.copy(start = selected.start.copy(minuteOfDay = minute)))
                    })
                    TimeEndpoint(activity, "إلى", selected.end, Modifier.weight(1f), onKind = { kind ->
                        replaceInterval(timeDialogIndex, selected.copy(end = selected.end.copy(
                            kind = kind, offsetMinutes = if (kind == DhikrTimeKind.FIXED) 0 else integer(endOffset) ?: 9999)))
                    }, onTime = { minute ->
                        replaceInterval(timeDialogIndex, selected.copy(end = selected.end.copy(minuteOfDay = minute)))
                    })
                }
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable {
                        replaceInterval(timeDialogIndex, selected.copy(endNextDay = selected.endNextDay != true))
                    }) {
                    Checkbox(selected.endNextDay == true, onCheckedChange = {
                        replaceInterval(timeDialogIndex, selected.copy(endNextDay = it))
                    })
                    Text("تنتهي في اليوم التالي", color = AdhkarHeading, fontSize = 13.sp)
                }
                if (selected.start.kind != DhikrTimeKind.FIXED) OutlinedTextField(startOffset, { value ->
                    startOffset = value
                    replaceInterval(timeDialogIndex, selected.copy(start = selected.start.copy(
                        offsetMinutes = integer(value) ?: 9999)))
                }, label = { Text("تعديل وقت البداية بالدقائق (− قبل، + بعد)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (selected.end.kind != DhikrTimeKind.FIXED) OutlinedTextField(endOffset, { value ->
                    endOffset = value
                    replaceInterval(timeDialogIndex, selected.copy(end = selected.end.copy(
                        offsetMinutes = integer(value) ?: 9999)))
                }, label = { Text("تعديل وقت النهاية بالدقائق (− قبل، + بعد)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                val selectedWindow = window?.let { next ->
                    DhikrReminderScheduler.resolveWindows(activity, edited, next.date)
                        .firstOrNull { it.intervalIndex == timeDialogIndex }
                }
                if (selectedWindow != null) Text(formatDhikrWindow(selectedWindow),
                    color = p.muted, fontSize = 12.sp, lineHeight = 22.sp)
                if (problem != null) Text(problem, color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp, lineHeight = 20.sp)
            } },
            confirmButton = { TextButton(onClick = { timeDialogIndex = -1 }) { Text("تم") } },
            dismissButton = { if (timeDialogIndex > 0) TextButton(onClick = {
                intervalDraft = encodeIntervalDraft(intervals.filterIndexed { index, _ -> index != timeDialogIndex })
                timeDialogIndex = -1
            }) { Text("حذف الفترة", color = MaterialTheme.colorScheme.error) } })
    }
    if (cadenceDialog) AlertDialog(onDismissRequest = { cadenceDialog = false }, title = { Text("تكرار الإشعارات") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val now = System.currentTimeMillis()
            val customInterval = integer(interval)
            val previewWindows = window?.let { DhikrReminderScheduler.resolveWindows(activity, edited, it.date) }
            fun expected(value: DhikrReminder): String = previewWindows
                ?.let { dhikrNudgeTimes(value, it).count { at -> at >= now } }
                ?.takeIf { it > 0 }?.let { " · إشعارات متوقعة: " + latinNumber(it) }.orEmpty()
            val options: List<Triple<String, Boolean, () -> Unit>> = listOf(
                Triple("خفيف · " + regularCadenceLabel(3, intervals.size) + expected(edited.copy(cadence = DhikrCadence.GENTLE)),
                    cadence == DhikrCadence.GENTLE.name) { cadence = DhikrCadence.GENTLE.name },
                Triple("متوازن · " + regularCadenceLabel(5, intervals.size) + expected(edited.copy(cadence = DhikrCadence.BALANCED)),
                    cadence == DhikrCadence.BALANCED.name) { cadence = DhikrCadence.BALANCED.name },
                Triple("كل ساعة" + expected(edited.copy(cadence = DhikrCadence.HOURLY)),
                    cadence == DhikrCadence.HOURLY.name) { cadence = DhikrCadence.HOURLY.name },
            ) + listOf(15, 30, 45, 120, 180).map { minutes ->
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
            Text("تحديد الفاصل بالدقائق", color = AdhkarHeading, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
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
            Text("في الخيارين «خفيف» و«متوازن»، يصلك تذكير واحد على الأقل خلال كل فترة ما دام الهدف اليومي غير مكتمل. قد يزيد العدد عن 3 أو 5 إذا أضفت فترات أكثر. يمكنك اختيار فاصل من 15 دقيقة إلى 24 ساعة، وقد يؤخّر الهاتف بعض الإشعارات في وضع توفير البطارية.",
                color = p.muted, fontSize = 11.sp, lineHeight = 18.sp)
        } }, confirmButton = { TextButton(onClick = { cadenceDialog = false }) { Text("إغلاق") } })
    if (preview) AlertDialog(onDismissRequest = { preview = false }, title = { Text("معاينة الإشعار") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(collection?.let(::collectionTitle) ?: selectedEntry.title, fontWeight = FontWeight.Bold)
            Text(if (collection != null) "حان وقت قراءة الأذكار"
                else "حان وقت الذكر • 0 من " + latinNumber(edited.targetCount.coerceAtLeast(1)))
            Text("متابعة الذكر     ·     تأجيل", color = p.primary)
            Text("هذه معاينة فقط؛ لن يصلك إشعار. يظهر «تأجيل» إذا بقيت 35 دقيقة على الأقل قبل نهاية الفترة، ويؤخّر التذكير 30 دقيقة.",
                fontSize = 12.sp, color = p.muted)
        } }, confirmButton = { TextButton(onClick = { preview = false }) { Text("تم") } })
}

private fun encodeIntervalDraft(intervals: List<DhikrInterval>): String = intervals.joinToString(";") { value ->
    listOf(value.start.kind.name, value.start.minuteOfDay, value.start.offsetMinutes,
        value.end.kind.name, value.end.minuteOfDay, value.end.offsetMinutes,
        value.endNextDay?.toString() ?: "null").joinToString(",")
}

private fun decodeIntervalDraft(value: String): List<DhikrInterval> = value.split(';').mapNotNull { encoded ->
    val fields = encoded.split(',')
    if (fields.size != 7) return@mapNotNull null
    runCatching {
        DhikrInterval(
            DhikrTime(DhikrTimeKind.valueOf(fields[0]), fields[1].toInt(), fields[2].toInt()),
            DhikrTime(DhikrTimeKind.valueOf(fields[3]), fields[4].toInt(), fields[5].toInt()),
            when (fields[6]) { "true" -> true; "false" -> false; else -> null },
        )
    }.getOrNull()
}

private fun dhikrIntervalLabel(value: DhikrInterval): String =
    "من " + dhikrTimeLabel(value.start) + " إلى " + dhikrTimeLabel(value.end) +
        if (value.endNextDay == true) " في اليوم التالي" else ""

private fun regularCadenceLabel(limit: Int, intervalCount: Int): String =
    if (intervalCount > limit) "تذكير واحد لكل فترة" else "حتى " + latinNumber(limit) + " تذكيرات يوميًا"

private fun cadenceLabel(cadence: String, interval: String, intervalCount: Int): String = when (DhikrCadence.valueOf(cadence)) {
    DhikrCadence.GENTLE -> regularCadenceLabel(3, intervalCount)
    DhikrCadence.BALANCED -> regularCadenceLabel(5, intervalCount)
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

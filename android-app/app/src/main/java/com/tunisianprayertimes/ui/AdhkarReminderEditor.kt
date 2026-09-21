package com.tunisianprayertimes.ui

import android.app.NotificationManager
import android.app.TimePickerDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
    var interval by rememberSaveable(initial.id) { mutableStateOf(initial.intervalMinutes.coerceAtLeast(30).toString()) }
    var enabled by rememberSaveable(initial.id) { mutableStateOf(initial.enabled) }
    var advanced by rememberSaveable(initial.id) { mutableStateOf(initial.cadence == DhikrCadence.CUSTOM || initial.start.offsetMinutes != 0 || initial.end.offsetMinutes != 0) }
    var selectingDhikr by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(false) }
    var showErrors by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    fun integer(value: String): Int? = value.trim().map { if (it.isDigit()) Character.digit(it, 10).digitToChar() else it }.joinToString("").toIntOrNull()
    val start = DhikrTime(DhikrTimeKind.valueOf(startKind), startMinute, if (startKind == "FIXED") 0 else integer(startOffset) ?: 9999)
    val end = DhikrTime(DhikrTimeKind.valueOf(endKind), endMinute, if (endKind == "FIXED") 0 else integer(endOffset) ?: 9999)
    val edited = initial.copy(dhikrId = selectedId, targetCount = integer(target) ?: 0, daysOfWeek = days, start = start, end = end,
        endNextDay = nextDay, cadence = DhikrCadence.valueOf(cadence), intervalMinutes = integer(interval) ?: 0, enabled = enabled)
    val problem = remember(edited) { DhikrReminderScheduler.validate(activity, edited) }
    LaunchedEffect(edited) { saveError = null }
    val window = remember(edited) { if (problem == null) DhikrReminderScheduler.currentOrNextWindow(activity, edited) else null }
    val large = LocalConfiguration.current.screenHeightDp < 640 || LocalDensity.current.fontScale > 1.3f
    val channel = activity.getSystemService(NotificationManager::class.java).getNotificationChannel(DhikrReminderScheduler.CHANNEL_ID)
    val isExisting = remember(initial.id) { DhikrRepository(activity).state.value.reminders.any { it.id == initial.id } }
    ModalBottomSheet(onDismissRequest = { if (!saving) onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = p.background, modifier = Modifier.testTag("adhkar_reminder_editor")) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(if (large) 1f else .95f).imePadding()) {
            DhikrSheetHeader("تخصيص التذكير") { if (!saving) onDismiss() }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FieldTitle("الذكر")
                    Box {
                        OutlinedButton(onClick = { selectingDhikr = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp)) {
                            Text(DhikrCatalog.find(selectedId)?.title.orEmpty(), Modifier.weight(1f), fontSize = 14.sp)
                            DhikrIcon(R.drawable.ic_adhkar_bookmark, modifier = Modifier.size(18.dp))
                        }
                        DropdownMenu(selectingDhikr, onDismissRequest = { selectingDhikr = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                            DhikrCatalog.entries.forEach { entry ->
                                DropdownMenuItem(text = { Text(entry.title) }, onClick = { selectedId = entry.id; selectingDhikr = false })
                            }
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FieldTitle("هدفك الشخصي", "عدد تختاره أنت")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(50, 100, 200).forEach { amount ->
                            FilterChip(integer(target) == amount, onClick = { target = amount.toString() }, label = { Text(arabicNumber(amount)) },
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp))
                        }
                    }
                    OutlinedTextField(target, onValueChange = { if (it.length <= 7) target = it }, label = { Text("عدد مخصص") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                        shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().testTag("adhkar_target_input"))
                }
                Column {
                    FieldTitle("الأيام")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        // Full accessible weekday names; controls wrap on compact displays.
                        listOf(7, 1, 2, 3, 4, 5, 6).forEach { day ->
                            FilterChip(day in days, onClick = {
                                daysText = (if (day in days) days - day else days + day).sorted().joinToString(",")
                            }, label = { Text(dhikrWeekdays[day - 1], fontSize = 13.sp) }, modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp))
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FieldTitle("خلال الفترة")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TimeEndpoint(activity, "من", start, Modifier.weight(1f), onKind = { startKind = it.name }, onTime = { startMinute = it })
                        TimeEndpoint(activity, "إلى", end, Modifier.weight(1f), onKind = { endKind = it.name }, onTime = { endMinute = it })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { nextDay = !nextDay }) {
                        Checkbox(nextDay, onCheckedChange = { nextDay = it })
                        Text("تنتهي في اليوم التالي", color = p.ink, fontSize = 13.sp)
                    }
                    if (window != null) Text(formatDhikrWindow(window), color = p.muted, fontSize = 12.sp, lineHeight = 23.sp)
                    else if (problem != null && (edited.start.kind != DhikrTimeKind.FIXED || edited.end.kind != DhikrTimeKind.FIXED))
                        Text(problem, color = p.muted, fontSize = 12.sp, lineHeight = 23.sp)
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FieldTitle("وتيرة التذكير", "تتوقف عند اكتمال هدفك")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(DhikrCadence.GENTLE to "خفيف · حتى ٣", DhikrCadence.BALANCED to "متوازن · حتى ٥", DhikrCadence.HOURLY to "كل ساعة").forEach { (value, label) ->
                            FilterChip(cadence == value.name, onClick = { cadence = value.name }, label = { Text(label, fontSize = 13.sp) },
                                modifier = Modifier.heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp))
                        }
                    }
                    Text("دعوات متباعدة للمتابعة. قد يؤخرها Android لتوفير البطارية.", color = p.muted, fontSize = 12.sp, lineHeight = 22.sp)
                    TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "إخفاء الخيارات المتقدمة" else "خيارات متقدمة") }
                    if (advanced) {
                        if (start.kind != DhikrTimeKind.FIXED) OutlinedTextField(startOffset, { startOffset = it }, label = { Text("فرق دقائق البداية (− قبل / + بعد)") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        if (end.kind != DhikrTimeKind.FIXED) OutlinedTextField(endOffset, { endOffset = it }, label = { Text("فرق دقائق النهاية (− قبل / + بعد)") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(cadence == DhikrCadence.CUSTOM.name, onCheckedChange = { cadence = if (it) DhikrCadence.CUSTOM.name else DhikrCadence.GENTLE.name })
                            Text("فاصل مخصص", color = p.ink)
                        }
                        if (cadence == DhikrCadence.CUSTOM.name) OutlinedTextField(interval, { interval = it }, label = { Text("دقيقة بين التذكيرات (٣٠ أو أكثر)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FieldTitle("الإشعارات")
                    Text(when {
                        !DhikrReminderScheduler.notificationsEnabled(activity) -> "الإشعارات غير مسموحة حاليًا. سيبقى تذكيرك محفوظًا."
                        channel?.sound != null -> "الصوت والاهتزاز بحسب إعدادات قناة الأذكار"
                        channel?.shouldVibrate() == false -> "الإشعارات صامتة؛ الاهتزاز متوقف في إعدادات الهاتف"
                        else -> "اهتزاز دون صوت، وفق إعدادات الهاتف"
                    }, color = p.muted, fontSize = 13.sp, lineHeight = 24.sp)
                    TextButton(onClick = { openDhikrNotificationSettings(activity) }) { Text("إعدادات قناة الأذكار") }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("تفعيل التذكير عند الحفظ", Modifier.weight(1f), color = p.ink, fontSize = 14.sp)
                        Switch(enabled, onCheckedChange = { enabled = it })
                    }
                }
                if (isExisting) Text("التعديلات تبدأ بالفترات القادمة. تتوقف تذكيرات الفترة الحالية ويُحفظ عددها دون تغيير.", color = p.muted, fontSize = 12.sp, lineHeight = 23.sp)
                if (problem == null) Surface(color = p.sage, shape = RoundedCornerShape(14.dp)) {
                    Text(dhikrRuleSummary(edited), Modifier.padding(16.dp), color = p.ink, fontSize = 13.sp, lineHeight = 24.sp)
                }
            }
            HorizontalDivider(color = p.border)
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 16.dp)) {
                (saveError ?: problem?.takeIf { showErrors })?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 8.dp).testTag("adhkar_editor_error"))
                }
                Button(onClick = { if (problem != null) showErrors = true else {
                    saving = true
                    onSave(edited) { error -> saving = false; saveError = error }
                } },
                    enabled = !saving, shape = RoundedCornerShape(15.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("adhkar_save_reminder")) { Text(if (saving) "جارٍ الحفظ…" else "حفظ التذكير") }
                TextButton(onClick = { preview = true }, modifier = Modifier.fillMaxWidth()) { Text("معاينة الإشعار", fontSize = 12.sp) }
            }
        }
    }
    if (preview) AlertDialog(onDismissRequest = { preview = false }, title = { Text("معاينة الإشعار") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(DhikrCatalog.find(selectedId)?.title.orEmpty(), fontWeight = FontWeight.Bold)
            Text("لحظة للذكر · متابعة هدفك الشخصي")
            Text("متابعة الذكر     ·     بعد ٣٠ دقيقة", color = p.primary)
            Text("هذه معاينة داخل التطبيق، لا ترسل إشعارًا. يظهر التأجيل فقط إذا بقيت ٣٠ دقيقة ضمن الفترة.", fontSize = 12.sp, color = p.muted)
        } }, confirmButton = { TextButton(onClick = { preview = false }) { Text("تم") } })
}
@Composable private fun FieldTitle(title: String, hint: String? = null) {
    val p = LocalAdhkarPalette.current
    Column {
        Text(title, color = p.ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
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
            OutlinedButton(onClick = { choosing = true }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
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
        }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            DhikrIcon(R.drawable.ic_adhkar_clock, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp)); Text(dhikrTimeLabel(value.copy(offsetMinutes = 0)))
        }
    }
}

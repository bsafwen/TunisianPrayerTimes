package com.tunisianprayertimes.ui

import android.app.NotificationManager
import androidx.activity.compose.BackHandler
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.tunisianprayertimes.R
import com.tunisianprayertimes.adhkar.*
import java.time.*
import kotlinx.coroutines.launch
import org.json.JSONObject

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
    val initialNextDay = remember(initial.id) { initial.endNextDay ?: run {
        val window = DhikrReminderScheduler.currentOrNextWindow(activity, initial)
        window != null && Instant.ofEpochMilli(window.endMillis).atZone(ZoneId.systemDefault()).toLocalDate() >
            Instant.ofEpochMilli(window.startMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    } }
    var intervalDraft by rememberSaveable(initial.id) {
        mutableStateOf(encodeIntervalDraft(initial.intervals().mapIndexed { index, value ->
            if (index == 0) value.copy(endNextDay = initialNextDay) else value
        }))
    }
    val intervals = remember(intervalDraft) { decodeIntervalDraft(intervalDraft) }
        .ifEmpty { listOf(DhikrInterval(initial.start, initial.end, initialNextDay)) }
    var timeDialogIndex by rememberSaveable(initial.id) { mutableIntStateOf(-1) }
    var cadence by rememberSaveable(initial.id) { mutableStateOf(initial.cadence.name) }
    var interval by rememberSaveable(initial.id) { mutableStateOf(initial.intervalMinutes.coerceAtLeast(MIN_DHIKR_INTERVAL_MINUTES).toString()) }
    var enabled by rememberSaveable(initial.id) { mutableStateOf(initial.enabled) }
    var vibrate by rememberSaveable(initial.id) { mutableStateOf(initial.vibrate) }
    var selectingDhikr by remember { mutableStateOf(false) }
    // «100 مرة» needs a single dhikr: without one it opens the list and applies after the choice.
    var hundredAfterPick by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var saveAttempted by rememberSaveable(initial.id) { mutableStateOf(false) }
    var cadenceDialog by remember { mutableStateOf(false) }
    var discardPrompt by remember { mutableStateOf(false) }
    var pendingTemplate by remember { mutableStateOf<DhikrReminder?>(null) }
    // The interval draft as it was when the time dialog opened, so «إلغاء» can restore it.
    var intervalBackup by rememberSaveable(initial.id) { mutableStateOf<String?>(null) }
    fun integer(value: String): Int? = parseDhikrInt(value)
    fun replaceInterval(index: Int, value: DhikrInterval) {
        intervalDraft = encodeIntervalDraft(intervals.toMutableList().also { it[index] = value })
    }
    fun openInterval(index: Int) {
        intervalBackup = intervalDraft
        timeDialogIndex = index
    }
    fun cancelInterval() {
        intervalBackup?.let { intervalDraft = it }
        intervalBackup = null
        timeDialogIndex = -1
    }
    val collection = collectionName?.let { runCatching { DhikrCategory.valueOf(it) }.getOrNull() }
    val showTarget = collection == null
    val repoState = remember(activity) { DhikrRepository(activity).state.value }
    val knownEntries = repoState.allEntries
    // Null until a dhikr is chosen, and when the saved one no longer exists; validation then asks for a choice.
    val selectedEntry = knownEntries.firstOrNull { it.id == selectedId }
    val steppedEntry = selectedEntry?.takeIf { it.steps.isNotEmpty() }
    val firstInterval = intervals.first()
    val start = firstInterval.start
    val end = firstInterval.end
    val nextDay = firstInterval.endNextDay
    val edited = initial.copy(dhikrId = selectedId, collection = collection,
        targetCount = if (collection != null) 1 else steppedEntry?.defaultCount ?: integer(target) ?: 0,
        daysOfWeek = days, start = start, end = end, endNextDay = nextDay,
        extraIntervals = intervals.drop(1),
        cadence = DhikrCadence.valueOf(cadence), intervalMinutes = integer(interval) ?: 0,
        enabled = enabled, vibrate = vibrate)
    val problem = remember(edited) { DhikrReminderScheduler.validate(activity, edited) }
    LaunchedEffect(edited) { saveError = null }
    val window = remember(edited) { if (problem == null) DhikrReminderScheduler.currentOrNextWindow(activity, edited) else null }
    val large = LocalConfiguration.current.screenHeightDp < 640 || LocalDensity.current.fontScale > 1.3f
    val isExisting = remember(initial.id) { repoState.reminders.any { it.id == initial.id } }
    // Saved, so a re-created activity still knows what the form held when it opened.
    val original = rememberSaveable(initial.id, saver = DhikrReminderSaver) { edited }
    val dirty = edited != original
    // What the form held after the last ready-made option; later edits on top of it are the user's own.
    var templateBase by rememberSaveable(initial.id, stateSaver = DhikrReminderSaver) { mutableStateOf(edited) }
    var rebaseToken by remember { mutableIntStateOf(0) }
    val currentEdited by rememberUpdatedState(edited)
    LaunchedEffect(rebaseToken) { if (rebaseToken > 0) templateBase = currentEdited }
    val customized = edited.copy(enabled = templateBase.enabled, vibrate = templateBase.vibrate) != templateBase ||
        (isExisting && templateBase == original)
    val formScroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    fun applyTemplate(value: DhikrReminder) {
        selectedId = value.dhikrId
        collectionName = value.collection?.name
        target = value.targetCount.toString()
        daysText = value.daysOfWeek.sorted().joinToString(",")
        intervalDraft = encodeIntervalDraft(value.intervals())
        timeDialogIndex = -1
        cadence = value.cadence.name; interval = value.intervalMinutes.coerceAtLeast(MIN_DHIKR_INTERVAL_MINUTES).toString()
        rebaseToken++
        // The option rewrites the fields above it: show them, rather than let the cards shift under the finger.
        scope.launch { formScroll.animateScrollTo(0) }
    }
    // A ready-made option replaces the whole schedule; ask first when that would drop the user's settings.
    fun requestTemplate(value: DhikrReminder) { if (customized) pendingTemplate = value else applyTemplate(value) }
    // Listed in the order they happen, when the times can be resolved.
    fun chronological(list: List<DhikrInterval>): List<DhikrInterval> {
        val date = window?.date ?: LocalDate.now()
        val starts = list.map { DhikrReminderScheduler.resolveTime(activity, it.start, date) }
        return if (starts.any { it == null }) list else list.indices.sortedBy { starts[it]!! }.map { list[it] }
    }
    var resumeTick by remember { mutableIntStateOf(0) }
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) resumeTick++ }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }
    val blockHide by rememberUpdatedState(saving || dirty)
    // Swipe, scrim and handle taps pass through here: never hide mid-save or over unsaved edits.
    val confirmSheetValue = remember { { value: SheetValue ->
        if (value != SheetValue.Hidden || !blockHide) true
        else { if (!saving) discardPrompt = true; false }
    } }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = confirmSheetValue)
    fun requestClose() {
        if (saving) return
        if (dirty) discardPrompt = true
        else scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = AdhkarSurface, unfocusedContainerColor = AdhkarSurface,
        focusedBorderColor = p.primary.copy(alpha = .6f), unfocusedBorderColor = AdhkarBorder)
    // The sheet's own back handling dismisses even when the hide is vetoed, so back goes through requestClose.
    ModalBottomSheet(onDismissRequest = { requestClose() }, sheetState = sheetState,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
        containerColor = p.background, modifier = Modifier.testTag("adhkar_reminder_editor")) {
        BackHandler { requestClose() }
        Column(Modifier.fillMaxWidth().fillMaxHeight(if (large) 1f else .85f).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { requestClose() }) { DhikrIcon(R.drawable.ic_adhkar_back, "رجوع") }
                Text("إعداد تذكير", Modifier.weight(1f), textAlign = TextAlign.Center,
                    fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AdhkarHeading)
                // Balances the back button so the title is centred on the sheet.
                Spacer(Modifier.size(48.dp))
            }
            // The guard keeps a scroll that reaches the top from dragging the sheet towards dismissal.
            Column(Modifier.weight(1f).nestedScroll(rememberSheetScrollGuard(formScroll)).verticalScroll(formScroll)
                .padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                AdhkarCard(Modifier.fillMaxWidth().testTag("adhkar_reminder_dhikr"), onClick = { selectingDhikr = true }) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(54.dp).clip(RoundedCornerShape(16.dp)).background(AdhkarSoftGreen), contentAlignment = Alignment.Center) {
                            DhikrIcon(when {
                                selectedEntry?.id == DhikrCatalog.SALAWAT_ID && collection == null -> R.drawable.ic_adhkar_salawat
                                else -> (collection ?: selectedEntry?.takeUnless { it.custom }?.categories?.firstOrNull())
                                    ?.let(::categoryIcon) ?: R.drawable.ic_adhkar_leaf
                            }, modifier = Modifier.size(28.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(collection?.let(::collectionTitle) ?: selectedEntry?.title ?: "اختر ذكرًا",
                                color = if (collection == null && selectedEntry == null) p.primary else AdhkarHeading,
                                fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (collection == null && selectedEntry != null) Text(dhikrPreview(selectedEntry.text),
                                color = p.muted, fontFamily = AdhkarReadingFont, fontSize = 12.sp, lineHeight = 22.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            else Text(when {
                                collection != null -> collectionSubtitle(repoState, collection)
                                selectedId.isEmpty() -> "اضغط لاختيار الذكر الذي تريد التذكير به."
                                else -> "الذكر المحفوظ لم يعد موجودًا."
                            }, color = p.muted, fontSize = 12.sp, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        DhikrIcon(R.drawable.ic_adhkar_next, tint = p.muted, modifier = Modifier.size(18.dp))
                    }
                }
                Text("الهدف وأوقات التذكير", color = AdhkarHeading, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                AdhkarCard(Modifier.fillMaxWidth()) {
                    Column {
                        if (showTarget && steppedEntry != null) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("الهدف اليومي: " + latinNumber(steppedEntry.defaultCount),
                                    color = AdhkarHeading, fontWeight = FontWeight.Bold)
                                Text(steppedEntry.steps.joinToString(" · ") { it.label + " " + latinNumber(it.repetitions) },
                                    color = p.muted, fontSize = 12.sp, textAlign = TextAlign.Center)
                            }
                            HorizontalDivider(color = AdhkarBorder)
                        } else if (showTarget) {
                            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("الهدف اليومي", color = p.muted, fontSize = 13.sp)
                                Spacer(Modifier.height(10.dp))
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    StepButton(R.drawable.ic_add, "زيادة العدد") {
                                        target = ((integer(target) ?: 0) + 1).coerceAtMost(100000).toString()
                                    }
                                    OutlinedTextField(
                                        value = target, onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) target = it },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center,
                                            fontWeight = FontWeight.Bold, fontSize = 18.sp, color = AdhkarHeading),
                                        shape = RoundedCornerShape(14.dp), colors = fieldColors,
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
                            intervalBackup = intervalDraft
                            intervalDraft = encodeIntervalDraft(intervals + suggestedExtraInterval(intervals))
                            timeDialogIndex = intervals.size
                        }, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                            DhikrIcon(R.drawable.ic_adhkar_plus, tint = p.primary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("إضافة فترة")
                        }
                        HorizontalDivider(color = AdhkarBorder)
                        SettingRow(R.drawable.ic_adhkar_bell, "تكرار الإشعارات", cadenceLabel(cadence, interval, intervals.size)) { cadenceDialog = true }
                    }
                }
                Column {
                    FieldTitle("الأيام")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        // Full accessible weekday names; controls wrap on compact displays.
                        listOf(7, 1, 2, 3, 4, 5, 6).forEach { day ->
                            FilterChip(day in days, onClick = {
                                daysText = (if (day in days) days - day else days + day).sorted().joinToString(",")
                            }, label = { Text(dhikrWeekdays[day - 1], fontSize = 13.sp) }, modifier = Modifier.heightIn(min = 44.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = FilterChipDefaults.filterChipColors(containerColor = AdhkarSurface, labelColor = p.muted,
                                    selectedContainerColor = AdhkarSoftGreen, selectedLabelColor = AdhkarHeading),
                                border = FilterChipDefaults.filterChipBorder(enabled = true, selected = day in days,
                                    borderColor = AdhkarBorder, selectedBorderColor = p.primary, selectedBorderWidth = 1.dp))
                        }
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
                        modifier = Modifier.weight(1f)) { requestTemplate(fridayDhikrPreset()) }
                    // Only the goal and the days: the chosen dhikr, periods and cadence stay as they are.
                    TemplateCard("100 مرة", "يوميًا", null,
                        selected = collection == null && edited.targetCount == 100 && days.size == 7,
                        modifier = Modifier.weight(1f)) {
                            if (collection != null || selectedEntry == null) { hundredAfterPick = true; selectingDhikr = true }
                            else { target = "100"; daysText = (1..7).joinToString(",") }
                        }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TemplateCard("أذكار الصباح", "مجموعة كاملة", R.drawable.ic_adhkar_sun,
                        selected = collection == DhikrCategory.MORNING,
                        modifier = Modifier.weight(1f)) { requestTemplate(morningCollectionPreset()) }
                    TemplateCard("أذكار المساء", "مجموعة كاملة", R.drawable.ic_adhkar_moon,
                        selected = collection == DhikrCategory.EVENING,
                        modifier = Modifier.weight(1f)) { requestTemplate(eveningCollectionPreset()) }
                }
                TemplateCard("أذكار الليل بعد المغرب", collectionSubtitle(repoState, DhikrCategory.NIGHT),
                    R.drawable.ic_adhkar_moon, selected = collection == DhikrCategory.NIGHT,
                    modifier = Modifier.fillMaxWidth()) { requestTemplate(nightCollectionPreset()) }
                if (window != null) Text("الفترة القادمة: " + formatDhikrWindow(window), color = p.muted, fontSize = 12.sp, lineHeight = 23.sp)
                if (isExisting) Text("تسري التعديلات على الفترات القادمة. تتوقف إشعارات الفترة الحالية، ويبقى عدد القراءات محفوظًا.", color = p.muted, fontSize = 12.sp, lineHeight = 23.sp)
                if (problem == null) Surface(color = AdhkarSoftGreen, shape = RoundedCornerShape(14.dp)) {
                    Text(dhikrRuleSummary(edited), Modifier.padding(16.dp), color = AdhkarHeading, fontSize = 13.sp, lineHeight = 24.sp)
                }
                if (problem == null) {
                    // Previewed in the form the rule is stored in: a new rule does not replay a nudge that came before it.
                    val nextReminder = remember(edited) {
                        DhikrReminderScheduler.nextNudge(activity, repoState.storedForm(edited, System.currentTimeMillis()))
                    }
                    if (nextReminder != null) Text("التنبيه القادم: " + formatDhikrTime(nextReminder), color = AdhkarHeading,
                        fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.testTag("adhkar_next_nudge"))
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (collection != null && repoState.collectionEntries(collection).isEmpty()) {
                        Text("هذه المجموعة فارغة. لن تصلك تذكيراتها حتى تضيف إليها ذكرًا.",
                            color = MaterialTheme.colorScheme.error, fontSize = 13.sp, lineHeight = 24.sp)
                    }
                    // Re-read after returning from the system notification settings.
                    val selectedChannel = remember(resumeTick, vibrate) {
                        activity.getSystemService(NotificationManager::class.java)
                            .getNotificationChannel(DhikrReminderScheduler.channelId(vibrate))
                    }
                    val notificationsAvailable = remember(resumeTick, vibrate) {
                        DhikrReminderScheduler.notificationsEnabled(activity, vibrate)
                    }
                    Text(when {
                        !enabled -> "إشعارات هذا التذكير متوقفة."
                        selectedChannel == null || !notificationsAvailable ->
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
                // An untouched new form stays quiet; once edited, or after «حفظ», it says what is missing.
                (saveError ?: problem?.takeIf { saveAttempted || dirty || isExisting })?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 8.dp).testTag("adhkar_editor_error"))
                }
                Button(onClick = {
                    if (problem != null) saveAttempted = true else {
                        saving = true
                        onSave(edited) { error -> saving = false; saveError = error }
                    }
                },
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
        // A time picker restored with the activity has lost its listener and would set nothing.
        LaunchedEffect(Unit) {
            (activity.supportFragmentManager.findFragmentByTag(DHIKR_TIME_PICKER_TAG) as? DialogFragment)?.dismissAllowingStateLoss()
        }
        // Reported whatever the dhikr and the goal, which this dialog cannot change.
        val periodProblem = remember(edited) { DhikrReminderScheduler.periodError(activity, edited) }
        AlertDialog(onDismissRequest = { cancelInterval() },
            title = { Text(if (intervals.size == 1) "فترة التذكير" else "الفترة " + latinNumber(timeDialogIndex + 1)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TimeEndpoint(activity, "من", "وقت البداية", selected.start, Modifier.weight(1f)) {
                        replaceInterval(timeDialogIndex, selected.copy(start = it))
                    }
                    TimeEndpoint(activity, "إلى", "وقت النهاية", selected.end, Modifier.weight(1f)) {
                        replaceInterval(timeDialogIndex, selected.copy(end = it))
                    }
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
                if (selected.start.kind != DhikrTimeKind.FIXED) OffsetStepper("تقديم البداية أو تأخيرها", selected.start) {
                    replaceInterval(timeDialogIndex, selected.copy(start = it))
                }
                if (selected.end.kind != DhikrTimeKind.FIXED) OffsetStepper("تقديم النهاية أو تأخيرها", selected.end) {
                    replaceInterval(timeDialogIndex, selected.copy(end = it))
                }
                val selectedWindow = window?.let { next ->
                    DhikrReminderScheduler.resolveWindows(activity, edited, next.date)
                        .firstOrNull { it.intervalIndex == timeDialogIndex }
                }
                if (selectedWindow != null) Text(formatDhikrWindow(selectedWindow),
                    color = p.muted, fontSize = 12.sp, lineHeight = 22.sp)
                if (periodProblem != null) Text(periodProblem, color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp, lineHeight = 20.sp)
            } },
            confirmButton = { TextButton(onClick = {
                intervalDraft = encodeIntervalDraft(chronological(intervals))
                intervalBackup = null
                timeDialogIndex = -1
            }) { Text("تم") } },
            dismissButton = { Row {
                if (intervals.size > 1) TextButton(onClick = {
                    intervalDraft = encodeIntervalDraft(intervals.filterIndexed { index, _ -> index != timeDialogIndex })
                    intervalBackup = null
                    timeDialogIndex = -1
                }) { Text("حذف الفترة", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { cancelInterval() }) { Text("إلغاء") }
            } })
    }
    if (selectingDhikr) {
        var query by remember { mutableStateOf("") }
        val focus = LocalFocusManager.current
        val normalized = remember(query) { normalizeDhikrSearch(query) }
        val results = remember(knownEntries, normalized) {
            knownEntries.filter { normalized.isEmpty() || normalizeDhikrSearch(it.title + " " + it.text).contains(normalized) }
        }
        fun closePicker() { selectingDhikr = false; hundredAfterPick = false }
        AlertDialog(onDismissRequest = { closePicker() },
            title = { Text("اختر ذكرًا", color = AdhkarHeading, fontWeight = FontWeight.Bold) },
            text = { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(query, { query = it }, singleLine = true,
                    placeholder = { Text("ابحث في الأذكار...", fontSize = 14.sp) },
                    shape = RoundedCornerShape(16.dp), colors = fieldColors,
                    // «بحث» puts the keyboard away so the whole list shows.
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                    modifier = Modifier.fillMaxWidth().testTag("adhkar_reminder_dhikr_search"))
                if (results.isEmpty()) Text("لا توجد نتائج.", color = p.muted, fontSize = 14.sp,
                    modifier = Modifier.padding(vertical = 16.dp))
                else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp).testTag("adhkar_reminder_dhikr_results")) {
                    items(results, key = { it.id }) { entry ->
                        Row(Modifier.fillMaxWidth().clickable {
                            // A goal the user set stays; one that only followed the previous dhikr follows the new one.
                            val followed = collection != null || steppedEntry != null ||
                                integer(target) == (selectedEntry?.defaultCount ?: initial.targetCount)
                            selectedId = entry.id
                            collectionName = null
                            if (hundredAfterPick) {
                                daysText = (1..7).joinToString(",")
                                if (entry.steps.isEmpty()) target = "100"
                            } else if (entry.steps.isEmpty() && followed) target = entry.defaultCount.toString()
                            closePicker()
                        }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(entry.title, color = AdhkarHeading, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(remember(entry.id) { dhikrPreview(entry.text) }, color = p.muted, fontFamily = AdhkarReadingFont,
                                    fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (collection == null && entry.id == selectedId)
                                DhikrIcon(R.drawable.ic_adhkar_check, tint = p.primary, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            } },
            confirmButton = { TextButton(onClick = { closePicker() }) { Text("إغلاق") } })
    }
    pendingTemplate?.let { template ->
        AlertDialog(onDismissRequest = { pendingTemplate = null },
            title = { Text("استبدال الإعدادات؟") },
            text = { Text("سيستبدل هذا الخيار الجاهز الذكر والهدف والأيام والفترات وتكرار الإشعارات التي اخترتها.") },
            confirmButton = { TextButton(onClick = { pendingTemplate = null; applyTemplate(template) }) { Text("استبدال") } },
            dismissButton = { TextButton(onClick = { pendingTemplate = null }) { Text("إلغاء") } })
    }
    if (discardPrompt) AlertDialog(onDismissRequest = { discardPrompt = false },
        title = { Text("تجاهل التعديلات؟") },
        text = { Text("لم تُحفظ التعديلات على هذا التذكير.") },
        confirmButton = { TextButton(onClick = { discardPrompt = false; onDismiss() }) {
            Text("تجاهل", color = MaterialTheme.colorScheme.error)
        } },
        dismissButton = { TextButton(onClick = { discardPrompt = false }) { Text("متابعة التعديل") } })
    if (cadenceDialog) AlertDialog(onDismissRequest = { cadenceDialog = false }, title = { Text("تكرار الإشعارات") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val customInterval = integer(interval)
            // Typed here and applied only by «تعيين», so closing the dialog cannot leave an invalid interval.
            var intervalInput by remember { mutableStateOf(interval) }
            val inputInterval = integer(intervalInput)
            val inputValid = inputInterval != null && inputInterval in MIN_DHIKR_INTERVAL_MINUTES..1440
            val previewWindows = window?.let { DhikrReminderScheduler.resolveWindows(activity, edited, it.date) }
            // A full day's count, whatever the time of day the dialog is opened at.
            fun expected(value: DhikrReminder): String = previewWindows
                ?.let { dhikrNudgeTimes(value, it).size }
                ?.takeIf { it > 0 }?.let { " · إشعارات في اليوم: " + latinNumber(it) }.orEmpty()
            val options: List<Triple<String, Boolean, () -> Unit>> = listOf(
                Triple("مرة واحدة يوميًا" + expected(edited.copy(cadence = DhikrCadence.ONCE)),
                    cadence == DhikrCadence.ONCE.name) { cadence = DhikrCadence.ONCE.name },
                Triple("خفيف · " + regularCadenceLabel(3, intervals.size) + expected(edited.copy(cadence = DhikrCadence.GENTLE)),
                    cadence == DhikrCadence.GENTLE.name) { cadence = DhikrCadence.GENTLE.name },
                Triple("متوازن · " + regularCadenceLabel(5, intervals.size) + expected(edited.copy(cadence = DhikrCadence.BALANCED)),
                    cadence == DhikrCadence.BALANCED.name) { cadence = DhikrCadence.BALANCED.name },
                Triple("كل ساعة" + expected(edited.copy(cadence = DhikrCadence.HOURLY)),
                    cadence == DhikrCadence.HOURLY.name) { cadence = DhikrCadence.HOURLY.name },
            ) + listOf(15, 30, 45, 120, 180).map { minutes ->
                Triple(dhikrEveryMinutesLabel(minutes) + expected(edited.copy(cadence = DhikrCadence.CUSTOM, intervalMinutes = minutes)),
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
                OutlinedTextField(intervalInput, { if (it.length <= 4 && it.all(Char::isDigit)) intervalInput = it }, singleLine = true,
                    suffix = { Text("دقيقة", fontSize = 12.sp) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = intervalInput.isNotEmpty() && !inputValid,
                    shape = RoundedCornerShape(14.dp), colors = fieldColors,
                    modifier = Modifier.weight(1f).testTag("adhkar_interval_input"))
                TextButton(onClick = {
                    interval = inputInterval.toString()
                    cadence = DhikrCadence.CUSTOM.name; cadenceDialog = false
                }, enabled = inputValid) { Text("تعيين") }
            }
            Text("«مرة واحدة يوميًا» ترسل إشعارًا واحدًا عند بداية أول فترة. في الخيارين «خفيف» و«متوازن»، يصلك تذكير واحد على الأقل خلال كل فترة ما دام الهدف اليومي غير مكتمل. قد يزيد العدد عن 3 أو 5 إذا أضفت فترات أكثر. يمكنك اختيار فاصل من 15 دقيقة إلى 24 ساعة، وقد يؤخّر الهاتف بعض الإشعارات في وضع توفير البطارية.",
                color = p.muted, fontSize = 11.sp, lineHeight = 18.sp)
        } }, confirmButton = { TextButton(onClick = { cadenceDialog = false }) { Text("إغلاق") } })
    if (preview) AlertDialog(onDismissRequest = { preview = false }, title = { Text("معاينة الإشعار") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(collection?.let(::collectionTitle) ?: selectedEntry?.title ?: "اختر ذكرًا", fontWeight = FontWeight.Bold)
            Text(if (collection != null) "حان وقت قراءة الأذكار"
                else "حان وقت الذكر • 0 من " + latinNumber(edited.targetCount.coerceAtLeast(1)))
            Text("متابعة الذكر     ·     تم     ·     تأجيل", color = p.primary)
            Text("هذه معاينة فقط؛ لن يصلك إشعار. يظهر «تأجيل» إذا بقيت 35 دقيقة على الأقل قبل نهاية الفترة، ويؤخّر التذكير 30 دقيقة.",
                fontSize = 12.sp, color = p.muted)
        } }, confirmButton = { TextButton(onClick = { preview = false }) { Text("تم") } })
}

/** The same JSON the reminder is stored as, so equality survives the round trip. */
private val DhikrReminderSaver = Saver<DhikrReminder, String>(
    save = { it.toJson().toString() }, restore = { dhikrReminderFromJson(JSONObject(it)) })

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

/** «قبل الفجر بـ 30 د»: spelled out where it is chosen, so nothing signed has to be read or typed. */
private fun dhikrOffsetLabel(time: DhikrTime): String {
    val prayer = dhikrTimeLabel(time.copy(offsetMinutes = 0))
    return when {
        time.offsetMinutes == 0 -> "عند $prayer"
        time.offsetMinutes > 0 -> "بعد $prayer بـ " + latinNumber(time.offsetMinutes) + " د"
        else -> "قبل $prayer بـ " + latinNumber(-time.offsetMinutes) + " د"
    }
}

private fun regularCadenceLabel(limit: Int, intervalCount: Int): String =
    if (intervalCount > limit) "تذكير واحد لكل فترة" else "حتى " + latinNumber(limit) + " تذكيرات يوميًا"

private fun cadenceLabel(cadence: String, interval: String, intervalCount: Int): String = when (DhikrCadence.valueOf(cadence)) {
    DhikrCadence.ONCE -> "مرة واحدة يوميًا"
    DhikrCadence.GENTLE -> regularCadenceLabel(3, intervalCount)
    DhikrCadence.BALANCED -> regularCadenceLabel(5, intervalCount)
    DhikrCadence.HOURLY -> "كل ساعة"
    DhikrCadence.CUSTOM -> dhikrEveryMinutesLabel(parseDhikrInt(interval) ?: MIN_DHIKR_INTERVAL_MINUTES)
}

/** Accepts Arabic-Indic digits as well as Latin ones. */
private fun parseDhikrInt(value: String): Int? =
    value.trim().map { if (it.isDigit()) Character.digit(it, 10).digitToChar() else it }.joinToString("").toIntOrNull()

/** Small collections name their adhkar; they follow the user's own additions and removals. */
private fun collectionSubtitle(state: DhikrState, category: DhikrCategory): String {
    val titles = state.collectionEntries(category).map(DhikrEntry::title)
    return if (titles.size in 1..3) titles.joinToString(" · ") else "مجموعة كاملة"
}

/**
 * A one-hour period next to the existing ones rather than a fixed clock time, which would
 * overlap a period ending at a prayer for much of the year.
 */
private fun suggestedExtraInterval(intervals: List<DhikrInterval>): DhikrInterval {
    fun shift(time: DhikrTime, minutes: Int): DhikrTime? =
        if (time.kind == DhikrTimeKind.FIXED) (time.minuteOfDay + minutes).takeIf { it in 0..1439 }?.let { time.copy(minuteOfDay = it) }
        else (time.offsetMinutes + minutes).takeIf { it in -MAX_DHIKR_OFFSET_MINUTES..MAX_DHIKR_OFFSET_MINUTES }
            ?.let { time.copy(offsetMinutes = it) }
    val first = intervals.first()
    val last = intervals.last()
    val after = if (last.endNextDay != true) shift(last.end, 60)?.let { DhikrInterval(last.end, it) } else null
    return after ?: shift(first.start, -60)?.let { DhikrInterval(it, first.start) }
        ?: DhikrInterval(DhikrTime(minuteOfDay = 18 * 60), DhikrTime(minuteOfDay = 19 * 60))
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
private fun StepButton(icon: Int, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    val p = LocalAdhkarPalette.current
    Surface(onClick = onClick, enabled = enabled, shape = CircleShape, color = AdhkarSoftGreen, modifier = Modifier.size(48.dp)) {
        Box(contentAlignment = Alignment.Center) {
            DhikrIcon(icon, description, tint = p.primary.copy(alpha = if (enabled) 1f else .38f), modifier = Modifier.size(22.dp))
        }
    }
}

@Composable private fun FieldTitle(title: String, hint: String? = null) {
    val p = LocalAdhkarPalette.current
    Column {
        Text(title, color = AdhkarHeading, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        hint?.let { Text(it, color = p.muted, fontSize = 11.sp) }
    }
}

/** Earlier or later in five-minute steps: nothing signed to type, and no keyboard to cover the dialog. */
@Composable private fun OffsetStepper(title: String, time: DhikrTime, onChange: (DhikrTime) -> Unit) {
    val p = LocalAdhkarPalette.current
    var choosing by remember { mutableStateOf(false) }
    val offset = time.offsetMinutes
    fun set(minutes: Int) = onChange(time.copy(offsetMinutes = minutes.coerceIn(-MAX_DHIKR_OFFSET_MINUTES, MAX_DHIKR_OFFSET_MINUTES)))
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = p.muted, fontSize = 12.sp)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // The next multiple of five in each direction, so an older odd value steps cleanly too.
            StepButton(R.drawable.ic_add, "تأخير خمس دقائق", enabled = offset < MAX_DHIKR_OFFSET_MINUTES) {
                set(Math.floorDiv(offset, 5) * 5 + 5)
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                TextButton(onClick = { choosing = true }) {
                    Text(dhikrOffsetLabel(time), color = AdhkarHeading, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                        textAlign = TextAlign.Center)
                }
                DropdownMenu(choosing, onDismissRequest = { choosing = false }) {
                    listOf(-60, -45, -30, -15, 0, 15, 30, 45, 60, 90, 120).forEach { minutes ->
                        DropdownMenuItem(text = { Text(dhikrOffsetLabel(time.copy(offsetMinutes = minutes))) },
                            onClick = { set(minutes); choosing = false })
                    }
                }
            }
            StepButton(R.drawable.ic_remove, "تقديم خمس دقائق", enabled = offset > -MAX_DHIKR_OFFSET_MINUTES) {
                set(-(Math.floorDiv(-offset, 5) * 5 + 5))
            }
        }
    }
}

@Composable private fun TimeEndpoint(activity: AppCompatActivity, label: String, pickerTitle: String, value: DhikrTime,
    modifier: Modifier, onChange: (DhikrTime) -> Unit) {
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
                        onClick = {
                            // A clock time carries no adjustment; a prayer keeps the one already set.
                            onChange(value.copy(kind = kind, offsetMinutes = if (kind == DhikrTimeKind.FIXED) 0 else value.offsetMinutes))
                            choosing = false
                        })
                }
            }
        }
        if (value.kind == DhikrTimeKind.FIXED) TextButton(onClick = {
            showDhikrTimePicker(activity, pickerTitle, value.minuteOfDay) { onChange(value.copy(minuteOfDay = it)) }
        }, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
            DhikrIcon(R.drawable.ic_adhkar_clock, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp)); Text(dhikrTimeLabel(value.copy(offsetMinutes = 0)))
        }
    }
}

private const val DHIKR_TIME_PICKER_TAG = "adhkar_period_time"

/** The picker the wake editor uses, titled with the end being set; a second tap does not stack another. */
private fun showDhikrTimePicker(activity: AppCompatActivity, title: String, minuteOfDay: Int, onPicked: (Int) -> Unit) {
    val manager = activity.supportFragmentManager
    if (manager.isStateSaved || manager.findFragmentByTag(DHIKR_TIME_PICKER_TAG) != null) return
    val picker = MaterialTimePicker.Builder().setTimeFormat(TimeFormat.CLOCK_24H)
        .setHour(minuteOfDay / 60).setMinute(minuteOfDay % 60).setTitleText(title).build()
    picker.addOnPositiveButtonClickListener { onPicked(picker.hour * 60 + picker.minute) }
    picker.showNow(manager, DHIKR_TIME_PICKER_TAG)
}

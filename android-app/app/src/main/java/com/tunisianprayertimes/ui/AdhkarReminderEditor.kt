package com.tunisianprayertimes.ui

import android.app.NotificationManager
import androidx.activity.compose.BackHandler
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.tunisianprayertimes.R
import com.tunisianprayertimes.adhkar.*
import java.time.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
/**
 * [onSave] reports back a message when the save failed and null when it worked; the sheet then closes
 * itself and calls [onDismiss]. [onDirtyChange] tells the screen whether unsaved edits are at stake.
 */
@Composable internal fun DhikrReminderEditor(activity: AppCompatActivity, initial: DhikrReminder,
    exactAlarmsAvailable: Boolean, snackbar: SnackbarHostState, onDismiss: () -> Unit,
    onDirtyChange: (Boolean) -> Unit, onSave: (DhikrReminder, (String?) -> Unit) -> Unit) {
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
    // The dialogs are saved with the form: a re-created activity brings back the one that was open.
    var selectingDhikr by rememberSaveable(initial.id) { mutableStateOf(false) }
    var pickerQuery by rememberSaveable(initial.id) { mutableStateOf("") }
    // «100 مرة» needs a single dhikr: without one it opens the list and applies after the choice.
    var hundredAfterPick by rememberSaveable(initial.id) { mutableStateOf(false) }
    var pendingHundred by rememberSaveable(initial.id) { mutableStateOf(false) }
    var preview by rememberSaveable(initial.id) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var saveAttempted by rememberSaveable(initial.id) { mutableStateOf(false) }
    // Set once the save went through, so the sheet may hide although the form still differs from what it opened with.
    var closing by remember { mutableStateOf(false) }
    var cadenceDialog by rememberSaveable(initial.id) { mutableStateOf(false) }
    var intervalInput by rememberSaveable(initial.id) { mutableStateOf("") }
    var discardPrompt by rememberSaveable(initial.id) { mutableStateOf(false) }
    var pendingTemplate by rememberSaveable(initial.id, stateSaver = DhikrReminderOrNullSaver) { mutableStateOf<DhikrReminder?>(null) }
    // The interval draft as it was when the time dialog opened, so «إلغاء» can restore it.
    var intervalBackup by rememberSaveable(initial.id) { mutableStateOf<String?>(null) }
    // The end the clock picker is setting, as "period|start" or "period|end": a picker restored with the activity finds it again.
    var pickerTarget by rememberSaveable(initial.id) { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
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
    // Called by the picker, possibly long after this composition: it reads the draft as it is then.
    fun applyPickedTime(minute: Int) {
        val target = pickerTarget ?: return
        pickerTarget = null
        val periods = decodeIntervalDraft(intervalDraft).toMutableList()
        val index = target.substringBefore('|').toIntOrNull() ?: return
        val period = periods.getOrNull(index) ?: return
        periods[index] = if (target.endsWith("start")) period.copy(start = period.start.copy(minuteOfDay = minute))
            else period.copy(end = period.end.copy(minuteOfDay = minute))
        intervalDraft = encodeIntervalDraft(periods)
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
        // Only a custom cadence reads the interval: one tried and then abandoned leaves no trace in the rule.
        cadence = DhikrCadence.valueOf(cadence),
        intervalMinutes = if (cadence == DhikrCadence.CUSTOM.name) integer(interval) ?: 0 else initial.intervalMinutes,
        enabled = enabled, vibrate = vibrate)
    val problem = remember(edited) { DhikrReminderScheduler.validate(activity, edited) }
    LaunchedEffect(edited) { saveError = null }
    val periodProblem = remember(edited) { DhikrReminderScheduler.periodProblem(activity, edited) }
    // The periods alone, resolved even before a dhikr is chosen: the dialogs show their clock times and counts.
    val schedulePreview = remember(edited) {
        if (periodProblem == null) DhikrReminderScheduler.currentOrNextWindow(activity, edited, scheduleOnly = true) else null
    }
    val large = LocalConfiguration.current.screenHeightDp < 640 || LocalDensity.current.fontScale > 1.3f
    val storedRule = remember(initial.id) { repoState.reminders.firstOrNull { it.id == initial.id } }
    val isExisting = storedRule != null
    // Saved, so a re-created activity still knows what the form held when it opened.
    val original = rememberSaveable(initial.id, saver = DhikrReminderSaver) { edited }
    // Compared without the fields nothing reads, so a prayer end that passed through «وقت ثابت» is not an edit.
    val dirty = edited.normalized() != original.normalized()
    LaunchedEffect(dirty) { onDirtyChange(dirty) }
    DisposableEffect(Unit) { onDispose { onDirtyChange(false) } }
    // What the form held after the last ready-made option; later edits on top of it are the user's own.
    var templateBase by rememberSaveable(initial.id, stateSaver = DhikrReminderSaver) { mutableStateOf(edited) }
    var rebaseToken by remember { mutableIntStateOf(0) }
    val currentEdited by rememberUpdatedState(edited)
    LaunchedEffect(rebaseToken) { if (rebaseToken > 0) templateBase = currentEdited }
    // A form that opened with a dhikr or a collection already holds a choice a ready-made option would replace.
    val customized = edited.copy(enabled = templateBase.enabled, vibrate = templateBase.vibrate) != templateBase ||
        (templateBase == original && original.dhikrId.isNotEmpty())
    // On a blank new reminder the period follows the dhikr chosen, until the user sets the times or the cadence.
    var scheduleFollowsDhikr by rememberSaveable(initial.id) { mutableStateOf(!isExisting && initial.dhikrId.isEmpty()) }
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
        scheduleFollowsDhikr = false
        rebaseToken++
        // The option rewrites the fields above it: show them, rather than let the cards shift under the finger.
        scope.launch { formScroll.animateScrollTo(0) }
    }
    // A ready-made option replaces the whole schedule; ask first when that would drop the user's settings.
    fun requestTemplate(value: DhikrReminder) { if (customized) pendingTemplate = value else applyTemplate(value) }
    fun applyHundred() {
        target = "100"; daysText = (1..7).joinToString(",")
        scope.launch { formScroll.animateScrollTo(0) }
    }
    // The times a dhikr is usually said at, as the reader's own shortcut uses them, when it has one
    // such occasion; any other dhikr gets back the period and cadence the form opened with.
    fun followDhikr(category: DhikrCategory?, wholeCollection: Boolean) {
        if (!scheduleFollowsDhikr) return
        val suggested = if (category in dhikrTimedCategories) defaultDhikrReminder(selectedId, category, knownEntries, wholeCollection)
            else original
        intervalDraft = encodeIntervalDraft(suggested.intervals())
        cadence = suggested.cadence.name
    }
    // Listed in the order they happen, when the times can be resolved.
    fun chronological(list: List<DhikrInterval>): List<DhikrInterval> {
        val date = schedulePreview?.date ?: LocalDate.now()
        val starts = list.map { DhikrReminderScheduler.resolveTime(activity, it.start, date) }
        return if (starts.any { it == null }) list else list.indices.sortedBy { starts[it]!! }.map { list[it] }
    }
    var resumeTick by remember { mutableIntStateOf(0) }
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { resumeTick++; now = System.currentTimeMillis() }
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }
    // What the bar under the form announces keeps up with the clock while the sheet stays open.
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    // The rule as saving it now would store it: its next period and notification are the ones the user gets.
    val stored = remember(edited, now) {
        if (problem == null) DhikrReminderScheduler.storedForm(activity, repoState, edited, now) else null
    }
    val storedWindow = remember(stored, now) { stored?.let { DhikrReminderScheduler.currentOrNextWindow(activity, it, now) } }
    val nextNudge = remember(stored, now) {
        stored?.let { DhikrReminderScheduler.nextNudge(activity, it, now, ignoreNotificationAccess = true) }
    }
    val stopsRunningPeriod = remember(edited, now) {
        storedRule != null && storedRule.enabled && repoState.changesSchedule(edited) &&
            DhikrReminderScheduler.currentOrNextWindow(activity, storedRule, now)?.let { now >= it.startMillis } == true
    }
    // A clock picker restored with the activity has lost its listeners and lies under this sheet and its
    // dialogs, which are shown after it: it is replaced by a fresh one on top, at the time it had reached.
    var pickerShownHere by remember { mutableStateOf(false) }
    LaunchedEffect(pickerTarget) {
        val manager = activity.supportFragmentManager
        var picker = manager.findFragmentByTag(DHIKR_TIME_PICKER_TAG) as? MaterialTimePicker
        val target = pickerTarget
        if (picker != null && !pickerShownHere && !manager.isStateSaved) {
            val reached = picker.hour * 60 + picker.minute
            manager.beginTransaction().remove(picker).commitNow()
            picker = if (target == null) null else showDhikrTimePicker(activity,
                if (target.endsWith("start")) "وقت البداية" else "وقت النهاية", reached)
            pickerShownHere = picker != null
        }
        if (picker == null) pickerTarget = null else if (target != null) {
            val shown = picker
            shown.clearOnPositiveButtonClickListeners(); shown.clearOnNegativeButtonClickListeners()
            shown.clearOnCancelListeners()
            shown.addOnPositiveButtonClickListener { applyPickedTime(shown.hour * 60 + shown.minute) }
            shown.addOnNegativeButtonClickListener { pickerTarget = null }
            shown.addOnCancelListener { pickerTarget = null }
        }
    }
    val blockHide by rememberUpdatedState(saving || dirty)
    // Swipe, scrim and handle taps pass through here: never hide mid-save or over unsaved edits.
    val confirmSheetValue = remember { { value: SheetValue ->
        if (value != SheetValue.Hidden || closing || !blockHide) true
        else { if (!saving) discardPrompt = true; false }
    } }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = confirmSheetValue)
    fun requestClose() {
        if (saving || closing) return
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
                // After a refused «حفظ» the card that still needs a choice is outlined, and the form shows it.
                val dhikrMissing = saveAttempted && collection == null && selectedEntry == null
                AdhkarCard(Modifier.fillMaxWidth().testTag("adhkar_reminder_dhikr").then(if (!dhikrMissing) Modifier
                    else Modifier.border(1.5.dp, MaterialTheme.colorScheme.error, RoundedCornerShape(18.dp))),
                    onClick = { selectingDhikr = true }) {
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
                            Text(collection?.let(::reminderCollectionTitle) ?: selectedEntry?.title ?: "اختر ذكرًا",
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
                Text(if (showTarget) "الهدف وأوقات التذكير" else "أوقات التذكير",
                    color = AdhkarHeading, fontWeight = FontWeight.Bold, fontSize = 17.sp)
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
                                        value = target, onValueChange = { typed -> latinDigits(typed)?.takeIf { it.length <= 6 }?.let { target = it } },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        // A number reads left to right: the caret then sits where the next digit goes.
                                        textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center, textDirection = TextDirection.Ltr,
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
                            // The period a schedule problem concerns is the one shown in the error colour.
                            SettingRow(R.drawable.ic_adhkar_clock,
                                if (intervals.size == 1) "فترة التذكير" else "الفترة " + latinNumber(index + 1),
                                dhikrIntervalLabel(value),
                                error = periodProblem != null && (intervals.size == 1 || index in periodProblem.periods)) { openInterval(index) }
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
                        SettingRow(R.drawable.ic_adhkar_bell, "تكرار الإشعارات", cadenceLabel(cadence, interval, intervals.size)) {
                            intervalInput = interval; cadenceDialog = true
                        }
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
                        ToggleRow("إشعارات التذكير", "تلقّي الإشعارات خلال الفترات المحددة", enabled) { enabled = it }
                        HorizontalDivider(color = AdhkarBorder)
                        ToggleRow("الاهتزاز", "اهتزاز عند وصول الإشعار", vibrate) { vibrate = it }
                        HorizontalDivider(color = AdhkarBorder)
                        // There is nothing to preview until the reminder has something to announce.
                        TextButton(onClick = { preview = true }, enabled = collection != null || selectedEntry != null,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) { Text("معاينة الإشعار") }
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
                            when {
                                collection != null || selectedEntry == null -> { hundredAfterPick = true; selectingDhikr = true }
                                // Ask before dropping days the user picked or a goal that is their own.
                                customized && (days.size != 7 || integer(target) !in setOf(100, selectedEntry?.defaultCount)) ->
                                    pendingHundred = true
                                else -> applyHundred()
                            }
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
                if (stopsRunningPeriod) Text("حفظ هذا التعديل يوقف إشعارات الفترة الحالية، وتسري الإعدادات الجديدة ابتداءً من الفترة التالية.",
                    color = p.muted, fontSize = 12.sp, lineHeight = 23.sp)
                if (problem == null) Surface(color = AdhkarSoftGreen, shape = RoundedCornerShape(14.dp)) {
                    Text(dhikrRuleSummary(edited), Modifier.padding(16.dp), color = AdhkarHeading, fontSize = 13.sp, lineHeight = 24.sp)
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (collection != null && repoState.collectionEntries(collection).isEmpty()) {
                        Text("هذه المجموعة فارغة. لن تصلك إشعاراتها حتى تضيف إليها ذكرًا.",
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
                            "الإشعارات غير مسموح بها لهذا التطبيق حاليًا، فلن يصلك إشعار حتى تسمح بها. يمكنك حفظ التذكير، وسيُطلب منك السماح بعد الحفظ."
                        selectedChannel.importance < NotificationManager.IMPORTANCE_HIGH ->
                            "قد لا يظهر الإشعار منبثقًا لأن أولوية إشعارات الأذكار منخفضة."
                        vibrate && !selectedChannel.shouldVibrate() -> "الاهتزاز معطّل في إعدادات إشعارات الأذكار."
                        !vibrate && selectedChannel.shouldVibrate() -> "الاهتزاز مفعّل في إعدادات إشعارات الأذكار رغم إيقافه هنا."
                        vibrate -> "إشعار مع اهتزاز دون صوت، وفق إعدادات الهاتف."
                        else -> "إشعار دون صوت أو اهتزاز، وفق إعدادات الهاتف."
                    }, color = p.muted, fontSize = 13.sp, lineHeight = 24.sp)
                    TextButton(onClick = { openDhikrNotificationSettings(activity, vibrate) }) { Text("إعدادات إشعارات الأذكار") }
                    if (enabled && !exactAlarmsAvailable) {
                        Text("لم تُفعّل «المنبّهات والتذكيرات». قد يصلك الإشعار متأخرًا، وقد لا يصلك إذا انتهت الفترة قبل وصوله.",
                            color = MaterialTheme.colorScheme.error, fontSize = 13.sp, lineHeight = 24.sp)
                        TextButton(onClick = { openDhikrExactAlarmSettings(activity) }) { Text("تفعيل المنبّهات والتذكيرات") }
                    }
                }
            }
            // Messages raised while this sheet covers the page show here, above the bar.
            SnackbarHost(snackbar, Modifier.padding(horizontal = 12.dp))
            HorizontalDivider(color = AdhkarBorder)
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 16.dp)) {
                // An untouched new form stays quiet; once edited, or after «حفظ», it says what is missing.
                val error = saveError ?: problem?.takeIf { saveAttempted || dirty || isExisting }
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 8.dp).testTag("adhkar_editor_error")
                        .semantics { liveRegion = LiveRegionMode.Polite })
                // A valid form says what saving gives, where it can be read without scrolling; the keyboard needs the room.
                else if (storedWindow != null && !WindowInsets.isImeVisible) Column(Modifier.padding(bottom = 8.dp)) {
                    Text((if (now in storedWindow.startMillis until storedWindow.endMillis) "الفترة الحالية: " else "الفترة القادمة: ") +
                        formatDhikrWindow(storedWindow), color = p.muted, fontSize = 12.sp, lineHeight = 20.sp)
                    if (nextNudge != null) Text("الإشعار القادم: " + formatDhikrTime(nextNudge, now), color = AdhkarHeading,
                        fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 20.sp,
                        modifier = Modifier.testTag("adhkar_next_nudge"))
                }
                Button(onClick = {
                    if (problem != null) {
                        saveAttempted = true
                        if (collection == null && selectedEntry == null) scope.launch { formScroll.animateScrollTo(0) }
                    } else {
                        saving = true
                        onSave(edited) { failure ->
                            saving = false
                            if (failure != null) saveError = failure else {
                                closing = true
                                scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                            }
                        }
                    }
                },
                    enabled = !saving && !closing, shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("adhkar_save_reminder")) {
                    DhikrIcon(R.drawable.ic_adhkar_check, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (saving) "جارٍ الحفظ…" else "حفظ التذكير", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
    intervals.getOrNull(timeDialogIndex)?.let { selected ->
        val previewDate = schedulePreview?.date ?: LocalDate.now()
        // A prayer turned into a clock time starts from where that prayer falls, to the nearest five
        // minutes, rather than from a minute the user never chose.
        fun clockMinute(time: DhikrTime): Int? = DhikrReminderScheduler.resolveTime(activity, time, previewDate)?.let { millis ->
            val clock = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime()
            ((clock.hour * 60 + clock.minute + 2) / 5 * 5).coerceIn(0, 1435)
        }
        fun pickTime(end: String, title: String, time: DhikrTime) {
            if (showDhikrTimePicker(activity, title, time.minuteOfDay) == null) return
            pickerShownHere = true
            pickerTarget = "$timeDialogIndex|$end"
        }
        // Only «تم», «إلغاء» and «حذف الفترة» close it: a stray tap outside must not undo the times just set.
        AlertDialog(onDismissRequest = { cancelInterval() }, properties = DialogProperties(dismissOnClickOutside = false),
            title = { Text(if (intervals.size == 1) "فترة التذكير" else "الفترة " + latinNumber(timeDialogIndex + 1)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TimeEndpoint("من", selected.start, Modifier.weight(1f), ::clockMinute,
                        onPickTime = { pickTime("start", "وقت البداية", selected.start) }) {
                        replaceInterval(timeDialogIndex, selected.copy(start = it))
                    }
                    TimeEndpoint("إلى", selected.end, Modifier.weight(1f), ::clockMinute,
                        onPickTime = { pickTime("end", "وقت النهاية", selected.end) }) {
                        replaceInterval(timeDialogIndex, selected.copy(end = it))
                    }
                }
                // What the choice comes to, and what is wrong with it, stay next to the two ends: seen without scrolling.
                val selectedWindow = schedulePreview?.let { next ->
                    DhikrReminderScheduler.resolveWindows(activity, edited, next.date, scheduleOnly = true)
                        .firstOrNull { it.intervalIndex == timeDialogIndex }
                }
                if (selectedWindow != null) Text(formatDhikrWindow(selectedWindow),
                    color = p.muted, fontSize = 12.sp, lineHeight = 22.sp)
                // Reported whatever the dhikr and the goal, which this dialog cannot change.
                if (periodProblem != null) Text(periodProblem.message, color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().toggleable(selected.endNextDay == true, role = Role.Checkbox) {
                        replaceInterval(timeDialogIndex, selected.copy(endNextDay = it))
                    }) {
                    Checkbox(selected.endNextDay == true, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                    Text("تنتهي في اليوم التالي", color = AdhkarHeading, fontSize = 13.sp)
                }
                if (selected.start.kind != DhikrTimeKind.FIXED) OffsetStepper("تقديم البداية أو تأخيرها", selected.start) {
                    replaceInterval(timeDialogIndex, selected.copy(start = it))
                }
                if (selected.end.kind != DhikrTimeKind.FIXED) OffsetStepper("تقديم النهاية أو تأخيرها", selected.end) {
                    replaceInterval(timeDialogIndex, selected.copy(end = it))
                }
            } },
            confirmButton = { TextButton(onClick = {
                if (intervalDraft != intervalBackup) scheduleFollowsDhikr = false
                intervalDraft = encodeIntervalDraft(chronological(intervals))
                intervalBackup = null
                timeDialogIndex = -1
            }) { Text("تم") } },
            dismissButton = { Row {
                if (intervals.size > 1) TextButton(onClick = {
                    intervalDraft = encodeIntervalDraft(intervals.filterIndexed { index, _ -> index != timeDialogIndex })
                    intervalBackup = null
                    timeDialogIndex = -1
                    scheduleFollowsDhikr = false
                }) { Text("حذف الفترة", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { cancelInterval() }) { Text("إلغاء") }
            } })
    }
    if (selectingDhikr) {
        val focus = LocalFocusManager.current
        val normalized = remember(pickerQuery) { normalizeDhikrSearch(pickerQuery) }
        // The same text the library searches, so the occasion a dhikr belongs to finds it here too.
        val results = remember(knownEntries, normalized) {
            knownEntries.filter { normalized.isEmpty() || normalizeDhikrSearch(dhikrSearchText(it)).contains(normalized) }
        }
        // A whole collection is a choice like any dhikr; «100 مرة» is a goal, which a collection does not have.
        val collections = remember(normalized, hundredAfterPick) {
            if (hundredAfterPick) emptyList() else reminderCollectionChoices.filter {
                normalized.isEmpty() || normalizeDhikrSearch(reminderCollectionTitle(it)).contains(normalized)
            }
        }
        // Opens on the current choice rather than at the top of a hundred entries.
        val listState = rememberLazyListState(remember {
            (if (collection != null) collections.indexOf(collection)
                else results.indexOfFirst { it.id == selectedId }.let { if (it < 0) it else it + collections.size }).coerceAtLeast(0)
        })
        // A new search starts from its first match, not from where the previous list stood.
        LaunchedEffect(normalized) { if (normalized.isNotEmpty()) listState.scrollToItem(0) }
        fun closePicker() { selectingDhikr = false; hundredAfterPick = false; pickerQuery = "" }
        AlertDialog(onDismissRequest = { closePicker() },
            title = { Text("اختر ذكرًا", color = AdhkarHeading, fontWeight = FontWeight.Bold) },
            text = { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(pickerQuery, { pickerQuery = it }, singleLine = true,
                    placeholder = { Text("ابحث في الأذكار...", fontSize = 14.sp) },
                    shape = RoundedCornerShape(16.dp), colors = fieldColors,
                    // «بحث» puts the keyboard away so the whole list shows.
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                    modifier = Modifier.fillMaxWidth().testTag("adhkar_reminder_dhikr_search"))
                if (results.isEmpty() && collections.isEmpty()) Text("لا توجد نتائج.", color = p.muted, fontSize = 14.sp,
                    modifier = Modifier.padding(vertical = 16.dp))
                // The list takes whatever height the dialog has left, on a short screen as on a tall one.
                else LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).testTag("adhkar_reminder_dhikr_results"), listState) {
                    items(collections, key = { "collection_" + it.name }) { category ->
                        PickerRow(reminderCollectionTitle(category), collectionSubtitle(repoState, category), null,
                            selected = collection == category) {
                            collectionName = category.name
                            selectedId = collectionRepresentative(category)
                            followDhikr(category, wholeCollection = true)
                            closePicker()
                        }
                    }
                    items(results, key = { it.id }) { entry ->
                        PickerRow(entry.title, remember(entry.id) { dhikrPreview(entry.text) }, AdhkarReadingFont,
                            selected = collection == null && entry.id == selectedId) {
                            // A goal the user set stays; one that only followed the previous dhikr, the reading it
                            // was opened from or a ready-made option follows the new one.
                            val followed = collection != null || steppedEntry != null ||
                                integer(target) == (selectedEntry?.defaultCount ?: initial.targetCount) ||
                                integer(target) == templateBase.targetCount
                            selectedId = entry.id
                            collectionName = null
                            if (hundredAfterPick) {
                                if (entry.steps.isEmpty()) target = "100"
                                // The days are the user's own once chosen: the same question as on the card itself.
                                if (customized && days.size != 7) pendingHundred = true else daysText = (1..7).joinToString(",")
                            } else if (entry.steps.isEmpty() && followed) target = entry.defaultCount.toString()
                            // A dhikr said on several occasions has no one time of its own.
                            followDhikr(entry.takeUnless { it.custom }?.categories?.filter { it in dhikrTimedCategories }?.singleOrNull(),
                                wholeCollection = false)
                            closePicker()
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
    if (pendingHundred) AlertDialog(onDismissRequest = { pendingHundred = false },
        title = { Text("استبدال الهدف والأيام؟") },
        text = { Text("سيجعل هذا الخيار الهدف 100 والتذكير في كل أيام الأسبوع.") },
        confirmButton = { TextButton(onClick = { pendingHundred = false; applyHundred() }) { Text("استبدال") } },
        dismissButton = { TextButton(onClick = { pendingHundred = false }) { Text("إلغاء") } })
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
            val inputInterval = integer(intervalInput)
            val inputValid = inputInterval != null && inputInterval in MIN_DHIKR_INTERVAL_MINUTES..1440
            fun choose(value: DhikrCadence, minutes: Int? = null) {
                cadence = value.name
                if (minutes != null) interval = minutes.toString()
                scheduleFollowsDhikr = false; cadenceDialog = false
            }
            // First in the dialog: the keyboard then leaves the field and its button in view instead of
            // pushing the whole dialog off the top of the screen.
            Text("تحديد الفاصل بالدقائق", color = AdhkarHeading, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            val rangeHint: (@Composable () -> Unit)? =
                if (intervalInput.isNotEmpty() && !inputValid) ({ Text("اختر فاصلًا بين 15 و1440 دقيقة.") }) else null
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(intervalInput, { typed -> latinDigits(typed)?.takeIf { it.length <= 4 }?.let { intervalInput = it } },
                    singleLine = true, suffix = { Text("دقيقة", fontSize = 12.sp) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr, textAlign = TextAlign.End),
                    isError = intervalInput.isNotEmpty() && !inputValid, supportingText = rangeHint,
                    shape = RoundedCornerShape(14.dp), colors = fieldColors,
                    modifier = Modifier.weight(1f).testTag("adhkar_interval_input"))
                // One hour is the «كل ساعة» option, so the list below marks it as chosen.
                TextButton(onClick = {
                    if (inputInterval == 60) choose(DhikrCadence.HOURLY) else choose(DhikrCadence.CUSTOM, inputInterval)
                }, enabled = inputValid) { Text("تعيين") }
            }
            HorizontalDivider(color = AdhkarBorder, modifier = Modifier.padding(vertical = 8.dp))
            val previewWindows = schedulePreview?.let { DhikrReminderScheduler.resolveWindows(activity, edited, it.date, scheduleOnly = true) }
            // A full day's count, whatever the time of day the dialog is opened at.
            fun expected(value: DhikrReminder): String = previewWindows
                ?.let { dhikrNudgeTimes(value, it).size }
                ?.takeIf { it > 0 }?.let { " · المتوقع في اليوم: " + latinNumber(it) }.orEmpty()
            val options: List<Triple<String, Boolean, () -> Unit>> = listOf(
                Triple("إشعار واحد يوميًا" + expected(edited.copy(cadence = DhikrCadence.ONCE)),
                    cadence == DhikrCadence.ONCE.name) { choose(DhikrCadence.ONCE) },
                Triple("خفيف · " + regularCadenceLabel(3, intervals.size) + expected(edited.copy(cadence = DhikrCadence.GENTLE)),
                    cadence == DhikrCadence.GENTLE.name) { choose(DhikrCadence.GENTLE) },
                Triple("متوازن · " + regularCadenceLabel(5, intervals.size) + expected(edited.copy(cadence = DhikrCadence.BALANCED)),
                    cadence == DhikrCadence.BALANCED.name) { choose(DhikrCadence.BALANCED) },
                Triple("كل ساعة" + expected(edited.copy(cadence = DhikrCadence.HOURLY)),
                    cadence == DhikrCadence.HOURLY.name) { choose(DhikrCadence.HOURLY) },
            ) + listOf(15, 30, 45, 120, 180).map { minutes ->
                Triple(dhikrEveryMinutesLabel(minutes) + expected(edited.copy(cadence = DhikrCadence.CUSTOM, intervalMinutes = minutes)),
                    cadence == DhikrCadence.CUSTOM.name && customInterval == minutes) { choose(DhikrCadence.CUSTOM, minutes) }
            }
            options.forEach { (label, selected, apply) ->
                Row(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = apply).padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f), color = AdhkarHeading, fontSize = 14.sp)
                    if (selected) DhikrIcon(R.drawable.ic_adhkar_check, tint = p.primary, modifier = Modifier.size(18.dp))
                }
            }
            Text("«إشعار واحد يوميًا» يُرسَل عند بداية أول فترة. في الخيارين «خفيف» و«متوازن» يصلك إشعار واحد على الأقل خلال كل فترة " +
                (if (collection != null) "ما دامت قراءة المجموعة لم تكتمل" else "ما دام الهدف اليومي لم يكتمل") +
                "، وقد يزيد العدد عن 3 أو 5 إذا أضفت فترات أكثر. يمكنك اختيار فاصل من 15 دقيقة إلى 24 ساعة، وقد يؤخّر الهاتف بعض الإشعارات في وضع توفير البطارية.",
                color = p.muted, fontSize = 11.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 8.dp))
        } }, confirmButton = { TextButton(onClick = { cadenceDialog = false }) { Text("إغلاق") } })
    if (preview) AlertDialog(onDismissRequest = { preview = false }, title = { Text("معاينة الإشعار") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(collection?.let(::collectionTitle) ?: selectedEntry?.title.orEmpty(), fontWeight = FontWeight.Bold)
            Text(if (collection != null) "حان وقت قراءة الأذكار"
                else "حان وقت الذكر • 0 من " + latinNumber(edited.targetCount.coerceAtLeast(1)))
            Text("متابعة الذكر     ·     تم", color = p.primary)
            Text("هذه معاينة فقط، ولن يُرسَل إشعار الآن. «تم» ينهي تذكير اليوم.",
                fontSize = 12.sp, color = p.muted)
        } }, confirmButton = { TextButton(onClick = { preview = false }) { Text("إغلاق") } })
}

/** Saved empty when there is none. */
internal val DhikrReminderOrNullSaver = Saver<DhikrReminder?, String>(
    save = { it?.toJson()?.toString().orEmpty() },
    restore = { if (it.isEmpty()) null else dhikrReminderFromJson(JSONObject(it)) })

/** The daily collections a reminder can cover as a whole, in the order the day runs. */
private val reminderCollectionChoices = listOf(DhikrCategory.MORNING, DhikrCategory.EVENING, DhikrCategory.NIGHT)
/** Collections whose adhkar are said at a time of their own; a dhikr of any other keeps the form's period. */
private val dhikrTimedCategories = setOf(DhikrCategory.MORNING, DhikrCategory.EVENING, DhikrCategory.NIGHT,
    DhikrCategory.SALAH, DhikrCategory.SLEEP)

/** Digits as typed on any keyboard, shown and stored as Latin ones; null when anything else was typed. */
private fun latinDigits(value: String): String? =
    if (value.all(Char::isDigit)) value.map { Character.digit(it, 10).digitToChar() }.joinToString("") else null

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
    if (intervalCount > limit) "إشعار واحد لكل فترة" else "حتى " + latinNumber(limit) + " إشعارات يوميًا"

private fun cadenceLabel(cadence: String, interval: String, intervalCount: Int): String = when (DhikrCadence.valueOf(cadence)) {
    DhikrCadence.ONCE -> "إشعار واحد يوميًا"
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
private fun SettingRow(icon: Int, title: String, value: String, error: Boolean = false, onClick: () -> Unit) {
    val p = LocalAdhkarPalette.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        DhikrIcon(icon, tint = p.primary, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = AdhkarHeading, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(value, color = if (error) MaterialTheme.colorScheme.error else p.muted, fontSize = 12.sp)
        }
        DhikrIcon(R.drawable.ic_adhkar_next, tint = p.muted, modifier = Modifier.size(18.dp))
    }
}

/** One control for the screen reader: the whole row toggles and says its title with its state. */
@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalAdhkarPalette.current
    Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onChange)
        .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = AdhkarHeading, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(subtitle, color = p.muted, fontSize = 12.sp)
        }
        AdhkarSwitch(checked, onCheckedChange = null)
    }
}

/** One choice of the dhikr picker; the check mark is said by the screen reader as the selected state. */
@Composable
private fun PickerRow(title: String, subtitle: String, subtitleFont: FontFamily?, selected: Boolean, onClick: () -> Unit) {
    val p = LocalAdhkarPalette.current
    Row(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = AdhkarHeading, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = p.muted, fontFamily = subtitleFont, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected) DhikrIcon(R.drawable.ic_adhkar_check, tint = p.primary, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun TemplateCard(title: String, subtitle: String, icon: Int?, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = LocalAdhkarPalette.current
    Box(modifier) {
        Surface(selected = selected, onClick = onClick, shape = RoundedCornerShape(16.dp),
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
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
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
        // Named for what they do to the time: a «+» would make «قبل الفجر بـ 30 د» count down.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // The next multiple of five in each direction, so an older odd value steps cleanly too.
            TextButton(onClick = { set(-(Math.floorDiv(-offset, 5) * 5 + 5)) }, enabled = offset > -MAX_DHIKR_OFFSET_MINUTES,
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.semantics { contentDescription = "تقديم خمس دقائق" }) { Text("تقديم", fontSize = 13.sp) }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                // Outlined and marked like a menu, because it is one: the usual offsets in one tap.
                OutlinedButton(onClick = { choosing = true }, shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                    Text(dhikrOffsetLabel(time) + " ▾", color = AdhkarHeading, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                        textAlign = TextAlign.Center)
                }
                DropdownMenu(choosing, onDismissRequest = { choosing = false }) {
                    listOf(-60, -45, -30, -15, 0, 15, 30, 45, 60, 90, 120).forEach { minutes ->
                        DropdownMenuItem(text = { Text(dhikrOffsetLabel(time.copy(offsetMinutes = minutes))) },
                            onClick = { set(minutes); choosing = false })
                    }
                }
            }
            TextButton(onClick = { set(Math.floorDiv(offset, 5) * 5 + 5) }, enabled = offset < MAX_DHIKR_OFFSET_MINUTES,
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.semantics { contentDescription = "تأخير خمس دقائق" }) { Text("تأخير", fontSize = 13.sp) }
        }
    }
}

/** [clockMinute] gives the minute of the day a prayer-relative time falls at, for a clock time to start from. */
@Composable private fun TimeEndpoint(label: String, value: DhikrTime, modifier: Modifier, clockMinute: (DhikrTime) -> Int?,
    onPickTime: () -> Unit, onChange: (DhikrTime) -> Unit) {
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
                            onChange(when {
                                kind == value.kind -> value
                                // A clock time carries no adjustment and starts from where the prayer falls.
                                kind == DhikrTimeKind.FIXED -> DhikrTime(kind, clockMinute(value) ?: value.minuteOfDay)
                                // A prayer keeps the adjustment already set and no clock minute: it never reads one.
                                else -> DhikrTime(kind, offsetMinutes = value.offsetMinutes)
                            })
                            choosing = false
                        })
                }
            }
        }
        if (value.kind == DhikrTimeKind.FIXED) TextButton(onClick = onPickTime,
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
            DhikrIcon(R.drawable.ic_adhkar_clock, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp)); Text(dhikrTimeLabel(value.copy(offsetMinutes = 0)))
        }
    }
}

private const val DHIKR_TIME_PICKER_TAG = "adhkar_period_time"

/**
 * The picker the wake editor uses, titled with the end being set; a second tap does not stack another.
 * The editor attaches the listeners, so that a picker brought back with the activity gets them too.
 * Null when it could not be shown.
 */
private fun showDhikrTimePicker(activity: AppCompatActivity, title: String, minuteOfDay: Int): MaterialTimePicker? {
    val manager = activity.supportFragmentManager
    if (manager.isStateSaved || manager.findFragmentByTag(DHIKR_TIME_PICKER_TAG) != null) return null
    return MaterialTimePicker.Builder().setTimeFormat(TimeFormat.CLOCK_24H).setTheme(R.style.ThemeOverlay_Adhkar_TimePicker)
        .setHour(minuteOfDay / 60).setMinute(minuteOfDay % 60).setTitleText(title).build()
        .also { it.showNow(manager, DHIKR_TIME_PICKER_TAG) }
}

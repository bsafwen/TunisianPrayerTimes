package com.tunisianprayertimes.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tunisianprayertimes.R
import com.tunisianprayertimes.adhkar.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AdhkarScreen(activity: AppCompatActivity, requestedReminderId: String? = null,
    requestedReminderSequence: Int = 0, requestedOccurrenceId: String? = null, modifier: Modifier = Modifier) {
    remember(activity) { DhikrReminderScheduler.ensureChannel(activity) }
    val repo = remember(activity) { DhikrRepository(activity) }
    val state by repo.state.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf("today") }
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var libraryTab by rememberSaveable { mutableStateOf("all") }
    var readerId by rememberSaveable { mutableStateOf<String?>(null) }
    // A reminder can open an independent reading outside its scheduled window.
    // Keep that origin paired with the session so the reader can manage the saved rule.
    var readerReminderSource by rememberSaveable { mutableStateOf<String?>(null) }
    var showReminders by rememberSaveable { mutableStateOf(false) }
    var customDraft by remember { mutableStateOf<DhikrEntry?>(null) }
    var customEditor by remember { mutableStateOf(false) }
    var collectionEdit by remember { mutableStateOf<DhikrEntry?>(null) }
    var addToCollectionSessionId by remember { mutableStateOf<String?>(null) }
    var reorderCollectionSessionId by remember { mutableStateOf<String?>(null) }
    var draft by rememberSaveable(saver = androidx.compose.runtime.saveable.Saver<MutableState<DhikrReminder?>, String>(
        save = { it.value?.toJson()?.toString() ?: "" },
        restore = { mutableStateOf(if (it.isEmpty()) null else dhikrReminderFromJson(org.json.JSONObject(it))) },
    )) { mutableStateOf<DhikrReminder?>(null) }
    var draftDirty by remember { mutableStateOf(false) }
    // A reminder asked for while another is being edited: it waits for the answer about the unsaved changes.
    var pendingDraft by rememberSaveable(stateSaver = DhikrReminderOrNullSaver) { mutableStateOf<DhikrReminder?>(null) }
    // The reminder just saved, for the reminders sheet to bring into view.
    var savedReminderId by remember { mutableStateOf<String?>(null) }
    // Saved, so the prompts still follow one another when a settings screen re-creates the activity in between.
    var permissionPrompt by rememberSaveable { mutableStateOf(false) }
    var permissionPromptVibrate by rememberSaveable { mutableStateOf(true) }
    var exactAlarmPrompt by rememberSaveable { mutableStateOf(false) }
    var exactAlarmPromptAfterNotifications by rememberSaveable { mutableStateOf(false) }
    // Answered «لاحقًا» once: the warning in the editor and the banner in the sheet go on saying it.
    var exactAlarmPromptDeclined by rememberSaveable { mutableStateOf(false) }
    var exactAlarmsAvailable by remember { mutableStateOf(DhikrReminderScheduler.exactAlarmsEnabled(activity)) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val collectionPeriodKeys by produceState<Map<DhikrCategory, String>>(emptyMap(), activity, now) {
        value = withContext(Dispatchers.IO) {
            listOf(DhikrCategory.MORNING, DhikrCategory.EVENING).associateWith { category ->
                collectionReadingPeriodKey(activity, category, null, now)
            }
        }
    }
    var focusSearch by remember { mutableStateOf(false) }
    var consumedRequest by rememberSaveable { mutableIntStateOf(-1) }
    val scope = rememberCoroutineScope()
    val mutex = remember { Mutex() }
    val snackbar = remember { SnackbarHostState() }
    val homeScroll = rememberLazyListState()
    val libraryScroll = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val keyboardFocus = LocalFocusManager.current
    val lifecycle = LocalLifecycleOwner.current
    fun mutate(action: () -> Unit, onFailure: (Throwable) -> Unit = {
        scope.launch { snackbar.showSnackbar("تعذّر حفظ التغيير. حاول مرة أخرى.") }
    }, after: () -> Unit = {}) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val error = runCatching { withContext(NonCancellable) { mutex.withLock { withContext(Dispatchers.IO) { action() } } } }.exceptionOrNull()
            if (isActive) { if (error != null) onFailure(error) else after() }
        }
    }
    fun promptForExactAlarms() {
        if (!exactAlarmPromptDeclined && !DhikrReminderScheduler.exactAlarmsEnabled(activity)) exactAlarmPrompt = true
    }
    fun promptForReminderAccess(rule: DhikrReminder) {
        if (!rule.enabled) return
        if (!DhikrReminderScheduler.notificationsEnabled(activity, rule.vibrate)) {
            permissionPromptVibrate = rule.vibrate
            permissionPrompt = true
            exactAlarmPromptAfterNotifications = true
        } else promptForExactAlarms()
    }
    // Every way out of the notification prompt ends here. Exact alarms only matter once a notification can be shown.
    fun afterNotificationPrompt() {
        if (!exactAlarmPromptAfterNotifications) return
        exactAlarmPromptAfterNotifications = false
        if (DhikrReminderScheduler.notificationsEnabled(activity, permissionPromptVibrate)) promptForExactAlarms()
    }
    // Opening another reminder while one is being edited would drop its unsaved changes: ask first.
    fun requestDraft(rule: DhikrReminder) {
        if (draft != null && draft?.id != rule.id && draftDirty) pendingDraft = rule else draft = rule
    }
    // From the reader: the editor opens above the reading, which stays to come back to. An editor that
    // was already open lies under the reader instead, so the reader makes way for it.
    fun requestDraftFromReader(rule: DhikrReminder) {
        val editorUnderneath = draft != null
        requestDraft(rule)
        if (editorUnderneath) { readerId = null; readerReminderSource = null }
    }
    fun openItems(items: List<String>, collection: DhikrCategory? = null, occurrence: DhikrOccurrence? = null,
        fresh: Boolean = false, targetCountOverride: Int? = null, collectionReading: Boolean = false,
        sourceReminderId: String? = null) {
        var id = ""
        mutate({ id = repo.openSession(items, collection, occurrence?.id, fresh, targetCountOverride,
            collectionReading = collectionReading) }) {
            readerReminderSource = (sourceReminderId ?: occurrence?.ruleId)?.let { "$id|$it" }
            readerId = id; keyboardFocus.clearFocus()
        }
    }
    fun openCollection(value: DhikrCategory) {
        val items = state.collectionEntries(value).map { it.id }
        if (items.isEmpty()) {
            page = "library"; category = null; libraryTab = "all"; query = ""
            scope.launch {
                libraryScroll.scrollToItem(0)
                snackbar.showSnackbar(EMPTY_COLLECTION_HINT)
            }
            return
        }
        openItems(items, value, collectionReading = true)
        // Once ever per collection: read outside its occasion, the morning or evening list counts for nothing.
        if (value == DhikrCategory.MORNING || value == DhikrCategory.EVENING) scope.launch {
            val prefs = activity.getSharedPreferences(ADHKAR_HINTS_PREFS, Context.MODE_PRIVATE)
            val hint = "outsideOccasion:" + value.name
            if (prefs.getBoolean(hint, false)) return@launch
            val opensAt = withContext(Dispatchers.IO) {
                collectionOccasionOpensAt(activity, value, System.currentTimeMillis())
            } ?: return@launch
            prefs.edit().putBoolean(hint, true).apply()
            val clock = bidiClock(Instant.ofEpochMilli(opensAt).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("HH:mm", Locale.US)))
            snackbar.showSnackbar(if (value == DhikrCategory.MORNING)
                "انتهى وقت أذكار الصباح مع الظهر، ويعود مع الفجر ($clock). ما تقرؤه الآن لا يُحسب لأذكار الصباح."
                else "لم يحن وقت أذكار المساء بعد؛ يبدأ مع العصر ($clock). ما تقرؤه الآن لا يُحسب لأذكار المساء.",
                duration = SnackbarDuration.Long)
        }
    }
    fun showEmptyReminderCollection() {
        showReminders = false
        page = "library"
        category = null
        libraryTab = "all"
        query = ""
        scope.launch { snackbar.showSnackbar(EMPTY_COLLECTION_HINT) }
    }
    fun openOccurrence(value: DhikrOccurrence) {
        val current = repo.state.value
        val rule = current.reminders.find { it.id == value.ruleId && it.revision == value.revision }
        val items = reminderSessionItems(current, value)
        if (items.isEmpty()) {
            if (rule?.collection != null) showEmptyReminderCollection()
            else scope.launch { snackbar.showSnackbar("هذا التذكير لم يعد متاحًا. يمكنك القراءة من المكتبة.") }
        } else openItems(items, rule?.collection, value, collectionReading = rule?.collection != null)
    }
    fun openDeepLink(occurrence: DhikrOccurrence): String? {
        val current = repo.state.value
        val rule = current.reminders.find { it.id == occurrence.ruleId && it.revision == occurrence.revision }
        val items = reminderSessionItems(current, occurrence)
        return if (items.isEmpty()) null else repo.openSession(items, rule?.collection, occurrence.id,
            collectionReading = rule?.collection != null)
    }
    fun openReminder(rule: DhikrReminder) {
        var occurrence: DhikrOccurrence? = null
        mutate({
            val scheduledRule = repo.state.value.reminders.firstOrNull {
                it.id == rule.id && it.revision == rule.revision && it.enabled
            }
            if (scheduledRule != null) {
                val openNow = System.currentTimeMillis()
                val today = Instant.ofEpochMilli(openNow).atZone(ZoneId.systemDefault()).toLocalDate()
                val window = (-1L..1L).asSequence()
                    .flatMap { offset -> DhikrReminderScheduler.resolveWindows(activity, scheduledRule, today.plusDays(offset)).asSequence() }
                    .firstOrNull { openNow in it.progressStartMillis until it.progressEndMillis && openNow < it.endMillis }
                if (window != null) occurrence = repo.ensureOccurrence(scheduledRule, window)
            }
        }) {
            val current = occurrence?.takeIf {
                (it.status == DhikrOccurrenceStatus.OPEN && it.count < it.target) ||
                    (rule.collection == null && it.status == DhikrOccurrenceStatus.COMPLETED)
            }
            if (current != null) {
                showReminders = false
                openOccurrence(current)
            } else {
                val collection = rule.collection
                val items = collection?.let { repo.state.value.collectionEntries(it).map(DhikrEntry::id) }
                    ?: listOf(rule.dhikrId)
                if (items.isEmpty()) {
                    showEmptyReminderCollection()
                } else {
                    showReminders = false
                    openItems(items, collection,
                        targetCountOverride = if (collection == null) rule.targetCount else null,
                        collectionReading = collection != null, sourceReminderId = rule.id)
                }
            }
        }
    }
    fun markReminderDone(rule: DhikrReminder) {
        mutate({
            val current = repo.state.value.reminders.firstOrNull {
                it.id == rule.id && it.revision == rule.revision && it.enabled
            } ?: return@mutate
            val doneAt = System.currentTimeMillis()
            val window = DhikrReminderScheduler.currentOrNextWindow(activity, current, doneAt) ?: return@mutate
            val today = Instant.ofEpochMilli(doneAt).atZone(ZoneId.systemDefault()).toLocalDate()
            if (window.date == today || doneAt in window.progressStartMillis until window.progressEndMillis) {
                repo.markDone(repo.ensureOccurrence(current, window).id, doneAt)
            }
        })
    }
    fun saveWithReport(rule: DhikrReminder) {
        mutate({ repo.save(rule) }, onFailure = {
            scope.launch { snackbar.showSnackbar(DhikrReminderScheduler.validate(activity, rule) ?: "تعذّر حفظ التذكير. حاول مرة أخرى.") }
        }) { promptForReminderAccess(rule) }
    }
    fun deleteReminder(rule: DhikrReminder, afterDelete: () -> Unit = {}) {
        var index = -1
        mutate({ index = repo.delete(rule.id) }) {
            afterDelete()
            scope.launch {
                if (snackbar.showSnackbar("حُذف التذكير. بقي تقدّم القراءة محفوظًا.", "تراجع", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed)
                    mutate({ repo.restore(rule, index) })
            }
        }
    }
    // The period whose day is under way, as the reminders list picks it, even when it began the evening
    // before its date; else the next one, if it is today's.
    fun readerSkipWindow(rule: DhikrReminder, at: Long): DhikrWindow? {
        val today = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate()
        return (-1L..1L).flatMap { DhikrReminderScheduler.resolveWindows(activity, rule, today.plusDays(it)) }
            .firstOrNull { at in it.progressStartMillis until it.progressEndMillis && at < it.endMillis }
            ?: DhikrReminderScheduler.currentOrNextWindow(activity, rule, at)?.takeIf { it.date == today }
    }
    fun removeCustom(entry: DhikrEntry, afterDelete: () -> Unit = {}) {
        var removal: CustomDhikrRemoval? = null
        mutate({ removal = repo.deleteCustom(entry.id) }) {
            customEditor = false
            customDraft = null
            afterDelete()
            val reminderCount = removal?.reminders?.size ?: 0
            val message = if (reminderCount > 0) "حُذف «" + entry.title + "» مع " +
                (if (reminderCount == 1) "تذكيره" else "تذكيراته") + ". بقي تقدّم القراءة محفوظًا."
                else "حُذف «" + entry.title + "» من أذكارك."
            scope.launch {
                if (removal != null && snackbar.showSnackbar(message, "تراجع", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed)
                    mutate({ removal?.let { repo.restoreCustom(it) } })
            }
        }
    }
    var permissionRequestedAt by remember { mutableLongStateOf(0L) }
    // Set while the notification settings are being opened: the answer is only known on the way back.
    var leavingForNotificationSettings by remember { mutableStateOf(false) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        now = System.currentTimeMillis()
        mutate({ DhikrReminderScheduler.refresh(activity, rearm = true) })
        // Once refused for good, the system answers at once without asking; the settings are then the only way.
        if (!granted && SystemClock.elapsedRealtime() - permissionRequestedAt < 500) {
            leavingForNotificationSettings = true
            openDhikrNotificationSettings(activity, permissionPromptVibrate)
        } else afterNotificationPrompt()
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            // The resume that delivers the refusal comes before the settings open; the pause that follows is theirs.
            if (event == Lifecycle.Event.ON_PAUSE) leavingForNotificationSettings = false
            if (event == Lifecycle.Event.ON_RESUME) {
                now = System.currentTimeMillis()
                DhikrReminderScheduler.ensureChannel(activity)
                exactAlarmsAvailable = DhikrReminderScheduler.exactAlarmsEnabled(activity)
                if (exactAlarmsAvailable) exactAlarmPromptDeclined = false
                if (!permissionPrompt && !leavingForNotificationSettings) afterNotificationPrompt()
                mutate({ DhikrReminderScheduler.refresh(activity, rearm = true) }, onFailure = {
                    scope.launch { snackbar.showSnackbar("تعذّر تحديث مواعيد تذكيرات الأذكار.") }
                })
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    LaunchedEffect(readerId, now) {
        val id = readerId ?: return@LaunchedEffect
        val session = repo.state.value.sessions[id] ?: return@LaunchedEffect
        val collection = session.category ?: return@LaunchedEffect
        if (session.occurrenceId != null || session.collectionPeriodKey == null ||
            collection !in setOf(DhikrCategory.MORNING, DhikrCategory.EVENING, DhikrCategory.NIGHT)) return@LaunchedEffect
        val currentKey = withContext(Dispatchers.IO) { collectionReadingPeriodKey(activity, collection, null, now) }
        if (session.collectionPeriodKey == currentKey) return@LaunchedEffect
        val items = repo.state.value.collectionEntries(collection).map(DhikrEntry::id)
        if (items.isEmpty()) {
            readerId = null
            showEmptyReminderCollection()
            return@LaunchedEffect
        }
        val nextId = withContext(Dispatchers.IO) {
            repo.openSession(items, collection, now = now, collectionReading = true)
        }
        if (readerId == id) {
            readerId = nextId
            readerReminderSource = null
            scope.launch { snackbar.showSnackbar("بدأت فترة أذكار جديدة؛ حُفظت قراءتك السابقة.") }
        }
    }
    LaunchedEffect(requestedReminderSequence, requestedReminderId, requestedOccurrenceId) {
        if (consumedRequest == requestedReminderSequence || (requestedReminderId == null && requestedOccurrenceId == null)) return@LaunchedEffect
        val occurrence = withContext(Dispatchers.IO) {
            requestedOccurrenceId?.let { repo.state.value.occurrences[it] } ?: if (requestedOccurrenceId == null) {
                repo.state.value.reminders.find { it.id == requestedReminderId }?.let { rule ->
                    DhikrReminderScheduler.currentOrNextWindow(activity, rule)?.let { repo.ensureOccurrence(rule, it) }
                }
            } else null
        }
        val opened = occurrence?.let { withContext(Dispatchers.IO) { runCatching { openDeepLink(it) }.getOrNull() } }
        // Marked only once the lookup finished: a request cancelled midway (the tab left, or the
        // activity was re-created) is retried when this tab composes again; openSession reuses its session.
        consumedRequest = requestedReminderSequence
        if (occurrence != null) {
            if (opened != null) {
                readerReminderSource = "$opened|${occurrence.ruleId}"
                readerId = opened
                keyboardFocus.clearFocus()
            } else {
                val current = repo.state.value
                val collection = current.reminders.find {
                    it.id == occurrence.ruleId && it.revision == occurrence.revision
                }?.collection
                if (collection != null && current.collectionEntries(collection).isEmpty()) showEmptyReminderCollection()
                else snackbar.showSnackbar("هذا التذكير لم يعد متاحًا. يمكنك القراءة من المكتبة.")
            }
        } else snackbar.showSnackbar("هذه الفترة لم تعد متاحة. يمكنك القراءة من المكتبة.")
    }

    AdhkarTheme {
        val p = LocalAdhkarPalette.current
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Box(modifier.fillMaxSize().background(p.background)) {
                if (page == "today") {
                    AdhkarTodayPage(
                        state = state, listState = homeScroll,
                        collectionPeriodKeys = collectionPeriodKeys,
                        onPage = { page = it },
                        onSearch = {
                            page = "library"; focusSearch = true
                            scope.launch { libraryScroll.scrollToItem(0) }
                        },
                        onShowReminders = { showReminders = true },
                        onOpenCollection = ::openCollection,
                        onOpenReminder = ::openReminder,
                        onToggleRule = { rule, enabled -> mutate({ repo.setEnabled(rule.id, enabled) }) {
                            if (enabled) promptForReminderAccess(rule.copy(enabled = true))
                        } },
                    )
                } else {
                    AdhkarLibraryPage(
                        state = state, listState = libraryScroll,
                        query = query, onQuery = { query = it },
                        category = category?.let(DhikrCategory::valueOf), onCategory = { category = it?.name },
                        tab = libraryTab, onTab = { libraryTab = it },
                        focus = focus, focusSearch = focusSearch, onFocusConsumed = { focusSearch = false },
                        onPage = { page = it },
                        onSearchAction = { scope.launch { libraryScroll.scrollToItem(0); focus.requestFocus() } },
                        onOpenEntry = { id, memberCategory -> openItems(listOf(id), memberCategory) },
                        onOpenCollection = ::openCollection,
                        onAddCustom = { libraryTab = "custom"; category = null; customDraft = null; customEditor = true },
                    )
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
                if (showReminders) {
                    DhikrRemindersSheet(
                        activity = activity, state = state, now = now, exactAlarmsAvailable = exactAlarmsAvailable,
                        snackbar = snackbar, scrollToId = savedReminderId, onScrolled = { savedReminderId = null },
                        onClose = { showReminders = false },
                        onDraft = ::requestDraft,
                        onToggle = { rule, enabled -> mutate({ repo.setEnabled(rule.id, enabled) }) {
                            if (enabled) promptForReminderAccess(rule.copy(enabled = true))
                        } },
                        onRead = ::openReminder,
                        onSavePreset = { rule -> saveWithReport(rule) },
                        onSkip = { rule -> mutate({
                            val currentRule = repo.state.value.reminders.firstOrNull {
                                it.id == rule.id && it.revision == rule.revision && it.enabled
                            }
                            if (currentRule != null) {
                                val skipNow = System.currentTimeMillis()
                                val window = DhikrReminderScheduler.currentOrNextWindow(activity, currentRule, skipNow)
                                if (window != null && skipNow < window.endMillis) {
                                    val existing = repo.state.value.occurrences[window.progressKey]
                                    if (existing == null || existing.status == DhikrOccurrenceStatus.OPEN)
                                        repo.skip(repo.ensureOccurrence(currentRule, window).id)
                                }
                            }
                        }) },
                        onDone = ::markReminderDone,
                        onDelete = { rule -> deleteReminder(rule) },
                    )
                }
                readerId?.let { id -> state.sessions[id]?.let { session ->
                    val reminderId = session.occurrenceId?.let { state.occurrences[it]?.ruleId }
                        ?: readerReminderSource?.takeIf { it.startsWith("$id|") }?.substringAfter('|')
                    val readerReminder = reminderId?.let { sourceId -> state.reminders.firstOrNull { it.id == sourceId } }
                    // A reading no reminder opened: the menu acts on the whole collection only when all of
                    // it is being read, otherwise on the dhikr on screen, and edits a reminder that already exists.
                    val readsCollection = session.collectionPeriodKey != null && session.category in reminderCollections
                    val ownReminder = state.reminders.firstOrNull {
                        if (readsCollection) it.collection == session.category
                        else it.collection == null && it.dhikrId == session.itemId
                    }
                    val skipWindow = readerReminder?.takeIf { rule ->
                        rule.enabled && rule.collection?.let { state.collectionEntries(it).isNotEmpty() } != false
                    }?.let { readerSkipWindow(it, now) }?.takeIf { window ->
                        session.occurrenceId == null || session.occurrenceId == window.progressKey
                    }
                    val skipOccurrence = skipWindow?.let { state.occurrences[it.progressKey] }
                    val skipReminderLabel = skipWindow?.takeIf {
                        skipOccurrence == null || (skipOccurrence.status == DhikrOccurrenceStatus.OPEN &&
                            skipOccurrence.count < skipOccurrence.target)
                    }?.let { window ->
                        val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
                        if (window.date == today) "تخطّي تذكير اليوم" else "تخطّي التذكير الحالي"
                    }
                    DhikrReader(activity, state, session, now, onDismiss = {
                        readerId = null; readerReminderSource = null
                    },
                        reminder = readerReminder,
                        onCount = { delta -> mutate({ repo.count(id, delta) }) },
                        onMove = { direction -> mutate({ repo.move(id, direction) }) },
                        onSkip = { mutate({ repo.skipItem(id) }) },
                        onRemove = { mutate({ repo.removeItem(id) }) },
                        onFavourite = { mutate({ repo.toggleFavourite(session.itemId) }) },
                        onCollections = { state.findDhikr(session.itemId)?.let { collectionEdit = it } },
                        onEditCustom = {
                            state.findDhikr(session.itemId)?.takeIf { it.custom }?.let {
                                readerId = null; readerReminderSource = null
                                customDraft = it; customEditor = true
                            }
                        },
                        onDeleteCustom = {
                            state.findDhikr(session.itemId)?.takeIf { it.custom }?.let { entry ->
                                removeCustom(entry) { readerId = null; readerReminderSource = null }
                            }
                        },
                        onAddToCollection = { addToCollectionSessionId = id },
                        onReorderCollection = { reorderCollectionSessionId = id },
                        onTextSize = { size -> mutate({ repo.setTextSize(size) }) },
                        onHaptics = { enabled -> mutate({ repo.setHaptics(enabled) }) },
                        onNewSession = {
                            val collectionReading = session.collectionPeriodKey != null
                            val items = if (collectionReading && session.category != null)
                                repo.state.value.collectionEntries(session.category).map(DhikrEntry::id)
                            else session.itemIds
                            if (items.isEmpty()) showEmptyReminderCollection()
                            else openItems(items, session.category, fresh = true,
                                targetCountOverride = session.targetCountOverride,
                                collectionReading = collectionReading)
                        },
                        reminderActionLabel = when {
                            ownReminder != null && readsCollection -> "تعديل تذكير هذه المجموعة"
                            ownReminder != null -> "تعديل تذكير هذا الذكر"
                            readsCollection -> "إنشاء تذكير لهذه المجموعة"
                            else -> "إنشاء تذكير لهذا الذكر"
                        },
                        onReminder = { requestDraftFromReader(ownReminder ?: defaultDhikrReminder(session.itemId, session.category,
                            session.category?.let { state.collectionEntries(it) } ?: state.allEntries, readsCollection)) },
                        onConfigureReminder = {
                            reminderId?.let { sourceId -> repo.state.value.reminders.firstOrNull { it.id == sourceId } }
                                ?.let(::requestDraftFromReader)
                        },
                        onDeleteReminder = {
                            reminderId?.let { sourceId -> repo.state.value.reminders.firstOrNull { it.id == sourceId } }
                                ?.let { current ->
                                    deleteReminder(current) { readerId = null; readerReminderSource = null }
                                }
                        },
                        skipReminderLabel = skipReminderLabel,
                        onSkipReminder = {
                            val expectedKey = skipWindow?.progressKey
                            if (expectedKey != null) {
                                var skipped = false
                                mutate({
                                    val current = reminderId?.let { sourceId -> repo.state.value.reminders.firstOrNull {
                                        it.id == sourceId && it.enabled
                                    } }
                                    if (current != null && current.collection?.let {
                                            repo.state.value.collectionEntries(it).isNotEmpty()
                                        } != false) {
                                        val skipNow = System.currentTimeMillis()
                                        val liveWindow = readerSkipWindow(current, skipNow)
                                        if (liveWindow?.progressKey == expectedKey &&
                                            (session.occurrenceId == null || session.occurrenceId == expectedKey)) {
                                            val existing = repo.state.value.occurrences[expectedKey]
                                            if (existing == null || (existing.status == DhikrOccurrenceStatus.OPEN &&
                                                    existing.count < existing.target)) {
                                                val occurrence = repo.ensureOccurrence(current, liveWindow)
                                                repo.skip(occurrence.id, skipNow)
                                                skipped = repo.state.value.occurrences[occurrence.id]?.status == DhikrOccurrenceStatus.SKIPPED
                                            }
                                        }
                                    }
                                }) {
                                    if (skipped) {
                                        readerId = null; readerReminderSource = null
                                        scope.launch { snackbar.showSnackbar("تم تخطّي هذا التذكير. ستعود التذكيرات في موعدها القادم.") }
                                    }
                                }
                            }
                        }, snackbar = snackbar)
                } }
                collectionEdit?.let { entry ->
                    DhikrCollectionsDialog(entry = entry,
                        isMember = { state.isInCollection(entry, it) },
                        onToggle = { category, member -> mutate({ repo.setCollectionMembership(category, entry.id, member) }) },
                        onDismiss = { collectionEdit = null })
                }
                addToCollectionSessionId?.let { sessionId ->
                    val session = state.sessions[sessionId]
                    val collection = session?.category
                    if (session != null && collection != null) {
                        DhikrCollectionAddDialog(
                            category = collection,
                            state = state,
                            onAdd = { entry -> mutate({ repo.addToCollectionSession(sessionId, entry.id) }) },
                            onDismiss = { addToCollectionSessionId = null },
                        )
                    } else LaunchedEffect(sessionId) { addToCollectionSessionId = null }
                }
                reorderCollectionSessionId?.let { sessionId ->
                    val session = state.sessions[sessionId]
                    val collection = session?.category
                    if (session != null && collection != null) {
                        DhikrCollectionOrderDialog(
                            category = collection,
                            state = state,
                            onReorder = { entryIds -> mutate({ repo.reorderCollection(sessionId, entryIds) }) },
                            onDismiss = { reorderCollectionSessionId = null },
                        )
                    } else LaunchedEffect(sessionId) { reorderCollectionSessionId = null }
                }
                if (customEditor) DhikrCustomEditor(initial = customDraft, onDismiss = { customEditor = false; customDraft = null },
                    onSave = { entry, reportError ->
                        mutate({ repo.saveCustom(entry) }, onFailure = {
                            reportError("تعذّر حفظ الذكر. حاول مرة أخرى.")
                        }) { customEditor = false; customDraft = null }
                    },
                    onDelete = { removeCustom(it) })
                draft?.let { rule -> key(rule.id) {
                    // Keyed, so switching to another reminder starts a fresh editor rather than reusing this one's state.
                    DhikrReminderEditor(activity, rule, exactAlarmsAvailable, snackbar,
                        onDismiss = { draft = null; draftDirty = false }, onDirtyChange = { draftDirty = it },
                        onSave = { saved, report ->
                        val revision = repo.state.value.reminders.firstOrNull { it.id == saved.id }?.revision
                        mutate({ repo.save(saved) }, onFailure = {
                            report(DhikrReminderScheduler.validate(activity, saved) ?: "تعذّر حفظ التذكير. حاول مرة أخرى.")
                        }) {
                            // The editor closes itself; the page says it worked and the sheet shows the reminder.
                            report(null)
                            if (showReminders) savedReminderId = saved.id
                            // A reading opened from this reminder belongs to the revision the edit just replaced:
                            // its goal, its dhikr or its period may no longer be the reminder's.
                            val replaced = revision != null &&
                                repo.state.value.reminders.firstOrNull { it.id == saved.id }?.revision != revision
                            val readingRule = readerId?.let { id ->
                                repo.state.value.sessions[id]?.occurrenceId?.let { repo.state.value.occurrences[it]?.ruleId }
                                    ?: readerReminderSource?.takeIf { it.startsWith("$id|") }?.substringAfter('|')
                            }
                            if (replaced && readingRule == saved.id) { readerId = null; readerReminderSource = null }
                            scope.launch { snackbar.showSnackbar("حُفظ التذكير.", duration = SnackbarDuration.Short) }
                            promptForReminderAccess(saved)
                        }
                    })
                } }
                pendingDraft?.let { next ->
                    AlertDialog(onDismissRequest = { pendingDraft = null },
                        title = { Text("تجاهل التعديلات؟") },
                        text = { Text("لم تُحفظ التعديلات على التذكير المفتوح.") },
                        confirmButton = { TextButton(onClick = { pendingDraft = null; draftDirty = false; draft = next }) {
                            Text("تجاهل", color = MaterialTheme.colorScheme.error)
                        } },
                        dismissButton = { TextButton(onClick = { pendingDraft = null }) { Text("متابعة التعديل") } })
                }
                if (permissionPrompt) AlertDialog(onDismissRequest = { permissionPrompt = false; afterNotificationPrompt() },
                    title = { Text("السماح بتذكيرات الأذكار") },
                    text = { Text("يمكنك قراءة الأذكار والعدّ دون إشعارات. اسمح بالإشعارات ليصلك التذكير في الأوقات التي اخترتها.") },
                    confirmButton = { TextButton(onClick = {
                        permissionPrompt = false
                        if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            permissionRequestedAt = SystemClock.elapsedRealtime()
                            permissions.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else openDhikrNotificationSettings(activity, permissionPromptVibrate)
                    }) { Text("السماح") } }, dismissButton = { TextButton(onClick = {
                        permissionPrompt = false; afterNotificationPrompt()
                    }) { Text("لاحقًا") } })
                if (exactAlarmPrompt && !permissionPrompt) AlertDialog(
                    onDismissRequest = { exactAlarmPrompt = false; exactAlarmPromptDeclined = true },
                    title = { Text("تفعيل المنبّهات والتذكيرات") },
                    text = { Text("دون «المنبّهات والتذكيرات» قد تصلك إشعارات الأذكار متأخرة، وقد يفوتك إشعار إذا انتهت فترته قبل وصوله. فعّلها من إعدادات الهاتف.") },
                    confirmButton = { TextButton(onClick = {
                        exactAlarmPrompt = false
                        openDhikrExactAlarmSettings(activity)
                    }) { Text("فتح الإعدادات") } },
                    dismissButton = { TextButton(onClick = { exactAlarmPrompt = false; exactAlarmPromptDeclined = true }) { Text("لاحقًا") } })
            }
        }
    }
}

@Composable
private fun AdhkarPageTabs(selected: String, onSelect: (String) -> Unit) {
    val p = LocalAdhkarPalette.current
    Surface(Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        shape = RoundedCornerShape(18.dp), color = AdhkarSoftGreen.copy(alpha = .7f),
        border = BorderStroke(1.dp, p.primary.copy(alpha = .12f))) {
        Row(Modifier.padding(4.dp)) {
            listOf("today" to "اليوم", "library" to "المكتبة").forEach { (id, label) ->
                val active = selected == id
                Surface(onClick = { onSelect(id) }, modifier = Modifier.weight(1f).height(44.dp)
                    .testTag("adhkar_$id").semantics { this.selected = active },
                    shape = RoundedCornerShape(14.dp),
                    color = if (active) p.primary else Color.Transparent) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(label, color = if (active) Color.White else p.primary,
                            fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun AdhkarTodayPage(
    state: DhikrState, listState: LazyListState,
    collectionPeriodKeys: Map<DhikrCategory, String>,
    onPage: (String) -> Unit,
    onSearch: () -> Unit,
    onShowReminders: () -> Unit,
    onOpenCollection: (DhikrCategory) -> Unit,
    onOpenReminder: (DhikrReminder) -> Unit,
    onToggleRule: (DhikrReminder, Boolean) -> Unit,
) {
    val p = LocalAdhkarPalette.current
    val slots = remember { reminderSlots() }
    val savedRules = remember(state.reminders) { slots.associate { it.key to savedRuleFor(it.key, state.reminders) } }
    fun completedCollection(category: DhikrCategory): Boolean {
        val periodKey = collectionPeriodKeys[category] ?: return false
        return state.isCollectionPeriodComplete(category, periodKey)
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().statusBarsPadding().testTag("adhkar_today_content"),
        contentPadding = PaddingValues(top = 8.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("الأذكار", color = p.forest, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text("ذكر الله حياة القلب", color = p.muted, fontSize = 12.sp)
                }
                Surface(onClick = onSearch, modifier = Modifier.size(46.dp).testTag("adhkar_search_action"),
                    shape = RoundedCornerShape(16.dp), color = AdhkarSoftGreen) {
                    Box(contentAlignment = Alignment.Center) {
                        DhikrIcon(R.drawable.ic_adhkar_search, "البحث في المكتبة", modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
        item { AdhkarPageTabs(selected = "today", onSelect = onPage) }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AdhkarHeroCard(
                    category = DhikrCategory.MORNING,
                    completed = completedCollection(DhikrCategory.MORNING),
                    modifier = Modifier.weight(1f).testTag("adhkar_hero_morning"),
                ) { onOpenCollection(DhikrCategory.MORNING) }
                AdhkarHeroCard(
                    category = DhikrCategory.EVENING,
                    completed = completedCollection(DhikrCategory.EVENING),
                    modifier = Modifier.weight(1f).testTag("adhkar_hero_evening"),
                ) { onOpenCollection(DhikrCategory.EVENING) }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                TodaySectionHeader("تذكيراتي", R.drawable.ic_adhkar_bell,
                    action = "إدارة", actionTag = "adhkar_reminders_action", onAction = onShowReminders)
                Text("اضغط على البطاقة للقراءة، وعلى المفتاح لتفعيل التذكير", color = p.muted, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 20.dp))
            }
        }
        if (state.reminders.isEmpty()) item {
            AdhkarCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("adhkar_empty_reminders"),
                onClick = onShowReminders) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DhikrIcon(R.drawable.ic_adhkar_bell, modifier = Modifier.size(22.dp))
                    Column(Modifier.weight(1f)) {
                        Text("لم تضف تذكيرًا بعد", color = AdhkarHeading, fontWeight = FontWeight.SemiBold)
                        Text("يمكنك القراءة في أي وقت", color = p.muted, fontSize = 12.sp)
                    }
                    DhikrIcon(R.drawable.ic_adhkar_next, tint = p.primary, modifier = Modifier.size(18.dp))
                }
            }
        }
        items(state.reminders, key = { "home_${it.id}" }) { rule ->
            val entry = state.findDhikr(rule.dhikrId)
            val icon = when (val category = rule.collection ?: entry?.categories?.firstOrNull()) {
                DhikrCategory.MORNING -> R.drawable.ic_adhkar_sun
                DhikrCategory.EVENING -> R.drawable.ic_adhkar_moon
                DhikrCategory.NIGHT -> R.drawable.ic_adhkar_moon
                null -> R.drawable.ic_adhkar_leaf
                else -> categoryIcon(category)
            }
            // Named and drawn after what it reminds of: a second reminder for the same dhikr looks like the first.
            val kind = slots.firstOrNull { it.key == reminderSlotKey(rule) }
            val slot = AdhkarReminderSlot(if (kind != null && savedRules[kind.key]?.id == rule.id) kind.key else rule.id, rule,
                reminderTitle(rule, state).ifBlank { "تذكير" }, kind?.icon ?: icon, kind?.iconTint ?: p.primary,
                kind?.iconBackground ?: AdhkarSoftGreen)
            HomeReminderRow(slot = slot, saved = rule,
                onRead = { onOpenReminder(rule) },
                onToggle = { checked -> onToggleRule(rule, checked) })
        }
    }
}

@Composable
private fun TodaySectionHeader(title: String, icon: Int, action: String? = null,
    actionTag: String? = null, onAction: (() -> Unit)? = null) {
    val p = LocalAdhkarPalette.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DhikrIcon(icon, tint = p.primary, modifier = Modifier.size(20.dp))
        Text(title, Modifier.weight(1f), color = AdhkarHeading, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (action != null && onAction != null) {
            Surface(onClick = onAction, modifier = if (actionTag != null) Modifier.testTag(actionTag) else Modifier,
                shape = RoundedCornerShape(50), color = AdhkarSoftGreen) {
                Text(action, Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    color = p.primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun HomeReminderRow(
    slot: AdhkarReminderSlot,
    saved: DhikrReminder?,
    onRead: () -> Unit,
    onToggle: (Boolean) -> Unit,
    tagPrefix: String = "adhkar_home",
    // A suggestion being saved: shown on, and not to be tapped again, until the save has landed.
    pending: Boolean = false,
) {
    val p = LocalAdhkarPalette.current
    AdhkarCard(Modifier.fillMaxWidth().padding(horizontal = if (tagPrefix == "adhkar_home") 20.dp else 0.dp)
        .testTag("${tagPrefix}_reminder_" + slot.key), onClick = onRead) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            val isTahlil = slot.icon == R.drawable.ic_adhkar_tahlil
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(slot.iconBackground),
                contentAlignment = Alignment.Center) {
                DhikrIcon(slot.icon, tint = slot.iconTint,
                    modifier = if (isTahlil) Modifier.size(36.dp, 18.dp) else Modifier.size(22.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(slot.title, color = AdhkarHeading, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(compactReminderSummary(saved ?: slot.preset), color = p.muted, fontSize = 12.sp, lineHeight = 19.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (saved == null) Text("اضغط لتخصيص التذكير", color = p.primary, fontSize = 11.sp)
            }
            AdhkarSwitch(saved?.enabled == true || pending, onCheckedChange = onToggle, enabled = !pending,
                modifier = Modifier.testTag("${tagPrefix}_toggle_" + slot.key)
                    .semantics { contentDescription = "تذكير " + slot.title })
        }
    }
}

@Composable
private fun AdhkarLibraryPage(
    state: DhikrState, listState: LazyListState,
    query: String, onQuery: (String) -> Unit,
    category: DhikrCategory?, onCategory: (DhikrCategory?) -> Unit,
    tab: String, onTab: (String) -> Unit,
    focus: FocusRequester, focusSearch: Boolean, onFocusConsumed: () -> Unit,
    onPage: (String) -> Unit,
    onSearchAction: () -> Unit,
    onOpenEntry: (String, DhikrCategory?) -> Unit,
    onOpenCollection: (DhikrCategory) -> Unit,
    onAddCustom: () -> Unit,
) {
    val p = LocalAdhkarPalette.current
    val normalized = remember(query) { normalizeDhikrSearch(query) }
    val personalFilter = tab == "favourites" || tab == "custom"
    val results = remember(normalized, category, tab, state.favourites, state.customEntries,
        state.collectionAdditions, state.collectionRemovals, state.collectionOrders) {
        (category?.let(state::collectionEntries) ?: state.allEntries).filter { entry ->
                (tab != "favourites" || entry.id in state.favourites) &&
                (tab != "custom" || entry.custom) &&
                (normalized.isEmpty() || normalizeDhikrSearch(dhikrSearchText(entry) + " " + category?.title.orEmpty())
                    .contains(normalized))
        }
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("المكتبة", color = p.forest, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text("أذكارك ومجموعاتك في مكان واحد", color = p.muted, fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Surface(onClick = onAddCustom, modifier = Modifier.size(44.dp).testTag("adhkar_add_custom"),
                    shape = RoundedCornerShape(15.dp), color = AdhkarSoftGreen) {
                    Box(contentAlignment = Alignment.Center) {
                        DhikrIcon(R.drawable.ic_adhkar_plus, "إضافة ذكر", modifier = Modifier.size(21.dp))
                    }
                }
                Surface(onClick = onSearchAction, modifier = Modifier.size(44.dp),
                    shape = RoundedCornerShape(15.dp), color = AdhkarSoftGreen) {
                    Box(contentAlignment = Alignment.Center) {
                        DhikrIcon(R.drawable.ic_adhkar_search, "البحث في المكتبة", modifier = Modifier.size(21.dp))
                    }
                }
            }
        }
        item { AdhkarPageTabs(selected = "library", onSelect = onPage) }
        item {
            OutlinedTextField(
                value = query, onValueChange = onQuery, singleLine = true,
                placeholder = { Text("ابحث في الأذكار...", fontSize = 14.sp, color = p.muted) },
                leadingIcon = { DhikrIcon(R.drawable.ic_adhkar_search, modifier = Modifier.size(20.dp)) },
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onFocusConsumed() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = AdhkarSurface, unfocusedContainerColor = AdhkarSurface,
                    focusedBorderColor = p.primary.copy(alpha = .6f), unfocusedBorderColor = AdhkarBorder),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
                    .focusRequester(focus).testTag("adhkar_search_input"),
            )
            LaunchedEffect(focusSearch) { if (focusSearch) { focus.requestFocus(); onFocusConsumed() } }
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                CategoryChip("الكل", category == null && !personalFilter) {
                    onCategory(null); onTab("all")
                }
                }
                item {
                    CategoryChip("المفضلة", tab == "favourites") {
                        onCategory(null); onTab(if (tab == "favourites") "all" else "favourites")
                    }
                }
                item {
                    CategoryChip("أذكاري", tab == "custom") {
                        onCategory(null); onTab(if (tab == "custom") "all" else "custom")
                    }
                }
                items(adhkarCategoryOrder, key = { it.name }) { value ->
                    CategoryChip(value.title, category == value && !personalFilter) {
                        onCategory(if (category == value && !personalFilter) null else value)
                        onTab("all")
                    }
                }
            }
        }
        if (normalized.isNotEmpty()) {
            item {
                Text("نتائج البحث: " + latinNumber(results.size), color = p.muted, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 20.dp))
            }
        }
        if (normalized.isEmpty() && category == null && !personalFilter) {
            item {
                Box(Modifier.padding(horizontal = 20.dp)) {
                    AdhkarSectionHeader("المجموعات")
                }
            }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(adhkarCategoryOrder, key = { it.name }) { value ->
                        CollectionCard(value, state.collectionEntries(value).size,
                            Modifier.width(180.dp)) { onOpenCollection(value) }
                    }
                }
            }
            item { Box(Modifier.padding(horizontal = 20.dp)) { AdhkarSectionHeader("جميع الأذكار") } }
        } else if (normalized.isEmpty() && category != null) {
            item {
                CollectionCard(category, state.collectionEntries(category).size,
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp)) { onOpenCollection(category) }
            }
        }
        if (tab == "custom" && normalized.isEmpty()) item {
            Button(onClick = onAddCustom, shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).heightIn(min = 52.dp)
                    .testTag("adhkar_add_custom_button")) {
                DhikrIcon(R.drawable.ic_adhkar_plus, tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("إضافة ذكر", fontWeight = FontWeight.Bold)
            }
        }
        if (results.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(when {
                    normalized.isNotEmpty() -> "لا توجد نتائج"
                    tab == "favourites" -> "لا توجد أذكار في المفضلة بعد"
                    tab == "custom" -> "لم تضف أذكارًا بعد"
                    else -> "لا توجد أذكار في هذه المجموعة"
                }, fontWeight = FontWeight.Bold, color = AdhkarHeading)
                Text(when {
                    normalized.isNotEmpty() -> "جرّب كلمة أخرى أو أزل عوامل التصفية."
                    tab == "favourites" -> "أضف الأذكار إلى المفضلة لتظهر هنا."
                    tab == "custom" -> "أضف ذكرًا ليظهر هنا."
                    else -> "أضف إليها أذكارًا من المكتبة."
                }, color = p.muted)
                if (normalized.isNotEmpty()) TextButton(onClick = {
                    onQuery(""); onCategory(null); onTab("all")
                }) { Text("مسح التصفية") }
            }
        }
        items(results, key = { it.id }) { entry ->
            // The same side margins as everything above the list.
            Box(Modifier.padding(horizontal = 20.dp)) {
                DhikrEntryCard(entry, { onOpenEntry(entry.id, category) }, displayCategory = category)
            }
        }
    }
}

@Composable
private fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalAdhkarPalette.current
    Surface(onClick = onClick, shape = RoundedCornerShape(50),
        color = if (selected) p.primary else AdhkarSurface,
        border = if (selected) null else BorderStroke(1.dp, AdhkarBorder)) {
        Text(label, Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
            color = if (selected) Color.White else AdhkarHeading, fontSize = 13.sp)
    }
}

@Composable
private fun CollectionCard(category: DhikrCategory, count: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = LocalAdhkarPalette.current
    AdhkarCard(modifier, onClick = onClick) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(collectionTitle(category), color = AdhkarHeading, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("عدد الأذكار: " + latinNumber(count), color = p.muted, fontSize = 12.sp)
            }
            DhikrIcon(categoryIcon(category), modifier = Modifier.size(26.dp))
        }
    }
}

@Composable
private fun DhikrEntryCard(entry: DhikrEntry, onClick: () -> Unit, displayCategory: DhikrCategory? = null) {
    val p = LocalAdhkarPalette.current
    AdhkarCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(entry.title, color = AdhkarHeading, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(dhikrPreview(entry.text), color = p.muted,
                    fontFamily = AdhkarReadingFont, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (entry.steps.isNotEmpty()) collectionTitle(displayCategory ?: DhikrCategory.SALAH) +
                        " · " + entry.steps.joinToString(" + ") { latinNumber(it.repetitions) }
                    else if (entry.custom) "ذكر أضفته · التكرار: " + latinNumber(entry.defaultCount)
                    else collectionTitle(displayCategory ?: entry.categories.firstOrNull() ?: DhikrCategory.DAILY) +
                        " · التكرار: " + latinNumber(entry.defaultCount),
                    color = p.muted, fontSize = 12.sp, maxLines = 1)
            }
            DhikrIcon(R.drawable.ic_adhkar_next, tint = p.muted, modifier = Modifier.size(18.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DhikrRemindersSheet(
    activity: AppCompatActivity, state: DhikrState, now: Long, exactAlarmsAvailable: Boolean,
    snackbar: SnackbarHostState, scrollToId: String?, onScrolled: () -> Unit,
    onClose: () -> Unit,
    onDraft: (DhikrReminder) -> Unit,
    onToggle: (DhikrReminder, Boolean) -> Unit,
    onRead: (DhikrReminder) -> Unit,
    onSavePreset: (DhikrReminder) -> Unit,
    onSkip: (DhikrReminder) -> Unit,
    onDone: (DhikrReminder) -> Unit,
    onDelete: (DhikrReminder) -> Unit,
) {
    val p = LocalAdhkarPalette.current
    // A suggestion switched on here stays where it is, switched on, until the sheet is opened again:
    // nothing moves under the finger, so a second tap cannot land on another card or switch.
    // Slot key to the id its reminder is saved under.
    var switchedOnHere by remember { mutableStateOf(emptyMap<String, String>()) }
    var switchedOnAt by remember { mutableLongStateOf(0L) }
    // A save that did not land, or a reminder deleted since, gives its suggestion back.
    LaunchedEffect(switchedOnHere, state.reminders) {
        if (switchedOnHere.values.any { id -> state.reminders.none { it.id == id } }) {
            delay(3_000)
            switchedOnHere = switchedOnHere.filterValues { id -> state.reminders.any { it.id == id } }
        }
    }
    val slots = remember { reminderSlots() }
    val suggestedSlots = remember(state.reminders, switchedOnHere) {
        slots.mapNotNull { slot ->
            val kept = switchedOnHere[slot.key]
            when {
                kept != null -> slot to state.reminders.firstOrNull { it.id == kept }
                savedRuleFor(slot.key, state.reminders) == null -> slot to null
                else -> null
            }
        }
    }
    val listed = remember(state.reminders, suggestedSlots) {
        state.reminders.filter { rule -> suggestedSlots.none { it.second?.id == rule.id } }
    }
    // Blocked for the whole app, which no single reminder can change: said once, above the list.
    val appNotificationsOff = remember(now) { !androidx.core.app.NotificationManagerCompat.from(activity).areNotificationsEnabled() }
    val notificationNotice = appNotificationsOff && state.reminders.any { it.enabled }
    val exactAlarmNotice = state.reminders.any { it.enabled } && !exactAlarmsAvailable
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = p.background) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f)) {
            DhikrSheetHeader("تذكيراتي") { onClose() }
            val listState = rememberLazyListState()
            val scrollGuard = rememberSheetScrollGuard(listState)
            // A reminder just saved may sit below the fold: bring it into view, unless it already shows whole.
            LaunchedEffect(scrollToId, listed) {
                if (scrollToId != null && state.reminders.any { it.id == scrollToId }) {
                    val index = listed.indexOfFirst { it.id == scrollToId }
                    if (index < 0) return@LaunchedEffect onScrolled()
                    val position = 1 + (if (notificationNotice) 1 else 0) + (if (exactAlarmNotice) 1 else 0) + index
                    val layout = listState.layoutInfo
                    val shown = layout.visibleItemsInfo.firstOrNull { it.index == position }
                    if (shown == null || shown.offset < layout.viewportStartOffset ||
                        shown.offset + shown.size > layout.viewportEndOffset) listState.animateScrollToItem(position)
                    onScrolled()
                }
            }
            LazyColumn(state = listState,
                modifier = Modifier.weight(1f).nestedScroll(scrollGuard),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    Text("الإشعار يذكّرك فقط ولا يُحتسب قراءة. اضغط على التذكير لتقرأ وتتابع تقدّمك، وتتوقف إشعارات اليوم عند اكتمال الهدف.",
                        color = p.muted, fontSize = 12.sp, lineHeight = 20.sp)
                }
                if (notificationNotice) item {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text("الإشعارات غير مسموح بها", fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onErrorContainer)
                            Text("لن يصلك أي إشعار من تذكيراتك حتى تسمح بالإشعارات لهذا التطبيق.",
                                color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 13.sp, lineHeight = 22.sp)
                            TextButton(onClick = { openDhikrNotificationSettings(activity) }) { Text("إعدادات الإشعارات") }
                        }
                    }
                }
                if (exactAlarmNotice) item {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text("قد تتأخر تذكيرات الأذكار", fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onErrorContainer)
                            Text("فعّل «المنبّهات والتذكيرات» لتصلك إشعارات الأذكار في وقتها.",
                                color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 13.sp, lineHeight = 22.sp)
                            TextButton(onClick = { openDhikrExactAlarmSettings(activity) }) { Text("تفعيل المنبّهات والتذكيرات") }
                        }
                    }
                }
                // Kept in place, unseen, once a suggestion is switched on, so that nothing below it moves.
                if (listed.isEmpty()) item {
                    Column(Modifier.alpha(if (state.reminders.isEmpty()) 1f else 0f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("لا توجد تذكيرات بعد", color = AdhkarHeading, fontWeight = FontWeight.Bold)
                        Text("القراءة متاحة دائمًا. أضف تذكيرًا عندما يناسبك.", color = p.muted)
                    }
                }
                items(listed, key = { it.id }) { rule ->
                    Box(Modifier.animateItem()) {
                        DhikrReminderRow(activity, state, rule, now, appNotificationsOff,
                            onToggle = { enabled -> onToggle(rule, enabled) },
                            onEdit = { onDraft(rule) },
                            onRead = { onRead(rule) },
                            onSkip = { onSkip(rule) },
                            onDone = { onDone(rule) },
                            onDelete = { onDelete(rule) })
                    }
                }
                if (suggestedSlots.isNotEmpty()) item(key = "suggested_header") {
                    Box(Modifier.animateItem()) { AdhkarSectionHeader("تذكيرات مقترحة") }
                }
                items(suggestedSlots, key = { "suggested_${it.first.key}" }) { (slot, saved) ->
                    Box(Modifier.animateItem()) {
                        HomeReminderRow(slot = slot, saved = saved,
                            onRead = { onDraft(saved ?: slot.preset) },
                            onToggle = { checked ->
                                // A second tap right behind the first is the same gesture, not a change of mind.
                                if (saved != null) {
                                    if (SystemClock.elapsedRealtime() - switchedOnAt > 1_000) onToggle(saved, checked)
                                } else if (checked && slot.key !in switchedOnHere) {
                                    switchedOnHere = switchedOnHere + (slot.key to slot.preset.id)
                                    switchedOnAt = SystemClock.elapsedRealtime()
                                    onSavePreset(slot.preset.copy(enabled = true))
                                }
                            },
                            // Shown on, and not to be tapped again, until the save has landed.
                            tagPrefix = "adhkar_sheet", pending = saved == null && slot.key in switchedOnHere)
                    }
                }
            }
            // This sheet covers the page and its snackbars: messages raised while it is open show here.
            SnackbarHost(snackbar, Modifier.padding(horizontal = 12.dp))
            // Below the list rather than at its end, so it stays in reach however long the list grows.
            HorizontalDivider(color = AdhkarBorder)
            // No dhikr yet: the editor asks for one instead of presuming the first of the catalog.
            Button(onClick = { onDraft(DhikrReminder(dhikrId = "", targetCount = 1)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 16.dp)
                    .heightIn(min = 54.dp).testTag("adhkar_new_reminder"), shape = RoundedCornerShape(16.dp)) {
                DhikrIcon(R.drawable.ic_adhkar_plus, tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("تذكير جديد", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
internal fun DhikrSheetHeader(title: String, onClose: () -> Unit) {
    AdhkarDialogSystemBars()
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = AdhkarHeading)
        IconButton(onClick = onClose) { DhikrIcon(R.drawable.ic_adhkar_close, "إغلاق") }
    }
}

private fun canMarkReminderDone(context: Context, rule: DhikrReminder, state: DhikrState, now: Long): Boolean {
    if (!rule.enabled || rule.collection?.let { state.collectionEntries(it).isEmpty() } == true) return false
    val window = DhikrReminderScheduler.currentOrNextWindow(context, rule, now) ?: return false
    val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
    if (now >= window.progressEndMillis ||
        (window.date != today && now !in window.progressStartMillis until window.progressEndMillis)) return false
    if (DhikrReminderScheduler.isCollectionReadingDone(context, state, rule, window)) return false
    return state.occurrences[window.progressKey]?.status?.let { it == DhikrOccurrenceStatus.OPEN } ?: true
}

@Composable
private fun DhikrReminderRow(activity: AppCompatActivity, state: DhikrState, rule: DhikrReminder, now: Long,
    appNotificationsOff: Boolean, onToggle: (Boolean) -> Unit, onEdit: () -> Unit, onRead: () -> Unit, onSkip: () -> Unit,
    onDone: () -> Unit, onDelete: () -> Unit) {
    val p = LocalAdhkarPalette.current
    var menu by remember { mutableStateOf(false) }
    var showDetails by remember(rule.id) { mutableStateOf(false) }
    val window = remember(rule, now) { DhikrReminderScheduler.currentOrNextWindow(activity, rule, now) }
    val occurrence = window?.let { state.occurrences[it.progressKey] }
    val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
    val multiIntervalSkipLabel = when {
        window?.date == today -> "تخطّي اليوم"
        window != null && now in window.progressStartMillis until window.progressEndMillis -> "تخطّي الورد الحالي"
        else -> "تخطّي الموعد القادم"
    }
    val multiIntervalSkippedStatus = when (multiIntervalSkipLabel) {
        "تخطّي اليوم" -> "تم تخطّي اليوم"
        "تخطّي الورد الحالي" -> "تم تخطّي الورد الحالي"
        else -> "تم تخطّي الموعد القادم"
    }
    val running = window != null && now in window.startMillis until window.endMillis
    val collectionEmpty = rule.collection?.let { state.collectionEntries(it).isEmpty() } == true
    val canSkip = rule.enabled && !collectionEmpty && window != null && now < window.endMillis &&
        (occurrence == null || occurrence.status == DhikrOccurrenceStatus.OPEN)
    val notificationsAvailable = DhikrReminderScheduler.notificationsEnabled(activity, rule.vibrate)
    val vibrationOff = rule.vibrate && activity.getSystemService(android.app.NotificationManager::class.java)
        .getNotificationChannel(DhikrReminderScheduler.CHANNEL_ID)?.shouldVibrate() == false
    AdhkarCard(Modifier.fillMaxWidth().testTag("adhkar_read_reminder_" + rule.id), onClick = onRead) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(reminderTitle(rule, state), Modifier.weight(1f),
                    fontWeight = FontWeight.SemiBold, color = AdhkarHeading,
                    fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                AdhkarSwitch(rule.enabled, onToggle, modifier = Modifier.testTag("adhkar_enable_" + rule.id)
                    .semantics { contentDescription = "تذكير " + reminderTitle(rule, state) })
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) {
                        DhikrIcon(R.drawable.ic_adhkar_more, "خيارات التذكير", modifier = Modifier.size(20.dp))
                    }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("تفاصيل الجدول") },
                            onClick = { menu = false; showDetails = true })
                        DropdownMenuItem(text = { Text("تعديل") }, onClick = { menu = false; onEdit() })
                        DropdownMenuItem(text = { Text(if (rule.extraIntervals.isNotEmpty()) multiIntervalSkipLabel
                            else if (running) "تخطّي الفترة الحالية" else "تخطّي الفترة القادمة") },
                            enabled = canSkip, onClick = { menu = false; onSkip() })
                        DropdownMenuItem(text = { Text("إنجاز التذكير") },
                            enabled = canMarkReminderDone(activity, rule, state, now),
                            onClick = { menu = false; onDone() },
                            modifier = Modifier.testTag("adhkar_done_" + rule.id))
                        DropdownMenuItem(text = { Text("حذف") }, onClick = { menu = false; onDelete() })
                    }
                }
            }
            // Not cut short: a schedule with several periods is read here, and the card grows with it.
            Text(compactReminderSummary(rule), fontSize = 12.sp, lineHeight = 19.sp, color = p.muted)
            val status = when {
                !rule.enabled -> "متوقف"
                collectionEmpty -> "هذه المجموعة فارغة · اضغط لفتح المكتبة"
                occurrence?.status == DhikrOccurrenceStatus.SKIPPED -> when {
                    rule.extraIntervals.isNotEmpty() -> multiIntervalSkippedStatus
                    running || window == null -> "تم تخطّي الفترة الحالية"
                    else -> "تم تخطّي فترة " + formatDhikrWindow(window)
                }
                occurrence?.status == DhikrOccurrenceStatus.DONE -> "تم إنجاز هذا التذكير"
                occurrence?.status == DhikrOccurrenceStatus.COMPLETED ->
                    if (rule.extraIntervals.isEmpty()) "اكتمل هدف هذه الفترة" else "اكتمل هدف اليوم"
                window != null && DhikrReminderScheduler.isCollectionReadingDone(activity, state, rule, window) -> "تمت قراءة المجموعة"
                // Times that resolve but give no period no longer fit together, e.g. an end that now precedes its start.
                window == null -> if (remember(rule, now) { DhikrReminderScheduler.prayerTimesAvailable(activity, rule) })
                    "أوقات هذا التذكير لا تصلح حاليًا · عدّلها" else "المواقيت غير متاحة"
                vibrationOff && notificationsAvailable -> "الاهتزاز معطّل في إعدادات إشعارات الأذكار"
                occurrence != null && occurrence.snoozedUntilMillis > now -> "مؤجل حتى " + formatDhikrTime(occurrence.snoozedUntilMillis, now)
                now !in window.startMillis until window.endMillis -> {
                    val upcoming = "الفترة القادمة: " + formatDhikrWindow(window)
                    if (rule.extraIntervals.isNotEmpty() && rule.collection == null &&
                        now in window.progressStartMillis until window.progressEndMillis) {
                        val period = if (window.date == today) "اليوم" else "الورد الحالي"
                        period + " · " + latinNumber(occurrence?.count ?: 0) + " من " +
                            latinNumber(rule.targetCount) + " · " + upcoming
                    } else upcoming
                }
                rule.collection != null -> if (rule.extraIntervals.isEmpty()) "الفترة الحالية · قراءة المجموعة" else "اليوم · قراءة المجموعة"
                else -> (if (rule.extraIntervals.isEmpty()) "الفترة الحالية · " else "اليوم · ") +
                    latinNumber(occurrence?.count ?: 0) + " من " + latinNumber(rule.targetCount)
            }
            Text(status, color = p.primary, fontSize = 12.sp,
                lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val nextNudge = remember(rule, state, now, notificationsAvailable) {
                if (rule.enabled) DhikrReminderScheduler.nextNudge(activity, rule, now) else null
            }
            if (nextNudge != null) Text("الإشعار القادم: " + formatDhikrTime(nextNudge, now),
                color = p.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // The app as a whole is covered by the notice above the list; here only this reminder's own channel.
            if (rule.enabled && !notificationsAvailable && !appNotificationsOff) {
                Text("إشعارات هذا التذكير متوقفة في إعدادات الهاتف", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                TextButton(onClick = { openDhikrNotificationSettings(activity, rule.vibrate) }) { Text("إعدادات الإشعارات") }
            }
            if (rule.enabled && notificationsAvailable && vibrationOff) TextButton(onClick = {
                openDhikrNotificationSettings(activity, true)
            }) { Text("تفعيل الاهتزاز") }
        }
    }
    if (showDetails) AlertDialog(onDismissRequest = { showDetails = false },
        title = { Text(reminderTitle(rule, state)) },
        text = { Box(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
            Text(dhikrRuleSummary(rule), color = p.ink, lineHeight = 23.sp)
        } },
        confirmButton = { TextButton(onClick = { showDetails = false }) { Text("إغلاق") } })
}

private data class AdhkarReminderSlot(
    val key: String,
    val preset: DhikrReminder,
    val title: String,
    val icon: Int,
    val iconTint: Color,
    val iconBackground: Color,
)

private fun reminderSlots(): List<AdhkarReminderSlot> = listOf(
    AdhkarReminderSlot("friday", fridayDhikrPreset(), "الصلاة على النبي ﷺ",
        R.drawable.ic_adhkar_salawat, Color(0xFF0F6B5D), AdhkarSoftGold),
    AdhkarReminderSlot("tahlil", tahlilDhikrPreset(), "لا إله إلا الله",
        R.drawable.ic_adhkar_tahlil, Color(0xFF0F6B5D), AdhkarSoftGreen),
    AdhkarReminderSlot("morning", morningCollectionPreset(), "أذكار الصباح",
        R.drawable.ic_adhkar_sun, AdhkarGoldAccent, AdhkarSoftGold),
    AdhkarReminderSlot("evening", eveningCollectionPreset(), "أذكار المساء",
        R.drawable.ic_adhkar_moon, Color(0xFF0F6B5D), AdhkarSoftGreen),
    AdhkarReminderSlot("night", nightCollectionPreset(), "أذكار الليل بعد المغرب",
        R.drawable.ic_adhkar_moon, Color(0xFF0F6B5D), AdhkarSoftGreen),
)

private fun savedRuleFor(key: String, reminders: List<DhikrReminder>): DhikrReminder? = when (key) {
    // Any salawat reminder that covers Friday: the suggestion is not offered again next to it.
    "friday" -> reminders.firstOrNull { it.collection == null && it.dhikrId == DhikrCatalog.SALAWAT_ID && 5 in it.daysOfWeek }
    "tahlil" -> reminders.firstOrNull { it.collection == null && it.dhikrId == TAHLIL_DAILY_ID }
    "morning" -> reminders.firstOrNull { it.collection == DhikrCategory.MORNING }
    "evening" -> reminders.firstOrNull { it.collection == DhikrCategory.EVENING }
    "night" -> reminders.firstOrNull { it.collection == DhikrCategory.NIGHT }
    else -> null
}

internal fun compactReminderSummary(rule: DhikrReminder): String {
    val days = when {
        rule.daysOfWeek == setOf(5) -> "كل جمعة"
        rule.daysOfWeek.size == 7 -> "يوميًا"
        else -> rule.daysOfWeek.sortedBy(::weekOrder).joinToString("، ") { dhikrWeekdays[it - 1] }
    }
    return buildList {
        if (rule.collection == null) add("الهدف: " + latinNumber(rule.targetCount))
        add(days)
        add(rule.intervals().joinToString("، ") { value ->
            dhikrTimeLabel(value.start) + " إلى " + dhikrTimeLabel(value.end) +
                if (value.endNextDay == true) " (اليوم التالي)" else ""
        })
    }.joinToString(" · ")
}

/** Which ready-made kind a reminder is, by what it reminds of; null for any other dhikr or collection. */
private fun reminderSlotKey(rule: DhikrReminder): String? = when {
    rule.collection == DhikrCategory.MORNING -> "morning"
    rule.collection == DhikrCategory.EVENING -> "evening"
    rule.collection == DhikrCategory.NIGHT -> "night"
    rule.collection != null -> null
    rule.dhikrId == DhikrCatalog.SALAWAT_ID -> "friday"
    rule.dhikrId == TAHLIL_DAILY_ID -> "tahlil"
    else -> null
}
internal fun reminderCollectionTitle(category: DhikrCategory): String =
    if (category == DhikrCategory.NIGHT) "أذكار الليل بعد المغرب" else collectionTitle(category)
/**
 * The ready-made titles are intentional reminder labels and can differ from the dhikr catalog title.
 * They follow the dhikr or the collection itself, so every reminder for it carries the same name and
 * no edit renames one.
 */
internal fun reminderTitle(rule: DhikrReminder, state: DhikrState): String = when {
    rule.collection != null -> reminderCollectionTitle(rule.collection)
    rule.dhikrId == DhikrCatalog.SALAWAT_ID -> "الصلاة على النبي ﷺ"
    rule.dhikrId == TAHLIL_DAILY_ID -> "لا إله إلا الله"
    else -> state.findDhikr(rule.dhikrId)?.title.orEmpty()
}

/** Use the current collection membership; the representative dhikr is not a fallback for an empty list. */
internal fun reminderSessionItems(state: DhikrState, occurrence: DhikrOccurrence): List<String> {
    val rule = state.reminders.firstOrNull {
        it.id == occurrence.ruleId && it.revision == occurrence.revision
    } ?: return emptyList()
    return rule.collection?.let { state.collectionEntries(it).map(DhikrEntry::id) }
        ?: listOf(occurrence.dhikrId).filter { state.findDhikr(it) != null }
}

internal fun fridayDhikrPreset() = DhikrReminder(dhikrId = DhikrCatalog.SALAWAT_ID, targetCount = 100, daysOfWeek = setOf(5),
    start = DhikrTime(minuteOfDay = 480), end = DhikrTime(DhikrTimeKind.MAGHRIB), cadence = DhikrCadence.GENTLE)
/** The 100-times-a-day tahlil (Bukhari 3293, Muslim 2691), not the single post-prayer one. */
internal const val TAHLIL_DAILY_ID = DhikrCatalog.DAILY_TAHLIL_ID
internal fun tahlilDhikrPreset() = DhikrReminder(dhikrId = TAHLIL_DAILY_ID, targetCount = 100, daysOfWeek = (1..7).toSet(),
    start = DhikrTime(minuteOfDay = 480), end = DhikrTime(DhikrTimeKind.MAGHRIB), cadence = DhikrCadence.GENTLE)
internal fun morningCollectionPreset() = DhikrReminder(dhikrId = "morning_kingdom", collection = DhikrCategory.MORNING,
    targetCount = 1, daysOfWeek = (1..7).toSet(), start = DhikrTime(DhikrTimeKind.FAJR),
    end = DhikrTime(DhikrTimeKind.SHURUK), cadence = DhikrCadence.GENTLE)
internal fun eveningCollectionPreset() = DhikrReminder(dhikrId = "evening_kingdom", collection = DhikrCategory.EVENING,
    targetCount = 1, daysOfWeek = (1..7).toSet(), start = DhikrTime(DhikrTimeKind.ASR),
    end = DhikrTime(DhikrTimeKind.MAGHRIB), cadence = DhikrCadence.GENTLE)
internal fun nightCollectionPreset() = DhikrReminder(dhikrId = "sleep_last_two_baqarah", collection = DhikrCategory.NIGHT,
    targetCount = 1, daysOfWeek = (1..7).toSet(), start = DhikrTime(DhikrTimeKind.MAGHRIB, offsetMinutes = 15),
    end = DhikrTime(DhikrTimeKind.ISHA), cadence = DhikrCadence.ONCE)
/** The daily collections a reminder can cover as a whole. */
private val reminderCollections = setOf(DhikrCategory.MORNING, DhikrCategory.EVENING, DhikrCategory.NIGHT)
/** [wholeCollection] is false for one dhikr read on its own: it keeps the collection's times but reminds of that dhikr. */
internal fun defaultDhikrReminder(id: String, category: DhikrCategory?, entries: List<DhikrEntry> = DhikrCatalog.entries,
    wholeCollection: Boolean = true): DhikrReminder {
    val times = when (category) {
        DhikrCategory.MORNING -> DhikrTime(DhikrTimeKind.FAJR) to DhikrTime(DhikrTimeKind.SHURUK)
        DhikrCategory.EVENING -> DhikrTime(DhikrTimeKind.ASR) to DhikrTime(DhikrTimeKind.MAGHRIB)
        DhikrCategory.NIGHT -> DhikrTime(DhikrTimeKind.MAGHRIB, offsetMinutes = 15) to DhikrTime(DhikrTimeKind.ISHA)
        DhikrCategory.SALAH -> DhikrTime(DhikrTimeKind.DHUHR, offsetMinutes = 15) to DhikrTime(DhikrTimeKind.DHUHR, offsetMinutes = 60)
        DhikrCategory.SLEEP -> DhikrTime(minuteOfDay = 22 * 60) to DhikrTime(minuteOfDay = 23 * 60)
        else -> DhikrTime(minuteOfDay = 480) to DhikrTime(minuteOfDay = 1200)
    }
    val collection = category?.takeIf { wholeCollection && it in reminderCollections }
    return DhikrReminder(
        // A whole collection is stood for by a catalog entry, never by one the user could delete from under it.
        dhikrId = if (collection != null) collectionRepresentative(collection) else id,
        collection = collection,
        targetCount = if (collection != null) 1 else entries.firstOrNull { it.id == id }?.defaultCount ?: 1,
        start = times.first, end = times.second,
        cadence = if (category == DhikrCategory.NIGHT) DhikrCadence.ONCE else DhikrCadence.GENTLE,
    )
}
internal fun dhikrTimeLabel(time: DhikrTime): String {
    val base = when (time.kind) {
        DhikrTimeKind.FIXED -> bidiClock(String.format(Locale.US, "%02d:%02d", time.minuteOfDay / 60, time.minuteOfDay % 60))
        DhikrTimeKind.FAJR -> "الفجر"; DhikrTimeKind.SHURUK -> "الشروق"; DhikrTimeKind.DHUHR -> "الظهر"
        DhikrTimeKind.ASR -> "العصر"; DhikrTimeKind.MAGHRIB -> "المغرب"; DhikrTimeKind.ISHA -> "العشاء"
    }
    // A signed number reads left to right even inside Arabic, sign first: isolate it so «−30» is not shown as «30−».
    // No-break spaces keep the prayer, its offset and the unit on one line.
    return base + if (time.offsetMinutes == 0) "" else
        "\u00A0" + bidiClock((if (time.offsetMinutes > 0) "+" else "−") + latinNumber(abs(time.offsetMinutes))) + "\u00A0د"
}
internal val dhikrWeekdays = listOf("الاثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت", "الأحد")
/** Sunday first, as the editor's day chips run. */
private fun weekOrder(day: Int): Int = day % 7
internal fun dhikrRuleSummary(rule: DhikrReminder): String {
    val days = if (rule.daysOfWeek.size == 7) "كل يوم" else rule.daysOfWeek.sortedBy(::weekOrder).joinToString("، ") { dhikrWeekdays[it - 1] }
    val target = if (rule.collection == null) " · الهدف اليومي: " + latinNumber(rule.targetCount) else ""
    val periods = rule.intervals().joinToString("، ") { value ->
        "من " + dhikrTimeLabel(value.start) + " إلى " + dhikrTimeLabel(value.end) +
            if (value.endNextDay == true) " في اليوم التالي" else ""
    }
    return days + target + " · " + periods + " · " + when (rule.cadence) {
        DhikrCadence.ONCE -> "إشعار واحد يوميًا"
        DhikrCadence.GENTLE -> if (rule.intervals().size > 3) "إشعار واحد لكل فترة" else "حتى 3 إشعارات يوميًا"
        DhikrCadence.BALANCED -> if (rule.intervals().size > 5) "إشعار واحد لكل فترة" else "حتى 5 إشعارات يوميًا"
        DhikrCadence.HOURLY -> "كل ساعة"; else -> dhikrEveryMinutesLabel(rule.intervalMinutes)
    }
}
/** Whole hours read as hours («كل ساعتين»), anything else as minutes. */
internal fun dhikrEveryMinutesLabel(minutes: Int): String = when {
    minutes <= 0 || minutes % 60 != 0 -> "كل " + latinNumber(minutes) + " دقيقة"
    minutes == 60 -> "كل ساعة"
    minutes == 120 -> "كل ساعتين"
    minutes / 60 <= 10 -> "كل " + latinNumber(minutes / 60) + " ساعات"
    else -> "كل " + latinNumber(minutes / 60) + " ساعة"
}
internal fun formatDhikrWindow(window: DhikrWindow): String {
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(window.startMillis).atZone(zone)
    val end = Instant.ofEpochMilli(window.endMillis).atZone(zone)
    val day = DateTimeFormatter.ofPattern("EEEE d MMMM", calendarLocale)
    val clock = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    return start.format(day) + " · " + bidiClock(start.format(clock)) + " – " +
        (if (start.toLocalDate() == end.toLocalDate()) "" else end.format(day) + " ") + bidiClock(end.format(clock))
}
/** Same-day nudges show only the clock; later ones are prefixed with the day. */
internal fun formatDhikrTime(millis: Long, now: Long = System.currentTimeMillis()): String {
    val zone = ZoneId.systemDefault()
    val time = Instant.ofEpochMilli(millis).atZone(zone)
    val clock = bidiClock(time.format(DateTimeFormatter.ofPattern("HH:mm", Locale.US)))
    return if (time.toLocalDate() == Instant.ofEpochMilli(now).atZone(zone).toLocalDate()) clock
    else time.format(DateTimeFormatter.ofPattern("EEEE d MMMM", calendarLocale)) + " · " + clock
}
private const val ADHKAR_HINTS_PREFS = "adhkar_hints"
internal const val EMPTY_COLLECTION_HINT =
    "هذه المجموعة فارغة. افتح ذكرًا من المكتبة، ثم اختر «إضافة هذا الذكر إلى مجموعة» من قائمة الخيارات."
internal fun collectionTitle(category: DhikrCategory) = if (category == DhikrCategory.SALAH) "أذكار بعد الصلاة" else "أذكار " + category.title
internal fun openDhikrNotificationSettings(activity: AppCompatActivity, vibrate: Boolean = true) {
    DhikrReminderScheduler.ensureChannel(activity)
    val enabled = androidx.core.app.NotificationManagerCompat.from(activity).areNotificationsEnabled()
    // Blocked for the whole app, only the app's own page can lift it: the channel page's switch cannot.
    activity.startActivity(if (!enabled) Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
        else Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, DhikrReminderScheduler.channelId(vibrate)))
}

internal fun openDhikrExactAlarmSettings(activity: AppCompatActivity) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val app = Uri.parse("package:" + activity.packageName)
    runCatching {
        activity.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).setData(app))
    }.onFailure {
        activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(app))
    }
}

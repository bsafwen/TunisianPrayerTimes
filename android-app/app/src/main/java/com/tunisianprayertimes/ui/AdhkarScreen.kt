package com.tunisianprayertimes.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
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
    var libraryTab by rememberSaveable { mutableStateOf("recent") }
    var readerId by rememberSaveable { mutableStateOf<String?>(null) }
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
    var permissionPrompt by remember { mutableStateOf(false) }
    var permissionPromptVibrate by remember { mutableStateOf(true) }
    var exactAlarmPrompt by remember { mutableStateOf(false) }
    var exactAlarmPromptAfterNotifications by remember { mutableStateOf(false) }
    var exactAlarmsAvailable by remember { mutableStateOf(DhikrReminderScheduler.exactAlarmsEnabled(activity)) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
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
    fun promptForReminderAccess(rule: DhikrReminder) {
        if (!rule.enabled) return
        val missingExactAlarmAccess = !DhikrReminderScheduler.exactAlarmsEnabled(activity)
        if (!DhikrReminderScheduler.notificationsEnabled(activity, rule.vibrate)) {
            permissionPromptVibrate = rule.vibrate
            permissionPrompt = true
            exactAlarmPromptAfterNotifications = missingExactAlarmAccess
        } else if (missingExactAlarmAccess) exactAlarmPrompt = true
    }
    fun openItems(items: List<String>, collection: DhikrCategory? = null, occurrence: DhikrOccurrence? = null,
        fresh: Boolean = false, targetCountOverride: Int? = null, collectionReading: Boolean = false) {
        var id = ""
        mutate({ id = repo.openSession(items, collection, occurrence?.id, fresh, targetCountOverride,
            collectionReading = collectionReading) }) {
            readerId = id; keyboardFocus.clearFocus()
        }
    }
    fun openCollection(value: DhikrCategory) {
        val items = state.collectionEntries(value).map { it.id }
        if (items.isEmpty()) {
            scope.launch { snackbar.showSnackbar("لا توجد أذكار في هذه المجموعة. أضف أذكارًا إليها من قائمة الذكر.") }
            return
        }
        openItems(items, value, collectionReading = true)
    }
    fun showEmptyReminderCollection() {
        showReminders = false
        page = "library"
        category = null
        query = ""
        scope.launch { snackbar.showSnackbar("مجموعة التذكير فارغة. ابحث عن ذكر في المكتبة ثم اختر «المجموعات» لإضافته.") }
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
                val window = DhikrReminderScheduler.currentOrNextWindow(activity, scheduledRule, openNow)
                    ?.takeIf { openNow in it.startMillis until it.endMillis }
                if (window != null) occurrence = repo.ensureOccurrence(scheduledRule, window)
            }
        }) {
            val current = occurrence?.takeIf {
                it.status == DhikrOccurrenceStatus.OPEN && it.count < it.target
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
                        collectionReading = collection != null)
                }
            }
        }
    }
    fun saveWithReport(rule: DhikrReminder) {
        mutate({ repo.save(rule) }, onFailure = {
            scope.launch { snackbar.showSnackbar(DhikrReminderScheduler.validate(activity, rule) ?: "تعذّر حفظ التذكير. حاول مرة أخرى.") }
        }) { promptForReminderAccess(rule) }
    }
    fun deleteReminder(rule: DhikrReminder, afterDelete: () -> Unit = {}) {
        mutate({ repo.delete(rule.id) }) {
            afterDelete()
            scope.launch {
                if (snackbar.showSnackbar("حُذف التذكير؛ حُفظ تقدم القراءة.", "تراجع", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed)
                    mutate({ repo.restore(rule) })
            }
        }
    }
    fun removeCustom(entry: DhikrEntry) {
        var removal: CustomDhikrRemoval? = null
        mutate({ removal = repo.deleteCustom(entry.id) }) {
            customEditor = false
            customDraft = null
            val message = if (removal?.reminders?.isNotEmpty() == true) "حُذف «" + entry.title + "» مع تذكيره؛ حُفظ تقدم القراءة."
                else "حُذف «" + entry.title + "» من أذكارك."
            scope.launch {
                if (removal != null && snackbar.showSnackbar(message, "تراجع", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed)
                    mutate({ removal?.let { repo.restoreCustom(it) } })
            }
        }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        now = System.currentTimeMillis()
        mutate({ DhikrReminderScheduler.refresh(activity, rearm = true) })
        if (exactAlarmPromptAfterNotifications) {
            exactAlarmPromptAfterNotifications = false
            exactAlarmPrompt = !DhikrReminderScheduler.exactAlarmsEnabled(activity)
        }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                now = System.currentTimeMillis()
                DhikrReminderScheduler.ensureChannel(activity)
                exactAlarmsAvailable = DhikrReminderScheduler.exactAlarmsEnabled(activity)
                if (!permissionPrompt && exactAlarmPromptAfterNotifications) {
                    exactAlarmPromptAfterNotifications = false
                    exactAlarmPrompt = !exactAlarmsAvailable
                }
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
            collection !in setOf(DhikrCategory.MORNING, DhikrCategory.EVENING)) return@LaunchedEffect
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
            scope.launch { snackbar.showSnackbar("بدأت فترة أذكار جديدة؛ حُفظت قراءتك السابقة.") }
        }
    }
    LaunchedEffect(requestedReminderSequence, requestedReminderId, requestedOccurrenceId) {
        if (consumedRequest == requestedReminderSequence || (requestedReminderId == null && requestedOccurrenceId == null)) return@LaunchedEffect
        consumedRequest = requestedReminderSequence
        val occurrence = withContext(Dispatchers.IO) {
            requestedOccurrenceId?.let { repo.state.value.occurrences[it] } ?: if (requestedOccurrenceId == null) {
                repo.state.value.reminders.find { it.id == requestedReminderId }?.let { rule ->
                    DhikrReminderScheduler.currentOrNextWindow(activity, rule)?.let { repo.ensureOccurrence(rule, it) }
                }
            } else null
        }
        if (occurrence != null) {
            val opened = withContext(Dispatchers.IO) { runCatching { openDeepLink(occurrence) }.getOrNull() }
            if (opened != null) {
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
                        state = state, now = now, listState = homeScroll,
                        onPage = { page = it },
                        onSearch = {
                            page = "library"; focusSearch = true
                            scope.launch { libraryScroll.scrollToItem(0) }
                        },
                        onOpenLibrary = { page = "library" },
                        onShowReminders = { showReminders = true },
                        onOpenCollection = ::openCollection,
                        onOpenReminder = ::openReminder,
                        onEditDraft = { draft = it },
                        onToggleRule = { rule, enabled -> mutate({ repo.setEnabled(rule.id, enabled) }) {
                            if (enabled) promptForReminderAccess(rule.copy(enabled = true))
                        } },
                        onDeleteRule = { rule -> deleteReminder(rule) },
                        onSavePreset = { rule -> saveWithReport(rule) },
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
                        onFavourite = { id -> mutate({ repo.toggleFavourite(id) }) },
                        onAddCustom = { libraryTab = "custom"; customDraft = null; customEditor = true },
                        onEditCustom = { customDraft = it; customEditor = true },
                        onDeleteCustom = ::removeCustom,
                        onCollections = { collectionEdit = it },
                    )
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
                if (showReminders) {
                    DhikrRemindersSheet(
                        activity = activity, state = state, now = now, exactAlarmsAvailable = exactAlarmsAvailable,
                        onClose = { showReminders = false },
                        onDraft = { draft = it },
                        onToggle = { rule, enabled -> mutate({ repo.setEnabled(rule.id, enabled) }) {
                            if (enabled) promptForReminderAccess(rule.copy(enabled = true))
                        } },
                        onRead = ::openReminder,
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
                        onDelete = { rule -> deleteReminder(rule) { showReminders = false } },
                    )
                }
                readerId?.let { id -> state.sessions[id]?.let { session ->
                    DhikrReader(activity, state, session, now, onDismiss = { readerId = null },
                        onCount = { delta -> mutate({ repo.count(id, delta) }) },
                        onMove = { direction -> mutate({ repo.move(id, direction) }) },
                        onSkip = { mutate({ repo.skipItem(id) }) },
                        onRemove = { mutate({ repo.removeItem(id) }) },
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
                        onReminder = { draft = defaultDhikrReminder(session.itemId, session.category,
                            session.category?.let { state.collectionEntries(it) } ?: state.allEntries) })
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
                            onMove = { entryId, direction -> mutate({ repo.moveCollectionEntry(sessionId, entryId, direction) }) },
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
                    onDelete = ::removeCustom)
                draft?.let { rule ->
                    DhikrReminderEditor(activity, rule, exactAlarmsAvailable,
                        onDismiss = { draft = null }, onSave = { saved, reportError ->
                        mutate({ repo.save(saved) }, onFailure = {
                            reportError(DhikrReminderScheduler.validate(activity, saved) ?: "تعذّر حفظ التذكير. حاول مرة أخرى.")
                        }) {
                            draft = null
                            promptForReminderAccess(saved)
                        }
                    })
                }
                if (permissionPrompt) AlertDialog(onDismissRequest = {
                    permissionPrompt = false
                    if (exactAlarmPromptAfterNotifications) {
                        exactAlarmPromptAfterNotifications = false
                        exactAlarmPrompt = !DhikrReminderScheduler.exactAlarmsEnabled(activity)
                    }
                },
                    title = { Text("السماح بتذكيرات الأذكار") },
                    text = { Text("يبقى الذكر والعدّ متاحين دون إشعارات. اسمح بها لتلقي دعوة هادئة للمتابعة خلال الفترة التي اخترتها.") },
                    confirmButton = { TextButton(onClick = {
                        permissionPrompt = false
                        if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                            permissions.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else openDhikrNotificationSettings(activity, permissionPromptVibrate)
                    }) { Text("السماح") } }, dismissButton = { TextButton(onClick = {
                        permissionPrompt = false
                        if (exactAlarmPromptAfterNotifications) {
                            exactAlarmPromptAfterNotifications = false
                            exactAlarmPrompt = !DhikrReminderScheduler.exactAlarmsEnabled(activity)
                        }
                    }) { Text("لاحقًا") } })
                if (exactAlarmPrompt && !permissionPrompt) AlertDialog(onDismissRequest = { exactAlarmPrompt = false },
                    title = { Text("تفعيل المنبّهات والتذكيرات") },
                    text = { Text("قد تتأخر تذكيرات الأذكار أو تفوت فترة الذكر دون صلاحية المنبّهات الدقيقة. فعّلها من إعدادات الهاتف.") },
                    confirmButton = { TextButton(onClick = {
                        exactAlarmPrompt = false
                        openDhikrExactAlarmSettings(activity)
                    }) { Text("فتح الإعدادات") } },
                    dismissButton = { TextButton(onClick = { exactAlarmPrompt = false }) { Text("لاحقًا") } })
            }
        }
    }
}

@Composable
private fun AdhkarPageTabs(selected: String, onSelect: (String) -> Unit) {
    val p = LocalAdhkarPalette.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically) {
        listOf("today" to "اليوم", "library" to "المكتبة").forEach { (id, label) ->
            Column(Modifier.width(96.dp).clickable { onSelect(id) }.testTag("adhkar_$id"), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.heightIn(min = 44.dp), contentAlignment = Alignment.Center) {
                    Text(label, color = if (selected == id) p.primary else p.muted, fontSize = 15.sp,
                        fontWeight = if (selected == id) FontWeight.Bold else FontWeight.Normal)
                }
                Box(Modifier.fillMaxWidth().widthIn(max = 64.dp).height(3.dp)
                    .background(if (selected == id) p.primary else Color.Transparent, RoundedCornerShape(3.dp)))
            }
        }
    }
}

@Composable
private fun AdhkarTodayPage(
    state: DhikrState, now: Long, listState: LazyListState,
    onPage: (String) -> Unit,
    onSearch: () -> Unit,
    onOpenLibrary: () -> Unit,
    onShowReminders: () -> Unit,
    onOpenCollection: (DhikrCategory) -> Unit,
    onOpenReminder: (DhikrReminder) -> Unit,
    onEditDraft: (DhikrReminder) -> Unit,
    onToggleRule: (DhikrReminder, Boolean) -> Unit,
    onDeleteRule: (DhikrReminder) -> Unit,
    onSavePreset: (DhikrReminder) -> Unit,
) {
    val p = LocalAdhkarPalette.current
    val slots = remember { reminderSlots() }
    val savedRules = remember(state.reminders) { slots.associate { it.key to savedRuleFor(it.key, state.reminders) } }
    val savedSlotsById = slots.mapNotNull { slot -> savedRules[slot.key]?.let { it.id to slot } }.toMap()
    val unsavedSlots = slots.filter { savedRules[it.key] == null }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().statusBarsPadding().testTag("adhkar_today_content"),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("الأذكار", Modifier.weight(1f), color = AdhkarHeading, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                IconButton(onClick = onSearch, modifier = Modifier.testTag("adhkar_search_action")) {
                    DhikrIcon(R.drawable.ic_adhkar_search, "البحث في المكتبة", modifier = Modifier.size(26.dp))
                }
            }
        }
        item { AdhkarPageTabs(selected = "today", onSelect = onPage) }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ReadingCollectionCard(
                    title = "أذكار الصباح",
                    subtitle = "أذكار تحصين الصباح\nوبداية يوم مبارك",
                    icon = R.drawable.ic_adhkar_sun, iconTint = Color.White.copy(alpha = .95f),
                    top = AdhkarMorningTop, bottom = AdhkarMorningBottom,
                    titleColor = p.forest, subtitleColor = p.forest.copy(alpha = .78f),
                    buttonColor = p.forest, buttonContent = Color.White,
                    buttonBorder = BorderStroke(1.dp, Color.White.copy(alpha = .55f)),
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                ) { onOpenCollection(DhikrCategory.MORNING) }
                ReadingCollectionCard(
                    title = "أذكار المساء",
                    subtitle = "أذكار تحصين المساء\nمن كل سوء",
                    icon = R.drawable.ic_adhkar_moon, iconTint = Color.White,
                    top = AdhkarEveningTop, bottom = AdhkarEveningBottom,
                    titleColor = Color.White, subtitleColor = Color.White.copy(alpha = .82f),
                    buttonColor = Color(0xFFF6F2E7), buttonContent = p.forest,
                    buttonBorder = null,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                ) { onOpenCollection(DhikrCategory.EVENING) }
            }
        }
        item {
            Box(Modifier.padding(horizontal = 20.dp)) {
                AdhkarSectionHeader("تذكيراتي", action = "عرض الكل", onAction = onShowReminders,
                    actionModifier = Modifier.testTag("adhkar_reminders_action"))
            }
        }
        if (state.reminders.isEmpty()) item {
            Text("لا توجد تذكيرات محفوظة بعد.", color = p.muted,
                modifier = Modifier.padding(horizontal = 20.dp))
        }
        items(state.reminders, key = { it.id }) { rule ->
            val entry = state.findDhikr(rule.dhikrId)
            val icon = when (val category = rule.collection ?: entry?.categories?.firstOrNull()) {
                DhikrCategory.MORNING -> R.drawable.ic_adhkar_sun
                DhikrCategory.EVENING -> R.drawable.ic_adhkar_moon
                null -> R.drawable.ic_adhkar_leaf
                else -> categoryIcon(category)
            }
            val slot = savedSlotsById[rule.id] ?: AdhkarReminderSlot(rule.id, rule,
                reminderTitle(rule, state).ifBlank { "تذكير محفوظ" }, icon, p.primary, AdhkarSoftGreen)
            HomeReminderRow(slot = slot, saved = rule,
                onRead = { onOpenReminder(rule) },
                onEdit = { onEditDraft(rule) },
                onToggle = { checked -> onToggleRule(rule, checked) },
                onDelete = { onDeleteRule(rule) })
        }
        if (unsavedSlots.isNotEmpty()) item {
            Box(Modifier.padding(horizontal = 20.dp)) {
                AdhkarSectionHeader("تذكيرات مقترحة")
            }
        }
        items(unsavedSlots, key = { it.key }) { slot ->
            HomeReminderRow(slot = slot, saved = null,
                onRead = { onOpenReminder(slot.preset.copy(enabled = false)) },
                onEdit = { onEditDraft(slot.preset) },
                onToggle = { checked ->
                    if (checked) onSavePreset(slot.preset.copy(enabled = true))
                })
        }
        item {
            Box(Modifier.padding(horizontal = 20.dp)) {
                AdhkarSectionHeader("تصفح حسب الحالة", action = "عرض الكل", onAction = onOpenLibrary)
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(DhikrCategory.SALAH, DhikrCategory.SLEEP, DhikrCategory.HOME, DhikrCategory.DAILY).forEach { value ->
                    SituationCard(value, Modifier.weight(1f).testTag("adhkar_category_" + value.name)) { onOpenCollection(value) }
                }
            }
        }
    }
}

@Composable
private fun ReadingCollectionCard(
    title: String, subtitle: String, icon: Int, iconTint: Color,
    top: Color, bottom: Color,
    titleColor: Color, subtitleColor: Color,
    buttonColor: Color, buttonContent: Color,
    buttonBorder: BorderStroke?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = modifier.clip(shape).background(Brush.verticalGradient(listOf(top, bottom))).clickable(onClick = onClick).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DhikrIcon(icon, tint = iconTint, modifier = Modifier.size(34.dp))
            Text(title, color = titleColor, fontSize = 19.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(subtitle, color = subtitleColor, fontSize = 12.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(14.dp))
        Button(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp), shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = buttonColor, contentColor = buttonContent),
            border = buttonBorder) {
            Text("ابدأ الآن", Modifier.weight(1f), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            DhikrIcon(R.drawable.ic_adhkar_next, tint = buttonContent, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SituationCard(category: DhikrCategory, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, color = AdhkarSurface, border = BorderStroke(1.dp, AdhkarBorder),
        shape = RoundedCornerShape(16.dp), modifier = modifier) {
        Column(Modifier.fillMaxWidth().heightIn(min = 92.dp).padding(horizontal = 4.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            DhikrIcon(categoryIcon(category), modifier = Modifier.size(26.dp))
            Spacer(Modifier.height(10.dp))
            Text(category.title, color = AdhkarHeading, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 1)
        }
    }
}

@Composable
private fun HomeReminderRow(
    slot: AdhkarReminderSlot,
    saved: DhikrReminder?,
    onRead: () -> Unit,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val p = LocalAdhkarPalette.current
    var menu by remember { mutableStateOf(false) }
    AdhkarCard(Modifier.fillMaxWidth().testTag("adhkar_home_reminder_" + slot.key), onClick = onRead) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(slot.iconBackground), contentAlignment = Alignment.Center) {
                DhikrIcon(slot.icon, tint = slot.iconTint, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(slot.title, color = AdhkarHeading, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(compactReminderSummary(saved ?: slot.preset), color = p.muted, fontSize = 12.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Switch(saved?.enabled == true, onCheckedChange = onToggle,
                modifier = Modifier.testTag("adhkar_home_toggle_" + slot.key))
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag("adhkar_home_menu_" + slot.key)) {
                    DhikrIcon(R.drawable.ic_adhkar_more, "خيارات التذكير")
                }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("إعدادات التذكير") }, onClick = { menu = false; onEdit() })
                    if (saved != null && onDelete != null) {
                        DropdownMenuItem(text = { Text("حذف التذكير") }, onClick = { menu = false; onDelete() })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
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
    onFavourite: (String) -> Unit,
    onAddCustom: () -> Unit,
    onEditCustom: (DhikrEntry) -> Unit,
    onDeleteCustom: (DhikrEntry) -> Unit,
    onCollections: (DhikrEntry) -> Unit,
) {
    val p = LocalAdhkarPalette.current
    val normalized = remember(query) { normalizeDhikrSearch(query) }
    val results = remember(normalized, category, state.customEntries, state.collectionAdditions, state.collectionRemovals) {
        state.allEntries.filter { entry ->
            (category == null || state.isInCollection(entry, category)) &&
                (normalized.isEmpty() || normalizeDhikrSearch(entry.title + " " + entry.text + " " + entry.explanation + " " +
                    entry.categories.joinToString { it.title } + " " + category?.title.orEmpty()).contains(normalized))
        }.map { entry ->
            category?.let { entry.copy(defaultCount = entry.countForCollection(it)) } ?: entry
        }
    }
    val recentEntries = remember(state.sessions, state.customEntries) {
        state.sessions.values.sortedByDescending { it.updatedAtMillis }
            .flatMap { it.itemIds }.distinct().mapNotNull { state.findDhikr(it) }.take(12)
    }
    val favouriteEntries = remember(state.favourites, state.customEntries) {
        state.allEntries.filter { it.id in state.favourites }
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("مكتبة الأذكار", Modifier.weight(1f), color = AdhkarHeading, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                IconButton(onClick = onAddCustom, modifier = Modifier.testTag("adhkar_add_custom")) {
                    DhikrIcon(R.drawable.ic_adhkar_plus, "إضافة ذكر خاص", modifier = Modifier.size(24.dp))
                }
                IconButton(onClick = onSearchAction) { DhikrIcon(R.drawable.ic_adhkar_search, "البحث في المكتبة", modifier = Modifier.size(26.dp)) }
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
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CategoryChip("الكل", category == null) { onCategory(null) }
                adhkarCategoryOrder.forEach { value ->
                    CategoryChip(value.title, category == value) { onCategory(if (category == value) null else value) }
                }
            }
        }
        if (normalized.isNotEmpty()) {
            item {
                Text(latinNumber(results.size) + " من الأذكار", color = p.muted, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 20.dp))
            }
            if (results.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("لا توجد نتائج", fontWeight = FontWeight.Bold, color = AdhkarHeading)
                    Text("جرّب كلمة أخرى أو أزل عوامل التصفية.", color = p.muted)
                    TextButton(onClick = { onQuery(""); onCategory(null) }) { Text("مسح التصفية") }
                }
            }
            items(results, key = { it.id }) { entry ->
                DhikrEntryCard(entry, entry.id in state.favourites, { onFavourite(entry.id) }, { onOpenEntry(entry.id, category) },
                    custom = entry.custom, onEdit = { onEditCustom(entry) }, onDelete = { onDeleteCustom(entry) },
                    onCollections = { onCollections(entry) }, displayCategory = category)
            }
        } else {
            item {
                Box(Modifier.padding(horizontal = 20.dp)) {
                    AdhkarSectionHeader("المجموعات", action = "عرض الكل", onAction = { onCategory(null) })
                }
            }
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    adhkarCategoryOrder.filter { category == null || it == category }.chunked(2).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { value ->
                                CollectionCard(value, state.collectionEntries(value).size,
                                    Modifier.weight(1f)) { onOpenCollection(value) }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            item { LibrarySubTabs(selected = tab, onSelect = onTab) }
            if (tab == "custom") item {
                Button(onClick = onAddCustom, shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).heightIn(min = 52.dp)
                        .testTag("adhkar_add_custom_button")) {
                    DhikrIcon(R.drawable.ic_adhkar_plus, tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("إضافة ذكر خاص", fontWeight = FontWeight.Bold)
                }
            }
            val list = when (tab) {
                "favourites" -> favouriteEntries
                "custom" -> state.customEntries
                else -> recentEntries
            }
            if (list.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(when (tab) {
                        "favourites" -> "لا توجد أذكار في المفضلة بعد"
                        "custom" -> "لا توجد أذكار خاصة بعد"
                        else -> "لا توجد قراءات حديثة"
                    }, fontWeight = FontWeight.Bold, color = AdhkarHeading)
                    Text(if (tab == "custom") "أضف ذكرك الأول ليظهر هنا." else "ابدأ القراءة لتظهر هنا.", color = p.muted)
                }
            }
            items(list, key = { it.id }) { entry ->
                DhikrEntryCard(entry, entry.id in state.favourites, { onFavourite(entry.id) }, { onOpenEntry(entry.id, null) },
                    custom = entry.custom, onEdit = { onEditCustom(entry) }, onDelete = { onDeleteCustom(entry) },
                    onCollections = { onCollections(entry) })
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
private fun LibrarySubTabs(selected: String, onSelect: (String) -> Unit) {
    val p = LocalAdhkarPalette.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        listOf("recent" to "الأخيرة", "favourites" to "المفضلة", "custom" to "أذكاري").forEach { (id, label) ->
            Column(Modifier.weight(1f).clickable { onSelect(id) }.testTag("adhkar_library_tab_" + id),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.heightIn(min = 44.dp), contentAlignment = Alignment.Center) {
                    Text(label, color = if (selected == id) AdhkarHeading else p.muted, fontSize = 15.sp,
                        fontWeight = if (selected == id) FontWeight.Bold else FontWeight.Normal)
                }
                Box(Modifier.fillMaxWidth().widthIn(max = 72.dp).height(3.dp)
                    .background(if (selected == id) p.primary else Color.Transparent, RoundedCornerShape(3.dp)))
            }
        }
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
                Text(latinNumber(count) + " ذكرًا", color = p.muted, fontSize = 12.sp)
            }
            DhikrIcon(categoryIcon(category), modifier = Modifier.size(26.dp))
        }
    }
}

@Composable
private fun DhikrEntryCard(entry: DhikrEntry, favourite: Boolean, onFavourite: () -> Unit, onClick: () -> Unit,
    custom: Boolean = false, onEdit: () -> Unit = {}, onDelete: () -> Unit = {}, onCollections: () -> Unit = {},
    displayCategory: DhikrCategory? = null) {
    val p = LocalAdhkarPalette.current
    AdhkarCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(entry.title, color = AdhkarHeading, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (entry.custom) "ذكر خاص · " + latinNumber(entry.defaultCount) + " مرة"
                    else collectionTitle(displayCategory ?: entry.categories.firstOrNull() ?: DhikrCategory.DAILY) +
                        " · " + latinNumber(entry.defaultCount) + " مرة",
                    color = p.muted, fontSize = 12.sp, maxLines = 1)
            }
            IconToggleButton(favourite, onCheckedChange = { onFavourite() }) {
                DhikrIcon(if (favourite) R.drawable.ic_adhkar_heart_filled else R.drawable.ic_adhkar_heart,
                    if (favourite) "إزالة من المفضلة" else "إضافة إلى المفضلة",
                    tint = if (favourite) AdhkarHeart else p.muted, modifier = Modifier.size(20.dp))
            }
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag("adhkar_custom_menu")) {
                    DhikrIcon(R.drawable.ic_adhkar_more, "خيارات الذكر", tint = p.muted, modifier = Modifier.size(20.dp))
                }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    if (custom) DropdownMenuItem(text = { Text("تعديل") }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("المجموعات") }, onClick = { menu = false; onCollections() })
                    if (custom) DropdownMenuItem(text = { Text("حذف") }, onClick = { menu = false; onDelete() })
                }
            }
            DhikrIcon(R.drawable.ic_adhkar_next, tint = p.muted, modifier = Modifier.size(18.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DhikrRemindersSheet(
    activity: AppCompatActivity, state: DhikrState, now: Long, exactAlarmsAvailable: Boolean,
    onClose: () -> Unit,
    onDraft: (DhikrReminder) -> Unit,
    onToggle: (DhikrReminder, Boolean) -> Unit,
    onRead: (DhikrReminder) -> Unit,
    onSkip: (DhikrReminder) -> Unit,
    onDelete: (DhikrReminder) -> Unit,
) {
    val p = LocalAdhkarPalette.current
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = p.background) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f)) {
            DhikrSheetHeader("تذكيراتي") { onClose() }
            val listState = rememberLazyListState()
            val scrollGuard = rememberSheetScrollGuard(listState)
            LazyColumn(state = listState,
                modifier = Modifier.nestedScroll(scrollGuard),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (state.reminders.any { it.enabled } && !exactAlarmsAvailable) item {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text("تذكيرات الأذكار قد تتأخر", fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onErrorContainer)
                            Text("فعّل صلاحية «المنبّهات والتذكيرات» لتصل إشعارات الأذكار في وقتها.",
                                color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 13.sp, lineHeight = 22.sp)
                            TextButton(onClick = { openDhikrExactAlarmSettings(activity) }) { Text("تفعيل المنبّهات والتذكيرات") }
                        }
                    }
                }
                item {
                    Surface(onClick = { onDraft(fridayDhikrPreset()) }, color = AdhkarSoftGreen,
                        border = BorderStroke(1.dp, p.primary.copy(alpha = .35f)), shape = RoundedCornerShape(16.dp)) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("ورد الجمعة · تخصيص تذكير", Modifier.weight(1f), color = p.primary,
                                fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            DhikrIcon(R.drawable.ic_adhkar_next, tint = p.primary, modifier = Modifier.size(20.dp))
                        }
                    }
                }
                if (state.reminders.isEmpty()) item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("لا توجد تذكيرات بعد", color = AdhkarHeading, fontWeight = FontWeight.Bold)
                        Text("القراءة متاحة دائمًا. أضف تذكيرًا عندما يناسبك.", color = p.muted)
                    }
                }
                items(state.reminders, key = { it.id }) { rule ->
                    DhikrReminderRow(activity, state, rule, now,
                        onToggle = { enabled -> onToggle(rule, enabled) },
                        onEdit = { onDraft(rule) },
                        onRead = { onRead(rule) },
                        onSkip = { onSkip(rule) },
                        onDelete = { onDelete(rule) })
                }
                item {
                    Button(onClick = { onDraft(DhikrReminder(dhikrId = DhikrCatalog.entries.first().id)) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(16.dp)) {
                        Text("تذكير جديد", fontWeight = FontWeight.Bold)
                    }
                }
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

@Composable
private fun DhikrReminderRow(activity: AppCompatActivity, state: DhikrState, rule: DhikrReminder, now: Long,
    onToggle: (Boolean) -> Unit, onEdit: () -> Unit, onRead: () -> Unit, onSkip: () -> Unit, onDelete: () -> Unit) {
    val p = LocalAdhkarPalette.current
    var menu by remember { mutableStateOf(false) }
    val window = remember(rule, now) { DhikrReminderScheduler.currentOrNextWindow(activity, rule, now) }
    val occurrence = window?.let { state.occurrences[it.progressKey] }
    val collectionEmpty = rule.collection?.let { state.collectionEntries(it).isEmpty() } == true
    val canSkip = rule.enabled && !collectionEmpty && window != null && now < window.endMillis &&
        (occurrence == null || occurrence.status == DhikrOccurrenceStatus.OPEN)
    val notificationsAvailable = DhikrReminderScheduler.notificationsEnabled(activity, rule.vibrate)
    val vibrationOff = rule.vibrate && activity.getSystemService(android.app.NotificationManager::class.java)
        .getNotificationChannel(DhikrReminderScheduler.CHANNEL_ID)?.shouldVibrate() == false
    AdhkarCard(Modifier.fillMaxWidth().testTag("adhkar_read_reminder_" + rule.id), onClick = onRead) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(reminderTitle(rule, state), Modifier.weight(1f).heightIn(min = 48.dp)
                    .wrapContentHeight(), fontWeight = FontWeight.SemiBold, color = AdhkarHeading)
                Switch(rule.enabled, onToggle, modifier = Modifier.testTag("adhkar_enable_" + rule.id))
                Box {
                    IconButton(onClick = { menu = true }) { DhikrIcon(R.drawable.ic_adhkar_more, "خيارات التذكير") }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("تعديل") }, onClick = { menu = false; onEdit() })
                        DropdownMenuItem(text = { Text("تخطّي هذه الفترة") }, enabled = canSkip, onClick = { menu = false; onSkip() })
                        DropdownMenuItem(text = { Text("حذف") }, onClick = { menu = false; onDelete() })
                    }
                }
            }
            Text(dhikrRuleSummary(rule), fontSize = 12.sp, lineHeight = 22.sp, color = p.muted)
            val status = when {
                !rule.enabled -> "متوقف"
                collectionEmpty -> "هذه المجموعة فارغة · اضغط لفتح المكتبة"
                occurrence?.status == DhikrOccurrenceStatus.SKIPPED -> "تم تخطّي هذه الفترة"
                occurrence?.status == DhikrOccurrenceStatus.COMPLETED -> "اكتمل هدف هذه الفترة"
                window == null -> "المواقيت غير متاحة"
                !notificationsAvailable -> "الإشعارات غير متاحة"
                vibrationOff -> "الاهتزاز متوقف لقناة تذكيرات الأذكار"
                occurrence != null && occurrence.snoozedUntilMillis > now -> "مؤجل حتى " + formatDhikrTime(occurrence.snoozedUntilMillis, now)
                now < window.startMillis -> "الفترة القادمة: " + formatDhikrWindow(window)
                rule.collection != null -> "الفترة الحالية · قراءة المجموعة"
                else -> "الفترة الحالية · " + latinNumber(occurrence?.count ?: 0) + " من " + latinNumber(rule.targetCount)
            }
            Text(status, color = p.primary, fontSize = 12.sp, lineHeight = 22.sp)
            val nextNudge = remember(rule, state, now, notificationsAvailable) {
                if (rule.enabled) DhikrReminderScheduler.nextNudge(activity, rule, now) else null
            }
            if (nextNudge != null) Text("التذكير القادم: " + formatDhikrTime(nextNudge, now), color = AdhkarHeading, fontSize = 12.sp, lineHeight = 22.sp)
            if (rule.enabled && !notificationsAvailable) TextButton(onClick = { openDhikrNotificationSettings(activity, rule.vibrate) }) { Text("إعدادات الإشعارات") }
            if (rule.enabled && notificationsAvailable && vibrationOff) TextButton(onClick = {
                openDhikrNotificationSettings(activity, true)
            }) { Text("تفعيل الاهتزاز") }
        }
    }
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
        R.drawable.ic_adhkar_mosque, Color(0xFF0F6B5D), AdhkarSoftGold),
    AdhkarReminderSlot("tahlil", tahlilDhikrPreset(), DhikrCatalog.find("salah_tahlil")?.text.orEmpty(),
        R.drawable.ic_adhkar_list, Color(0xFF0F6B5D), AdhkarSoftGreen),
    AdhkarReminderSlot("morning", morningCollectionPreset(), "أذكار الصباح",
        R.drawable.ic_adhkar_sun, AdhkarGoldAccent, AdhkarSoftGold),
    AdhkarReminderSlot("evening", eveningCollectionPreset(), "أذكار المساء",
        R.drawable.ic_adhkar_moon, Color(0xFF0F6B5D), AdhkarSoftGreen),
)

private fun savedRuleFor(key: String, reminders: List<DhikrReminder>): DhikrReminder? = when (key) {
    "friday" -> reminders.firstOrNull { it.collection == null && it.dhikrId == DhikrCatalog.SALAWAT_ID && it.daysOfWeek == setOf(5) }
    "tahlil" -> reminders.firstOrNull { it.collection == null && it.dhikrId == "salah_tahlil" }
    "morning" -> reminders.firstOrNull { it.collection == DhikrCategory.MORNING }
    "evening" -> reminders.firstOrNull { it.collection == DhikrCategory.EVENING }
    else -> null
}

internal fun compactReminderSummary(rule: DhikrReminder): String {
    val days = when {
        rule.daysOfWeek == setOf(5) -> "كل يوم جمعة"
        rule.daysOfWeek.size == 7 -> "يوميًا"
        else -> rule.daysOfWeek.sorted().joinToString("، ") { dhikrWeekdays[it - 1] }
    }
    return buildList {
        if (rule.collection == null) add(latinNumber(rule.targetCount) + " مرة")
        add(days)
        add(dhikrTimeLabel(rule.start) + " إلى " + dhikrTimeLabel(rule.end))
    }.joinToString(" · ")
}

internal fun reminderTitle(rule: DhikrReminder, state: DhikrState): String =
    rule.collection?.let(::collectionTitle) ?: state.findDhikr(rule.dhikrId)?.title.orEmpty()

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
internal fun tahlilDhikrPreset() = DhikrReminder(dhikrId = "salah_tahlil", targetCount = 100, daysOfWeek = (1..7).toSet(),
    start = DhikrTime(minuteOfDay = 480), end = DhikrTime(DhikrTimeKind.MAGHRIB), cadence = DhikrCadence.GENTLE)
internal fun morningCollectionPreset() = DhikrReminder(dhikrId = "morning_kingdom", collection = DhikrCategory.MORNING,
    targetCount = 1, daysOfWeek = (1..7).toSet(), start = DhikrTime(DhikrTimeKind.FAJR),
    end = DhikrTime(DhikrTimeKind.SHURUK), cadence = DhikrCadence.GENTLE)
internal fun eveningCollectionPreset() = DhikrReminder(dhikrId = "evening_kingdom", collection = DhikrCategory.EVENING,
    targetCount = 1, daysOfWeek = (1..7).toSet(), start = DhikrTime(DhikrTimeKind.ASR),
    end = DhikrTime(DhikrTimeKind.MAGHRIB), cadence = DhikrCadence.GENTLE)
internal fun defaultDhikrReminder(id: String, category: DhikrCategory?, entries: List<DhikrEntry> = DhikrCatalog.entries): DhikrReminder {
    val times = when (category) {
        DhikrCategory.MORNING -> DhikrTime(DhikrTimeKind.FAJR) to DhikrTime(DhikrTimeKind.SHURUK)
        DhikrCategory.EVENING -> DhikrTime(DhikrTimeKind.ASR) to DhikrTime(DhikrTimeKind.MAGHRIB)
        DhikrCategory.SALAH -> DhikrTime(DhikrTimeKind.DHUHR, offsetMinutes = 15) to DhikrTime(DhikrTimeKind.DHUHR, offsetMinutes = 60)
        DhikrCategory.SLEEP -> DhikrTime(minuteOfDay = 22 * 60) to DhikrTime(minuteOfDay = 23 * 60)
        else -> DhikrTime(minuteOfDay = 480) to DhikrTime(minuteOfDay = 1200)
    }
    val collection = category?.takeIf { it == DhikrCategory.MORNING || it == DhikrCategory.EVENING }
    return DhikrReminder(
        dhikrId = if (collection != null) entries.firstOrNull()?.id ?: id else id,
        collection = collection,
        targetCount = if (collection != null) 1 else entries.firstOrNull { it.id == id }?.defaultCount ?: 1,
        start = times.first, end = times.second,
    )
}
internal fun dhikrTimeLabel(time: DhikrTime): String {
    val base = when (time.kind) {
        DhikrTimeKind.FIXED -> bidiClock(String.format(Locale.US, "%02d:%02d", time.minuteOfDay / 60, time.minuteOfDay % 60))
        DhikrTimeKind.FAJR -> "الفجر"; DhikrTimeKind.SHURUK -> "الشروق"; DhikrTimeKind.DHUHR -> "الظهر"
        DhikrTimeKind.ASR -> "العصر"; DhikrTimeKind.MAGHRIB -> "المغرب"; DhikrTimeKind.ISHA -> "العشاء"
    }
    return base + if (time.offsetMinutes == 0) "" else if (time.offsetMinutes > 0) " + " + latinNumber(time.offsetMinutes) + " د" else " − " + latinNumber(-time.offsetMinutes) + " د"
}
internal val dhikrWeekdays = listOf("الإثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت", "الأحد")
internal fun dhikrRuleSummary(rule: DhikrReminder): String {
    val days = if (rule.daysOfWeek.size == 7) "كل يوم" else rule.daysOfWeek.sorted().joinToString("، ") { dhikrWeekdays[it - 1] }
    val target = if (rule.collection == null) " · هدفك " + latinNumber(rule.targetCount) + " مرة" else ""
    return days + target + " · من " + dhikrTimeLabel(rule.start) + " إلى " + dhikrTimeLabel(rule.end) +
        (if (rule.endNextDay == true) " في اليوم التالي" else "") + " · " + when (rule.cadence) {
        DhikrCadence.GENTLE -> "حتى 3 تذكيرات"; DhikrCadence.BALANCED -> "حتى 5 تذكيرات"
        DhikrCadence.HOURLY -> "كل ساعة"; else -> "كل " + latinNumber(rule.intervalMinutes) + " دقيقة"
    }
}
internal fun formatDhikrWindow(window: DhikrWindow): String {
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(window.startMillis).atZone(zone)
    val end = Instant.ofEpochMilli(window.endMillis).atZone(zone)
    val day = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.forLanguageTag("ar"))
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
    else time.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.forLanguageTag("ar"))) + " · " + clock
}
internal fun collectionTitle(category: DhikrCategory) = if (category == DhikrCategory.SALAH) "أذكار بعد الصلاة" else "أذكار " + category.title
internal fun openDhikrNotificationSettings(activity: AppCompatActivity, vibrate: Boolean = true) {
    DhikrReminderScheduler.ensureChannel(activity)
    val enabled = androidx.core.app.NotificationManagerCompat.from(activity).areNotificationsEnabled()
    activity.startActivity(Intent(if (enabled) Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS else Settings.ACTION_APP_NOTIFICATION_SETTINGS)
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

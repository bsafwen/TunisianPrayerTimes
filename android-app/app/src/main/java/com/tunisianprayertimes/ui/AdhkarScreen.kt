package com.tunisianprayertimes.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tunisianprayertimes.R
import com.tunisianprayertimes.adhkar.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AdhkarScreen(activity: AppCompatActivity, requestedReminderId: String? = null,
    requestedReminderSequence: Int = 0, requestedOccurrenceId: String? = null, modifier: Modifier = Modifier) {
    val repo = remember(activity) { DhikrRepository(activity) }
    val state by repo.state.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf("today") }
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var onlyFavourites by rememberSaveable { mutableStateOf(false) }
    var readerId by rememberSaveable { mutableStateOf<String?>(null) }
    var showReminders by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable(saver = androidx.compose.runtime.saveable.Saver<MutableState<DhikrReminder?>, String>(
        save = { it.value?.toJson()?.toString() ?: "" },
        restore = { mutableStateOf(if (it.isEmpty()) null else dhikrReminderFromJson(org.json.JSONObject(it))) },
    )) { mutableStateOf<DhikrReminder?>(null) }
    var permissionPrompt by remember { mutableStateOf(false) }
    var permissionAvailable by remember { mutableStateOf(DhikrReminderScheduler.notificationsEnabled(activity)) }
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
    val dark = isSystemInDarkTheme()
    fun mutate(action: () -> Unit, onFailure: (Throwable) -> Unit = {
        scope.launch { snackbar.showSnackbar("تعذّر حفظ التغيير. حاول مرة أخرى.") }
    }, after: () -> Unit = {}) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val error = runCatching { withContext(NonCancellable) { mutex.withLock { withContext(Dispatchers.IO) { action() } } } }.exceptionOrNull()
            if (isActive) { if (error != null) onFailure(error) else after() }
        }
    }
    fun openItems(items: List<String>, collection: DhikrCategory? = null, occurrence: DhikrOccurrence? = null, fresh: Boolean = false) {
        var id = ""
        mutate({ id = repo.openSession(items, collection, occurrence?.id, fresh) }) { readerId = id; keyboardFocus.clearFocus() }
    }
    fun openCollection(value: DhikrCategory) = openItems(DhikrCatalog.entries.filter { value in it.categories }.map { it.id }, value)
    fun openOccurrence(value: DhikrOccurrence) = openItems(listOf(value.dhikrId), occurrence = value)
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionAvailable = DhikrReminderScheduler.notificationsEnabled(activity)
        mutate({ DhikrReminderScheduler.refresh(activity, rearm = true) })
    }
    DisposableEffect(activity, dark) {
        val bars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        bars.isAppearanceLightStatusBars = !dark
        bars.isAppearanceLightNavigationBars = !dark
        onDispose { bars.isAppearanceLightStatusBars = false; bars.isAppearanceLightNavigationBars = true }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                now = System.currentTimeMillis()
                permissionAvailable = DhikrReminderScheduler.notificationsEnabled(activity)
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
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
        if (occurrence != null) readerId = withContext(Dispatchers.IO) { repo.openSession(listOf(occurrence.dhikrId), occurrenceId = occurrence.id) }
        else snackbar.showSnackbar("هذه الفترة لم تعد متاحة. يمكنك القراءة من المكتبة.")
    }
    val suggested = remember(now / 60_000) { suggestedDhikrCategory(activity, now) }
    // Freeze the featured choice for this visit. Counts update, but another routine cannot replace it mid-interaction.
    var featured by rememberSaveable(page) { mutableStateOf(chooseDhikrFeature(state, now)) }
    LaunchedEffect(state.occurrences.keys) {
        if (featured == null) featured = chooseDhikrFeature(state, now)
    }
    val featuredSession = featured?.takeIf { it.startsWith("session:") }?.removePrefix("session:")?.let { state.sessions[it] }
    val featuredOccurrence = featured?.takeIf { it.startsWith("occurrence:") }?.removePrefix("occurrence:")?.let { state.occurrences[it] }
    val activeOccurrence = featuredOccurrence ?: featuredSession?.occurrenceId?.let { state.occurrences[it] }
    val featuredEntry = (activeOccurrence?.dhikrId ?: featuredSession?.itemId)?.let(DhikrCatalog::find)
    val featuredRule = activeOccurrence?.let { value -> state.reminders.find { it.id == value.ruleId } }
    val featuredTitle = featuredEntry?.title ?: collectionTitle(suggested)
    val count = activeOccurrence?.count ?: featuredSession?.let { it.counts[it.itemId] ?: 0 }
    val target = activeOccurrence?.target ?: featuredSession?.let { state.target(it) }
    val hasReading = featuredSession != null || activeOccurrence != null

    AdhkarTheme {
        val p = LocalAdhkarPalette.current
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Box(modifier.fillMaxSize().background(p.background).statusBarsPadding()) {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE، d MMMM", Locale.forLanguageTag("ar"))), fontSize = 12.sp, color = p.muted)
                            Text("الأذكار", fontSize = 29.sp, fontWeight = FontWeight.Bold, color = p.ink)
                        }
                        IconButton(onClick = {
                            page = "library"; focusSearch = true
                            scope.launch { libraryScroll.scrollToItem(0) }
                        }, modifier = Modifier.testTag("adhkar_search_action")) {
                            DhikrIcon(R.drawable.ic_adhkar_search, "البحث في المكتبة")
                        }
                        IconButton(onClick = { showReminders = true }, modifier = Modifier.testTag("adhkar_reminders_action")) {
                            DhikrIcon(R.drawable.ic_adhkar_bell, "تذكيراتي")
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        listOf("today" to "اليوم", "library" to "المكتبة").forEach { (id, label) ->
                            Column(Modifier.width(80.dp).clickable { page = id }.testTag("adhkar_" + id), horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                                    Text(label, color = if (page == id) p.primary else p.muted, fontSize = 14.sp, fontWeight = if (page == id) FontWeight.Bold else FontWeight.Normal)
                                }
                                Box(Modifier.fillMaxWidth().widthIn(max = 52.dp).height(3.dp).background(if (page == id) p.primary else Color.Transparent, RoundedCornerShape(3.dp)))
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = p.border)
                    if (page == "today") {
                        LazyColumn(state = homeScroll, contentPadding = PaddingValues(20.dp, 16.dp, 20.dp, 20.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.testTag("adhkar_today_content")) {
                            item {
                                Surface(color = p.forest, shape = RoundedCornerShape(24.dp)) {
                                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                        Text(if (activeOccurrence != null && featuredRule?.daysOfWeek == setOf(5)) "ورد الجمعة"
                                            else if (hasReading) "من حيث توقفت" else "لحظتك للذكر", color = Color(0xFFE8D6B5), fontSize = 12.sp)
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                                Text(featuredTitle, fontSize = 23.sp, lineHeight = 35.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                                Text(if (activeOccurrence != null) "هدف شخصي · " + (featuredRule?.let { "حتى " + dhikrTimeLabel(it.end) } ?: "هذه الفترة")
                                                    else if (featuredSession?.category != null) collectionTitle(featuredSession.category)
                                                    else "اقرأ على مهلك", color = Color(0xFFD5E5DC), fontSize = 12.sp)
                                            }
                                            if (count != null && target != null) {
                                                Box(Modifier.size(82.dp), contentAlignment = Alignment.Center) {
                                                    CircularProgressIndicator(progress = { count.toFloat() / target.coerceAtLeast(1) }, modifier = Modifier.fillMaxSize(),
                                                        color = p.gold, trackColor = Color.White.copy(alpha = .16f), strokeWidth = 3.dp, strokeCap = StrokeCap.Round)
                                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                        Text(arabicNumber(count), color = Color.White, fontSize = 28.sp)
                                                        Text("من " + arabicNumber(target), color = Color(0xFFD5E5DC), fontSize = 11.sp)
                                                    }
                                                }
                                            } else DhikrIcon(categoryIcon(suggested), tint = Color(0xFFD5E5DC), modifier = Modifier.size(42.dp))
                                        }
                                        if (activeOccurrence != null && (!permissionAvailable || featuredRule?.enabled != true))
                                            Text(if (featuredRule?.enabled != true) "التذكير متوقف · القراءة متاحة" else "الإشعارات غير متاحة · القراءة متاحة", color = Color.White, fontSize = 12.sp)
                                        Button(onClick = {
                                            if (featuredSession != null) { mutate({ repo.resumeSession(featuredSession.id) }) { readerId = featuredSession.id } }
                                            else if (activeOccurrence != null) openOccurrence(activeOccurrence)
                                            else openCollection(suggested)
                                        }, colors = ButtonDefaults.buttonColors(containerColor = AdhkarLight.background, contentColor = p.forest),
                                            shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("adhkar_featured_read")) {
                                            Text(if (hasReading) "متابعة الذكر" else "بدء القراءة", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                                            DhikrIcon(R.drawable.ic_adhkar_next, tint = p.forest)
                                        }
                                    }
                                }
                            }
                            item {
                                val secondary = if (suggested == DhikrCategory.MORNING) DhikrCategory.SALAH else if (suggested == DhikrCategory.EVENING) DhikrCategory.SLEEP else DhikrCategory.MORNING
                                DhikrCompactRow(collectionTitle(secondary), "مجموعة أذكار تقرؤها في وقتك", categoryIcon(secondary)) { openCollection(secondary) }
                            }
                            item {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("لكل وقت ذكر", Modifier.weight(1f), color = p.ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    TextButton(onClick = { page = "library"; category = null }) { Text("عرض الكل", fontSize = 12.sp) }
                                }
                                DhikrCategoryGrid { category = it.name; page = "library" }
                            }
                            item { Text("اقرأ على مهل. يمكنك المتابعة من حيث توقفت.", color = p.muted, fontSize = 12.sp) }
                        }
                    } else {
                        val selectedCategory = category?.let(DhikrCategory::valueOf)
                        val results = remember(query, category, onlyFavourites, state.favourites) {
                            val normalized = normalizeDhikrSearch(query)
                            DhikrCatalog.entries.filter { entry ->
                                (selectedCategory == null || selectedCategory in entry.categories) &&
                                    (!onlyFavourites || entry.id in state.favourites) &&
                                    (normalized.isEmpty() || normalizeDhikrSearch(entry.title + " " + entry.text + " " + entry.categories.joinToString { it.title }).contains(normalized))
                            }
                        }
                        LazyColumn(state = libraryScroll, contentPadding = PaddingValues(20.dp, 16.dp, 20.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            item {
                                OutlinedTextField(query, onValueChange = { query = it }, singleLine = true, placeholder = { Text("ابحث عن ذكر أو مناسبة", fontSize = 14.sp) },
                                    leadingIcon = { DhikrIcon(R.drawable.ic_adhkar_search) }, shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("adhkar_search_input"))
                                LaunchedEffect(focusSearch) { if (focusSearch) { focus.requestFocus(); focusSearch = false } }
                            }
                            item { DhikrCategoryGrid(selectedCategory) { category = if (selectedCategory == it) null else it.name } }
                            item {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(!onlyFavourites, onClick = { onlyFavourites = false }, label = { Text("الكل") }, modifier = Modifier.heightIn(min = 48.dp))
                                    FilterChip(onlyFavourites, onClick = { onlyFavourites = !onlyFavourites }, label = { Text("المفضلة") },
                                        leadingIcon = { DhikrIcon(R.drawable.ic_adhkar_bookmark, modifier = Modifier.size(18.dp)) }, modifier = Modifier.heightIn(min = 48.dp))
                                    if (selectedCategory != null) TextButton(onClick = { openCollection(selectedCategory) }) { Text("قراءة المجموعة") }
                                }
                                Text(arabicNumber(results.size) + " من الأذكار", color = p.muted, fontSize = 12.sp)
                            }
                            if (results.isEmpty()) item {
                                Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("لا توجد نتائج", fontWeight = FontWeight.Bold, color = p.ink)
                                    Text("جرّب كلمة أخرى أو أزل عوامل التصفية.", color = p.muted)
                                    TextButton(onClick = { query = ""; category = null; onlyFavourites = false }) { Text("مسح التصفية") }
                                }
                            }
                            items(results, key = { it.id }) { entry ->
                                DhikrLibraryRow(entry, entry.id in state.favourites, onFavourite = { mutate({ repo.toggleFavourite(entry.id) }) }) { openItems(listOf(entry.id)) }
                            }
                        }
                    }
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
            }
            if (showReminders) {
                ModalBottomSheet(onDismissRequest = { showReminders = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = p.background) {
                    Column(Modifier.fillMaxWidth().fillMaxHeight(.9f)) {
                        DhikrSheetHeader("تذكيراتي") { showReminders = false }
                        LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            item {
                                OutlinedButton(onClick = { draft = state.reminders.firstOrNull { it.dhikrId == DhikrCatalog.SALAWAT_ID && it.daysOfWeek == setOf(5) } ?: fridayDhikrPreset() },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp)) {
                                    Text("ورد الجمعة · تخصيص تذكير")
                                }
                            }
                            if (state.reminders.isEmpty()) item {
                                Text("لا توجد تذكيرات بعد", color = p.ink, fontWeight = FontWeight.Bold)
                                Text("القراءة متاحة دائمًا. أضف تذكيرًا عندما يناسبك.", color = p.muted)
                            }
                            items(state.reminders, key = { it.id }) { rule ->
                                DhikrReminderRow(activity, state, rule, now, permissionAvailable,
                                    onToggle = { enabled -> mutate({ repo.setEnabled(rule.id, enabled) }) { if (enabled && !permissionAvailable) permissionPrompt = true } },
                                    onEdit = { draft = rule },
                                    onRead = {
                                        var occurrence: DhikrOccurrence? = null
                                        mutate({ DhikrReminderScheduler.currentOrNextWindow(activity, rule)?.let { occurrence = repo.ensureOccurrence(rule, it) } }) {
                                            occurrence?.let { showReminders = false; openOccurrence(it) }
                                        }
                                    },
                                    onSkip = { mutate({ DhikrReminderScheduler.currentOrNextWindow(activity, rule)?.let { repo.skip(repo.ensureOccurrence(rule, it).id) } }) },
                                    onDelete = {
                                        mutate({ repo.delete(rule.id) }) { scope.launch {
                                            if (snackbar.showSnackbar("حُذف التذكير؛ حُفظ تقدم القراءة.", "تراجع", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed)
                                                mutate({ repo.restore(rule) })
                                        }; showReminders = false }
                                    })
                            }
                            item {
                                Button(onClick = { draft = DhikrReminder(dhikrId = DhikrCatalog.entries.first().id) },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp)) { Text("تذكير جديد") }
                            }
                        }
                    }
                }
            }
            readerId?.let { id -> state.sessions[id]?.let { session ->
                DhikrReader(activity, state, session, now, onDismiss = { readerId = null; featured = chooseDhikrFeature(state, System.currentTimeMillis()) },
                    onCount = { delta -> mutate({ repo.count(id, delta) }) },
                    onMove = { direction -> mutate({ repo.move(id, direction) }) },
                    onFavourite = { mutate({ repo.toggleFavourite(session.itemId) }) },
                    onTextSize = { size -> mutate({ repo.setTextSize(size) }) },
                    onHaptics = { enabled -> mutate({ repo.setHaptics(enabled) }) },
                    onNewSession = { openItems(session.itemIds, session.category, fresh = true) },
                    onReminder = { draft = defaultDhikrReminder(session.itemId, session.category) })
            } }
            draft?.let { rule ->
                DhikrReminderEditor(activity, rule, onDismiss = { draft = null }, onSave = { saved, reportError ->
                    mutate({ repo.save(saved) }, onFailure = {
                        reportError(DhikrReminderScheduler.validate(activity, saved) ?: "تعذّر حفظ التذكير. حاول مرة أخرى.")
                    }) {
                        draft = null
                        if (saved.enabled && !permissionAvailable) permissionPrompt = true
                    }
                })
            }
            if (permissionPrompt) AlertDialog(onDismissRequest = { permissionPrompt = false },
                title = { Text("السماح بتذكيرات الأذكار") },
                text = { Text("يبقى الذكر والعدّ متاحين دون إشعارات. اسمح بها لتلقي دعوة هادئة للمتابعة خلال الفترة التي اخترتها.") },
                confirmButton = { TextButton(onClick = {
                    permissionPrompt = false
                    if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                        permissions.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else openDhikrNotificationSettings(activity)
                }) { Text("السماح") } }, dismissButton = { TextButton(onClick = { permissionPrompt = false }) { Text("لاحقًا") } })
        }
    }
}

@Composable internal fun DhikrCategoryGrid(selected: DhikrCategory? = null, onSelect: (DhikrCategory) -> Unit) {
    val p = LocalAdhkarPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        adhkarCategoryOrder.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { category ->
                    Surface(onClick = { onSelect(category) }, color = p.sage.copy(alpha = if (selected == category) 1f else .5f),
                        border = if (selected == category) BorderStroke(1.dp, p.primary) else null,
                        shape = RoundedCornerShape(16.dp), modifier = Modifier.weight(1f).testTag("adhkar_category_" + category.name)) {
                        Column(Modifier.fillMaxWidth().heightIn(min = 82.dp).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterVertically)) {
                            DhikrIcon(categoryIcon(category))
                            Text(category.title, fontSize = 13.sp, color = p.ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}
@Composable internal fun DhikrCompactRow(title: String, subtitle: String, icon: Int, onClick: () -> Unit) {
    val p = LocalAdhkarPalette.current
    Surface(onClick = onClick, color = p.surface, border = BorderStroke(1.dp, p.border), shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DhikrIcon(icon, modifier = Modifier.size(30.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = p.ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(subtitle, color = p.muted, fontSize = 12.sp)
            }
            DhikrIcon(R.drawable.ic_adhkar_next, tint = p.muted, modifier = Modifier.size(18.dp))
        }
    }
}
@Composable private fun DhikrLibraryRow(entry: DhikrEntry, favourite: Boolean, onFavourite: () -> Unit, onOpen: () -> Unit) {
    val p = LocalAdhkarPalette.current
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 76.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clickable(onClick = onOpen).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(38.dp).background(p.sage, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) { DhikrIcon(categoryIcon(entry.categories.first())) }
                Column(Modifier.weight(1f)) {
                    Text(entry.title, color = p.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(entry.categories.joinToString(" · ") { it.title }, color = p.muted, fontSize = 12.sp)
                }
            }
            IconToggleButton(favourite, onCheckedChange = { onFavourite() }) { DhikrIcon(R.drawable.ic_adhkar_bookmark, if (favourite) "إزالة من المفضلة" else "إضافة إلى المفضلة", tint = if (favourite) p.primary else p.muted) }
        }
        HorizontalDivider(color = p.border)
    }
}
@Composable internal fun DhikrSheetHeader(title: String, onClose: () -> Unit) {
    AdhkarDialogSystemBars()
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = LocalAdhkarPalette.current.ink)
        IconButton(onClick = onClose) { DhikrIcon(R.drawable.ic_adhkar_close, "إغلاق") }
    }
}
@Composable private fun DhikrReminderRow(activity: AppCompatActivity, state: DhikrState, rule: DhikrReminder, now: Long, permission: Boolean,
    onToggle: (Boolean) -> Unit, onEdit: () -> Unit, onRead: () -> Unit, onSkip: () -> Unit, onDelete: () -> Unit) {
    val p = LocalAdhkarPalette.current
    var menu by remember { mutableStateOf(false) }
    val window = remember(rule, now) { DhikrReminderScheduler.currentOrNextWindow(activity, rule, now) }
    val occurrence = window?.let { state.occurrences[it.progressKey] }
    Surface(color = p.surface, border = BorderStroke(1.dp, p.border), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(DhikrCatalog.find(rule.dhikrId)?.title.orEmpty(), Modifier.weight(1f).heightIn(min = 48.dp)
                    .clickable(onClick = onRead).wrapContentHeight(), fontWeight = FontWeight.SemiBold, color = p.ink)
                Switch(rule.enabled, onToggle, modifier = Modifier.testTag("adhkar_enable_" + rule.id))
                Box {
                    IconButton(onClick = { menu = true }) { DhikrIcon(R.drawable.ic_adhkar_more, "خيارات التذكير") }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("تعديل") }, onClick = { menu = false; onEdit() })
                        DropdownMenuItem(text = { Text("تخطّي هذه الفترة") }, enabled = window != null, onClick = { menu = false; onSkip() })
                        DropdownMenuItem(text = { Text("حذف") }, onClick = { menu = false; onDelete() })
                    }
                }
            }
            Text(dhikrRuleSummary(rule), fontSize = 12.sp, lineHeight = 22.sp, color = p.muted)
            val status = when {
                !rule.enabled -> "متوقف"
                occurrence?.status == DhikrOccurrenceStatus.SKIPPED -> "تم تخطّي هذه الفترة"
                occurrence?.status == DhikrOccurrenceStatus.COMPLETED -> "اكتمل هدف هذه الفترة"
                window == null -> "المواقيت غير متاحة"
                !permission -> "الإشعارات غير متاحة"
                occurrence != null && occurrence.snoozedUntilMillis > now -> "مؤجل لمدة ٣٠ دقيقة"
                now < window.startMillis -> "الفترة القادمة: " + formatDhikrWindow(window)
                else -> "الفترة الحالية · " + arabicNumber(occurrence?.count ?: 0) + " من " + arabicNumber(rule.targetCount)
            }
            Text(status, color = p.primary, fontSize = 12.sp, lineHeight = 22.sp)
            if (rule.enabled && !permission) TextButton(onClick = { openDhikrNotificationSettings(activity) }) { Text("إعدادات الإشعارات") }
        }
    }
}
internal fun chooseDhikrFeature(state: DhikrState, now: Long): String? {
    val recent = state.sessions.values.filter { session ->
        !state.isComplete(session) && session.counts.values.any { it > 0 } && now - session.updatedAtMillis < 24 * 60 * 60_000L &&
            (session.occurrenceId == null || state.occurrences[session.occurrenceId]?.let { now in it.startMillis until it.endMillis && it.status == DhikrOccurrenceStatus.OPEN } == true)
    }.maxByOrNull { it.updatedAtMillis }
    if (recent != null) return "session:" + recent.id
    return state.occurrences.values.filter { o -> now in o.startMillis until o.endMillis && o.status == DhikrOccurrenceStatus.OPEN &&
        state.reminders.any { it.id == o.ruleId && it.enabled && it.revision == o.revision } }
        .sortedWith(compareByDescending<DhikrOccurrence> { it.count > 0 }.thenBy { it.endMillis }).firstOrNull()?.let { "occurrence:" + it.id }
}
internal fun suggestedDhikrCategory(context: android.content.Context, now: Long): DhikrCategory {
    val local = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
    fun anchor(kind: DhikrTimeKind) = DhikrReminderScheduler.resolveTime(context, DhikrTime(kind), local.toLocalDate())
    val prayers = listOf(DhikrTimeKind.FAJR, DhikrTimeKind.DHUHR, DhikrTimeKind.ASR, DhikrTimeKind.MAGHRIB, DhikrTimeKind.ISHA).mapNotNull(::anchor)
    if (prayers.any { now in (it + 15 * 60_000L)..(it + 45 * 60_000L) }) return DhikrCategory.SALAH
    return when {
        local.hour >= 22 || local.hour < 4 -> DhikrCategory.SLEEP
        now >= (anchor(DhikrTimeKind.ASR) ?: Long.MAX_VALUE) && now < (anchor(DhikrTimeKind.ISHA) ?: Long.MIN_VALUE) -> DhikrCategory.EVENING
        now >= (anchor(DhikrTimeKind.FAJR) ?: Long.MAX_VALUE) && now < (anchor(DhikrTimeKind.DHUHR) ?: Long.MIN_VALUE) -> DhikrCategory.MORNING
        else -> DhikrCategory.DAILY
    }
}
internal fun collectionTitle(category: DhikrCategory) = if (category == DhikrCategory.SALAH) "أذكار بعد الصلاة" else "أذكار " + category.title
internal fun fridayDhikrPreset() = DhikrReminder(dhikrId = DhikrCatalog.SALAWAT_ID, targetCount = 100, daysOfWeek = setOf(5),
    start = DhikrTime(minuteOfDay = 480), end = DhikrTime(DhikrTimeKind.MAGHRIB), cadence = DhikrCadence.GENTLE)
internal fun defaultDhikrReminder(id: String, category: DhikrCategory?): DhikrReminder {
    val times = when (category) {
        DhikrCategory.MORNING -> DhikrTime(DhikrTimeKind.FAJR) to DhikrTime(DhikrTimeKind.SHURUK)
        DhikrCategory.EVENING -> DhikrTime(DhikrTimeKind.ASR) to DhikrTime(DhikrTimeKind.MAGHRIB)
        DhikrCategory.SALAH -> DhikrTime(DhikrTimeKind.DHUHR, offsetMinutes = 15) to DhikrTime(DhikrTimeKind.DHUHR, offsetMinutes = 60)
        DhikrCategory.SLEEP -> DhikrTime(minuteOfDay = 22 * 60) to DhikrTime(minuteOfDay = 23 * 60)
        else -> DhikrTime(minuteOfDay = 480) to DhikrTime(minuteOfDay = 1200)
    }
    return DhikrReminder(dhikrId = id, targetCount = DhikrCatalog.find(id)?.defaultCount ?: 1, start = times.first, end = times.second)
}
internal fun dhikrTimeLabel(time: DhikrTime): String {
    val base = when (time.kind) {
        DhikrTimeKind.FIXED -> bidiClock(String.format(Locale.US, "%02d:%02d", time.minuteOfDay / 60, time.minuteOfDay % 60))
        DhikrTimeKind.FAJR -> "الفجر"; DhikrTimeKind.SHURUK -> "الشروق"; DhikrTimeKind.DHUHR -> "الظهر"
        DhikrTimeKind.ASR -> "العصر"; DhikrTimeKind.MAGHRIB -> "المغرب"; DhikrTimeKind.ISHA -> "العشاء"
    }
    return base + if (time.offsetMinutes == 0) "" else if (time.offsetMinutes > 0) " + " + arabicNumber(time.offsetMinutes) + " د" else " − " + arabicNumber(-time.offsetMinutes) + " د"
}
internal val dhikrWeekdays = listOf("الإثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت", "الأحد")
internal fun dhikrRuleSummary(rule: DhikrReminder) = (if (rule.daysOfWeek.size == 7) "كل يوم" else rule.daysOfWeek.sorted().joinToString("، ") { dhikrWeekdays[it - 1] }) +
    " · هدفك " + arabicNumber(rule.targetCount) + " مرة\nمن " + dhikrTimeLabel(rule.start) + " إلى " + dhikrTimeLabel(rule.end) +
    (if (rule.endNextDay == true) " في اليوم التالي" else "") + " · " + when (rule.cadence) {
        DhikrCadence.GENTLE -> "حتى ٣ تذكيرات"; DhikrCadence.BALANCED -> "حتى ٥ تذكيرات"
        DhikrCadence.HOURLY -> "كل ساعة"; else -> "كل " + arabicNumber(rule.intervalMinutes) + " دقيقة"
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
internal fun openDhikrNotificationSettings(activity: AppCompatActivity) {
    DhikrReminderScheduler.ensureChannel(activity)
    val enabled = androidx.core.app.NotificationManagerCompat.from(activity).areNotificationsEnabled()
    activity.startActivity(Intent(if (enabled) Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS else Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, DhikrReminderScheduler.CHANNEL_ID))
}

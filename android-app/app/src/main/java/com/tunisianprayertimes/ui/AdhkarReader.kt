package com.tunisianprayertimes.ui

import android.view.HapticFeedbackConstants
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tunisianprayertimes.R
import com.tunisianprayertimes.adhkar.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Calm reading surface: dhikr text first, a compact counter with increment/undo and
 * circular previous/next that stay fixed while long text scrolls. Source, explanation,
 * and session progress are deliberately secondary; rare actions live in the overflow menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun DhikrReader(
    activity: AppCompatActivity, state: DhikrState, session: DhikrSession, now: Long,
    onDismiss: () -> Unit, onCount: (Int) -> Unit, onMove: (Int) -> Unit, onSkip: () -> Unit, onRemove: () -> Unit,
    onFavourite: () -> Unit, onCollections: () -> Unit,
    onEditCustom: () -> Unit, onDeleteCustom: () -> Unit,
    onAddToCollection: () -> Unit, onReorderCollection: () -> Unit,
    onTextSize: (Int) -> Unit, onHaptics: (Boolean) -> Unit,
    onNewSession: () -> Unit, reminder: DhikrReminder?, onReminder: () -> Unit,
    onConfigureReminder: () -> Unit, onDeleteReminder: () -> Unit,
    skipReminderLabel: String?, onSkipReminder: () -> Unit,
) {
    val p = LocalAdhkarPalette.current
    val entry = state.findDhikr(session.itemId) ?: return
    val favourite = entry.id in state.favourites
    val count = session.counts[entry.id] ?: 0
    val target = state.target(session)
    val hundredTahlil = target >= 100 && entry.id == TAHLIL_DAILY_ID
    val sourceEntry = if (hundredTahlil) entry.copy(
        reference = "صحيح البخاري 3293؛ صحيح مسلم 2691؛ مائة مرة في يوم",
        explanation = "هذا التهليل إفراد لله بالعبادة والملك والحمد، وإقرار بعموم قدرته. من قاله مائة مرة في يوم كانت له عدل عشر رقاب، وكُتبت له مائة حسنة، ومُحيت عنه مائة سيئة، وكان في حرز من الشيطان حتى يمسي؛ ولم يأت أحد بأفضل مما جاء به إلا من عمل أكثر من ذلك.",
        narration = DhikrNarrations.tahlilHundred,
    ) else entry
    val complete = count >= target
    val skipped = entry.id in session.skippedIds
    val occurrence = session.occurrenceId?.let { state.occurrences[it] }
    val liveRule = occurrence?.let { current -> state.reminders.any {
        it.id == current.ruleId && it.enabled && it.revision == current.revision
    } } == true
    val open = if (session.occurrenceId == null) true else occurrence != null && liveRule &&
        now in occurrence.startMillis until occurrence.endMillis &&
        occurrence.status != DhikrOccurrenceStatus.SKIPPED && occurrence.status != DhikrOccurrenceStatus.REPLACED &&
        occurrence.status != DhikrOccurrenceStatus.DONE
    var sources by remember { mutableStateOf(false) }
    var textSettings by remember { mutableStateOf(false) }
    var showExplanation by remember(session.itemId) { mutableStateOf(false) }
    var showNarration by remember(session.itemId) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current
    val view = LocalView.current
    val swipeThresholdPx = with(LocalDensity.current) { 72.dp.toPx() }
    val explanationRequester = remember(entry.id) { BringIntoViewRequester() }
    val narrationRequester = remember(entry.id) { BringIntoViewRequester() }
    DisposableEffect(session.occurrenceId, lifecycle) {
        fun presence(active: Boolean) { DhikrReadingPresence.occurrenceId = if (active) session.occurrenceId else null }
        presence(lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) presence(true)
            if (event == Lifecycle.Event.ON_PAUSE) presence(false)
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { presence(false); lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(session.occurrenceId) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { DhikrReminderScheduler.refresh(activity) }
    }
    LaunchedEffect(showExplanation, entry.id) {
        if (showExplanation) explanationRequester.bringIntoView()
    }
    LaunchedEffect(showNarration, entry.id) {
        if (showNarration) narrationRequester.bringIntoView()
    }
    val canNavigate = session.itemIds.size > 1
    val currentIndex = session.index.coerceIn(0, session.itemIds.lastIndex)
    val previousEntry = if (canNavigate) state.findDhikr(session.itemIds[(currentIndex - 1 + session.itemIds.size) % session.itemIds.size]) else null
    val nextEntry = if (canNavigate) state.findDhikr(session.itemIds[(currentIndex + 1) % session.itemIds.size]) else null
    val swipeScope = rememberCoroutineScope()
    val swipeOffset = remember(session.id, session.itemId) { mutableFloatStateOf(0f) }
    val swipeAnimation = remember(session.id, session.itemId) { mutableStateOf<Job?>(null) }
    val readingScroll = remember(session.id, session.itemId) { ScrollState(0) }
    DisposableEffect(session.id, session.itemId) {
        onDispose { swipeAnimation.value?.cancel() }
    }
    val canRemove = session.category != null || session.itemIds.size > 1
    val canCount = open && count < Int.MAX_VALUE && (entry.steps.isEmpty() || count < entry.defaultCount) &&
        (session.category == null || !complete)
    val canAdvanceOnTap = complete && canNavigate
    val canUndo = count > 0 && open
    val sessionDone = session.itemIds.count { it in session.skippedIds || (session.counts[it] ?: 0) >= state.target(session, it) }
    fun countOnce() {
        if (!canCount) return
        onCount(1)
        if (state.countHaptics) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }
    fun activateDhikr() {
        if (canAdvanceOnTap) onMove(1) else countOnce()
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        AdhkarDialogSystemBars()
        Surface(color = p.background, modifier = Modifier.fillMaxSize().testTag("adhkar_reader")) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                // Header: one action on each side keeps the title centered.
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss, modifier = Modifier.testTag("adhkar_reader_back")) {
                        DhikrIcon(R.drawable.ic_adhkar_back, "العودة", modifier = Modifier.size(22.dp))
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(reminder?.let { reminderTitle(it, state) } ?: entry.title,
                            fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                            color = AdhkarHeading, maxLines = 1)
                        if (reminder == null) Text(when {
                            session.category != null -> collectionTitle(session.category)
                            else -> "قراءة مستقلة"
                        }, color = p.muted, fontSize = 12.sp)
                    }
                    Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.testTag("adhkar_reader_menu")) {
                            DhikrIcon(R.drawable.ic_adhkar_more, "خيارات الذكر", modifier = Modifier.size(22.dp))
                        }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            if (reminder != null) {
                                DropdownMenuItem(leadingIcon = { DhikrIcon(R.drawable.ic_adhkar_bell, tint = p.primary, modifier = Modifier.size(20.dp)) },
                                    text = { Text("إعدادات التذكير") },
                                    onClick = { menu = false; onConfigureReminder() },
                                    modifier = Modifier.testTag("adhkar_reader_configure_reminder"))
                                skipReminderLabel?.let { label ->
                                    DropdownMenuItem(leadingIcon = { DhikrIcon(R.drawable.ic_bell_off, tint = p.primary, modifier = Modifier.size(20.dp)) },
                                        text = { Text(label) },
                                        onClick = { menu = false; onSkipReminder() },
                                        modifier = Modifier.testTag("adhkar_reader_skip_reminder"))
                                }
                                DropdownMenuItem(leadingIcon = { DhikrIcon(R.drawable.ic_delete, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp)) },
                                    text = { Text("حذف التذكير", color = MaterialTheme.colorScheme.error) },
                                    onClick = { menu = false; onDeleteReminder() },
                                    modifier = Modifier.testTag("adhkar_reader_delete_reminder"))
                            } else DropdownMenuItem(leadingIcon = { DhikrIcon(R.drawable.ic_adhkar_bell, tint = p.primary, modifier = Modifier.size(20.dp)) },
                                text = { Text("إنشاء تذكير لهذا الذكر") },
                                onClick = { menu = false; onReminder() })
                            HorizontalDivider(color = AdhkarBorder)
                            DropdownMenuItem(
                                leadingIcon = {
                                    DhikrIcon(if (favourite) R.drawable.ic_adhkar_heart_filled else R.drawable.ic_adhkar_heart,
                                        tint = if (favourite) AdhkarHeart else p.primary, modifier = Modifier.size(20.dp))
                                },
                                text = { Text(if (favourite) "إزالة من المفضلة" else "إضافة إلى المفضلة") },
                                onClick = { menu = false; onFavourite() },
                                modifier = Modifier.testTag("adhkar_reader_favourite"))
                            DropdownMenuItem(
                                leadingIcon = { DhikrIcon(R.drawable.ic_adhkar_plus, tint = p.primary, modifier = Modifier.size(20.dp)) },
                                text = { Text("إضافة هذا الذكر إلى مجموعة") },
                                onClick = { menu = false; onCollections() },
                                modifier = Modifier.testTag("adhkar_reader_collections"))
                            if (entry.custom) {
                                DropdownMenuItem(text = { Text("تعديل الذكر") },
                                    onClick = { menu = false; onEditCustom() },
                                    modifier = Modifier.testTag("adhkar_reader_edit_custom"))
                                DropdownMenuItem(text = { Text("حذف الذكر", color = MaterialTheme.colorScheme.error) },
                                    onClick = { menu = false; onDeleteCustom() },
                                    modifier = Modifier.testTag("adhkar_reader_delete_custom"))
                            }
                            DropdownMenuItem(leadingIcon = { DhikrIcon(R.drawable.ic_adhkar_next, tint = p.primary, modifier = Modifier.size(20.dp)) },
                                text = { Text("تخطّي الذكر") },
                                enabled = open,
                                onClick = { menu = false; onSkip() })
                            if (session.category != null) DropdownMenuItem(
                                leadingIcon = { DhikrIcon(R.drawable.ic_adhkar_plus, tint = p.primary, modifier = Modifier.size(20.dp)) },
                                text = { Text("إضافة ذكر آخر إلى هذه القائمة") },
                                onClick = { menu = false; onAddToCollection() },
                                modifier = Modifier.testTag("adhkar_reader_add_to_list"))
                            if (session.category != null && session.itemIds.size > 1) DropdownMenuItem(
                                leadingIcon = { DhikrIcon(R.drawable.ic_adhkar_reorder, tint = p.primary, modifier = Modifier.size(20.dp)) },
                                text = { Text("ترتيب") },
                                onClick = { menu = false; onReorderCollection() },
                                modifier = Modifier.testTag("adhkar_reader_reorder_list"))
                            if (canRemove) DropdownMenuItem(
                                leadingIcon = { DhikrIcon(R.drawable.ic_delete, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp)) },
                                text = { Text("إزالة من القائمة", color = MaterialTheme.colorScheme.error) },
                                onClick = { menu = false; confirmRemove = true })
                            HorizontalDivider(color = AdhkarBorder)
                            DropdownMenuItem(leadingIcon = {
                                Text("Aa", color = p.primary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            }, text = { Text("حجم النص") }, onClick = { menu = false; textSettings = true })
                        }
                    }
                }

                // The reminder title is the only heading for a reminder reading.
                if (reminder == null) {
                    Surface(shape = RoundedCornerShape(50), color = AdhkarSoftGreen,
                        modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("الذكر " + latinNumber(session.index + 1) + " من " + latinNumber(session.itemIds.size),
                            Modifier.padding(horizontal = 16.dp, vertical = 6.dp), color = p.muted, fontSize = 12.sp)
                    }
                }

                // Dragging right reveals the next dhikr on the left; dragging left reveals the previous on the right.
                // The session changes only after the destination has finished sliding into place.
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                    val paneWidthPx = constraints.maxWidth.toFloat()
                    fun settleSwipe(direction: Int?) {
                        swipeAnimation.value?.cancel()
                        swipeAnimation.value = swipeScope.launch {
                            val destination = when (direction) {
                                1 -> paneWidthPx
                                -1 -> -paneWidthPx
                                else -> 0f
                            }
                            animate(swipeOffset.floatValue, destination,
                                animationSpec = tween(180, easing = FastOutSlowInEasing)) { value, _ ->
                                swipeOffset.floatValue = value
                            }
                            if (direction == null) swipeOffset.floatValue = 0f else onMove(direction)
                        }
                    }
                    val drag = swipeOffset.floatValue
                    val preview = if (drag > 0f) nextEntry else previousEntry
                    if (drag != 0f && preview != null) {
                        Box(Modifier.fillMaxSize()
                            .graphicsLayer { translationX = drag - if (drag > 0f) paneWidthPx else -paneWidthPx }
                            .background(p.background).clearAndSetSemantics { }) {
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                                .padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Spacer(Modifier.height(28.dp))
                                Text(preview.text, fontFamily = AdhkarReadingFont, fontSize = state.textSize.sp,
                                    lineHeight = (state.textSize * 1.9f).sp, color = p.forest,
                                    textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.ContentOrRtl),
                                    modifier = Modifier.fillMaxWidth())
                                Spacer(Modifier.height(20.dp))
                                if (preview.reference.isNotBlank() || preview.custom) {
                                    Text(preview.reference.ifBlank { "ذكر أضفته" }, color = p.muted,
                                        fontSize = 13.sp, textAlign = TextAlign.Center)
                                }
                            }
                            Text((if (drag > 0f) "التالي · " else "السابق · ") + preview.title,
                                Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 24.dp),
                                color = p.primary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    // Dhikr text scrolls; the counter and navigation below stay put.
                    Column(Modifier.fillMaxSize()
                        .graphicsLayer { translationX = swipeOffset.floatValue }
                        .background(p.background).verticalScroll(readingScroll)
                        .pointerInput(session.id, session.itemId, canNavigate, paneWidthPx, swipeThresholdPx) {
                            if (!canNavigate) return@pointerInput
                            detectHorizontalDragGestures(
                                onDragStart = { swipeAnimation.value?.cancel() },
                                onHorizontalDrag = { change, dragAmount ->
                                    change.consume()
                                    swipeOffset.floatValue = (swipeOffset.floatValue + dragAmount)
                                        .coerceIn(-paneWidthPx, paneWidthPx)
                                },
                                onDragEnd = {
                                    val direction = when {
                                        abs(swipeOffset.floatValue) < swipeThresholdPx -> null
                                        swipeOffset.floatValue > 0f -> 1
                                        else -> -1
                                    }
                                    settleSwipe(direction)
                                },
                                onDragCancel = { settleSwipe(null) },
                            )
                        }
                        .padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(28.dp))
                    if (entry.steps.isEmpty()) {
                        Text(entry.text, fontFamily = AdhkarReadingFont, fontSize = state.textSize.sp,
                            lineHeight = (state.textSize * 1.9f).sp, color = p.forest, textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.ContentOrRtl),
                            modifier = Modifier.fillMaxWidth()
                                .clickable(enabled = canCount || canAdvanceOnTap,
                                    onClickLabel = if (canAdvanceOnTap) "الانتقال إلى الذكر التالي" else "زيادة العدد",
                                    onClick = ::activateDhikr)
                                .testTag("adhkar_reader_text"))
                    } else {
                        var completedBefore = 0
                        Column(Modifier.fillMaxWidth()
                            .clickable(enabled = canCount || canAdvanceOnTap,
                                onClickLabel = if (canAdvanceOnTap) "الانتقال إلى الذكر التالي" else "احتساب الصيغة الحالية",
                                onClick = ::activateDhikr)
                            .testTag("adhkar_reader_text"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            entry.steps.forEach { step ->
                                val stepCount = (count - completedBefore).coerceIn(0, step.repetitions)
                                val activeStep = count in completedBefore until (completedBefore + step.repetitions)
                                Surface(shape = RoundedCornerShape(18.dp),
                                    color = if (activeStep) AdhkarSoftGreen else AdhkarSurface,
                                    border = BorderStroke(1.dp, if (activeStep) p.primary else AdhkarBorder),
                                    modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                                        Text(step.text, Modifier.fillMaxWidth(), fontFamily = AdhkarReadingFont,
                                            fontSize = if (activeStep) state.textSize.sp else 18.sp,
                                            lineHeight = if (activeStep) (state.textSize * 1.7f).sp else 30.sp,
                                            color = p.forest, textAlign = TextAlign.Right)
                                        Text((if (activeStep) "الآن · " else "") + step.label + " · " +
                                            latinNumber(stepCount) + " من " + latinNumber(step.repetitions),
                                            color = if (activeStep) p.primary else p.muted, fontSize = 13.sp)
                                    }
                                }
                                completedBefore += step.repetitions
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    if (sourceEntry.reference.isNotBlank() || entry.custom) {
                        Text(sourceEntry.reference.ifBlank { "ذكر أضفته" }, color = p.muted, fontSize = 13.sp,
                            textAlign = TextAlign.Center, modifier = Modifier.clickable { sources = true })
                    }
                    if (skipped && !complete) Text("تم تخطّي هذا الذكر", color = p.primary, fontSize = 12.sp,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                    if (sourceEntry.explanation.isNotBlank() || sourceEntry.narration.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (sourceEntry.explanation.isNotBlank()) ReaderToggleChip(R.drawable.ic_adhkar_info, "الشرح",
                                if (showExplanation) "إخفاء الشرح" else "الشرح", "adhkar_explanation_toggle") {
                                showExplanation = !showExplanation
                            }
                            if (sourceEntry.narration.isNotBlank()) ReaderToggleChip(R.drawable.ic_adhkar_bookmark, "الحديث",
                                if (showNarration) "إخفاء الحديث" else "الحديث", "adhkar_narration_toggle") {
                                showNarration = !showNarration
                            }
                        }
                        if (showNarration) {
                            Spacer(Modifier.height(12.dp))
                            Surface(shape = RoundedCornerShape(18.dp), color = AdhkarSurface,
                                border = BorderStroke(1.dp, AdhkarBorder), modifier = Modifier.fillMaxWidth()
                                    .bringIntoViewRequester(narrationRequester).testTag("adhkar_narration")) {
                                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                                    Text("نص الحديث", color = AdhkarHeading, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Spacer(Modifier.height(8.dp))
                                    Text(sourceEntry.narration, Modifier.fillMaxWidth(), color = p.forest,
                                        fontSize = 15.sp, lineHeight = 30.sp, textAlign = TextAlign.Right)
                                }
                            }
                        }
                        if (showExplanation) {
                            Spacer(Modifier.height(12.dp))
                            Surface(shape = RoundedCornerShape(18.dp), color = AdhkarSurface,
                                border = BorderStroke(1.dp, AdhkarBorder), modifier = Modifier.fillMaxWidth()
                                    .bringIntoViewRequester(explanationRequester)) {
                                Text(sourceEntry.explanation, Modifier.fillMaxWidth().padding(18.dp), color = p.forest,
                                    fontSize = 15.sp, lineHeight = 30.sp, textAlign = TextAlign.Right)
                            }
                        }
                    }
                    if (session.occurrenceId == null || state.isComplete(session) || !open) {
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onNewSession, modifier = Modifier.testTag("adhkar_new_session")) {
                            Text(if (session.occurrenceId != null) "بدء قراءة مستقلة" else "بدء جلسة جديدة",
                                color = p.primary, fontSize = 13.sp)
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    }
                }

                // Counter: increment on the right, undo on the left, tap the ring to count too.
                Row(Modifier.fillMaxWidth().padding(horizontal = 32.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    CounterButton(icon = R.drawable.ic_adhkar_plus, description = "زيادة العدد", enabled = canCount,
                        size = 60, onClick = ::countOnce, tag = "adhkar_increment")
                    Box(Modifier.size(124.dp).clip(CircleShape)
                        .clickable(enabled = canCount || canAdvanceOnTap, onClick = ::activateDhikr)
                        .testTag("adhkar_count")
                        .semantics {
                            contentDescription = if (canAdvanceOnTap) "الانتقال إلى الذكر التالي"
                                else "احتساب قراءة واحدة لهذا الذكر"
                            stateDescription = latinNumber(count) + " من " + latinNumber(target)
                            liveRegion = LiveRegionMode.Polite
                        },
                        contentAlignment = Alignment.Center) {
                        CounterRing(progress = (count.toFloat() / target.coerceAtLeast(1)).coerceIn(0f, 1f),
                            color = p.primary, track = AdhkarRingTrack)
                        Column(Modifier.padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(latinNumber(count), fontSize = 40.sp, fontWeight = FontWeight.Bold, color = p.forest)
                        }
                        Text("من " + latinNumber(target), Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp),
                            fontSize = 13.sp, color = p.muted)
                    }
                    CounterButton(icon = R.drawable.ic_adhkar_undo, description = "تراجع", enabled = canUndo,
                        size = 52, onClick = { onCount(-1) }, tag = "adhkar_undo")
                }

                // Navigation is free and circular; the thin bar tracks session completion.
                Row(Modifier.fillMaxWidth().padding(top = 14.dp, start = 12.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onMove(-1) }, enabled = canNavigate,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        DhikrIcon(R.drawable.ic_adhkar_back, tint = if (canNavigate) p.primary else p.muted, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("السابق", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Box(Modifier.width(1.dp).height(26.dp).background(AdhkarBorder))
                    TextButton(onClick = { onMove(1) }, enabled = canNavigate,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("adhkar_next_item")) {
                        Text("التالي", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(8.dp))
                        DhikrIcon(R.drawable.ic_adhkar_next, tint = if (canNavigate) p.primary else p.muted, modifier = Modifier.size(18.dp))
                    }
                }
                Box(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 10.dp)
                    .height(4.dp).clip(RoundedCornerShape(2.dp)).background(AdhkarRingTrack)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(sessionDone.toFloat() / session.itemIds.size.coerceAtLeast(1))
                        .clip(RoundedCornerShape(2.dp)).background(p.primary))
                }
            }
        }
        if (sources) ModalBottomSheet(onDismissRequest = { sources = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = p.background) {
            DhikrSheetHeader("المصدر وعدد التكرار") { sources = false }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text(if (entry.custom) "هذا ذكر أضفته بنفسك، ويُعرض نصّه كما كتبته." else sourceEntry.reference,
                    color = AdhkarHeading, fontSize = 16.sp, lineHeight = 30.sp)
                if (sourceEntry.narration.isNotBlank()) {
                    Text("نص الحديث", color = AdhkarHeading, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(sourceEntry.narration, color = p.forest, fontSize = 15.sp, lineHeight = 30.sp)
                }
                if (sourceEntry.explanation.isNotBlank()) {
                    Text("شرح الألفاظ", color = AdhkarHeading, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(sourceEntry.explanation, color = p.forest, fontSize = 15.sp, lineHeight = 30.sp)
                }
                Text(if (entry.steps.isNotEmpty()) "تُحسب الصيغ الأربع ذكرًا واحدًا: التسبيح 33، والتحميد 33، والتكبير 33، ثم التهليل مرة واحدة."
                    else if (entry.custom) "العدد الافتراضي: " + latinNumber(entry.defaultCount) + ". يمكنك تغييره عند إنشاء تذكير، وتعديل الذكر أو حذفه من قائمة الخيارات هنا."
                    else if (occurrence != null || (reminder != null && reminder.collection == null)) "هدف التذكير: " + latinNumber(target) + ". يمكنك تغييره دون تعديل عدد التكرار الافتراضي للذكر."
                    else "عدد التكرار الافتراضي: " + latinNumber(entry.defaultCount) + ". إذا لم يحدد المصدر عددًا، فهذا العدد يساعدك على العدّ ولا يقيّد تكرار الذكر.",
                    color = p.muted, fontSize = 14.sp, lineHeight = 27.sp)
                if (!entry.custom) Text("إصدار النص: " + bidiClock(entry.contentVersion) + " · " + entry.editorialNote,
                    color = p.muted, fontSize = 12.sp, lineHeight = 23.sp)
            }
        }
        if (textSettings) AlertDialog(onDismissRequest = { textSettings = false }, title = { Text("حجم النص") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("يبقى تقدّمك محفوظًا عند تغيير حجم النص.", color = p.muted)
                Slider(value = state.textSize.toFloat(), onValueChange = { onTextSize(it.toInt()) }, valueRange = 24f..40f, steps = 7)
                Text("بِسْمِ اللَّهِ الرَّحْمَٰنِ الرَّحِيمِ", fontFamily = AdhkarReadingFont, fontSize = state.textSize.sp, color = p.forest)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("الاهتزاز عند العدّ", Modifier.weight(1f), color = AdhkarHeading, fontSize = 14.sp)
                    Switch(state.countHaptics, onCheckedChange = onHaptics)
                }
            } }, confirmButton = { TextButton(onClick = { textSettings = false }) { Text("تم") } })
        if (confirmRemove) AlertDialog(onDismissRequest = { confirmRemove = false },
            title = { Text("إزالة الذكر من القائمة؟") },
            text = { Text(if (session.category != null)
                "سيُزال «" + entry.title + "» من هذه القراءة ومن مجموعة " + collectionTitle(session.category) + " فقط. سيبقى متاحًا في مكتبة الأذكار، ويمكنك إضافته إلى المجموعة لاحقًا."
                else "سيُزال «" + entry.title + "» من هذه القراءة فقط.",
                color = p.muted) },
            confirmButton = { TextButton(onClick = { confirmRemove = false; onRemove() },
                modifier = Modifier.testTag("adhkar_confirm_remove")) {
                Text("إزالة", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
            } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("إلغاء") } })
    }
}

@Composable
private fun CounterButton(icon: Int, description: String, enabled: Boolean, size: Int, tag: String, onClick: () -> Unit) {
    val p = LocalAdhkarPalette.current
    Box(Modifier.size(size.dp).clip(CircleShape).background(AdhkarSoftGreen)
        .clickable(enabled = enabled, onClick = onClick)
        .testTag(tag), contentAlignment = Alignment.Center) {
        DhikrIcon(icon, description, tint = if (enabled) p.primary else p.muted, modifier = Modifier.size((size * 0.4f).dp))
    }
}

/** Pill under the dhikr that expands a detail card (explanation or source hadith) in place. */
@Composable
private fun ReaderToggleChip(icon: Int, description: String, label: String, tag: String, onClick: () -> Unit) {
    val p = LocalAdhkarPalette.current
    Surface(shape = RoundedCornerShape(50), color = AdhkarSoftGreen,
        modifier = Modifier.clickable(onClick = onClick).testTag(tag)) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            DhikrIcon(icon, description, tint = p.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, color = p.primary, fontSize = 13.sp)
        }
    }
}

@Composable
private fun CounterRing(progress: Float, color: Color, track: Color) {
    Canvas(Modifier.fillMaxSize().padding(6.dp)) {
        val stroke = 7.dp.toPx()
        val inset = stroke / 2
        val arcSize = Size(size.width - stroke, size.height - stroke)
        val topLeft = Offset(inset, inset)
        drawArc(color = track, startAngle = -90f, sweepAngle = 360f, useCenter = false,
            topLeft = topLeft, size = arcSize, style = Stroke(width = stroke, cap = StrokeCap.Round))
        drawArc(color = color, startAngle = -90f, sweepAngle = 360f * progress, useCenter = false,
            topLeft = topLeft, size = arcSize, style = Stroke(width = stroke, cap = StrokeCap.Round))
    }
}

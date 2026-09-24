package com.tunisianprayertimes.ui

import android.view.HapticFeedbackConstants
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tunisianprayertimes.R
import com.tunisianprayertimes.adhkar.*
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
    onAddToCollection: () -> Unit, onReorderCollection: () -> Unit,
    onTextSize: (Int) -> Unit, onHaptics: (Boolean) -> Unit,
    onNewSession: () -> Unit, onReminder: () -> Unit,
) {
    val p = LocalAdhkarPalette.current
    val entry = state.findDhikr(session.itemId) ?: return
    val count = session.counts[entry.id] ?: 0
    val target = state.target(session)
    val complete = count >= target
    val skipped = entry.id in session.skippedIds
    val occurrence = session.occurrenceId?.let { state.occurrences[it] }
    val open = occurrence == null || (now in occurrence.startMillis until occurrence.endMillis &&
        occurrence.status != DhikrOccurrenceStatus.SKIPPED && occurrence.status != DhikrOccurrenceStatus.REPLACED)
    var sources by remember { mutableStateOf(false) }
    var textSettings by remember { mutableStateOf(false) }
    var showExplanation by remember(session.itemId) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current
    val view = LocalView.current
    val swipeThresholdPx = with(LocalDensity.current) { 72.dp.toPx() }
    val explanationRequester = remember(entry.id) { BringIntoViewRequester() }
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
    val canNavigate = session.itemIds.size > 1
    val canRemove = session.category != null || session.itemIds.size > 1
    val canCount = open && !complete
    val canAdvanceFromCounter = complete && canNavigate
    val canUndo = count > 0 && open
    val sessionDone = session.itemIds.count { it in session.skippedIds || (session.counts[it] ?: 0) >= state.target(session, it) }
    fun countOnce() {
        if (!canCount) return
        onCount(1)
        if (state.countHaptics) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        AdhkarDialogSystemBars()
        Surface(color = p.background, modifier = Modifier.fillMaxSize().testTag("adhkar_reader")) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                // Header: back at the RTL start (right), overflow at the end (left), title centered.
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss, modifier = Modifier.testTag("adhkar_reader_back")) {
                        DhikrIcon(R.drawable.ic_adhkar_back, "العودة", modifier = Modifier.size(22.dp))
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(entry.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                            color = AdhkarHeading, maxLines = 1)
                        Text(when {
                            session.category != null -> collectionTitle(session.category)
                            occurrence != null -> "هدف شخصي"
                            else -> "قراءة مستقلة"
                        }, color = p.muted, fontSize = 12.sp)
                    }
                    Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.testTag("adhkar_reader_menu")) {
                            DhikrIcon(R.drawable.ic_adhkar_more, "خيارات الذكر", modifier = Modifier.size(22.dp))
                        }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(leadingIcon = { DhikrIcon(R.drawable.ic_adhkar_bell, tint = p.primary, modifier = Modifier.size(20.dp)) },
                                text = { Text("تذكير بهذا الذكر") },
                                onClick = { menu = false; onReminder() })
                            DropdownMenuItem(leadingIcon = { DhikrIcon(R.drawable.ic_adhkar_next, tint = p.primary, modifier = Modifier.size(20.dp)) },
                                text = { Text("تخطّي الذكر") },
                                onClick = { menu = false; onSkip() })
                            if (session.category != null) DropdownMenuItem(
                                leadingIcon = { DhikrIcon(R.drawable.ic_adhkar_plus, tint = p.primary, modifier = Modifier.size(20.dp)) },
                                text = { Text("إضافة ذكر إلى القائمة") },
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

                // Session position, deliberately quiet.
                Surface(shape = RoundedCornerShape(50), color = AdhkarSoftGreen,
                    modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("الذكر " + latinNumber(session.index + 1) + " من " + latinNumber(session.itemIds.size),
                        Modifier.padding(horizontal = 16.dp, vertical = 6.dp), color = p.muted, fontSize = 12.sp)
                }

                // Dhikr text scrolls; the counter and navigation below stay put.
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .pointerInput(session.itemId, canNavigate, swipeThresholdPx) {
                        if (!canNavigate) return@pointerInput
                        var horizontalDrag = 0f
                        detectHorizontalDragGestures(
                            onHorizontalDrag = { _, dragAmount -> horizontalDrag += dragAmount },
                            onDragEnd = {
                                if (abs(horizontalDrag) >= swipeThresholdPx) {
                                    onMove(if (horizontalDrag < 0f) 1 else -1)
                                }
                                horizontalDrag = 0f
                            },
                            onDragCancel = { horizontalDrag = 0f },
                        )
                    }
                    .padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(28.dp))
                    Text(entry.text, fontFamily = AdhkarReadingFont, fontSize = state.textSize.sp,
                        lineHeight = (state.textSize * 1.9f).sp, color = p.forest, textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.ContentOrRtl),
                        modifier = Modifier.fillMaxWidth().testTag("adhkar_reader_text"))
                    Spacer(Modifier.height(20.dp))
                    if (entry.reference.isNotBlank() || entry.custom) {
                        Text(entry.reference.ifBlank { "ذكر خاص" }, color = p.muted, fontSize = 13.sp,
                            textAlign = TextAlign.Center, modifier = Modifier.clickable { sources = true })
                    }
                    if (skipped && !complete) Text("تم تخطّي هذا الذكر", color = p.primary, fontSize = 12.sp,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                    if (entry.explanation.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Surface(shape = RoundedCornerShape(50), color = AdhkarSoftGreen,
                            modifier = Modifier.clickable { showExplanation = !showExplanation }
                                .testTag("adhkar_explanation_toggle")) {
                            Row(Modifier.padding(horizontal = 18.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                DhikrIcon(R.drawable.ic_adhkar_info, "الشرح", tint = p.primary, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(if (showExplanation) "إخفاء الشرح" else "الشرح", color = p.primary, fontSize = 13.sp)
                            }
                        }
                        if (showExplanation) {
                            Spacer(Modifier.height(12.dp))
                            Surface(shape = RoundedCornerShape(18.dp), color = AdhkarSurface,
                                border = BorderStroke(1.dp, AdhkarBorder), modifier = Modifier.fillMaxWidth()
                                    .bringIntoViewRequester(explanationRequester)) {
                                Text(entry.explanation, Modifier.fillMaxWidth().padding(18.dp), color = p.forest,
                                    fontSize = 15.sp, lineHeight = 30.sp, textAlign = TextAlign.Right)
                            }
                        }
                    }
                    if (state.isComplete(session) || !open) {
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onNewSession, modifier = Modifier.testTag("adhkar_new_session")) {
                            Text(if (session.occurrenceId != null) "بدء قراءة مستقلة" else "بدء جلسة جديدة",
                                color = p.primary, fontSize = 13.sp)
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }

                // Counter: increment on the right, undo on the left, tap the ring to count too.
                Row(Modifier.fillMaxWidth().padding(horizontal = 32.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    CounterButton(icon = R.drawable.ic_adhkar_plus, description = "زيادة العدد", enabled = canCount,
                        size = 60, onClick = ::countOnce, tag = "adhkar_increment")
                    Box(Modifier.size(124.dp).clip(CircleShape)
                        .clickable(enabled = canCount || canAdvanceFromCounter) {
                            if (canAdvanceFromCounter) onMove(1) else countOnce()
                        }
                        .testTag("adhkar_count")
                        .semantics {
                            contentDescription = if (canAdvanceFromCounter) "الانتقال إلى الذكر التالي"
                                else "إتمام قراءة واحدة للذكر كاملًا"
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
            DhikrSheetHeader("عن النص والعدد") { sources = false }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text(if (entry.custom) "ذكر خاص أضفته أنت؛ يُعرض النص كما كتبته دون تغيير." else entry.reference,
                    color = AdhkarHeading, fontSize = 16.sp, lineHeight = 30.sp)
                if (entry.explanation.isNotBlank()) {
                    Text("شرح الألفاظ", color = AdhkarHeading, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(entry.explanation, color = p.forest, fontSize = 15.sp, lineHeight = 30.sp)
                }
                Text(if (entry.custom) "العدد الافتراضي " + latinNumber(entry.defaultCount) + " مرة، ويمكنك تعديله عند إنشاء تذكير. تعديل الذكر أو حذفه أو إضافته إلى المجموعات متاح من مكتبة الأذكار."
                    else if (occurrence != null) "العدد " + latinNumber(occurrence.target) + " هدف اخترته لهذه الفترة، مستقل عن العدد الوارد في المصدر."
                    else "عدد القراءة الافتراضي مبين مع مصدر الذكر. العدد الواحد في الأذكار غير المقيّدة بعدد هو بداية للعدّ، وليس حدًّا للتكرار.",
                    color = p.muted, fontSize = 14.sp, lineHeight = 27.sp)
                if (!entry.custom) Text("إصدار النص: " + bidiClock(entry.contentVersion) + " · " + entry.editorialNote,
                    color = p.muted, fontSize = 12.sp, lineHeight = 23.sp)
            }
        }
        if (textSettings) AlertDialog(onDismissRequest = { textSettings = false }, title = { Text("حجم النص") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("تُحفظ القراءة والعدد عند تغيير الحجم.", color = p.muted)
                Slider(value = state.textSize.toFloat(), onValueChange = { onTextSize(it.toInt()) }, valueRange = 24f..40f, steps = 7)
                Text("بِسْمِ اللَّهِ الرَّحْمَٰنِ الرَّحِيمِ", fontFamily = AdhkarReadingFont, fontSize = state.textSize.sp, color = p.forest)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("اهتزاز العدّ", Modifier.weight(1f), color = AdhkarHeading, fontSize = 14.sp)
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

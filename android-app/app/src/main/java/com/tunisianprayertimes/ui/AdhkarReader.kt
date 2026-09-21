package com.tunisianprayertimes.ui

import android.view.HapticFeedbackConstants
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tunisianprayertimes.R
import com.tunisianprayertimes.adhkar.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable internal fun DhikrReader(
    activity: AppCompatActivity, state: DhikrState, session: DhikrSession, now: Long,
    onDismiss: () -> Unit, onCount: (Int) -> Unit, onMove: (Int) -> Unit, onFavourite: () -> Unit,
    onTextSize: (Int) -> Unit, onHaptics: (Boolean) -> Unit, onNewSession: () -> Unit, onReminder: () -> Unit,
) {
    val p = LocalAdhkarPalette.current
    val entry = DhikrCatalog.find(session.itemId) ?: return
    val count = session.counts[entry.id] ?: 0
    val target = state.target(session)
    val complete = count >= target
    val occurrence = session.occurrenceId?.let { state.occurrences[it] }
    val open = occurrence == null || (now in occurrence.startMillis until occurrence.endMillis &&
        occurrence.status != DhikrOccurrenceStatus.SKIPPED && occurrence.status != DhikrOccurrenceStatus.REPLACED)
    var sources by remember { mutableStateOf(false) }
    var textSettings by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current
    val view = LocalView.current
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
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val dialogView = LocalView.current
        val dark = isSystemInDarkTheme()
        DisposableEffect(dialogView, dark) {
            (dialogView.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, dialogView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            onDispose { }
        }
        val readingContent: @Composable (Modifier, Boolean) -> Unit = { contentModifier, compact ->
                key(session.itemId) {
                    Column(contentModifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        if (!compact) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            HorizontalDivider(Modifier.width(32.dp), color = p.border)
                            DhikrIcon(R.drawable.ic_adhkar_leaf, tint = p.gold, modifier = Modifier.size(16.dp))
                            HorizontalDivider(Modifier.width(32.dp), color = p.border)
                        }
                        Text(entry.text, fontFamily = AdhkarReadingFont, fontSize = state.textSize.sp,
                            lineHeight = (state.textSize * 1.9f).sp, color = p.ink, textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.ContentOrRtl),
                            modifier = Modifier.fillMaxWidth().testTag("adhkar_reader_text"))
                        TextButton(onClick = { sources = true }) {
                            DhikrIcon(R.drawable.ic_adhkar_info, modifier = Modifier.size(17.dp), tint = p.muted)
                            Spacer(Modifier.width(8.dp))
                            Text("عن النص والعدد", fontSize = 12.sp, color = p.muted)
                        }
                        if (!open) Text(when (occurrence?.status) {
                            DhikrOccurrenceStatus.SKIPPED -> "تُخطّيت هذه الفترة. تقدمك محفوظ."
                            DhikrOccurrenceStatus.REPLACED -> "عُدّل التذكير. تقدم هذه الفترة محفوظ."
                            else -> "هذه الفترة خارج وقت العدّ. يمكنك قراءة النص أو بدء قراءة مستقلة."
                        }, color = p.muted, fontSize = 13.sp, textAlign = TextAlign.Center)
                    }
                }
        }
        val countingControls: @Composable (Modifier, Boolean) -> Unit = { controlsModifier, compact ->
                Column(controlsModifier.fillMaxWidth().background(p.background).padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 8.dp)) {
                    Button(
                        onClick = {
                            onCount(1)
                            // View feedback honors the system haptics setting; never use an override flag.
                            if (state.countHaptics)
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        }, enabled = open && !complete, shape = RoundedCornerShape(22.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = p.forest, contentColor = Color.White,
                            disabledContainerColor = p.sage, disabledContentColor = p.primary),
                        modifier = Modifier.fillMaxWidth().heightIn(min = if (compact) 80.dp else 108.dp).testTag("adhkar_count")
                            .semantics { contentDescription = "إتمام قراءة واحدة للذكر كاملًا"; stateDescription = arabicNumber(count) + " من " + arabicNumber(target) },
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = 8.dp)) {
                            Text(arabicNumber(count), fontSize = if (compact) 30.sp else 38.sp)
                            Text(if (complete) "اكتمل العدد، تقبّل الله" else "اضغط بعد إتمام الذكر", fontSize = 13.sp, textAlign = TextAlign.Center)
                        }
                    }
                    if (complete && session.index < session.itemIds.lastIndex) TextButton(onClick = { onMove(1) }, modifier = Modifier.fillMaxWidth().testTag("adhkar_next_item")) {
                        Text("الذكر التالي")
                        Spacer(Modifier.width(8.dp)); DhikrIcon(R.drawable.ic_adhkar_next)
                    } else if (state.isComplete(session) || !open) TextButton(onClick = onNewSession, modifier = Modifier.fillMaxWidth()) {
                        Text(if (session.occurrenceId != null) "بدء قراءة مستقلة" else "بدء جلسة جديدة")
                    }
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { onCount(-1) }, enabled = count > 0 && open, modifier = Modifier.testTag("adhkar_undo")) {
                            DhikrIcon(R.drawable.ic_adhkar_undo, tint = if (count > 0 && open) p.primary else p.muted, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp)); Text("تراجع", fontSize = 12.sp)
                        }
                        IconButton(onClick = { textSettings = true }) { Text("ع", fontSize = 24.sp, color = p.primary, modifier = Modifier.semantics { contentDescription = "حجم النص" }) }
                        IconToggleButton(state.countHaptics, onCheckedChange = onHaptics) { DhikrIcon(R.drawable.ic_adhkar_vibrate, "اهتزاز العدّ") }
                        IconButton(onClick = onReminder) { DhikrIcon(R.drawable.ic_adhkar_bell, "تذكير بهذا الذكر") }
                        if (session.index > 0) IconButton(onClick = { onMove(-1) }) { DhikrIcon(R.drawable.ic_adhkar_back, "الذكر السابق") }
                    }
                }
        }
        Surface(color = p.background, modifier = Modifier.fillMaxSize().testTag("adhkar_reader")) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val compact = maxWidth > maxHeight && maxHeight < 500.dp
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { DhikrIcon(R.drawable.ic_adhkar_back, "العودة") }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(entry.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, color = p.ink)
                        Text(if (occurrence != null) "هدف شخصي" else session.category?.let(::collectionTitle) ?: "قراءة مستقلة",
                            color = p.muted, fontSize = 11.sp)
                    }
                    IconToggleButton(checked = entry.id in state.favourites, onCheckedChange = { onFavourite() }) {
                        DhikrIcon(R.drawable.ic_adhkar_bookmark, if (entry.id in state.favourites) "إزالة من المفضلة" else "حفظ في المفضلة",
                            tint = if (entry.id in state.favourites) p.primary else p.muted)
                    }
                }
                Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(if (session.itemIds.size > 1) "الذكر " + arabicNumber(session.index + 1) + " من " + arabicNumber(session.itemIds.size)
                            else if (occurrence != null) "هدف هذه الفترة" else "عدد القراءة", Modifier.weight(1f), fontSize = 12.sp, color = p.muted)
                        Text(arabicNumber(count) + " من " + arabicNumber(target), color = p.primary, fontSize = 12.sp,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    LinearProgressIndicator(progress = { count.toFloat() / target.coerceAtLeast(1) }, color = p.primary,
                        trackColor = p.sage, modifier = Modifier.fillMaxWidth().height(3.dp))
                }
                if (compact) {
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        readingContent(Modifier.weight(1f).fillMaxHeight(), true)
                        countingControls(Modifier.width(220.dp).fillMaxHeight().verticalScroll(rememberScrollState()), true)
                    }
                } else {
                    readingContent(Modifier.weight(1f), false)
                    countingControls(Modifier, false)
                }
            }
        }
        }
        if (sources) ModalBottomSheet(onDismissRequest = { sources = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = p.background) {
            DhikrSheetHeader("عن النص والعدد") { sources = false }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text(entry.reference, color = p.ink, fontSize = 16.sp, lineHeight = 30.sp)
                Text(if (occurrence != null) "العدد " + arabicNumber(occurrence.target) + " هدف اخترته لهذه الفترة، مستقل عن العدد الوارد في المصدر."
                    else "عدد القراءة الافتراضي مبين مع مصدر الذكر. العدد الواحد في الأذكار غير المقيّدة بعدد هو بداية للعدّ، وليس حدًّا للتكرار.",
                    color = p.muted, fontSize = 14.sp, lineHeight = 27.sp)
                Text("إصدار النص: " + bidiClock(entry.contentVersion) + " · " + entry.editorialNote, color = p.muted, fontSize = 12.sp, lineHeight = 23.sp)
            }
        }
        if (textSettings) AlertDialog(onDismissRequest = { textSettings = false }, title = { Text("حجم النص") },
            text = { Column {
                Text("تُحفظ القراءة والعدد عند تغيير الحجم.", color = p.muted)
                Slider(value = state.textSize.toFloat(), onValueChange = { onTextSize(it.toInt()) }, valueRange = 24f..40f, steps = 7)
                Text("بِسْمِ اللَّهِ الرَّحْمَٰنِ الرَّحِيمِ", fontFamily = AdhkarReadingFont, fontSize = state.textSize.sp, color = p.ink)
            } }, confirmButton = { TextButton(onClick = { textSettings = false }) { Text("تم") } })
    }
}

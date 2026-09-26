package com.tunisianprayertimes.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.R
import com.tunisianprayertimes.adhkar.DhikrCategory
import com.tunisianprayertimes.adhkar.DhikrState
import com.tunisianprayertimes.adhkar.collectionEntries
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
internal fun DhikrCollectionOrderDialog(
    category: DhikrCategory,
    state: DhikrState,
    onMove: (String, Int) -> Unit,
    onReorder: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalAdhkarPalette.current
    val entries = state.collectionEntries(category)
    val entryIds = entries.map { it.id }
    val orderedIds = remember { mutableStateOf(entryIds) }
    val draggedId = remember { mutableStateOf<String?>(null) }
    val dragStartOrder = remember { mutableStateOf(emptyList<String>()) }
    val dragStartWindowY = remember { mutableFloatStateOf(0f) }
    val autoScrollDirection = remember { mutableIntStateOf(0) }
    val viewportBounds = remember { mutableStateOf<Rect?>(null) }
    val scrollState = rememberScrollState()
    val entryById = remember(entries) { entries.associateBy { it.id } }
    val displayedEntries = orderedIds.value.mapNotNull(entryById::get)
    val onReorderState = rememberUpdatedState(onReorder)
    val dragEdgePx = with(androidx.compose.ui.platform.LocalDensity.current) { 40.dp.toPx() }
    val moveThresholdPx = with(androidx.compose.ui.platform.LocalDensity.current) { 48.dp.toPx() }

    LaunchedEffect(entryIds.toSet()) {
        if (draggedId.value == null) orderedIds.value = entryIds
    }
    LaunchedEffect(draggedId.value, autoScrollDirection.intValue) {
        val direction = autoScrollDirection.intValue
        if (draggedId.value != null && direction != 0) {
            while (draggedId.value != null && autoScrollDirection.intValue == direction) {
                val before = scrollState.value
                scrollState.scrollTo((before + direction * 20f).toInt())
                if (scrollState.value == before) break
                delay(16L)
            }
        }
    }

    fun moveLocally(id: String, direction: Int) {
        val current = orderedIds.value
        val from = current.indexOf(id)
        val to = from + direction
        if (from < 0 || to !in current.indices) return
        orderedIds.value = current.toMutableList().also { ids ->
            val moved = ids.removeAt(from)
            ids.add(to, moved)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ترتيب " + collectionTitle(category), color = AdhkarHeading, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("اضغط مطولًا على الذكر، ثم اسحبه إلى موضعه. يمكنك أيضًا استخدام السهمين. سيُحفظ ترتيب الأذكار في هذه المجموعة.",
                    color = p.muted, fontSize = 13.sp, lineHeight = 21.sp)
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 360.dp)
                        .verticalScroll(scrollState, enabled = draggedId.value == null)
                        .onGloballyPositioned { viewportBounds.value = it.boundsInWindow() }
                        .testTag("adhkar_reorder_list"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    displayedEntries.forEachIndexed { index, entry ->
                        key(entry.id) {
                            val isDragging = draggedId.value == entry.id
                            val dragScale by animateFloatAsState(
                                targetValue = if (isDragging) 1.035f else 1f,
                                label = "adhkar_drag_scale",
                            )
                            val dragElevation by animateDpAsState(
                                targetValue = if (isDragging) 14.dp else 0.dp,
                                label = "adhkar_drag_elevation",
                            )
                            val rowCoordinates = remember { mutableStateOf<LayoutCoordinates?>(null) }
                            AdhkarCard(
                                Modifier.fillMaxWidth()
                                    .graphicsLayer {
                                        scaleX = dragScale
                                        scaleY = dragScale
                                        shadowElevation = dragElevation.toPx()
                                        shape = RoundedCornerShape(18.dp)
                                    }
                                    .zIndex(if (isDragging) 1f else 0f)
                                    .onGloballyPositioned {
                                        rowCoordinates.value = it
                                    }
                                    .pointerInput(entry.id) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = { startPosition ->
                                                draggedId.value = entry.id
                                                dragStartOrder.value = orderedIds.value
                                                dragStartWindowY.floatValue = rowCoordinates.value?.localToWindow(startPosition)?.y ?: 0f
                                            },
                                            onDrag = { change, _ ->
                                                change.consume()
                                                val pointerY = rowCoordinates.value?.localToWindow(change.position)?.y
                                                    ?: return@detectDragGesturesAfterLongPress
                                                val startOrder = dragStartOrder.value
                                                val startIndex = startOrder.indexOf(entry.id)
                                                if (startIndex >= 0) {
                                                    val steps = ((pointerY - dragStartWindowY.floatValue) / moveThresholdPx).roundToInt()
                                                    val targetIndex = (startIndex + steps).coerceIn(0, startOrder.lastIndex)
                                                    if (targetIndex != orderedIds.value.indexOf(entry.id)) {
                                                        orderedIds.value = startOrder.toMutableList().also { ids ->
                                                            ids.removeAt(startIndex)
                                                            ids.add(targetIndex, entry.id)
                                                        }
                                                    }
                                                }
                                                val viewport = viewportBounds.value
                                                autoScrollDirection.intValue = when {
                                                    viewport == null -> 0
                                                    pointerY < viewport.top + dragEdgePx -> -1
                                                    pointerY > viewport.bottom - dragEdgePx -> 1
                                                    else -> 0
                                                }
                                            },
                                            onDragEnd = {
                                                val changedOrder = orderedIds.value.toList()
                                                draggedId.value = null
                                                autoScrollDirection.intValue = 0
                                                if (changedOrder != dragStartOrder.value) onReorderState.value(changedOrder)
                                            },
                                            onDragCancel = {
                                                orderedIds.value = dragStartOrder.value
                                                draggedId.value = null
                                                autoScrollDirection.intValue = 0
                                            },
                                        )
                                    },
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                                        Text(
                                            text = if (isDragging) "جارٍ ترتيبه" else "الذكر " + latinNumber(index + 1),
                                            color = if (isDragging) p.primary else p.muted,
                                            fontSize = 11.sp,
                                            fontWeight = if (isDragging) FontWeight.SemiBold else FontWeight.Normal,
                                        )
                                        Text(entry.title, color = AdhkarHeading, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    IconButton(
                                        onClick = { moveLocally(entry.id, -1); onMove(entry.id, -1) },
                                        enabled = index > 0,
                                        modifier = Modifier.testTag("adhkar_reorder_up_" + entry.id)
                                            .semantics { contentDescription = "نقل " + entry.title + " إلى الأعلى" },
                                    ) { Text("↑", color = if (index > 0) p.primary else p.muted, fontSize = 22.sp) }
                                    IconButton(
                                        onClick = { moveLocally(entry.id, 1); onMove(entry.id, 1) },
                                        enabled = index < displayedEntries.lastIndex,
                                        modifier = Modifier.testTag("adhkar_reorder_down_" + entry.id)
                                            .semantics { contentDescription = "نقل " + entry.title + " إلى الأسفل" },
                                    ) { Text("↓", color = if (index < displayedEntries.lastIndex) p.primary else p.muted, fontSize = 22.sp) }
                                    Box(
                                        Modifier.size(44.dp)
                                            .semantics { contentDescription = "اسحب لإعادة ترتيب " + entry.title }
                                            .testTag("adhkar_drag_handle_" + entry.id),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        DhikrIcon(R.drawable.ic_adhkar_drag, "اسحب لإعادة الترتيب",
                                            tint = if (isDragging) p.primary else p.muted,
                                            modifier = Modifier.size(if (isDragging) 26.dp else 22.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("تم") } },
    )
}

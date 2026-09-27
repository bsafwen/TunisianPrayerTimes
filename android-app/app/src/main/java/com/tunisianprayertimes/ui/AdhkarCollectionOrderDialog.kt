package com.tunisianprayertimes.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlin.math.abs

@Composable
internal fun DhikrCollectionOrderDialog(
    category: DhikrCategory,
    state: DhikrState,
    onReorder: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalAdhkarPalette.current
    val entries = state.collectionEntries(category)
    val entryIds = entries.map { it.id }
    val orderedIds = remember { mutableStateOf(entryIds) }
    val draggedId = remember { mutableStateOf<String?>(null) }
    val dragStartOrder = remember { mutableStateOf(emptyList<String>()) }
    val dragPointerY = remember { mutableFloatStateOf(0f) }
    val dragGrabOffsetY = remember { mutableFloatStateOf(0f) }
    val autoScrollDirection = remember { mutableIntStateOf(0) }
    val scrollState = rememberLazyListState()
    val entryById = remember(entries) { entries.associateBy { it.id } }
    val displayedEntries = orderedIds.value.mapNotNull(entryById::get)
    val onReorderState = rememberUpdatedState(onReorder)
    val autoScrollOutsidePx = with(androidx.compose.ui.platform.LocalDensity.current) { 24.dp.toPx() }

    LaunchedEffect(entryIds.toSet()) {
        if (draggedId.value == null) orderedIds.value = entryIds
    }

    fun moveDraggedToPointer(pointerY: Float) {
        val id = draggedId.value ?: return
        val current = orderedIds.value
        val from = current.indexOf(id)
        if (from < 0) return
        val visible = scrollState.layoutInfo.visibleItemsInfo
        // Wait for the list to lay out a completed swap before using its item positions again.
        if (visible.any { current.getOrNull(it.index) != it.key }) return
        val draggedItem = visible.firstOrNull { it.key == id } ?: return
        // Compare the card's visible centre with the other cards' layout positions.
        // The pointer is measured in the list's fixed coordinate space, so moving
        // the dragged card to a new slot cannot change the pointer position.
        val draggedCentre = pointerY - dragGrabOffsetY.floatValue + draggedItem.size / 2f
        val targetBelow = visible.filter { item ->
            item.key != id && item.index > from && item.offset + item.size / 2f < draggedCentre
        }.maxOfOrNull { it.index }
        val targetAbove = visible.filter { item ->
            item.key != id && item.index < from && item.offset + item.size / 2f > draggedCentre
        }.minOfOrNull { it.index }
        val to = targetBelow ?: targetAbove ?: return
        val anchorIndex = scrollState.firstVisibleItemIndex
        val anchorOffset = scrollState.firstVisibleItemScrollOffset
        orderedIds.value = current.toMutableList().also { ids ->
            ids.removeAt(from)
            ids.add(to, id)
        }
        // LazyColumn otherwise keeps the moved row's key at the top and scrolls after every swap.
        scrollState.requestScrollToItem(anchorIndex, anchorOffset)
    }

    LaunchedEffect(draggedId.value, autoScrollDirection.intValue) {
        val direction = autoScrollDirection.intValue
        if (draggedId.value != null && direction != 0) {
            while (draggedId.value != null && autoScrollDirection.intValue == direction) {
                val activeId = draggedId.value ?: break
                if (scrollState.layoutInfo.visibleItemsInfo.none { it.key == activeId }) {
                    autoScrollDirection.intValue = 0
                    break
                }
                if (abs(scrollState.scrollBy(direction * 8f)) < 1f) break
                if (scrollState.layoutInfo.visibleItemsInfo.none { it.key == activeId }) {
                    autoScrollDirection.intValue = 0
                    break
                }
                moveDraggedToPointer(dragPointerY.floatValue)
                delay(16L)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ترتيب " + collectionTitle(category), color = AdhkarHeading, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("اضغط مطولًا على الذكر، ثم اسحبه إلى موضعه. سيُحفظ ترتيب الأذكار في هذه المجموعة.",
                    color = p.muted, fontSize = 13.sp, lineHeight = 21.sp)
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 360.dp)
                        .pointerInput(Unit) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { startPosition ->
                                    val item = scrollState.layoutInfo.visibleItemsInfo.firstOrNull {
                                        startPosition.y >= it.offset && startPosition.y < it.offset + it.size
                                    } ?: return@detectDragGesturesAfterLongPress
                                    val id = item.key as? String ?: return@detectDragGesturesAfterLongPress
                                    draggedId.value = id
                                    dragStartOrder.value = orderedIds.value
                                    dragPointerY.floatValue = startPosition.y
                                    dragGrabOffsetY.floatValue = startPosition.y - item.offset
                                },
                                onDrag = { change, _ ->
                                    if (draggedId.value == null) return@detectDragGesturesAfterLongPress
                                    change.consume()
                                    dragPointerY.floatValue = change.position.y
                                    moveDraggedToPointer(change.position.y)
                                    val viewport = scrollState.layoutInfo
                                    autoScrollDirection.intValue = when {
                                        change.position.y < viewport.viewportStartOffset - autoScrollOutsidePx -> -1
                                        change.position.y > viewport.viewportEndOffset + autoScrollOutsidePx -> 1
                                        else -> 0
                                    }
                                },
                                onDragEnd = {
                                    if (draggedId.value != null) {
                                        val changedOrder = orderedIds.value.toList()
                                        draggedId.value = null
                                        autoScrollDirection.intValue = 0
                                        if (changedOrder != dragStartOrder.value) onReorderState.value(changedOrder)
                                    }
                                },
                                onDragCancel = {
                                    if (draggedId.value != null) {
                                        orderedIds.value = dragStartOrder.value
                                        draggedId.value = null
                                        autoScrollDirection.intValue = 0
                                    }
                                },
                            )
                        }
                        .testTag("adhkar_reorder_list"),
                    state = scrollState,
                    userScrollEnabled = draggedId.value == null,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(displayedEntries, key = { _, entry -> entry.id }) { index, entry ->
                            val isDragging = draggedId.value == entry.id
                            val dragScale by animateFloatAsState(
                                targetValue = if (isDragging) 1.035f else 1f,
                                label = "adhkar_drag_scale",
                            )
                            val dragElevation by animateDpAsState(
                                targetValue = if (isDragging) 14.dp else 0.dp,
                                label = "adhkar_drag_elevation",
                            )
                            AdhkarCard(
                                Modifier.fillMaxWidth()
                                    .animateItem(
                                        fadeInSpec = null,
                                        placementSpec = if (isDragging) null else tween(180),
                                        fadeOutSpec = null,
                                    )
                                    .graphicsLayer {
                                        if (isDragging) {
                                            val itemOffset = scrollState.layoutInfo.visibleItemsInfo
                                                .firstOrNull { it.key == entry.id }?.offset ?: 0
                                            translationY = dragPointerY.floatValue - dragGrabOffsetY.floatValue - itemOffset
                                        } else translationY = 0f
                                        scaleX = dragScale
                                        scaleY = dragScale
                                        shadowElevation = dragElevation.toPx()
                                        shape = RoundedCornerShape(18.dp)
                                    }
                                    .zIndex(if (isDragging) 1f else 0f)
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
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("تم") } },
    )
}

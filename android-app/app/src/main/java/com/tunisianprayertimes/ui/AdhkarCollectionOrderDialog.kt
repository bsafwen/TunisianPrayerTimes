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
import kotlinx.coroutines.flow.first

/** Band along the list's top and bottom edges where a held card scrolls the list. */
private val AutoScrollEdge = 56.dp
/** In a short list (landscape, large text) the bands shrink so a still zone remains, but not below this. */
private val AutoScrollMinEdge = 12.dp
/** Scroll speed once the card is fully into the band or past it; about two list heights per second. */
private val AutoScrollMaxSpeedPerSecond = 720.dp
/** Entering the band starts gently rather than jumping straight to full speed. */
private const val AutoScrollMinFraction = 0.15f

/**
 * Height of each edge band for a list [listHeight] tall while dragging a card [cardHeight] tall.
 * In a short list (landscape, large text) the bands shrink to a third of the free space, so the card
 * keeps a still zone between them and a one-slot move does not start scrolling.
 */
internal fun autoScrollEdgeBand(listHeight: Float, cardHeight: Float, maxBand: Float, minBand: Float): Float =
    ((listHeight - cardHeight) / 3f).coerceIn(minBand, maxBand)

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
    val dragStartPointerY = remember { mutableFloatStateOf(0f) }
    // An edge scrolls only while "armed": the card entered that edge's band during the drag, or was
    // pushed toward it past touch slop. It stays armed while the card remains in the band, so easing
    // the finger back a little does not stop the scroll, and disarms once the card leaves the band.
    val topArmed = remember { mutableStateOf(false) }
    val bottomArmed = remember { mutableStateOf(false) }
    val inTopBand = remember { mutableStateOf(false) }
    val inBottomBand = remember { mutableStateOf(false) }
    val dragGrabOffsetY = remember { mutableFloatStateOf(0f) }
    val draggedSize = remember { mutableIntStateOf(0) }
    val scrollState = rememberLazyListState()
    val entryById = remember(entries) { entries.associateBy { it.id } }
    val displayedEntries = orderedIds.value.mapNotNull(entryById::get)
    val onReorderState = rememberUpdatedState(onReorder)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val autoScrollEdgePx = with(density) { AutoScrollEdge.toPx() }
    val autoScrollMinEdgePx = with(density) { AutoScrollMinEdge.toPx() }
    val autoScrollMaxSpeedPx = with(density) { AutoScrollMaxSpeedPerSecond.toPx() }
    val touchSlop = androidx.compose.ui.platform.LocalViewConfiguration.current.touchSlop

    LaunchedEffect(entryIds.toSet()) {
        if (draggedId.value == null) orderedIds.value = entryIds
    }

    /** Bottom of the visible list. The viewport end is the height limit, which a short list does not fill. */
    fun listEnd(): Float = scrollState.layoutInfo.let { minOf(it.viewportEndOffset, it.viewportSize.height) }.toFloat()

    /** The dragged card follows the finger but stays inside the list, so it is never clipped out of view. */
    fun draggedTop(): Float {
        val start = scrollState.layoutInfo.viewportStartOffset.toFloat()
        val top = dragPointerY.floatValue - dragGrabOffsetY.floatValue
        return top.coerceIn(start, maxOf(start, listEnd() - draggedSize.intValue))
    }

    /** How far the card under the finger reaches into the top and bottom bands (positive = inside), and the band height. */
    fun bandDepths(): Triple<Float, Float, Float> {
        val start = scrollState.layoutInfo.viewportStartOffset.toFloat()
        val end = listEnd()
        val band = autoScrollEdgeBand(end - start, draggedSize.intValue.toFloat(), autoScrollEdgePx, autoScrollMinEdgePx)
        val top = dragPointerY.floatValue - dragGrabOffsetY.floatValue
        return Triple(start + band - top, top + draggedSize.intValue - (end - band), band)
    }

    /**
     * Arms an edge when the card enters its band during the drag, or is pushed toward it past touch slop.
     * A card picked up inside a band therefore does not scroll on finger jitter.
     */
    fun updateEdgeArming(y: Float) {
        val (intoTop, intoBottom) = bandDepths()
        val nowInTop = intoTop > 0f
        val nowInBottom = intoBottom > 0f
        topArmed.value = nowInTop && (topArmed.value || !inTopBand.value || dragStartPointerY.floatValue - y > touchSlop)
        bottomArmed.value = nowInBottom && (bottomArmed.value || !inBottomBand.value || y - dragStartPointerY.floatValue > touchSlop)
        inTopBand.value = nowInTop
        inBottomBand.value = nowInBottom
    }

    /**
     * Scroll speed in px/s (negative scrolls up) while the dragged card is held in an armed edge band.
     * Speed grows with how far the card reaches into the band, or past it.
     */
    fun autoScrollSpeed(): Float {
        if (draggedId.value == null) return 0f
        val (intoTop, intoBottom, band) = bandDepths()
        fun speed(depth: Float) = autoScrollMaxSpeedPx * (depth / band).coerceIn(AutoScrollMinFraction, 1f)
        return when {
            topArmed.value && intoTop > 0f && scrollState.canScrollBackward -> -speed(intoTop)
            bottomArmed.value && intoBottom > 0f && scrollState.canScrollForward -> speed(intoBottom)
            else -> 0f
        }
    }

    fun moveDraggedToPointer() {
        val id = draggedId.value ?: return
        val current = orderedIds.value
        val from = current.indexOf(id)
        if (from < 0) return
        val visible = scrollState.layoutInfo.visibleItemsInfo
        // Wait for the list to lay out a completed swap before using its item positions again.
        if (visible.any { current.getOrNull(it.index) != it.key }) return
        // Compare the centre of the card under the finger with the other cards' layout positions.
        // The pointer is measured in the list's fixed coordinate space, so moving the dragged
        // card to a new slot cannot change the pointer position. The finger, not the drawn card
        // (which stays inside the list), decides the slot, so pushing past the first or last card
        // still reaches the ends. The card's own slot may have scrolled out of view during
        // auto-scroll, so its size is kept from the start of the drag.
        val draggedCentre = dragPointerY.floatValue - dragGrabOffsetY.floatValue + draggedSize.intValue / 2f
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

    // While a held card sits in an edge band, scroll every frame, even if the finger stays still.
    // Frames are only requested while scrolling, not for the whole drag.
    LaunchedEffect(draggedId.value) {
        if (draggedId.value == null) return@LaunchedEffect
        while (true) {
            // A fresh flow reads the current state first, so a scroll need that appeared while
            // the last run was stopping is never missed.
            snapshotFlow { autoScrollSpeed() != 0f }.first { it }
            var previousFrame = withFrameNanos { it }
            while (true) {
                val frame = withFrameNanos { it }
                // A long frame (e.g. the app was paused) must not turn into one large jump.
                val seconds = ((frame - previousFrame) / 1_000_000_000f).coerceAtMost(0.05f)
                previousFrame = frame
                val speed = autoScrollSpeed()
                if (speed == 0f) break
                if (scrollState.scrollBy(speed * seconds) != 0f) moveDraggedToPointer()
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
                                    dragStartOrder.value = orderedIds.value
                                    dragPointerY.floatValue = startPosition.y
                                    dragStartPointerY.floatValue = startPosition.y
                                    dragGrabOffsetY.floatValue = startPosition.y - item.offset
                                    draggedSize.intValue = item.size
                                    // Bands the card starts in are not armed until it is pushed toward that edge.
                                    val (intoTop, intoBottom) = bandDepths()
                                    inTopBand.value = intoTop > 0f
                                    inBottomBand.value = intoBottom > 0f
                                    topArmed.value = false
                                    bottomArmed.value = false
                                    draggedId.value = id
                                },
                                onDrag = { change, _ ->
                                    if (draggedId.value == null) return@detectDragGesturesAfterLongPress
                                    change.consume()
                                    dragPointerY.floatValue = change.position.y
                                    updateEdgeArming(change.position.y)
                                    moveDraggedToPointer()
                                },
                                onDragEnd = {
                                    if (draggedId.value != null) {
                                        val changedOrder = orderedIds.value.toList()
                                        draggedId.value = null
                                        if (changedOrder != dragStartOrder.value) onReorderState.value(changedOrder)
                                    }
                                },
                                onDragCancel = {
                                    if (draggedId.value != null) {
                                        orderedIds.value = dragStartOrder.value
                                        draggedId.value = null
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
                                            translationY = draggedTop() - itemOffset
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

package com.tunisianprayertimes.ui

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import com.tunisianprayertimes.adhkar.DhikrCategory
import com.tunisianprayertimes.adhkar.DhikrState
import com.tunisianprayertimes.adhkar.collectionEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Long-press drags in the collection reorder dialog scroll the list when held against its edges. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ar-rTN-w360dp-h800dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdhkarReorderAutoScrollTest {
    @get:Rule val compose = createComposeRule()

    private val ids = DhikrState().collectionEntries(DhikrCategory.MORNING).map { it.id }
    private var reordered: List<String>? = null

    private fun showDialog(category: DhikrCategory = DhikrCategory.MORNING) = compose.setContent {
        DhikrCollectionOrderDialog(category, DhikrState(), onReorder = { reordered = it }, onDismiss = {})
    }

    private fun handleBounds(id: String) = compose.onNodeWithTag("adhkar_drag_handle_$id").fetchSemanticsNode().boundsInRoot

    private fun press(y: Float) {
        var longPress = 0L
        list.performTouchInput {
            longPress = viewConfiguration.longPressTimeoutMillis
            down(Offset(centerX, y))
        }
        compose.mainClock.advanceTimeBy(longPress + 100)
    }

    private fun slide(fromY: Float, toY: Float, steps: Int = 12) = repeat(steps) { step ->
        list.performTouchInput { moveTo(Offset(centerX, fromY + (toY - fromY) * (step + 1) / steps)) }
        compose.mainClock.advanceTimeBy(16)
    }

    private fun release() {
        list.performTouchInput { up() }
        compose.waitForIdle()
    }

    private val list get() = compose.onNodeWithTag("adhkar_reorder_list")

    /** Vertical centre of a dhikr's row, in the list's own coordinates. */
    private fun rowCentreY(id: String): Float {
        val row = compose.onNodeWithTag("adhkar_drag_handle_$id").fetchSemanticsNode().boundsInRoot
        return row.center.y - list.fetchSemanticsNode().boundsInRoot.top
    }

    /** Long-presses at [fromY], moves in steps to [toY], holds for [holdMillis], then lifts. */
    private fun dragAndHold(fromY: Float, toY: Float, holdMillis: Long) {
        press(fromY)
        slide(fromY, toY)
        compose.mainClock.advanceTimeBy(holdMillis)
        release()
    }

    @Test fun holdingACardAtTheBottomEdgeScrollsDownUntilItReachesTheEnd() {
        showDialog()
        val height = list.fetchSemanticsNode().size.height.toFloat()
        // Start from a card in the middle: the first visible row has its own scroll anchoring.
        val picked = ids[2]
        dragAndHold(fromY = rowCentreY(picked), toY = height - 4f, holdMillis = 4_000)
        val order = requireNotNull(reordered) { "the drag should have produced a new order" }
        assertEquals(picked, order.last())
        assertEquals(ids - picked, order.dropLast(1))
    }

    @Test fun holdingACardAtTheTopEdgeScrollsUpUntilItReachesTheStart() {
        showDialog()
        list.performScrollToIndex(ids.lastIndex)
        compose.waitForIdle()
        val height = list.fetchSemanticsNode().size.height.toFloat()
        dragAndHold(fromY = height - 20f, toY = 4f, holdMillis = 4_000)
        val order = requireNotNull(reordered) { "the drag should have produced a new order" }
        assertEquals(ids.last(), order.first())
        assertEquals(ids.dropLast(1), order.drop(1))
    }

    @Test fun pushingBackTowardTheBottomAfterDraggingUpScrollsWithoutPassingThePickupPoint() {
        showDialog()
        val height = list.fetchSemanticsNode().size.height.toFloat()
        val listTop = list.fetchSemanticsNode().boundsInRoot.top
        // The lowest card with a visible handle sits against the bottom edge.
        val picked = ids.filter { id -> runCatching { handleBounds(id).bottom - listTop <= height }.getOrDefault(false) }.last()
        val startY = handleBounds(picked).center.y - listTop
        press(startY)
        slide(startY, height / 2f)
        // Back down into the bottom band, yet still above where the card was picked up.
        slide(height / 2f, startY - 2f)
        compose.mainClock.advanceTimeBy(4_000)
        release()
        // Carried to the end of the list; the finger rests above the very last slot's centre.
        val order = requireNotNull(reordered)
        assertTrue("$picked ended at ${order.indexOf(picked)} in $order", order.indexOf(picked) >= ids.lastIndex - 1)
    }

    @Test fun shortListKeepsTheDraggedCardInsideTheList() {
        showDialog(DhikrCategory.NIGHT)
        val night = DhikrState().collectionEntries(DhikrCategory.NIGHT).map { it.id }
        val bounds = list.fetchSemanticsNode().boundsInRoot
        val startY = handleBounds(night.first()).center.y - bounds.top
        press(startY)
        // Far below the two-card list, onto the dialog's button row.
        slide(startY, bounds.height + 120f)
        compose.mainClock.advanceTimeBy(500)
        // Unclipped position: the list clips its content, which would hide a card drawn below it.
        val handle = compose.onNodeWithTag("adhkar_drag_handle_${night.first()}").fetchSemanticsNode()
        val handleBottom = handle.positionInRoot.y + handle.size.height
        assertTrue("dragged card drawn down to $handleBottom, list ends at ${bounds.bottom}", handleBottom <= bounds.bottom + 1f)
        release()
        assertEquals(night.reversed(), reordered)
    }

    @Test fun shortListBandsLeaveTheCardAStillZone() {
        val card = 60f
        for (list in listOf(100f, 120f, 150f, 172f, 250f, 360f)) {
            val band = autoScrollEdgeBand(list, card, maxBand = 56f, minBand = 12f)
            assertTrue("list $list: band $band", band in 12f..56f)
            // Wherever it fits, the card can rest between the two bands with room to spare.
            if (list - card >= 24f) assertTrue("list $list: band $band leaves no still zone", list - 2 * band > card)
        }
        assertEquals(56f, autoScrollEdgeBand(360f, card, 56f, 12f))
    }

    @Test fun cardPickedUpAtAnEdgeDoesNotScrollWithoutBeingPushedTowardIt() {
        showDialog()
        val height = list.fetchSemanticsNode().size.height.toFloat()
        // The last visible card sits in the bottom band; a still long-press must not start scrolling.
        dragAndHold(fromY = height - 20f, toY = height - 19f, holdMillis = 2_000)
        assertNull(reordered)
    }
}

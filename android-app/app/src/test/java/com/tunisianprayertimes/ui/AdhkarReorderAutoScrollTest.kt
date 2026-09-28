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

    private fun showDialog() = compose.setContent {
        DhikrCollectionOrderDialog(DhikrCategory.MORNING, DhikrState(), onReorder = { reordered = it }, onDismiss = {})
    }

    private val list get() = compose.onNodeWithTag("adhkar_reorder_list")

    /** Vertical centre of a dhikr's row, in the list's own coordinates. */
    private fun rowCentreY(id: String): Float {
        val row = compose.onNodeWithTag("adhkar_drag_handle_$id").fetchSemanticsNode().boundsInRoot
        return row.center.y - list.fetchSemanticsNode().boundsInRoot.top
    }

    /** Long-presses at [fromY], moves in steps to [toY], holds for [holdMillis], then lifts. */
    private fun dragAndHold(fromY: Float, toY: Float, holdMillis: Long) {
        var longPress = 0L
        list.performTouchInput {
            longPress = viewConfiguration.longPressTimeoutMillis
            down(Offset(centerX, fromY))
        }
        compose.mainClock.advanceTimeBy(longPress + 100)
        repeat(12) { step ->
            list.performTouchInput { moveTo(Offset(centerX, fromY + (toY - fromY) * (step + 1) / 12f)) }
            compose.mainClock.advanceTimeBy(16)
        }
        compose.mainClock.advanceTimeBy(holdMillis)
        list.performTouchInput { up() }
        compose.waitForIdle()
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

    @Test fun cardPickedUpAtAnEdgeDoesNotScrollWithoutBeingPushedTowardIt() {
        showDialog()
        val height = list.fetchSemanticsNode().size.height.toFloat()
        // The last visible card sits in the bottom band; a still long-press must not start scrolling.
        dragAndHold(fromY = height - 20f, toY = height - 19f, holdMillis = 2_000)
        assertNull(reordered)
    }
}

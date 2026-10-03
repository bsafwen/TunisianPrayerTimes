package com.tunisianprayertimes.ui

import android.app.Application
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.tunisianprayertimes.adhkar.DhikrCategory
import com.tunisianprayertimes.adhkar.DhikrSession
import com.tunisianprayertimes.adhkar.DhikrState
import com.tunisianprayertimes.adhkar.collectionEntries
import com.tunisianprayertimes.adhkar.findDhikr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Swiping between adhkar in the reader: from anywhere under the header, by distance or by flick. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ar-rTN-w360dp-h800dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdhkarReaderSwipeTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val state = DhikrState()
    private val ids = state.collectionEntries(DhikrCategory.MORNING).map { it.id }
    private val start = 2
    private var session by mutableStateOf(DhikrSession(itemIds = ids, index = start, category = DhikrCategory.MORNING))
    private val moves = mutableListOf<Int>()
    private val counts = mutableListOf<Int>()
    /** False simulates a move whose save never lands. */
    private var landMoves = true

    @Before fun showReader() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext())
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
        val activity = controller.setup().get()
        activity.setContent {
            DhikrReader(activity, state, session, now = 0L, onDismiss = {},
                onCount = { counts += it },
                onMove = { direction ->
                    moves += direction
                    if (landMoves) session = session.copy(index = (session.index + direction).mod(ids.size))
                },
                onSkip = {}, onRemove = {}, onFavourite = {}, onCollections = {}, onEditCustom = {},
                onDeleteCustom = {}, onAddToCollection = {}, onReorderCollection = {}, onTextSize = {},
                onHaptics = {}, onNewSession = {}, reminder = null, reminderActionLabel = "إنشاء تذكير لهذا الذكر",
                onReminder = {}, onConfigureReminder = {}, onDeleteReminder = {}, skipReminderLabel = null,
                onSkipReminder = {}, snackbar = remember { SnackbarHostState() })
        }
        compose.waitForIdle()
    }

    private val reader get() = compose.onNodeWithTag("adhkar_reader")
    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private val middle get() = bounds("adhkar_reader_text").center

    private fun swipeOnReader(from: Offset, dx: Float, millis: Long) {
        reader.performTouchInput { swipe(from, from + Offset(dx, 0f), millis) }
        compose.waitForIdle()
    }

    @Test fun quickFlickFromTheMiddleMovesToTheNextDhikr() {
        // A short flick from mid-screen, well under the slow-drag distance.
        swipeOnReader(middle, dx = 60f, millis = 60)
        assertEquals(listOf(1), moves)
        assertEquals(ids[start + 1], session.itemId)
    }

    @Test fun quickFlickLeftFromTheMiddleMovesToThePreviousDhikr() {
        swipeOnReader(middle, dx = -60f, millis = 60)
        assertEquals(listOf(-1), moves)
        assertEquals(ids[start - 1], session.itemId)
    }

    @Test fun slowDragAcrossTheMiddleMovesOn() {
        // Well under flick speed: only the distance can move it on.
        swipeOnReader(middle, dx = 160f, millis = 1_000)
        assertEquals(listOf(1), moves)
    }

    @Test fun swipeStartedOnTheCounterMoves() {
        swipeOnReader(bounds("adhkar_count").center, dx = 160f, millis = 250)
        assertEquals(listOf(1), moves)
        assertEquals("a swipe is not a tap", emptyList<Int>(), counts)
    }

    @Test fun shortSlowDragSpringsBackAndKeepsCounting() {
        swipeOnReader(middle, dx = 60f, millis = 400)
        assertEquals(emptyList<Int>(), moves)
        assertEquals(ids[start], session.itemId)
        compose.onNodeWithTag("adhkar_count").performClick()
        compose.waitForIdle()
        assertEquals(listOf(1), counts)
    }

    @Test fun tapThatSlipsSidewaysNeitherNavigatesNorCounts() {
        reader.performTouchInput {
            down(bounds("adhkar_count").center)
            // Just past touch slop, slowly: too short and slow to be a flick.
            moveBy(Offset(viewConfiguration.touchSlop + 4f, 0f), delayMillis = 80)
            up()
        }
        compose.waitForIdle()
        assertEquals(emptyList<Int>(), moves)
        assertEquals("past touch slop it is a swipe, not a tap", emptyList<Int>(), counts)
    }

    @Test fun quickTapWhileThePaneSpringsBackStillCounts() {
        compose.mainClock.autoAdvance = false
        var slop = 0f
        reader.performTouchInput { slop = viewConfiguration.touchSlop }
        swipeOnReader(bounds("adhkar_count").center, dx = slop + 8f, millis = 100)
        compose.mainClock.advanceTimeBy(50)
        // Mid spring-back: the next counting tap must land.
        compose.onNodeWithTag("adhkar_count").performClick()
        compose.mainClock.advanceTimeBy(500)
        assertEquals(listOf(1), counts)
        assertEquals(emptyList<Int>(), moves)
    }

    @Test fun touchCatchesAPaneSlidingToItsNeighbourWithoutCounting() {
        compose.mainClock.autoAdvance = false
        swipeOnReader(middle, dx = 160f, millis = 100)
        compose.mainClock.advanceTimeBy(64)
        assertEquals("still sliding", emptyList<Int>(), moves)
        compose.onNodeWithTag("adhkar_count").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(listOf(1), moves)
        assertEquals("the caught touch counts nothing", emptyList<Int>(), counts)
    }

    @Test fun scrollingLongTextVerticallyNeverNavigates() {
        val longest = ids.maxBy { state.findDhikr(it)!!.text.length }
        session = session.copy(index = ids.indexOf(longest))
        compose.waitForIdle()
        val before = bounds("adhkar_reader_text").top
        val text = bounds("adhkar_reader_text")
        reader.performTouchInput { swipe(Offset(text.center.x, text.bottom - 20f), Offset(text.center.x, text.top + 20f), 300) }
        compose.waitForIdle()
        assertNotEquals("the text should have scrolled", before, bounds("adhkar_reader_text").top)
        assertEquals(emptyList<Int>(), moves)
    }

    @Test fun moveThatNeverLandsBringsTheCurrentDhikrBack() {
        landMoves = false
        swipeOnReader(middle, dx = 160f, millis = 250)
        assertEquals(listOf(1), moves)
        // Swiped away and waiting on the move: a tap must not count the dhikr that slid out of view.
        compose.onNodeWithTag("adhkar_count").performClick()
        compose.waitForIdle()
        assertEquals(emptyList<Int>(), counts)
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        assertEquals(24f, bounds("adhkar_reader_text").left, 0.5f)
        compose.onNodeWithTag("adhkar_count").performClick()
        compose.waitForIdle()
        assertEquals(listOf(1), counts)
    }

    @Test fun swipeDirectionRules() {
        val drag = 72f
        val flick = 24f
        val fast = 400f
        fun direction(offset: Float, velocity: Float) = readerSwipeDirection(offset, velocity, drag, flick, fast)
        // Slow drags decide by distance.
        assertEquals(1, direction(80f, 0f))
        assertEquals(-1, direction(-80f, 0f))
        assertEquals(0, direction(60f, 100f))
        // Flicks decide by direction once past the small flick distance.
        assertEquals(1, direction(30f, 900f))
        assertEquals(-1, direction(-30f, -900f))
        assertEquals(0, direction(10f, 900f))
        // Flicking back toward the middle cancels even a long drag.
        assertEquals(0, direction(150f, -900f))
        assertEquals(0, direction(-150f, 900f))
        assertEquals(0, direction(0f, 900f))
    }
}

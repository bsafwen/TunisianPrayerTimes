package com.tunisianprayertimes.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.tunisianprayertimes.quran.QuranCatalog
import com.tunisianprayertimes.quran.QuranSurah
import com.tunisianprayertimes.quran.QuranVerseReference
import com.tunisianprayertimes.quran.hifz.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ar-rTN-w360dp-h800dp-xhdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HifzReviewEndTest {
    @get:Rule val compose = createComposeRule()
    private var now = 10_000L
    private val today = todayEpochDay()
    private val catalog = QuranCatalog(emptyList(), listOf(QuranSurah(2, "البقرة", 1)), emptyList())
    private val actions = object : HifzReviewActions {
        override fun grade(point: HifzPoint, grade: HifzGrade) {}
        override fun listenBefore(point: HifzPoint) {}
        override fun listen(point: HifzPoint) {}
        override fun drill(point: HifzPoint) {}
        override fun leaveCard() {}
        override fun more() {}
        override fun listenTo(ids: List<Long>) {}
        override fun dismiss() {}
    }

    private fun point(ayah: Int) = HifzPoint(
        id = ayah.toLong(), verse = QuranVerseReference(2, ayah),
        mistakes = listOf(HifzMistake(HifzKind.Word)), dueDay = today, createdAt = 0L,
    )

    private fun answer(state: HifzReviewState, queue: List<HifzPoint>) {
        state.shown(state.point(queue))
        now += 500L
        state.reveal(queue, actions)
        state.shown(state.point(queue))
        now += 500L
        state.grade(queue, actions, HifzGrade.Held)
    }

    private fun show(state: HifzReviewState, queue: List<HifzPoint>) {
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                AdhkarTheme {
                    HifzReviewCard(
                        state, catalog, null, queue, null, HifzAudioControls({}, {}, {}), emptySet(), 1L, actions,
                        maxHeight = 500.dp, beforeHidden = false, pointInView = true,
                        eraserHint = false, onEraserHintClosed = {},
                    )
                }
            }
        }
    }

    @Test
    fun aQuestionExposedDuringTheRoundIsExplainedWithoutOpeningDetails() {
        val queue = listOf(point(20), point(30))
        val state = HifzReviewState(clock = { now })
        state.follow(queue, actions)
        state.noteExposed(setOf(queue[1].verse), actions)
        answer(state, queue)
        assertEquals(setOf(queue[1].id), state.deferredIds)
        show(state, state.questions(queue))

        compose.onNodeWithTag("hifz_review_deferred").assertIsDisplayed()
        compose.onNodeWithText("أكملت هذه الجولة").assertIsDisplayed()
        compose.onNodeWithText("أكملت المراجعة").assertDoesNotExist()
        compose.onNodeWithTag("hifz_review_progress").assertDoesNotExist()
    }

    @Test
    fun detailsCanBeClosedAfterScrollingToTheirLastAction() {
        val queue = (1..5).map { point(it * 10) }
        val state = HifzReviewState(clock = { now })
        state.follow(queue, actions)
        repeat(queue.size) { answer(state, queue) }
        show(state, queue)

        compose.onNodeWithTag("hifz_review_details_toggle").performClick()
        compose.onNodeWithTag("hifz_review_listen_done").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("hifz_review_details_toggle").assertIsDisplayed().performClick()
        compose.onNodeWithTag("hifz_review_listen_done").assertDoesNotExist()
        compose.onNodeWithText("أكملت المراجعة").assertIsDisplayed()
    }
}

package com.tunisianprayertimes.ui

import android.app.Application
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.tunisianprayertimes.adhkar.DhikrCategory
import com.tunisianprayertimes.adhkar.DhikrOccurrence
import com.tunisianprayertimes.adhkar.DhikrReminder
import com.tunisianprayertimes.adhkar.DhikrSession
import com.tunisianprayertimes.adhkar.DhikrState
import com.tunisianprayertimes.adhkar.MushafPassages
import com.tunisianprayertimes.adhkar.collectionEntries
import com.tunisianprayertimes.adhkar.findDhikr
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Reminder provenance must not change the reading's header, position or page layout. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ar-rTN-w360dp-h800dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdhkarReaderEntryPointTest {
    @get:Rule val compose = createEmptyComposeRule()

    private var state by mutableStateOf(DhikrState())
    private val ids = state.collectionEntries(DhikrCategory.EVENING).map { it.id }
    private var session by mutableStateOf(DhikrSession(
        itemIds = ids, index = ids.indexOf("ya_hayyu"), category = DhikrCategory.EVENING))
    private var reminder by mutableStateOf<DhikrReminder?>(null)
    private val counts = mutableListOf<Int>()
    private var configuredReminders = 0

    @Before fun showReader() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext())
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
        val activity = controller.setup().get()
        activity.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                AdhkarTheme {
                    DhikrReader(activity, state, session, now = 1_000L, onDismiss = {},
                        onCount = { counts += it },
                        onMove = { direction ->
                            session = session.copy(index = (session.index + direction).mod(session.itemIds.size))
                        },
                        onSkip = {}, onRemove = {}, onFavourite = {}, onCollections = {}, onEditCustom = {},
                        onDeleteCustom = {}, onAddToCollection = {}, onReorderCollection = {}, onTextSize = {},
                        onHaptics = {}, onVariant = {}, reminder = reminder, reminderActionLabel = "إنشاء تذكير لهذا الذكر",
                        onReminder = {}, onConfigureReminder = { configuredReminders++ }, onDeleteReminder = {},
                        skipReminderLabel = if (reminder != null) "تخطّي تذكير اليوم" else null,
                        onSkipReminder = {}, snackbar = remember { SnackbarHostState() })
                }
            }
        }
        compose.waitForIdle()
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun assertReadingHeader() {
        compose.onNodeWithText(state.findDhikr(session.itemId)!!.title, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(session.category?.let(::collectionTitle) ?: "قراءة مستقلة").assertIsDisplayed()
        compose.onNodeWithText("الذكر " + latinNumber(session.index + 1) + " من " + latinNumber(session.itemIds.size))
            .assertIsDisplayed()
    }

    private fun linkReminder() {
        val rule = DhikrReminder(id = "reader-reminder", dhikrId = session.itemIds.first(), collection = session.category)
        val occurrence = DhikrOccurrence(
            id = "notification-occurrence", ruleId = rule.id, revision = rule.revision, date = "2026-10-30",
            dhikrId = rule.dhikrId, target = rule.targetCount, startMillis = 0L, endMillis = 2_000L)
        compose.runOnIdle {
            state = state.copy(reminders = listOf(rule), occurrences = mapOf(occurrence.id to occurrence))
            session = session.copy(occurrenceId = occurrence.id)
            reminder = rule
        }
        compose.waitForIdle()
    }

    @Test fun notificationReadingKeepsTheManualHeaderIndexAndLayout() {
        assertReadingHeader()
        val text = bounds("adhkar_reader_text")
        val counter = bounds("adhkar_count")

        linkReminder()

        assertReadingHeader()
        assertEquals(text, bounds("adhkar_reader_text"))
        assertEquals(counter, bounds("adhkar_count"))
    }

    @Test fun notificationHeaderAndIndexFollowTheCurrentDhikrAfterASwipe() {
        linkReminder()
        assertReadingHeader()
        val next = (session.index + 1).mod(ids.size)
        val from = bounds("adhkar_reader_text").center

        compose.onNodeWithTag("adhkar_reader").performTouchInput {
            swipe(from, from + Offset(160f, 0f), 250)
        }
        compose.waitForIdle()

        assertEquals(next, session.index)
        assertEquals("notification-occurrence", session.occurrenceId)
        assertReadingHeader()
    }

    @Test fun quranPassageTitleAppearsOnlyOnceForBothEntryPoints() {
        compose.runOnIdle {
            session = session.copy(index = ids.indexOfFirst { MushafPassages.text(it) != null })
        }
        compose.waitForIdle()
        val title = state.findDhikr(session.itemId)!!.title
        assertReadingHeader()
        compose.onAllNodesWithText(title, useUnmergedTree = true).assertCountEquals(1)
        val text = bounds("adhkar_reader_text")

        linkReminder()

        assertReadingHeader()
        compose.onAllNodesWithText(title, useUnmergedTree = true).assertCountEquals(1)
        assertEquals(text, bounds("adhkar_reader_text"))
    }

    @Test fun singleDhikrReminderKeepsTheIndependentSubtitleAndOneOfOneIndex() {
        compose.runOnIdle { session = DhikrSession(itemIds = listOf(session.itemId)) }
        compose.waitForIdle()
        assertReadingHeader()

        linkReminder()

        assertReadingHeader()
    }

    @Test fun notificationReadingStillCountsAndOffersReminderActions() {
        linkReminder()
        compose.onNodeWithTag("adhkar_count").performClick()
        assertEquals(listOf(1), counts)

        compose.onNodeWithTag("adhkar_reader_menu").performClick()
        compose.onNodeWithTag("adhkar_reader_configure_reminder").assertIsDisplayed()
        compose.onNodeWithTag("adhkar_reader_skip_reminder").assertIsDisplayed()
        compose.onNodeWithTag("adhkar_reader_delete_reminder").assertIsDisplayed()
        compose.onNodeWithTag("adhkar_reader_reminder").assertDoesNotExist()
        compose.onNodeWithTag("adhkar_reader_configure_reminder").performClick()
        assertEquals(1, configuredReminders)
    }
}

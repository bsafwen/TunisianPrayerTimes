package com.tunisianprayertimes

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tunisianprayertimes.adhkar.*
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Compose + activity recreation, with the emulator's existing reading state restored afterward. */
@RunWith(AndroidJUnit4::class)
class AdhkarReaderInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun invalidReminderShowsErrorBesideSaveWithoutEnablingAnything() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val originalRules = DhikrRepository(context).state.value.reminders
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra(MainTabNavigation.EXTRA_DESTINATION, MainTabNavigation.DESTINATION_ADHKAR)).use {
            compose.onNodeWithTag("adhkar_reminders_action").performClick()
            compose.onNodeWithTag("adhkar_new_reminder").performClick()
            // An untouched new reminder reports nothing until it is edited or saved.
            compose.onNodeWithTag("adhkar_editor_error").assertDoesNotExist()
            compose.onNodeWithTag("adhkar_target_input").performTextReplacement("0")
            compose.onNodeWithTag("adhkar_save_reminder").performClick()
            compose.onNodeWithTag("adhkar_editor_error").assertIsDisplayed()
            compose.onNodeWithTag("adhkar_save_reminder").assertIsDisplayed().assertIsEnabled()
            assertEquals(originalRules, DhikrRepository(context).state.value.reminders)
        }
    }

    @Test fun backOverUnsavedReminderEditsAsksBeforeDiscarding() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra(MainTabNavigation.EXTRA_DESTINATION, MainTabNavigation.DESTINATION_ADHKAR)).use { scenario ->
            compose.onNodeWithTag("adhkar_reminders_action").performClick()
            compose.onNodeWithTag("adhkar_new_reminder").performClick()
            compose.onNodeWithTag("adhkar_target_input").performTextReplacement("7")
            Espresso.closeSoftKeyboard()
            Espresso.pressBack()
            compose.onNodeWithText("تجاهل التعديلات؟").assertIsDisplayed()
            compose.onNodeWithText("متابعة التعديل").performClick()
            // The edit also survives a re-created activity, and still counts as unsaved.
            scenario.recreate()
            compose.onNodeWithTag("adhkar_target_input").assertTextEquals("7")
            Espresso.pressBack()
            compose.onNodeWithText("تجاهل التعديلات؟").assertIsDisplayed()
        }
    }

    @Test fun notificationReaderCountsOnlyExplicitActivationsAndSurvivesRecreation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("adhkar_reminders", Context.MODE_PRIVATE)
        val original = prefs.getString("state_v2", null)
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            val repo = DhikrRepository(context)
            val now = System.currentTimeMillis()
            val rule = DhikrReminder(id = "adhkar-ui-verification", dhikrId = DhikrCatalog.SALAWAT_ID, targetCount = 100)
            val occurrence = repo.ensureOccurrence(rule, DhikrWindow(now - 60_000, now + 3_600_000,
                rule.id + "|1|" + LocalDate.now(), LocalDate.now()))
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(MainTabNavigation.EXTRA_DESTINATION, MainTabNavigation.DESTINATION_ADHKAR)
                .putExtra(DhikrReminderScheduler.EXTRA_REMINDER_ID, rule.id)
                .putExtra(DhikrReminderScheduler.EXTRA_OCCURRENCE_ID, occurrence.id)
            scenario = ActivityScenario.launch(intent)
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("adhkar_reader").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("adhkar_count").assertIsDisplayed().assertIsEnabled()
            assertEquals(0, repo.state.value.occurrences.getValue(occurrence.id).count)
            compose.onNodeWithTag("adhkar_reader_text").performTouchInput { swipeUp() }
            compose.waitForIdle()
            assertEquals(0, repo.state.value.occurrences.getValue(occurrence.id).count)
            compose.onNodeWithTag("adhkar_count").performClick()
            compose.waitUntil { repo.state.value.occurrences.getValue(occurrence.id).count == 1 }
            scenario.recreate()
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("adhkar_count").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, repo.state.value.occurrences.getValue(occurrence.id).count)
            compose.onNodeWithTag("adhkar_undo").performClick()
            compose.waitUntil { repo.state.value.occurrences.getValue(occurrence.id).count == 0 }
            compose.onNodeWithTag("adhkar_undo").assertIsNotEnabled()
            // A second delivery/open request targets the same saved occurrence and never counts.
            scenario.onActivity { it.startActivity(intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
            compose.waitForIdle()
            assertEquals(0, repo.state.value.occurrences.getValue(occurrence.id).count)
            compose.onNodeWithTag("adhkar_count").assertIsDisplayed()
        } finally {
            scenario?.close()
            prefs.edit().apply { if (original == null) remove("state_v2") else putString("state_v2", original) }.commit()
            DhikrRepository.clearMemoryCache()
            DhikrReadingPresence.occurrenceId = null
        }
    }
}

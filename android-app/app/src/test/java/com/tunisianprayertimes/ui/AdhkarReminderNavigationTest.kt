package com.tunisianprayertimes.ui

import com.tunisianprayertimes.adhkar.DhikrCatalog
import com.tunisianprayertimes.adhkar.DhikrCategory
import com.tunisianprayertimes.adhkar.DhikrEntry
import com.tunisianprayertimes.adhkar.DhikrOccurrence
import com.tunisianprayertimes.adhkar.DhikrReminder
import com.tunisianprayertimes.adhkar.DhikrState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdhkarReminderNavigationTest {
    @Test fun personalReminderUsesPersonalEntryTitle() {
        val entry = DhikrEntry("personal", "ذكري الخاص", "النص", "", 1, emptySet(), custom = true)
        val rule = DhikrReminder(dhikrId = entry.id)

        assertEquals(entry.title, reminderTitle(rule, DhikrState(customEntries = listOf(entry))))
    }

    @Test fun emptyCollectionDoesNotOpenRepresentativeDhikr() {
        val representative = DhikrCatalog.entries.first { DhikrCategory.MORNING in it.categories }
        val rule = DhikrReminder(dhikrId = representative.id, collection = DhikrCategory.MORNING)
        val removed = DhikrCatalog.entries.filter { DhikrCategory.MORNING in it.categories }.map { it.id }.toSet()
        val state = DhikrState(reminders = listOf(rule),
            collectionRemovals = mapOf(DhikrCategory.MORNING to removed))

        assertTrue(reminderSessionItems(state, occurrence(rule)).isEmpty())
    }

    @Test fun collectionReminderOpensOnlyCurrentMembers() {
        val representative = DhikrCatalog.entries.first { DhikrCategory.MORNING in it.categories }
        val custom = DhikrEntry("personal", "ذكري الخاص", "النص", "", 1, emptySet(), custom = true)
        val rule = DhikrReminder(dhikrId = representative.id, collection = DhikrCategory.MORNING)
        val removed = DhikrCatalog.entries.filter { DhikrCategory.MORNING in it.categories }.map { it.id }.toSet()
        val state = DhikrState(reminders = listOf(rule), customEntries = listOf(custom),
            collectionRemovals = mapOf(DhikrCategory.MORNING to removed),
            collectionAdditions = mapOf(DhikrCategory.MORNING to setOf(custom.id)))

        assertEquals(listOf(custom.id), reminderSessionItems(state, occurrence(rule)))
    }

    private fun occurrence(rule: DhikrReminder) = DhikrOccurrence(
        id = "period", ruleId = rule.id, revision = rule.revision, date = "2026-09-25",
        dhikrId = rule.dhikrId, target = rule.targetCount, startMillis = 1, endMillis = 2,
    )
}

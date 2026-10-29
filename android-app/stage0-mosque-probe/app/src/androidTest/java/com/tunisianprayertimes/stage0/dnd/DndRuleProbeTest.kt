package com.tunisianprayertimes.stage0.dnd

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 36, maxSdkVersion = 36)
class DndRuleProbeTest {
    @Test
    fun ownedRulesMergeAndReleaseWithoutRingerWrites() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val report = DndRuleProbe(instrumentation.targetContext).runChecks(
            includeSyntheticUserOrigin = arguments.getString("syntheticUserOrigin") == "true"
        )
        println("DND_PROBE_REPORT $report")
        assertEquals(report.toString(2), "passed", report.getString("result"))
        assertTrue(report.toString(2), report.getJSONObject("cleanup").getBoolean("complete"))
        assertTrue(report.toString(2), report.getBoolean("preexistingOwnedRuleDefinitionsUnchanged"))
    }

    @Test
    fun repeatedRecoveryWithoutAJournalDoesNotTouchOtherOwners() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val first = DndRuleProbe.recoverJournal(context)
        val second = DndRuleProbe.recoverJournal(context)
        println("DND_PROBE_RECOVERY $first\nDND_PROBE_RECOVERY $second")
        assertTrue(first.toString(2), first.getBoolean("complete"))
        assertTrue(second.toString(2), second.getBoolean("complete"))
        assertEquals(second.toString(2), 0, second.getJSONArray("removedRuleIds").length())
    }

    @Test
    fun persistedJournalRecoveryTargetsOnlyItsCreatedRule() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val notifications = context.getSystemService(NotificationManager::class.java)
        val baseline = DndRuleProbe.observeStableBaseline(context)
        assertTrue(baseline.toString(2), baseline.getBoolean("stable"))
        val before = notifications.automaticZenRules
        try {
            val fixture = DndRuleProbe(context).prepareRecoveryFixture()
            println("DND_RECOVERY_FIXTURE $fixture")
            assertEquals(fixture.toString(2), "recovery_fixture_left_active", fixture.getString("result"))
            val id = fixture.getString("fixtureRuleId")
            val recovery = DndRuleProbe.recoverJournal(context)
            println("DND_SAME_PROCESS_JOURNAL_RECOVERY $recovery")
            assertTrue(recovery.toString(2), recovery.getBoolean("complete"))
            assertEquals(recovery.toString(2), listOf(id),
                (0 until recovery.getJSONArray("removedRuleIds").length()).map {
                    recovery.getJSONArray("removedRuleIds").getString(it)
                })
            val settled = DndRuleProbe.observeStableBaseline(context)
            println("DND_SAME_PROCESS_RECOVERY_SETTLED $settled")
            assertTrue(settled.toString(2), settled.getBoolean("stable"))
            assertEquals(settled.toString(2), baseline.getInt("aggregateFilter"), settled.getInt("aggregateFilter"))
            assertEquals(settled.toString(2), baseline.getInt("ringerMode"), settled.getInt("ringerMode"))
            assertEquals(before, notifications.automaticZenRules)
        } finally {
            assertTrue(DndRuleProbe.recoverJournal(context).getBoolean("complete"))
        }
    }

    /** Run alone with -e leaveDndRecoveryFixture true, then explicitly restart the activity. */
    @Test
    fun prepareJournalForNextLaunchRecovery() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("leaveDndRecoveryFixture") == "true")
        val report = DndRuleProbe(InstrumentationRegistry.getInstrumentation().targetContext).prepareRecoveryFixture()
        println("DND_RECOVERY_FIXTURE $report")
        assertEquals(report.toString(2), "recovery_fixture_left_active", report.getString("result"))
    }
}

package com.tunisianprayertimes

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.adhkar.DhikrReminderScheduler
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class MainTabRequestsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun launcher() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    private fun reminder(occurrenceId: String) = Intent(context, MainActivity::class.java)
        .putExtra(MainTabNavigation.EXTRA_DESTINATION, MainTabNavigation.DESTINATION_ADHKAR)
        .putExtra(MainTabNavigation.EXTRA_DHIKR_REMINDER_ID, "rule")
        .putExtra(DhikrReminderScheduler.EXTRA_OCCURRENCE_ID, occurrenceId)

    /** onSaveInstanceState, then a new activity instance restored from that bundle. */
    private fun recreate(requests: MainTabRequests, launchIntent: Intent) = MainTabRequests().apply {
        onCreate(launchIntent, Bundle().also(requests::onSaveInstanceState))
    }

    @Test fun reminderTappedAfterRecreationIsNotMistakenForTheHandledOne() {
        // 2026-09-29: the morning reminder opened its reader, the activity was destroyed in
        // the background, and the tahlil tap restarted the count at 1. The Adhkar tab had
        // saved request 1 as handled, so only the tab opened.
        val first = MainTabRequests().apply { onCreate(launcher(), null) }
        first.onNewIntent(reminder("morning"))
        val handled = first.current.sequence

        val restored = recreate(first, launcher())
        restored.onNewIntent(reminder("tahlil"))

        assertTrue(restored.current.sequence > handled)
        assertEquals(MainTabNavigation.DESTINATION_ADHKAR, restored.current.destination)
        assertEquals("tahlil", restored.current.dhikrOccurrenceId)
    }

    @Test fun numberingSurvivesRepeatedRecreation() {
        var requests = MainTabRequests().apply { onCreate(launcher(), null) }
        val seen = mutableListOf<Int>()
        repeat(3) { index ->
            requests.onNewIntent(reminder("occurrence-$index"))
            seen += requests.current.sequence
            requests = recreate(requests, launcher())
        }
        assertEquals(seen.distinct(), seen)
        assertEquals(seen.sorted(), seen)
    }

    @Test fun recreationRestoresTheLastRequestInsteadOfReplayingTheLaunchIntent() {
        // A task started by a notification tap keeps that intent as its launch intent.
        val launch = reminder("morning")
        val first = MainTabRequests().apply { onCreate(launch, null) }

        val restored = recreate(first, launch)

        // Same number, so the Adhkar tab's handled marker still matches; no tab is forced.
        assertEquals(first.current.sequence, restored.current.sequence)
        assertNull(restored.current.destination)
        assertEquals("morning", restored.current.dhikrOccurrenceId)
    }

    @Test fun mainActivityCarriesTheNumberingAcrossRecreation() {
        PrefsManager.markFirstLaunchDone(context)
        val launcher = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val first = Robolectric.buildActivity(MainActivity::class.java, launcher).create().start().resume()
        first.newIntent(reminder("morning"))
        val handled = first.get().tabRequests.current.sequence
        val saved = Bundle()
        first.pause().saveInstanceState(saved).stop().destroy()

        val second = Robolectric.buildActivity(MainActivity::class.java, launcher)
            .create(saved).start().restoreInstanceState(saved).resume()
        assertNull(second.get().tabRequests.current.destination)
        second.newIntent(reminder("tahlil"))

        assertTrue(second.get().tabRequests.current.sequence > handled)
        assertEquals("tahlil", second.get().tabRequests.current.dhikrOccurrenceId)
    }

    @Test fun reopeningFromRecentsIsNotANewRequest() {
        val requests = MainTabRequests()
        requests.onCreate(reminder("morning").addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY), null)

        assertNull(requests.current.destination)
        assertEquals(0, requests.current.sequence)
    }

    @Test fun intentWithoutDestinationLeavesTheCurrentRequest() {
        val requests = MainTabRequests().apply { onCreate(reminder("morning"), null) }
        val before = requests.current

        requests.onNewIntent(launcher())

        assertSame(before, requests.current)
    }
}

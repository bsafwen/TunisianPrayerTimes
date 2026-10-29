package com.tunisianprayertimes.stage0.execution

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Recovery bookkeeping tests do not stand in for real alarm/geofence/indoor device evidence. */
@RunWith(AndroidJUnit4::class)
class ExecutionJournalTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun before() { ExecutionProbe.wipe(context) }
    @After fun after() { ExecutionProbe.wipe(context) }

    @Test fun expiredSessionIsReleasedAndNotRevived() {
        ExecutionJournal.begin(context, "expired-fixture", -1L)
        assertTrue(ExecutionProbe.recoverExpired(context))
        assertEquals("", ExecutionJournal.currentSession(context))
        assertFalse(ExecutionProbe.recoverExpired(context))
        assertTrue(ExecutionProbe.diagnostics(context).contains("expired_or_previous_boot_recovery"))
    }

    @Test fun freshSessionSurvivesRecoveryAndStaleFinishCannotClearIt() {
        ExecutionJournal.begin(context, "new-session", 60_000L)
        assertFalse(ExecutionProbe.recoverExpired(context))
        ExecutionJournal.finish(context, "stale-release", "old-session")
        assertEquals("new-session", ExecutionJournal.currentSession(context))
        ExecutionJournal.finish(context, "matching-release", "new-session")
        assertEquals("", ExecutionJournal.currentSession(context))
    }

    @Test fun journalIsBoundedAndDefaultDiagnosticsContainNoCoordinates() {
        repeat(260) { ExecutionJournal.event(context, "bounded_entry", fields = mapOf("accuracy_m" to 12.0, "age_ms" to 50L)) }
        val json = JSONObject(ExecutionProbe.diagnostics(context))
        assertEquals(240, json.getJSONArray("events").length())
        assertFalse(json.toString().contains("latitude"))
        assertFalse(json.toString().contains("longitude"))
    }

    @Test fun armedAlarmGenerationIsOneShotAndOldDeliveryDoesNotConsumeReplacement() {
        val trigger = SystemClock.elapsedRealtime() + 10_000L
        ExecutionJournal.armStart(context, "old-alarm", trigger)
        ExecutionJournal.armStart(context, "new-alarm", trigger + 1)
        assertFalse(ExecutionJournal.consumeArmedStart(context, "old-alarm", trigger))
        assertEquals("new-alarm", ExecutionJournal.armedStart(context))
        assertFalse(ExecutionJournal.consumeArmedStart(context, "new-alarm", trigger))
        assertTrue(ExecutionJournal.consumeArmedStart(context, "new-alarm", trigger + 1))
        assertFalse(ExecutionJournal.consumeArmedStart(context, "new-alarm", trigger + 1))
    }

    @Test fun stopInvalidatesAlarmGeofenceAndAlreadyEnqueuedServiceStart() {
        val trigger = SystemClock.elapsedRealtime() + 10_000L
        ExecutionJournal.armStart(context, "armed-before-stop", trigger)
        ExecutionJournal.beginGeofence(context, "fence-before-stop", 60_000L)
        ExecutionJournal.reserveServiceStart(context, "queued-before-stop")
        ExecutionProbe.stop(context, "explicit-test-stop")
        assertFalse(ExecutionJournal.consumeArmedStart(context, "armed-before-stop", trigger))
        assertFalse(ExecutionJournal.consumeGeofenceStart(context, "fence-before-stop"))
        assertFalse(ExecutionJournal.consumeServiceStart(context, "queued-before-stop"))
        ProbeStartAlarmReceiver().onReceive(context, Intent(ExecutionProbe.ACTION_START_ALARM)
            .setData(ExecutionProbe.identityUri("start", "armed-before-stop"))
            .putExtra(ExecutionProbe.EXTRA_SESSION, "armed-before-stop")
            .putExtra(ExecutionProbe.EXTRA_TRIGGER_ELAPSED, trigger))
        assertEquals("", ExecutionJournal.currentSession(context))
        assertTrue(ExecutionProbe.diagnostics(context).contains("unarmed_stale_or_already_consumed"))
    }

    @Test fun geofenceScopeCanStartOnlyOnceEvenAfterSessionExpiry() {
        ExecutionJournal.beginGeofence(context, "one-shot-fence", 60_000L)
        assertTrue(ExecutionJournal.consumeGeofenceStart(context, "one-shot-fence"))
        ExecutionJournal.begin(context, "expired-geofence-session", -1L)
        assertTrue(ExecutionProbe.recoverExpired(context))
        assertFalse(ExecutionJournal.consumeGeofenceStart(context, "one-shot-fence"))
        ExecutionJournal.beginGeofence(context, "explicit-new-scope", 60_000L)
        assertFalse(ExecutionJournal.consumeGeofenceStart(context, "one-shot-fence"))
        assertTrue(ExecutionJournal.consumeGeofenceStart(context, "explicit-new-scope"))
    }

    @Test fun releasePendingIntentIdentityAndStaleDeliveryRemainSessionSpecific() {
        val old = ExecutionProbe.releasePending(context, "old-release-session")
        val newer = ExecutionProbe.releasePending(context, "new-release-session")
        try {
            assertNotEquals(old, newer)
            ExecutionJournal.begin(context, "new-release-session", 60_000L)
            ProbeReleaseReceiver().onReceive(context, ExecutionProbe.releaseIntent(context, "old-release-session"))
            assertEquals("new-release-session", ExecutionJournal.currentSession(context))
            // Even a mismatched mutable payload cannot make an old identity target the newer session.
            ProbeReleaseReceiver().onReceive(context, ExecutionProbe.releaseIntent(context, "old-release-session")
                .putExtra(ExecutionProbe.EXTRA_SESSION, "new-release-session"))
            assertEquals("new-release-session", ExecutionJournal.currentSession(context))
        } finally {
            old.cancel()
            newer.cancel()
        }
    }
}

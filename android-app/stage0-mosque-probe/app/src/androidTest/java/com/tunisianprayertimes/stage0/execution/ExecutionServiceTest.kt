package com.tunisianprayertimes.stage0.execution

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.json.JSONObject
import org.junit.runner.RunWith

/** Finite visible-start/timeout check; this does not prove a background alarm or geofence exemption. */
@RunWith(AndroidJUnit4::class)
class ExecutionServiceTest {
    @Test fun visibleSessionEndsAndProviderRemovalCompletesAtTheConfiguredWindowEnd() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue("Physical run must explicitly grant fine location first",
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
        val activity = instrumentation.startActivitySync(Intent(Intent.ACTION_MAIN)
            .setClassName(context.packageName, "${context.packageName}.ProbeActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            WindowAuthority.configure(context, 0, 3_000, armBoundaries = false)
            ExecutionProbe.wipe(context)
            lateinit var outcome: ExecutionProbe.Outcome
            instrumentation.runOnMainSync { outcome = ExecutionProbe.startFromVisible(activity, 3_000L) }
            assertTrue(outcome.detail, outcome.accepted)
            val windowEnd = WindowAuthority.peek(context)!!.end
            val waitEnd = windowEnd + 5_000L
            while (SystemClock.elapsedRealtime() < waitEnd) {
                val current = ExecutionProbe.diagnostics(context)
                if (ExecutionJournal.currentSession(context).isEmpty() && current.contains("provider_remove_complete")) break
                SystemClock.sleep(100L)
            }
            val evidence = ExecutionProbe.diagnostics(context)
            val entries = JSONObject(evidence).getJSONArray("events")
            val events = (0 until entries.length()).map { entries.getJSONObject(it) }
            assertTrue(evidence, events.any { it.getString("event") == "service_ready" })
            val ended = events.first { it.getString("event") == "session_finished" &&
                (it.getString("detail").startsWith("window_closed") || it.getString("detail") == "in_session_window_deadline") }
            assertTrue(evidence, ended.getLong("elapsed_ms") in windowEnd..(windowEnd + 2_000L))
            assertTrue(evidence, events.any { it.getString("event") == "provider_remove_complete" && it.getString("detail") == "removed" })
            for (event in events.filter { it.getString("event") == "provider_request" }) {
                assertTrue(evidence, event.getLong("native_call_elapsed_ms") + event.getLong("request_duration_ms") <= windowEnd)
            }
            for (event in events.filter { it.getString("event") == "fix" }) {
                assertTrue(evidence, event.getLong("evaluation_elapsed_ms") < windowEnd)
            }
            assertEquals("", ExecutionJournal.currentSession(context))

        } finally {
            WindowAuthority.close(context)
            ExecutionProbe.wipe(context)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}

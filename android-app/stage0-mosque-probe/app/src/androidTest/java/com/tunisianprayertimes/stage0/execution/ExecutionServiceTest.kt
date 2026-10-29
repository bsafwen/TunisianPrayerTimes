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
import org.junit.runner.RunWith

/** Finite visible-start/timeout check; this does not prove a background alarm or geofence exemption. */
@RunWith(AndroidJUnit4::class)
class ExecutionServiceTest {
    @Test fun visibleSessionFinishesAtItsInProcessDeadline() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue("Physical run must explicitly grant fine location first",
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
        val activity = instrumentation.startActivitySync(Intent(Intent.ACTION_MAIN)
            .setClassName(context.packageName, "${context.packageName}.ProbeActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            ExecutionProbe.wipe(context)
            lateinit var outcome: ExecutionProbe.Outcome
            instrumentation.runOnMainSync { outcome = ExecutionProbe.startFromVisible(activity, 3_000L) }
            assertTrue(outcome.detail, outcome.accepted)
            val end = SystemClock.elapsedRealtime() + 15_000L
            while (SystemClock.elapsedRealtime() < end && !ExecutionProbe.diagnostics(context).contains("in_session_deadline")) {
                SystemClock.sleep(100L)
            }
            val evidence = ExecutionProbe.diagnostics(context)
            assertTrue(evidence, evidence.contains("service_ready"))
            assertTrue(evidence, evidence.contains("in_session_deadline"))
            assertEquals("", ExecutionJournal.currentSession(context))
        } finally {
            ExecutionProbe.wipe(context)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}

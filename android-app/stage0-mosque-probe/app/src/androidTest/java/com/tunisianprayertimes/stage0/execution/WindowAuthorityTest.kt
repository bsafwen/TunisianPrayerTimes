package com.tunisianprayertimes.stage0.execution

import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit state fixtures do not stand in for the separately measured real system alarm route. */
@RunWith(AndroidJUnit4::class)
class WindowAuthorityTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun before() { WindowAuthority.close(context); ExecutionProbe.wipe(context) }
    @After fun after() { WindowAuthority.close(context); ExecutionProbe.wipe(context) }
    private fun configure(delay: Long = 0, mode: String = "TIME_AND_MOSQUE") =
        WindowAuthority.configure(context, delay, 60_000, mode = mode, armBoundaries = false)

    @Test fun noWindowAndFutureWindowCannotStartAnyLocationProviderOrGeofence() {
        for (withFuture in listOf(false, true)) {
            if (withFuture) configure(60_000)
            assertFalse(ExecutionProbe.startFromVisible(context).accepted)
            var response: ExecutionProbe.Outcome? = null
            ExecutionProbe.registerAtCurrentPosition(context) { response = it }
            assertNotNull(response); assertFalse(response!!.accepted)
            val events = JSONObject(ExecutionProbe.diagnostics(context)).getJSONArray("events")
            assertTrue((0 until events.length()).none { events.getJSONObject(it).getString("event") in
                setOf("provider_request", "geofence_registered", "geofence_center_acquired", "fix") })
        }
    }

    @Test fun timeOnlyScheduleHasNoLocationAuthority() {
        configure(mode = "TIME_ONLY")
        assertNull(WindowAuthority.peek(context))
        assertFalse(ExecutionProbe.startFromVisible(context).accepted)
        assertFalse(ExecutionProbe.armAlarm(context).accepted)
    }

    @Test fun obsoleteCallbackCannotUseAReplacementActiveWindow() {
        configure(); val old = WindowAuthority.peek(context)!!.token
        configure(); assertNotEquals(old, WindowAuthority.peek(context)!!.token)
        var called = false
        assertNull(WindowAuthority.withLease(context, "injected_obsolete_callback", old) { called = true })
        assertFalse(called)
    }

    @Test fun skipPersistsAndCannotBeUndoneByNewLocationStartOrReconcile() {
        configure(); val old = WindowAuthority.peek(context)!!.token
        WindowAuthority.skip(context)
        assertNull(WindowAuthority.peek(context))
        assertFalse(WindowAuthority.reconcile(context, "test_reentry"))
        assertFalse(ExecutionProbe.startFromVisible(context).accepted)
        val schedule = WindowAuthority.snapshot(context).getJSONObject("schedule")
        assertEquals("fixture-a", schedule.getJSONArray("dismissed").getString(0))
        assertNull(WindowAuthority.withLease(context, "injected_old_fix", old) { true })
    }

    @Test fun expiredWindowRejectsLateCallbacksWithoutProviderOrGeometryWork() {
        WindowAuthority.configure(context, 0, 1_000, armBoundaries = false)
        val old = WindowAuthority.peek(context)!!.token
        SystemClock.sleep(1_050)
        var evaluated = false
        assertNull(WindowAuthority.withLease(context, "injected_late_fix", old) { evaluated = true })
        assertFalse(evaluated)
        assertFalse(ExecutionProbe.startFromVisible(context).accepted)
        assertEquals("", ExecutionJournal.currentSession(context))
    }

    @Test fun unsupportedRemovedModeCannotFallBackToAnActiveSchedule() {
        configure()
        val result = WindowAuthority.configure(context, 0, 60_000, mode = "UNSUPPORTED_REMOVED_MODE", armBoundaries = false)
        assertFalse(result.getBoolean("accepted")); assertNull(WindowAuthority.peek(context))
    }
}

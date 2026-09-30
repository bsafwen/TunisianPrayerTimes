package com.tunisianprayertimes.tv.kiosk

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.tunisianprayertimes.tv.MainActivity

/**
 * Records every crash, then has the display started again in a few seconds, so a wall never stays
 * on the box's launcher. [CrashLoopGuard] turns a crash loop into safe mode, then stops restarting.
 * The system's own handler still runs afterwards, so the crash also reaches Play's reports.
 */
object KioskCrashHandler {

    const val RESTART_DELAY_MILLIS = 3_000L

    fun install(context: Context, store: KioskStore, guard: CrashLoopGuard = CrashLoopGuard()) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val (state, decision) = guard.recordCrash(store.crashes, SystemClock.elapsedRealtime())
                store.crashes = state
                store.eventLog.append(KioskEvent.CRASH, "$decision ${summary(error)}")
                if (decision == CrashLoopGuard.Decision.RESTART_IN_SAFE_MODE) store.eventLog.append(KioskEvent.SAFE_MODE_ENTER)
                if (decision != CrashLoopGuard.Decision.STOP &&
                    shouldRestart(SystemClock.elapsedRealtime(), store.state, MainActivity.inFront)
                ) {
                    KioskController.scheduleRestart(app, RESTART_DELAY_MILLIS)
                }
            }.onFailure { Log.e("Kiosk", "crash handler", it) }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * Only a display that was on screen is put back, or one that died there before its stop was
     * recorded. A crash elsewhere (a receiver, the dashboard server, the quick-start service while the
     * admin works in the box's settings) is left to the watchdog, which respects the admin's time away.
     */
    fun shouldRestart(now: Long, state: KioskState, inFront: Boolean): Boolean {
        if (state.adminAwayUntil != null && now < state.adminAwayUntil) return false
        val resumed = state.resumedAt
        return inFront || (resumed != null && (state.stoppedAt == null || state.stoppedAt < resumed))
    }

    /** The exception and the first lines of its stack, enough to recognise it on the health page. */
    fun summary(error: Throwable): String =
        (listOf(error.toString()) + error.stackTrace.take(STACK_LINES).map { "at $it" }).joinToString(" | ")

    private const val STACK_LINES = 20
}

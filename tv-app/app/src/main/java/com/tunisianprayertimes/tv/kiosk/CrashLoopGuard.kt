package com.tunisianprayertimes.tv.kiosk

/**
 * Decides what happens after a crash, so a bad setting or file cannot restart the app forever.
 * Times are elapsedRealtime (immune to clock changes); KioskStore drops times from before a reboot.
 *
 * One or two crashes within [window]: restart. The [safeModeAfter]th: restart in safe mode (default
 * theme, no custom backgrounds or announcements) until [safeModeFor] passes without a crash.
 * The [giveUpAfter]th: stop restarting and leave it to the watchdog, which retries every few minutes.
 */
class CrashLoopGuard(
    private val window: Long = 10 * MINUTE,
    private val safeModeAfter: Int = 3,
    private val giveUpAfter: Int = 6,
    private val safeModeFor: Long = 30 * MINUTE,
) {
    enum class Decision { RESTART, RESTART_IN_SAFE_MODE, STOP }

    data class State(val crashes: List<Long> = emptyList(), val safeModeUntil: Long? = null) {
        fun encode(): String = crashes.joinToString(",") + ";" + (safeModeUntil?.toString() ?: "")

        companion object {
            fun decode(text: String?): State {
                if (text.isNullOrBlank()) return State()
                val (crashes, until) = text.split(';', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                return State(crashes.split(',').mapNotNull { it.trim().toLongOrNull() }, until.trim().toLongOrNull())
            }
        }
    }

    fun recordCrash(state: State, now: Long): Pair<State, Decision> {
        val recent = state.crashes.filter { it in (now - window)..now } + now
        val inSafeMode = isSafeMode(state, now) || recent.size >= safeModeAfter
        val next = State(recent.takeLast(giveUpAfter), if (inSafeMode) now + safeModeFor else null)
        val decision = when {
            recent.size >= giveUpAfter -> Decision.STOP
            inSafeMode -> Decision.RESTART_IN_SAFE_MODE
            else -> Decision.RESTART
        }
        return next to decision
    }

    /** True after [giveUpAfter] crashes within [window]: nothing should start the app until the window passes. */
    fun isGivingUp(state: State, now: Long): Boolean = state.crashes.count { it in (now - window)..now } >= giveUpAfter

    /** True while crashes are recent enough; a reboot (a smaller clock) ends safe mode. */
    fun isSafeMode(state: State, now: Long): Boolean {
        val until = state.safeModeUntil ?: return false
        return now < until && until - now <= safeModeFor
    }

    /**
     * True when the wall is really broken and an update may be installed at once, prayer or not: the app
     * stopped restarting, or it is in safe mode and either off the screen or crashing since this boot's
     * update ([packageReplacedAt], elapsedRealtime). Safe mode on its own usually still shows the display.
     */
    fun needsRescue(state: State, now: Long, packageReplacedAt: Long?, displayInFront: Boolean): Boolean {
        if (isGivingUp(state, now)) return true
        if (!isSafeMode(state, now)) return false
        val sinceUpdate = packageReplacedAt != null && state.crashes.all { it >= packageReplacedAt }
        return !displayInFront || sinceUpdate
    }

    companion object {
        const val MINUTE = 60_000L
    }
}

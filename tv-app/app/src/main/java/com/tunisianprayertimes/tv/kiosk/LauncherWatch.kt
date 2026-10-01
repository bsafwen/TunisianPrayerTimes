package com.tunisianprayertimes.tv.kiosk

/**
 * The box's own home screens: when one of them comes to the front, the box is showing its home
 * instead of the display (Fire TV after boot, after standby, or after Home).
 */
object LauncherPackages {

    /** Fire TV's home and its Kids home. (com.amazon.firebat is Prime Video, not a home.) */
    val AMAZON = setOf("com.amazon.tv.launcher", "com.amazon.tahoe")

    /** Resolver, fallback home and settings screens: an admin going there must never be pulled back. */
    private val NEVER = setOf("android", "com.android.settings", "com.android.tv.settings", "com.amazon.tv.settings.v2")

    fun of(defaultHome: String?, homeCandidates: Collection<String>, self: String): Set<String> =
        (AMAZON + homeCandidates + listOfNotNull(defaultHome)) - NEVER - self
}

/** System panels that come and go over any app (volume, quick settings): they neither start nor cancel anything. */
val NEUTRAL_WINDOW_PACKAGES = setOf("com.android.systemui", "com.amazon.tv.quicksettings.ui")

enum class LauncherAction { NOW, AFTER_GRACE, IGNORE }

/** When the quick-start service brings the display back, all times in elapsedRealtime milliseconds. */
object LauncherWatchPolicy {

    /** The service starts the display when it connects this early after boot (a slow box may take minutes). */
    const val BOOT_WINDOW_MILLIS = 10 * 60_000L

    /**
     * Just after boot the home screen is never what the mosque wants (Amazon's home may even come back by
     * itself): the display replaces it at once. Past this, a Home press is an installer's and gets the grace.
     */
    const val BOOT_NOW_WINDOW_MILLIS = 3 * 60_000L

    /** Fire TV shows its home when it wakes from standby: the same, for a minute after the screen came on. */
    const val WAKE_WINDOW_MILLIS = 60_000L

    /** Otherwise someone pressed Home: a minute to walk on to the box's settings before the display returns. */
    const val GRACE_MILLIS = 60_000L

    /**
     * A home screen just came to the front, so the display is not (its own window, whose stop may still
     * be on its way, is behind it).
     */
    fun onLauncherFront(now: Long, screenOnAt: Long?, setupDone: Boolean, adminAwayUntil: Long?): LauncherAction = when {
        !setupDone -> LauncherAction.IGNORE
        adminAwayUntil != null && now < adminAwayUntil -> LauncherAction.IGNORE
        now < BOOT_NOW_WINDOW_MILLIS -> LauncherAction.NOW
        screenOnAt != null && now - screenOnAt < WAKE_WINDOW_MILLIS -> LauncherAction.NOW
        else -> LauncherAction.AFTER_GRACE
    }

    /**
     * When the service connects again because the app's process died while the display was on screen (a crash,
     * a low-memory kill): the display goes back, unless the admin left, the screen is off or the app keeps crashing.
     * The regular 3-second crash restart may be refused on Android 10+ when only the service allows background starts.
     */
    fun refrontOnReconnect(
        now: Long, state: KioskState, inFront: Boolean, setupDone: Boolean, interactive: Boolean, givingUp: Boolean,
    ): Boolean {
        if (inFront || !setupDone || !interactive || givingUp) return false
        val resumed = state.resumedAt ?: return false
        if (state.stoppedAt != null && state.stoppedAt >= resumed) return false
        return state.adminAwayUntil == null || now >= state.adminAwayUntil
    }

    /** When the service connects: right after boot, the display starts unless it already showed this boot. */
    fun launchOnConnect(now: Long, setupDone: Boolean, inFront: Boolean, resumedThisBoot: Boolean, adminAwayUntil: Long?): Boolean =
        setupDone && !inFront && !resumedThisBoot && now < BOOT_WINDOW_MILLIS &&
            (adminAwayUntil == null || now >= adminAwayUntil)
}

/**
 * On wake from standby, for boxes without the quick-start service (Fire OS 7, or "display over other
 * apps" only): Fire TV shows its own home then, so the display goes back on top if it is not already.
 * On other boxes the display is back by itself and nothing happens.
 */
object WakePolicy {
    const val DELAY_MILLIS = 1_000L

    fun shouldRefront(now: Long, inFront: Boolean, quickStartRunning: Boolean, canBringToFront: Boolean, setupDone: Boolean, adminAwayUntil: Long?): Boolean =
        !inFront && !quickStartRunning && canBringToFront && setupDone && (adminAwayUntil == null || now >= adminAwayUntil)
}

/**
 * Never fights a screen that insists (a system update notice, a home screen that keeps coming back):
 * at most one start every [minGap], and [max] in any [window].
 */
class RefrontLimiter(private val minGap: Long = 5_000L, private val window: Long = 10 * 60_000L, private val max: Int = 6) {
    private val times = ArrayDeque<Long>()

    @Synchronized
    fun tryAcquire(now: Long): Boolean {
        if (waitMillis(now) != 0L) return false
        times.addLast(now)
        return true
    }

    /** 0 when a start may go now, the rest of the gap when only the gap holds it, -1 when the cap is reached. */
    @Synchronized
    fun waitMillis(now: Long): Long {
        while (times.isNotEmpty() && now - times.first() > window) times.removeFirst()
        if (times.size >= max) return -1L
        val sinceLast = if (times.isEmpty()) Long.MAX_VALUE else now - times.last()
        return if (sinceLast < minGap) minGap - sinceLast else 0L
    }
}

package com.tunisianprayertimes.tv.kiosk

/**
 * How this box lets the app come to the front by itself (at boot, after a crash, after Home).
 * Android 10+ blocks activity starts from the background unless the app is the home app, the
 * device owner, allowed to draw over other apps, or has an accessibility service the system bound.
 */
enum class AutoStartTier {
    /** The app is the home screen: the system shows it at boot and on Home. The strongest. */
    HOME,

    /** Provisioned as device owner with adb: the app pins itself with lock task. */
    DEVICE_OWNER,

    /**
     * The quick-start service is on (the sideloaded build only): the system starts it as soon as the
     * box is up, before the boot broadcast reaches apps, and lets it open the display; it also brings
     * the display back when the box's own home screen appears (after boot, standby or Home). The
     * fastest on Fire TV, where the app cannot be the home screen.
     */
    ACCESSIBILITY,

    /** "Display over other apps" is granted: background starts are allowed. */
    OVERLAY,

    /** Android 8 or 9: background starts are still allowed. */
    LEGACY,

    /** Nothing allows it: after a reboot the box stays on its launcher. */
    NONE,
}

/**
 * [fireTv]: an Amazon Fire TV, whose own home screen always comes back and can't be replaced.
 * [canBringToFront]: a start from the background is allowed now; false for a quick-start service
 * that is on but not bound, unless "display over other apps" or Android 8/9 allow it anyway.
 */
data class AutoStart(
    val tier: AutoStartTier,
    val fireTv: Boolean = false,
    val canBringToFront: Boolean = tier != AutoStartTier.NONE,
)

object AutoStartTierResolver {

    /**
     * The strongest way this box allows. Fire TV: Fire OS 7 (Android 9) starts the app at boot with
     * nothing to set; Fire OS 8+ needs the quick-start service or "display over other apps", both
     * granted once with adb; being its home screen is impossible (Fire OS puts its own back).
     * The quick-start tier is shown as soon as it is on ([accessibilityEnabled]), so the kiosk page can
     * say when the box does not run it; only a bound service ([accessibilityRunning]) allows starts.
     */
    fun resolve(
        isDefaultHome: Boolean,
        isDeviceOwner: Boolean,
        accessibilityEnabled: Boolean,
        canDrawOverlays: Boolean,
        sdkInt: Int,
        isFireTv: Boolean,
        accessibilityRunning: Boolean,
    ): AutoStart {
        val tier = when {
            isDefaultHome && !isFireTv -> AutoStartTier.HOME
            isDeviceOwner -> AutoStartTier.DEVICE_OWNER
            accessibilityEnabled -> AutoStartTier.ACCESSIBILITY
            canDrawOverlays -> AutoStartTier.OVERLAY
            sdkInt < ANDROID_10 -> AutoStartTier.LEGACY
            else -> AutoStartTier.NONE
        }
        val canBringToFront = if (tier == AutoStartTier.ACCESSIBILITY) accessibilityRunning || canDrawOverlays || sdkInt < ANDROID_10
        else tier != AutoStartTier.NONE
        return AutoStart(tier, fireTv = isFireTv, canBringToFront = canBringToFront)
    }

    private const val ANDROID_10 = 29
}

/**
 * The foreground bookkeeping the watchdog decides on, in elapsedRealtime milliseconds.
 * [adminAwayUntil] is set when the admin deliberately leaves the app (Exit to Android, system settings).
 */
data class KioskState(
    val resumedAt: Long? = null,
    val stoppedAt: Long? = null,
    val refrontedAt: Long? = null,
    val adminAwayUntil: Long? = null,
)

/** When to bring the app back after it left the screen (Home pressed, another app opened). */
object ForegroundWatchdogPolicy {

    const val GRACE_MILLIS = 3 * 60_000L

    /** A start the box silently refused is tried again after this long, not on every tick. */
    const val RETRY_MILLIS = 15 * 60_000L

    /**
     * [inFront] is whether the display is on screen in this process now. When it is not, but its last
     * record is a start with no stop after it, the process died on screen (a crash, a low-memory kill):
     * the time since that start counts as time away. Not while the screen is off ([interactive] false):
     * a start then never reaches the screen, and the screen-on path brings the display back anyway.
     */
    fun shouldRefront(
        now: Long, state: KioskState, autoStart: AutoStart, setupDone: Boolean, inFront: Boolean = false, interactive: Boolean = true,
    ): Boolean {
        if (inFront || !interactive || !setupDone || !autoStart.canBringToFront) return false
        val resumed = state.resumedAt
        val stopped = state.stoppedAt?.takeIf { resumed == null || it >= resumed } ?: resumed ?: return false
        val refronted = state.refrontedAt
        if (refronted != null && refronted >= stopped && now - refronted < RETRY_MILLIS) return false
        if (state.adminAwayUntil != null && now < state.adminAwayUntil) return false
        return now - stopped >= GRACE_MILLIS
    }
}

/**
 * How long the admin may stay in the system's pages (Exit to Android, Wi-Fi, date, permissions): the
 * time asked for, but never into the prayer. Every path that brings the display back waits for
 * [KioskState.adminAwayUntil], so the prayer's own time caps it here. The app's own timer then brings
 * the display back on time instead of the inexact watchdog, with an alarm behind it for a process the
 * system stopped (Android 12+ may deliver that alarm up to 10 minutes late).
 */
object AdminAway {

    /** How soon after the display left the screen its away window is looked at again, when it is already over. */
    const val RECHECK_MILLIS = 3_000L

    /** The display is back this long before the adhan (or the iqamah of a prayer already called). */
    val BEFORE_PRAYER: java.time.Duration = java.time.Duration.ofMinutes(1)

    /**
     * The end of the away window in elapsedRealtime: [millis] after [nowElapsed], but no later than
     * [BEFORE_PRAYER] before [prayerAt] ([now] is the wall time at [nowElapsed]; null: no prayer is
     * known, as with an impossible clock). At once when that is already past.
     */
    fun until(nowElapsed: Long, millis: Long, now: java.time.LocalDateTime, prayerAt: java.time.LocalDateTime?): Long {
        val asked = nowElapsed + millis.coerceAtLeast(0)
        prayerAt ?: return asked
        val left = java.time.Duration.between(now, prayerAt.minus(BEFORE_PRAYER)).toMillis().coerceAtLeast(0)
        return minOf(asked, nowElapsed + left)
    }

    /**
     * When to look at the away window [until] again once the display has left the screen at
     * [nowElapsed]: at its end, or just after now when it ended while the display was still in front
     * (no time was left before the prayer), so the display comes back at once instead of at the watchdog.
     */
    fun checkAt(until: Long, nowElapsed: Long): Long = maxOf(until, nowElapsed + RECHECK_MILLIS)
}

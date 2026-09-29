package com.tunisianprayertimes.tv.kiosk

/**
 * How this box lets the app come to the front by itself (at boot, after a crash, after Home).
 * Android 10+ blocks activity starts from the background unless the app is the home app, the
 * device owner, or allowed to draw over other apps.
 */
enum class AutoStartTier {
    /** The app is the home screen: the system shows it at boot and on Home. The strongest. */
    HOME,

    /** Provisioned as device owner with adb: the app pins itself with lock task. */
    DEVICE_OWNER,

    /** "Display over other apps" is granted: background starts are allowed. */
    OVERLAY,

    /** Android 8 or 9: background starts are still allowed. */
    LEGACY,

    /** Nothing allows it: after a reboot the box stays on its launcher. */
    NONE,
}

data class AutoStart(val tier: AutoStartTier, val unsupportedDevice: Boolean = false) {
    val canBringToFront: Boolean get() = tier != AutoStartTier.NONE
}

object AutoStartTierResolver {

    /** Fire TV blocks launching at boot whatever is granted, so it is reported as unsupported. */
    fun resolve(isDefaultHome: Boolean, isDeviceOwner: Boolean, canDrawOverlays: Boolean, sdkInt: Int, isFireTv: Boolean): AutoStart = when {
        isFireTv -> AutoStart(AutoStartTier.NONE, unsupportedDevice = true)
        isDefaultHome -> AutoStart(AutoStartTier.HOME)
        isDeviceOwner -> AutoStart(AutoStartTier.DEVICE_OWNER)
        canDrawOverlays -> AutoStart(AutoStartTier.OVERLAY)
        sdkInt < ANDROID_10 -> AutoStart(AutoStartTier.LEGACY)
        else -> AutoStart(AutoStartTier.NONE)
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
     * the time since that start counts as time away.
     */
    fun shouldRefront(now: Long, state: KioskState, autoStart: AutoStart, setupDone: Boolean, inFront: Boolean = false): Boolean {
        if (inFront || !setupDone || !autoStart.canBringToFront) return false
        val resumed = state.resumedAt
        val stopped = state.stoppedAt?.takeIf { resumed == null || it >= resumed } ?: resumed ?: return false
        val refronted = state.refrontedAt
        if (refronted != null && refronted >= stopped && now - refronted < RETRY_MILLIS) return false
        if (state.adminAwayUntil != null && now < state.adminAwayUntil) return false
        return now - stopped >= GRACE_MILLIS
    }
}

package com.tunisianprayertimes.platform

/**
 * Platform abstraction for controlling system volume/silence mode.
 * On Android: DND + AudioManager. On Desktop: system volume API.
 */
expect object SilenceController {
    /** Whether the system is currently in silent/muted mode. */
    fun isSilent(): Boolean

    /** Enable silent/mute mode. */
    fun enableSilence()

    /** Restore normal mode. */
    fun disableSilence()

    /** Whether the app has permission to control silence (DND on Android, always true on desktop). */
    fun hasPermission(): Boolean
}

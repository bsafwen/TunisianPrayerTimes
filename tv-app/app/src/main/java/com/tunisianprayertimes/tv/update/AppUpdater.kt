package com.tunisianprayertimes.tv.update

/** Where the app's own updates stand, for the settings, the kiosk page and the dashboard. */
data class UpdateStatus(
    /** False in the Play build: Google Play updates it. */
    val supported: Boolean,
    /** The version waiting to be installed, if any. */
    val available: String? = null,
    /** What is happening, for the admin (Arabic). */
    val message: String = "",
    /** The box does not yet let the app install its updates ("Install unknown apps"). */
    val needsPermission: Boolean = false,
)

/**
 * The app's own updates. The Play build has none (Play updates it, and Play forbids any other way);
 * the GitHub build, for boxes without Play Store, fetches them from GitHub releases.
 */
interface AppUpdater {
    val status: UpdateStatus

    /**
     * Called about every half hour: checks for an update when one is due and the TV is [online],
     * downloads it, and installs it only when [quiet] (at night, with no prayer on screen), because
     * installing restarts the app.
     */
    suspend fun tick(online: Boolean, quiet: Boolean)

    /** The admin asked for the update now: checks, downloads and installs. Returns what happens (Arabic). */
    suspend fun installNow(): String
}

/** The Play build: updated by Google Play. */
object PlayUpdates : AppUpdater {
    override val status = UpdateStatus(supported = false, message = "يُحدَّث هذا الإصدار من متجر Google Play")
    override suspend fun tick(online: Boolean, quiet: Boolean) = Unit
    override suspend fun installNow(): String = status.message
}

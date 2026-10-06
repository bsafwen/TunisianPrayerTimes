package com.tunisianprayertimes.tv.kiosk

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * The quick-start accessibility service. Only the sideloaded (GitHub) build has it: Google Play does
 * not allow a service that opens an app by itself, and Fire TV, where it matters most, has no Play
 * Store. It reads no screen content: it only notices which app is in front.
 *
 * Fire TV has no page to turn it on, so the admin grants WRITE_SECURE_SETTINGS once with adb and the
 * kiosk page turns it on (and back on after a force stop, which removes it).
 */
object KioskAccessibility {

    const val SERVICE_CLASS = "com.tunisianprayertimes.tv.kiosk.KioskAccessibilityService"

    /** The service is bound right now (set by the service): this process may start the display from the background. */
    @Volatile var serviceConnected: Boolean = false

    /** "on", "off" or "running", for the kiosk log. */
    fun state(context: Context): String = when {
        serviceConnected -> "running"
        isEnabled(context) -> "on"
        else -> "off"
    }
    private const val WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"

    fun component(context: Context) = ComponentName(context.packageName, SERVICE_CLASS)

    /** This build has the service (the Play build does not). */
    fun isAvailable(context: Context): Boolean =
        runCatching { context.packageManager.getServiceInfo(component(context), 0); true }.getOrDefault(false)

    fun isEnabled(context: Context): Boolean = runCatching {
        val resolver = context.contentResolver
        Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1 &&
            enabledServices(context).any { ComponentName.unflattenFromString(it) == component(context) }
    }.getOrDefault(false)

    /** Granted once with adb; lets the app turn its service on and off, and set the box's sleep. */
    fun canWriteSecureSettings(context: Context): Boolean =
        context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Adds the service to those already on (never replaces them). */
    fun enable(context: Context): Boolean = runCatching {
        val list = enabledServices(context)
        if (list.none { ComponentName.unflattenFromString(it) == component(context) }) {
            Settings.Secure.putString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                (list + component(context).flattenToString()).joinToString(":"),
            )
        }
        Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        true
    }.getOrDefault(false)

    fun disable(context: Context): Boolean = runCatching {
        val list = enabledServices(context).filterNot { ComponentName.unflattenFromString(it) == component(context) }
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list.joinToString(":"))
        if (list.isEmpty()) Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        true
    }.getOrDefault(false)

    /** Fire TV's sleep after 20 minutes without a key press: off (the display keeps the screen on while in front). */
    fun fireTvSleepMillis(context: Context): Long? =
        runCatching { Settings.Secure.getLong(context.contentResolver, FIRE_TV_SLEEP) }.getOrNull()

    fun disableFireTvSleep(context: Context): Boolean =
        runCatching { Settings.Secure.putLong(context.contentResolver, FIRE_TV_SLEEP, 0L) }.getOrDefault(false)

    private fun enabledServices(context: Context): List<String> =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':').filter { it.isNotBlank() }

    /** Fire OS's own secure setting for its sleep timer. */
    const val FIRE_TV_SLEEP = "sleep_timeout"

    /** The one adb session an admin runs from a computer on the same network (ADB debugging on). */
    fun adbSetup(packageName: String): List<String> = listOf(
        "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS",
        "adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow",
    )
}

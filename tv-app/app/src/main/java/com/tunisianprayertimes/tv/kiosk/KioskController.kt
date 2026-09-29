package com.tunisianprayertimes.tv.kiosk

import android.app.Activity
import android.app.ActivityOptions
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.tunisianprayertimes.time.ClockGuard
import com.tunisianprayertimes.time.ClockSource
import com.tunisianprayertimes.time.ClockTrust
import com.tunisianprayertimes.time.TunisTime
import com.tunisianprayertimes.tv.MainActivity
import java.time.Duration
import java.time.Instant
import java.util.TimeZone
import kotlin.system.exitProcess

/** What the box allows and the actions that keep the app on screen. Every call is safe on any box. */
object KioskController {

    private const val TAG = "Kiosk"
    const val WATCHDOG_PERIOD_MILLIS = 5 * 60_000L
    const val AUTOSTART_CHECK_DELAY_MILLIS = 90_000L
    const val ACTION_WATCHDOG = "com.tunisianprayertimes.tv.kiosk.WATCHDOG"
    const val ACTION_AUTOSTART_CHECK = "com.tunisianprayertimes.tv.kiosk.AUTOSTART_CHECK"
    const val ACTION_BOOT_REFRONT = "com.tunisianprayertimes.tv.kiosk.BOOT_REFRONT"

    /** After boot, the box's own home screen (or a profile picker) may land on top of the display: looked at again then. */
    val BOOT_REFRONT_DELAYS_MILLIS = listOf(10_000L, 30_000L, 60_000L)

    /** The disabled launcher alias the admin can turn on to make the app the home screen. */
    private const val HOME_ALIAS = "com.tunisianprayertimes.tv.KioskHomeAlias"

    fun autoStart(context: Context): AutoStart = AutoStartTierResolver.resolve(
        isDefaultHome = isDefaultHome(context),
        isDeviceOwner = isDeviceOwner(context),
        accessibilityEnabled = KioskAccessibility.isEnabled(context),
        canDrawOverlays = canDrawOverlays(context),
        sdkInt = Build.VERSION.SDK_INT,
        isFireTv = isFireTv(context),
    )

    /** Amazon's documented checks: the Fire TV feature, or a model name starting with "AFT". */
    fun isFireTv(context: Context): Boolean =
        runCatching { context.packageManager.hasSystemFeature("amazon.hardware.fire_tv") }.getOrDefault(false) ||
            Build.MODEL.orEmpty().startsWith("AFT")

    fun isDefaultHome(context: Context): Boolean = runCatching {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == context.packageName
    }.getOrDefault(false)

    fun isDeviceOwner(context: Context): Boolean = runCatching {
        context.getSystemService(DevicePolicyManager::class.java)?.isDeviceOwnerApp(context.packageName) == true
    }.getOrDefault(false)

    fun canDrawOverlays(context: Context): Boolean = runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

    fun isHomeModeEnabled(context: Context): Boolean = runCatching {
        context.packageManager.getComponentEnabledSetting(homeAlias(context)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }.getOrDefault(false)

    /**
     * Offers the app as a home screen (or withdraws it), then opens the system's home chooser so
     * the admin picks it. Off by default: on some Google TV devices a third-party home is refused.
     */
    fun setHomeMode(context: Context, enabled: Boolean, log: EventLog) {
        runCatching {
            context.packageManager.setComponentEnabledSetting(
                homeAlias(context),
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
            log.append(if (enabled) KioskEvent.HOME_MODE_ON else KioskEvent.HOME_MODE_OFF)
        }.onFailure { Log.w(TAG, "home mode", it) }
        val chooser = listOf(
            Intent(Settings.ACTION_HOME_SETTINGS),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
        ).firstOrNull { it.resolveActivity(context.packageManager) != null }
        chooser?.let { start(context, it) }
    }

    /** The system page to allow "display over other apps", or null where the box has none (use adb). */
    fun overlaySettingsIntent(context: Context): Intent? =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            .takeIf { it.resolveActivity(context.packageManager) != null }

    fun power(context: Context): PowerStatus = PowerSettingsProbe.probe(object : PowerSettingsReader {
        override fun attentiveTimeoutMillis(): Long? =
            runCatching { Settings.Secure.getLong(context.contentResolver, "attentive_timeout") }.getOrNull()

        override fun stayOnWhilePluggedIn(): Int? =
            runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.STAY_ON_WHILE_PLUGGED_IN) }.getOrNull()
    })

    /** Pins the app with lock task when an admin provisioned the box as device owner (adb dpm). */
    fun lockTaskIfDeviceOwner(activity: Activity) {
        if (!isDeviceOwner(activity)) return
        runCatching {
            val dpm = activity.getSystemService(DevicePolicyManager::class.java)
            dpm.setLockTaskPackages(ComponentName(activity, TvDeviceAdminReceiver::class.java), arrayOf(activity.packageName))
            activity.startLockTask()
        }.onFailure { Log.w(TAG, "lock task", it) }
    }

    fun unlockTask(activity: Activity) {
        runCatching { activity.stopLockTask() }
    }

    // The system zone and clock, on device-owner boxes (Android 9+). Prayer times never need them: the
    // clock guard keeps Tunisia's time whatever the box says. But a wrong system clock fails the HTTPS
    // checks (weather, updates, official dates) and leaves the box's own screens and logs off.

    /**
     * Sets the box's zone to Tunisia's. Only once the time is confirmed ([alignSystemClock]): a clock set
     * by hand in a foreign zone is off by the zones' difference, and in Tunisia's zone the guard would take
     * it as right ([ClockSource.ZONE]). Android refuses while the automatic zone is on, so it is turned off
     * first (a mosque box does not travel), and back on if the zone is refused anyway. True when the zone
     * is Tunisia's (already, or now); false when not device owner, below Android 9, or refused.
     */
    fun setSystemZoneToTunis(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !isDeviceOwner(context)) return false
        if (TimeZone.getDefault().id == TunisTime.ZONE.id) return true
        return runCatching {
            val dpm = context.getSystemService(DevicePolicyManager::class.java)
            val admin = adminComponent(context)
            val autoWasOn = globalSetting(context, Settings.Global.AUTO_TIME_ZONE) == 1
            if (autoWasOn) setAutoZone(dpm, admin, false)
            val done = dpm.setTimeZone(admin, TunisTime.ZONE.id)
            if (!done && autoWasOn) setAutoZone(dpm, admin, true)
            done
        }.getOrElse {
            Log.w(TAG, "time zone", it)
            false
        }
    }

    /**
     * Sets the system clock to [instant], a confirmed time ([alignSystemClock]); the time-set broadcast
     * that follows is recognised as the app's own ([OwnClockSet]). False when not device owner, below
     * Android 9, while the automatic time is on (the network keeps the system clock then, and stays in
     * charge), or refused.
     */
    fun setSystemTime(context: Context, instant: Instant): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !isDeviceOwner(context)) return false
        if (globalSetting(context, Settings.Global.AUTO_TIME) == 1) return false
        return runCatching {
            OwnClockSet.expect(instant.toEpochMilli(), SystemClock.elapsedRealtime())
            context.getSystemService(DevicePolicyManager::class.java).setTime(adminComponent(context), instant.toEpochMilli())
        }.getOrElse {
            Log.w(TAG, "system time", it)
            false
        }.also { if (!it) OwnClockSet.cancel() }
    }

    /**
     * After the time was confirmed (by the network, the admin or the phone), on a device-owner box: the
     * system zone becomes Tunisia's and, when it is more than a minute off, the system clock becomes the
     * time shown. The guard is then confirmed again by the same source, since the clock it reads just
     * jumped. Call it on the main thread, where the guard is read. False when nothing changed (not device
     * owner, below Android 9, the time not confirmed, already aligned, or refused).
     */
    fun alignSystemClock(context: Context, guard: ClockGuard): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !isDeviceOwner(context)) return false
        val reading = guard.read()
        // The guard's correction: the time shown less the device clock.
        val offset = Duration.between(Instant.now(), reading.now.atZone(TunisTime.ZONE).toInstant())
        val source = reading.source
        if (reading.trust != ClockTrust.TRUSTED || source == null) return false
        val zoneWasTunis = TimeZone.getDefault().id == TunisTime.ZONE.id
        val zoneChanged = setSystemZoneToTunis(context) && !zoneWasTunis
        // Trusted for its zone: the device clock itself is the time shown, there is nothing to move.
        if (source == ClockSource.ZONE || offset.abs() <= SYSTEM_CLOCK_TOLERANCE) return zoneChanged
        if (!setSystemTime(context, Instant.now().plus(offset))) return zoneChanged
        guard.systemClockChanged()
        guard.acceptInstant(Instant.now(), source)
        return true
    }

    /** The system clock this close to the time shown is left alone: it only serves HTTPS and the logs. */
    private val SYSTEM_CLOCK_TOLERANCE: Duration = Duration.ofMinutes(1)

    private fun adminComponent(context: Context) = ComponentName(context, TvDeviceAdminReceiver::class.java)

    private fun globalSetting(context: Context, name: String): Int? =
        runCatching { Settings.Global.getInt(context.contentResolver, name) }.getOrNull()

    private fun setAutoZone(dpm: DevicePolicyManager, admin: ComponentName, enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            dpm.setAutoTimeZoneEnabled(admin, enabled)
        } else {
            @Suppress("DEPRECATION")
            dpm.setGlobalSetting(admin, Settings.Global.AUTO_TIME_ZONE, if (enabled) "1" else "0")
        }
    }

    /** Brings the display to the front from the background; the box may refuse (tier NONE). */
    fun bringToFront(context: Context): Boolean = start(
        context,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
    )

    /** Starts the display again in [delayMillis], after this process dies (crash, restart). */
    fun scheduleRestart(context: Context, delayMillis: Long) {
        runCatching {
            val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            val pending = PendingIntent.getActivity(
                context, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT, backgroundStartOptions(),
            )
            alarms(context)?.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + delayMillis, pending)
        }.onFailure { Log.w(TAG, "restart alarm", it) }
    }

    /** A new process in the foreground, right now (the nightly maintenance restart). */
    fun relaunch(activity: Activity): Nothing {
        activity.startActivity(Intent.makeRestartActivityTask(ComponentName(activity, MainActivity::class.java)))
        exitProcess(0)
    }

    /** The repeating check that brings the app back after Home or another app took the screen. */
    fun armWatchdog(context: Context) {
        runCatching {
            alarms(context)?.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + WATCHDOG_PERIOD_MILLIS,
                WATCHDOG_PERIOD_MILLIS,
                broadcast(context, ACTION_WATCHDOG),
            )
        }.onFailure { Log.w(TAG, "watchdog alarm", it) }
    }

    /** A few looks after boot: the display goes back on top if the box's home screen covered it. */
    fun scheduleBootRefronts(context: Context) {
        runCatching {
            val alarms = alarms(context) ?: return
            BOOT_REFRONT_DELAYS_MILLIS.forEachIndexed { index, delay ->
                val pending = PendingIntent.getBroadcast(
                    context, ACTION_BOOT_REFRONT.hashCode() + index,
                    Intent(context, KioskAlarmReceiver::class.java).setAction(ACTION_BOOT_REFRONT),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                alarms.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + delay, pending)
            }
        }.onFailure { Log.w(TAG, "boot refront alarms", it) }
    }

    /** After boot: did the display actually reach the screen? */
    fun scheduleAutoStartCheck(context: Context) {
        runCatching {
            alarms(context)?.set(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + AUTOSTART_CHECK_DELAY_MILLIS,
                broadcast(context, ACTION_AUTOSTART_CHECK),
            )
        }.onFailure { Log.w(TAG, "auto-start check alarm", it) }
    }

    private fun broadcast(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
        context, action.hashCode(), Intent(context, KioskAlarmReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Android 14+ lets a pending activity start from the background only if its creator opts in. */
    private fun backgroundStartOptions(): Bundle? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ActivityOptions.makeBasic()
                .setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle()
        } else {
            null
        }

    private fun alarms(context: Context): AlarmManager? = context.getSystemService(AlarmManager::class.java)

    private fun homeAlias(context: Context) = ComponentName(context.packageName, HOME_ALIAS)

    private fun start(context: Context, intent: Intent): Boolean = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrElse {
        Log.w(TAG, "start ${intent.action ?: intent.component}", it)
        false
    }
}

package com.tunisianprayertimes.tv.kiosk

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import com.tunisianprayertimes.tv.MainActivity
import com.tunisianprayertimes.tv.data.PrefsManager

/**
 * The quick-start service (GitHub build only; see [KioskAccessibility]). Android binds an enabled
 * accessibility service as soon as the box is up, before the boot broadcast reaches apps, and lets
 * its app start activities from the background. So on a Fire TV, where the app can't be the home
 * screen, the display appears right after boot, and comes back when Fire TV shows its own home again
 * (after standby, or Home). It only reads which app is in front, never the screen's content.
 */
class KioskAccessibilityService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private val limiter = RefrontLimiter()
    private lateinit var store: KioskStore
    private var homePackages: Set<String> = emptySet()
    private var lastPackage: String? = null
    @Volatile private var screenOnAt: Long? = null

    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenOnAt = SystemClock.elapsedRealtime()
            // Fire TV wakes to its own home: the display goes back on top shortly after.
            main.postDelayed({ if (!MainActivity.inFront) refrontIfWanted("wake") }, WAKE_DELAY_MILLIS)
        }
    }

    /** A minute after Home: only if the home screen is still what was last in front. */
    private val graceRefront = Runnable { if (lastPackage in homePackages) refrontIfWanted("home") }

    override fun onServiceConnected() {
        bound = true
        KioskAccessibility.serviceConnected = true
        val now = SystemClock.elapsedRealtime()
        store = KioskStore(this)
        homePackages = resolveHomePackages()
        Log.i(TAG, "connected; home screens $homePackages")
        store.quickStartWanted = true
        ContextCompat.registerReceiver(this, screenOn, IntentFilter(Intent.ACTION_SCREEN_ON), ContextCompat.RECEIVER_NOT_EXPORTED)
        BootTiming.mark(this, BootTiming.SERVICE, now)
        if (now < SCREENS_WINDOW_MILLIS) main.postDelayed({ if (!screensLogged) logScreens() }, SCREENS_WINDOW_MILLIS - now)
        val state = store.state
        val setupDone = PrefsManager(this).isSetupDone
        val interactive = runCatching { getSystemService(PowerManager::class.java).isInteractive }.getOrDefault(true)
        when {
            LauncherWatchPolicy.launchOnConnect(now, setupDone, MainActivity.inFront, state.resumedAt != null, state.adminAwayUntil) ->
                refront("boot")
            // Bound again after the app updated itself: the update's own start may have come too early.
            store.packageReplacedAt?.let { now - it < UPDATE_WINDOW_MILLIS } == true && setupDone && !MainActivity.inFront ->
                refront("update")
            // Bound again in a new process after the old one died on screen.
            LauncherWatchPolicy.refrontOnReconnect(now, state, MainActivity.inFront, setupDone, interactive,
                CrashLoopGuard().isGivingUp(store.crashes, now)) ->
                refront("restart")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // Which screens a box shows (Amazon's names are not documented): readable with adb logcat -s QuickStart,
        // and once per boot in the kiosk log.
        Log.d(TAG, "window $pkg ${event.className}")
        noteScreen(pkg, event.className?.toString())
        if (pkg in NEUTRAL_WINDOW_PACKAGES) return
        lastPackage = pkg
        if (pkg !in homePackages) {
            main.removeCallbacks(graceRefront) // the admin walked on (to settings, another app) or the display is back
            return
        }
        val state = store.state
        when (LauncherWatchPolicy.onLauncherFront(SystemClock.elapsedRealtime(), screenOnAt, PrefsManager(this).isSetupDone, state.adminAwayUntil)) {
            LauncherAction.NOW -> if (!refront("home ${event.className}")) {
                // Right after our own start (the wake timer, the boot start) the home screen came back: try again once
                // the gap between starts has passed, if it is still in front then.
                val wait = limiter.waitMillis(SystemClock.elapsedRealtime())
                if (wait > 0) {
                    main.removeCallbacks(graceRefront)
                    main.postDelayed(graceRefront, wait)
                }
            }
            LauncherAction.AFTER_GRACE -> {
                main.removeCallbacks(graceRefront)
                main.postDelayed(graceRefront, LauncherWatchPolicy.GRACE_MILLIS)
            }
            LauncherAction.IGNORE -> Unit
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        // Switched off outside the app (the system's accessibility page, adb) while the app runs: respected, not turned
        // back on. A force stop kills the process before any unbind, and an update keeps the service on, so the app's
        // own repair after those still works.
        if (bound && !KioskAccessibility.isEnabled(this)) store.quickStartWanted = false
        cleanUp()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanUp()
        super.onDestroy()
    }

    private fun cleanUp() {
        if (!bound) return
        bound = false
        KioskAccessibility.serviceConnected = false
        main.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(screenOn) }
    }

    /** From the wake and grace timers: only when nothing changed meanwhile (setup, admin away). */
    private fun refrontIfWanted(reason: String) {
        val state = store.state
        val now = SystemClock.elapsedRealtime()
        if (!PrefsManager(this).isSetupDone) return
        if (state.adminAwayUntil?.let { now < it } == true) return
        refront(reason)
    }

    /** False when the limiter held the start back. */
    private fun refront(reason: String): Boolean {
        if (!limiter.tryAcquire(SystemClock.elapsedRealtime())) {
            Log.w(TAG, "not starting ($reason) now: a start was just made, or too many lately")
            return false
        }
        val started = KioskController.bringToFront(this)
        Log.i(TAG, "start ($reason) started=$started")
        store.eventLog.append(KioskEvent.WATCHDOG_REFRONT, "quick-start $reason started=$started")
        return true
    }

    /**
     * The screens other than the display seen in the first two minutes after boot, written once to the kiosk
     * log: on a real Fire TV this names the profile picker or the remote-pairing screen if one covers the display.
     */
    private fun noteScreen(pkg: String, className: String?) {
        val now = SystemClock.elapsedRealtime()
        if (screensLogged || now > SCREENS_WINDOW_MILLIS) {
            if (!screensLogged && seenScreens.isNotEmpty()) logScreens()
            return
        }
        if (pkg != packageName && seenScreens.size < MAX_SCREENS) seenScreens += "$pkg/${className.orEmpty().substringAfterLast('.')}"
    }

    private fun logScreens() {
        screensLogged = true
        store.eventLog.append(KioskEvent.QUICK_START, "homes=$homePackages seen=$seenScreens")
    }

    private val seenScreens = linkedSetOf<String>()
    private var screensLogged = false
    private var bound = false

    /** The box's home screens: Amazon's, and whatever answers HOME here (the emulator's launcher, a TV's). */
    private fun resolveHomePackages(): Set<String> {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val candidates = runCatching { packageManager.queryIntentActivities(home, 0).map { it.activityInfo.packageName } }.getOrDefault(emptyList())
        val default = runCatching { packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName }.getOrNull()
        return LauncherPackages.of(default, candidates, packageName)
    }

    companion object {
        private const val TAG = "QuickStart"
        private const val WAKE_DELAY_MILLIS = 700L
        private const val UPDATE_WINDOW_MILLIS = 60_000L
        private const val SCREENS_WINDOW_MILLIS = 2 * 60_000L
        private const val MAX_SCREENS = 10
    }
}

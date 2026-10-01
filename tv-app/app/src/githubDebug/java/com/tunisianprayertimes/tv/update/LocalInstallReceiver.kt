package com.tunisianprayertimes.tv.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.tunisianprayertimes.tv.kiosk.KioskStore
import java.io.File
import kotlin.concurrent.thread

/**
 * Debug builds only: installs an APK already placed in the app's files/updates folder, to test the
 * self-update path on an emulator without publishing a release:
 * adb shell am broadcast -n <package>/com.tunisianprayertimes.tv.update.LocalInstallReceiver --es file test.apk
 */
class LocalInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.getStringExtra("file")?.takeIf { it.matches(Regex("""[A-Za-z0-9._-]+\.apk""")) } ?: return
        val pending = goAsync()
        thread {
            try {
                val file = File(File(context.filesDir, "updates"), name)
                val result = GithubUpdater(context, KioskStore(context).eventLog).installLocal(file)
                Log.i("Updates", "local install: $result")
            } finally {
                pending.finish()
            }
        }
    }
}

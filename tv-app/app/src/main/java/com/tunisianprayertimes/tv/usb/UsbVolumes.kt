package com.tunisianprayertimes.tv.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Environment
import android.os.storage.StorageManager
import androidx.core.content.ContextCompat

/** Mounted USB keys and SD cards, and notifications when one is plugged in. */
object UsbVolumes {

    /**
     * Every mounted removable volume the app can use. Android only lists a volume here when the box
     * marks its port as usable by apps, so some boxes hide plain USB keys (see [hiddenCount]).
     * Creates the app folder on each volume: a disk access, so call it off the main thread.
     */
    fun mounted(context: Context): List<RemovableVolume> =
        context.getExternalFilesDirs(null).filterNotNull().filter { dir ->
            runCatching {
                Environment.isExternalStorageRemovable(dir) && Environment.getExternalStorageState(dir) == Environment.MEDIA_MOUNTED
            }.getOrDefault(false)
        }.map(::RemovableVolume)

    /** Removable volumes that are mounted but that Android does not let this app reach. */
    fun hiddenCount(context: Context, visible: Int): Int = runCatching {
        val storage = context.getSystemService(StorageManager::class.java)
        val mounted = storage.storageVolumes.count { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }
        (mounted - visible).coerceAtLeast(0)
    }.getOrDefault(0)

    /** Calls [onMounted] whenever a volume is mounted; returns the function that stops listening. */
    fun onMounted(context: Context, onMounted: () -> Unit): () -> Unit {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = onMounted()
        }
        val filter = IntentFilter(Intent.ACTION_MEDIA_MOUNTED).apply { addDataScheme("file") }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        return { runCatching { context.unregisterReceiver(receiver) } }
    }
}

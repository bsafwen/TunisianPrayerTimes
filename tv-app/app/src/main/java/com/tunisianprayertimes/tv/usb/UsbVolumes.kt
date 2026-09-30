package com.tunisianprayertimes.tv.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Environment
import android.os.storage.StorageManager
import androidx.core.content.ContextCompat
import java.io.File

/** Mounted USB keys and SD cards, and notifications when one is plugged in or taken out. */
object UsbVolumes {

    /**
     * Every mounted removable volume the app can use. Android only lists a volume here when the box
     * marks its port as usable by apps, so some boxes hide plain USB keys (see [hidden]). A key
     * mounted read-only (NTFS on many boxes, a FAT key with its dirty bit set) is read but never written;
     * one that does not have the app folder yet is not listed, since Android cannot make it there.
     * Creates the app folder on each volume: a disk access, so call it off the main thread.
     */
    fun mounted(context: Context): List<RemovableVolume> =
        context.getExternalFilesDirs(null).filterNotNull().mapNotNull { dir ->
            val state = runCatching {
                if (Environment.isExternalStorageRemovable(dir)) Environment.getExternalStorageState(dir) else null
            }.getOrNull()
            when (state) {
                Environment.MEDIA_MOUNTED -> RemovableVolume(dir)
                Environment.MEDIA_MOUNTED_READ_ONLY -> RemovableVolume(dir, readOnly = true)
                else -> null
            }
        }

    /**
     * Removable volumes that are mounted but missing from the [visible] ones, by their state: the
     * read-only ones lack the app folder, the others are kept from apps by the box.
     */
    fun hidden(context: Context, visible: List<RemovableVolume>): HiddenVolumes = runCatching {
        val states = context.getSystemService(StorageManager::class.java).storageVolumes.filter { it.isRemovable }.map { it.state }
        HiddenVolumes(
            readOnly = (states.count { it == Environment.MEDIA_MOUNTED_READ_ONLY } - visible.count { it.readOnly }).coerceAtLeast(0),
            keptFromApps = (states.count { it == Environment.MEDIA_MOUNTED } - visible.count { !it.readOnly }).coerceAtLeast(0),
        )
    }.getOrDefault(HiddenVolumes())

    /**
     * Calls [onMounted] with the volume's mount point (null when the box does not say) whenever a
     * volume is mounted; returns the function that stops listening.
     */
    fun onMounted(context: Context, onMounted: (String?) -> Unit): () -> Unit =
        listen(context, Intent.ACTION_MEDIA_MOUNTED) { onMounted(it.data?.path) }

    /** Calls [onGone] whenever a volume is unmounted or pulled out; returns the function that stops listening. */
    fun onGone(context: Context, onGone: () -> Unit): () -> Unit =
        listen(context, Intent.ACTION_MEDIA_UNMOUNTED, Intent.ACTION_MEDIA_REMOVED, Intent.ACTION_MEDIA_BAD_REMOVAL) { onGone() }

    private fun listen(context: Context, vararg actions: String, handle: (Intent) -> Unit): () -> Unit {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = handle(intent)
        }
        val filter = IntentFilter().apply {
            actions.forEach(::addAction)
            addDataScheme("file")
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        return { runCatching { context.unregisterReceiver(receiver) } }
    }

    /**
     * True when [volume] is the one mounted at [mountPath]. By the volume's own folder name (its id, such
     * as 1A2B-3C4D), since boxes report /storage/1A2B-3C4D or /mnt/media_rw/1A2B-3C4D for the same key.
     */
    fun isAt(volume: RemovableVolume, mountPath: String): Boolean {
        val name = File(mountPath).name
        return name.isNotEmpty() && name in volume.appFolder.path.split('/', '\\')
    }
}

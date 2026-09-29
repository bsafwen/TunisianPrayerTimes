package com.tunisianprayertimes.tv.data

import android.content.Context
import android.net.Uri
import java.io.File

/** An announcement shown in the slideshow after the after-salah adhkar. */
sealed class Announcement {
    data class Image(val uri: Uri) : Announcement()
    data class Text(val title: String, val content: String) : Announcement()
}

/** The two kinds of images a mosque puts on the screen, each in its own folder (on the USB key and on the TV). */
enum class MediaKind(val folder: String) { BACKGROUNDS("backgrounds"), ANNOUNCEMENTS("announcements") }

/**
 * The mosque's background and announcement images, kept in the app's own storage: copied there
 * from a USB key, so they stay after the key is removed and no storage permission is needed.
 */
class LocalMediaManager(private val root: File) {

    constructor(context: Context) : this(File(context.applicationContext.filesDir, "media"))

    fun images(kind: MediaKind): List<File> =
        File(root, kind.folder).listFiles().orEmpty()
            .filter { it.isFile && it.extension.lowercase() in IMAGE_EXTENSIONS }
            .sortedBy { it.name.lowercase() }

    fun getBackgroundImages(): List<Uri> = images(MediaKind.BACKGROUNDS).map(Uri::fromFile)

    fun getImageAnnouncements(): List<Announcement> = images(MediaKind.ANNOUNCEMENTS).map { Announcement.Image(Uri.fromFile(it)) }

    /**
     * Replaces the [kind] images with copies of [sources]. The copies are made in a separate folder
     * first, so a key pulled out halfway leaves the previous images in place. Returns how many were copied.
     */
    fun replace(kind: MediaKind, sources: List<File>): Int {
        val target = File(root, kind.folder)
        val staging = File(root, "${kind.folder}.new")
        staging.deleteRecursively()
        check(staging.mkdirs() || staging.isDirectory) { "cannot create $staging" }
        sources.forEach { source -> source.copyTo(File(staging, source.name), overwrite = true) }
        val old = File(root, "${kind.folder}.old")
        old.deleteRecursively()
        if (target.exists()) check(target.renameTo(old)) { "cannot move $target" }
        check(staging.renameTo(target)) { "cannot move $staging" }
        old.deleteRecursively()
        return sources.size
    }

    fun clear(kind: MediaKind) {
        File(root, kind.folder).deleteRecursively()
    }

    companion object {
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    }
}

package com.tunisianprayertimes.tv.data

import android.content.Context
import android.net.Uri
import com.tunisianprayertimes.mosque.TextAnnouncement
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.LocalDate

/** An announcement shown in the slideshow after the after-salah adhkar. */
sealed class Announcement {
    data class Image(val uri: Uri) : Announcement()

    /**
     * A written announcement. [until] is its last day, said under it («إلى 31 أكتوبر 2026»): only the
     * settings file's announcements have one, a .txt file has none.
     */
    data class Text(val title: String, val content: String, val until: LocalDate? = null) : Announcement() {
        companion object {
            /** One of the settings file's announcements, with its end date. */
            fun of(announcement: TextAnnouncement): Text = Text(title = "", content = announcement.text, until = announcement.until)
        }
    }
}

/** A written announcement that came as a .txt file. */
data class TextFile(val name: String, val text: String)

/** The two kinds of images a mosque puts on the screen, each in its own folder (on the USB key and on the TV). */
enum class MediaKind(val folder: String) { BACKGROUNDS("backgrounds"), ANNOUNCEMENTS("announcements") }

/**
 * The mosque's background and announcement images (and announcement .txt files), kept in the app's
 * own storage: copied there from a USB key or uploaded from the dashboard, so they stay after the key
 * is removed and no storage permission is needed. [storeImage] stores an image from a key ([ScreenImage]
 * on the TV, byte for byte otherwise): it throws when the key cannot be read, and says false when the
 * file is not an image Android can decode. [freeSpace] is the room left on the TV's storage.
 */
class LocalMediaManager(
    private val root: File,
    private val storeImage: (source: File, target: File) -> Boolean = { source, target -> copySynced(source, target); true },
    private val freeSpace: (File) -> Long = File::getUsableSpace,
) {

    constructor(context: Context) : this(File(context.applicationContext.filesDir, "media"), ScreenImage::store)

    fun images(kind: MediaKind): List<File> =
        File(root, kind.folder).listFiles().orEmpty()
            .filter { it.isFile && it.extension.lowercase() in IMAGE_EXTENSIONS }
            .sortedBy { it.name.lowercase() }

    fun getBackgroundImages(): List<Uri> = images(MediaKind.BACKGROUNDS).map(Uri::fromFile)

    fun getImageAnnouncements(): List<Announcement> = images(MediaKind.ANNOUNCEMENTS).map { Announcement.Image(Uri.fromFile(it)) }

    /** The announcement .txt files, in file-name order, each with its text on one line; unreadable files are left out. */
    fun textFiles(): List<TextFile> =
        File(root, MediaKind.ANNOUNCEMENTS.folder).listFiles().orEmpty()
            .filter { it.isFile && it.extension.lowercase() == TEXT_EXTENSION }
            .sortedBy { it.name.lowercase() }
            .mapNotNull { file ->
                runCatching { AnnouncementText.fromBytes(file.readBytes()) }.getOrNull()?.let { TextFile(file.name, it) }
            }

    fun textFileAnnouncements(): List<String> = textFiles().map { it.text }

    /**
     * Stores one image (from the dashboard) through a temporary file, synced before it is renamed, so a
     * failed upload or a power cut leaves nothing half written.
     */
    fun add(kind: MediaKind, name: String, bytes: ByteArray) {
        val folder = File(root, kind.folder)
        check(folder.mkdirs() || folder.isDirectory) { "cannot create $folder" }
        val temp = File(folder, ".$name.part")
        FileOutputStream(temp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        val target = File(folder, name)
        check(temp.renameTo(target) || (target.delete() && temp.renameTo(target))) { "cannot store $name" }
    }

    fun delete(kind: MediaKind, name: String): Boolean = File(File(root, kind.folder), name).let { it.isFile && it.delete() }

    /**
     * The TV's [kind] files that a copy of [sources] replaces: the images when they bring images, the
     * .txt announcements when they bring texts. A key with only a .txt file never takes the images away.
     */
    fun replacedBy(kind: MediaKind, sources: List<File>): List<File> {
        val brought = sources.map(::isText).toSet()
        return File(root, kind.folder).listFiles().orEmpty().filter { it.isFile && isMedia(it) && isText(it) in brought }
    }

    /**
     * Replaces the [kind] files of the types [sources] bring ([replacedBy]) with copies of them, the
     * images through [storeImage]; an image it cannot read is left out. The copies are made and synced
     * in a separate folder first, so a key pulled out halfway leaves the previous files in place and
     * nothing behind; a power cut after the copy never leaves empty images. Throws [NoRoom] when the
     * TV's storage cannot take the next file, and nothing changes. Returns how many were stored.
     */
    fun replace(kind: MediaKind, sources: List<File>): Int {
        val target = File(root, kind.folder)
        val staging = File(root, "${kind.folder}.new")
        staging.deleteRecursively()
        try {
            check(staging.mkdirs() || staging.isDirectory) { "cannot create $staging" }
            sources.forEach { source ->
                // Room for this file as it is on the key (a photo is shrunk only once copied), and some to
                // spare: settings and clock corrections are saved on the same storage.
                if (freeSpace(staging) < source.length() + RESERVE_BYTES) throw NoRoom()
                val copy = File(staging, source.name)
                if (isText(source)) copySynced(source, copy) else if (!storeImage(source, copy)) copy.delete()
            }
            val staged = staging.listFiles().orEmpty().toList()
            check(target.mkdirs() || target.isDirectory) { "cannot create $target" }
            // What was stored decides: images that could not be read never take the TV's own away.
            replacedBy(kind, staged).forEach { it.delete() }
            staged.forEach { check(it.renameTo(File(target, it.name))) { "cannot move $it" } }
            return staged.size
        } finally {
            staging.deleteRecursively()
        }
    }

    fun clear(kind: MediaKind) {
        File(root, kind.folder).deleteRecursively()
    }

    /** Every image and .txt file, and any copy left half done: a TV moved to another mosque starts empty. */
    fun clearAll() {
        root.deleteRecursively()
    }

    /** The TV's storage cannot take the files: the admin needs to hear it, not that the key failed. */
    class NoRoom : IOException("not enough room for the images")

    companion object {
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
        const val TEXT_EXTENSION = "txt"

        /** Left free after a copy. */
        const val RESERVE_BYTES = 50L * 1024 * 1024

        /** Copies [source] to [target] and syncs it, so a power cut right after never leaves it empty. */
        fun copySynced(source: File, target: File) {
            source.inputStream().use { input ->
                FileOutputStream(target).use { out ->
                    input.copyTo(out)
                    out.fd.sync()
                }
            }
        }

        private fun isText(file: File) = file.extension.lowercase() == TEXT_EXTENSION

        private fun isMedia(file: File) = isText(file) || file.extension.lowercase() in IMAGE_EXTENSIONS
    }
}

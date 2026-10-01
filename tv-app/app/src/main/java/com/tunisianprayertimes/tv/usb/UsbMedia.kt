package com.tunisianprayertimes.tv.usb

import com.tunisianprayertimes.tv.data.AnnouncementText
import com.tunisianprayertimes.tv.data.LocalMediaManager
import com.tunisianprayertimes.tv.data.MediaKind
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** A file of a key that is not copied, and why, so the admin is told instead of left guessing. */
data class RejectedFile(val file: File, val reason: Reason) {
    enum class Reason {
        /** Not a JPEG, PNG or WebP image by its content: HEIC or GIF from a phone, BMP, a renamed file. */
        FORMAT,
        /** Larger than [UsbMedia.MAX_BYTES]. */
        TOO_LARGE,
        /** Past the first [UsbMedia.MAX_FILES] of its kind. */
        TOO_MANY,
        /** A .txt file larger than [UsbMedia.MAX_TEXT_BYTES], or not readable text. */
        TEXT,
        /** Next to the settings file, or a .txt among the backgrounds, instead of in its folder. */
        MISPLACED,
    }
}

/**
 * Images (and announcement .txt files) found on a key, per kind, and the files left out ([rejected]);
 * [signature] identifies them so the same set is offered once. [replaced] is how many of the TV's own
 * files each kind's copy removes, for the admin to see before copying.
 */
data class UsbMediaFound(
    val images: Map<MediaKind, List<File>>,
    val signature: String,
    val rejected: List<RejectedFile> = emptyList(),
    val replaced: Map<MediaKind, Int> = emptyMap(),
) {
    /** Nothing to copy: only files left out. */
    val isEmpty: Boolean get() = images.values.all { it.isEmpty() }
}

/** How a copy from a key ended. */
sealed interface UsbMediaCopy {
    /** [files] stored; [unreadable] images that Android could not decode were left out. */
    data class Done(val files: Int, val unreadable: Int = 0) : UsbMediaCopy

    /** A file could not be read (a key pulled out, a bad sector): what was not copied stays as it was. */
    data object Failed : UsbMediaCopy

    /** The TV has not the room: nothing of that kind was copied. */
    data object NoRoom : UsbMediaCopy
}

/**
 * Background and announcement images on a USB key: the "backgrounds" and "announcements" folders
 * next to the settings file. Only real JPEG, PNG or WebP files of a sensible size are taken, and in
 * "announcements" also .txt files, each one a written announcement. Other images and misplaced
 * files are reported; files that are not media at all (Thumbs.db, a hidden ._photo.jpg) are ignored.
 */
object UsbMedia {

    const val MAX_FILES = 20
    const val MAX_BYTES = 15L * 1024 * 1024
    const val MAX_TEXT_BYTES = 4L * 1024

    /** Creates the empty folders on a key, so the admin sees where the images go. */
    fun ensureFolders(volume: RemovableVolume) {
        if (volume.readOnly) return
        MediaKind.entries.forEach { runCatching { File(volume.appFolder, it.folder).mkdirs() } }
    }

    /** The media files on [volume], or null when it has none, usable or not. Never throws. */
    fun find(volume: RemovableVolume): UsbMediaFound? = runCatching {
        val rejected = mutableListOf<RejectedFile>()
        val images = MediaKind.entries.associateWith { kind ->
            filesIn(File(volume.appFolder, kind.folder), texts = kind == MediaKind.ANNOUNCEMENTS, rejected)
        }
        // Where admins often drop them: next to mosque-tv.json.
        rejected += listed(volume.appFolder).filter(::isMedia).map { RejectedFile(it, RejectedFile.Reason.MISPLACED) }
        UsbMediaFound(images, signature(images, rejected), rejected).takeUnless { it.isEmpty && rejected.isEmpty() }
    }.getOrNull()

    /** False once [found]'s key is taken out: none of its files is there any more. A disk access. */
    fun stillThere(found: UsbMediaFound): Boolean =
        (found.images.values.flatten() + found.rejected.map { it.file }).any { it.isFile }

    private fun filesIn(folder: File, texts: Boolean, rejected: MutableList<RejectedFile>): List<File> {
        val images = mutableListOf<File>()
        val notes = mutableListOf<File>()
        for (file in listed(folder)) {
            val extension = file.extension.lowercase()
            val reason = when {
                extension in LocalMediaManager.IMAGE_EXTENSIONS -> when {
                    file.length() > MAX_BYTES -> RejectedFile.Reason.TOO_LARGE
                    !isImage(file) -> RejectedFile.Reason.FORMAT
                    images.size >= MAX_FILES -> RejectedFile.Reason.TOO_MANY
                    else -> null
                }
                extension in OTHER_IMAGE_EXTENSIONS -> RejectedFile.Reason.FORMAT
                extension != LocalMediaManager.TEXT_EXTENSION -> continue
                !texts -> RejectedFile.Reason.MISPLACED
                file.length() !in 1..MAX_TEXT_BYTES || !isText(file) -> RejectedFile.Reason.TEXT
                notes.size >= MAX_FILES -> RejectedFile.Reason.TOO_MANY
                else -> null
            }
            when {
                reason != null -> rejected += RejectedFile(file, reason)
                extension == LocalMediaManager.TEXT_EXTENSION -> notes += file
                else -> images += file
            }
        }
        return images + notes
    }

    /** A folder's files in name order, without the hidden ones a Mac leaves beside each image (._photo.jpg). */
    private fun listed(folder: File): List<File> =
        folder.listFiles().orEmpty().filter { it.isFile && !it.name.startsWith(".") }.sortedBy { it.name.lowercase() }

    private fun isMedia(file: File): Boolean = file.extension.lowercase().let {
        it in LocalMediaManager.IMAGE_EXTENSIONS || it in OTHER_IMAGE_EXTENSIONS || it == LocalMediaManager.TEXT_EXTENSION
    }

    /** Images the screen cannot show, told apart by their names alone. */
    private val OTHER_IMAGE_EXTENSIONS = setOf("heic", "heif", "gif", "bmp", "tif", "tiff", "avif", "jfif")

    /** By content, not name: JPEG, PNG or WebP signatures. */
    fun isImage(file: File): Boolean = runCatching {
        isImage(file.inputStream().use { input -> ByteArray(12).also { input.read(it) } })
    }.getOrDefault(false)

    fun isImage(head: ByteArray): Boolean {
        if (head.size < 12) return false
        val jpeg = head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte()
        val png = head.copyOfRange(0, 4).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
        val webp = String(head, 0, 4, Charsets.US_ASCII) == "RIFF" && String(head, 8, 4, Charsets.US_ASCII) == "WEBP"
        return jpeg || png || webp
    }

    /** Readable text with something in it (not a renamed binary file); see [AnnouncementText]. */
    fun isText(file: File): Boolean = runCatching { AnnouncementText.fromBytes(file.readBytes()) != null }.getOrDefault(false)

    private fun signature(images: Map<MediaKind, List<File>>, rejected: List<RejectedFile>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        images.forEach { (kind, files) ->
            files.forEach { digest.update("${kind.name}/${it.name}/${it.length()}/${it.lastModified()}\n".toByteArray()) }
        }
        rejected.forEach { digest.update("${it.reason}/${it.file.path}/${it.file.length()}/${it.file.lastModified()}\n".toByteArray()) }
        return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
    }
}

/**
 * Offers the images of a key once, and copies them to the TV when the admin confirms.
 * [readHandled] and [writeHandled] keep the sets already answered ([HandledSignatures]).
 */
class UsbMediaInbox(
    private val store: LocalMediaManager,
    readHandled: () -> String,
    writeHandled: (String) -> Unit,
) {
    private val handled = HandledSignatures(readHandled, writeHandled)

    /** The first key's files not answered yet, with how many of the TV's files each kind's copy removes. */
    fun scan(volumes: List<RemovableVolume>): UsbMediaFound? =
        volumes.firstNotNullOfOrNull { volume -> UsbMedia.find(volume)?.takeIf { it.signature !in handled } }?.let { found ->
            found.copy(replaced = found.images.filterValues { it.isNotEmpty() }.mapValues { (kind, files) -> store.replacedBy(kind, files).size })
        }

    /**
     * Copies each kind the key has files for, every kind even when one fails. The set counts as
     * answered only once all were copied: a key pulled out mid-copy is offered again when it comes back.
     */
    fun apply(found: UsbMediaFound): UsbMediaCopy {
        var copied = 0
        var unreadable = 0
        var failure: UsbMediaCopy? = null
        found.images.filterValues { it.isNotEmpty() }.forEach { (kind, files) ->
            runCatching { store.replace(kind, files) }
                .onSuccess { stored ->
                    copied += stored
                    unreadable += files.size - stored
                }
                .onFailure { failure = if (it is LocalMediaManager.NoRoom || failure == UsbMediaCopy.NoRoom) UsbMediaCopy.NoRoom else UsbMediaCopy.Failed }
        }
        return failure ?: UsbMediaCopy.Done(copied, unreadable).also { handled.add(found.signature) }
    }

    fun dismiss(found: UsbMediaFound) = handled.add(found.signature)
}

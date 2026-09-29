package com.tunisianprayertimes.tv.usb

import com.tunisianprayertimes.tv.data.LocalMediaManager
import com.tunisianprayertimes.tv.data.MediaKind
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** Images found on a key, per kind; [signature] identifies them so the same images are offered once. */
data class UsbMediaFound(val images: Map<MediaKind, List<File>>, val signature: String) {
    val isEmpty: Boolean get() = images.values.all { it.isEmpty() }
}

/**
 * Background and announcement images on a USB key: the "backgrounds" and "announcements" folders
 * next to the settings file. Only real JPEG, PNG or WebP files of a sensible size are taken.
 */
object UsbMedia {

    const val MAX_FILES = 20
    const val MAX_BYTES = 15L * 1024 * 1024

    /** Creates the empty folders on a key, so the admin sees where the images go. */
    fun ensureFolders(volume: RemovableVolume) {
        MediaKind.entries.forEach { runCatching { File(volume.appFolder, it.folder).mkdirs() } }
    }

    /** The images on the first key that has any, or null. Never throws. */
    fun find(volumes: List<RemovableVolume>): UsbMediaFound? = volumes.firstNotNullOfOrNull { volume ->
        val images = MediaKind.entries.associateWith { kind -> imagesIn(File(volume.appFolder, kind.folder)) }
        UsbMediaFound(images, signature(images)).takeUnless { it.isEmpty }
    }

    private fun imagesIn(folder: File): List<File> = runCatching {
        folder.listFiles().orEmpty()
            .filter { it.isFile && it.extension.lowercase() in LocalMediaManager.IMAGE_EXTENSIONS && it.length() in 1..MAX_BYTES && isImage(it) }
            .sortedBy { it.name.lowercase() }
            .take(MAX_FILES)
    }.getOrDefault(emptyList())

    /** By content, not name: JPEG, PNG or WebP signatures. */
    fun isImage(file: File): Boolean = runCatching {
        val head = file.inputStream().use { input -> ByteArray(12).also { input.read(it) } }
        val jpeg = head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte()
        val png = head.copyOfRange(0, 4).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
        val webp = String(head, 0, 4, Charsets.US_ASCII) == "RIFF" && String(head, 8, 4, Charsets.US_ASCII) == "WEBP"
        jpeg || png || webp
    }.getOrDefault(false)

    private fun signature(images: Map<MediaKind, List<File>>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        images.forEach { (kind, files) ->
            files.forEach { digest.update("${kind.name}/${it.name}/${it.length()}/${it.lastModified()}\n".toByteArray()) }
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
    }
}

/** Offers the images of a key once, and copies them to the TV when the admin confirms. */
class UsbMediaInbox(
    private val store: LocalMediaManager,
    private val lastHandled: () -> String,
    private val setLastHandled: (String) -> Unit,
) {
    fun scan(volumes: List<RemovableVolume>): UsbMediaFound? =
        UsbMedia.find(volumes)?.takeIf { it.signature != lastHandled() }

    /** Replaces each kind the key has images for; the other kind stays as it is. False when a copy failed. */
    fun apply(found: UsbMediaFound): Boolean {
        setLastHandled(found.signature)
        return found.images.filterValues { it.isNotEmpty() }.all { (kind, files) -> runCatching { store.replace(kind, files) }.isSuccess }
    }

    fun dismiss(found: UsbMediaFound) = setLastHandled(found.signature)
}

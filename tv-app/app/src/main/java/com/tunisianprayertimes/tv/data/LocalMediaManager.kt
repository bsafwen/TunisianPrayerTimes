package com.tunisianprayertimes.tv.data

import android.content.Context
import android.net.Uri
import java.io.File

/** An announcement shown in the slideshow after the after-salah adhkar. */
sealed class Announcement {
    data class Image(val uri: Uri) : Announcement()
    data class Text(val title: String, val content: String) : Announcement()
}

/**
 * Backgrounds and announcements placed in the app's own external folders
 * (Android/data/<package>/files/backgrounds and .../announcements), which need no
 * storage permission on any Android version.
 */
class LocalMediaManager(private val context: Context) {

    private fun folder(name: String): File? = context.getExternalFilesDir(name)

    fun ensureDirectories() {
        folder(BACKGROUNDS)?.mkdirs()
        folder(ANNOUNCEMENTS)?.mkdirs()
    }

    fun getBackgroundImages(): List<Uri> = images(folder(BACKGROUNDS)).map(Uri::fromFile)

    /** Images become image slides; each .txt file becomes a text slide (first line is the title). */
    fun getAnnouncements(): List<Announcement> {
        val dir = folder(ANNOUNCEMENTS) ?: return emptyList()
        val files = dir.listFiles().orEmpty().sortedBy { it.name.lowercase() }
        return files.mapNotNull { file ->
            when {
                file.isImage() -> Announcement.Image(Uri.fromFile(file))
                file.extension.equals("txt", ignoreCase = true) -> textAnnouncement(file)
                else -> null
            }
        }
    }

    private fun textAnnouncement(file: File): Announcement.Text? {
        val lines = runCatching { file.readLines() }.getOrNull()?.map(String::trim) ?: return null
        val title = lines.firstOrNull { it.isNotEmpty() } ?: return null
        val content = lines.dropWhile { it != title }.drop(1).joinToString("\n").trim()
        return Announcement.Text(title = title, content = content)
    }

    private fun images(dir: File?): List<File> =
        dir?.listFiles().orEmpty().filter { it.isImage() }.sortedBy { it.name.lowercase() }

    private fun File.isImage(): Boolean = isFile && extension.lowercase() in IMAGE_EXTENSIONS

    private companion object {
        const val BACKGROUNDS = "backgrounds"
        const val ANNOUNCEMENTS = "announcements"
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    }
}

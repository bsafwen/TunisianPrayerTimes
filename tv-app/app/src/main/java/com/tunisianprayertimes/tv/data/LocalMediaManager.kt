package com.tunisianprayertimes.tv.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File

/** An announcement shown in the slideshow after the after-salah adhkar. */
sealed class Announcement {
    data class Image(val uri: Uri) : Announcement()
    data class Text(val title: String, val content: String) : Announcement()
}

/**
 * Backgrounds and announcements the admin copies into the TunisianPrayerTimesTV folder at
 * the root of the device storage, as the settings screen explains. Each image is a slide;
 * each .txt file is a text slide titled by its name.
 */
class LocalMediaManager(@Suppress("unused") private val context: Context) {

    private val baseDir: File get() = File(Environment.getExternalStorageDirectory(), APP_FOLDER)
    private val backgroundsDir: File get() = File(baseDir, BACKGROUNDS_FOLDER)
    private val announcementsDir: File get() = File(baseDir, ANNOUNCEMENTS_FOLDER)

    fun ensureDirectories() {
        runCatching {
            backgroundsDir.mkdirs()
            announcementsDir.mkdirs()
        }
    }

    fun getBackgroundImages(): List<Uri> = listImageFiles(backgroundsDir).map(Uri::fromFile)

    fun getAnnouncements(): List<Announcement> {
        val files = announcementsDir.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name.lowercase() }
        return files.mapNotNull { file ->
            val extension = file.extension.lowercase()
            when {
                extension in IMAGE_EXTENSIONS -> Announcement.Image(Uri.fromFile(file))
                extension == TEXT_EXTENSION -> runCatching { file.readText().trim() }.getOrNull()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { Announcement.Text(title = file.nameWithoutExtension, content = it) }
                else -> null
            }
        }
    }

    private fun listImageFiles(dir: File): List<File> =
        dir.listFiles().orEmpty()
            .filter { it.isFile && it.extension.lowercase() in IMAGE_EXTENSIONS }
            .sortedBy { it.name.lowercase() }

    private companion object {
        const val APP_FOLDER = "TunisianPrayerTimesTV"
        const val BACKGROUNDS_FOLDER = "backgrounds"
        const val ANNOUNCEMENTS_FOLDER = "announcements"
        const val TEXT_EXTENSION = "txt"
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    }
}

package com.tunisianprayertimes.tv.usb

import com.tunisianprayertimes.ManualIslamicDates
import com.tunisianprayertimes.mosque.AdhkarContent
import com.tunisianprayertimes.mosque.MosqueProfile
import com.tunisianprayertimes.mosque.MosqueSchedule
import com.tunisianprayertimes.mosque.MosqueSettingsFile
import com.tunisianprayertimes.mosque.MosqueSettingsFile.ParseResult
import com.tunisianprayertimes.mosque.ProfileCatalog
import com.tunisianprayertimes.mosque.TextAnnouncement
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale

/**
 * A mounted USB key or SD card, reached through [appFolder]: this app's own folder on it
 * (Android/data/<package>/files). It is the only place on a removable volume that every Android
 * version lets an app read and write without a permission, and Android TV has no file picker.
 */
data class RemovableVolume(val appFolder: File)

/** A settings file read from a USB key. [signature] identifies its content, so a key left in is only offered once. */
data class UsbSettingsFound(val file: File, val text: String, val signature: String)

/** Finds, reads and seeds the mosque settings file ([MosqueSettingsFile.FILE_NAME]) on removable volumes. */
object UsbSettings {

    /** Every character the settings file may hold, at up to four bytes each in UTF-8. */
    private const val MAX_BYTES = MosqueSettingsFile.MAX_CHARS * 4

    fun settingsFile(volume: RemovableVolume): File = File(volume.appFolder, MosqueSettingsFile.FILE_NAME)

    /** The most recently modified non-empty settings file on any of [volumes], or null. */
    fun find(volumes: List<RemovableVolume>): File? =
        volumes.map(::settingsFile)
            .filter(::isPresent)
            .maxByOrNull { runCatching { it.lastModified() }.getOrDefault(0L) }

    /** Reads [file]; null when it cannot be read. Never throws. */
    fun read(file: File): UsbSettingsFound? {
        val bytes = runCatching { readAtMost(file, MAX_BYTES + 1) }.getOrNull() ?: return null
        return UsbSettingsFound(file, bytes.toString(Charsets.UTF_8), signature(bytes))
    }

    /**
     * Puts the TV's settings file [text] on each key that has no settings file yet, so the admin gets a
     * correctly placed file to edit on a computer. The file is synced before it is renamed into place,
     * because keys are usually pulled out without ejecting. Returns the files written and their signature.
     */
    fun writeTemplates(volumes: List<RemovableVolume>, text: String): Pair<List<File>, String> {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val written = volumes.filterNot { isPresent(settingsFile(it)) }.mapNotNull { volume ->
            runCatching {
                volume.appFolder.mkdirs()
                val target = settingsFile(volume)
                val temp = File(volume.appFolder, "${MosqueSettingsFile.FILE_NAME}.tmp")
                writeAtomically(target, bytes)
                target
            }.getOrNull()
        }
        return written to signature(bytes)
    }

    /** Writes through a synced temporary file, so a pulled key or a power cut never leaves half a file. */
    fun writeAtomically(target: File, bytes: ByteArray) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(temp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        check(temp.renameTo(target) || (target.delete() && temp.renameTo(target))) { "rename failed" }
    }

    /** An empty file (a key pulled mid-write) counts as absent, so the template is written again. */
    private fun isPresent(file: File): Boolean =
        runCatching { file.isFile && file.canRead() && file.length() > 0 }.getOrDefault(false)

    /** The first [limit] bytes of [file] (InputStream.readNBytes needs Android 13). */
    private fun readAtMost(file: File, limit: Int): ByteArray = file.inputStream().use { input ->
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        while (out.size() < limit) {
            val read = input.read(chunk, 0, minOf(chunk.size, limit - out.size()))
            if (read < 0) break
            out.write(chunk, 0, read)
        }
        out.toByteArray()
    }

    private fun signature(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(Locale.ROOT, it) }
}

/** What a scan of the plugged-in keys found. */
sealed interface UsbScan {
    /** Nothing to tell the admin. */
    data object Quiet : UsbScan

    /** A key had no settings file: the current settings were written to it as a template. */
    data class TemplateWritten(val files: List<File>) : UsbScan

    /** A settings file with changes to confirm, or mistakes to show. */
    data class Offer(val found: UsbSettingsFound) : UsbScan

    /** A key is plugged in but this box does not let apps read it. */
    data object Inaccessible : UsbScan
}

/**
 * Decides what to do with settings files on USB keys, and applies them. Files are parsed against
 * the TV's settings at the moment they are shown and applied, never against an older snapshot.
 * [readDates] and [writeDates] are the admin's Ramadan and Eid dates by Hijri year; [readProfile]
 * and [writeProfile] the mosque's name, place and theme, checked against [catalog].
 * Before a file is applied, the TV's settings are kept with [saveSnapshot] so the import can be undone.
 */
class UsbSettingsInbox(
    private val readSchedule: () -> MosqueSchedule,
    private val writeSchedule: (MosqueSchedule) -> Unit,
    private val lastHandled: () -> String,
    private val setLastHandled: (String) -> Unit,
    private val readDates: () -> Map<Int, ManualIslamicDates> = { emptyMap() },
    private val writeDates: (Map<Int, ManualIslamicDates>) -> Unit = {},
    private val readProfile: () -> MosqueProfile = { MosqueProfile() },
    private val writeProfile: (MosqueProfile) -> Unit = {},
    private val catalog: ProfileCatalog = ProfileCatalog.NONE,
    private val saveSnapshot: (String) -> Unit = {},
    private val readContent: () -> AdhkarContent = { AdhkarContent() },
    private val writeContent: (AdhkarContent) -> Unit = {},
    private val readAnnouncements: () -> List<TextAnnouncement> = { emptyList() },
    private val writeAnnouncements: (List<TextAnnouncement>) -> Unit = {},
) {

    /** The TV's whole settings as a file: the template put on a new key, and what a copy to another TV carries. */
    fun currentFile(): String =
        MosqueSettingsFile.write(readSchedule(), readDates(), readProfile(), catalog, content = readContent(), announcements = readAnnouncements())

    fun scan(volumes: List<RemovableVolume>, hiddenVolumes: Int = 0): UsbScan {
        val file = UsbSettings.find(volumes)
        if (file == null) {
            if (volumes.isEmpty()) return if (hiddenVolumes > 0) UsbScan.Inaccessible else UsbScan.Quiet
            val (written, signature) = UsbSettings.writeTemplates(volumes, currentFile())
            if (written.isEmpty()) return UsbScan.Quiet
            // The TV's own template is not something to confirm; only an edited file is offered.
            setLastHandled(signature)
            return UsbScan.TemplateWritten(written)
        }
        val found = UsbSettings.read(file) ?: return UsbScan.Quiet
        if (found.signature == lastHandled()) return UsbScan.Quiet
        val result = preview(found)
        if (result is ParseResult.Success && !result.hasChanges) {
            setLastHandled(found.signature)
            return UsbScan.Quiet
        }
        return UsbScan.Offer(found)
    }

    /** The file against the current settings: what applying it would change, or its mistakes. */
    fun preview(found: UsbSettingsFound): ParseResult =
        MosqueSettingsFile.parse(found.text, readSchedule(), readDates(), readProfile(), catalog, readContent(), readAnnouncements())

    /**
     * Applies [found] to the current settings; false when the file has mistakes. A file from a key
     * is not offered again either way ([fromKey]); the undo snapshot is not a key file.
     */
    @Synchronized
    fun apply(found: UsbSettingsFound, fromKey: Boolean = true): Boolean {
        val result = preview(found)
        if (result is ParseResult.Success) {
            // Every year the file touches is in the snapshot, so undoing also clears dates it added.
            val touched = readDates() + result.islamicDates.keys.filterNot(readDates()::containsKey).associateWith { ManualIslamicDates() }
            saveSnapshot(MosqueSettingsFile.write(
                readSchedule(), touched, readProfile(), catalog, complete = true, content = readContent(), announcements = readAnnouncements(),
            ))
            writeSchedule(result.schedule)
            if (result.dateChanges.isNotEmpty()) writeDates(result.islamicDates)
            if (result.profileChanges.isNotEmpty()) writeProfile(result.profile)
            if (result.contentChanges.isNotEmpty()) {
                writeContent(result.content)
                writeAnnouncements(result.announcements)
            }
        }
        if (fromKey) setLastHandled(found.signature)
        return result is ParseResult.Success
    }

    fun dismiss(found: UsbSettingsFound) = setLastHandled(found.signature)
}

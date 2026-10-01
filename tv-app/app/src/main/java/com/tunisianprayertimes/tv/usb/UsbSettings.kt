package com.tunisianprayertimes.tv.usb

import com.tunisianprayertimes.ManualIslamicDates
import com.tunisianprayertimes.TunisianHijriCalendar
import com.tunisianprayertimes.YearDates
import com.tunisianprayertimes.mosque.AdhkarContent
import com.tunisianprayertimes.mosque.MosqueProfile
import com.tunisianprayertimes.mosque.MosqueSchedule
import com.tunisianprayertimes.mosque.MosqueSettingsFile
import com.tunisianprayertimes.mosque.MosqueSettingsFile.ParseResult
import com.tunisianprayertimes.mosque.ProfileCatalog
import com.tunisianprayertimes.mosque.TextAnnouncement
import com.tunisianprayertimes.time.TunisTime
import com.tunisianprayertimes.tv.data.AnnouncementText
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.LocalDate
import java.util.Locale

/**
 * A mounted USB key or SD card, reached through [appFolder]: this app's own folder on it
 * (Android/data/<package>/files). It is the only place on a removable volume that every Android
 * version lets an app read and write without a permission, and Android TV has no file picker.
 * A [readOnly] volume is read, never written.
 */
data class RemovableVolume(val appFolder: File, val readOnly: Boolean = false)

/**
 * Mounted volumes the app cannot reach: [readOnly] ones where Android could not make the app's folder
 * (a new NTFS key), and [keptFromApps] on a box that does not let apps use its USB ports.
 */
data class HiddenVolumes(val readOnly: Int = 0, val keptFromApps: Int = 0)

/**
 * A settings file read from a USB key. [signature] identifies its content, so a key left in is only
 * offered once. [stored] is the TV's own saved settings (the undo snapshot), read as they were saved.
 */
data class UsbSettingsFound(val file: File, val text: String, val signature: String, val stored: Boolean = false)

/** Finds, reads and seeds the mosque settings file ([MosqueSettingsFile.FILE_NAME]) on removable volumes. */
object UsbSettings {

    /** Every character the settings file may hold, at up to four bytes each in UTF-8. */
    private const val MAX_BYTES = MosqueSettingsFile.MAX_CHARS * 4

    fun settingsFile(volume: RemovableVolume): File = File(volume.appFolder, MosqueSettingsFile.FILE_NAME)

    /** The non-empty settings files on [volumes], the most recently modified first. */
    fun files(volumes: List<RemovableVolume>): List<File> =
        volumes.map(::settingsFile)
            .filter(::isPresent)
            .sortedByDescending { runCatching { it.lastModified() }.getOrDefault(0L) }

    fun hasFile(volume: RemovableVolume): Boolean = isPresent(settingsFile(volume))

    /** Reads [file]; null when it cannot be read. Never throws. */
    fun read(file: File): UsbSettingsFound? {
        val bytes = runCatching { readAtMost(file, MAX_BYTES + 1) }.getOrNull() ?: return null
        return UsbSettingsFound(file, decode(bytes), signature(bytes))
    }

    /** The TV's own saved settings (the undo snapshot): a text an update retired since must not block the undo. */
    fun readSaved(file: File): UsbSettingsFound? = read(file)?.copy(stored = true)

    /**
     * The file's text however the admin saved it (UTF-8, Notepad's "Unicode" or its Arabic "ANSI"), as
     * for the .txt announcements. What none of them reads keeps its replacement characters, and the
     * file is then refused with a word about its encoding rather than about its brackets.
     */
    private fun decode(bytes: ByteArray): String = AnnouncementText.decode(bytes) ?: bytes.toString(Charsets.UTF_8)

    /**
     * Puts the TV's settings file [text] on each of [volumes] that has no settings file yet, so the admin
     * gets a correctly placed file to edit on a computer. Returns the files written and their signature.
     */
    fun writeTemplates(volumes: List<RemovableVolume>, text: String): Pair<List<File>, String> =
        write(volumes.filterNot(::hasFile), text)

    /**
     * Writes the TV's settings [text] on each of [volumes], over the file already there: how the admin
     * carries this TV's current settings to another. Returns the files written and their signature.
     */
    fun export(volumes: List<RemovableVolume>, text: String): Pair<List<File>, String> = write(volumes, text)

    /**
     * The file is synced before it is renamed into place, because keys are usually pulled out without
     * ejecting. A file it replaces is kept beside it as mosque-tv.json.bak, in case it held the admin's work.
     */
    private fun write(volumes: List<RemovableVolume>, text: String): Pair<List<File>, String> {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val written = volumes.filterNot { it.readOnly }.mapNotNull { volume ->
            runCatching {
                volume.appFolder.mkdirs()
                val target = settingsFile(volume)
                if (isPresent(target)) target.copyTo(File(volume.appFolder, "${MosqueSettingsFile.FILE_NAME}.bak"), overwrite = true)
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

/**
 * The signatures of the last files the admin answered (applied or dismissed), newest first, in one
 * preference: a key or card left in, or a key that comes back, is not offered the same file again,
 * whatever other keys came meanwhile.
 */
class HandledSignatures(private val read: () -> String, private val write: (String) -> Unit) {
    operator fun contains(signature: String): Boolean = signature in list()

    fun add(signature: String) = write((listOf(signature) + list().filter { it != signature }).take(MAX).joinToString(" "))

    private fun list(): List<String> = read().split(' ').filter { it.isNotEmpty() }

    companion object {
        const val MAX = 16
    }
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

    /**
     * A key without a settings file that Android mounted read-only: no template can be written on it,
     * nor, on a new key, the app's folder where a file would be read.
     */
    data object ReadOnly : UsbScan
}

/**
 * Decides what to do with settings files on USB keys, and applies them. Files are parsed against
 * the TV's settings at the moment they are shown and applied, never against an older snapshot.
 * [readDates] and [writeDates] are the admin's Ramadan and Eid dates by Hijri year, checked together
 * with the TV's [yearDates] (its announced dates); [readProfile]
 * and [writeProfile] the mosque's name, place, theme and prayer-time values, checked against [catalog].
 * Before a file is applied, the TV's settings are kept with [saveSnapshot] so the import can be undone;
 * [readSnapshot] reads them back, so the undo is taken as the TV's own former state.
 * [readHandled] and [writeHandled] keep the files already answered ([HandledSignatures]).
 */
class UsbSettingsInbox(
    private val readSchedule: () -> MosqueSchedule,
    private val writeSchedule: (MosqueSchedule) -> Unit,
    readHandled: () -> String,
    writeHandled: (String) -> Unit,
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
    private val yearDates: (Int) -> YearDates? = { null },
    private val readSnapshot: () -> String? = { null },
    /** Today in Tunisia, for the Hijri years the file carries. */
    private val today: () -> LocalDate = { LocalDate.now(TunisTime.ZONE) },
) {
    private val handled = HandledSignatures(readHandled, writeHandled)

    /**
     * The TV's whole settings as a file: the template put on a new key, what a copy to another TV
     * carries, and the dashboard's «متقدّم». Written in full (what is unset as null, and this Hijri
     * year and the next with their dates or null), so the TV that reads it ends up the same instead
     * of keeping its own Ramadan changes, dates, texts and announcements.
     */
    fun currentFile(): String {
        val year = runCatching { TunisianHijriCalendar().date(today()).year }.getOrNull()
        val years = listOfNotNull(year, year?.plus(1)).associateWith { ManualIslamicDates() }
        return MosqueSettingsFile.write(
            readSchedule(), years + readDates(), readProfile(), catalog, complete = true, content = readContent(), announcements = readAnnouncements(),
        )
    }

    /**
     * Offers the newest file not answered yet, on any of [volumes]: another key's or a card's file never
     * hides it. Only a key [justMounted] gets the TV's file as a template: a card that stays in the box
     * is left alone. With no volume to use, the [hidden] ones say why.
     */
    fun scan(volumes: List<RemovableVolume>, hidden: HiddenVolumes = HiddenVolumes(), justMounted: (RemovableVolume) -> Boolean = { true }): UsbScan {
        for (file in UsbSettings.files(volumes)) {
            val found = UsbSettings.read(file) ?: continue
            if (found.signature in handled) continue
            val result = preview(found)
            if (result is ParseResult.Success && !result.hasChanges) {
                handled.add(found.signature)
                continue
            }
            return UsbScan.Offer(found)
        }
        if (volumes.isEmpty()) return when {
            hidden.readOnly > 0 -> UsbScan.ReadOnly
            hidden.keptFromApps > 0 -> UsbScan.Inaccessible
            else -> UsbScan.Quiet
        }
        val fresh = volumes.filter(justMounted).filterNot(UsbSettings::hasFile)
        if (fresh.isEmpty()) return UsbScan.Quiet
        if (fresh.all { it.readOnly }) return UsbScan.ReadOnly
        val (written, signature) = UsbSettings.writeTemplates(fresh, currentFile())
        if (written.isEmpty()) return UsbScan.Quiet
        // The TV's own template is not something to confirm; only an edited file is offered.
        handled.add(signature)
        return UsbScan.TemplateWritten(written)
    }

    /** Writes the TV's settings over the file on every key; returns the files written. */
    fun export(volumes: List<RemovableVolume>): List<File> {
        val (written, signature) = UsbSettings.export(volumes, currentFile())
        // The TV's own file: offered to other TVs, never back to this one.
        if (written.isNotEmpty()) handled.add(signature)
        return written
    }

    /**
     * During onboarding: a file on [volumes] that can set up this TV by itself, one without mistakes
     * that names the mosque's delegation (another TV's copy, a prepared file). Nothing is written to
     * the keys and nothing is marked handled.
     */
    fun setupFile(volumes: List<RemovableVolume>): UsbSettingsFound? =
        UsbSettings.files(volumes).asSequence().mapNotNull(UsbSettings::read).firstOrNull { found ->
            (preview(found) as? ParseResult.Success)?.profile?.delegationId != null
        }

    /**
     * The file against the current settings: what applying it would change, or its mistakes. The undo
     * snapshot, from the TV or the phone, returns whole: its dates are not refused over an announcement
     * that arrived since.
     */
    fun preview(found: UsbSettingsFound): ParseResult =
        MosqueSettingsFile.parse(found.text, readSchedule(), readDates(), readProfile(), catalog, readContent(), readAnnouncements(),
            stored = found.stored || found.text == readSnapshot(), yearDates = yearDates)

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
            // The prayer-time values travel with the profile (MosqueProfile.formula).
            if (result.profileChanges.isNotEmpty() || result.formulaChanges.isNotEmpty()) writeProfile(result.profile)
            if (result.contentChanges.isNotEmpty()) {
                writeContent(result.content)
                writeAnnouncements(result.announcements)
            }
        }
        if (fromKey) handled.add(found.signature)
        return result is ParseResult.Success
    }

    fun dismiss(found: UsbSettingsFound) = handled.add(found.signature)
}

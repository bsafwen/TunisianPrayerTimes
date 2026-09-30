package com.tunisianprayertimes.tv.kiosk

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * What the kiosk remembers happened, for the health page. Local only: nothing is uploaded.
 * The clock's events carry the details [ClockLog] writes: CLOCK_SET, the system clock was set (by
 * someone, the network or the app itself); ZONE_SET, the system zone changed; CLOCK_CONFIRMED, the
 * time shown was confirmed or corrected (by the network, the admin or the phone).
 */
enum class KioskEvent {
    BOOT, AUTOSTART_OK, AUTOSTART_BLOCKED, CRASH, SAFE_MODE_ENTER, SAFE_MODE_EXIT, WATCHDOG_REFRONT,
    SLEEP_GAP, SCREEN_OFF, SCREEN_ON, MAINT_RESTART, HOME_MODE_ON, HOME_MODE_OFF, ADMIN_EXIT, UPDATE,
    BOOT_TIMING, QUICK_START, CLOCK_SET, ZONE_SET, CLOCK_CONFIRMED,
}

/** One logged event; [atMillis] is the device's wall clock, which may have been wrong at the time. */
data class EventEntry(val atMillis: Long, val type: KioskEvent, val detail: String = "")

/**
 * A small rolling log of kiosk events, one per line, keeping the newest [capacity]. Every write
 * replaces the file through a synced temporary file, so a power cut leaves the old log or the new
 * one, never half of either. Events are rare (a few a day), so rewriting the file is cheap.
 * Receivers, services and the display each build their own EventLog on the same file, so the lock
 * and the parsed lines are shared per file in the process.
 */
class EventLog(private val file: File, private val capacity: Int = 500) {

    private val shared: Shared = files.getOrPut(runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)) { Shared() }

    fun append(entry: EventEntry) = synchronized(shared) {
        runCatching {
            val lines = (readLines() + encode(entry)).takeLast(capacity)
            file.parentFile?.mkdirs()
            val temp = File.createTempFile(file.name, ".tmp", file.parentFile)
            try {
                FileOutputStream(temp).use { out ->
                    out.write(lines.joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8))
                    out.fd.sync()
                }
                // A failed move throws and keeps the live log: losing one event beats losing them all.
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
                shared.remember(file, lines)
            } finally {
                temp.delete()
            }
        }
        Unit
    }

    fun append(type: KioskEvent, detail: String = "", atMillis: Long = System.currentTimeMillis()) =
        append(EventEntry(atMillis, type, detail))

    /** The newest [limit] events, newest first. Unreadable lines are skipped. */
    fun recent(limit: Int = 50): List<EventEntry> = synchronized(shared) {
        readLines().mapNotNull(::decode).takeLast(limit).reversed()
    }

    /** The newest event of one of [types], if any. */
    fun last(vararg types: KioskEvent): EventEntry? = recent(capacity).firstOrNull { it.type in types }

    /** The file is parsed again only when it changed since the last read or write. */
    private fun readLines(): List<String> {
        shared.linesIfCurrent(file)?.let { return it }
        val lines = runCatching { if (file.isFile) file.readLines(Charsets.UTF_8).filter { it.isNotBlank() } else emptyList() }
            .getOrDefault(emptyList())
        shared.remember(file, lines)
        return lines
    }

    private fun encode(entry: EventEntry): String =
        "${entry.atMillis}\t${entry.type.name}\t${entry.detail.replace('\n', ' ').replace('\t', ' ').take(MAX_DETAIL)}"

    private fun decode(line: String): EventEntry? {
        val parts = line.split('\t', limit = 3)
        if (parts.size < 2) return null
        val at = parts[0].toLongOrNull() ?: return null
        val type = KioskEvent.entries.find { it.name == parts[1] } ?: return null
        return EventEntry(at, type, parts.getOrElse(2) { "" })
    }

    private class Shared {
        private var lines: List<String>? = null
        private var stamp = 0L to 0L

        fun linesIfCurrent(file: File): List<String>? = lines?.takeIf { stamp == stampOf(file) }

        fun remember(file: File, lines: List<String>) {
            this.lines = lines
            stamp = stampOf(file)
        }

        private fun stampOf(file: File) = file.lastModified() to file.length()
    }

    private companion object {
        const val MAX_DETAIL = 2000
        val files = ConcurrentHashMap<String, Shared>()
    }
}

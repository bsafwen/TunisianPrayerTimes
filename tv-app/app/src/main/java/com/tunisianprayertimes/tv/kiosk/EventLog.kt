package com.tunisianprayertimes.tv.kiosk

import java.io.File
import java.io.FileOutputStream

/** What the kiosk remembers happened, for the health page. Local only: nothing is uploaded. */
enum class KioskEvent {
    BOOT, AUTOSTART_OK, AUTOSTART_BLOCKED, CRASH, SAFE_MODE_ENTER, SAFE_MODE_EXIT, WATCHDOG_REFRONT,
    SLEEP_GAP, SCREEN_OFF, SCREEN_ON, MAINT_RESTART, HOME_MODE_ON, HOME_MODE_OFF, ADMIN_EXIT, UPDATE,
    BOOT_TIMING, QUICK_START,
}

/** One logged event; [atMillis] is the device's wall clock, which may have been wrong at the time. */
data class EventEntry(val atMillis: Long, val type: KioskEvent, val detail: String = "")

/**
 * A small rolling log of kiosk events, one per line, keeping the newest [capacity]. Every write
 * replaces the file through a synced temporary file, so a power cut leaves the old log or the new
 * one, never half of either. Events are rare (a few a day), so rewriting the file is cheap.
 */
class EventLog(private val file: File, private val capacity: Int = 500) {

    @Synchronized
    fun append(entry: EventEntry) {
        runCatching {
            val lines = (readLines() + encode(entry)).takeLast(capacity)
            file.parentFile?.mkdirs()
            val temp = File(file.path + ".tmp")
            FileOutputStream(temp).use { out ->
                out.write(lines.joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        }
    }

    fun append(type: KioskEvent, detail: String = "", atMillis: Long = System.currentTimeMillis()) =
        append(EventEntry(atMillis, type, detail))

    /** The newest [limit] events, newest first. Unreadable lines are skipped. */
    @Synchronized
    fun recent(limit: Int = 50): List<EventEntry> = readLines().mapNotNull(::decode).takeLast(limit).reversed()

    /** The newest event of one of [types], if any. */
    fun last(vararg types: KioskEvent): EventEntry? = recent(capacity).firstOrNull { it.type in types }

    private fun readLines(): List<String> =
        runCatching { if (file.isFile) file.readLines(Charsets.UTF_8).filter { it.isNotBlank() } else emptyList() }
            .getOrDefault(emptyList())

    private fun encode(entry: EventEntry): String =
        "${entry.atMillis}\t${entry.type.name}\t${entry.detail.replace('\n', ' ').replace('\t', ' ').take(MAX_DETAIL)}"

    private fun decode(line: String): EventEntry? {
        val parts = line.split('\t', limit = 3)
        if (parts.size < 2) return null
        val at = parts[0].toLongOrNull() ?: return null
        val type = KioskEvent.entries.find { it.name == parts[1] } ?: return null
        return EventEntry(at, type, parts.getOrElse(2) { "" })
    }

    private companion object {
        const val MAX_DETAIL = 2000
    }
}

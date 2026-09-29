package com.tunisianprayertimes.tv.kiosk

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class EventLogTest {

    private val dir: File = Files.createTempDirectory("kiosk").toFile()
    private val file = File(dir, "events.log")

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun onlyTheNewestEntriesAreKept() {
        val log = EventLog(file, capacity = 5)
        (1..8L).forEach { log.append(KioskEvent.SCREEN_ON, "n$it", atMillis = it) }
        assertEquals((8L downTo 4L).toList(), log.recent(10).map { it.atMillis })
        assertEquals(5, file.readLines().size)
    }

    @Test
    fun brokenLinesAreSkippedAndDetailsStayOnOneLine() {
        file.writeText("garbage\n12\tNOT_AN_EVENT\tx\n13\tCRASH\tboom\n")
        val log = EventLog(file)
        log.append(KioskEvent.CRASH, "line one\nline two\ttab", atMillis = 14)
        assertEquals(listOf(14L, 13L), log.recent().map { it.atMillis })
        assertEquals("line one line two tab", log.recent().first().detail)
        assertEquals(14L, log.last(KioskEvent.CRASH)?.atMillis)
    }

    @Test
    fun aLeftoverTemporaryFileDoesNotHideTheLog() {
        val log = EventLog(file)
        log.append(KioskEvent.BOOT, atMillis = 1)
        File(file.path + ".tmp").writeText("half writ")
        log.append(KioskEvent.AUTOSTART_OK, atMillis = 2)
        assertEquals(listOf(KioskEvent.AUTOSTART_OK, KioskEvent.BOOT), EventLog(file).recent().map { it.type })
    }
}

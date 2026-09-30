package com.tunisianprayertimes.tv.ui

import com.tunisianprayertimes.mosque.FlowPhase
import com.tunisianprayertimes.mosque.MosqueSchedule
import com.tunisianprayertimes.mosque.MosqueSettingsFile
import com.tunisianprayertimes.mosque.MosqueSettingsFile.ParseResult
import com.tunisianprayertimes.mosque.TextAnnouncement
import com.tunisianprayertimes.tv.data.Announcement
import com.tunisianprayertimes.tv.ui.common.NoticePlace
import com.tunisianprayertimes.tv.ui.common.NoticePlacement
import com.tunisianprayertimes.tv.ui.display.MAX_DOTS
import com.tunisianprayertimes.tv.ui.display.PagerKind
import com.tunisianprayertimes.tv.ui.display.largestFitting
import com.tunisianprayertimes.tv.ui.display.nextSlide
import com.tunisianprayertimes.tv.ui.display.pagerKind
import com.tunisianprayertimes.tv.ui.usb.SettingsChangeLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AnnouncementsAndNoticesTest {

    private fun place(phase: FlowPhase, adhkar: Boolean = false, settings: Boolean = false, copying: Boolean = false) =
        NoticePlacement.of(phase, adhkarOnWall = adhkar, onDisplay = !settings, inSettings = settings, copying = copying)

    @Test
    fun noticesWaitForTheWallAfterThePrayersTexts() {
        // A key plugged in during the salah: its notice waits through the adhkar, not over their count and source.
        assertEquals(NoticePlace.HELD, place(FlowPhase.SALAH))
        assertEquals(NoticePlace.HELD, place(FlowPhase.AFTER_SALAH, adhkar = true))
        assertEquals(NoticePlace.HELD, place(FlowPhase.ADHAN))
        assertEquals(NoticePlace.HELD, place(FlowPhase.IQAMAH_COUNTDOWN))
        // The adhkar played through: the wall is the timetable's or the announcements' again.
        assertEquals(NoticePlace.BOTTOM, place(FlowPhase.AFTER_SALAH, adhkar = false))
        assertEquals(NoticePlace.BOTTOM, place(FlowPhase.IDLE))
    }

    @Test
    fun theCopysNoticeStaysInTheTopBarExceptOverThePrayer() {
        // The copy and its outcome, on the wall: in its own top bar, low enough for an overscanning TV.
        assertEquals(NoticePlace.WALL_TOP, place(FlowPhase.ADHAN, copying = true))
        assertEquals(NoticePlace.WALL_TOP, place(FlowPhase.AFTER_SALAH, adhkar = true, copying = true))
        assertEquals(NoticePlace.HELD, place(FlowPhase.SALAH, copying = true))
        assertEquals(NoticePlace.HELD, place(FlowPhase.KHUTBA, copying = true))
        // In settings the admin reads them at once, in the top bar.
        assertEquals(NoticePlace.TOP, place(FlowPhase.SALAH, settings = true))
    }

    @Test
    fun aPassShowsEachAnnouncementOnceThenEnds() {
        assertEquals(1, nextSlide(0, 3))
        assertEquals(2, nextSlide(1, 3))
        assertNull(nextSlide(2, 3))
        assertNull(nextSlide(0, 1))
    }

    @Test
    fun thePagerSaysWhereTheSlideshowIsWithoutCrowdingTheFooter() {
        assertEquals(PagerKind.NONE, pagerKind(1))
        assertEquals(PagerKind.DOTS, pagerKind(2))
        assertEquals(PagerKind.DOTS, pagerKind(MAX_DOTS))
        assertEquals(PagerKind.WORDS, pagerKind(MAX_DOTS + 1))
    }

    @Test
    fun aLongTextStepsDownToTheLargestSizeThatFits() {
        val ladder = listOf(56, 44, 30, 22)
        assertEquals(56, largestFitting(ladder) { true })
        assertEquals(30, largestFitting(ladder) { it <= 40 })
        // Nothing fits: the smallest size, and the text ellipsizes rather than leave the card.
        assertEquals(22, largestFitting(ladder) { false })
    }

    @Test
    fun aSettingsFileAnnouncementKeepsItsEndDate() {
        val until = LocalDate.of(2026, 10, 31)
        val slide = Announcement.Text.of(TextAnnouncement("درس في التفسير", from = LocalDate.of(2026, 9, 1), until = until))
        assertEquals(Announcement.Text(title = "", content = "درس في التفسير", until = until), slide)
        assertEquals("إلى 31 أكتوبر 2026", TvStrings.announcementUntil(until))
        assertNull(Announcement.Text.of(TextAnnouncement("صلاة الجنازة بعد الظهر")).until)
    }

    @Test
    fun theClockPageWritesTheMonthTheTunisianWay() {
        assertEquals("سبتمبر 2026", TvStrings.monthYear(LocalDate.of(2026, 9, 29)))
        assertEquals("جويلية 2027", TvStrings.monthYear(LocalDate.of(2027, 7, 1)))
    }

    @Test
    fun aDateChangeReadsAsADateNotAnIsoString() {
        val result = MosqueSettingsFile.parse("""{ "islamicDates": { "1448": { "ramadanStart": "2027-02-08" } } }""", MosqueSchedule.DEFAULT)
        val line = SettingsChangeLines.of(result as ParseResult.Success).single()
        assertTrue(line, line.endsWith("${TvStrings.AUTOMATIC} ← 8 فيفري 2027"))
    }
}

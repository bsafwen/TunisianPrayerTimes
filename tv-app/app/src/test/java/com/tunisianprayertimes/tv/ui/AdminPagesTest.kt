package com.tunisianprayertimes.tv.ui

import androidx.compose.runtime.saveable.SaverScope
import com.tunisianprayertimes.DayPrayerTimes
import com.tunisianprayertimes.Delegation
import com.tunisianprayertimes.Gouvernorat
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerTime
import com.tunisianprayertimes.mosque.IqamahRule
import com.tunisianprayertimes.mosque.PrayerEvent
import com.tunisianprayertimes.mosque.PrayerOverride
import com.tunisianprayertimes.tv.data.IqamahConfig
import com.tunisianprayertimes.tv.data.IqamahMode
import com.tunisianprayertimes.tv.data.PrefsManager
import com.tunisianprayertimes.tv.ui.kiosk.HealthLevel
import com.tunisianprayertimes.tv.ui.kiosk.HealthRow
import com.tunisianprayertimes.tv.ui.kiosk.KioskAction
import com.tunisianprayertimes.tv.ui.kiosk.color
import com.tunisianprayertimes.tv.ui.kiosk.focusedActionGone
import com.tunisianprayertimes.tv.ui.kiosk.healthSummary
import com.tunisianprayertimes.tv.ui.kiosk.worstFirst
import com.tunisianprayertimes.tv.ui.settings.AdvancedAction
import com.tunisianprayertimes.tv.ui.settings.SETTINGS_MENU
import com.tunisianprayertimes.tv.ui.settings.SettingsPage
import com.tunisianprayertimes.tv.ui.settings.advancedActions
import com.tunisianprayertimes.tv.ui.settings.compareTimes
import com.tunisianprayertimes.tv.ui.settings.everyText
import com.tunisianprayertimes.tv.ui.settings.gouvernoratOf
import com.tunisianprayertimes.tv.ui.settings.placeName
import com.tunisianprayertimes.tv.ui.settings.stepEveryMinutes
import com.tunisianprayertimes.tv.ui.settings.stepSlideSeconds
import com.tunisianprayertimes.tv.ui.settings.title
import com.tunisianprayertimes.tv.ui.setup.IqamahConfigsSaver
import com.tunisianprayertimes.tv.ui.setup.iqamahText
import com.tunisianprayertimes.tv.ui.setup.ramadanText
import com.tunisianprayertimes.tv.ui.setup.shiftFixed
import com.tunisianprayertimes.tv.ui.setup.switched
import com.tunisianprayertimes.tv.ui.setup.todayIqamahText
import com.tunisianprayertimes.tv.ui.setup.todayMark
import com.tunisianprayertimes.tv.ui.theme.Midad
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The admin pages' logic: the settings menu and its pages, the tables' wording, the kiosk summary. */
class AdminPagesTest {

    @Test
    fun theMenuFollowsTheBoard() {
        assertEquals(
            listOf(
                "المسجد والموقع", "الإقامة ومدة الصلاة", "رمضان والعيد", "الساعة", "الإعلانات والصور", "المظهر",
                "لوحة الإدارة من الهاتف", "التشغيل الدائم للشاشة", "متقدم", "حول التطبيق",
            ),
            SETTINGS_MENU.map { it.title },
        )
    }

    @Test
    fun everyPageOpensFromTheMenuAndBackLeadsToIt() {
        SettingsPage.entries.filter { it != SettingsPage.Menu }.forEach { page ->
            assertTrue("${page.name} belongs to a menu row", page.section in SETTINGS_MENU)
            var at = page
            repeat(3) { if (at != SettingsPage.Menu) at = at.up }
            assertEquals("${page.name} leads back to the menu", SettingsPage.Menu, at)
        }
        assertEquals(SettingsPage.Mosque, SettingsPage.MosqueName.up)
        assertEquals(SettingsPage.Mosque, SettingsPage.Location.up)
        assertEquals(SettingsPage.Advanced, SettingsPage.Reset.up)
        assertEquals(SettingsPage.Advanced, SettingsPage.BundledTexts.section)
        // The deletion of the media files asks first, on its own page under «الإعلانات والصور».
        assertEquals(SettingsPage.Media, SettingsPage.DeleteMedia.up)
        assertEquals(TvStrings.DELETE_IMAGES, SettingsPage.DeleteMedia.title)
    }

    @Test
    fun theDeletionSaysWhatGoes() {
        // Images from a USB key or the phone, and the written .txt announcements, which live in the same folder.
        listOf(TvStrings.DELETE_IMAGES_HINT, TvStrings.DELETE_IMAGES_CONFIRM).forEach { text ->
            assertTrue(text, "USB" in text && "الهاتف" in text && ".txt" in text)
        }
        assertTrue("الهاتف" in TvStrings.BACKGROUNDS_HOWTO)
    }

    @Test
    fun advancedOffersUndoAndBundledTextsOnlyWhenTheyApply() {
        assertEquals(
            listOf(AdvancedAction.USB_EXPORT, AdvancedAction.USB_READ_AGAIN, AdvancedAction.RESET, AdvancedAction.EXIT_TO_ANDROID),
            advancedActions(canUndoImport = false, customTexts = false),
        )
        assertEquals(AdvancedAction.entries.toList(), advancedActions(canUndoImport = true, customTexts = true))
        assertEquals(AdvancedAction.UNDO_IMPORT, advancedActions(canUndoImport = true, customTexts = false).first())
    }

    @Test
    fun announcementStepsStayInRange() {
        assertEquals(0, stepEveryMinutes(0, -1))
        assertEquals(20, stepEveryMinutes(15, 1))
        assertEquals(120, stepEveryMinutes(120, 1))
        assertEquals(5, stepSlideSeconds(5, -1))
        assertEquals(15, stepSlideSeconds(10, 1))
        assertEquals(60, stepSlideSeconds(60, 1))
        assertEquals(TvStrings.ANNOUNCEMENTS_EVERY_OFF, everyText(0))
    }

    @Test
    fun theIntervalAgreesWithItsNumber() {
        assertEquals("كل دقيقة", everyText(1))
        assertEquals("كل دقيقتين", everyText(2))
        // The plural from 3 to 10, the singular from 11: what one press of − from 15 shows, then two.
        assertEquals("كل 5 دقائق", everyText(5))
        assertEquals("كل 10 دقائق", everyText(10))
        assertEquals("كل 15 دقيقة", everyText(15))
        assertEquals("كل 120 دقيقة", everyText(120))
    }

    @Test
    fun theKioskFocusLeavesAnActionThatIsGone() {
        val before = listOf(KioskAction.GRANT_OVERLAY, KioskAction.HOME_MODE, KioskAction.BACK)
        val after = listOf(KioskAction.HOME_MODE, KioskAction.BACK)
        // The overlay granted: the focus goes to «رجوع», not to «جعل التطبيق الشاشة الرئيسية» in its place.
        assertTrue(focusedActionGone(KioskAction.GRANT_OVERLAY, after))
        assertEquals(KioskAction.BACK, after.last())
        // An action that stays keeps the focus; so does a row of the panel, and a page not yet focused.
        assertFalse(focusedActionGone(KioskAction.HOME_MODE, after))
        assertFalse(focusedActionGone(KioskAction.HOME_MODE, before))
        assertFalse(focusedActionGone(null, after))
    }

    @Test
    fun placesAndTheTimesBeforeAMove() {
        val marsa = Delegation(11, "La Marsa", "المرسى", "La Marsa")
        val tunis = Gouvernorat(1, "Tunis", "تونس", "Tunis", listOf(marsa))
        assertEquals(tunis, gouvernoratOf(listOf(tunis), 11))
        assertNull(gouvernoratOf(listOf(tunis), 99))
        assertEquals("المرسى — تونس", placeName("المرسى", tunis))
        assertEquals("المرسى", placeName("المرسى", null))
        assertEquals(TvStrings.NOT_SET, placeName("", null))

        val day = DayPrayerTimes(
            day = 29,
            fajr = PrayerTime(Prayer.FAJR, 5, 1),
            shurukHour = 6, shurukMinute = 28,
            dhuhr = PrayerTime(Prayer.DHUHR, 12, 10),
            asr = PrayerTime(Prayer.ASR, 15, 35),
            maghrib = PrayerTime(Prayer.MAGHRIB, 18, 2),
            isha = PrayerTime(Prayer.ISHA, 19, 20),
        )
        val rows = compareTimes(before = day, after = null)
        assertEquals(5, rows.size)
        assertEquals(listOf(TvStrings.FAJR, "05:01", "—"), rows.first())
        assertEquals(listOf(TvStrings.ISHA, "19:20", "—"), rows.last())
    }

    @Test
    fun theIqamahAsTheTablesSayIt() {
        assertEquals("بعد الأذان 15 د", iqamahText(IqamahConfig(delayMinutes = 15)))
        assertEquals("بعد الشروق 20 د", iqamahText(IqamahConfig(delayMinutes = 20), afterSunrise = true))
        assertEquals("الساعة 19:40", iqamahText(IqamahConfig(mode = IqamahMode.FIXED_TIME, fixedHour = 19, fixedMinute = 40)))
        // A fixed mode whose time was never set counts from the adhan, as the schedule does.
        assertEquals("بعد الأذان 10 د", iqamahText(IqamahConfig(mode = IqamahMode.FIXED_TIME)))

        val pastMidnight = IqamahConfig(mode = IqamahMode.FIXED_TIME, fixedHour = 23, fixedMinute = 59).shiftFixed(1)
        assertEquals(0 to 0, pastMidnight.fixedHour to pastMidnight.fixedMinute)
        assertEquals(IqamahMode.FIXED_TIME, pastMidnight.mode)
    }

    @Test
    fun theRemoteSwitchesARuleBetweenMinutesAndAFixedTime() {
        // A winter fixed time goes back to the minutes it had, and returns as it was.
        val winter = IqamahConfig(mode = IqamahMode.FIXED_TIME, delayMinutes = 15, fixedHour = 19, fixedMinute = 30)
        val minutes = winter.switched(LocalTime.of(21, 45))!!
        assertEquals("بعد الأذان 15 د", iqamahText(minutes))
        assertEquals(winter, minutes.switched(LocalTime.of(21, 45)))
        // Never fixed before: it starts where the minutes put it after today's adhan.
        assertEquals("الساعة 20:00", iqamahText(IqamahConfig(delayMinutes = 15).switched(LocalTime.of(19, 45))!!))
        // Nothing to start from (an Eid off its day): not offered.
        assertNull(IqamahConfig(delayMinutes = 30).switched(null))
    }

    @Test
    fun theIqamahPageSaysWhatTheWallUsesToday() {
        val adhan = LocalDateTime.of(2027, 3, 1, 19, 45)
        fun event(adjusted: Boolean = false, ramadan: Boolean = false) = PrayerEvent(
            Prayer.ISHA, adhan, adhan.plusMinutes(3), adhan.plusMinutes(20), adhan.plusMinutes(95), adhan.plusMinutes(105), iqamahAdjusted = adjusted, ramadanSettings = ramadan,
        )
        assertEquals("20:05", todayIqamahText(event()))
        assertEquals("—", todayIqamahText(null))
        assertNull(todayMark(event()))
        assertEquals(TvStrings.RAMADAN, todayMark(event(ramadan = true)))
        assertEquals(TvStrings.IQAMAH_ADJUSTED, todayMark(event(adjusted = true, ramadan = true)))

        assertEquals(
            "في رمضان: الإقامة بعد الأذان 20 د · مدة الصلاة 75 د",
            ramadanText(PrayerOverride(IqamahRule.AfterAdhan(20), 75)),
        )
        assertEquals("في رمضان: مدة الصلاة 75 د", ramadanText(PrayerOverride(salahMinutes = 75)))
        assertEquals("في رمضان: الإقامة الساعة 20:30", ramadanText(PrayerOverride(IqamahRule.FixedTime(LocalTime.of(20, 30)))))
    }

    @Test
    fun onboardingsIqamahTableSurvivesARecreation() {
        val configs = PrefsManager.EDITABLE.associateWith { IqamahConfig(delayMinutes = 12) } +
            // A neighbourhood masjid: no Jumu'a, and a 30-minute khutba without the dua kept for when it is held again.
            (Prayer.JOMOAA to IqamahConfig(IqamahMode.FIXED_TIME, 15, 13, 5, 20, held = false, khutbaMinutes = 30, adhanDua = false)) +
            (Prayer.AID_FITR to IqamahConfig(delayMinutes = 30, held = false))
        val saved = with(IqamahConfigsSaver) { SaverScope { true }.save(configs) }!!
        assertEquals(configs, IqamahConfigsSaver.restore(saved))
    }

    @Test
    fun theKioskSummaryPutsProblemsFirst() {
        val rows = listOf(
            HealthRow(HealthLevel.GOOD, "a"), HealthRow(HealthLevel.WARNING, "b"), HealthRow(HealthLevel.BAD, "c"),
            HealthRow(HealthLevel.INFO, "d"), HealthRow(HealthLevel.WARNING, "e"),
        )
        assertEquals(listOf("c", "b", "e", "d", "a"), rows.worstFirst().map { it.text })
        assertEquals("مشكلة واحدة · تنبيهان", healthSummary(rows))
        assertEquals(TvStrings.KIOSK_ALL_GOOD, healthSummary(listOf(HealthRow(HealthLevel.GOOD, "a"), HealthRow(HealthLevel.INFO, "b"))))
    }

    @Test
    fun onlyAWarningIsGold() {
        assertEquals(Midad.Gold, HealthLevel.WARNING.color)
        assertEquals(Midad.Alert, HealthLevel.BAD.color)
        assertEquals(Midad.Muted, HealthLevel.INFO.color)
        assertNotEquals(Midad.Gold, HealthLevel.GOOD.color)
    }
}

package com.tunisianprayertimes.tv.ui.usb

import com.tunisianprayertimes.DayPrayerTimes
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerTime
import com.tunisianprayertimes.mosque.FlowTiming
import com.tunisianprayertimes.mosque.MosqueProfile
import com.tunisianprayertimes.mosque.MosqueSchedule
import com.tunisianprayertimes.mosque.MosqueSettingsFile
import com.tunisianprayertimes.mosque.MosqueSettingsFile.ContentList
import com.tunisianprayertimes.mosque.MosqueSettingsFile.FormulaField
import com.tunisianprayertimes.mosque.MosqueSettingsFile.ParseResult
import com.tunisianprayertimes.mosque.MosqueSettingsFile.ProfileField
import com.tunisianprayertimes.tv.ui.TvStrings
import com.tunisianprayertimes.tv.ui.settings.everyText
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

/**
 * What a settings file would change, or its mistakes, in lines an admin reads: on the TV and on the
 * phone page. Values are said as the settings pages say them («بعد الأذان 15 د», «كل 15 دقيقة»).
 */
object SettingsChangeLines {

    /**
     * What a preview is read against: [date], and today's adhan of each prayer ([anchors]; sunrise for
     * the Eids), to say when a fixed iqamah will not be used today. [times] are today's times on the
     * wall, and [timesWith] computes them for the place and prayer-time values a file leaves (null when
     * unknown): a file that changes them is then read against its own times, and says how today's move.
     */
    data class Today(
        val date: LocalDate,
        val anchors: Map<Prayer, LocalTime> = emptyMap(),
        val times: DayPrayerTimes? = null,
        val timesWith: ((MosqueProfile) -> DayPrayerTimes?)? = null,
    ) {
        /** Today as the file would make it, when it changes the place or the values and they can be computed. */
        internal fun after(result: ParseResult.Success): Today? {
            val moved = result.formulaChanges.isNotEmpty() || result.profileChanges.any { it.field == ProfileField.DELEGATION }
            if (!moved) return null
            val times = timesWith?.let { runCatching { it(result.profile) }.getOrNull() } ?: return null
            return of(date, times, timesWith)
        }

        companion object {
            fun of(date: LocalDate, times: DayPrayerTimes?, timesWith: ((MosqueProfile) -> DayPrayerTimes?)? = null): Today {
                if (times == null) return Today(date, timesWith = timesWith)
                val sunrise = LocalTime.of(times.shurukHour, times.shurukMinute)
                fun at(time: PrayerTime) = LocalTime.of(time.hour, time.minute)
                return Today(date, mapOf(
                    Prayer.FAJR to at(times.fajr), Prayer.DHUHR to at(times.dhuhr), Prayer.JOMOAA to at(times.dhuhr),
                    Prayer.ASR to at(times.asr), Prayer.MAGHRIB to at(times.maghrib), Prayer.ISHA to at(times.isha),
                    Prayer.AID_FITR to sunrise, Prayer.AID_ADHA to sunrise,
                ), times, timesWith)
            }
        }
    }

    fun of(result: ParseResult.Success, today: Today? = null): List<String> {
        // A fixed iqamah is checked against the times the file leaves, not the wall's.
        val after = today?.after(result)
        val lines = result.profileChanges.map(::profileLine) + formulaLines(result, today, after) +
            result.changes.map { changeLine(it, after ?: today, result) } +
            result.dateChanges.map(::dateLine) + result.contentChanges.flatMap(::contentLines) + expired(result, today)
        // A hostile file could list thousands of years: the screen shows enough to judge it.
        return if (lines.size <= MAX_LINES) lines else lines.take(MAX_LINES) + TvStrings.moreChanges(lines.size - MAX_LINES)
    }

    fun of(result: ParseResult, today: Today? = null): List<String> = when (result) {
        is ParseResult.Success -> of(result, today)
        is ParseResult.Failure -> result.errors.map { it.message } + listOfNotNull(TvStrings.moreErrors(result.more).takeIf { result.more > 0 })
    }

    private fun changeLine(change: MosqueSettingsFile.Change, today: Today?, result: ParseResult.Success): String {
        val eid = change.prayer in MosqueSchedule.EID
        val prayer = MosqueSettingsFile.arabicName(change.prayer) + if (change.ramadan) " (${TvStrings.RAMADAN})" else ""
        // «الجمعة: تقام ← لا تقام».
        fun held(value: String) = if (value.toBoolean()) TvStrings.HELD else TvStrings.NOT_HELD
        val field = when (change.field) {
            MosqueSettingsFile.Field.IQAMAH -> TvStrings.IQAMAH_LABEL
            MosqueSettingsFile.Field.DURATION -> TvStrings.DURATION_LABEL
            MosqueSettingsFile.Field.HELD -> return "$prayer: ${held(change.before)} ← ${held(change.after)}"
            MosqueSettingsFile.Field.KHUTBA -> return "$prayer · ${TvStrings.KHUTBA_LENGTH}: " +
                "${TvStrings.khutbaLength(change.before.toInt())} ← ${TvStrings.khutbaLength(change.after.toInt())}"
            // «الدعاء بعد أذان الجمعة: نعم ← لا».
            MosqueSettingsFile.Field.DUA -> return "${TvStrings.JUMUA_ADHAN_DUA_CHANGE}: ${yesNo(change.before)} ← ${yesNo(change.after)}"
        }
        fun show(value: String): String = when {
            value == "—" -> TvStrings.AS_USUAL
            change.field == MosqueSettingsFile.Field.DURATION -> value.toIntOrNull()?.let(TvStrings::minutesShort) ?: value
            else -> value.removePrefix("+").toIntOrNull()?.let { if (eid) TvStrings.iqamahAfterSunrise(it) else TvStrings.iqamahAfterAdhan(it) }
                ?: fixedTime(value)?.let { TvStrings.atTime(it.hour, it.minute) } ?: value
        }
        val line = "$prayer · $field: ${show(change.before)} ← ${show(change.after)}"
        val warning = if (change.field == MosqueSettingsFile.Field.IQAMAH && !change.ramadan) notToday(change.prayer, change.after, today, result) else null
        return if (warning != null) "$line ($warning)" else line
    }

    /**
     * The screen puts a fixed iqamah that is not 1 to 90 minutes after today's adhan (sunrise for an
     * Eid) at the mosque's own minutes instead ([MosqueSchedule.fallbackMinutes] of the schedule the
     * file makes, as PrayerFlow does), and an iqamah before the end of the adhan screen (the minutes
     * the file leaves, [com.tunisianprayertimes.mosque.DisplayOptions.adhanScreenMinutes]) and the dua
     * after it ([FlowTiming.ADHAN_DUA_MINUTES], unless the file leaves Jumu'a without it) at their end:
     * the admin hears it here, not on the wall.
     */
    private fun notToday(prayer: Prayer, after: String, today: Today?, result: ParseResult.Success): String? {
        val anchor = today?.anchors?.get(prayer) ?: return null
        val eid = prayer in MosqueSchedule.EID
        // The Eid prayer has no adhan screen and no dua: its minutes count from sunrise.
        val dua = result.schedule.showsAdhanDua(prayer)
        val screen = if (eid) 0 else (result.profile.display.adhanScreenMinutes ?: FlowTiming.DEFAULT_ADHAN_SCREEN_MINUTES)
            .coerceIn(FlowTiming.ADHAN_SCREEN_MINUTES) + if (dua) FlowTiming.ADHAN_DUA_MINUTES else 0
        val time = fixedTime(after)
        if (time == null) {
            val minutes = after.removePrefix("+").toIntOrNull() ?: return null
            return if (minutes < screen) waits(anchor, screen, dua) else null
        }
        val window = MosqueSchedule.IQAMAH_MINUTES.first.toLong()..MosqueSchedule.IQAMAH_MINUTES.last.toLong()
        val set = Duration.between(anchor, time).toMinutes()
        if (set in window) return if (set < screen) waits(anchor, screen, dua) else null
        val minutes = maxOf(result.schedule.fallbackMinutes(prayer), screen)
        return if (eid) {
            TvStrings.notTodayAfterSunrise(TvStrings.hm(anchor), TvStrings.iqamahAfterSunrise(minutes))
        } else {
            TvStrings.notTodayAfterAdhan(TvStrings.hm(anchor), TvStrings.iqamahAfterAdhan(minutes))
        }
    }

    /** An iqamah before the end of the adhan screen and the dua ([screen] minutes) waits for them, as PrayerFlow does. */
    private fun waits(anchor: LocalTime, screen: Int, dua: Boolean): String =
        TvStrings.waitsForAdhanScreen(TvStrings.hm(anchor), TvStrings.hm(anchor.plusMinutes(screen.toLong())), dua)

    private fun yesNo(value: String): String = if (value.toBoolean()) TvStrings.YES else TvStrings.NO

    private fun fixedTime(value: String): LocalTime? = runCatching { LocalTime.parse(value) }.getOrNull()

    private fun profileLine(change: MosqueSettingsFile.ProfileChange): String {
        val field = when (change.field) {
            ProfileField.NAME -> TvStrings.MOSQUE_NAME_LABEL
            ProfileField.DELEGATION -> TvStrings.SETTINGS_LOCATION
            ProfileField.THEME -> TvStrings.THEME_LABEL
            ProfileField.WEATHER -> TvStrings.WEATHER_ENABLED
            ProfileField.BACKGROUNDS -> TvStrings.CUSTOM_BG_ENABLED
            ProfileField.ANNOUNCEMENTS -> TvStrings.ANNOUNCEMENTS_ENABLED
            ProfileField.SLIDE_SECONDS -> TvStrings.ANNOUNCEMENT_INTERVAL
            ProfileField.ANNOUNCEMENTS_EVERY -> TvStrings.ANNOUNCEMENTS_BETWEEN
            ProfileField.NIGHT_SCREEN -> TvStrings.NIGHT_SCREEN
            ProfileField.ADHAN_SCREEN -> TvStrings.ADHAN_SCREEN_LENGTH
        }
        // The options come as in the file ("true", "15"): said as the settings pages say them.
        fun show(value: String): String = when (change.field) {
            ProfileField.WEATHER, ProfileField.BACKGROUNDS, ProfileField.ANNOUNCEMENTS, ProfileField.NIGHT_SCREEN -> when (value) {
                "true" -> TvStrings.ON
                "false" -> TvStrings.OFF
                else -> value
            }
            ProfileField.SLIDE_SECONDS -> value.toIntOrNull()?.let(TvStrings::secondsShort) ?: value
            ProfileField.ADHAN_SCREEN -> value.toIntOrNull()?.let(TvStrings::minutesShort) ?: value
            ProfileField.ANNOUNCEMENTS_EVERY -> value.toIntOrNull()?.let(::everyText) ?: value
            else -> value
        }
        return "$field: ${show(change.before)} ← ${show(change.after)}"
    }

    /**
     * The prayer-time values the file changes («زاوية الفجر: 18° ← 16.5°»), a word when they return to
     * INM's official ones, then today's times that move («الفجر اليوم: 04:47 ← 04:57») when both are known.
     */
    private fun formulaLines(result: ParseResult.Success, today: Today?, after: Today?): List<String> {
        if (result.formulaChanges.isEmpty()) return emptyList()
        return result.formulaChanges.map(::formulaLine) +
            listOfNotNull(TvStrings.OFFICIAL_TIMES_BACK.takeIf { result.formula.isOfficial }) +
            todayTimeLines(today?.times, after?.times)
    }

    private fun formulaLine(change: MosqueSettingsFile.FormulaChange): String {
        val field = when (change.field) {
            FormulaField.FAJR_ANGLE -> TvStrings.FAJR_ANGLE
            FormulaField.ISHA_ANGLE -> TvStrings.ISHA_ANGLE
            FormulaField.ASR_SHADOW -> TvStrings.ASR_SHADOW
            FormulaField.DHUHR_MINUTES -> TvStrings.DHUHR_AFTER_NOON
            FormulaField.MAGHRIB_MINUTES -> TvStrings.MAGHRIB_AFTER_SUNSET
            FormulaField.ELEVATION -> TvStrings.ELEVATION_COUNTED
            FormulaField.ADJUSTMENT -> change.prayer?.let(TvStrings::adjustmentOf).orEmpty()
        }
        // The values come as in the file ("17.5", "2", "true", "+2").
        fun show(value: String): String = when (change.field) {
            FormulaField.FAJR_ANGLE, FormulaField.ISHA_ANGLE -> TvStrings.degrees(value)
            FormulaField.ASR_SHADOW -> when (value) {
                "1" -> TvStrings.ASR_ONE_SHADOW
                "2" -> TvStrings.ASR_TWO_SHADOWS
                else -> value
            }
            FormulaField.DHUHR_MINUTES, FormulaField.MAGHRIB_MINUTES -> value.toIntOrNull()?.let(TvStrings::minutesShort) ?: value
            FormulaField.ELEVATION -> when (value) {
                "true" -> TvStrings.YES
                "false" -> TvStrings.NO
                else -> value
            }
            FormulaField.ADJUSTMENT -> value.removePrefix("+").toIntOrNull()?.let(TvStrings::signedMinutes) ?: value
        }
        return "$field: ${show(change.before)} ← ${show(change.after)}"
    }

    /** Today's six times that differ between [before] and [after]. */
    private fun todayTimeLines(before: DayPrayerTimes?, after: DayPrayerTimes?): List<String> {
        if (before == null || after == null) return emptyList()
        fun at(time: PrayerTime) = LocalTime.of(time.hour, time.minute)
        fun sunrise(times: DayPrayerTimes) = LocalTime.of(times.shurukHour, times.shurukMinute)
        return listOf(
            Triple(TvStrings.prayerName(Prayer.FAJR), at(before.fajr), at(after.fajr)),
            Triple(TvStrings.SUNRISE, sunrise(before), sunrise(after)),
            Triple(TvStrings.prayerName(Prayer.DHUHR), at(before.dhuhr), at(after.dhuhr)),
            Triple(TvStrings.prayerName(Prayer.ASR), at(before.asr), at(after.asr)),
            Triple(TvStrings.prayerName(Prayer.MAGHRIB), at(before.maghrib), at(after.maghrib)),
            Triple(TvStrings.prayerName(Prayer.ISHA), at(before.isha), at(after.isha)),
        ).filter { (_, old, new) -> old != new }
            .map { (name, old, new) -> "${TvStrings.todayTime(name)}: ${TvStrings.hm(old)} ← ${TvStrings.hm(new)}" }
    }

    /**
     * The list's summary before and after, then which texts come, go, or change (count, source,
     * wording, dates), by title or first words, or that only their order changes.
     */
    private fun contentLines(change: MosqueSettingsFile.ContentChange): List<String> {
        val list = listName(change.list)
        return listOfNotNull(
            "$list: ${change.before} ← ${change.after}",
            "$list · ${TvStrings.TEXTS_ADDED}: ${names(change.added)}".takeIf { change.added.isNotEmpty() },
            "$list · ${TvStrings.TEXTS_REMOVED}: ${names(change.removed)}".takeIf { change.removed.isNotEmpty() },
            "$list · ${TvStrings.TEXTS_RECOUNTED}: ${names(change.recounted)}".takeIf { change.recounted.isNotEmpty() },
            "$list · ${TvStrings.TEXTS_RESOURCED}: ${names(change.resourced)}".takeIf { change.resourced.isNotEmpty() },
            "$list · ${TvStrings.TEXTS_REWORDED}: ${names(change.reworded)}".takeIf { change.reworded.isNotEmpty() },
            "$list · ${TvStrings.ANNOUNCEMENTS_REDATED}: ${names(change.redated)}".takeIf { change.redated.isNotEmpty() },
            "$list · ${TvStrings.TEXTS_REORDERED}".takeIf { change.reordered },
        )
    }

    /** The file's announcements whose last day has passed: they would never be shown. */
    private fun expired(result: ParseResult.Success, today: Today?): List<String> {
        if (today == null || result.contentChanges.none { it.list == ContentList.ANNOUNCEMENTS }) return emptyList()
        val past = result.announcements.filter { it.until?.isBefore(today.date) == true }.map { MosqueSettingsFile.textName(it.text) }
        return listOfNotNull("${listName(ContentList.ANNOUNCEMENTS)} · ${TvStrings.ANNOUNCEMENTS_EXPIRED}: ${names(past)}".takeIf { past.isNotEmpty() })
    }

    private fun listName(list: ContentList): String = when (list) {
        ContentList.AFTER_SALAH -> TvStrings.AFTER_SALAH_TEXTS
        ContentList.TICKER -> TvStrings.TICKER_TEXTS
        ContentList.ANNOUNCEMENTS -> TvStrings.TEXT_ANNOUNCEMENTS
    }

    /** The texts by name, as the wall shows them: a «−3°» in an announcement's first words keeps its sign on the left. */
    private fun names(texts: List<String>) =
        texts.take(MAX_NAMES).joinToString("، ") { TvStrings.mosqueText(it) } + if (texts.size > MAX_NAMES) " ${TvStrings.andOthers(texts.size - MAX_NAMES)}" else ""

    private const val MAX_NAMES = 4
    private const val MAX_LINES = 100

    private fun dateLine(change: MosqueSettingsFile.DateChange): String {
        // «10 مارس 2027»: an ISO date would turn around between Arabic words.
        fun show(date: java.time.LocalDate?) = date?.let { TvStrings.gregorianDate(it, withWeekday = false) } ?: TvStrings.AUTOMATIC
        return "${MosqueSettingsFile.dateEventName(change.event)} ${change.hijriYear}: ${show(change.before)} ← ${show(change.after)}"
    }
}

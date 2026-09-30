package com.tunisianprayertimes.tv.ui.usb

import com.tunisianprayertimes.DayPrayerTimes
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerTime
import com.tunisianprayertimes.mosque.FlowTiming
import com.tunisianprayertimes.mosque.MosqueSchedule
import com.tunisianprayertimes.mosque.MosqueSettingsFile
import com.tunisianprayertimes.mosque.MosqueSettingsFile.ContentList
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
     * the Eids), to say when a fixed iqamah will not be used today.
     */
    data class Today(val date: LocalDate, val anchors: Map<Prayer, LocalTime> = emptyMap()) {
        companion object {
            fun of(date: LocalDate, times: DayPrayerTimes?): Today {
                if (times == null) return Today(date)
                val sunrise = LocalTime.of(times.shurukHour, times.shurukMinute)
                fun at(time: PrayerTime) = LocalTime.of(time.hour, time.minute)
                return Today(date, mapOf(
                    Prayer.FAJR to at(times.fajr), Prayer.DHUHR to at(times.dhuhr), Prayer.JOMOAA to at(times.dhuhr),
                    Prayer.ASR to at(times.asr), Prayer.MAGHRIB to at(times.maghrib), Prayer.ISHA to at(times.isha),
                    Prayer.AID_FITR to sunrise, Prayer.AID_ADHA to sunrise,
                ))
            }
        }
    }

    fun of(result: ParseResult.Success, today: Today? = null): List<String> {
        val lines = result.profileChanges.map(::profileLine) + result.changes.map { changeLine(it, today, result) } +
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
     * the file leaves, [com.tunisianprayertimes.mosque.DisplayOptions.adhanScreenMinutes]) at its end:
     * the admin hears it here, not on the wall.
     */
    private fun notToday(prayer: Prayer, after: String, today: Today?, result: ParseResult.Success): String? {
        val anchor = today?.anchors?.get(prayer) ?: return null
        val eid = prayer in MosqueSchedule.EID
        // The Eid prayer has no adhan screen: its minutes count from sunrise.
        val screen = if (eid) 0 else (result.profile.display.adhanScreenMinutes ?: FlowTiming.DEFAULT_ADHAN_SCREEN_MINUTES)
            .coerceIn(FlowTiming.ADHAN_SCREEN_MINUTES)
        val time = fixedTime(after)
        if (time == null) {
            val minutes = after.removePrefix("+").toIntOrNull() ?: return null
            return if (minutes < screen) waits(anchor, screen) else null
        }
        val window = MosqueSchedule.IQAMAH_MINUTES.first.toLong()..MosqueSchedule.IQAMAH_MINUTES.last.toLong()
        val set = Duration.between(anchor, time).toMinutes()
        if (set in window) return if (set < screen) waits(anchor, screen) else null
        val minutes = maxOf(result.schedule.fallbackMinutes(prayer), screen)
        return if (eid) {
            TvStrings.notTodayAfterSunrise(TvStrings.hm(anchor), TvStrings.iqamahAfterSunrise(minutes))
        } else {
            TvStrings.notTodayAfterAdhan(TvStrings.hm(anchor), TvStrings.iqamahAfterAdhan(minutes))
        }
    }

    /** An iqamah before the end of the adhan screen waits for it, as PrayerFlow does. */
    private fun waits(anchor: LocalTime, screen: Int): String =
        TvStrings.waitsForAdhanScreen(TvStrings.hm(anchor), TvStrings.hm(anchor.plusMinutes(screen.toLong())))

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

    private fun names(texts: List<String>) =
        texts.take(MAX_NAMES).joinToString("، ") + if (texts.size > MAX_NAMES) " ${TvStrings.andOthers(texts.size - MAX_NAMES)}" else ""

    private const val MAX_NAMES = 4
    private const val MAX_LINES = 100

    private fun dateLine(change: MosqueSettingsFile.DateChange): String {
        // «10 مارس 2027»: an ISO date would turn around between Arabic words.
        fun show(date: java.time.LocalDate?) = date?.let { TvStrings.gregorianDate(it, withWeekday = false) } ?: TvStrings.AUTOMATIC
        return "${MosqueSettingsFile.dateEventName(change.event)} ${change.hijriYear}: ${show(change.before)} ← ${show(change.after)}"
    }
}

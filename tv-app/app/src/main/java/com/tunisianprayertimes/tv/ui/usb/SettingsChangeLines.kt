package com.tunisianprayertimes.tv.ui.usb

import com.tunisianprayertimes.mosque.MosqueSettingsFile
import com.tunisianprayertimes.mosque.MosqueSettingsFile.ParseResult
import com.tunisianprayertimes.tv.ui.TvStrings

/** What a settings file would change, or its mistakes, in lines an admin reads: on the TV and on the phone page. */
object SettingsChangeLines {

    fun of(result: ParseResult.Success): List<String> =
        result.profileChanges.map(::profileLine) + result.changes.map(::changeLine) +
            result.dateChanges.map(::dateLine) + result.contentChanges.map(::contentLine)

    fun of(result: ParseResult): List<String> = when (result) {
        is ParseResult.Success -> of(result)
        is ParseResult.Failure -> result.errors.map { it.message }
    }

    private fun changeLine(change: MosqueSettingsFile.Change): String {
        val field = when (change.field) {
            MosqueSettingsFile.Field.IQAMAH -> TvStrings.IQAMAH_LABEL
            MosqueSettingsFile.Field.DURATION -> TvStrings.DURATION_LABEL
        }
        fun show(value: String) = if (value.contains(':') || value == "—") value else "$value ${TvStrings.MINUTES_SUFFIX}"
        val prayer = MosqueSettingsFile.arabicName(change.prayer) + if (change.ramadan) " (${TvStrings.RAMADAN})" else ""
        return "$prayer · $field: ${show(change.before)} ← ${show(change.after)}"
    }

    private fun profileLine(change: MosqueSettingsFile.ProfileChange): String {
        val field = when (change.field) {
            MosqueSettingsFile.ProfileField.NAME -> TvStrings.MOSQUE_NAME_LABEL
            MosqueSettingsFile.ProfileField.DELEGATION -> TvStrings.SETTINGS_LOCATION
            MosqueSettingsFile.ProfileField.THEME -> TvStrings.THEME_LABEL
            MosqueSettingsFile.ProfileField.WEATHER -> TvStrings.WEATHER_ENABLED
            MosqueSettingsFile.ProfileField.BACKGROUNDS -> TvStrings.CUSTOM_BG_ENABLED
            MosqueSettingsFile.ProfileField.ANNOUNCEMENTS -> TvStrings.ANNOUNCEMENTS_ENABLED
            MosqueSettingsFile.ProfileField.SLIDE_SECONDS -> TvStrings.ANNOUNCEMENT_INTERVAL
            MosqueSettingsFile.ProfileField.ANNOUNCEMENTS_EVERY -> TvStrings.ANNOUNCEMENTS_EVERY
        }
        return "$field: ${change.before} ← ${change.after}"
    }

    private fun contentLine(change: MosqueSettingsFile.ContentChange): String {
        val list = when (change.list) {
            MosqueSettingsFile.ContentList.AFTER_SALAH -> TvStrings.AFTER_SALAH_TEXTS
            MosqueSettingsFile.ContentList.TICKER -> TvStrings.TICKER_TEXTS
            MosqueSettingsFile.ContentList.ANNOUNCEMENTS -> TvStrings.TEXT_ANNOUNCEMENTS
        }
        return "$list: ${change.before} ← ${change.after}"
    }

    private fun dateLine(change: MosqueSettingsFile.DateChange): String {
        fun show(date: java.time.LocalDate?) = date?.toString() ?: TvStrings.AUTOMATIC
        return "${MosqueSettingsFile.dateEventName(change.event)} ${change.hijriYear}: ${show(change.before)} ← ${show(change.after)}"
    }
}

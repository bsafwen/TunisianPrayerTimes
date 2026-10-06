package com.tunisianprayertimes.tv.ui.theme

import androidx.compose.runtime.Immutable

/**
 * A look the admin can pick. Both keep the «مداد» palette and the same layout; they differ in what is
 * behind the timetable: the sky of the day's prayer times, or the plain ground.
 */
@Immutable
data class DisplayTheme(
    val id: String,
    val nameAr: String,
    /** One line for the settings page and the phone. */
    val description: String,
    /** Whether the top of the screen shows the sky of the prayer times ([Sky]). */
    val sky: Boolean,
)

object ThemeRegistry {

    val HORIZON = DisplayTheme("horizon", "أفق", "سماء تتبع أوقات الصلاة، تُحسب على الجهاز دون إنترنت", sky = true)
    val MIDAD = DisplayTheme("midad", "مداد", "أرضية داكنة ثابتة دون سماء", sky = false)

    val builtInThemes: List<DisplayTheme> = listOf(HORIZON, MIDAD)

    /** The theme with [id]; the default one for an unknown id. */
    fun findById(id: String): DisplayTheme = builtInThemes.find { it.id == id } ?: builtInThemes.first()
}

package com.tunisianprayertimes.ui

object TestTags {
    // MainScreen
    const val STATUS_CARD = "status_card"
    const val PERMISSION_BANNER = "permission_banner"
    const val BATTERY_BANNER = "battery_banner"
    const val LOCATION_PICKER = "location_picker"
    const val PRAYER_SETTINGS = "prayer_settings"
    const val AUTO_SILENCE_CARD = "auto_silence_card"
    const val AUTO_SILENCE_SWITCH = "auto_silence_switch"
    const val MANUAL_SILENCE_BUTTON = "manual_silence_button"
    const val MANUAL_SILENCE_MODE_UNTIL = "manual_silence_mode_until"
    const val MANUAL_SILENCE_MODE_DURATION = "manual_silence_mode_duration"
    const val MANUAL_SILENCE_DURATION_INPUT = "manual_silence_duration_input"
    const val INFO_TEXT = "info_text"
    const val DATE_LABEL = "date_label"
    const val OVERLAP_WARNING = "overlap_warning"

    // Per-prayer duration input tags (for UI testing)
    fun durationInput(prayer: String) = "duration_input_$prayer"

    // OnboardingScreen
    const val ONBOARDING_NEXT = "onboarding_next"
    const val ONBOARDING_PREV = "onboarding_prev"
    const val ONBOARDING_START = "onboarding_start"
    const val ONBOARDING_PROGRESS = "onboarding_progress"
}

package com.tunisianprayertimes.mosque

import java.time.LocalDate

/**
 * A mosque's written announcement (a lesson, a collection, a funeral prayer), shown after the
 * adhkar from [from] until [until] included; without dates it is always shown.
 */
data class TextAnnouncement(val text: String, val from: LocalDate? = null, val until: LocalDate? = null) {
    fun isShownOn(date: LocalDate): Boolean =
        (from == null || !date.isBefore(from)) && (until == null || !date.isAfter(until))

    companion object {
        const val MAX_LENGTH = 300
        const val MAX_COUNT = 30
    }
}

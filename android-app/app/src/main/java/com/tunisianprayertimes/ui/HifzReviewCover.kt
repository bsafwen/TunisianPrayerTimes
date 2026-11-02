package com.tunisianprayertimes.ui

import com.tunisianprayertimes.quran.QuranVerseReference

/** Revealing one answer stops before the next question on this page, keeping its continuation hidden too. */
internal fun hifzReviewHiddenVerses(
    page: List<QuranVerseReference>,
    asked: QuranVerseReference,
    revealed: Boolean,
    upcoming: Set<QuranVerseReference>,
): Set<QuranVerseReference> {
    val coverFrom = if (!revealed) asked else page.filter { it > asked && it in upcoming }.minOrNull()
    return page.filter { it in upcoming || (coverFrom != null && it >= coverFrom) }.toSet()
}

package com.tunisianprayertimes.tv.ui.kiosk

import com.tunisianprayertimes.mosque.MosqueSchedule
import com.tunisianprayertimes.mosque.MosqueSettingsFile
import com.tunisianprayertimes.mosque.PrayerEvent
import com.tunisianprayertimes.tv.ui.TvStrings
import java.time.LocalDate

/**
 * Today's iqamahs the screen moved from their setting (a fixed time that does not suit today's adhan,
 * or sunrise for an Eid, one that would run into the next adhan, or one before the end of the adhan
 * screen, which waits for it): the wall shows the moved time without a word, so the kiosk page says it.
 */
fun movedIqamahRows(events: List<PrayerEvent>, today: LocalDate): List<HealthRow> =
    events.filter { it.iqamahAdjusted && it.adhanAt.toLocalDate() == today }.map { event ->
        val name = MosqueSettingsFile.arabicName(event.prayer)
        val time = TvStrings.hm(event.iqamahAt.toLocalTime())
        val eid = event.prayer in MosqueSchedule.EID
        val waited = !eid && event.iqamahAt == event.adhanScreenEndAt
        when {
            eid -> HealthRow(HealthLevel.WARNING, TvStrings.eidPrayerMoved(name, time), fix = TvStrings.EID_PRAYER_MOVED_FIX)
            waited -> HealthRow(HealthLevel.WARNING, TvStrings.iqamahWaitsForAdhan(name, time), fix = TvStrings.IQAMAH_WAITS_FIX)
            else -> HealthRow(HealthLevel.WARNING, TvStrings.iqamahMoved(name, time), fix = TvStrings.IQAMAH_MOVED_FIX)
        }
    }

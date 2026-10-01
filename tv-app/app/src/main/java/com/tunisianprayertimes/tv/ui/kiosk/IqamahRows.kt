package com.tunisianprayertimes.tv.ui.kiosk

import com.tunisianprayertimes.mosque.IqamahMove
import com.tunisianprayertimes.mosque.MosqueSchedule
import com.tunisianprayertimes.mosque.MosqueSettingsFile
import com.tunisianprayertimes.mosque.PrayerEvent
import com.tunisianprayertimes.tv.ui.TvStrings
import java.time.LocalDate

/**
 * Today's iqamahs the screen moved from their setting (a fixed time that does not suit today's adhan,
 * or sunrise for an Eid, one that would run into the next adhan, or one before the end of the adhan
 * screen and the dua after it, which waits for them, or for the adhan screen alone on a Friday without the
 * dua): the wall shows the moved time without a word, so the kiosk page says it.
 */
fun movedIqamahRows(events: List<PrayerEvent>, today: LocalDate): List<HealthRow> =
    events.filter { it.iqamahAdjusted && it.adhanAt.toLocalDate() == today }.map { event ->
        val name = MosqueSettingsFile.arabicName(event.prayer)
        val time = TvStrings.hm(event.iqamahAt.toLocalTime())
        val eid = event.prayer in MosqueSchedule.EID
        // The flow's reason, not the time: a stale time's fallback or a capped iqamah can end with the dua too.
        val waited = !eid && event.iqamahMove == IqamahMove.WAITED_FOR_ADHAN
        when {
            eid -> HealthRow(HealthLevel.WARNING, TvStrings.eidPrayerMoved(name, time), fix = TvStrings.EID_PRAYER_MOVED_FIX)
            waited -> {
                // A Jumu'a whose mosque left the dua out waited for the adhan screen only.
                val withDua = event.adhanDuaEndAt.isAfter(event.adhanScreenEndAt)
                HealthRow(HealthLevel.WARNING, TvStrings.iqamahWaitsForAdhan(name, time, withDua),
                    fix = if (withDua) TvStrings.IQAMAH_WAITS_FIX else TvStrings.IQAMAH_WAITS_FIX_NO_DUA)
            }
            else -> HealthRow(HealthLevel.WARNING, TvStrings.iqamahMoved(name, time), fix = TvStrings.IQAMAH_MOVED_FIX)
        }
    }

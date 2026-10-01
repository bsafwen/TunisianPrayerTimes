package com.tunisianprayertimes.tv.update

import com.tunisianprayertimes.mosque.FlowPhase
import com.tunisianprayertimes.tv.ui.TvStrings
import java.time.LocalDateTime

/**
 * Installing an update ends the app, and on some boxes shows the system's dialog: never on a prayer
 * screen, nor in the minutes before an adhan, whoever asked for it (the remote or the phone).
 */
object InstallGate {

    const val BEFORE_ADHAN_MINUTES = 15L

    private val PRAYING = setOf(FlowPhase.ADHAN, FlowPhase.ADHAN_DUA, FlowPhase.IQAMAH_COUNTDOWN, FlowPhase.KHUTBA, FlowPhase.SALAH)

    /** Why installing must wait now (Arabic), or null when it may go ahead. */
    fun refusal(phase: FlowPhase, now: LocalDateTime, nextAdhan: LocalDateTime?): String? = when {
        phase in PRAYING -> TvStrings.UPDATE_WAIT_PRAYER
        nextAdhan != null && now.plusMinutes(BEFORE_ADHAN_MINUTES).isAfter(nextAdhan) -> TvStrings.UPDATE_WAIT_ADHAN
        else -> null
    }
}

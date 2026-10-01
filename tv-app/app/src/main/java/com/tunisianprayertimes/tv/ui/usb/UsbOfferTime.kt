package com.tunisianprayertimes.tv.ui.usb

import java.time.Duration

/**
 * A key's offer left unanswered on the wall for [IDLE] waits for the admin instead: a quiet mark, and
 * the dialog when settings open. Only its time on the wall counts: a prayer screen or the clock page
 * over it holds its time, and it starts again when the wall is back (the admin who plugged the key in
 * at the adhan sees the offer after the prayer).
 */
object UsbOfferTime {

    val IDLE: Duration = Duration.ofMinutes(2)

    /** Whether the offer was left unanswered: [idle] without a key, while nothing covers it ([hidden]). */
    fun lapsed(idle: Duration, hidden: Boolean): Boolean = !hidden && idle > IDLE
}

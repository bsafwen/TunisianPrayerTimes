package com.tunisianprayertimes.tv.kiosk

/**
 * Opens settings from any remote, including the many with no Menu key: OK held for
 * [longPressMillis], OK pressed [taps] times within [tapWindowMillis], or a Menu, Settings or Info
 * key. Remotes report a held key differently (repeated key-downs, or one down and a late up), so
 * both are handled. Every other key, and a short OK, passes through untouched.
 */
class AdminEntryDetector(
    private val longPressMillis: Long = LONG_PRESS_MILLIS,
    private val taps: Int = TAPS,
    private val tapWindowMillis: Long = TAP_WINDOW_MILLIS,
) {
    /** The defaults, which the hint on the wall states ([com.tunisianprayertimes.tv.ui.TvStrings.HOLD_OK_HINT]). */
    companion object {
        const val LONG_PRESS_MILLIS = 3_000L
        const val TAPS = 5
        const val TAP_WINDOW_MILLIS = 3_000L
    }

    enum class Key { OK, MENU, OTHER }
    enum class Action { DOWN, UP }
    enum class Result { PASS, OPEN_ADMIN, CONSUME }

    private var okDownAt: Long? = null
    private var openedThisPress = false
    private val okTaps = ArrayDeque<Long>()

    fun onKey(key: Key, action: Action, eventTime: Long): Result = when (key) {
        Key.MENU -> if (action == Action.UP) Result.OPEN_ADMIN else Result.CONSUME
        Key.OTHER -> Result.PASS
        Key.OK -> onOk(action, eventTime)
    }

    /** Forgets the press and taps under way: the keys went elsewhere, and the end of that press never comes here. */
    fun reset() {
        okDownAt = null
        openedThisPress = false
        okTaps.clear()
    }

    private fun onOk(action: Action, eventTime: Long): Result {
        if (action == Action.DOWN) {
            val down = okDownAt ?: eventTime.also { okDownAt = it; openedThisPress = false }
            if (openedThisPress) return Result.CONSUME
            if (eventTime - down >= longPressMillis) {
                openedThisPress = true
                okTaps.clear()
                return Result.OPEN_ADMIN
            }
            return Result.PASS
        }
        val down = okDownAt
        okDownAt = null
        if (openedThisPress) {
            openedThisPress = false
            return Result.CONSUME
        }
        if (down != null && eventTime - down >= longPressMillis) {
            okTaps.clear()
            return Result.OPEN_ADMIN
        }
        okTaps.addLast(eventTime)
        while (okTaps.isNotEmpty() && eventTime - okTaps.first() > tapWindowMillis) okTaps.removeFirst()
        if (okTaps.size >= taps) {
            okTaps.clear()
            return Result.OPEN_ADMIN
        }
        return Result.PASS
    }
}

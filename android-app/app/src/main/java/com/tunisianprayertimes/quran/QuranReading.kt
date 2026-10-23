package com.tunisianprayertimes.quran

import android.content.Context
import android.util.Log
import com.tunisianprayertimes.adhkar.DhikrReminderScheduler
import com.tunisianprayertimes.quran.hifz.HifzReminder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The Qur'an being read in the app: the reader in front, the screen on, and a touch, a page turned or the recitation
 * playing within the last [IdleMs]. The app's own reminders wait while it is read, as they wait for a prayer: the adhkar
 * nudges ([DhikrReminderScheduler]) and the reminder of «المراجعة» ([HifzReminder]) come once the reading ends, each at
 * most once and only if still due. Nothing else waits: the alarms, the extra alarms, the awake check, the prayers'
 * silence and their times.
 *
 * Kept on disk as the time the reading ends if nothing more is done in it: the reminders' alarms may wake the app in a
 * process that never ran the reader. A reader left open on a table holds nothing back past [IdleMs].
 */
object QuranReading {
    /** How long a reading lasts after the last thing done in it. */
    const val IdleMs = 10 * 60_000L
    /** The end kept moves by at least this, so that each touch does not write it: a reading may end this much early. */
    const val StepMs = 60_000L
    private const val TAG = "QuranReading"
    private const val PREFS = "quran_reading"
    private const val UNTIL = "until"
    private val releaseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** The reader is in front in this process: a touch or a tick that comes after it left counts for nothing. */
    @Volatile private var open = false

    /** When the reading ends if nothing more is done in it; null when the Qur'an is not being read at [now]. */
    fun holdsUntil(context: Context, now: Long = System.currentTimeMillis()): Long? =
        quranReadingHolds(prefs(context).getLong(UNTIL, 0L), now)

    /** The reader came in front, the screen on: a reading begins, as with a touch. */
    fun opened(context: Context, now: Long = System.currentTimeMillis()) {
        open = true
        active(context, now)
    }

    /** Something done in the reader at [now], a touch, a page turned, the recitation playing: the reading goes on. */
    fun active(context: Context, now: Long = System.currentTimeMillis()) {
        if (!open) return
        val prefs = prefs(context)
        quranReadingRenewed(prefs.getLong(UNTIL, 0L), now)?.let { prefs.edit().putLong(UNTIL, it).apply() }
    }

    /** The reader left the screen, or the screen went off: what the reading held back comes now. */
    fun closed(context: Context) {
        if (!end(context, System.currentTimeMillis())) return
        val app = context.applicationContext
        releaseScope.launch { release(app) }
    }

    /** Ends the reading at [now]; true when it was holding the reminders back. */
    internal fun end(context: Context, now: Long): Boolean {
        open = false
        val prefs = prefs(context)
        val holding = quranReadingHolds(prefs.getLong(UNTIL, 0L), now) != null
        prefs.edit().remove(UNTIL).apply()
        return holding
    }

    /**
     * Brings what the reading held back, by each reminder's own rules: an adhkar nudge once, inside its period and while
     * its count is not done; the reminder of «المراجعة» only if its verses are still due. Call off the main thread.
     */
    internal fun release(context: Context, now: Long = System.currentTimeMillis()) {
        runCatching { DhikrReminderScheduler.refresh(context, nowMillis = now) }
            .onFailure { Log.w(TAG, "Could not release the adhkar reminders", it) }
        runCatching { HifzReminder.refresh(context) }
            .onFailure { Log.w(TAG, "Could not release the review reminder", it) }
        runCatching { com.tunisianprayertimes.remembrance.DeathReminder.refresh(context) }
            .onFailure { Log.w(TAG, "Could not release the remembrance reminder", it) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * The reading's end as kept, [until], read at [now]: the reminders wait until then. One further ahead than
 * [QuranReading.IdleMs] was kept before the clock was put back: it holds nothing.
 */
internal fun quranReadingHolds(until: Long, now: Long): Long? = until.takeIf { it > now && it - now <= QuranReading.IdleMs }

/** The end to keep for something done at [now]; null when the end [kept] already lasts about as long. */
internal fun quranReadingRenewed(kept: Long, now: Long): Long? =
    (now + QuranReading.IdleMs).takeIf { it - kept >= QuranReading.StepMs || it < kept }

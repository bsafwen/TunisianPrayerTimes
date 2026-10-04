package com.tunisianprayertimes.quran.audio

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class QuranReciter(
    val id: String,
    val name: String,
    val riwaya: String,
    internal val timingAssetPath: String,
)

data class QuranPlaybackState(
    val reciterId: String = QuranAudioController.DEFAULT_RECITER_ID,
    val surah: Int? = null,
    /** Null during the introduction, gaps and closing silence, with no inferred verse. */
    val ayah: Int? = null,
    val playing: Boolean = false,
    val loading: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val introEndMs: Long = 0L,
    val error: String? = null,
    /** The recording of [surah] is not on this device yet; playback waits, paused, for its download. */
    val notDownloaded: Boolean = false,
    /** The verse range being recited again and again, if any. */
    val repeat: QuranRepeatRange? = null,
    /** The one-based pass over [repeat] being recited; 0 without a repetition. */
    val repeatRound: Int = 0,
    /** The part of the current chapter's audio that belongs to [repeat]. */
    val repeatWindow: QuranRepeatWindow? = null,
)

/** The service owns playback; screens may disappear without interrupting the recitation. */
object QuranAudioController {
    const val DEFAULT_RECITER_ID = "hosary-qaloun"

    val reciters: List<QuranReciter> = listOf(
        QuranReciter(DEFAULT_RECITER_ID, "محمود خليل الحصري", "قالون عن نافع", "quran/audio/hosary/timings.json"),
        QuranReciter("ayoub-qaloun", "أيوب الركباوي", "قالون عن نافع", "quran/audio/ayoub/timings.json"),
    )
    private val mutableState = MutableStateFlow(QuranPlaybackState())
    val state: StateFlow<QuranPlaybackState> = mutableState.asStateFlow()
    internal var serviceRunning = false

    fun play(context: Context, reciterId: String = DEFAULT_RECITER_ID, surah: Int, ayah: Int? = null) {
        if (reciters.none { it.id == reciterId } || surah !in 1..114 || (ayah != null && ayah < 1)) return
        send(context, QuranPlaybackService.ACTION_PLAY, foreground = true) {
            putExtra(QuranPlaybackService.EXTRA_RECITER, reciterId)
            putExtra(QuranPlaybackService.EXTRA_SURAH, surah)
            ayah?.let { putExtra(QuranPlaybackService.EXTRA_AYAH, it) }
        }
    }

    /** Recites [range] from its first verse, again and again as it asks. */
    fun repeat(context: Context, range: QuranRepeatRange, reciterId: String = state.value.reciterId) {
        if (reciters.none { it.id == reciterId } || !range.isValid) return
        send(context, QuranPlaybackService.ACTION_PLAY, foreground = true) {
            putExtra(QuranPlaybackService.EXTRA_RECITER, reciterId)
            putExtra(QuranPlaybackService.EXTRA_SURAH, range.from.surah)
            putExtra(QuranPlaybackService.EXTRA_AYAH, range.from.ayah)
            putExtra(QuranPlaybackService.EXTRA_REPEAT_TO_SURAH, range.to.surah)
            putExtra(QuranPlaybackService.EXTRA_REPEAT_TO_AYAH, range.to.ayah)
            putExtra(QuranPlaybackService.EXTRA_REPEAT_TIMES, range.times ?: 0)
        }
    }

    /** Recitation goes on from where it is, past the end of the range. */
    fun cancelRepeat(context: Context) {
        if (serviceRunning) send(context, QuranPlaybackService.ACTION_REPEAT_OFF)
        else {
            context.getSharedPreferences(QuranPlaybackService.PREFERENCES, Context.MODE_PRIVATE).edit()
                .remove(QuranPlaybackService.KEY_REPEAT).apply()
            publish(state.value.copy(repeat = null, repeatRound = 0, repeatWindow = null))
        }
    }

    fun pause(context: Context) = send(context, QuranPlaybackService.ACTION_PAUSE)
    fun resume(context: Context) = send(context, QuranPlaybackService.ACTION_RESUME, foreground = true)
    fun stop(context: Context) {
        if (serviceRunning) send(context, QuranPlaybackService.ACTION_STOP)
        else {
            context.getSharedPreferences(QuranPlaybackService.PREFERENCES, Context.MODE_PRIVATE).edit().clear().apply()
            publish(QuranPlaybackState(reciterId = state.value.reciterId))
        }
    }

    fun seekTo(context: Context, positionMs: Long) {
        val current = state.value
        if (!serviceRunning && current.notDownloaded && current.surah != null) {
            // Waiting for its download with no service: the position is where play will start.
            publish(current.copy(positionMs = positionMs.coerceIn(0L, (current.durationMs - 1L).coerceAtLeast(0L)), ayah = null))
            return
        }
        send(context, QuranPlaybackService.ACTION_SEEK) {
            putExtra(QuranPlaybackService.EXTRA_POSITION, positionMs.coerceAtLeast(0L))
        }
    }

    fun seekVerse(context: Context, surah: Int, ayah: Int) {
        if (surah !in 1..114 || ayah < 1) return
        if (state.value.surah == null || !serviceRunning) {
            play(context, state.value.reciterId, surah, ayah)
        } else send(context, QuranPlaybackService.ACTION_SEEK_VERSE) {
            putExtra(QuranPlaybackService.EXTRA_SURAH, surah)
            putExtra(QuranPlaybackService.EXTRA_AYAH, ayah)
        }
    }

    fun nextVerse(context: Context) = send(context, QuranPlaybackService.ACTION_NEXT)
    fun previousVerse(context: Context) = send(context, QuranPlaybackService.ACTION_PREVIOUS)

    /** Other reciters can be added with their own recordings and verified verse boundaries. */
    fun selectReciter(context: Context, reciterId: String) {
        if (reciters.none { it.id == reciterId } || reciterId == state.value.reciterId) return
        if (!serviceRunning || state.value.surah == null) publish(state.value.copy(reciterId = reciterId, error = null))
        else send(context, QuranPlaybackService.ACTION_RECITER) {
            putExtra(QuranPlaybackService.EXTRA_RECITER, reciterId)
        }
    }

    internal fun publish(value: QuranPlaybackState) { mutableState.value = value }

    private fun send(context: Context, action: String, foreground: Boolean = false, extras: Intent.() -> Unit = {}) {
        if (!foreground && !serviceRunning) return
        val intent = Intent(context.applicationContext, QuranPlaybackService::class.java).setAction(action).apply(extras)
        runCatching {
            if (foreground) ContextCompat.startForegroundService(context.applicationContext, intent)
            else context.applicationContext.startService(intent)
        }.onFailure {
            publish(state.value.copy(error = "تعذّر تشغيل التلاوة. أعد المحاولة من داخل التطبيق."))
        }
    }
}

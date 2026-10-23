package com.tunisianprayertimes.quran.audio

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.tunisianprayertimes.quran.QuranVerseReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class QuranReciter(
    val id: String,
    val name: String,
    /** The short form for chips: «الحصري». */
    val shortName: String,
    val riwaya: String,
    internal val timingAssetPath: String,
)

/**
 * Why a recitation stopped by itself; the listener resumes it, it never resumes on its own. [Silence]: the phone went
 * into total silence (the app's own at prayer time, its manual one, a silence until an alarm, or the listener's), which
 * mutes every recitation.
 */
enum class QuranInterruption { Alarm, OtherAudio, Silence }

/** Where the sleep timer stops the recitation; always at the end of a verse. */
data class QuranSleep(
    val kind: Kind,
    /** The last verse recited, for [Kind.Verse], [Kind.Page] and [Kind.Surah]. */
    val verse: QuranVerseReference? = null,
    /** For [Kind.Minutes]: the end of the verse being recited once this elapsed-realtime moment has passed. */
    val untilElapsedMs: Long = 0L,
) {
    enum class Kind { Verse, Page, Surah, Minutes }
}

/** How the listener likes to listen; kept across recitations and restarts. */
data class QuranListening(
    val speed: Float = 1f,
    /** «كل آية»: each verse is recited this many times; 0 repeats it until the listener moves on. */
    val verseTimes: Int = 1,
    /**
     * «سورة … كلها»: every surah listened to is recited whole this many times, then listening stops; 0 repeats it until
     * the listener stops it; 1 recites it once.
     */
    val surahTimes: Int = 1,
    /** Silence after each verse, as a multiple of the verse's own length; 0 for none. */
    val gapFactor: Float = 0f,
    val continueAfterSurah: Boolean = true,
    /** Not kept across restarts. */
    val sleep: QuranSleep? = null,
    /** The passage count last chosen, offered first next time; null for «بلا توقّف». */
    val lastRepeatTimes: Int? = null,
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
    /** The one-based recitation of the current verse while each verse is repeated; 0 otherwise. */
    val verseRound: Int = 0,
    /** A silent pause after verse [gapAfter] is under way: what is left of it, and its whole length. */
    val gapRemainingMs: Long = 0L,
    val gapTotalMs: Long = 0L,
    val gapAfter: Int? = null,
    val interruption: QuranInterruption? = null,
    /** Who silenced the phone, with [QuranInterruption.Silence]: said instead of a bare «silent». */
    val silence: QuranSilenceNotice? = null,
    /** The place saved by an earlier session, shown paused before any recitation service runs. */
    val restored: Boolean = false,
    /** When the restored place was saved, as wall-clock milliseconds; 0 when unknown. */
    val savedAtMs: Long = 0L,
    /** Separate passages recited in turn (a drill, «مواضعي صوتا»); [repeat] is then its current item. */
    val playlist: QuranPlaylist? = null,
    val playlistIndex: Int = 0,
    /** The one-based round over [playlist]; 0 without one. */
    val playlistRound: Int = 0,
    /**
     * The playlist that has just been recited to its end: playback rests after its last verse, and play goes on
     * from there as ordinary recitation; null once anything else plays or moves.
     */
    val finishedPlaylist: QuranPlaylist? = null,
    /**
     * The counted repetition just recited to its end: playback rests after its last verse, «أعِده» recites it again and
     * play goes on from there; null once anything else plays or moves.
     */
    val finishedRepeat: QuranRepeatRange? = null,
)

/** The service owns playback; screens may disappear without interrupting the recitation. */
object QuranAudioController {
    const val DEFAULT_RECITER_ID = "hosary-qaloun"

    val reciters: List<QuranReciter> = listOf(
        QuranReciter(DEFAULT_RECITER_ID, "محمود خليل الحصري", "الحصري", "قالون عن نافع", "quran/audio/hosary/timings.json"),
        QuranReciter("ayoub-qaloun", "أيوب الركباوي", "الركباوي", "قالون عن نافع", "quran/audio/ayoub/timings.json"),
        QuranReciter("trabelsi-qaloun", "أحمد خضر الطرابلسي", "الطرابلسي", "قالون عن نافع", "quran/audio/trabelsi/timings.json"),
    )
    private val mutableState = MutableStateFlow(QuranPlaybackState())
    val state: StateFlow<QuranPlaybackState> = mutableState.asStateFlow()
    private val mutableListening = MutableStateFlow(QuranListening())
    val listening: StateFlow<QuranListening> = mutableListening.asStateFlow()
    @Volatile internal var serviceRunning = false
    @Volatile private var initialized = false
    /** The recording of a restored place, so the place can move before any service runs. */
    @Volatile private var restoredTrack: QuranSurahRecording? = null
    /** Reads another reciter's timings for a place that no service holds. */
    private val idleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun reciter(id: String): QuranReciter = reciters.firstOrNull { it.id == id } ?: reciters.first()

    @Volatile private var listeningRead = false

    /**
     * Reads the listening settings, once: for the reader, and for a service started alone from its notification after
     * the process was gone, which would otherwise play with the defaults.
     */
    fun readListening(context: Context) {
        if (listeningRead) return
        synchronized(this) {
            if (listeningRead) return
            listeningRead = true
            val settings = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
            mutableListening.value = QuranListening(
                speed = settings.getFloat(KEY_SPEED, 1f).takeIf { it in QuranSpeeds } ?: 1f,
                verseTimes = settings.getInt(KEY_VERSE_TIMES, 1).takeIf { it in 0..QuranRepeatRange.MAX_TIMES } ?: 1,
                surahTimes = settings.getInt(KEY_SURAH_TIMES, 1).takeIf { it in 0..QuranRepeatRange.MAX_TIMES } ?: 1,
                gapFactor = settings.getFloat(KEY_GAP, 0f).takeIf { it in QuranGapChoices } ?: 0f,
                continueAfterSurah = settings.getBoolean(KEY_CONTINUE, true),
                lastRepeatTimes = settings.getInt(KEY_LAST_TIMES, 0).takeIf { it in 1..QuranRepeatRange.MAX_TIMES },
            )
        }
    }

    /**
     * Reads the listening settings and, when nothing plays, the place an earlier session stopped at.
     * Call on an IO dispatcher; later calls do nothing.
     */
    fun initialize(context: Context) {
        if (initialized) return
        readListening(context)
        synchronized(this) {
            if (initialized) return
            initialized = true
            val settings = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
            val chosen = settings.getString(KEY_RECITER, null)?.takeIf { id -> reciters.any { it.id == id } }
            if (serviceRunning || state.value.surah != null) {
                if (chosen != null && state.value.surah == null) publish(state.value.copy(reciterId = chosen))
                return
            }
            val saved = context.getSharedPreferences(QuranPlaybackService.PREFERENCES, Context.MODE_PRIVATE)
            val surah = saved.getInt(QuranPlaybackService.EXTRA_SURAH, 0).takeIf { it in 1..114 }
            val reciterId = saved.getString(QuranPlaybackService.EXTRA_RECITER, null)?.takeIf { id -> reciters.any { it.id == id } }
                ?: chosen ?: DEFAULT_RECITER_ID
            if (surah == null) {
                publish(state.value.copy(reciterId = chosen ?: DEFAULT_RECITER_ID))
                return
            }
            val track = runCatching { QuranRecitationTimings.load(context, reciter(reciterId))[surah - 1] }.getOrNull()
            val position = saved.getLong(QuranPlaybackService.EXTRA_POSITION, 0L).coerceAtLeast(0L)
            val repeat = decodeQuranRepeat(saved.getString(QuranPlaybackService.KEY_REPEAT, null))
            restoredTrack = track
            publish(QuranPlaybackState(
                reciterId = reciterId,
                surah = surah,
                ayah = track?.takeIf { position < it.timings.last().endMs }?.let { it.ayahAt(position) ?: it.timings.lastOrNull { t -> t.startMs <= position }?.ayah },
                positionMs = position,
                durationMs = track?.durationMs ?: 0L,
                introEndMs = track?.timings?.first()?.startMs ?: 0L,
                repeat = repeat?.first,
                repeatRound = repeat?.second ?: 0,
                repeatWindow = repeat?.first?.let { range -> track?.repeatWindow(range) },
                restored = true,
                savedAtMs = saved.getLong(QuranPlaybackService.KEY_SAVED_AT, 0L),
            ))
        }
    }

    fun play(context: Context, reciterId: String = state.value.reciterId, surah: Int, ayah: Int? = null) {
        if (reciters.none { it.id == reciterId } || surah !in 1..114 || (ayah != null && ayah < 1)) return
        send(context, QuranPlaybackService.ACTION_PLAY, foreground = true) {
            putExtra(QuranPlaybackService.EXTRA_RECITER, reciterId)
            putExtra(QuranPlaybackService.EXTRA_SURAH, surah)
            ayah?.let { putExtra(QuranPlaybackService.EXTRA_AYAH, it) }
        }
    }

    /** Plays again from a stopped place, e.g. to undo «إيقاف». */
    fun playAt(context: Context, reciterId: String, surah: Int, positionMs: Long, repeat: QuranRepeatRange?, round: Int, autoPlay: Boolean = true) {
        if (reciters.none { it.id == reciterId } || surah !in 1..114) return
        send(context, QuranPlaybackService.ACTION_PLAY, foreground = true) {
            putExtra(QuranPlaybackService.EXTRA_AUTOPLAY, autoPlay)
            putExtra(QuranPlaybackService.EXTRA_RECITER, reciterId)
            putExtra(QuranPlaybackService.EXTRA_SURAH, surah)
            putExtra(QuranPlaybackService.EXTRA_POSITION, positionMs.coerceAtLeast(0L))
            repeat?.takeIf { it.isValid }?.let {
                putExtra(QuranPlaybackService.EXTRA_AYAH, it.from.ayah)
                putExtra(QuranPlaybackService.EXTRA_REPEAT_FROM_SURAH, it.from.surah)
                putExtra(QuranPlaybackService.EXTRA_REPEAT_TO_SURAH, it.to.surah)
                putExtra(QuranPlaybackService.EXTRA_REPEAT_TO_AYAH, it.to.ayah)
                putExtra(QuranPlaybackService.EXTRA_REPEAT_TIMES, it.times ?: 0)
                putExtra(QuranPlaybackService.EXTRA_REPEAT_ROUND, round.coerceAtLeast(1))
            }
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

    /** Recites [playlist]'s passages in turn, from its first one. */
    /** [index] and [round] resume a drill where it was, e.g. to undo leaving it. */
    fun playPlaylist(context: Context, playlist: QuranPlaylist, reciterId: String = state.value.reciterId, index: Int = 0, round: Int = 1, autoPlay: Boolean = true) {
        if (reciters.none { it.id == reciterId } || !playlist.isValid) return
        send(context, QuranPlaybackService.ACTION_PLAYLIST, foreground = true) {
            putExtra(QuranPlaybackService.EXTRA_AUTOPLAY, autoPlay)
            putExtra(QuranPlaybackService.EXTRA_RECITER, reciterId)
            putExtra(QuranPlaybackService.EXTRA_PLAYLIST, playlist.encode())
            putExtra(QuranPlaybackService.EXTRA_PLAYLIST_INDEX, index)
            putExtra(QuranPlaybackService.EXTRA_PLAYLIST_ROUND, round)
        }
    }

    /**
     * Repeats [range] from where the recitation is, without starting it again; a place outside it goes to its start.
     * [round] keeps the pass under way, e.g. when only the count changes.
     */
    fun repeatInPlace(context: Context, range: QuranRepeatRange, round: Int = 1) {
        val current = state.value
        val surah = current.surah ?: return
        if (!range.isValid) return
        if (serviceRunning && !current.restored) send(context, QuranPlaybackService.ACTION_REPEAT_HERE) {
            putExtra(QuranPlaybackService.EXTRA_SURAH, range.from.surah)
            putExtra(QuranPlaybackService.EXTRA_AYAH, range.from.ayah)
            putExtra(QuranPlaybackService.EXTRA_REPEAT_TO_SURAH, range.to.surah)
            putExtra(QuranPlaybackService.EXTRA_REPEAT_TO_AYAH, range.to.ayah)
            putExtra(QuranPlaybackService.EXTRA_REPEAT_TIMES, range.times ?: 0)
            putExtra(QuranPlaybackService.EXTRA_REPEAT_ROUND, round.coerceAtLeast(1))
        } else {
            // No service: the saved place keeps the repetition, as a service would have saved it.
            val track = restoredTrack?.takeIf { it.number == surah }
            val window = track?.repeatWindow(range)
            context.getSharedPreferences(QuranPlaybackService.PREFERENCES, Context.MODE_PRIVATE).edit()
                .putString(QuranPlaybackService.KEY_REPEAT, range.encode(round.coerceAtLeast(1)))
                .remove(QuranPlaybackService.KEY_FINISHED_SURAH).apply()
            publish(current.copy(repeat = range, repeatRound = round.coerceAtLeast(1), repeatWindow = window, finishedRepeat = null))
            // Resting at the very end of the range would end a first pass at once: from its start instead.
            if (window != null && current.positionMs !in window.startMs until window.endMs - QuranPlaybackService.VerseEndEarlyMs) {
                moveIdle(context, window.startMs)
            }
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
            restoredTrack = null
            publish(QuranPlaybackState(reciterId = state.value.reciterId))
        }
    }

    fun seekTo(context: Context, positionMs: Long, quiet: Boolean = false) {
        val current = state.value
        if (!serviceRunning && current.surah != null && (current.notDownloaded || current.restored)) {
            // No service yet: the position is where play will start.
            moveIdle(context, positionMs)
            return
        }
        send(context, QuranPlaybackService.ACTION_SEEK) {
            putExtra(QuranPlaybackService.EXTRA_POSITION, positionMs.coerceAtLeast(0L))
            putExtra(QuranPlaybackService.EXTRA_QUIET, quiet)
        }
    }

    /** Fine moves inside a long verse, without leaving a repeated passage. */
    fun skipBy(context: Context, deltaMs: Long) {
        val current = state.value
        if (current.surah == null) return
        seekTo(context, (current.positionMs + deltaMs).coerceAtLeast(0L), quiet = true)
    }

    fun seekVerse(context: Context, surah: Int, ayah: Int) {
        if (surah !in 1..114 || ayah < 1) return
        val current = state.value
        if (current.surah == null || !serviceRunning) {
            play(context, current.reciterId, surah, ayah)
        } else send(context, QuranPlaybackService.ACTION_SEEK_VERSE) {
            putExtra(QuranPlaybackService.EXTRA_SURAH, surah)
            putExtra(QuranPlaybackService.EXTRA_AYAH, ayah)
        }
    }

    fun nextVerse(context: Context) {
        if (!serviceRunning) { moveIdleVerse(context, next = true); return }
        send(context, QuranPlaybackService.ACTION_NEXT)
    }

    fun previousVerse(context: Context) {
        if (!serviceRunning) { moveIdleVerse(context, next = false); return }
        send(context, QuranPlaybackService.ACTION_PREVIOUS)
    }

    /** Other reciters can be added with their own recordings and verified verse boundaries. */
    fun selectReciter(context: Context, reciterId: String) {
        if (reciters.none { it.id == reciterId }) return
        context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).edit().putString(KEY_RECITER, reciterId).apply()
        if (reciterId == state.value.reciterId) return
        if (!serviceRunning || state.value.surah == null) {
            val before = state.value
            val surah = before.surah
            if (surah == null) {
                restoredTrack = null
                publish(before.copy(reciterId = reciterId, error = null))
                return
            }
            val app = context.applicationContext
            // A place is a verse, not a time. The old reciter's place stays in force, moves included,
            // until the new reciter's timings are read; then the verse it holds is found in them.
            idleScope.launch {
                val loaded = runCatching { QuranRecitationTimings.load(app, reciter(reciterId))[surah - 1] }.getOrNull()
                val missing = loaded?.let { com.tunisianprayertimes.quran.assets.QuranAssets.resolve(app, it.assetPath) == null } ?: false
                // Only the reciter chosen last, on the place it was chosen for, decided where the service publishes.
                val chosen = app.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).getString(KEY_RECITER, null)
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                if (serviceRunning || state.value.surah != surah || chosen != reciterId) return@withContext
                val track = loaded ?: run {
                    // Without its timings the place cannot be found by verse: the chapter starts from its opening.
                    restoredTrack = null
                    publish(state.value.copy(reciterId = reciterId, error = null, notDownloaded = false, positionMs = 0L, ayah = null, gapAfter = null))
                    return@withContext
                }
                val current = state.value
                val oldTrack = restoredTrack?.takeIf { it.number == surah }
                val heard = current.ayah ?: current.gapAfter ?: oldTrack?.ayahAt(current.positionMs)
                val verse = heard?.takeIf { track.startOf(it) != null }
                val position = verse?.let(track::startOf) ?: 0L
                restoredTrack = track
                publish(current.copy(
                    reciterId = reciterId, error = null,
                    // Whether this reciter's recording of the chapter is on the device, not the old one's.
                    notDownloaded = missing,
                    positionMs = position, ayah = verse, gapAfter = null, durationMs = track.durationMs,
                    introEndMs = track.timings.first().startMs, repeatWindow = current.repeat?.let(track::repeatWindow),
                ))
                app.getSharedPreferences(QuranPlaybackService.PREFERENCES, Context.MODE_PRIVATE).edit()
                    .putString(QuranPlaybackService.EXTRA_RECITER, reciterId)
                    .putInt(QuranPlaybackService.EXTRA_SURAH, surah)
                    .putLong(QuranPlaybackService.EXTRA_POSITION, position).apply()
                }
            }
        } else send(context, QuranPlaybackService.ACTION_RECITER) {
            putExtra(QuranPlaybackService.EXTRA_RECITER, reciterId)
        }
    }

    /** Applies at once; the speed of a paused recitation applies when it plays again. */
    fun updateListening(context: Context, change: (QuranListening) -> QuranListening) {
        val before = mutableListening.value
        val after = change(before).let { it.copy(speed = it.speed.coerceIn(0.5f, 2f)) }
        mutableListening.value = after
        context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_SPEED, after.speed)
            .putInt(KEY_VERSE_TIMES, after.verseTimes)
            .putInt(KEY_SURAH_TIMES, after.surahTimes)
            .putFloat(KEY_GAP, after.gapFactor)
            .putBoolean(KEY_CONTINUE, after.continueAfterSurah)
            .putInt(KEY_LAST_TIMES, after.lastRepeatTimes ?: 0)
            .apply()
        if (serviceRunning && (before.speed != after.speed || before.gapFactor != after.gapFactor || before.verseTimes != after.verseTimes)) {
            send(context, QuranPlaybackService.ACTION_SETTINGS)
        }
    }

    /**
     * A reciter chosen while listening whose recording downloads first, as (chosen, voice heard then). Kept outside the
     * reader, which leaves the composition when another tab opens: back on the Quran tab, the switch goes on.
     */
    @Volatile internal var pendingSwitch: Pair<String, String>? = null

    /** Play refused while the phone is silent: the place says who silenced it on every surface, as when the service refuses it. */
    fun markSilenced(notice: QuranSilenceNotice) {
        val now = state.value
        if (now.surah != null && !now.playing) publish(now.copy(interruption = QuranInterruption.Silence, silence = notice))
    }

    /**
     * The place paused for the silence says it again: cleared once the phone is heard, its cause anew while silent. A
     * running service does it, so the notification and the lock screen follow.
     */
    fun refreshSilence(context: Context) {
        if (state.value.interruption != QuranInterruption.Silence) return
        if (serviceRunning) send(context, QuranPlaybackService.ACTION_REFRESH_SILENCE)
        else quranSilenceRefreshed(context, state.value)?.let(::publish)
    }

    /** Ends the sleep timer's wait now, e.g. once [QuranSleep.Kind.Minutes] has passed at a verse end. */
    internal fun clearSleep() {
        mutableListening.value = mutableListening.value.copy(sleep = null)
    }

    internal fun publish(value: QuranPlaybackState) { mutableState.value = value }

    /** The recording of a place left paused when the service ends, so the place can still move verse by verse. */
    internal fun rememberTrack(track: QuranSurahRecording) { restoredTrack = track }

    private fun moveIdle(context: Context, positionMs: Long) {
        val current = state.value
        val track = restoredTrack?.takeIf { it.number == current.surah }
        val bounded = positionMs.coerceIn(0L, (current.durationMs - 1L).coerceAtLeast(0L))
        val target = current.repeatWindow?.clamp(bounded) ?: bounded
        val surah = current.surah ?: return
        publish(current.copy(positionMs = target, ayah = track?.ayahAt(target), gapAfter = null, gapRemainingMs = 0L, gapTotalMs = 0L,
            finishedPlaylist = null))
        context.getSharedPreferences(QuranPlaybackService.PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(QuranPlaybackService.EXTRA_RECITER, current.reciterId)
            .putInt(QuranPlaybackService.EXTRA_SURAH, surah)
            .putLong(QuranPlaybackService.EXTRA_POSITION, target)
            .remove(QuranPlaybackService.KEY_END_HANDLED)
            .apply()
    }

    private fun moveIdleVerse(context: Context, next: Boolean) {
        val current = state.value
        val surah = current.surah ?: return
        val track = restoredTrack?.takeIf { it.number == surah }
        if (track == null) {
            // Without the recording's timings the place cannot move verse by verse: play from there instead.
            play(context, current.reciterId, surah, current.ayah)
            return
        }
        val target = if (next) {
            track.timings.firstOrNull { it.startMs > current.positionMs }?.ayah ?: return
        } else when (val previous = track.previousTarget(current.positionMs)) {
            is QuranPreviousTarget.Verse -> previous.ayah
            QuranPreviousTarget.PreviousChapter -> return
        }
        val start = track.startOf(target) ?: return
        if (current.repeat?.contains(QuranVerseReference(surah, target)) == false) return
        moveIdle(context, start)
    }

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

    internal fun elapsedNow(): Long = SystemClock.elapsedRealtime()

    internal const val SETTINGS = "quran_audio_settings"
    private const val KEY_RECITER = "reciter"
    private const val KEY_SPEED = "speed"
    private const val KEY_VERSE_TIMES = "verse_times"
    private const val KEY_SURAH_TIMES = "surah_times"
    private const val KEY_GAP = "gap"
    private const val KEY_CONTINUE = "continue_after_surah"
    private const val KEY_LAST_TIMES = "last_repeat_times"
}

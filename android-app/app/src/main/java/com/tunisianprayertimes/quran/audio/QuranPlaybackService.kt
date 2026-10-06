package com.tunisianprayertimes.quran.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import com.tunisianprayertimes.MainActivity
import com.tunisianprayertimes.MainTabNavigation
import com.tunisianprayertimes.R
import com.tunisianprayertimes.quran.QuranRepository
import com.tunisianprayertimes.quran.QuranVerseReference
import com.tunisianprayertimes.quran.assets.QuranAssets
import com.tunisianprayertimes.quran.assets.attachTo
import com.tunisianprayertimes.wake.AwakeCheckService
import com.tunisianprayertimes.wake.WakeAlarmQueueHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/** Offline spoken-audio playback with a platform media session and verified verse intervals. */
class QuranPlaybackService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }
    private val preferences by lazy { getSharedPreferences(PREFERENCES, MODE_PRIVATE) }
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private lateinit var session: MediaSession
    private lateinit var focusRequest: AudioFocusRequest
    private var player: MediaPlayer? = null
    private var prepared = false
    private var seeking = false
    private var playWhenReady = false
    private var resumeAfterFocusGain = false
    private var hasFocus = false
    private var focusRequested = false
    private var foreground = false
    private var loadJob: Job? = null
    private var generation = 0L
    private var recording: QuranSurahRecording? = null
    private var recordings = emptyList<QuranSurahRecording>()
    private var surahNames = emptyList<String>()
    private var pendingSeekMs: Long? = null
    private var explicitStop = false
    private var lastPersistedSecond = -1L
    private var lastSessionSecond = -1L
    private var notificationKey: String? = null
    private var repeat: QuranRepeatRange? = null
    /** The one-based pass over [repeat] being recited. */
    private var repeatRound = 1
    /** Ends the slow idle wait of the position poll as soon as audio starts. */
    private val pollWake = Channel<Unit>(Channel.CONFLATED)

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pausePlayback(userInitiated = true)
        }
    }

    override fun onCreate() {
        super.onCreate()
        QuranAudioController.serviceRunning = true
        notifications.createNotificationChannel(NotificationChannel(CHANNEL_ID, "تلاوة القرآن", NotificationManager.IMPORTANCE_LOW).apply {
            description = "التحكم في تلاوة القرآن الكريم"
            setSound(null, null)
            enableVibration(false)
        })
        session = MediaSession(this, "QuranRecitation").apply {
            setSessionActivity(openReaderIntent())
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { ensureForeground(); resumePlayback() }
                override fun onPause() = pausePlayback(userInitiated = true)
                override fun onStop() = stopPlayback()
                override fun onSeekTo(pos: Long) = seek(pos)
                override fun onSkipToNext() = adjacentVerse(next = true)
                override fun onSkipToPrevious() = adjacentVerse(next = false)
            }, Handler(Looper.getMainLooper()))
            isActive = true
        }
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttributes)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change -> onFocusChanged(change) }, Handler(Looper.getMainLooper()))
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        }
        scope.launch {
            while (isActive) {
                // The app's alarm player does not request audio focus, so its active
                // state must also interrupt recitation independently of focus callbacks.
                if (playWhenReady && alarmIsActive()) pauseForAlarm()
                if (prepared && !seeking) refreshPosition(checkRepeat = true)
                withTimeoutOrNull(pollDelayMs()) { pollWake.receive() }
            }
        }
    }

    /** Wake exactly at the end of a repeated range, so the following verse is not heard before the loop. */
    private fun pollDelayMs(): Long {
        val current = QuranAudioController.state.value
        if (!current.playing) return 500L
        val end = current.repeatWindow?.endMs?.takeIf { current.surah == repeat?.to?.surah } ?: return 100L
        return (end - current.positionMs).coerceIn(10L, 100L)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A killed service never restarts playback on its own.
        if (intent == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_PLAY -> {
                ensureForeground()
                val surah = intent.getIntExtra(EXTRA_SURAH, 1)
                val ayah = intent.getIntExtra(EXTRA_AYAH, 0).takeIf { it > 0 }
                // Listening from a chosen place replaces any earlier repetition.
                repeat = repeatRange(intent, surah, ayah)
                repeatRound = 1
                load(
                    intent.getStringExtra(EXTRA_RECITER) ?: QuranAudioController.DEFAULT_RECITER_ID,
                    surah,
                    ayah,
                    autoPlay = true,
                )
            }
            ACTION_REPEAT_OFF -> cancelRepeat()
            ACTION_RESUME -> { ensureForeground(); resumePlayback() }
            ACTION_PAUSE -> pausePlayback(userInitiated = true)
            ACTION_STOP -> stopPlayback()
            ACTION_SEEK -> seek(intent.getLongExtra(EXTRA_POSITION, 0L))
            ACTION_SEEK_VERSE -> seekVerse(intent.getIntExtra(EXTRA_SURAH, 1), intent.getIntExtra(EXTRA_AYAH, 1))
            ACTION_NEXT -> adjacentVerse(next = true)
            ACTION_PREVIOUS -> adjacentVerse(next = false)
            ACTION_RECITER -> intent.getStringExtra(EXTRA_RECITER)?.let { id ->
                val current = QuranAudioController.state.value
                current.surah?.let { surah -> load(id, surah, current.ayah, autoPlay = playWhenReady) }
            }
        }
        if (player == null && loadJob?.isActive != true && QuranAudioController.state.value.surah == null) stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun repeatRange(intent: Intent, surah: Int, ayah: Int?): QuranRepeatRange? {
        val toSurah = intent.getIntExtra(EXTRA_REPEAT_TO_SURAH, 0)
        if (toSurah == 0 || ayah == null) return null
        return QuranRepeatRange(
            QuranVerseReference(surah, ayah),
            QuranVerseReference(toSurah, intent.getIntExtra(EXTRA_REPEAT_TO_AYAH, 0)),
            intent.getIntExtra(EXTRA_REPEAT_TIMES, 0).takeIf { it > 0 },
        ).takeIf { it.isValid }
    }

    private fun load(reciterId: String, surah: Int, ayah: Int?, autoPlay: Boolean, positionMs: Long? = null) {
        val reciter = QuranAudioController.reciters.firstOrNull { it.id == reciterId }
        if (reciter == null || surah !in 1..114) {
            fail("هذه التلاوة غير متاحة.")
            return
        }
        explicitStop = false
        val request = ++generation
        loadJob?.cancel()
        releasePlayer()
        // Keep the same focus grant between chapters; returning it briefly can restart
        // another app's music during our asynchronous preparation of the next recording.
        if (!autoPlay || !focusRequested) abandonFocus()
        recording = null
        pendingSeekMs = positionMs
        playWhenReady = autoPlay
        if (!autoPlay) resumeAfterFocusGain = false
        publish(QuranPlaybackState(reciterId = reciterId, surah = surah, loading = true, positionMs = positionMs ?: 0L))
        if (autoPlay) ensureForeground()
        loadJob = scope.launch {
            try {
                val (allRecordings, names, audio) = withContext(Dispatchers.IO) {
                    val timings = QuranRecitationTimings.load(applicationContext, reciter)
                    val chapters = QuranRepository.load(applicationContext).surahs
                    require(chapters.size == timings.size && chapters.zip(timings).all { (chapter, track) ->
                        chapter.verseCount == track.timings.size
                    }) { "Mushaf and recitation numbering differ" }
                    val path = timings[surah - 1].assetPath
                    // Null until the chapter's pack is downloaded; a recording no pack holds cannot come at all.
                    val audio = QuranAssets.resolve(applicationContext, path)
                    require(audio != null || QuranAssets.downloadable(applicationContext, path)) { "No source for $path" }
                    Triple(timings, chapters.map { it.name }, audio)
                }
                if (request != generation) return@launch
                recordings = allRecordings
                surahNames = names
                val track = allRecordings[surah - 1]
                if (audio == null) {
                    recording = track
                    // Resuming after the download starts from the verse that was asked for.
                    awaitDownload(track, pendingSeekMs ?: ayah?.let(track::startOf) ?: 0L)
                    return@launch
                }
                repeat?.let { range ->
                    requireNotNull(allRecordings[range.from.surah - 1].startOf(range.from.ayah)) { "Unknown verse" }
                    requireNotNull(allRecordings[range.to.surah - 1].endOf(range.to.ayah)) { "Unknown verse" }
                }
                recording = track
                if (ayah != null && pendingSeekMs == null) pendingSeekMs = requireNotNull(track.startOf(ayah)) { "Unknown verse" }
                publish(QuranAudioController.state.value.copy(durationMs = track.durationMs, introEndMs = track.timings.first().startMs))
                val media = MediaPlayer()
                player = media
                media.setAudioAttributes(audioAttributes)
                media.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
                audio.attachTo(media, applicationContext)
                media.setOnPreparedListener { loaded ->
                    if (player !== loaded || request != generation) return@setOnPreparedListener
                    prepared = true
                    val actualDuration = loaded.duration.toLong()
                    if (abs(actualDuration - track.durationMs) > 2_000L) {
                        fail("ملف التلاوة لا يطابق توقيت الآيات.")
                        return@setOnPreparedListener
                    }
                    publish(QuranAudioController.state.value.copy(durationMs = actualDuration, loading = false))
                    // A repeated range never starts outside its verses, even from a saved or queued position of 0.
                    val window = repeat?.let(track::repeatWindow)
                    val target = (pendingSeekMs ?: window?.startMs)?.let { window?.clamp(it) ?: it }
                    pendingSeekMs = null
                    if (target != null && target > 0L) seek(target)
                    else if (playWhenReady) startPrepared()
                    else { refreshPosition(); leaveForeground() }
                }
                media.setOnSeekCompleteListener { loaded ->
                    if (player !== loaded || request != generation) return@setOnSeekCompleteListener
                    seeking = false
                    val queuedTarget = pendingSeekMs
                    pendingSeekMs = null
                    if (queuedTarget != null) {
                        seek(queuedTarget)
                        return@setOnSeekCompleteListener
                    }
                    publish(QuranAudioController.state.value.copy(loading = false))
                    refreshPosition()
                    // A paused seek is where a later resume starts, e.g. the rewind after the last counted pass.
                    if (playWhenReady) startPrepared() else persistPosition()
                }
                media.setOnCompletionListener { loaded ->
                    if (player !== loaded || request != generation) return@setOnCompletionListener
                    // A seek already under way supersedes the end of the file; a repeated pass is counted once.
                    if (seeking) return@setOnCompletionListener
                    val range = repeat
                    // The range's last verse can run to the very end of the file.
                    if (range != null && surah >= range.to.surah) finishRepeatPass()
                    else if (surah < 114 && playWhenReady) load(reciterId, surah + 1, null, autoPlay = true)
                    else {
                        playWhenReady = false
                        publish(QuranAudioController.state.value.copy(playing = false, ayah = null, positionMs = loaded.duration.toLong()))
                        persistPosition()
                        abandonFocus()
                        leaveForeground()
                    }
                }
                media.setOnErrorListener { loaded, _, _ ->
                    if (player === loaded && request == generation) fail("تعذّر قراءة ملف التلاوة. أعد المحاولة.")
                    true
                }
                media.prepareAsync()
                updateMetadata()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (request == generation) {
                    android.util.Log.e("QuranPlayback", "Cannot load recitation $reciterId/$surah", error)
                    fail("تعذّر تحميل التلاوة وتوقيت الآيات. أعد المحاولة.")
                }
            }
        }
    }

    private fun resumePlayback() {
        if (player != null || loadJob?.isActive == true) {
            playWhenReady = true
            resumeAfterFocusGain = false
            if (prepared && !seeking) {
                val current = QuranAudioController.state.value
                if (current.durationMs > 0L && current.positionMs >= current.durationMs) seek(0L)
                else startPrepared()
            }
        } else {
            val current = QuranAudioController.state.value
            val surah = current.surah ?: preferences.getInt(EXTRA_SURAH, 0).takeIf { it in 1..114 }
            if (surah == null) { stopPlayback(); return }
            // A new service instance continues the repetition the previous one was reciting.
            val saved = if (current.surah != null) current.repeat?.let { it to current.repeatRound }
                else decodeQuranRepeat(preferences.getString(KEY_REPEAT, null))
            repeat = saved?.first
            repeatRound = saved?.second?.coerceAtLeast(1) ?: 1
            val reciterId = if (current.surah != null) current.reciterId else preferences.getString(EXTRA_RECITER, null)
            val position = if (current.surah != null) current.positionMs else preferences.getLong(EXTRA_POSITION, 0L)
            load(reciterId ?: QuranAudioController.DEFAULT_RECITER_ID, surah, null, autoPlay = true, positionMs = position)
        }
    }

    private fun startPrepared() {
        val media = player ?: return
        if (!prepared || seeking || !playWhenReady || resumeAfterFocusGain) return
        if (alarmIsActive()) {
            pauseForAlarm()
            return
        }
        ensureForeground()
        if (!hasFocus) {
            focusRequested = true
            hasFocus = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        if (!hasFocus) {
            abandonFocus()
            playWhenReady = false
            publish(QuranAudioController.state.value.copy(playing = false, loading = false, error = "الصوت مستخدم الآن. اضغط تشغيل للمحاولة مجددًا."))
            leaveForeground()
            return
        }
        try {
            media.start()
            publish(QuranAudioController.state.value.copy(playing = true, loading = false, error = null))
            refreshPosition()
            // The end of a repeated range may be milliseconds away.
            pollWake.trySend(Unit)
        } catch (error: IllegalStateException) {
            fail("تعذّر تشغيل التلاوة. أعد المحاولة.")
        }
    }

    private fun pausePlayback(userInitiated: Boolean, temporaryFocusLoss: Boolean = false) {
        if (userInitiated || !temporaryFocusLoss) {
            playWhenReady = false
            resumeAfterFocusGain = false
        }
        if (prepared) runCatching { player?.let { if (it.isPlaying) it.pause() } }
        if (!seeking) refreshPosition()
        publish(QuranAudioController.state.value.copy(playing = false))
        persistPosition()
        if (!temporaryFocusLoss) {
            abandonFocus()
            leaveForeground()
        }
    }

    private fun alarmIsActive(): Boolean = WakeAlarmQueueHolder.queue.current != null || AwakeCheckService.isRunning.value

    private fun pauseForAlarm() {
        // Require an explicit play action once the alarm ends; never resume over it.
        pausePlayback(userInitiated = true)
        publish(QuranAudioController.state.value.copy(error = "أُوقفت التلاوة مؤقتًا أثناء التنبيه. شغّلها بعد انتهائه."))
    }

    private fun onFocusChanged(change: Int) {
        // Ignore queued focus callbacks after an explicit pause/stop abandoned this request.
        if (!focusRequested) return
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                if (resumeAfterFocusGain && playWhenReady) {
                    resumeAfterFocusGain = false
                    startPrepared()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                hasFocus = false
                resumeAfterFocusGain = playWhenReady
                pausePlayback(userInitiated = false, temporaryFocusLoss = true)
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasFocus = false
                pausePlayback(userInitiated = false)
            }
        }
    }

    /** [quiet] keeps the controls steady while a repeated range silently returns to its start. */
    private fun seek(positionMs: Long, quiet: Boolean = false) {
        val current = QuranAudioController.state.value
        val waiting = recording
        if (!prepared && player == null && loadJob?.isActive != true && current.notDownloaded && waiting != null) {
            // The chapter waits for its download: move where it will start once it is there.
            val bounded = positionMs.coerceIn(0L, (current.durationMs - 1L).coerceAtLeast(0L))
            val target = current.repeatWindow?.clamp(bounded) ?: bounded
            publish(current.copy(positionMs = target, ayah = waiting.ayahAt(target)))
            persistPosition()
            return
        }
        if (!prepared) { pendingSeekMs = positionMs.coerceAtLeast(0L); return }
        val media = player ?: return
        val bounded = positionMs.coerceIn(0L, (current.durationMs - 1L).coerceAtLeast(0L))
        val target = current.repeatWindow?.clamp(bounded) ?: bounded
        if (seeking) {
            // MediaPlayer may queue native seeks. Keep only the newest target ourselves,
            // so an earlier completion cannot restart audio at an obsolete position.
            pendingSeekMs = target
            publish(current.copy(positionMs = target, ayah = null))
            return
        }
        try {
            // pause() is invalid in Prepared; initial verse playback seeks before start().
            if (media.isPlaying) media.pause()
            seeking = true
            publish(
                if (quiet) current.copy(positionMs = target, ayah = recording?.ayahAt(target))
                else current.copy(playing = false, loading = true, positionMs = target, ayah = null),
            )
            media.seekTo(target, MediaPlayer.SEEK_CLOSEST)
        } catch (error: IllegalStateException) {
            fail("تعذّر الانتقال إلى موضع التلاوة.")
        }
    }

    private fun seekVerse(surah: Int, ayah: Int, quiet: Boolean = false) {
        if (surah !in 1..114 || ayah < 1) return
        // Going to a verse outside the repeated range ends the repetition.
        if (repeat?.contains(QuranVerseReference(surah, ayah)) == false) {
            repeat = null
            repeatRound = 1
        }
        val current = QuranAudioController.state.value
        val track = recording
        if (current.surah == surah && track != null) {
            // Refresh the range's bounds before seek() reads them.
            publish(current)
            track.startOf(ayah)?.let { seek(it, quiet) }
        } else load(current.reciterId, surah, ayah, autoPlay = playWhenReady)
    }

    private fun adjacentVerse(next: Boolean) {
        val track = recording ?: return
        val current = QuranAudioController.state.value
        val position = current.positionMs
        val target = if (next) {
            track.timings.firstOrNull { it.startMs > position }
        } else {
            val active = track.ayahAt(position)
            track.timings.lastOrNull { it.startMs < position && it.ayah != active }
        }
        val verse = when {
            target != null -> QuranVerseReference(track.number, target.ayah)
            next && track.number < 114 -> QuranVerseReference(track.number + 1, 1)
            !next && track.number > 1 -> {
                val previous = recordings.getOrNull(track.number - 2) ?: return
                QuranVerseReference(previous.number, previous.timings.last().ayah)
            }
            else -> QuranVerseReference(track.number, track.timings.first().ayah)
        }
        // Skipping stays inside a repeated range: past either end it returns to the first verse.
        val destination = repeat?.takeIf { verse !in it }?.from ?: verse
        seekVerse(destination.surah, destination.ayah)
    }

    /** One pass over the repeated range ended, at its last verse's boundary or at the end of the file. */
    private fun finishRepeatPass() {
        val range = repeat ?: return
        when (val step = range.afterPass(repeatRound)) {
            is QuranRepeatStep.Again -> {
                repeatRound = step.round
                seekVerse(range.from.surah, range.from.ayah, quiet = playWhenReady)
            }
            QuranRepeatStep.Finished -> {
                // Rest on the range's first verse; pressing play recites the same passes again.
                repeatRound = 1
                pausePlayback(userInitiated = true)
                seekVerse(range.from.surah, range.from.ayah)
            }
        }
    }

    private fun cancelRepeat() {
        if (repeat == null) return
        repeat = null
        repeatRound = 1
        publish(QuranAudioController.state.value)
        persistPosition()
    }

    private fun refreshPosition(checkRepeat: Boolean = false) {
        val media = player ?: return
        if (!prepared || seeking) return
        val actual = runCatching { media.currentPosition.toLong() }.getOrNull() ?: return
        val current = QuranAudioController.state.value
        val window = current.repeatWindow
        if (checkRepeat && current.playing && window != null && actual >= window.endMs && current.surah == repeat?.to?.surah) {
            finishRepeatPass()
            return
        }
        // A verse outside the repeated range is never shown as the one being recited.
        val position = window?.clamp(actual) ?: actual
        publish(current.copy(positionMs = position, ayah = recording?.ayahAt(position)))
        if (position / 10_000L != lastPersistedSecond) {
            lastPersistedSecond = position / 10_000L
            persistPosition()
        }
    }

    /** Every published state carries the service's own repetition, whatever state it was copied from. */
    private fun publish(value: QuranPlaybackState) {
        val range = repeat
        val state = value.copy(
            repeat = range,
            repeatRound = if (range == null) 0 else repeatRound,
            repeatWindow = range?.let { recording?.takeIf { track -> track.number == value.surah }?.repeatWindow(it) },
        )
        QuranAudioController.publish(state)
        val key = "${state.reciterId}:${state.surah}:${state.ayah}:${state.playing}:${state.loading}:${state.error}:${state.notDownloaded}:$range:$repeatRound"
        if (key != notificationKey) {
            notificationKey = key
            if (state.surah != null) notifications.notify(NOTIFICATION_ID, buildNotification())
            updateSession()
        } else if (state.positionMs / 1_000L != lastSessionSecond) updateSession()
    }

    private fun updateSession() {
        val current = QuranAudioController.state.value
        lastSessionSecond = current.positionMs / 1_000L
        val status = when {
            current.error != null -> PlaybackState.STATE_ERROR
            current.loading -> PlaybackState.STATE_BUFFERING
            current.playing -> PlaybackState.STATE_PLAYING
            current.surah != null -> PlaybackState.STATE_PAUSED
            else -> PlaybackState.STATE_STOPPED
        }
        val builder = PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_STOP or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
            .setState(status, current.positionMs, if (current.playing) 1f else 0f)
        current.error?.let(builder::setErrorMessage)
        session.setPlaybackState(builder.build())
    }

    private fun updateMetadata() {
        val current = QuranAudioController.state.value
        val reciter = QuranAudioController.reciters.firstOrNull { it.id == current.reciterId }
        session.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, surahTitle(current.surah))
            .putString(MediaMetadata.METADATA_KEY_ARTIST, reciter?.name.orEmpty())
            .putString(MediaMetadata.METADATA_KEY_ALBUM, "القرآن الكريم — ${reciter?.riwaya.orEmpty()}")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, current.durationMs)
            .build())
        notifications.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun ensureForeground() {
        if (!foreground) {
            startForeground(NOTIFICATION_ID, buildNotification())
            foreground = true
        }
    }

    private fun leaveForeground() {
        if (foreground) {
            stopForeground(STOP_FOREGROUND_DETACH)
            foreground = false
        }
        if (QuranAudioController.state.value.surah != null) notifications.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val current = QuranAudioController.state.value
        val reciter = QuranAudioController.reciters.firstOrNull { it.id == current.reciterId }
        val active = current.playing || (current.loading && playWhenReady)
        val round = current.repeat?.let { range ->
            range.times?.let { "التكرار ${current.repeatRound} من $it" } ?: "تكرار بلا توقف"
        }
        val detail = listOfNotNull(reciter?.name, current.ayah?.let { "الآية $it" }, round).joinToString(" · ")
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tab_quran)
            .setContentTitle(surahTitle(current.surah))
            .setContentText(current.error ?: if (current.notDownloaded) "السورة غير محمّلة على الجهاز · افتح التطبيق لتنزيلها" else detail)
            .setContentIntent(openReaderIntent())
            .setDeleteIntent(actionIntent(ACTION_STOP))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(active)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_previous, "الآية السابقة", actionIntent(ACTION_PREVIOUS)).build())
            .addAction(Notification.Action.Builder(
                if (active) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (active) "إيقاف مؤقت" else "تشغيل",
                actionIntent(if (active) ACTION_PAUSE else ACTION_RESUME),
            ).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_next, "الآية التالية", actionIntent(ACTION_NEXT)).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "إيقاف التلاوة", actionIntent(ACTION_STOP)).build())
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun surahTitle(surah: Int?): String = surah?.let { surahNames.getOrNull(it - 1)?.let { name -> "سورة $name" } } ?: "القرآن الكريم"

    private fun openReaderIntent(): PendingIntent = PendingIntent.getActivity(this, NOTIFICATION_ID,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainTabNavigation.EXTRA_DESTINATION, MainTabNavigation.DESTINATION_QURAN),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun actionIntent(action: String): PendingIntent {
        val intent = Intent(this, QuranPlaybackService::class.java).setAction(action)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (action == ACTION_RESUME) PendingIntent.getForegroundService(this, 0, intent, flags)
        else PendingIntent.getService(this, 0, intent, flags)
    }

    private fun persistPosition() {
        val state = QuranAudioController.state.value
        val surah = state.surah ?: return
        preferences.edit().putString(EXTRA_RECITER, state.reciterId).putInt(EXTRA_SURAH, surah)
            .putLong(EXTRA_POSITION, state.positionMs).putString(KEY_REPEAT, repeat?.encode(repeatRound)).apply()
    }

    private fun abandonFocus() {
        focusRequested = false
        audioManager.abandonAudioFocusRequest(focusRequest)
        hasFocus = false
        resumeAfterFocusGain = false
    }

    private fun releasePlayer() {
        prepared = false
        seeking = false
        player?.let { old ->
            old.setOnPreparedListener(null)
            old.setOnSeekCompleteListener(null)
            old.setOnCompletionListener(null)
            old.setOnErrorListener(null)
            old.release()
        }
        player = null
    }

    /**
     * The chapter's recording is not on this device yet. Playback rests, paused, at the chapter so that
     * the reader can download it and resume; the notification stays to say why the recitation stopped.
     */
    private fun awaitDownload(track: QuranSurahRecording, positionMs: Long) {
        playWhenReady = false
        pendingSeekMs = null
        releasePlayer()
        abandonFocus()
        publish(QuranAudioController.state.value.copy(
            playing = false, loading = false, error = null, notDownloaded = true, positionMs = positionMs,
            durationMs = track.durationMs, introEndMs = track.timings.first().startMs,
        ))
        updateMetadata()
        persistPosition()
        leaveForeground()
    }

    private fun fail(message: String) {
        playWhenReady = false
        releasePlayer()
        abandonFocus()
        publish(QuranAudioController.state.value.copy(playing = false, loading = false, error = message))
        stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
        notifications.cancel(NOTIFICATION_ID)
        stopSelf()
    }

    private fun stopPlayback() {
        explicitStop = true
        ++generation
        loadJob?.cancel()
        playWhenReady = false
        repeat = null
        repeatRound = 1
        releasePlayer()
        abandonFocus()
        preferences.edit().clear().apply()
        publish(QuranPlaybackState(reciterId = QuranAudioController.state.value.reciterId))
        stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
        notifications.cancel(NOTIFICATION_ID)
        stopSelf()
    }

    override fun onDestroy() {
        if (!explicitStop) {
            if (prepared && !seeking) refreshPosition()
            persistPosition()
        }
        ++generation
        scope.cancel()
        releasePlayer()
        abandonFocus()
        unregisterReceiver(noisyReceiver)
        session.isActive = false
        session.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        notifications.cancel(NOTIFICATION_ID)
        QuranAudioController.serviceRunning = false
        QuranAudioController.publish(QuranAudioController.state.value.copy(playing = false, loading = false))
        super.onDestroy()
    }

    companion object {
        internal const val PREFERENCES = "quran_recitation_resume"
        internal const val ACTION_PLAY = "com.tunisianprayertimes.quran.PLAY"
        internal const val ACTION_RESUME = "com.tunisianprayertimes.quran.RESUME"
        internal const val ACTION_PAUSE = "com.tunisianprayertimes.quran.PAUSE"
        internal const val ACTION_STOP = "com.tunisianprayertimes.quran.STOP"
        internal const val ACTION_SEEK = "com.tunisianprayertimes.quran.SEEK"
        internal const val ACTION_SEEK_VERSE = "com.tunisianprayertimes.quran.SEEK_VERSE"
        internal const val ACTION_NEXT = "com.tunisianprayertimes.quran.NEXT"
        internal const val ACTION_PREVIOUS = "com.tunisianprayertimes.quran.PREVIOUS"
        internal const val ACTION_RECITER = "com.tunisianprayertimes.quran.RECITER"
        internal const val ACTION_REPEAT_OFF = "com.tunisianprayertimes.quran.REPEAT_OFF"
        internal const val EXTRA_RECITER = "reciter"
        internal const val EXTRA_SURAH = "surah"
        internal const val EXTRA_AYAH = "ayah"
        internal const val EXTRA_POSITION = "position"
        internal const val EXTRA_REPEAT_TO_SURAH = "repeatToSurah"
        internal const val EXTRA_REPEAT_TO_AYAH = "repeatToAyah"
        internal const val EXTRA_REPEAT_TIMES = "repeatTimes"
        internal const val KEY_REPEAT = "repeat"
        private const val CHANNEL_ID = "quran_recitation"
        private const val NOTIFICATION_ID = 740_001
    }
}

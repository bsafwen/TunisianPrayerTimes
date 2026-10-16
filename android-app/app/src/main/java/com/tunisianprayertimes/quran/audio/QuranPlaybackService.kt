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
import com.tunisianprayertimes.quran.hifz.HifzRepository
import com.tunisianprayertimes.quran.hifz.HifzSettings
import com.tunisianprayertimes.quran.hifz.HifzLearning
import com.tunisianprayertimes.quran.hifz.todayEpochDay
import com.tunisianprayertimes.wake.AwakeCheckService
import com.tunisianprayertimes.wake.WakeAlarmQueueHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
    /** The verse whose end the previous service had handled where it stopped, for a resume from there. */
    private var restoredEndHandled: Int? = null
    /** Every seek queued behind the one under way was quiet. */
    private var pendingSeekQuiet = true
    private var explicitStop = false
    private var lastPersistedSecond = -1L
    private var lastSessionSecond = -1L
    private var notificationKey: String? = null
    /** Position polling publishes repeatedly; only a new audible verse/day consults the learning ledger. */
    private var lastLearningExposure: Pair<Long, QuranVerseReference>? = null
    private var repeat: QuranRepeatRange? = null
    /** The one-based pass over [repeat] being recited. */
    private var repeatRound = 1
    /** The verse heard now, kept through the silence after it, and its one-based recitation. */
    private var currentVerse: Int? = null
    private var verseRound = 1
    /** The verse whose end was already handled, so its trailing silence does not handle it again. */
    private var verseEndHandled: Int? = null
    /** While a silent pause after a verse runs: when it ends, as elapsed realtime; 0 otherwise. */
    private var gapUntil = 0L
    /** Separate passages recited in turn; [repeat] holds the current one. */
    private var playlist: QuranPlaylist? = null
    private var playlistIndex = 0
    private var playlistRound = 1
    /** The playlist just recited to its end, for «انتهى التدريب»; see [QuranPlaybackState.finishedPlaylist]. */
    private var finishedList: QuranPlaylist? = null
    /** Paused before this verse's hifz point; see [QuranPlaybackState.stoppedBefore]. */
    private var stoppedBefore: QuranVerseReference? = null
    /**
     * The listener's own place when a playlist took over, a drill or a listening to points: the playlist comes back
     * to it when it ends or is stopped, and it is what stays saved meanwhile, so that «درّب» never erases the wird.
     */
    private var returnPlace: QuranReturnPlace? = null
    /** The playlist ended back on [returnPlace]: the place in view is the listener's own again. */
    private var backHome = false
    /** Leaves the foreground once the reader has had time to recite a point stopped before. */
    private var foregroundHold: Job? = null
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
        // Read off the main thread before the first verse ends: whether to stop before the reader's points, and where they are.
        scope.launch(Dispatchers.IO) {
            HifzSettings.read(applicationContext)
            HifzRepository.load(applicationContext)
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
        if (gapUntil > 0L) return (gapUntil - QuranAudioController.elapsedNow()).coerceIn(10L, 100L)
        if (!current.playing) return 500L
        val listening = QuranAudioController.listening.value
        val speed = (playlist?.speed ?: listening.speed).coerceAtLeast(0.25f)
        val verseEnd = currentVerse?.takeIf { verse ->
            playlist != null || listening.verseTimes != 1 || listening.gapFactor > 0f || listening.sleep != null ||
                recording?.let { stopsBeforePoint(it, QuranVerseReference(it.number, verse + 1)) } == true
        }
            ?.let { recording?.endOf(it) }
        val end = listOfNotNull(
            current.repeatWindow?.endMs?.takeIf { current.surah == repeat?.to?.surah },
            verseEnd,
        ).minOrNull() ?: return 100L
        // Wake inside the early window before the boundary: one verse's end is the next one's start.
        return ((end - VerseEndEarlyMs / 2 - current.positionMs) / speed).toLong().coerceIn(10L, 100L)
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
                val position = if (intent.hasExtra(EXTRA_POSITION)) intent.getLongExtra(EXTRA_POSITION, 0L) else null
                // Listening from a chosen place replaces any earlier repetition or drill, and the place it left.
                playlist = null
                returnPlace = null
                backHome = false
                finishedList = null
                stoppedBefore = null
                repeat = repeatRange(intent, intent.getIntExtra(EXTRA_REPEAT_FROM_SURAH, surah), ayah)
                repeatRound = if (repeat != null) intent.getIntExtra(EXTRA_REPEAT_ROUND, 1).coerceAtLeast(1) else 1
                load(
                    intent.getStringExtra(EXTRA_RECITER) ?: QuranAudioController.DEFAULT_RECITER_ID,
                    surah,
                    if (position != null) null else ayah,
                    autoPlay = intent.getBooleanExtra(EXTRA_AUTOPLAY, true),
                    positionMs = position,
                )
            }
            ACTION_PLAYLIST -> QuranPlaylist.decode(intent.getStringExtra(EXTRA_PLAYLIST))?.let { list ->
                ensureForeground()
                // The listener's own place, kept to come back to: never a drill's, nor the rest after one.
                val current = QuranAudioController.state.value
                val own = playlist == null && (finishedList == null || backHome)
                if (own && returnPlace == null && current.surah != null && !current.notDownloaded) {
                    if (prepared && !seeking) refreshPosition()
                    val now = QuranAudioController.state.value
                    returnPlace = QuranReturnPlace(now.reciterId, current.surah, now.positionMs, repeat?.encode(repeatRound))
                }
                backHome = false
                finishedList = null
                stoppedBefore = null
                playlist = list
                playlistIndex = intent.getIntExtra(EXTRA_PLAYLIST_INDEX, 0).coerceIn(0, list.items.lastIndex)
                playlistRound = intent.getIntExtra(EXTRA_PLAYLIST_ROUND, 1).coerceIn(1, list.rounds)
                repeat = list.items[playlistIndex]
                repeatRound = 1
                val first = list.items[playlistIndex].from
                load(intent.getStringExtra(EXTRA_RECITER) ?: QuranAudioController.state.value.reciterId, first.surah, first.ayah,
                    autoPlay = intent.getBooleanExtra(EXTRA_AUTOPLAY, true))
            }
            ACTION_REPEAT_OFF -> cancelRepeat()
            ACTION_REPEAT_HERE -> repeatHere(intent.getIntExtra(EXTRA_SURAH, 0), intent.getIntExtra(EXTRA_AYAH, 0))
            ACTION_SETTINGS -> applySettings()
            ACTION_DISMISS -> dismiss()
            ACTION_RESUME -> { ensureForeground(); resumePlayback() }
            ACTION_PAUSE -> pausePlayback(userInitiated = true)
            ACTION_STOP -> stopPlayback()
            ACTION_SEEK -> seek(intent.getLongExtra(EXTRA_POSITION, 0L), quiet = intent.getBooleanExtra(EXTRA_QUIET, false) && playWhenReady)
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
        finishedList = null
        stoppedBefore = null
        clearGap()
        currentVerse = ayah
        verseRound = 1
        verseEndHandled = null
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
                    // A passage saved at the end of its last verse finished that pass: count it, then start the next.
                    val savedTarget = pendingSeekMs
                    if (window != null && savedTarget != null && savedTarget >= window.endMs - VerseEndEarlyMs - 1L) {
                        when (val step = repeat!!.afterPass(repeatRound)) {
                            is QuranRepeatStep.Again -> repeatRound = step.round
                            QuranRepeatStep.Finished -> repeatRound = 1
                        }
                        pendingSeekMs = window.startMs
                        restoredEndHandled = null
                    }
                    val target = (pendingSeekMs ?: window?.startMs)?.let { window?.clamp(it) ?: it }
                        // A place past the last verse recites the chapter, or the passage, again; a place inside
                        // the last verse plays what is left of it, as a running service would.
                        ?.let { if (it >= (window?.endMs?.minus(1L) ?: track.timings.last().endMs)) window?.startMs ?: 0L else it }
                    pendingSeekMs = null
                    if (target != null && target > 0L) {
                        seek(target)
                        // seek() starts the verse's count afresh; a verse end handled before a restart is not handled twice.
                        restoredEndHandled?.takeIf { it == track.ayahAt(target) }?.let { verse ->
                            currentVerse = verse
                            verseEndHandled = verse
                        }
                        restoredEndHandled = null
                    }
                    else if (playWhenReady) startPrepared()
                    else { refreshPosition(); leaveForeground() }
                }
                media.setOnSeekCompleteListener { loaded ->
                    if (player !== loaded || request != generation) return@setOnSeekCompleteListener
                    seeking = false
                    val queuedTarget = pendingSeekMs
                    val queuedQuiet = pendingSeekQuiet && playWhenReady
                    pendingSeekMs = null
                    pendingSeekQuiet = true
                    if (queuedTarget != null) {
                        seek(queuedTarget, quiet = queuedQuiet)
                        return@setOnSeekCompleteListener
                    }
                    publish(QuranAudioController.state.value.copy(loading = false))
                    refreshPosition()
                    // A paused seek is where a later resume starts, e.g. the rewind after the last counted pass.
                    if (playWhenReady) startPrepared() else { persistPosition(); leaveForeground() }
                }
                media.setOnCompletionListener { loaded ->
                    if (player !== loaded || request != generation) return@setOnCompletionListener
                    // A seek already under way supersedes the end of the file; a repeated pass is counted once.
                    if (seeking) return@setOnCompletionListener
                    val range = repeat
                    // The range's last verse can run to the very end of the file.
                    if (range != null && surah >= range.to.surah) finishRepeatPass()
                    // A passage that goes on into the next chapter goes on, whatever «بعد السورة» says.
                    else if (surah < 114 && playWhenReady && (range != null || QuranAudioController.listening.value.continueAfterSurah)) {
                        load(reciterId, surah + 1, null, autoPlay = true)
                    }
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
            restoredEndHandled = preferences.getInt(KEY_END_HANDLED, 0).takeIf { it > 0 }
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
            finishedList = null
            stoppedBefore = null
            applySpeed(media)
            publish(QuranAudioController.state.value.copy(playing = true, loading = false, error = null, interruption = null))
            refreshPosition()
            // The end of a repeated range may be milliseconds away.
            pollWake.trySend(Unit)
        } catch (error: IllegalStateException) {
            fail("تعذّر تشغيل التلاوة. أعد المحاولة.")
        }
    }

    /** [keepForeground]: the notification stays, as after a stop before a point that the reader resumes from the lock screen. */
    private fun pausePlayback(userInitiated: Boolean, temporaryFocusLoss: Boolean = false, keepForeground: Boolean = false) {
        if (userInitiated || !temporaryFocusLoss) {
            playWhenReady = false
            resumeAfterFocusGain = false
        }
        // Pausing during the silence after a verse rests at that verse's end; play goes on from there.
        if (gapUntil > 0L) clearGap()
        if (prepared) runCatching { player?.let { if (it.isPlaying) it.pause() } }
        if (!seeking) refreshPosition()
        publish(QuranAudioController.state.value.copy(playing = false))
        persistPosition()
        if (!temporaryFocusLoss) {
            abandonFocus()
            if (keepForeground) notifications.notify(NOTIFICATION_ID, buildNotification()) else leaveForeground()
        }
    }

    private fun alarmIsActive(): Boolean = WakeAlarmQueueHolder.queue.current != null || AwakeCheckService.isRunning.value

    private fun pauseForAlarm() {
        // Require an explicit play action once the alarm ends; never resume over it.
        pausePlayback(userInitiated = true)
        publish(QuranAudioController.state.value.copy(interruption = QuranInterruption.Alarm))
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
                publish(QuranAudioController.state.value.copy(interruption = QuranInterruption.OtherAudio))
            }
        }
    }

    /** [quiet] keeps the controls steady while a repeated range silently returns to its start. */
    private fun seek(positionMs: Long, quiet: Boolean = false) {
        finishedList = null
        stoppedBefore = null
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
        // The silence after a verse ends with any move; the state is read once it is cleared.
        val pausedAfter = QuranAudioController.state.value.gapAfter.takeIf { gapUntil > 0L }
        if (gapUntil > 0L) clearGap()
        val now = QuranAudioController.state.value
        val bounded = positionMs.coerceIn(0L, (now.durationMs - 1L).coerceAtLeast(0L))
        val target = now.repeatWindow?.clamp(bounded) ?: bounded
        val targetVerse = recording?.ayahAt(target)
        // Another verse starts its own count of recitations.
        if (targetVerse != currentVerse) verseRound = 1
        if (!quiet || targetVerse != currentVerse) verseEndHandled = null
        if (pausedAfter != null && targetVerse == pausedAfter) {
            // Back into the verse whose silence was under way: its end brings the silence again,
            // not another round of its repetitions.
            verseEndHandled = null
            val times = if (playlist != null) 1 else QuranAudioController.listening.value.verseTimes
            if (times > 0) verseRound = times
        }
        currentVerse = targetVerse ?: currentVerse.takeIf { quiet }
        if (seeking) {
            // MediaPlayer may queue native seeks. Keep only the newest target ourselves,
            // so an earlier completion cannot restart audio at an obsolete position.
            pendingSeekQuiet = (pendingSeekMs == null || pendingSeekQuiet) && quiet
            pendingSeekMs = target
            publish(now.copy(positionMs = target, ayah = if (quiet) targetVerse else null))
            return
        }
        try {
            // pause() is invalid in Prepared; initial verse playback seeks before start().
            if (media.isPlaying) media.pause()
            seeking = true
            publish(
                if (quiet) now.copy(positionMs = target, ayah = targetVerse)
                else now.copy(playing = false, loading = true, positionMs = target, ayah = null),
            )
            media.seekTo(target, MediaPlayer.SEEK_CLOSEST)
        } catch (error: IllegalStateException) {
            fail("تعذّر الانتقال إلى موضع التلاوة.")
        }
    }

    private fun seekVerse(surah: Int, ayah: Int, quiet: Boolean = false) {
        if (surah !in 1..114 || ayah < 1) return
        // Going to a verse outside the repeated range ends the repetition, and a drill with it: the reader chose a new
        // place, which the drill's old one no longer replaces.
        if (repeat?.contains(QuranVerseReference(surah, ayah)) == false) {
            repeat = null
            repeatRound = 1
            if (playlist != null) returnPlace = null
            playlist = null
        }
        val current = QuranAudioController.state.value
        val track = recording
        verseRound = 1
        if (current.surah == surah && track != null) {
            // Refresh the range's bounds before seek() reads them.
            publish(current)
            track.startOf(ayah)?.let {
                seek(it, quiet)
                currentVerse = ayah
                verseEndHandled = null
            }
        } else load(current.reciterId, surah, ayah, autoPlay = playWhenReady)
    }

    private fun adjacentVerse(next: Boolean) {
        // In a drill, previous and next move between its passages, from the lock screen too.
        playlist?.let { list ->
            val step = if (next) list.after(playlistIndex, playlistRound) else list.before(playlistIndex, playlistRound)
            if (step is QuranPlaylistStep.Item) playItem(step.index, step.round, quiet = false)
            return
        }
        val track = recording ?: return
        val current = QuranAudioController.state.value
        val position = current.positionMs
        val verse = if (next) {
            val target = track.timings.firstOrNull { it.startMs > position }
            when {
                target != null -> QuranVerseReference(track.number, target.ayah)
                track.number < 114 -> QuranVerseReference(track.number + 1, 1)
                else -> QuranVerseReference(track.number, track.timings.last().ayah)
            }
        } else when (val previous = track.previousTarget(position, pausedAfter = current.gapAfter)) {
            // Within the first moments of a verse it goes to the one before; later, it hears the same verse again.
            is QuranPreviousTarget.Verse -> QuranVerseReference(track.number, previous.ayah)
            QuranPreviousTarget.PreviousChapter -> {
                val before = recordings.getOrNull(track.number - 2)
                if (before != null) QuranVerseReference(before.number, before.timings.last().ayah)
                else QuranVerseReference(track.number, track.timings.first().ayah)
            }
        }
        // Skipping stays inside a repeated range: past either end it returns to the first verse.
        val destination = repeat?.takeIf { verse !in it }?.from ?: verse
        seekVerse(destination.surah, destination.ayah)
    }

    /** One pass over the repeated range ended, at its last verse's boundary or at the end of the file. */
    private fun finishRepeatPass() {
        playlist?.let { list ->
            when (val step = list.after(playlistIndex, playlistRound)) {
                is QuranPlaylistStep.Item -> playItem(step.index, step.round, quiet = playWhenReady)
                QuranPlaylistStep.Finished -> {
                    // Over: back to the listener's own place, paused, «انتهى التدريب» said with it.
                    returnPlace?.let { back ->
                        goBack(back, ended = list)
                        return
                    }
                    // With no place to go back to, it rests after its last verse, that verse's end handled, and play goes
                    // on from there as ordinary recitation; «أعده» recites the list again. Never the whole list again on a play.
                    val last = list.items.last().to
                    playlist = null
                    repeat = null
                    repeatRound = 1
                    pausePlayback(userInitiated = true)
                    recording?.takeIf { it.number == last.surah }?.endOf(last.ayah)?.let { end ->
                        seek(end - 1L, quiet = true)
                        currentVerse = last.ayah
                        verseEndHandled = last.ayah
                    }
                    finishedList = list
                    publish(QuranAudioController.state.value)
                    persistPosition()
                }
            }
            return
        }
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

    /** Goes to the playlist's passage [index] of round [round]. */
    private fun playItem(index: Int, round: Int, quiet: Boolean) {
        val item = playlist?.items?.getOrNull(index) ?: return
        playlistIndex = index
        playlistRound = round
        repeat = item
        repeatRound = 1
        seekVerse(item.from.surah, item.from.ayah, quiet = quiet && item.from.surah == recording?.number)
    }

    private fun cancelRepeat() {
        if (repeat == null) return
        // A drill ended early goes back to the listener's own place, as when it ends by itself.
        if (playlist != null) returnPlace?.let { back ->
            goBack(back, ended = null)
            return
        }
        playlist = null
        repeat = null
        repeatRound = 1
        refreshSpeed()
        publish(QuranAudioController.state.value)
        persistPosition()
    }

    /** Repeats [ayah] of the chapter being recited, from where the recitation is, until it is stopped. */
    private fun repeatHere(surah: Int, ayah: Int) {
        val track = recording ?: return
        if (track.number != surah || track.startOf(ayah) == null) return
        finishedList = null
        stoppedBefore = null
        playlist = null
        repeat = QuranRepeatRange(QuranVerseReference(surah, ayah), QuranVerseReference(surah, ayah))
        repeatRound = 1
        refreshSpeed()
        val current = QuranAudioController.state.value
        publish(current)
        val window = QuranAudioController.state.value.repeatWindow ?: return
        // From the silence after the verse, or from elsewhere, it starts the verse again.
        if (current.positionMs !in window.startMs until window.endMs || gapUntil > 0L) seekVerse(surah, ayah, quiet = playWhenReady)
        persistPosition()
    }

    private fun applySettings() {
        refreshSpeed()
        // A cancelled pause takes effect now; a drill keeps its own pause.
        if (gapUntil > 0L && (playlist?.gapFactor ?: QuranAudioController.listening.value.gapFactor) == 0f) endGap()
    }

    /** The speed in force changes at once while playing, e.g. when a drill with its own speed ends. */
    private fun refreshSpeed() {
        val media = player ?: return
        if (prepared && runCatching { media.isPlaying }.getOrDefault(false)) applySpeed(media)
    }

    private fun applySpeed(media: MediaPlayer) {
        val speed = playlist?.speed ?: QuranAudioController.listening.value.speed
        runCatching {
            if (media.playbackParams.speed != speed) media.playbackParams = media.playbackParams.setSpeed(speed)
        }
    }

    /** The notification was swiped away while paused: the place is kept, shown in the reader as a saved place. */
    private fun dismiss() {
        if (playWhenReady || QuranAudioController.state.value.playing) return
        if (prepared && !seeking) refreshPosition()
        persistPosition()
        recording?.let(QuranAudioController::rememberTrack)
        QuranAudioController.publish(restoredState())
        explicitStop = true
        ++generation
        loadJob?.cancel()
        releasePlayer()
        abandonFocus()
        stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
        notifications.cancel(NOTIFICATION_ID)
        stopSelf()
    }

    /**
     * The place as it stays once no service runs: shown paused, moved verse by verse, resumed by a new
     * service. A drill does not outlive its service, since a new one could recite only its current passage.
     */
    private fun restoredState(): QuranPlaybackState {
        returnPlace?.takeIf { playlist != null }?.let { return returnState(it) }
        val current = QuranAudioController.state.value.copy(playing = false, loading = false, restored = true)
        return if (current.playlist == null) current.copy(finishedPlaylist = null)
        else current.copy(playlist = null, playlistIndex = 0, playlistRound = 0, repeat = null, repeatRound = 0, repeatWindow = null, finishedPlaylist = null)
    }

    /**
     * The verse being recited reached its end: it is recited again, the sleep timer ends here, a
     * silent pause starts, or the recitation goes on. True when nothing else should act on this poll.
     */
    private fun handleVerseEnd(track: QuranSurahRecording, verse: Int, overshot: Boolean = false): Boolean {
        if (verseEndHandled == verse) return false
        val start = track.startOf(verse) ?: return false
        val end = track.endOf(verse) ?: return false
        val listening = QuranAudioController.listening.value
        val sleep = listening.sleep
        val sleepHere = sleep != null && when (sleep.kind) {
            QuranSleep.Kind.Minutes -> QuranAudioController.elapsedNow() >= sleep.untilElapsedMs
            else -> sleep.verse == QuranVerseReference(track.number, verse)
        }
        verseEndHandled = verse
        // A drill recites each verse once, with its own silence and speed.
        val drill = playlist
        val times = if (drill != null) 1 else listening.verseTimes
        // Only on the recitation that leaves the verse: never while it is still repeated, nor in an endless repetition.
        val leaving = times != 0 && verseRound >= times
        val beforePoint = QuranVerseReference(track.number, verse + 1).takeIf { drill == null && leaving && stopsBeforePoint(track, it) }
        val step = quranVerseEnd(
            times, verseRound, sleepHere, drill?.gapFactor ?: listening.gapFactor, end - start, drill?.speed ?: listening.speed,
            stopBefore = beforePoint != null,
        )
        return when (step) {
            is QuranVerseEnd.Again -> {
                verseRound = step.round
                seek(start, quiet = true)
                currentVerse = verse
                verseEndHandled = null
                publish(QuranAudioController.state.value)
                true
            }
            QuranVerseEnd.Sleep -> {
                // The listener's own timer ends here, or the next verse is a point to recite from memory first.
                if (sleepHere) QuranAudioController.clearSleep()
                verseRound = 1
                pausePlayback(userInitiated = true, keepForeground = beforePoint != null)
                // Rest on the verse that ended, not on the first moments of the next one, with its end
                // already handled: play goes on to the next verse instead of hearing this end again.
                if (overshot) seek(end - 1L, quiet = true)
                currentVerse = verse
                verseEndHandled = verse
                if (beforePoint != null) {
                    stoppedBefore = beforePoint
                    publish(QuranAudioController.state.value)
                    persistPosition()
                    // Ten minutes to recite it from memory with the controls on the lock screen, as a media app keeps them.
                    foregroundHold?.cancel()
                    foregroundHold = scope.launch {
                        delay(10 * 60_000L)
                        if (!playWhenReady) leaveForeground()
                    }
                }
                true
            }
            is QuranVerseEnd.Pause -> {
                verseRound = 1
                startGap(verse, step.durationMs)
                true
            }
            QuranVerseEnd.Continue -> {
                verseRound = 1
                false
            }
        }
    }

    /**
     * «توقف قبل الموضع لأسمّعه»: [next] has a point the reader keeps a heads-up for, within the passage repeated if
     * any. Decided here, in the service, so that it also happens with the screen off.
     */
    private fun stopsBeforePoint(track: QuranSurahRecording, next: QuranVerseReference): Boolean {
        if (track.startOf(next.ayah) == null) return false
        if (HifzRepository.points.value.none { it.verse == next && it.headsUp }) return false
        if (repeat?.contains(next) == false) return false
        // Within a passage repeated to memorise it, only its first pass stops: the next passes are its repetition.
        if (repeat != null && repeatRound > 1) return false
        return HifzSettings.read(this).stopBefore
    }

    private fun startGap(verse: Int, durationMs: Long) {
        val media = player ?: return
        runCatching { if (media.isPlaying) media.pause() }
        gapUntil = QuranAudioController.elapsedNow() + durationMs
        val current = QuranAudioController.state.value
        publish(current.copy(ayah = verse, gapAfter = verse, gapRemainingMs = durationMs, gapTotalMs = durationMs))
    }

    private fun tickGap() {
        val left = gapUntil - QuranAudioController.elapsedNow()
        if (left <= 0L) endGap()
        else publish(QuranAudioController.state.value.copy(gapRemainingMs = left))
    }

    /** The silence after a verse ended: the passage starts again at its end, otherwise recitation goes on. */
    private fun endGap() {
        val verse = QuranAudioController.state.value.gapAfter
        clearGap()
        val range = repeat
        val track = recording
        if (range != null && track != null && verse != null && track.number == range.to.surah && verse == range.to.ayah) {
            finishRepeatPass()
            return
        }
        if (playWhenReady) startPrepared()
    }

    private fun clearGap() {
        if (gapUntil == 0L && QuranAudioController.state.value.gapAfter == null) return
        gapUntil = 0L
        val current = QuranAudioController.state.value
        publish(current.copy(gapAfter = null, gapRemainingMs = 0L, gapTotalMs = 0L))
    }

    private fun refreshPosition(checkRepeat: Boolean = false) {
        val media = player ?: return
        if (!prepared || seeking) return
        if (gapUntil > 0L) {
            if (checkRepeat) tickGap()
            return
        }
        val actual = runCatching { media.currentPosition.toLong() }.getOrNull() ?: return
        val track = recording
        if (checkRepeat && track != null && QuranAudioController.state.value.playing) {
            // Before following the verse now heard: a poll that lands just past a verse's end, already in
            // the next verse, still handles that end once.
            val verse = currentVerse
            val end = verse?.let(track::endOf)
            if (verse != null && end != null && actual >= end - VerseEndEarlyMs && actual < end + LateVerseEndMs &&
                handleVerseEnd(track, verse, overshot = actual >= end)) return
        }
        track?.ayahAt(actual)?.let { heard ->
            if (heard != currentVerse) {
                currentVerse = heard
                verseEndHandled = null
            }
        }
        val current = QuranAudioController.state.value
        val window = current.repeatWindow
        if (checkRepeat && current.playing && window != null && actual >= window.endMs - VerseEndEarlyMs && current.surah == repeat?.to?.surah) {
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
        val listening = QuranAudioController.listening.value
        val list = playlist
        val state = value.copy(
            restored = false,
            verseRound = if (listening.verseTimes == 1 || value.surah == null || list != null) 0 else verseRound,
            playlist = list,
            playlistIndex = if (list == null) 0 else playlistIndex,
            playlistRound = if (list == null) 0 else playlistRound,
            repeat = range,
            repeatRound = if (range == null) 0 else repeatRound,
            repeatWindow = range?.let { recording?.takeIf { track -> track.number == value.surah }?.repeatWindow(it) },
            finishedPlaylist = finishedList,
            stoppedBefore = stoppedBefore,
        )
        // Quiet seeks can publish a target while their old playing flag is still set. Wait until it actually starts.
        if (prepared && !seeking && state.playing && !state.loading && state.gapAfter == null) {
            val surah = state.surah
            val ayah = state.ayah
            if (surah != null && ayah != null && ayah > 0) {
                val heard = todayEpochDay() to QuranVerseReference(surah, ayah)
                if (heard != lastLearningExposure) {
                    HifzLearning.recordExposure(applicationContext, heard.second, heard.first)
                    lastLearningExposure = heard
                }
            }
        }
        QuranAudioController.publish(state)
        val key = "${state.reciterId}:${state.surah}:${state.ayah}:${state.playing}:${state.loading}:${state.error}:${state.notDownloaded}:$range:$repeatRound:${state.interruption}:${list?.title}:$playlistIndex:$playlistRound:${finishedList?.title}:$stoppedBefore"
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
        val round = current.playlist?.let { list -> "${list.title} · ${current.playlistIndex + 1} من ${list.items.size}" }
            ?: current.repeat?.let { range -> range.times?.let { "التكرار ${current.repeatRound} من $it" } ?: "تكرار بلا توقف" }
        val detail = listOfNotNull(reciter?.name, current.ayah?.let { "الآية $it" }, round).joinToString(" · ")
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tab_quran)
            .setContentTitle(surahTitle(current.surah))
            .setContentText(current.error ?: when {
                current.notDownloaded -> "السورة غير محمّلة على الجهاز · افتح التطبيق لتنزيلها"
                current.interruption == QuranInterruption.Alarm -> "توقفت التلاوة للمنبّه · اضغط تشغيل للمتابعة"
                current.interruption == QuranInterruption.OtherAudio -> "توقفت التلاوة لصوت آخر · اضغط تشغيل للمتابعة"
                // Said where the reader may be, the phone in a pocket: why it stopped, and what to do.
                !active && current.stoppedBefore != null -> "توقفت قبل الموضع · سمّع الآية ${current.stoppedBefore.ayah} من حفظك ثم اضغط تشغيل"
                !active && current.finishedPlaylist != null -> "${quranPlaylistEnded(current.finishedPlaylist.title)} · " + when {
                    backHome -> "اضغط تشغيل لتتابع استماعك" + (current.ayah?.let { " من الآية $it" } ?: "")
                    else -> current.ayah?.let { "اضغط تشغيل للمتابعة بعد الآية $it" } ?: "اضغط تشغيل للمتابعة"
                }
                else -> detail
            })
            .setContentIntent(openReaderIntent())
            .setDeleteIntent(actionIntent(ACTION_DISMISS))
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
        // During a drill the listener's own place is the one kept, should the service end before it does.
        returnPlace?.takeIf { playlist != null }?.let { back ->
            preferences.edit().putString(EXTRA_RECITER, back.reciterId).putInt(EXTRA_SURAH, back.surah)
                .putLong(EXTRA_POSITION, back.positionMs).putString(KEY_REPEAT, back.repeat).putInt(KEY_END_HANDLED, 0)
                .remove(KEY_STOPPED_BEFORE).putLong(KEY_SAVED_AT, System.currentTimeMillis()).apply()
            return
        }
        val state = QuranAudioController.state.value
        val surah = state.surah ?: return
        preferences.edit().putString(EXTRA_RECITER, state.reciterId).putInt(EXTRA_SURAH, surah)
            .putLong(EXTRA_POSITION, state.positionMs).putString(KEY_REPEAT, repeat?.takeIf { playlist == null }?.encode(repeatRound))
            .putInt(KEY_END_HANDLED, verseEndHandled?.takeIf { it == recording?.ayahAt(state.positionMs) } ?: 0)
            .putString(KEY_STOPPED_BEFORE, stoppedBefore?.let { "${it.surah}:${it.ayah}" })
            .putLong(KEY_SAVED_AT, System.currentTimeMillis()).apply()
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

    /** Back to the listener's own place, paused: after a drill ([ended], said as «انتهى التدريب»), or one ended early. */
    private fun goBack(back: QuranReturnPlace, ended: QuranPlaylist?) {
        returnPlace = null
        playlist = null
        stoppedBefore = null
        val saved = decodeQuranRepeat(back.repeat)
        repeat = saved?.first
        repeatRound = saved?.second?.coerceAtLeast(1) ?: 1
        pausePlayback(userInitiated = true)
        load(back.reciterId, back.surah, null, autoPlay = false, positionMs = back.positionMs)
        finishedList = ended
        backHome = true
        publish(QuranAudioController.state.value)
        persistPosition()
    }

    /** The place [back] as shown with no service: paused, saved, resumed by a new service. */
    private fun returnState(back: QuranReturnPlace): QuranPlaybackState {
        val track = recordings.getOrNull(back.surah - 1)
        val saved = decodeQuranRepeat(back.repeat)
        track?.let(QuranAudioController::rememberTrack)
        return QuranPlaybackState(
            reciterId = back.reciterId, surah = back.surah, ayah = track?.ayahAt(back.positionMs), positionMs = back.positionMs,
            durationMs = track?.durationMs ?: 0L, introEndMs = track?.timings?.firstOrNull()?.startMs ?: 0L,
            repeat = saved?.first, repeatRound = saved?.second ?: 0, restored = true, savedAtMs = System.currentTimeMillis(),
        )
    }

    private fun stopPlayback() {
        // A drill or a listening to points stopped: the recitation ends, and the listener's own place stays saved, as
        // after a dismiss, rather than going with it.
        returnPlace?.takeIf { playlist != null }?.let { back ->
            persistPosition()
            returnPlace = null
            playlist = null
            finishedList = null
            stoppedBefore = null
            repeat = null
            repeatRound = 1
            explicitStop = true
            ++generation
            loadJob?.cancel()
            gapUntil = 0L
            playWhenReady = false
            releasePlayer()
            abandonFocus()
            QuranAudioController.publish(returnState(back))
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
            notifications.cancel(NOTIFICATION_ID)
            stopSelf()
            return
        }
        explicitStop = true
        ++generation
        loadJob?.cancel()
        gapUntil = 0L
        playWhenReady = false
        playlist = null
        finishedList = null
        stoppedBefore = null
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
        val current = QuranAudioController.state.value
        // Stopped by the system while paused: the place stays usable without a service, as after a dismiss.
        if (!explicitStop && current.surah != null) {
            recording?.let(QuranAudioController::rememberTrack)
            QuranAudioController.publish(restoredState())
        } else QuranAudioController.publish(current.copy(playing = false, loading = false))
        super.onDestroy()
    }

    /** The listener's place a playlist will come back to; see [returnPlace]. */
    private data class QuranReturnPlace(val reciterId: String, val surah: Int, val positionMs: Long, val repeat: String?)

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
        internal const val ACTION_REPEAT_HERE = "com.tunisianprayertimes.quran.REPEAT_HERE"
        internal const val ACTION_SETTINGS = "com.tunisianprayertimes.quran.SETTINGS"
        internal const val ACTION_DISMISS = "com.tunisianprayertimes.quran.DISMISS"
        internal const val ACTION_PLAYLIST = "com.tunisianprayertimes.quran.PLAYLIST"
        internal const val EXTRA_PLAYLIST = "playlist"
        internal const val EXTRA_PLAYLIST_INDEX = "playlistIndex"
        internal const val EXTRA_PLAYLIST_ROUND = "playlistRound"
        internal const val EXTRA_QUIET = "quiet"
        internal const val EXTRA_AUTOPLAY = "autoPlay"
        internal const val EXTRA_RECITER = "reciter"
        internal const val EXTRA_SURAH = "surah"
        internal const val EXTRA_AYAH = "ayah"
        internal const val EXTRA_POSITION = "position"
        internal const val EXTRA_REPEAT_TO_SURAH = "repeatToSurah"
        internal const val EXTRA_REPEAT_TO_AYAH = "repeatToAyah"
        internal const val EXTRA_REPEAT_TIMES = "repeatTimes"
        internal const val EXTRA_REPEAT_FROM_SURAH = "repeatFromSurah"
        internal const val EXTRA_REPEAT_ROUND = "repeatRound"
        internal const val KEY_REPEAT = "repeat"
        internal const val KEY_SAVED_AT = "savedAt"
        internal const val KEY_END_HANDLED = "endHandled"
        internal const val KEY_STOPPED_BEFORE = "stoppedBefore"
        /** A verse end is handled this much early, so none of the next verse is heard before a repeat or pause. */
        private const val VerseEndEarlyMs = 40L
        /** A verse end noticed this late is still handled; later than that, playback jumped elsewhere. */
        private const val LateVerseEndMs = 1_500L
        private const val CHANNEL_ID = "quran_recitation"
        private const val NOTIFICATION_ID = 740_001
    }
}

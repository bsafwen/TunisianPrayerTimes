package com.tunisianprayertimes.quran.audio

import android.content.Context
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.PlaybackState
import com.tunisianprayertimes.R
import com.tunisianprayertimes.quran.assets.QuranAssets
import com.tunisianprayertimes.quran.hifz.*
import com.tunisianprayertimes.ui.hifzVerseName
import kotlinx.coroutines.*
import java.util.UUID

/** Android Auto is an adapter to the same Hifz state machines used by QuranScreen. */
internal class QuranAutoReview(
    private val context: Context,
    val sessions: HifzSessionCoordinator,
    private val scope: CoroutineScope,
    private val player: Player,
) : HifzSessionAudio {
    interface Player {
        fun foreground()
        fun idle()
        fun pause()
        fun resume()
        fun play(list: QuranPlaylist, reciter: String, index: Int, round: Int, position: Long?, surah: Int?)
        fun restore(place: String?)
        fun refresh()
    }
    private var selected = false
    private var request: Job? = null
    private var preparing = false
    private var listenId: String? = null
    val active get() = selected || sessions.hasSession && (sessions.carActive || sessions.active)
    val listeningOnly get() = listenId != null && QuranAudioController.state.value.playlist?.requestId == listenId
    fun owns(requestId: String?) = sessions.owns(requestId) || requestId != null && requestId == listenId

    fun start(mediaId: String) {
        request?.cancel()
        player.pause()
        player.foreground()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                sessions.initialize()
                sessions.useCar()
                if (mediaId == LISTEN_ID) {
                    sessions.pause()
                    sessions.leaveCar()
                    selected = false
                    val list = sessions.catalog?.let { hifzListen(it, HifzRepository.points.value) }
                    if (list != null) {
                        listenId = "auto-hifz-listen:${UUID.randomUUID()}"
                        prepare(list.copy(requestId = listenId), QuranAudioController.state.value.reciterId)
                    } else sessions.audioError(context.getString(R.string.quran_auto_review_empty))
                } else {
                    selected = true
                    val ready = when (mediaId) {
                        DAILY_ID -> sessions.startDaily()
                        NOTEBOOK_ID -> sessions.startNotebook()
                        else -> { sessions.resume(); sessions.hasSession }
                    }
                    if (ready) onPlay() else player.idle()
                }
                player.refresh()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { sessions.audioError(context.getString(R.string.quran_auto_review_failed)); player.idle() }
        }
        request = job; job.start()
    }

    fun onPlay() {
        selected = true
        sessions.useCar()
        sessions.resume()
        if (sessions.savingAnswer) { sessions.retry(); return }
        val saved = sessions.resumableClip()
        if (saved != null) {
            val current = QuranAudioController.state.value
            if (sessions.owns(current.playlist?.requestId) && !current.notDownloaded && current.error == null) player.resume()
            else prepare(saved.first, sessions.savedClipReciter(), saved.second, saved.third,
                sessions.savedClipPosition(), sessions.savedClipSurah())
        } else when (sessions.view().phase) {
            HifzSessionPhase.Question -> sessions.cue()
            HifzSessionPhase.Assessment -> sessions.answerAudio()
            HifzSessionPhase.Training -> sessions.drill()
            HifzSessionPhase.Finished -> player.idle()
        }
        player.refresh()
    }

    fun pauseAudio() {
        request?.cancel(); preparing = false
        player.pause()
        sessions.notePlayback(QuranAudioController.state.value, force = true)
        scope.launch { sessions.persist() }
        player.refresh()
    }

    fun stop() { pauseAudio(); sessions.pause(); player.idle(); player.refresh() }

    fun leaveForListening() {
        val leaving = active
        request?.cancel(); preparing = false
        listenId = null
        if (!leaving) return
        sessions.pause()
        sessions.leaveCar()
        sessions.reviewReturn.value = null
        sessions.reviewPlayed.value = false
        sessions.reviewAudio.value = false
        selected = false
    }

    fun detach() {
        if (active) { stop(); sessions.leaveCar() }
        selected = false
    }

    override fun play(list: QuranPlaylist) { prepare(list, sessions.savedClipReciter()) }
    override fun pause() { player.pause() }
    override fun restore(place: String?) { request?.cancel(); preparing = false; selected = false; player.restore(place) }

    private fun prepare(list: QuranPlaylist, reciterId: String, index: Int = 0, round: Int = 1,
        position: Long? = null, surah: Int? = null, ready: (() -> Unit)? = null) {
        request?.cancel()
        player.foreground()
        preparing = true
        player.refresh()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val available = withContext(Dispatchers.IO) {
                    val tracks = QuranRecitationTimings.load(context, QuranAudioController.reciter(reciterId))
                    list.items.all { range ->
                        tracks[range.from.surah - 1].startOf(range.from.ayah) != null &&
                            tracks[range.to.surah - 1].endOf(range.to.ayah) != null &&
                            (range.from.surah..range.to.surah).all { QuranAssets.resolve(context, tracks[it - 1].assetPath) != null }
                    }
                }
                preparing = false
                if (list.requestId?.startsWith("hifz-session:") == true &&
                    (!sessions.owns(list.requestId) || !sessions.active || !sessions.carActive)) return@launch
                if (!available) {
                    sessions.audioError(context.getString(R.string.quran_auto_review_download))
                    player.idle(); player.refresh(); return@launch
                }
                sessions.dismissError()
                if (ready != null) ready() else player.play(list, reciterId, index, round, position, surah)
            } catch (cancelled: CancellationException) { preparing = false; throw cancelled }
            catch (_: Exception) {
                preparing = false
                sessions.audioError(context.getString(R.string.quran_auto_review_failed))
                player.idle()
            }
            player.refresh()
        }
        request = job; job.start()
    }

    /** The action itself names its question and phase. A late callback cannot assess the next question. */
    fun customAction(action: String): Boolean {
        if (!action.startsWith(ACTION_PREFIX)) return false
        val view = sessions.view()
        val prefix = "$ACTION_PREFIX${view.token}/"
        if (!active || !action.startsWith(prefix) || sessions.busy) return true
        sessions.useCar()
        when (action.removePrefix(prefix)) {
            "reveal" -> sessions.list("answer")?.let { list ->
                prepare(list, QuranAudioController.state.value.reciterId, ready = {
                    if (sessions.view().token == view.token && sessions.reveal()) sessions.answerAudio()
                })
            }
            "held" -> sessions.assess(HifzGrade.Held)
            "hesitated" -> sessions.assess(HifzGrade.Hesitated)
            "again" -> sessions.assess(HifzGrade.Again)
            "drill" -> sessions.drill()
            "cue" -> sessions.cue()
            "retry" -> sessions.retry()
            "return" -> { sessions.close(); selected = false }
            "continue" -> { sessions.resume(); player.refresh() }
            "more" -> { sessions.startDaily(newRound = true); player.refresh() }
        }
        player.refresh()
        return true
    }

    fun next() { if (!sessions.savingAnswer) sessions.next(); player.refresh() }
    fun previous() { if (!sessions.savingAnswer) sessions.previous(); player.refresh() }

    fun playbackState(): PlaybackState {
        val view = sessions.view()
        val current = QuranAudioController.state.value
        val audio = owns(current.playlist?.requestId)
        val failure = sessions.error ?: current.error?.takeIf { audio }
        val status = when {
            failure != null -> PlaybackState.STATE_ERROR
            sessions.busy || preparing || audio && current.loading -> PlaybackState.STATE_BUFFERING
            audio && current.playing -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_PAUSED
        }
        var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP or
            PlaybackState.ACTION_PLAY_FROM_MEDIA_ID or PlaybackState.ACTION_PLAY_FROM_SEARCH
        if (audio) actions = actions or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_REWIND
        if (view.phase == HifzSessionPhase.Training || view.mode == HifzSessionMode.Notebook && view.index < view.total) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
        if (view.mode == HifzSessionMode.Notebook && view.index > 0) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        val state = PlaybackState.Builder().setActions(actions)
            .setState(status, if (audio) current.positionMs else 0L, if (audio && current.playing) 1f else 0f)
        fun add(command: String, label: Int, icon: Int) {
            state.addCustomAction(PlaybackState.CustomAction.Builder("$ACTION_PREFIX${view.token}/$command", context.getString(label), icon).build())
        }
        if (sessions.savingAnswer || failure != null) add("retry", R.string.quran_auto_review_retry, R.drawable.ic_quran_repeat)
        else when (view.phase) {
            HifzSessionPhase.Question -> {
                add("reveal", R.string.quran_auto_review_reveal, R.drawable.ic_quran_auto_reveal)
                if (view.point?.verse?.ayah?.let { it > 1 } == true) add("cue", R.string.quran_auto_review_cue, R.drawable.ic_quran_headphones)
            }
            HifzSessionPhase.Assessment -> {
                if (!view.assisted) add("held", R.string.quran_auto_review_held, R.drawable.ic_quran_auto_held)
                add("hesitated", R.string.quran_auto_review_hesitated, R.drawable.ic_quran_auto_hesitated)
                add("again", R.string.quran_auto_review_again, R.drawable.ic_quran_auto_again)
            }
            HifzSessionPhase.Training -> add("drill", R.string.quran_auto_review_drill, R.drawable.ic_quran_repeat)
            HifzSessionPhase.Finished -> {
                if (sessions.reviewState.stopping) add("continue", R.string.quran_auto_review_continue, R.drawable.ic_play_arrow)
                else add("more", R.string.quran_auto_review_more, R.drawable.ic_quran_repeat)
            }
        }
        add("return", R.string.quran_auto_review_return, R.drawable.ic_stop)
        failure?.let(state::setErrorMessage)
        return state.build()
    }

    fun metadata(): MediaMetadata {
        val view = sessions.view()
        val current = QuranAudioController.state.value
        val title = when {
            sessions.error != null && view.point == null -> context.getString(R.string.quran_auto_review)
            view.phase == HifzSessionPhase.Finished -> context.getString(R.string.quran_auto_review_result)
            view.phase == HifzSessionPhase.Question -> view.question
            else -> view.point?.let { p -> sessions.catalog?.let { hifzVerseName(it, p.verse) } }.orEmpty()
        }
        val subtitle = sessions.error ?: when (view.phase) {
            HifzSessionPhase.Question -> context.getString(R.string.quran_auto_review_question_hint)
            HifzSessionPhase.Assessment -> context.getString(if (view.assisted) R.string.quran_auto_review_assisted_hint else R.string.quran_auto_review_assessment_hint)
            HifzSessionPhase.Training -> context.getString(R.string.quran_auto_review_training_hint)
            HifzSessionPhase.Finished -> "دون مساعدة ${view.tally.held} · بتردّد ${view.tally.hesitated} · نسيت ${view.tally.again}" +
                if (view.deferred > 0) " · مؤجّلة ${view.deferred}" else ""
        }
        val progress = if (view.point != null) "السؤال ${view.index + 1} من ${view.total}" else ""
        val mode = context.getString(if (view.mode == HifzSessionMode.Notebook) R.string.quran_auto_review_notebook else R.string.quran_auto_review_daily)
        return MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_MEDIA_ID, CONTINUE_ID)
            .putString(MediaMetadata.METADATA_KEY_TITLE, title).putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, subtitle).putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION, "$mode · $progress")
            .putString(MediaMetadata.METADATA_KEY_ALBUM, "$mode · $progress")
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI, QuranAutoBrowse.iconUri(context).toString())
            .putLong(MediaMetadata.METADATA_KEY_DURATION, if (owns(current.playlist?.requestId)) current.durationMs else 0L).build()
    }

    companion object {
        const val PARENT_ID = "quran/hifz"
        const val DAILY_ID = "quran/hifz/daily"
        const val NOTEBOOK_ID = "quran/hifz/notebook"
        const val CONTINUE_ID = "quran/hifz/continue"
        const val LISTEN_ID = "quran/hifz/listen"
        const val ACTION_PREFIX = "quran.hifz/"
        fun handles(id: String) = id in setOf(DAILY_ID, NOTEBOOK_ID, CONTINUE_ID, LISTEN_ID)
        fun choices(context: Context, sessions: HifzSessionCoordinator): List<MediaBrowser.MediaItem> = buildList {
            fun addItem(id: String, title: Int, subtitle: String) = add(MediaBrowser.MediaItem(
                MediaDescription.Builder().setMediaId(id).setTitle(context.getString(title)).setSubtitle(subtitle)
                    .setIconUri(QuranAutoBrowse.iconUri(context)).build(), MediaBrowser.MediaItem.FLAG_PLAYABLE))
            if (sessions.hasSession) addItem(CONTINUE_ID, R.string.quran_auto_review_continue, sessions.view().question)
            addItem(DAILY_ID, R.string.quran_auto_review_daily, context.getString(R.string.quran_auto_review_daily_hint))
            addItem(NOTEBOOK_ID, R.string.quran_auto_review_notebook, context.getString(R.string.quran_auto_review_notebook_hint))
            addItem(LISTEN_ID, R.string.quran_auto_review_listen, context.getString(R.string.quran_auto_review_listen_hint))
        }
    }
}

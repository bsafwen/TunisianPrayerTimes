package com.tunisianprayertimes.quran.hifz

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.snapshots.Snapshot
import com.tunisianprayertimes.quran.QuranCatalog
import com.tunisianprayertimes.quran.QuranRepository
import com.tunisianprayertimes.quran.QuranVerseReference
import com.tunisianprayertimes.quran.audio.*
import com.tunisianprayertimes.ui.*
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** One owner for the existing phone review state machines, independent of a particular screen or service. */
internal object HifzSessions {
    private var instance: HifzSessionCoordinator? = null
    @Synchronized fun get(context: Context): HifzSessionCoordinator = instance
        ?: HifzSessionCoordinator(context.applicationContext).also { instance = it }
}

internal enum class HifzSessionMode { None, Daily, Notebook }
internal enum class HifzSessionPhase { Question, Assessment, Training, Finished }

internal interface HifzSessionAudio {
    fun play(list: QuranPlaylist)
    fun pause()
    fun restore(place: String?)
}

internal data class HifzSessionView(
    val mode: HifzSessionMode,
    val phase: HifzSessionPhase,
    val point: HifzPoint?,
    val index: Int,
    val total: Int,
    val question: String,
    val tally: HifzTally,
    val assisted: Boolean,
    val deferred: Int,
    val token: String,
)

internal class HifzSessionCoordinator(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val now: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = SystemClock::uptimeMillis,
) {
    val reviewIds = mutableStateOf<List<Long>>(emptyList())
    val reviewSession = mutableIntStateOf(0)
    val reviewStartedDay = mutableLongStateOf(todayEpochDay())
    val reviewDayDraft = mutableStateOf<String?>(null)
    val reviewAudio = mutableStateOf(false)
    val reviewPlayed = mutableStateOf(false)
    val reviewReturn = mutableStateOf<String?>(null)
    var reviewState by mutableStateOf(HifzReviewState(clock = elapsed, wallClock = now))
        private set
    var practiceState by mutableStateOf(HifzPracticeState(emptyList(), elapsed))
        private set
    var mode by mutableStateOf(HifzSessionMode.None)
        private set
    var initialized by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var paused by mutableStateOf(true)
        private set
    var carActive by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var phoneAudio: HifzSessionAudio? = null
    var carAudio: HifzSessionAudio? = null
    var catalog: QuranCatalog? = null
        private set
    private var sessionId = UUID.randomUUID().toString()
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()
    private val initialization = Mutex()
    private val persistenceLock = Any()
    private var version = 0L
    private var writtenVersion = 0L
    private var committing = false
    private var pending by mutableStateOf<PendingGrade?>(null)
    private var clip by mutableStateOf<QuranPlaylist?>(null)
    private var clipPosition by mutableLongStateOf(0L)
    private var clipIndex by mutableIntStateOf(0)
    private var clipRound by mutableIntStateOf(1)
    private var clipEnded by mutableStateOf(false)
    private var lastReceiptAt = 0L
    private var learningListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var clipReciterId by mutableStateOf(QuranAudioController.DEFAULT_RECITER_ID)
    private var clipSurah by mutableIntStateOf(0)
    private val preferences by lazy { context.getSharedPreferences("hifz_shared_session", Context.MODE_PRIVATE) }
    private val saverScope = object : SaverScope { override fun canBeSaved(value: Any) = true }

    val hasSession: Boolean get() = mode != HifzSessionMode.None
    val active: Boolean get() = hasSession && !paused
    val savingAnswer: Boolean get() = pending != null || busy
    private fun day() = epochDay(now())
    private fun bridge() = if (carActive) carAudio else phoneAudio

    suspend fun initialize() = initialization.withLock {
        if (initialized) return@withLock
        val saved = withContext(Dispatchers.IO) {
            HifzRepository.load(context)
            QuranAudioController.initialize(context)
            catalog = QuranRepository.load(context)
            preferences.getString("session", null)
        }
        if (saved != null) try { restore(JSONObject(saved)) } catch (_: Exception) {
            // Retain the recovery evidence; the notebook itself is never reset because a session cannot be read.
            withContext(Dispatchers.IO) { preferences.edit().putString("unreadable_session", saved).commit() }
            error = "تعذّر استرجاع موضع المراجعة. احتفظنا بالدفتر ونتائجه المحفوظة."
        }
        initialized = true
        paused = true
        carActive = false
        if (pending != null) finishPending()
        refresh()
        learningListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            scope.launch { if (!busy) { refresh(); changed() } }
        }.also { context.getSharedPreferences("hifz_learning", Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(it) }
        scope.launch {
            snapshotFlow { encode().toString() to busy }.collect {
                if (!busy && pending == null) {
                    runCatching { persist() }.onFailure { error = "تعذّر حفظ موضع المراجعة. أعد المحاولة." }
                    changed()
                }
            }
        }
        scope.launch { HifzRepository.points.collect { if (!busy) { refresh(); changed() } } }
        changed()
    }

    fun dayTally(): HifzTally = reviewDayDraft.value?.split(':', limit = 2)
        ?.takeIf { it.size == 2 && it[0] == day().toString() }?.let { HifzTally.decode(it[1]) } ?: run {
            val points = HifzRepository.points.value
            val checks = points.mapNotNull { p -> p.checks.firstOrNull { it.day == day() } }
            HifzTally(checks.count { it.grade == HifzGrade.Held }, checks.count { it.grade == HifzGrade.Hesitated },
                checks.count { it.grade == HifzGrade.Again }, points.count { it.mastered && it.masteredAt?.let(::epochDay) == day() })
        }

    fun keepDay() { if (mode == HifzSessionMode.Daily) reviewDayDraft.value = "${day()}:${reviewState.day.encode()}" }

    fun startDaily(first: HifzPoint? = null, selectedIds: List<Long>? = null, newRound: Boolean = false): Boolean {
        if (!initialized || savingAnswer) return false
        refresh()
        if (!newRound && first == null && selectedIds == null && mode == HifzSessionMode.Daily && !view().let { it.phase == HifzSessionPhase.Finished }) {
            resume(); return true
        }
        keepDay()
        val learning = HifzLearning.read(context, day())
        val eligible = HifzRepository.points.value.askable(day(), now(), learning.seen, learning.exposed)
        val chosen = (selectedIds?.let { ids -> eligible.filter { it.id in ids } } ?: eligible)
            .reviewBatch(day(), now(), HifzRoundSize, first?.let { p -> eligible.firstOrNull { it.id == p.id } })
        if (chosen.isEmpty()) { error = "لا توجد أسئلة مستحقة الآن. تبقى مواعيد المراجعة كما هي."; changed(); return false }
        captureListening()
        reviewState = HifzReviewState(elapsed, dayTally(), learning.exposed, learning.seen, now)
        reviewIds.value = chosen.map { it.id }
        reviewStartedDay.longValue = day()
        reviewSession.intValue++
        mode = HifzSessionMode.Daily
        sessionId = UUID.randomUUID().toString()
        paused = false
        error = null
        HifzReminder.clearReminder(context)
        refresh(); changed()
        return true
    }

    fun startNotebook(order: List<Long>? = null): Boolean {
        if (!initialized || savingAnswer) return false
        val points = HifzRepository.points.value
        val ids = order ?: points.sortedBy { it.verse }.map { it.id }
        if (mode == HifzSessionMode.Notebook && order == null && practiceState.index < practiceState.cards(points).size) {
            resume(); return true
        }
        val learning = HifzLearning.read(context, day())
        val due = points.askable(day(), now(), learning.seen, learning.exposed).map { it.id }.toSet()
        val plan = hifzPracticePlan(points, ids, due)
        if (plan.points.isEmpty()) { error = "دفتر المراجعة فارغ. أضف مواضعك من الهاتف حين يكون ذلك آمنًا."; changed(); return false }
        keepDay(); captureListening()
        practiceState = HifzPracticeState(plan.points.map { it.id }, elapsed)
        mode = HifzSessionMode.Notebook
        sessionId = UUID.randomUUID().toString()
        paused = false
        error = null
        refresh(); changed()
        return true
    }

    fun usePhone() { carActive = false; paused = false; changed() }
    fun useCar() { carActive = true; paused = false; changed() }
    fun leaveCar() { carActive = false; changed() }
    fun audioError(message: String) { error = message; changed() }
    fun resume() { if (!savingAnswer) { captureListening(); paused = false; reviewState.resume(); refresh(); changed() } else retry() }
    fun pause() {
        bridge()?.pause()
        notePlayback(QuranAudioController.state.value, force = true)
        paused = true
        changed()
        scope.launch { runCatching { persist() } }
    }

    /** A closed surface keeps an unfinished attempt for the other surface. */
    fun close() {
        if (savingAnswer) return
        keepDay()
        pause()
        bridge()?.restore(reviewReturn.value)
        reviewReturn.value = null
        reviewPlayed.value = false
        if (view().phase == HifzSessionPhase.Finished && !reviewState.stopping) mode = HifzSessionMode.None
        carActive = false
        changed()
    }

    fun clear() {
        if (busy) return
        mode = HifzSessionMode.None
        pending = null
        reviewIds.value = emptyList()
        reviewSession.intValue++
        reviewState = HifzReviewState(clock = elapsed, wallClock = now)
        practiceState = HifzPracticeState(emptyList(), elapsed)
        clip = null
        reviewReturn.value = null
        reviewPlayed.value = false
        reviewAudio.value = false
        paused = true
        carActive = false
        changed()
    }

    fun refresh() {
        if (!initialized || busy) return
        if (mode == HifzSessionMode.Daily && reviewStartedDay.longValue != day()) {
            keepDay()
            reviewIds.value = emptyList()
            mode = HifzSessionMode.None
            paused = true
            reviewDayDraft.value = null
            error = "بدأ يوم جديد، فجدّدنا أسئلة مراجعة اليوم."
            return
        }
        val learning = HifzLearning.read(context, day())
        reviewState.syncExposed(learning.exposed, reviewActions, learning.seen)
        if (mode == HifzSessionMode.Daily) {
            reviewState.follow(queue(), reviewActions)
            reviewState.shown(reviewState.point(queue()))
        } else if (mode == HifzSessionMode.Notebook) {
            val cards = practiceState.cards(HifzRepository.points.value)
            practiceState.keepWithin(cards.size)
            practiceState.shown(cards.getOrNull(practiceState.index))
        }
    }

    private fun queue() = reviewState.questions(reviewIds.value.mapNotNull { id -> HifzRepository.points.value.firstOrNull { it.id == id } })

    fun view(): HifzSessionView {
        val daily = mode == HifzSessionMode.Daily
        val cards = if (daily) queue() else practiceState.cards(HifzRepository.points.value)
        val index = if (daily) reviewState.index(cards) else practiceState.index
        val point = cards.getOrNull(index)
        val phase = when {
            mode == HifzSessionMode.None || point == null || daily && reviewState.atEnd(cards) -> HifzSessionPhase.Finished
            daily && reviewState.training(point) || !daily && practiceState.answer(point) in listOf(HifzGrade.Hesitated, HifzGrade.Again) -> HifzSessionPhase.Training
            if (daily) reviewState.revealed(point) else practiceState.revealed(point) -> HifzSessionPhase.Assessment
            else -> HifzSessionPhase.Question
        }
        val question = point?.let { p -> catalog?.let { hifzQuestionAbout(it, p) } }.orEmpty()
        return HifzSessionView(mode, phase, point, index, if (daily) reviewState.count(cards) else cards.size,
            question, if (daily) reviewState.round else practiceState.tally, daily && reviewState.assisted(point),
            if (daily) reviewState.deferredIds.size else 0, "$sessionId:${mode.name}:${point?.id}:$phase")
    }

    fun reveal(): Boolean {
        if (savingAnswer) return false
        if (mode == HifzSessionMode.Daily) reviewState.reveal(queue(), reviewActions)
        else view().point?.let { practiceState.reveal(it, practiceActions) }
        refresh(); changed()
        return view().phase == HifzSessionPhase.Assessment
    }

    fun assess(grade: HifzGrade) {
        if (savingAnswer || view().phase != HifzSessionPhase.Assessment) return
        if (mode == HifzSessionMode.Daily) reviewState.grade(queue(), reviewActions, grade)
        else view().point?.let { point ->
            val counted = practiceState.counted(point)
            val outcome = hifzPracticeOutcome(point, grade, counted, "في تذكير لاحق", "غدًا")
                ?.text(withBack = true, short = false).orEmpty()
            practiceState.answer(point, grade, outcome, practiceState.cards(HifzRepository.points.value).size, practiceActions)
        }
        changed()
    }

    fun next() {
        if (savingAnswer) return
        if (mode == HifzSessionMode.Daily) {
            if (view().phase == HifzSessionPhase.Training) reviewState.next(queue(), reviewActions)
        } else practiceState.step(1, practiceState.cards(HifzRepository.points.value).size, practiceActions)
        refresh(); changed()
    }

    fun previous() {
        if (!savingAnswer && mode == HifzSessionMode.Notebook) practiceState.step(-1, practiceState.cards(HifzRepository.points.value).size, practiceActions)
        refresh(); changed()
    }

    fun list(kind: String): QuranPlaylist? = view().point?.let { point -> catalog?.let { index -> when (kind) {
        "cue" -> hifzCue(index, point)
        "drill" -> hifzDrill(index, point)
        else -> hifzListen(index, listOf(point))
    } } }

    fun cue() { view().point?.let { reviewActions.listenBefore(it) } }
    fun answerAudio() { view().point?.let { reviewActions.listen(it) } }
    fun drill() {
        view().point?.let { if (mode == HifzSessionMode.Daily) reviewState.drill(it, reviewActions) else practiceState.drill(it, practiceActions) }
        changed()
    }

    private fun play(list: QuranPlaylist?) {
        list ?: return
        captureListening()
        reviewAudio.value = true
        reviewPlayed.value = true
        clip = list.copy(requestId = "hifz-session:$sessionId:${UUID.randomUUID()}")
        clipReciterId = QuranAudioController.state.value.reciterId
        clipPosition = 0L; clipIndex = 0; clipRound = 1; clipEnded = false
        clipSurah = list.items.first().from.surah
        bridge()?.play(requireNotNull(clip))
        changed()
    }

    fun owns(requestId: String?) = requestId != null && requestId == clip?.requestId
    fun resumableClip(): Triple<QuranPlaylist, Int, Int>? = clip?.takeUnless { clipEnded }?.let { Triple(it, clipIndex, clipRound) }
    fun savedClipPosition() = clipPosition
    fun savedClipReciter() = clipReciterId
    fun savedClipSurah(): Int? = clipSurah.takeIf { it in 1..114 }

    fun notePlayback(state: QuranPlaybackState, force: Boolean = false) {
        if (!clipEnded && owns(state.finishedPlaylist?.requestId)) { clipEnded = true; changed() }
        if (!owns(state.playlist?.requestId)) return
        if (force || !state.playing || state.positionMs / 5_000L != clipPosition / 5_000L || clipIndex != state.playlistIndex) {
            clipPosition = state.positionMs
            clipIndex = state.playlistIndex
            clipRound = state.playlistRound.coerceAtLeast(1)
            clipSurah = state.surah ?: 0
        }
    }

    fun captureListening() {
        if (reviewReturn.value != null || reviewPlayed.value) return
        val state = QuranAudioController.state.value
        val surah = state.surah ?: return
        if (state.playlist != null || state.notDownloaded || state.loading || state.error != null) return
        reviewReturn.value = listOf(state.reciterId, surah, state.positionMs, state.repeat?.encode(state.repeatRound).orEmpty(), if (state.playing) 1 else 0).joinToString("|")
        if (state.playing) bridge()?.pause()
    }

    val reviewActions: HifzReviewActions = object : HifzReviewActions {
        override fun grade(point: HifzPoint, grade: HifzGrade) = Unit // The durable receipt already wrote it.
        override fun tryGrade(point: HifzPoint, grade: HifzGrade): Boolean {
            if (committing) return true
            requestGrade(point, grade, counted = true, outcome = "")
            return false
        }
        override fun seen(verses: Set<QuranVerseReference>) = expose(verses)
        override fun exposed(verses: Set<QuranVerseReference>) = expose(verses)
        override fun listenBefore(point: HifzPoint) { play(catalog?.let { hifzCue(it, point) }) }
        override fun listen(point: HifzPoint) { play(catalog?.let { hifzListen(it, listOf(point)) }) }
        override fun drill(point: HifzPoint) { play(catalog?.let { hifzDrill(it, point) }) }
        override fun leaveCard() { if (reviewAudio.value) bridge()?.pause(); reviewAudio.value = false; clip = null }
        override fun more() { startDaily(newRound = true) }
        override fun listenTo(ids: List<Long>) { play(catalog?.let { hifzListen(it, HifzRepository.points.value.filter { p -> p.id in ids }) }) }
        override fun dismiss() { close() }
    }

    val practiceActions: HifzPracticeActions = object : HifzPracticeActions {
        override fun dueNow(point: HifzPoint): Boolean {
            val learning = HifzLearning.read(context, day())
            return HifzRepository.points.value.firstOrNull { it.id == point.id }?.isDue(day(), now(), learning.seen, learning.exposed) == true
        }
        override fun seen(verses: Set<QuranVerseReference>) = expose(verses)
        override fun grade(point: HifzPoint, grade: HifzGrade, counted: Boolean) = Unit
        override fun tryGrade(point: HifzPoint, grade: HifzGrade, counted: Boolean, outcome: String): Boolean {
            if (committing) return true
            requestGrade(point, grade, counted, outcome)
            return false
        }
        override fun listenBefore(point: HifzPoint) = reviewActions.listenBefore(point)
        override fun listen(point: HifzPoint) = reviewActions.listen(point)
        override fun drill(point: HifzPoint) = reviewActions.drill(point)
        override fun addWords(point: HifzPoint, words: Set<Int>) = Unit // Phone editor owns word selection.
        override fun leaveCard() = reviewActions.leaveCard()
        override fun close() { this@HifzSessionCoordinator.close() }
    }

    private fun expose(verses: Set<QuranVerseReference>) {
        if (verses.isEmpty()) return
        HifzLearning.read(context, day()).expose(verses, now()).write(context)
        changed()
    }

    private fun requestGrade(point: HifzPoint, grade: HifzGrade, counted: Boolean, outcome: String) {
        if (!initialized || savingAnswer) return
        val stamp = maxOf(now(), lastReceiptAt + 1)
        lastReceiptAt = stamp
        pending = PendingGrade(mode, point, grade, counted, outcome, day(), stamp)
        reviewState.inputBlocked = true
        practiceState.inputBlocked = true
        scope.launch { finishPending() }
    }

    private suspend fun finishPending() {
        val receipt = pending ?: return
        if (busy) return
        busy = true
        reviewState.inputBlocked = true
        practiceState.inputBlocked = true
        changed()
        try {
            persist() // Journal the explicit response before changing the notebook.
            val saved = withContext(Dispatchers.IO) {
                if (receipt.counted || receipt.grade == HifzGrade.Again) HifzRepository.gradeReview(context, receipt.point, receipt.grade, receipt.day, receipt.at)
                else HifzRepository.points.value.any { it.id == receipt.point.id && it.verse == receipt.point.verse }
            }
            if (!saved) {
                pending = null
                error = "تغيّر هذا الموضع أو حُذف. لم نسجّل إجابة جديدة عليه."
            } else if (!receipt.applied) {
                committing = true
                reviewState.inputBlocked = false
                practiceState.inputBlocked = false
                if (receipt.mode == HifzSessionMode.Daily && reviewState.point(queue())?.id == receipt.point.id) {
                    reviewState.grade(queue(), reviewActions, receipt.grade, confirmed = true)
                    keepDay()
                } else if (receipt.mode == HifzSessionMode.Notebook) {
                    practiceState.answer(receipt.point, receipt.grade, receipt.outcome,
                        practiceState.cards(HifzRepository.points.value).size, practiceActions, confirmed = true)
                }
                receipt.applied = true
            }
            pending = null
            persist()
            if (saved) error = null
            scope.launch(Dispatchers.IO) { runCatching { HifzReminder.refresh(context) } }
        } catch (_: IOException) {
            pending = receipt
            error = "تعذّر حفظ الإجابة. أعد المحاولة؛ لن تُحسب الإجابة مرتين."
        } finally {
            committing = false
            busy = false
            reviewState.inputBlocked = pending != null
            practiceState.inputBlocked = pending != null
            refresh(); changed()
        }
    }

    fun retry() { if (!busy) scope.launch { if (pending != null) finishPending() else { error = null; persist() } } }
    fun dismissError() { error = null; changed() }

    private fun changed() { Snapshot.sendApplyNotifications(); revision.update { it + 1 } }

    private fun <T : Any> saved(saver: Saver<T, Any>, state: T): JSONArray =
        JSONArray((with(saver) { saverScope.save(state) } as? List<*>) ?: emptyList<Any>())
    private fun <T : Any> restored(saver: Saver<T, Any>, rows: JSONArray): T? =
        saver.restore(List(rows.length()) { rows.optString(it) })

    private fun encode() = JSONObject().put("version", 1).put("id", sessionId).put("mode", mode.name)
        .put("dailyIds", JSONArray(reviewIds.value)).put("generation", reviewSession.intValue)
        .put("day", reviewStartedDay.longValue).put("dayDraft", reviewDayDraft.value)
        .put("review", saved(HifzReviewState.Saver, reviewState)).put("practice", saved(HifzPracticeState.Saver, practiceState))
        .put("return", reviewReturn.value).put("played", reviewPlayed.value).put("clip", clip?.encode())
        .put("position", clipPosition).put("clipIndex", clipIndex).put("clipRound", clipRound).put("clipEnded", clipEnded)
        .put("clipReciter", clipReciterId)
        .put("clipSurah", clipSurah)
        .put("pending", pending?.encode())

    private fun restore(row: JSONObject) {
        require(row.getInt("version") == 1)
        sessionId = row.getString("id")
        mode = HifzSessionMode.valueOf(row.getString("mode"))
        reviewIds.value = row.getJSONArray("dailyIds").let { ids -> List(ids.length()) { ids.getLong(it) } }
        reviewSession.intValue = row.optInt("generation")
        reviewStartedDay.longValue = row.getLong("day")
        reviewDayDraft.value = row.opt("dayDraft") as? String
        reviewState = restored(HifzReviewState.Saver, row.getJSONArray("review")) ?: error("Invalid review state")
        practiceState = restored(HifzPracticeState.Saver, row.getJSONArray("practice")) ?: error("Invalid practice state")
        reviewReturn.value = row.opt("return") as? String
        reviewPlayed.value = row.optBoolean("played")
        clip = QuranPlaylist.decode(row.opt("clip") as? String)
        clipPosition = row.optLong("position")
        clipIndex = row.optInt("clipIndex")
        clipRound = row.optInt("clipRound", 1)
        clipEnded = row.optBoolean("clipEnded")
        clipReciterId = row.optString("clipReciter", QuranAudioController.DEFAULT_RECITER_ID)
        clipSurah = row.optInt("clipSurah")
        pending = row.optJSONObject("pending")?.let(PendingGrade::decode)
    }

    suspend fun persist() {
        if (!initialized) return
        val encoded = encode().toString()
        val request = ++version
        withContext(Dispatchers.IO) {
            synchronized(persistenceLock) {
                if (request > writtenVersion) {
                    if (!preferences.edit().putString("session", encoded).commit()) throw IOException("Session commit failed")
                    writtenVersion = request
                }
            }
        }
    }

    fun dispose() {
        learningListener?.let { context.getSharedPreferences("hifz_learning", Context.MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(it) }
        scope.cancel()
    }

    private data class PendingGrade(val mode: HifzSessionMode, val point: HifzPoint, val grade: HifzGrade,
        val counted: Boolean, val outcome: String, val day: Long, val at: Long, var applied: Boolean = false) {
        fun encode() = JSONObject().put("mode", mode.name).put("point", encodeHifzPoints(listOf(point)))
            .put("grade", grade.name).put("counted", counted).put("outcome", outcome).put("day", day).put("at", at).put("applied", applied)
        companion object {
            fun decode(row: JSONObject) = PendingGrade(HifzSessionMode.valueOf(row.getString("mode")),
                decodeHifzPoints(row.getString("point")).single(), HifzGrade.valueOf(row.getString("grade")),
                row.getBoolean("counted"), row.getString("outcome"), row.getLong("day"), row.getLong("at"), row.optBoolean("applied"))
        }
    }
}
